package com.group33.server.auth

import com.group33.shared.model.UserRole

data class AnonymousUser(
    val id: String,
    val installationId: String,
    val displayName: String,
    val role: UserRole,
)
