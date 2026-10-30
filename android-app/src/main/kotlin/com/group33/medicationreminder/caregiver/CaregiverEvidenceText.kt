package com.group33.medicationreminder.caregiver

import android.content.Context
import androidx.core.os.ConfigurationCompat
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.LocalActionClassification
import com.group33.shared.contract.ExactAlarmAccess
import com.group33.shared.contract.ReminderCapabilityView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun Context.caregiverInstant(epochMillis: Long): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM)
    .withLocale(ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault())
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))

internal fun Context.syncedReportText(report: CaregiverSyncedReport): String = getString(
    R.string.c3_synced_report,
    getString(when (report.classification) {
        LocalActionClassification.TIMELY_TAKEN -> R.string.c3_report_timely_taken
        LocalActionClassification.LATE_TAKEN -> R.string.c3_report_late_taken
        LocalActionClassification.REMIND_LATER -> R.string.c3_report_remind_later
    }),
    caregiverInstant(report.recordedAtEpochMillis),
)

/** Last-known permission facts, not proof of notification delivery or a live connection. */
internal fun Context.capabilityDetails(capability: ReminderCapabilityView): String = getString(
    R.string.c3_capability_details,
    getString(if (capability.notificationPermissionGranted) R.string.c3_permission_granted else R.string.c3_permission_denied),
    getString(if (capability.channelEnabled) R.string.c3_channel_enabled else R.string.c3_channel_disabled),
    getString(when (capability.exactAlarmAccess) {
        ExactAlarmAccess.NOT_REQUIRED -> R.string.c3_permission_not_required
        ExactAlarmAccess.GRANTED -> R.string.c3_permission_granted
        ExactAlarmAccess.DENIED -> R.string.c3_permission_denied
    }),
    caregiverInstant(capability.receivedAtEpochMillis),
)
