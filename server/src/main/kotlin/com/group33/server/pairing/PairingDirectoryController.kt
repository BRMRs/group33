package com.group33.server.pairing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.model.ApiError
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.Collections
import java.util.UUID

@RestController
internal class PairingDirectoryController(private val actors: VerifiedPairingActorResolver,
    private val service: PairingDirectoryService, mapper: ObjectMapper) {
    private val reader = mapper.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @GetMapping("/api/v1/pairing-requests")
    fun requests(@AuthenticationPrincipal jwt: Jwt, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        if (request.parameterMap.keys.any { it != "status" }) malformed()
        val values = request.getParameterValues("status")
        val status = if (values == null) null else {
            if (values.size != 1) malformed()
            try { PairingRequestStatus.valueOf(values.single()) } catch (_: IllegalArgumentException) { malformed() }
        }
        return safely { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.requests(actor, status)) }
    }
    @GetMapping("/api/v1/care-relationships")
    fun relationships(@AuthenticationPrincipal jwt: Jwt, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        if (request.parameterMap.isNotEmpty()) malformed()
        return safely { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.relationships(actor)) }
    }
    @DeleteMapping("/api/v1/pairing-codes/{id}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String, @RequestBody(required = false) body: String?,
        request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        if (request.parameterMap.isNotEmpty()) malformed()
        val key = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER)).singleOrNull()
            ?.takeIf { UUID_PATTERN.matches(it) }?.let(UUID::fromString) ?: malformed()
        if (body != null) {
            if (request.contentType?.substringBefore(';')?.trim() != MediaType.APPLICATION_JSON_VALUE)
                return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
            val node: JsonNode? = try { reader.readValue(body) } catch (_: JsonProcessingException) { malformed() }
            if (node == null || !node.isObject || node.size() != 0) malformed()
        }
        return safely {
            when (val result = service.deleteCode(actor, id, key)) {
                CodeDeletionResult.Deleted -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build<Void>()
                is CodeDeletionResult.Rejected -> when (result.reason) {
                    CodeDeletionRejection.ROLE_NOT_ALLOWED -> error(HttpStatus.FORBIDDEN, result.reason.name)
                    CodeDeletionRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, result.reason.name)
                    CodeDeletionRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, result.reason.name)
                    CodeDeletionRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, result.reason.name, true, 1)
                }
            }
        }
    }
    private fun safely(work: () -> ResponseEntity<*>): ResponseEntity<*> = try { work() }
        catch (_: CodeStoreBusy) { error(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true, 1) }
        catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", true)
        }
    @ExceptionHandler(ApiException::class)
    fun invalid(exception: ApiException) = error(exception.status, exception.code)
    private fun error(status: HttpStatus, code: String, retryable: Boolean = false, retry: Long? = null): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Request could not be completed", retryable)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", body.requestId)
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        if (retry != null) response.header("Retry-After", retry.toString())
        return response.body(body)
    }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed pairing request")
    private companion object { val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") }
}
