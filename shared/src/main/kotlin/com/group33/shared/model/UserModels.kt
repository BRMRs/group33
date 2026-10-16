package com.group33.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class UserRole {
    ELDER,
    CAREGIVER,
}

@Serializable
data class User(
    val id: String,
    val displayName: String,
    val role: UserRole,
)

@Serializable
data class CareRelation(
    val id: String,
    val elderId: String,
    val caregiverId: String,
    val createdAtEpochMillis: Long,
)

