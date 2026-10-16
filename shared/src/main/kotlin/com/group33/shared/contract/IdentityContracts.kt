package com.group33.shared.contract

import com.group33.shared.model.User
import kotlinx.serialization.Serializable

/** Same wire identity as the scaffold; keeps anonymous login backward compatible. */
typealias UserView = User

@Serializable
data class ItemsResponse<T>(val items: List<T>)

@Serializable
data class PairingCodeView(val id: String, val code: String, val expiresAtEpochMillis: Long)

@Serializable
data class RequestPairingRequest(val code: String)

@Serializable
enum class PairingRequestStatus { PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED }

@Serializable
enum class PairingClosedReason {
    ELDER_REJECTED, ELDER_CANCELLED, CAREGIVER_CANCELLED, CODE_REPLACED, CODE_EXPIRED,
}

@Serializable
data class PairingRequestView(
    val id: String,
    val elderId: String,
    val caregiver: UserView,
    val status: PairingRequestStatus,
    val expiresAtEpochMillis: Long,
    val closedReason: PairingClosedReason? = null,
    val relationshipId: String? = null,
)

@Serializable
enum class CareRelationshipStatus { ACTIVE, REVOKED }

@Serializable
data class CareRelationshipView(
    val id: String,
    val elderId: String,
    val caregiverId: String,
    val elder: UserView,
    val caregiver: UserView,
    val status: CareRelationshipStatus,
    val createdAtEpochMillis: Long,
    val revokedAtEpochMillis: Long? = null,
)
