package com.group33.server.auth

import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.authentication.AuthenticationServiceException
import java.util.UUID

/** Invoked only after verified signature, issuer and time checks. No installation or business actor is invented. */
internal class CurrentPhoneAccountValidator(private val findById: (String) -> User?) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val claims = runCatching { Triple(token.getClaimAsString("type"), token.subject, token.getClaimAsString("role")) }
            .getOrElse { return rejected() }
        if (claims.first != "phone") return OAuth2TokenValidatorResult.success()
        val id = claims.second ?: return rejected()
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id || claims.third !in UserRole.entries.map { it.name }) return rejected()
        val current = try { findById(id) } catch (_: Exception) { throw PhoneAccountReadUnavailable() }
        return if (current != null && current.id == id && current.role.name == claims.third)
            OAuth2TokenValidatorResult.success() else rejected()
    }

    private fun rejected() = OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Invalid access token", null))
}

/** Temporary database failure must not masquerade as a revoked session. No private cause is retained. */
internal class PhoneAccountReadUnavailable : AuthenticationServiceException("Authentication is temporarily unavailable")
