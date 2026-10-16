package com.group33.server.auth

import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.AuthResponse
import com.group33.shared.model.User
import com.group33.shared.model.FieldError
import com.group33.server.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userStore: AnonymousUserStore,
    private val jwtService: JwtService,
) {
    fun anonymousLogin(request: AnonymousLoginRequest): AuthResponse {
        val installationId = request.installationId.trim()
        val displayName = request.displayName.trim()
        val fieldErrors = buildList {
            if (installationId.length !in 8..128) add(FieldError("installationId", "INVALID_LENGTH"))
            if (displayName.length !in 1..40) add(FieldError("displayName", "INVALID_LENGTH"))
        }
        if (fieldErrors.isNotEmpty()) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Login fields are invalid", fieldErrors,
            )
        }

        val anonymousUser = userStore.getOrCreate(installationId, displayName, request.role)
        val token = jwtService.issueFor(anonymousUser)
        return AuthResponse(
            accessToken = token.value,
            tokenType = "Bearer",
            expiresInSeconds = token.expiresInSeconds,
            user = User(
                id = anonymousUser.id,
                displayName = anonymousUser.displayName,
                role = anonymousUser.role,
            ),
        )
    }
}
