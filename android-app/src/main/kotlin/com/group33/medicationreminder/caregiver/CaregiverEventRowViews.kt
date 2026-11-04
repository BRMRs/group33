package com.group33.medicationreminder.caregiver

import android.view.View
import androidx.core.view.ViewCompat
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.SyncState
import com.group33.medicationreminder.databinding.ItemCaregiverOverviewEventBinding
import com.group33.shared.model.DoseStatus

/** Shared only by C3 overview/history, so the same accepted outcome cannot acquire different copy. */
internal fun ItemCaregiverOverviewEventBinding.bindEvent(event: OverviewEventRow) {
    val context = root.context
    ViewCompat.setAccessibilityHeading(medication, true)
    medication.text = event.medication.displayName
    dosage.text = event.medication.dosageText
    plannedTime.text = context.getString(R.string.c3_planned_time,
        event.occurrence.localDate, event.occurrence.localTime, event.executionTimeZoneId)
    outcome.setText(event.acceptedStatus.outcomeString())
    localSync.text = event.localSyncStates.takeIf { it.isNotEmpty() }?.let { states ->
        context.getString(R.string.c3_local_sync, states.joinToString(", ") {
            context.getString(when (it) {
                SyncState.PENDING_SYNC -> R.string.c3_sync_pending
                SyncState.SYNCED -> R.string.c3_sync_synced
                SyncState.NEEDS_ATTENTION -> R.string.c3_sync_attention
            })
        })
    }.orEmpty()
    localSync.visibility = if (event.localSyncStates.isEmpty()) View.GONE else View.VISIBLE
    syncedReports.text = event.syncedReports.joinToString("\n\n") { context.syncedReportText(it) }
    syncedReports.visibility = if (event.syncedReports.isEmpty()) View.GONE else View.VISIBLE
    reportLinkError.text = context.getString(R.string.c3_report_unlinked).takeIf { event.hasUnlinkedReports }.orEmpty()
    reportLinkError.visibility = if (event.hasUnlinkedReports) View.VISIBLE else View.GONE
}

internal fun DoseStatus?.outcomeString(): Int = when (this) {
    DoseStatus.SCHEDULED -> R.string.c3_outcome_scheduled
    DoseStatus.DUE -> R.string.c3_outcome_due
    DoseStatus.SNOOZED -> R.string.c3_outcome_snoozed
    DoseStatus.TAKEN -> R.string.c3_outcome_taken
    DoseStatus.MISSED -> R.string.c3_outcome_missed
    null -> R.string.c3_outcome_unknown
}
