package com.group33.server.occurrence

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.auth.BoundPhoneBearer
import com.group33.server.auth.BoundPhoneBearerFilter
import com.group33.server.auth.BoundPhoneInfrastructure
import com.group33.server.config.ApiSecurityHandlers
import com.group33.server.config.PersistenceRuntime
import com.group33.server.pairing.CodeStoreBusy
import com.group33.server.pairing.PostgresPairingCodeStore
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.FieldError
import jakarta.servlet.http.HttpServletRequest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.web.bind.annotation.*

internal class BoundPhoneOccurrenceOperations(runtime: PersistenceRuntime,
                                              private val infrastructure: BoundPhoneInfrastructure) {
    private val postgres = checkNotNull(runtime.pairing as? PostgresPairingCodeStore)
    private val registrations = OccurrenceRegistrationService(postgres, infrastructure.clock)
    private val doseGroups = DebugDoseGroups(postgres, infrastructure.clock)

    fun register(token: String, elderId: String, key: UUID, input: OccurrenceRegistrationRequest) =
        registrations.registerPhone(postgres, infrastructure.jwt, token, elderId, key, input)
    fun submitDoseGroup(token: String, elderId: String, input: DebugDoseGroupRequest) =
        doseGroups.submitPhone(postgres, infrastructure.jwt, token, elderId, input)
    fun readDoseEvents(token: String, elderId: String, from: LocalDate, through: LocalDate) =
        doseGroups.readPhone(postgres, infrastructure.jwt, token, elderId, from, through)
}

@RestController
@ConditionalOnProperty(name = ["app.bound-phone.enabled", "app.occurrence.enabled"], havingValue = "true")
internal class BoundPhoneOccurrenceController(
    private val operations: BoundPhoneOccurrenceOperations,
    mapper: ObjectMapper,
) {
    private val reader = mapper.readerFor(JsonNode::class.java).with(
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @PostMapping("/api/v1/elders/{elderId}/occurrence-registrations", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun register(@PathVariable elderId: String, @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> = try {
        val token = BoundPhoneBearer.token(request)
        if (request.parameterMap.isNotEmpty()) malformed()
        val key = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER)).singleOrNull()
            ?.takeIf { UUID_PATTERN.matches(it) } ?: malformed()
        when (val result = operations.register(token, elderId, UUID.fromString(key), decode(body))) {
            is RegistrationResult.Accepted -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.response)
            is RegistrationResult.Rejected -> when (result.reason) {
                RegistrationRejection.UNAUTHENTICATED -> failure(HttpStatus.UNAUTHORIZED, result.reason.name)
                RegistrationRejection.RESOURCE_NOT_ACCESSIBLE -> failure(HttpStatus.NOT_FOUND, result.reason.name)
                RegistrationRejection.ROLE_NOT_ALLOWED -> failure(HttpStatus.FORBIDDEN, result.reason.name)
                RegistrationRejection.VALIDATION_FAILED -> failure(HttpStatus.UNPROCESSABLE_ENTITY, result.reason.name,
                    fields = result.violations.map { FieldError(it.field, it.reason) })
                RegistrationRejection.IDEMPOTENCY_KEY_REUSED -> failure(HttpStatus.CONFLICT, result.reason.name)
                RegistrationRejection.OPERATION_IN_PROGRESS -> failure(HttpStatus.CONFLICT, result.reason.name, true, "1")
                RegistrationRejection.SERVICE_UNAVAILABLE -> failure(HttpStatus.SERVICE_UNAVAILABLE, result.reason.name, true)
            }
        }
    } catch (exception: ApiException) {
        failure(exception.status, exception.code)
    } catch (exception: Exception) {
        if (exception is InterruptedException) Thread.currentThread().interrupt()
        failure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", true)
    }

    private fun decode(body: String): OccurrenceRegistrationRequest {
        val root: JsonNode? = try { reader.readValue(body) } catch (_: JsonProcessingException) { malformed() }
        exact(root, setOf("items"))
        val items = root!!["items"].takeIf(JsonNode::isArray) ?: malformed()
        return OccurrenceRegistrationRequest(items.map { node ->
            exact(node, EVIDENCE_FIELDS)
            val occurrence = node["occurrence"]
            exact(occurrence, setOf("planId", "localDate", "localTime"))
            val policy = TimeResolutionPolicy.entries.singleOrNull { it.name == text(node, "resolutionPolicyVersion") }
                ?: malformed()
            OccurrenceGenerationEvidence(text(node, "generationId"),
                OccurrenceKey(text(occurrence, "planId"), text(occurrence, "localDate"), text(occurrence, "localTime")),
                long(node, "planRevision"), long(node, "generatedAtEpochMillis"), text(node, "executionTimeZoneId"),
                long(node, "plannedAtEpochMillis"), node["resolvedOffsetSeconds"].takeIf {
                    it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: malformed(), policy,
                text(node, "sourceSnapshotVersion"), long(node, "sourceSnapshotServerAtEpochMillis"))
        })
    }

    private fun exact(node: JsonNode?, fields: Set<String>) {
        if (node == null || !node.isObject || node.fieldNames().asSequence().toSet() != fields) malformed()
    }
    private fun text(node: JsonNode, field: String) = node[field].takeIf(JsonNode::isTextual)?.textValue() ?: malformed()
    private fun long(node: JsonNode, field: String) = node[field].takeIf {
        it.isIntegralNumber && it.canConvertToLong() }?.longValue() ?: malformed()
    private fun failure(status: HttpStatus, code: String, retryable: Boolean = false,
                        retryAfter: String? = null, fields: List<FieldError>? = null): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Request could not be completed", retryable, fieldErrors = fields)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", body.requestId)
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        if (retryAfter != null) response.header("Retry-After", retryAfter)
        return response.body(body)
    }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed registration")
    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val EVIDENCE_FIELDS = setOf("generationId", "occurrence", "planRevision", "generatedAtEpochMillis",
            "executionTimeZoneId", "plannedAtEpochMillis", "resolvedOffsetSeconds", "resolutionPolicyVersion",
            "sourceSnapshotVersion", "sourceSnapshotServerAtEpochMillis")
    }
}

