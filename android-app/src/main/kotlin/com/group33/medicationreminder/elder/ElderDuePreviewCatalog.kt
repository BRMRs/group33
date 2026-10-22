package com.group33.medicationreminder.elder

import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.model.DoseStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Fictional Elder Due / upcoming rows for accessibility layout preview (#19).
 * Not authenticated identity, Room truth, or caregiver-authored Reminder plan data.
 *
 * Default [PREVIEW_CLOCK] is a labeled simulated instant (08:30 Asia/Shanghai on 2026-10-22)
 * inside the 08:00 response window so wall-clock afternoon cannot falsely keep Due actionable.
 */
object ElderDuePreviewCatalog {
    private const val SNOOZE_MILLIS = 600_000L
    const val RESPONSE_WINDOW_MILLIS = 7_200_000L

    val PREVIEW_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** Simulated preview time: 2026-10-22 08:30 Asia/Shanghai. */
    val PREVIEW_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-22T00:30:00Z"), PREVIEW_ZONE)

    fun snoozeMillis(): Long = SNOOZE_MILLIS

    fun responseWindowMillis(): Long = RESPONSE_WINDOW_MILLIS

    /** Response window is [plannedAt, deadline); the exact deadline is not actionable. */
    fun isWithinResponseWindow(
        nowEpochMillis: Long,
        plannedAtEpochMillis: Long,
        responseDeadlineEpochMillis: Long,
    ): Boolean = nowEpochMillis >= plannedAtEpochMillis && nowEpochMillis < responseDeadlineEpochMillis

    fun canAcceptElderAction(
        status: DoseStatus,
        nowEpochMillis: Long,
        plannedAtEpochMillis: Long,
        responseDeadlineEpochMillis: Long,
    ): Boolean {
        if (status != DoseStatus.DUE && status != DoseStatus.SNOOZED) return false
        return isWithinResponseWindow(nowEpochMillis, plannedAtEpochMillis, responseDeadlineEpochMillis)
    }

    fun dayPlan(
        clock: Clock,
        dueMedicationName: String,
        upcomingMedicationName: String,
        dosageText: String,
    ): Pair<ElderDueCardUi, List<ElderUpcomingUi>> {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val duePlanned = today.atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
        val deadline = duePlanned + RESPONSE_WINDOW_MILLIS
        val now = clock.millis()
        val status = when {
            now < duePlanned -> DoseStatus.SCHEDULED
            now < deadline -> DoseStatus.DUE
            else -> DoseStatus.MISSED
        }
        val due = ElderDueCardUi(
            occurrence = OccurrenceKey(planId = "preview-plan-a", localDate = today.toString(), localTime = "08:00"),
            plannedTimeText = "08:00",
            medicationName = dueMedicationName,
            dosageText = dosageText,
            instructions = null,
            plannedAtEpochMillis = duePlanned,
            responseDeadlineEpochMillis = deadline,
            status = status,
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
            val time = Instant.ofEpochMilli(nominal).atZone(zone).toLocalTime()
            ElderRemindLaterOutcome.NextReminder("%02d:%02d".format(time.hour, time.minute))
        } else {
            ElderRemindLaterOutcome.WindowEndsBeforeReminder
        }
    }
}
