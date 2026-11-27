package com.group33.server.pairing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.auth.BoundPhoneBearer
import com.group33.server.auth.BoundPhoneBearerFilter
import com.group33.server.auth.BoundPhoneInfrastructure
import com.group33.server.auth.BoundPhoneRuntimeSettings
import com.group33.server.auth.PhoneAuthFailure
import com.group33.server.config.ApiSecurityHandlers
import com.group33.server.config.PersistenceRuntime
import com.group33.server.plan.PlanPhoneUnauthenticated
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.RequestPairingRequest
import com.group33.shared.model.ApiError
import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress
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

internal class BoundPhonePairingOperations(
    runtime: PersistenceRuntime,
    generator: PairingCodeGenerator,
    private val settings: BoundPhoneRuntimeSettings,
    private val infrastructure: BoundPhoneInfrastructure,
) {
    private val postgres = checkNotNull(runtime.pairing as? PostgresPairingCodeStore)
    private val issuance = PairingCodeIssuanceService(postgres, infrastructure.clock, generator)
    private val requests = PairingRequestService(postgres, infrastructure.clock)
    private val decisions = PairingDecisionService(postgres, infrastructure.clock)
    private val directory = PairingDirectoryService(postgres, infrastructure.clock)

    fun issue(token: String, key: UUID) = issuance.issuePhone(postgres, infrastructure.jwt, token, key)
    fun request(token: String, key: UUID, input: RequestPairingRequest, address: InetAddress) =
        requests.requestPhone(postgres, infrastructure.jwt, token, key, input, address)
    fun detail(token: String, id: String) = requests.detailPhone(postgres, infrastructure.jwt, token, id)
    fun accept(token: String, id: String, key: UUID) = decisions.acceptPhone(postgres, infrastructure.jwt, token, id, key)
    fun reject(token: String, id: String, key: UUID) = decisions.rejectPhone(postgres, infrastructure.jwt, token, id, key)
    fun cancel(token: String, id: String, key: UUID) = decisions.cancelPhone(postgres, infrastructure.jwt, token, id, key)
    fun revoke(token: String, id: String, key: UUID) = decisions.revokePhone(postgres, infrastructure.jwt, token, id, key)
    fun listRequests(token: String, status: PairingRequestStatus?) =
        directory.requestsPhone(postgres, infrastructure.jwt, token, status)
    fun listRelationships(token: String) = directory.relationshipsPhone(postgres, infrastructure.jwt, token)
    fun deleteCode(token: String, id: String, key: UUID) =
        directory.deleteCodePhone(postgres, infrastructure.jwt, token, id, key)
    fun clientAddress(request: HttpServletRequest): InetAddress = try {
        if (request.getHeaders("X-Forwarded-For").hasMoreElements()) {
            settings.rateLimit.resolveClientInetAddress(request)
        } else {
            directPeer(request)
        }
    } catch (failure: PhoneAuthFailure) {
        throw ApiException(failure.status, failure.code, "Malformed forwarding transport")
    }

    private fun directPeer(request: HttpServletRequest): InetAddress {
        val value = request.remoteAddr.orEmpty()
        try {
            if (Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(value)) {
                val parts = value.split('.').map(String::toInt)
                if (parts.all { it in 0..255 }) return InetAddress.getByAddress(parts.map(Int::toByte).toByteArray())
            } else if (':' in value && Regex("[0-9a-fA-F:.]+(%[0-9]+)?").matches(value)) {
                return InetAddress.getByName(value)
            }
        } catch (_: IllegalArgumentException) { unavailable() }
        catch (_: java.net.UnknownHostException) { unavailable() }
        unavailable()
    }
    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Peer unavailable")
}