@RestController
@ConditionalOnProperty(name = ["app.bound-phone.enabled", "app.dose-group.enabled"], havingValue = "true")
internal class BoundPhoneDoseGroupController(
    private val operations: BoundPhoneOccurrenceOperations,
    mapper: ObjectMapper,
) {
    private val reader = mapper.readerFor(JsonNode::class.java).with(
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @PostMapping("/api/v1/elders/{elderId}/dose-action-groups", consumes = ["application/json"])
    fun submit(@PathVariable elderId: String, @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> = reply {
        val token = BoundPhoneBearer.token(request)
        val key = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER)).singleOrNull()
        if (request.parameterMap.isNotEmpty() || body.length > 32_768 ||
            key == null || canonicalDoseUuid(key) == null) malformed()
        val input = decode(body)
        if (input.actionId != key) malformed()
        operations.submitDoseGroup(token, elderId, input)
    }

    @GetMapping("/api/v1/elders/{elderId}/dose-events")
    fun events(@PathVariable elderId: String, @RequestParam fromDate: String,
               @RequestParam throughDate: String, request: HttpServletRequest): ResponseEntity<*> = reply {
        val token = BoundPhoneBearer.token(request)
        if (request.parameterMap.keys != setOf("fromDate", "throughDate") ||
            request.getParameterValues("fromDate")?.size != 1 ||
            request.getParameterValues("throughDate")?.size != 1) malformed()
        val from = date(fromDate)
        val through = date(throughDate)
        if (through < from || ChronoUnit.DAYS.between(from, through) >= 90) malformed()
        ItemsResponse(operations.readDoseEvents(token, elderId, from, through))
    }

    private fun decode(body: String): DebugDoseGroupRequest {
        val root: JsonNode = try { reader.readValue<JsonNode?>(body) ?: malformed() }
            catch (_: JsonProcessingException) { malformed() }
        if (!root.isObject || root.fieldNames().asSequence().toSet() !=
            setOf("actionId", "occurrences", "kind", "recordedAtEpochMillis")) malformed()
        val kind = DoseActionKind.entries.singleOrNull { it.name == text(root, "kind") } ?: malformed()
        val recordedAt = root["recordedAtEpochMillis"].takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.longValue() ?: malformed()
        val occurrences = root["occurrences"].takeIf { it.isArray && it.size() in 1..100 } ?: malformed()
        return DebugDoseGroupRequest(text(root, "actionId"), occurrences.map { node ->
            if (!node.isObject || node.fieldNames().asSequence().toSet() != setOf("planId", "localDate", "localTime")) malformed()
            OccurrenceKey(text(node, "planId"), text(node, "localDate"), text(node, "localTime"))
        }, kind, recordedAt)
    }

    private fun text(node: JsonNode, field: String) = node[field]?.takeIf { it.isTextual }?.textValue() ?: malformed()
    private fun date(value: String): LocalDate =
        runCatching { LocalDate.parse(value).also { require(it.toString() == value && value.length == 10) } }
            .getOrElse { malformed() }
    private fun reply(block: () -> Any): ResponseEntity<*> = try {
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(block())
    } catch (error: ApiException) {
        failure(error.status, error.code, false)
    } catch (_: CodeStoreBusy) {
        failure(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true)
    }
    private fun failure(status: HttpStatus, code: String, retryable: Boolean): ResponseEntity<*> {
        val error = ApiErrors.body(code, "Dose group request failed", retryable)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", error.requestId)
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        if (code == "OPERATION_IN_PROGRESS") response.header("Retry-After", "1")
        return response.body(error)
    }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed dose group request")
}

