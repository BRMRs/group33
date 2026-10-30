package com.group33.medicationreminder.caregiver

import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.medicationreminder.core.data.contract.LocalActionClassification
import com.group33.medicationreminder.core.data.contract.SyncState
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.model.DoseStatus

data class OverviewEventRow(
    val occurrence: OccurrenceKey,
    val medication: MedicationText,
    val executionTimeZoneId: String,
    val acceptedStatus: DoseStatus?,
    val localSyncStates: Set<SyncState>,
    val syncedReports: List<CaregiverSyncedReport> = emptyList(),
    val hasUnlinkedReports: Boolean = false,
)

/** C3-only display data. No receipt timestamp is inferred from sync state or last-successful-sync. */
data class CaregiverSyncedReport(
    val classification: LocalActionClassification,
    val recordedAtEpochMillis: Long,
)

/** Only a confirmed local report exposes its action details to the caregiver screen. */
internal fun DoseEventState.toCaregiverEventRow(): OverviewEventRow {
    val matching = localReports.filter { it.occurrence == occurrence }
    return OverviewEventRow(
        occurrence, medicationSnapshot, generationEvidence.executionTimeZoneId, lastAcceptedStatus,
        matching.map { it.syncState }.toSet(),
        matching.filter { it.syncState == SyncState.SYNCED }.map {
            CaregiverSyncedReport(it.classification, it.recordedAtEpochMillis)
        },
        hasUnlinkedReports = matching.size != localReports.size,
    )
}
