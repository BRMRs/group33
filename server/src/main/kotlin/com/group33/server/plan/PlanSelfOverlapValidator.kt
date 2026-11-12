package com.group33.server.plan

import com.group33.shared.contract.ReminderPlanDraft
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.TreeSet

/** Full supported (four-digit ISO year) range, including open end = 9999-12-31; not a download horizon scan. */
internal object PlanSelfOverlapValidator {
    fun hasCollision(plan: ReminderPlanDraft): Boolean {
        require(ReminderPlanInputValidator.validate(plan).isEmpty())
        val zone = ZoneId.of(plan.executionTimeZoneId); val rules = zone.rules
        if (rules.isFixedOffset) return false
        val start = LocalDate.parse(plan.startDate)
        val end = plan.endDate?.let(LocalDate::parse) ?: LocalDate.of(9999, 12, 31)
        val times = plan.localTimes.map(LocalTime::parse).toSet()
        var cursor = start.atStartOfDay().toInstant(ZoneOffset.UTC).minus(3, ChronoUnit.DAYS)
        val limit = end.plusDays(3).atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC)
        val pendingGapInstants = TreeSet<Instant>()
        while (true) {
            val transition = rules.nextTransition(cursor) ?: break
            if (transition.instant > limit) break
            cursor = transition.instant
            // A gap maps its missing nominal interval onto [transition instant, instant + gap duration).
            // Results older than the next transition cannot collide with any future gap input.
            pendingGapInstants.headSet(cursor).clear()
            if (!transition.isGap) continue
            var day = maxOf(start, transition.dateTimeBefore.toLocalDate())
            val last = minOf(end, transition.dateTimeAfter.toLocalDate())
            while (day <= last) {
                for (time in times) {
                    val nominal = day.atTime(time)
                    if (nominal < transition.dateTimeBefore || nominal >= transition.dateTimeAfter) continue
                    val instant = resolve(nominal, zone)
                    if (!pendingGapInstants.add(instant)) return true
                    val actual = instant.atZone(zone).toLocalDateTime()
                    if (actual != nominal && actual.toLocalDate() in start..end && actual.toLocalTime() in times
                        && resolve(actual, zone) == instant) return true
                }
                day = day.plusDays(1)
            }
        }
        // Distinct valid wall times cannot map to the same instant in the same zone. Folds select only
        // the earlier instant once. Thus every possible collision includes at least one gap input above.
        return false
    }

    /** P0_TIME_V1: shift a gap by its full duration; select the earlier instant in a fold. */
    internal fun resolve(nominal: LocalDateTime, zone: ZoneId): Instant {
        val offsets = zone.rules.getValidOffsets(nominal)
        return when {
            offsets.isEmpty() -> nominal.toInstant(checkNotNull(zone.rules.getTransition(nominal)).offsetBefore)
            else -> offsets.map(nominal::toInstant).min()
        }
    }
}
