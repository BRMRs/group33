package com.group33.server.pairing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.model.ApiError
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Local HTTP adapter for issuance only; no caller-selected target or installation is accepted. */
@RestController
internal class PairingCodeController(
    private val actors: VerifiedPairingActorResolver,
    private val service: PairingCodeIssuanceService,
    mapper: ObjectMapper,
) {
    private val emptyObjectReader = mapper.readerFor(JsonNode::class.java)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @PostMapping("/api/v1/pairing-codes", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun issue(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody body: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        if (actor.role != com.group33.shared.model.UserRole.ELDER) {
            return error(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED")
        }
        val keys = java.util.Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER))
        val key = keys.singleOrNull()?.takeIf { UUID_PATTERN.matches(it) } ?: malformed()
        val json: JsonNode? = try {
            emptyObjectReader.readValue(body)
        } catch (_: JsonProcessingException) {
            malformed()
        }
        if (json == null || !json.isObject || json.size() != 0 || request.parameterMap.isNotEmpty()) malformed()

        val result = try {
            service.issue(actor, UUID.fromString(key))
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            // A store/generator failure may be unknown to the caller. Never return private exception details.
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", retryable = true)
        }
        return when (result) {
            is CodeIssuanceResult.Issued -> ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(result.view)
            is CodeIssuanceResult.Rejected -> when (result.reason) {
                CodeIssueRejection.ROLE_FORBIDDEN -> error(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED")
                CodeIssueRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
                CodeIssueRejection.RATE_LIMITED -> error(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER", true,
                    result.retryAfterSeconds)
                CodeIssueRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true, 1)
                CodeIssueRejection.CODE_GENERATION_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE", true)
            }
        }
    }

    @ExceptionHandler(ApiException::class)
    fun invalid(exception: ApiException): ResponseEntity<ApiError> = error(exception.status, exception.code)

    private fun error(
        status: HttpStatus,
        code: String,
        retryable: Boolean = false,
        retryAfter: Long? = null,
    ): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Request could not be completed", retryable)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .header("X-Request-ID", body.requestId)
        if (retryAfter != null) response.header("Retry-After", retryAfter.toString())
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        return response.body(body)
    }

    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST,
        "MALFORMED_REQUEST", "Request body or operation header is malformed")

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