@RestController
@ConditionalOnProperty(name = ["app.bound-phone.enabled"], havingValue = "true")
internal class BoundPhonePairingController(private val pairing: BoundPhonePairingOperations, mapper: ObjectMapper) {
    private val reader = mapper.readerFor(JsonNode::class.java).with(
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @PostMapping("/api/v1/pairing-codes", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun issue(@RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        val operation = key(request)
        emptyBody(body)
        return safely {
            when (val result = pairing.issue(token, operation)) {
                is CodeIssuanceResult.Issued -> ResponseEntity.status(HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore()).body(result.view)
                is CodeIssuanceResult.Rejected -> when (result.reason) {
                    CodeIssueRejection.UNAUTHENTICATED -> error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
                    CodeIssueRejection.ROLE_FORBIDDEN -> error(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED")
                    CodeIssueRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
                    CodeIssueRejection.RATE_LIMITED -> error(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER", true, result.retryAfterSeconds)
                    CodeIssueRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true, 1)
                    CodeIssueRejection.CODE_GENERATION_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", true)
                }
            }
        }
    }

    @PostMapping("/api/v1/pairing-requests", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        val operation = key(request)
        val node = objectBody(body)
        if (node.size() != 1 || node.get("code")?.isTextual != true) malformed()
        val address = pairing.clientAddress(request)
        return safely { requestResult(pairing.request(token, operation, RequestPairingRequest(node["code"].textValue()), address), HttpStatus.CREATED) }
    }

    @GetMapping("/api/v1/pairing-requests/{id}")
    fun detail(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        noQuery(request)
        return safely { requestResult(pairing.detail(token, id), HttpStatus.OK) }
    }

    @PostMapping("/api/v1/pairing-requests/{id}/accept", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun accept(@PathVariable id: String, @RequestBody body: String, request: HttpServletRequest) =
        decide(id, body, request, PairingDecision.ACCEPT)

    @PostMapping("/api/v1/pairing-requests/{id}/reject", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun reject(@PathVariable id: String, @RequestBody body: String, request: HttpServletRequest) =
        decide(id, body, request, PairingDecision.REJECT)

    @DeleteMapping("/api/v1/pairing-requests/{id}")
    fun cancel(@PathVariable id: String, @RequestBody(required = false) body: String?, request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        val operation = key(request)
        if (body != null) {
            if (request.contentType?.substringBefore(';')?.trim() != MediaType.APPLICATION_JSON_VALUE)
                return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
            emptyBody(body)
        }
        return safely { decisionResult(pairing.cancel(token, id, operation)) }
    }

    @PostMapping("/api/v1/care-relationships/{id}/revoke", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun revoke(@PathVariable id: String, @RequestBody body: String, request: HttpServletRequest) =
        decide(id, body, request, PairingDecision.REVOKE)

    @GetMapping("/api/v1/pairing-requests")
    fun requests(request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        if (request.parameterMap.keys.any { it != "status" }) malformed()
        val values = request.getParameterValues("status")
        val status = if (values == null) null else {
            if (values.size != 1) malformed()
            try { PairingRequestStatus.valueOf(values.single()) } catch (_: IllegalArgumentException) { malformed() }
        }
        return safely { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pairing.listRequests(token, status)) }
    }

    @GetMapping("/api/v1/care-relationships")
    fun relationships(request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        noQuery(request)
        return safely { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pairing.listRelationships(token)) }
    }

    @DeleteMapping("/api/v1/pairing-codes/{id}")
    fun deleteCode(@PathVariable id: String, @RequestBody(required = false) body: String?, request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        val operation = key(request)
        if (body != null) {
            if (request.contentType?.substringBefore(';')?.trim() != MediaType.APPLICATION_JSON_VALUE)
                return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
            emptyBody(body)
        }
        return safely {
            when (val result = pairing.deleteCode(token, id, operation)) {
                CodeDeletionResult.Deleted -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build<Void>()
                is CodeDeletionResult.Rejected -> when (result.reason) {
                    CodeDeletionRejection.UNAUTHENTICATED -> error(HttpStatus.UNAUTHORIZED, result.reason.name)
                    CodeDeletionRejection.ROLE_NOT_ALLOWED -> error(HttpStatus.FORBIDDEN, result.reason.name)
                    CodeDeletionRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, result.reason.name)
                    CodeDeletionRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, result.reason.name)
                    CodeDeletionRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, result.reason.name, true, 1)
                }
            }
        }
    }

    private fun decide(id: String, body: String, request: HttpServletRequest, action: PairingDecision): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        val operation = key(request)
        emptyBody(body)
        return safely {
            decisionResult(when (action) {
                PairingDecision.ACCEPT -> pairing.accept(token, id, operation)
                PairingDecision.REJECT -> pairing.reject(token, id, operation)
                PairingDecision.REVOKE -> pairing.revoke(token, id, operation)
                PairingDecision.CANCEL -> error("Cancellation uses its DELETE route")
            })
        }
    }

