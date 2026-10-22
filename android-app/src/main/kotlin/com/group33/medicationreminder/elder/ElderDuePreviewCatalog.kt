package com.group33.medicationreminder.elder

import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.model.DoseStatus
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Fictional Elder Due / upcoming rows for accessibility layout preview (#19).
 * Not authenticated identity, Room truth, or caregiver-authored Reminder plan data.
 */
object ElderDuePreviewCatalog {
    private const val SNOOZE_MILLIS = 600_000L
    private const val RESPONSE_WINDOW_MILLIS = 7_200_000L

    fun snoozeMillis(): Long = SNOOZE_MILLIS

    fun dayPlan(
        clock: Clock,
        dueMedicationName: String,
        upcomingMedicationName: String,
        dosageText: String,
    ): Pair<ElderDueCardUi, List<ElderUpcomingUi>> {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val duePlanned = today.atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
        val due = ElderDueCardUi(
            occurrence = OccurrenceKey(planId = "preview-plan-a", localDate = today.toString(), localTime = "08:00"),
            plannedTimeText = "08:00",
            medicationName = dueMedicationName,
            dosageText = dosageText,
            instructions = null,
            plannedAtEpochMillis = duePlanned,
            responseDeadlineEpochMillis = duePlanned + RESPONSE_WINDOW_MILLIS,
            status = DoseStatus.DUE,
        )
        val upcoming = listOf(
            ElderUpcomingUi(
                medicationName = upcomingMedicationName,
                plannedTimeText = "12:00",
                dosageText = dosageText,
            ),
        )
        return due to upcoming
    }

    fun remindLaterOutcome(
        recordedAtEpochMillis: Long,
        responseDeadlineEpochMillis: Long,
        zone: ZoneId,
    ): ElderRemindLaterOutcome {
        val nominal = recordedAtEpochMillis + SNOOZE_MILLIS
        return if (nominal < responseDeadlineEpochMillis) {
            val time = java.time.Instant.ofEpochMilli(nominal).atZone(zone).toLocalTime()
            ElderRemindLaterOutcome.NextReminder("%02d:%02d".format(time.hour, time.minute))
        } else {
            ElderRemindLaterOutcome.WindowEndsBeforeReminder
        }
    }
}
