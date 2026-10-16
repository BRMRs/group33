package com.group33.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val requestId: String,
    val fieldErrors: List<FieldError>? = null,
    val reason: String? = null,
)

@Serializable
data class FieldError(
    val field: String,
    val code: String,
)

@Serializable
data class AnonymousLoginRequest(
    val installationId: String,
    val role: UserRole,
    val displayName: String,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
    val user: User,
)
