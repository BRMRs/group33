package com.group33.shared.contract.proposal

import com.group33.shared.contract.OccurrenceGenerationEvidence
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Collections
import java.util.UUID

private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }

/** PROPOSED #24 V2: independent of retired r1/debug DTOs, never enabled by serialization alone. */
@Serializable(with = FrozenDoseGroupSerializer::class)
class FrozenDoseGroup(
    val doseGroupId: String, val elderId: String, val executionScopeId: String,
    val plannedAtEpochMillis: Long, members: List<OccurrenceGenerationEvidence>,
) {
    // A read-only Kotlin List can still alias a caller's MutableList. Own the validated snapshot.
    val members: List<OccurrenceGenerationEvidence> = Collections.unmodifiableList(ArrayList(members))

    init {
        uuid(doseGroupId)
        require(elderId.isNotBlank() && executionScopeId.isNotBlank() && plannedAtEpochMillis >= 0)
        require(this.members.size in 1..100 && this.members.map { it.occurrence }.distinct().size == this.members.size)
        require(this.members.all { it.plannedAtEpochMillis == plannedAtEpochMillis })
    }

    fun copy(
        doseGroupId: String = this.doseGroupId,
        elderId: String = this.elderId,
        executionScopeId: String = this.executionScopeId,
        plannedAtEpochMillis: Long = this.plannedAtEpochMillis,
        members: List<OccurrenceGenerationEvidence> = this.members,
    ) = FrozenDoseGroup(doseGroupId, elderId, executionScopeId, plannedAtEpochMillis, members)

    operator fun component1() = doseGroupId
    operator fun component2() = elderId
    operator fun component3() = executionScopeId
    operator fun component4() = plannedAtEpochMillis
    operator fun component5() = members

    override fun equals(other: Any?): Boolean = other is FrozenDoseGroup &&
        doseGroupId == other.doseGroupId && elderId == other.elderId &&
        executionScopeId == other.executionScopeId && plannedAtEpochMillis == other.plannedAtEpochMillis &&
        members == other.members

    override fun hashCode(): Int {
        var result = doseGroupId.hashCode()
        result = 31 * result + elderId.hashCode()
        result = 31 * result + executionScopeId.hashCode()
        result = 31 * result + plannedAtEpochMillis.hashCode()
        return 31 * result + members.hashCode()
    }

    override fun toString() = "FrozenDoseGroup(doseGroupId=$doseGroupId, elderId=$elderId, " +
        "executionScopeId=$executionScopeId, plannedAtEpochMillis=$plannedAtEpochMillis, members=$members)"
}

/** Preserve the existing five-field wire while forcing every decode through the owning constructor. */
@Serializable
@SerialName("com.group33.shared.contract.proposal.FrozenDoseGroup")
private data class FrozenDoseGroupWire(
    val doseGroupId: String, val elderId: String, val executionScopeId: String,
    val plannedAtEpochMillis: Long, val members: List<OccurrenceGenerationEvidence>,
)

object FrozenDoseGroupSerializer : KSerializer<FrozenDoseGroup> {
    override val descriptor = FrozenDoseGroupWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FrozenDoseGroup) = encoder.encodeSerializableValue(
        FrozenDoseGroupWire.serializer(),
        FrozenDoseGroupWire(value.doseGroupId, value.elderId, value.executionScopeId, value.plannedAtEpochMillis, value.members),
    )

    override fun deserialize(decoder: Decoder): FrozenDoseGroup {
        val value = decoder.decodeSerializableValue(FrozenDoseGroupWire.serializer())
        return FrozenDoseGroup(value.doseGroupId, value.elderId, value.executionScopeId, value.plannedAtEpochMillis, value.members)
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
