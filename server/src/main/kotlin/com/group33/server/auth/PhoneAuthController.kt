package com.group33.server.auth

import com.fasterxml.jackson.annotation.JsonInclude
import com.group33.server.web.ApiException
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap

data class FusionSdkTokenResponse(val fusionAuthToken: String, val schemeCode: String, val expiresInSeconds: Long)
data class FusionVerifyRequest(val verifyToken: String)
data class PhoneRegistrationRequest(val registrationTicket: String, val role: UserRole, val displayName: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PhoneAuthResponse(
    val status: String,
    val registrationTicket: String? = null,
    val accessToken: String? = null,
    val expiresInSeconds: Long? = null,
    val user: User? = null,
)

@RestController
@RequestMapping("/api/v1/auth")
class PhoneAuthController(
    private val provider: FusionAuthProvider,
    private val accounts: PhoneAccountStore,
    private val jwt: JwtService,
    @Value("\${app.fusion.enabled:false}") private val enabled: Boolean,
    @Value("\${app.fusion.scheme-code:}") private val schemeCode: String,
    @Value("\${app.jwt.secret}") private val jwtSecret: String,
) {
    private data class Bucket(var minute: Long, var count: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    @PostMapping("/fusion/token")
    fun sdkToken(request: HttpServletRequest): FusionSdkTokenResponse {
        guard(request)
        return providerCall { FusionSdkTokenResponse(provider.getSdkToken(), schemeCode, 900) }
    }

    @PostMapping("/fusion/verify")
    fun verify(@RequestBody body: FusionVerifyRequest, request: HttpServletRequest): PhoneAuthResponse {
        guard(request)
        val phone = providerCall { provider.verify(body.verifyToken) }
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "PHONE_VERIFICATION_FAILED", "Phone verification failed")
        val account = accounts.findByPhone(phone)
        return if (account == null) {
            PhoneAuthResponse("REGISTRATION_REQUIRED", registrationTicket = accounts.newRegistrationTicket(phone))
        } else {
            session(account)
        }
    }

    @PostMapping("/phone/register")
    fun register(@RequestBody body: PhoneRegistrationRequest, request: HttpServletRequest): PhoneAuthResponse {
        guard(request)
        val account = accounts.register(body.registrationTicket, body.role, body.displayName)
            ?: throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REGISTRATION_INVALID", "Registration could not be completed")
        return session(account)
    }

    private fun session(user: User): PhoneAuthResponse {
        val token = jwt.issueForPhone(user.id, user.role.name)
        return PhoneAuthResponse("SIGNED_IN", accessToken = token.value, expiresInSeconds = token.expiresInSeconds, user = user)
    }

    private fun guard(request: HttpServletRequest) {
        if (!enabled || jwtSecret == "GROUP33_LOCAL_DEVELOPMENT_SECRET_CHANGE_BEFORE_DEPLOYMENT_2026") {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PHONE_AUTH_UNAVAILABLE", "Phone authentication is not configured")
        }
        val now = System.currentTimeMillis() / 60_000
        synchronized(buckets) {
            buckets.entries.removeIf { it.value.minute < now }
            val bucket = buckets.computeIfAbsent(request.remoteAddr ?: "unknown") { Bucket(now, 0) }
            if (bucket.count >= 20 || buckets.size > 10_000) {
                throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Try again later")
            }
            bucket.count++
        }
    }

    private inline fun <T> providerCall(block: () -> T): T = try {
        block()
    } catch (_: FusionProviderException) {
        throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PHONE_AUTH_UNAVAILABLE", "Phone authentication is unavailable")
    }
}
