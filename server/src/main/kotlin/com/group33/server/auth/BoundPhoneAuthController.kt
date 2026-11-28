package com.group33.server.auth

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.web.ApiErrors
import com.group33.shared.model.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** An explicit deployment composition must supply the service and its trusted IP rate limiter. */
@RestController
@ConditionalOnProperty(name = ["app.bound-phone.enabled"], havingValue = "true")
@RequestMapping("/api/v1/auth")
internal class BoundPhoneAuthController(
    private val authentication: BoundPhoneAuthentication,
    private val rateLimit: BoundPhoneRateLimit,
) {
    @PostMapping("/fusion/token", consumes = ["application/json"])
    fun token(request: HttpServletRequest) = handle(request) { body ->
        body.fields(setOf("installationId"), setOf("authenticationAttemptToken"))
        val install = body.installation()
        if (body.has("authenticationAttemptToken")) authentication.refresh(install, body.capability("authenticationAttemptToken"))
        else authentication.start(install)
    }

    @PostMapping("/fusion/verify", consumes = ["application/json"])
    fun verify(request: HttpServletRequest) = handle(request) { body ->
        body.fields(setOf("installationId", "authenticationAttemptToken", "verifyToken"), setOf("expectedPhone"))
        val proof = body.text("verifyToken").takeIf { it.isNotBlank() && it.length <= 8192 } ?: invalid()
        val expected = if (body.has("expectedPhone")) body.text("expectedPhone").takeIf { it.isNotBlank() && it.length <= 32 }
            ?: invalid() else null
        authentication.verify(body.installation(), body.capability("authenticationAttemptToken"), proof, expected)
    }

    @PostMapping("/phone/register", consumes = ["application/json"])
    fun register(request: HttpServletRequest) = handle(request) { body ->
        body.fields(setOf("registrationTicket", "installationId", "role"), setOf("displayName"))
        val role = try { UserRole.valueOf(body.text("role")) } catch (_: IllegalArgumentException) { malformed() }
        val name = body.get("displayName")?.takeUnless { it.isNull }?.let { body.text("displayName").trim() }
        if (role == UserRole.CAREGIVER && (name == null || name.length !in 1..40)) invalid()
        authentication.register(body.installation(), body.capability("registrationTicket"), role, name)
    }

    private fun handle(request: HttpServletRequest, work: (JsonNode) -> Map<String, Any>): ResponseEntity<Any> = try {
        val wait = rateLimit.retryAfterSeconds(request)
        if (wait != null) error(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", true, wait.coerceAtLeast(1))
        else {
            val bytes = request.inputStream.readNBytes(MAX_BODY + 1)
            if (bytes.size > MAX_BODY) malformed()
            val body = try { json.readTree(bytes) } catch (_: Exception) { malformed() }
            if (body == null || !body.isObject) malformed()
            ResponseEntity.ok().header("Cache-Control", "no-store").body(work(body))
        }
    } catch (failure: PhoneAuthFailure) {
        error(failure.status, failure.code, failure.retryable, if (failure.code == "OPERATION_IN_PROGRESS") 1 else null)
    }

    private fun error(status: HttpStatus, code: String, retryable: Boolean, wait: Long?): ResponseEntity<Any> {
        val error = ApiErrors.body(code, "Phone authentication could not complete", retryable)
        val response = ResponseEntity.status(status).header("Cache-Control", "no-store").header("X-Request-ID", error.requestId)
        if (wait != null) response.header("Retry-After", wait.toString())
        return response.body(error)
    }

    private fun JsonNode.fields(required: Set<String>, optional: Set<String>) {
        val keys = fieldNames().asSequence().toSet()
        if (!keys.containsAll(required) || keys.any { it !in required && it !in optional }) malformed()
    }

    private fun JsonNode.text(field: String): String = get(field)?.takeIf { it.isTextual }?.textValue() ?: malformed()
    private fun JsonNode.installation() = canonicalPhoneUuid(text("installationId")) ?: invalid()
    private fun JsonNode.capability(field: String) = text(field).takeIf(CAPABILITY::matches) ?: invalid()

    private companion object {
        const val MAX_BODY = 65_536
        val CAPABILITY = Regex("[A-Za-z0-9_-]{43}")
        val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        fun malformed(): Nothing = throw PhoneAuthFailure(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST")
        fun invalid(): Nothing = throw PhoneAuthFailure(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED")
    }
}

internal fun interface BoundPhoneRateLimit {
    /** Null allows this request; otherwise the trusted transport's remaining quota window. */
    fun retryAfterSeconds(request: HttpServletRequest): Long?
}
