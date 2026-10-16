package com.group33.shared.contract

import com.group33.shared.model.DoseStatus
import kotlinx.serialization.Serializable

/** Revision and UTC offset deliberately do NOT participate in occurrence identity. */
@Serializable
data class OccurrenceKey(val planId: String, val localDate: String, val localTime: String)

@Serializable
enum class TimeResolutionPolicy { P0_TIME_V1 }

@Serializable
data class OccurrenceGenerationEvidence(
    val generationId: String,
    val occurrence: OccurrenceKey,
    val planRevision: Long,
    val generatedAtEpochMillis: Long,
    val executionTimeZoneId: String,
    val plannedAtEpochMillis: Long,
    val resolvedOffsetSeconds: Int,
    val resolutionPolicyVersion: TimeResolutionPolicy,
    val sourceSnapshotVersion: String,
    val sourceSnapshotServerAtEpochMillis: Long,
)

@Serializable
enum class DoseActionKind { TAKEN, REMIND_LATER }

@Serializable
enum class DoseActionClassification { TIMELY_TAKEN, LATE_TAKEN, REMIND_LATER, SUPERSEDED }

@Serializable
data class LateTakenReport(val actionId: String, val recordedAtEpochMillis: Long, val receivedAtEpochMillis: Long)

@Serializable
data class DoseEventView(
    val id: String,
    val elderId: String,
    val occurrence: OccurrenceKey,
    val planRevision: Long,
    val medicationId: String,
    val medicationSnapshot: MedicationText,
    val executionTimeZoneId: String,
    val plannedAtEpochMillis: Long,
    val responseDeadlineEpochMillis: Long,
    val generationEvidence: OccurrenceGenerationEvidence,
    val status: DoseStatus,
    val lateTakenReports: List<LateTakenReport>,
    val serverUpdatedAtEpochMillis: Long,
    val snoozedUntilEpochMillis: Long? = null,
    val takenRecordedAtEpochMillis: Long? = null,
)

@Serializable
data class DoseActionRequest(
    val actionId: String,
    val occurrence: OccurrenceKey,
    val planRevision: Long,
    val generationEvidence: OccurrenceGenerationEvidence,
    val kind: DoseActionKind,
    val recordedAtEpochMillis: Long,
)

@Serializable
data class DoseActionReceipt(
    val actionId: String,
    val receivedAtEpochMillis: Long,
    val classification: DoseActionClassification,
    val event: DoseEventView,
)

@Serializable
data class OccurrenceRegistrationRequest(val items: List<OccurrenceGenerationEvidence>)

@Serializable
data class OccurrenceRegistrationReceipt(val generationId: String, val receivedAtEpochMillis: Long, val event: DoseEventView)

@Serializable
data class OccurrenceRegistrationResponse(val items: List<OccurrenceRegistrationReceipt>)
