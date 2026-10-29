package com.group33.server.occurrence

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.pairing.PairingDirectoryStore
import com.group33.server.pairing.VerifiedPairingActorResolver
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.FieldError
import com.group33.shared.model.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.Collections
import java.util.UUID

/** Adapts the existing registration DTO; it does not generate events or add V2 action fields. */
@RestController
@ConditionalOnProperty(name = ["app.occurrence.enabled"], havingValue = "true")
internal class OccurrenceRegistrationController(
    private val actors: VerifiedPairingActorResolver,
    private val pairing: PairingDirectoryStore,
    private val service: OccurrenceRegistrationService,
    mapper: ObjectMapper,
) {
    private val reader = mapper.readerFor(JsonNode::class.java).with(
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @PostMapping("/api/v1/elders/{elderId}/occurrence-registrations", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun register(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> = try {
        val actor = actors.resolve(jwt)
        if (actor.role != UserRole.ELDER) {
            val known = actor.id == elderId || pairing.directoryTransaction { it.activeRelationship(elderId)?.caregiverId == actor.id }
            failure(if (known) HttpStatus.FORBIDDEN else HttpStatus.NOT_FOUND,
                if (known) "ROLE_NOT_ALLOWED" else "RESOURCE_NOT_ACCESSIBLE")
        } else if (actor.id != elderId) failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_ACCESSIBLE")
        else {
            if (request.parameterMap.isNotEmpty()) malformed()
            val key = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER)).singleOrNull()
                ?.takeIf { UUID_PATTERN.matches(it) } ?: malformed()
            when (val result = service.register(actor, elderId, UUID.fromString(key), decode(body))) {
                is RegistrationResult.Accepted -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.response)
                is RegistrationResult.Rejected -> when (result.reason) {
                    RegistrationRejection.RESOURCE_NOT_ACCESSIBLE -> failure(HttpStatus.NOT_FOUND, result.reason.name)
                    RegistrationRejection.ROLE_NOT_ALLOWED -> failure(HttpStatus.FORBIDDEN, result.reason.name)
                    RegistrationRejection.VALIDATION_FAILED -> failure(HttpStatus.UNPROCESSABLE_ENTITY, result.reason.name,
                        fields = result.violations.map { FieldError(it.field, it.reason) })
                    RegistrationRejection.IDEMPOTENCY_KEY_REUSED -> failure(HttpStatus.CONFLICT, result.reason.name)
                    RegistrationRejection.OPERATION_IN_PROGRESS -> failure(HttpStatus.CONFLICT, result.reason.name, true, "1")
                    RegistrationRejection.SERVICE_UNAVAILABLE -> failure(HttpStatus.SERVICE_UNAVAILABLE, result.reason.name, true)
                }
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
