package com.group33.server.capability

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

/** The existing owner-only PUT. This does not expose the private snapshot/read seam. */
@RestController
@ConditionalOnProperty(name = ["app.capability.enabled"], havingValue = "true")
internal class CapabilityController(
    private val actors: VerifiedPairingActorResolver,
    private val pairing: PairingDirectoryStore,
    private val service: CapabilityService,
    mapper: ObjectMapper,
) {
    // A route-local tree reader avoids scalar coercions without modifying the shared mapper.
    private val reader = mapper.readerFor(JsonNode::class.java).with(
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @PutMapping("/api/v1/elders/{elderId}/reminder-capability", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun report(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> = try {
        val actor = actors.resolve(jwt)
        if (actor.role != UserRole.ELDER) {
            val known = actor.id == elderId || pairing.directoryTransaction { it.activeRelationship(elderId)?.caregiverId == actor.id }
            failure(if (known) HttpStatus.FORBIDDEN else HttpStatus.NOT_FOUND,
                if (known) "ROLE_NOT_ALLOWED" else "RESOURCE_NOT_ACCESSIBLE")
        } else if (actor.id != elderId) failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_ACCESSIBLE")
        else {
            if (request.parameterMap.isNotEmpty()) malformed()
            val text = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER)).singleOrNull()
                ?.takeIf { UUID_PATTERN.matches(it) } ?: malformed()
            when (val result = service.report(actor, elderId, UUID.fromString(text), decode(body))) {
                is CapabilityAcceptance.Accepted -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.receipt)
                CapabilityAcceptance.InProgress -> failure(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true, "1")
                is CapabilityAcceptance.Rejected -> when (result.reason) {
                    CapabilityRejection.INVALID_REPORT -> failure(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED")
                    CapabilityRejection.RESOURCE_NOT_ACCESSIBLE -> failure(HttpStatus.NOT_FOUND, result.reason.name)
                    CapabilityRejection.ROLE_NOT_ALLOWED -> failure(HttpStatus.FORBIDDEN, result.reason.name)
                    else -> failure(HttpStatus.CONFLICT, result.reason.name)
                }
            }
        }
    } catch (exception: ApiException) {
        failure(exception.status, exception.code)
    } catch (exception: Exception) {
        if (exception is InterruptedException) Thread.currentThread().interrupt()
        failure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", true)
    }

    private fun decode(body: String): ReminderCapabilityReport {
        val node: JsonNode? = try { reader.readValue(body) } catch (_: JsonProcessingException) { malformed() }
        if (node == null || !node.isObject || node.fieldNames().asSequence().toSet() != FIELDS) malformed()
        fun text(field: String): String = node[field].takeIf { it.isTextual }?.textValue() ?: malformed()
        fun long(field: String): Long = node[field].takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue() ?: malformed()
        fun boolean(field: String): Boolean = node[field].takeIf { it.isBoolean }?.booleanValue() ?: malformed()
        val delivery = DeliveryCondition.entries.singleOrNull { it.name == text("deliveryCondition") } ?: malformed()
        val exact = ExactAlarmAccess.entries.singleOrNull { it.name == text("exactAlarmAccess") } ?: malformed()
        val reasons = node["reasons"].takeIf { it.isArray && it.all(JsonNode::isTextual) } ?: malformed()
        return ReminderCapabilityReport(text("reportId"), long("observationSequence"), long("observedAtEpochMillis"),
            delivery, boolean("notificationPermissionGranted"), boolean("channelEnabled"), exact, reasons.map(JsonNode::textValue))
    }

    private fun failure(status: HttpStatus, code: String, retryable: Boolean = false, retryAfter: String? = null): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Request could not be completed", retryable)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", body.requestId)
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        if (retryAfter != null) response.header("Retry-After", retryAfter)
        return response.body(body)
    }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed capability report")
    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val FIELDS = setOf("reportId", "observationSequence", "observedAtEpochMillis", "deliveryCondition",
            "notificationPermissionGranted", "channelEnabled", "exactAlarmAccess", "reasons")
    }
}
