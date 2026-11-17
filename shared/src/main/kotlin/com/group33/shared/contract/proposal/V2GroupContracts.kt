package com.group33.shared.contract.proposal

import com.group33.shared.contract.OccurrenceGenerationEvidence
import kotlinx.serialization.Serializable
import java.util.UUID

private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }

/** PROPOSED #24 V2: independent of retired r1/debug DTOs, never enabled by serialization alone. */
@Serializable
data class FrozenDoseGroup(
    val doseGroupId: String, val elderId: String, val executionScopeId: String,
    val plannedAtEpochMillis: Long, val members: List<OccurrenceGenerationEvidence>,
) {
    init {
        uuid(doseGroupId)
        require(elderId.isNotBlank() && executionScopeId.isNotBlank() && plannedAtEpochMillis >= 0)
        require(members.size in 1..100 && members.map { it.occurrence }.distinct().size == members.size)
        require(members.all { it.plannedAtEpochMillis == plannedAtEpochMillis })
    }
}
@Serializable enum class GroupIntentKind { TAKEN, REMIND_LATER, HELP }
@Serializable data class GroupIntent(
    val actionId: String, val group: FrozenDoseGroup, val kind: GroupIntentKind,
    val recordedAtEpochMillis: Long,
) { init { uuid(actionId); require(recordedAtEpochMillis >= 0) } }

/** Takeover is a separate caregiver command and cannot become an Elder TAKEN intent. */
@Serializable data class CaregiverTakeover(
    val actionId: String, val doseGroupId: String, val recordedAtEpochMillis: Long,
) { init { uuid(actionId); uuid(doseGroupId); require(recordedAtEpochMillis >= 0) } }
@Serializable enum class ActualTimeKnowledge { KNOWN, UNKNOWN }
/** Required non-null envelope avoids missing-null ambiguity between Jackson and kotlinx. */
@Serializable data class ActualMedicationTime(
    val knowledge: ActualTimeKnowledge, val epochMillis: Long? = null,
) {
    init {
        require((knowledge == ActualTimeKnowledge.KNOWN) == (epochMillis != null))
        require(epochMillis == null || epochMillis >= 0)
    }
}
@Serializable enum class FollowUpOutcome { CONTACTED, TAKEN, NOT_TAKEN, UNKNOWN }
@Serializable data class FollowUpCommand(
    val actionId: String, val followUpId: String, val doseGroupId: String,
    val expectedRevision: Long, val outcome: FollowUpOutcome,
    val actualTime: ActualMedicationTime, val enteredAtEpochMillis: Long,
) {
    init {
        uuid(actionId); uuid(followUpId); uuid(doseGroupId)
        require(expectedRevision >= 0 && enteredAtEpochMillis >= 0)
    }
}
/** Server records source actor/receivedAt/revision; no caller-supplied trusted identity. */
@Serializable data class FollowUpFact(
    val command: FollowUpCommand, val actorId: String, val revision: Long,
    val receivedAtEpochMillis: Long,
) { init { require(actorId.isNotBlank() && revision > 0 && receivedAtEpochMillis >= 0) } }
@Serializable enum class ProbePhase { BEFORE_FIVE_MINUTES, AFTER_FIVE_MINUTES }
@Serializable data class DispatchedProbe(
    val roundId: String, val attemptId: String, val doseGroupId: String,
    val phase: ProbePhase, val dispatchedAtEpochMillis: Long,
) { init { uuid(roundId); uuid(attemptId); uuid(doseGroupId); require(dispatchedAtEpochMillis >= 0) } }
@Serializable data class ProbeReply(
    val roundId: String, val attemptId: String,
) { init { uuid(roundId); uuid(attemptId) } }
@Serializable enum class DeliveryStage { SENT, DEVICE_RECEIVED, VISIBLE, READ }
/** Contact/takeover are business facts, not delivery ACK stages. Environment stays server-bound. */
@Serializable data class DeliveryAcknowledgement(
    val alertId: String, val deliveryId: String, val attemptId: String,
    val fencingToken: Long, val stage: DeliveryStage, val recordedAtEpochMillis: Long,
) {
    init { uuid(alertId); uuid(deliveryId); uuid(attemptId); require(fencingToken > 0 && recordedAtEpochMillis >= 0) }
}

/** Durable original command acknowledgement; never a replacement for current authorized projection. */
@Serializable data class GroupIntentReceipt(
    val actionId: String, val doseGroupId: String, val receivedAtEpochMillis: Long,
) { init { uuid(actionId); uuid(doseGroupId); require(receivedAtEpochMillis >= 0) } }