    private fun requestResult(result: PairingRequestResult, success: HttpStatus): ResponseEntity<*> = when (result) {
        is PairingRequestResult.Success -> ResponseEntity.status(success).cacheControl(CacheControl.noStore()).body(result.view)
        is PairingRequestResult.Rejected -> when (result.reason) {
            PairingRequestRejection.UNAUTHENTICATED -> error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
            PairingRequestRejection.ROLE_FORBIDDEN -> error(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED")
            PairingRequestRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_ACCESSIBLE")
            PairingRequestRejection.PAIRING_CODE_UNAVAILABLE -> error(HttpStatus.UNPROCESSABLE_ENTITY, "PAIRING_CODE_UNAVAILABLE")
            PairingRequestRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
            PairingRequestRejection.RATE_LIMITED -> error(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER", true, result.retryAfterSeconds)
            PairingRequestRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true, 1)
        }
    }

    private fun decisionResult(result: PairingDecisionResult): ResponseEntity<*> = when (result) {
        is PairingDecisionResult.Relationship -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.view)
        is PairingDecisionResult.Request -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.view)
        PairingDecisionResult.Cancelled -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build<Void>()
        is PairingDecisionResult.Rejected -> when (result.reason) {
            PairingDecisionRejection.UNAUTHENTICATED -> error(HttpStatus.UNAUTHORIZED, result.reason.name)
            PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, result.reason.name)
            PairingDecisionRejection.ROLE_NOT_ALLOWED -> error(HttpStatus.FORBIDDEN, result.reason.name)
            PairingDecisionRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, result.reason.name, true, 1)
            else -> error(HttpStatus.CONFLICT, result.reason.name)
        }
    }

    private fun key(request: HttpServletRequest): UUID {
        noQuery(request)
        val value = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER)).singleOrNull()
            ?.takeIf { UUID_PATTERN.matches(it) } ?: malformed()
        return UUID.fromString(value)
    }
    private fun noQuery(request: HttpServletRequest) { if (request.parameterMap.isNotEmpty()) malformed() }
    private fun objectBody(body: String): JsonNode {
        val node: JsonNode? = try { reader.readValue(body) } catch (_: JsonProcessingException) { malformed() }
        if (node == null || !node.isObject) malformed()
        return node
    }
    private fun emptyBody(body: String) { if (objectBody(body).size() != 0) malformed() }
    private fun safely(work: () -> ResponseEntity<*>): ResponseEntity<*> = try { work() }
        catch (_: PlanPhoneUnauthenticated) { error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED") }
        catch (_: CodeStoreBusy) { error(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true, 1) }
        catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", true)
        }
    @ExceptionHandler(ApiException::class)
    fun invalid(exception: ApiException) = error(exception.status, exception.code, exception.status.is5xxServerError)
    private fun error(status: HttpStatus, code: String, retryable: Boolean = false, retryAfter: Long? = null): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Request could not be completed", retryable)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", body.requestId)
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        if (retryAfter != null) response.header("Retry-After", retryAfter.toString())
        return response.body(body)
    }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed pairing request")
    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.bound-phone.enabled"], havingValue = "true")
internal class BoundPhonePairingConfiguration {
    @Bean fun boundPhonePairingOperations(
        runtime: PersistenceRuntime,
        generator: PairingCodeGenerator,
        settings: BoundPhoneRuntimeSettings,
        infrastructure: BoundPhoneInfrastructure,
    ) = BoundPhonePairingOperations(runtime, generator, settings, infrastructure)

    @Bean @Order(1)
    fun boundPhonePairingSecurity(http: HttpSecurity, infrastructure: BoundPhoneInfrastructure, errors: ApiSecurityHandlers): SecurityFilterChain = http
        .securityMatchers {
            it.requestMatchers(HttpMethod.POST, "/api/v1/pairing-codes")
            it.requestMatchers(HttpMethod.DELETE, "/api/v1/pairing-codes/*")
            it.requestMatchers(HttpMethod.POST, "/api/v1/pairing-requests")
            it.requestMatchers(HttpMethod.GET, "/api/v1/pairing-requests")
            it.requestMatchers(HttpMethod.GET, "/api/v1/pairing-requests/*")
            it.requestMatchers(HttpMethod.POST, "/api/v1/pairing-requests/*/accept")
            it.requestMatchers(HttpMethod.POST, "/api/v1/pairing-requests/*/reject")
            it.requestMatchers(HttpMethod.DELETE, "/api/v1/pairing-requests/*")
            it.requestMatchers(HttpMethod.GET, "/api/v1/care-relationships")
            it.requestMatchers(HttpMethod.POST, "/api/v1/care-relationships/*/revoke")
        }
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().authenticated() }
        .exceptionHandling { it.authenticationEntryPoint(errors); it.accessDeniedHandler(errors) }
        .addFilterBefore(BoundPhoneBearerFilter(infrastructure.jwt, errors), AuthorizationFilter::class.java)
        .build()
}
