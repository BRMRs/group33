package com.group33.shared.contract

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class HelpActionSyncState { PENDING, ACCEPTED, RETRY, REJECTED }
@Serializable data class HelpRequest(
    val actionId: String,
    val elderId: String,
    val groupId: String,
    val occurrenceIds: List<String>,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(UUID.fromString(actionId).toString() == actionId)
        require(elderId.isNotBlank() && groupId.isNotBlank())
        require(occurrenceIds.isNotEmpty() && occurrenceIds.distinct().size == occurrenceIds.size)
        require(occurrenceIds.all { it.isNotBlank() })
        require(occurredAtEpochMillis >= 0)
    }
}

@Serializable data class HelpReceipt(
    val actionId: String,
    val groupId: String,
    val status: HelpActionSyncState,
    val receivedAtEpochMillis: Long,
) {
    init {
        require(UUID.fromString(actionId).toString() == actionId)
        require(groupId.isNotBlank() && status == HelpActionSyncState.ACCEPTED)
        require(receivedAtEpochMillis >= 0)
    }
}

@Serializable enum class CaregiverFollowUpState { NEEDS_CONTACT }
@Serializable data class CaregiverFollowUpView(
    val actionId: String,
    val groupId: String,
    val elderId: String,
    val occurrenceIds: List<String>,
    val occurredAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val state: CaregiverFollowUpState = CaregiverFollowUpState.NEEDS_CONTACT,
) {
    init {
        require(UUID.fromString(actionId).toString() == actionId)
        require(groupId.isNotBlank() && elderId.isNotBlank())
        require(occurrenceIds.isNotEmpty() && occurrenceIds.distinct().size == occurrenceIds.size)
        require(occurrenceIds.all { it.isNotBlank() })
        require(occurredAtEpochMillis >= 0 && receivedAtEpochMillis >= occurredAtEpochMillis)
    }
}
