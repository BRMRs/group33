package com.group33.server.pairing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.RequestPairingRequest
import com.group33.shared.model.ApiError
import com.group33.shared.model.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.net.InetAddress
import java.util.Collections
import java.util.UUID

/** Existing routes/DTOs only. Real authentication precedes actor lookup; no public access adapter is enabled. */
@RestController
internal class PairingLifecycleController(
    private val actors: VerifiedPairingActorResolver,
    private val requests: PairingRequestService,
    private val decisions: PairingDecisionService,
    mapper: ObjectMapper,
) {
    private val reader = mapper.readerFor(JsonNode::class.java).with(
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @PostMapping("/api/v1/pairing-requests", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        if (actor.role != UserRole.CAREGIVER) return error(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED")
        val operation = key(request)
        val node = objectBody(body)
        if (node.size() != 1 || node.get("code")?.isTextual != true) malformed()
        val address = peer(request)
        return safely { requestResult(requests.request(actor, operation, RequestPairingRequest(node["code"].textValue()), address), HttpStatus.CREATED) }
    }

    @GetMapping("/api/v1/pairing-requests/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        noQuery(request)
        return safely { requestResult(requests.detail(actor, id), HttpStatus.OK) }
    }

    @PostMapping("/api/v1/pairing-requests/{id}/accept", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun accept(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String, @RequestBody body: String, request: HttpServletRequest) =
        decide(jwt, id, body, request, PairingDecision.ACCEPT)

    @PostMapping("/api/v1/pairing-requests/{id}/reject", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun reject(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String, @RequestBody body: String, request: HttpServletRequest) =
        decide(jwt, id, body, request, PairingDecision.REJECT)

    @DeleteMapping("/api/v1/pairing-requests/{id}")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String, @RequestBody(required = false) body: String?, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        val operation = key(request)
        if (body != null) {
            if (request.contentType?.substringBefore(';')?.trim() != MediaType.APPLICATION_JSON_VALUE) {
                return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
            }
            emptyBody(body)
        }
        return safely { decisionResult(decisions.cancel(actor, id, operation)) }
    }

    @PostMapping("/api/v1/care-relationships/{id}/revoke", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun revoke(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String, @RequestBody body: String, request: HttpServletRequest) =
        decide(jwt, id, body, request, PairingDecision.REVOKE)

    private fun decide(jwt: Jwt, id: String, body: String, request: HttpServletRequest, action: PairingDecision): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        val operation = key(request)
        emptyBody(body)
        return safely {
            decisionResult(when (action) {
                PairingDecision.ACCEPT -> decisions.accept(actor, id, operation)
                PairingDecision.REJECT -> decisions.reject(actor, id, operation)
                PairingDecision.REVOKE -> decisions.revoke(actor, id, operation)
                PairingDecision.CANCEL -> error("Cancellation uses its DELETE route")
            })
        }
    }

    private fun requestResult(result: PairingRequestResult, success: HttpStatus): ResponseEntity<*> = when (result) {
        is PairingRequestResult.Success -> ResponseEntity.status(success).cacheControl(CacheControl.noStore()).body(result.view)
        is PairingRequestResult.Rejected -> when (result.reason) {
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

    /** Literal socket peer only; forwarded headers never select the quota bucket in this direct-peer candidate. */
    private fun peer(request: HttpServletRequest): InetAddress {
        val value = request.remoteAddr.orEmpty()
        try {
            if (Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(value)) {
                val parts = value.split('.').map(String::toInt)
                if (parts.all { it in 0..255 }) return InetAddress.getByAddress(parts.map(Int::toByte).toByteArray())
            } else if (':' in value && Regex("[0-9a-fA-F:.]+(%[0-9]+)?").matches(value)) {
                return InetAddress.getByName(value) // IPv6 literal; the validated colon rules out a DNS host name.
            }
        } catch (_: IllegalArgumentException) { unavailable() }
        catch (_: java.net.UnknownHostException) { unavailable() }
        unavailable()
    }

    private fun safely(work: () -> ResponseEntity<*>): ResponseEntity<*> = try { work() } catch (failure: Exception) {
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
    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Peer unavailable")
    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
