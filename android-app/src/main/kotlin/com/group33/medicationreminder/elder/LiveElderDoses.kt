package com.group33.medicationreminder.elder

import com.group33.medicationreminder.core.data.contract.TrustedSessionContext
import com.group33.medicationreminder.core.reminder.InitialTiming
import com.group33.medicationreminder.core.reminder.ReminderTimingPolicy
import com.group33.medicationreminder.core.reminder.ReminderWindow
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.MedicationView
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.ReminderPlanView
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
internal data class LocalElderOccurrence(
    val key: OccurrenceKey,
    val planRevision: Long,
    val medication: MedicationText,
    val plannedAt: Long,
    val executionZone: String,
    val offsetSeconds: Int,
    val generatedAt: Long,
    val planSnapshotAt: Long,
    val takenAt: Long? = null,
    val snoozedUntil: Long? = null,
)

@Serializable
internal data class LocalElderAction(
    val id: String,
    val occurrences: List<OccurrenceKey>,
    val kind: DoseActionKind,
    val recordedAt: Long,
)

internal data class ElderDoseGroup(
    val plannedAt: Long,
    val occurrences: List<LocalElderOccurrence>,
    val canSnooze: Boolean,
) {
    val keys get() = occurrences.map { it.key }
}

internal data class ElderDoseScreen(
    val session: TrustedSessionContext? = null,
    val due: ElderDoseGroup? = null,
    val notificationsEnabled: Boolean = false,
    val available: Boolean = false,
)

internal interface LiveElderDoses {
    val state: StateFlow<ElderDoseScreen>
    suspend fun refresh()
    suspend fun record(session: TrustedSessionContext, group: ElderDoseGroup, kind: DoseActionKind): Boolean
}

internal fun nextElderOccurrences(
    plans: List<ReminderPlanView>,
    medications: List<MedicationView>,
    now: Instant,
    snapshotAt: Long,
): List<LocalElderOccurrence> {
    val horizon = now.plus(Duration.ofHours(24))
    val medicationsById = medications.associateBy { it.id }
    return plans.filter { it.active }.flatMap { plan ->
        val medication = medicationsById[plan.medicationId]?.takeIf { it.active && it.elderId == plan.elderId }
            ?: return@flatMap emptyList()
        val zone = ZoneId.of(plan.executionTimeZoneId)
        val start = LocalDate.parse(plan.startDate)
        val end = plan.endDate?.let(LocalDate::parse)
        val firstDay = now.atZone(zone).toLocalDate().minusDays(1)
        val lastDay = horizon.atZone(zone).toLocalDate()
        generateSequence(firstDay) { it.plusDays(1) }.takeWhile { it <= lastDay }
            .filter { it >= start && (end == null || it <= end) }
            .flatMap { day ->
                plan.localTimes.asSequence().mapNotNull { nominal ->
                    // java.time shifts gaps forward by the gap and selects the earlier offset in a fold.
                    val resolved = day.atTime(LocalTime.parse(nominal)).atZone(zone)
                    val planned = resolved.toInstant()
                    if (planned >= horizon || !now.isBefore(planned.plus(InitialTiming.responseWindow)) ||
                        planned.toEpochMilli() < maxOf(plan.effectiveFromEpochMillis, plan.confirmedAtEpochMillis)) {
                        return@mapNotNull null
                    }
                    LocalElderOccurrence(OccurrenceKey(plan.id, day.toString(), nominal), plan.revision,
                        MedicationText(medication.displayName, medication.dosageText, medication.instructions),
                        planned.toEpochMilli(), zone.id, resolved.offset.totalSeconds, now.toEpochMilli(), snapshotAt)
                }
            }.toList()
    }
}

internal fun dueElderDose(occurrences: List<LocalElderOccurrence>, clock: Clock): ElderDoseGroup? {
    val now = clock.millis()
    val timing = ReminderTimingPolicy(clock)
    val due = occurrences.filter { it.takenAt == null && (it.snoozedUntil ?: Long.MIN_VALUE) <= now }
        .groupBy { it.plannedAt }.toSortedMap().entries.firstOrNull { (plannedAt, _) ->
            timing.evaluate(ReminderWindow(Instant.ofEpochMilli(plannedAt))).canRespond
        } ?: return null
    val window = ReminderWindow(Instant.ofEpochMilli(due.key))
    return ElderDoseGroup(due.key, due.value, timing.evaluate(window).nextSnoozeAt != null)
}
