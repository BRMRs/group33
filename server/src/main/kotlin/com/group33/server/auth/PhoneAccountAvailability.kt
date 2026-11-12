package com.group33.server.auth

import com.group33.server.web.ApiErrors
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** No JDBC/configuration cause, token, ticket or phone is retained in public failure state. */
internal class PhoneAccountStorageUnavailable : RuntimeException("Phone account storage is temporarily unavailable")

internal class PhoneAuthProviderUnavailable : RuntimeException("Phone authentication is unavailable")

/** Same generic temporary-storage error as current-account validation, scoped to phone auth routes. */
@RestControllerAdvice(assignableTypes = [PhoneAuthController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class PhoneAccountAvailabilityHandler {
    @ExceptionHandler(PhoneAuthProviderUnavailable::class)
    fun providerUnavailable(): ResponseEntity<com.group33.shared.model.ApiError> {
        val error = ApiErrors.body("PHONE_AUTH_UNAVAILABLE", "Phone authentication is unavailable", retryable = true)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("X-Request-ID", error.requestId).header("Cache-Control", "no-store").body(error)
    }

    @ExceptionHandler(PhoneAccountStorageUnavailable::class)
    fun unavailable(): ResponseEntity<com.group33.shared.model.ApiError> {
        val error = ApiErrors.body("SERVICE_UNAVAILABLE", "Authentication is temporarily unavailable", retryable = true)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("X-Request-ID", error.requestId).header("Cache-Control", "no-store").body(error)
    }
}