private fun canonicalDoseUuid(value: String): UUID? =
    runCatching { UUID.fromString(value).takeIf { it.toString() == value } }.getOrNull()

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.bound-phone.enabled", "app.occurrence.enabled"], havingValue = "true")
internal class BoundPhoneOccurrenceConfiguration {
    @Bean fun boundPhoneOccurrenceOperations(runtime: PersistenceRuntime, infrastructure: BoundPhoneInfrastructure) =
        BoundPhoneOccurrenceOperations(runtime, infrastructure)

    @Bean @Order(1)
    fun boundPhoneOccurrenceSecurity(http: HttpSecurity, infrastructure: BoundPhoneInfrastructure, errors: ApiSecurityHandlers): SecurityFilterChain = http
        .securityMatchers {
            it.requestMatchers(HttpMethod.POST, "/api/v1/elders/*/occurrence-registrations")
        }
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().authenticated() }
        .exceptionHandling { it.authenticationEntryPoint(errors); it.accessDeniedHandler(errors) }
        .addFilterBefore(BoundPhoneBearerFilter(infrastructure.jwt, errors), AuthorizationFilter::class.java)
        .build()
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.bound-phone.enabled", "app.dose-group.enabled"], havingValue = "true")
internal class BoundPhoneDoseGroupConfiguration {
    @Bean @Order(1)
    fun boundPhoneDoseGroupSecurity(http: HttpSecurity, infrastructure: BoundPhoneInfrastructure, errors: ApiSecurityHandlers): SecurityFilterChain = http
        .securityMatchers {
            it.requestMatchers(HttpMethod.POST, "/api/v1/elders/*/dose-action-groups")
            it.requestMatchers(HttpMethod.GET, "/api/v1/elders/*/dose-events")
        }
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().authenticated() }
        .exceptionHandling { it.authenticationEntryPoint(errors); it.accessDeniedHandler(errors) }
        .addFilterBefore(BoundPhoneBearerFilter(infrastructure.jwt, errors), AuthorizationFilter::class.java)
        .build()
}
