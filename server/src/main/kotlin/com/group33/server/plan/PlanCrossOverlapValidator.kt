package com.group33.server.plan

import com.group33.shared.contract.ReminderPlanDraft
import java.time.*

/** Exact full-range intersection of daily resolved instants; no seven-day or sampled-year horizon. */
internal object PlanCrossOverlapValidator {
    private const val DAY = 86_400L
    private data class Span(val start: Long, val end: Long, val residues: Set<Long>, val side: Int)

    fun hasCollision(a: ReminderPlanDraft, b: ReminderPlanDraft): Boolean {
        require(ReminderPlanInputValidator.validate(a).isEmpty() && ReminderPlanInputValidator.validate(b).isEmpty())
        val active = arrayOf(mutableListOf<Span>(), mutableListOf())
        for (span in (spans(a, 0) + spans(b, 1)).sortedBy { it.start }) {
            active.forEach { list -> list.removeAll { it.end <= span.start } }
            for (other in active[1 - span.side]) {
                val lower = maxOf(span.start, other.start); val upper = minOf(span.end, other.end)
                val small = if (span.residues.size < other.residues.size) span.residues else other.residues
                val large = if (small === span.residues) other.residues else span.residues
                for (residue in small) if (residue in large &&
                    lower + Math.floorMod(residue - Math.floorMod(lower, DAY), DAY) < upper) return true
            }
            active[span.side].add(span)
        }
        return false
    }

    private fun spans(plan: ReminderPlanDraft, side: Int): List<Span> {
        val zone = ZoneId.of(plan.executionTimeZoneId)
        val first = LocalDate.parse(plan.startDate).atStartOfDay()
        val after = (plan.endDate?.let(LocalDate::parse) ?: LocalDate.of(9999, 12, 31)).plusDays(1).atStartOfDay()
        val times = plan.localTimes.map { LocalTime.parse(it).toSecondOfDay().toLong() }
        val residues = mutableMapOf<Int, Set<Long>>()
        val output = mutableListOf<Span>()
        var localStart = first
        fun append(until: LocalDateTime) {
            if (until <= localStart) return
            val offset = (localStart.toEpochSecond(ZoneOffset.UTC) - PlanSelfOverlapValidator.resolve(localStart, zone).epochSecond).toInt()
            val values = residues.getOrPut(offset) { times.map { Math.floorMod(it - offset, DAY) }.toSet() }
            output.add(Span(localStart.toEpochSecond(ZoneOffset.UTC) - offset,
                until.toEpochSecond(ZoneOffset.UTC) - offset, values, side))
            localStart = until
        }
        var cursor = first.toInstant(ZoneOffset.UTC).minusSeconds(3 * DAY)
        val limit = after.toInstant(ZoneOffset.UTC).plusSeconds(3 * DAY)
        while (true) {
            val change = zone.rules.nextTransition(cursor) ?: break
            if (change.instant > limit) break
            cursor = change.instant
            // P0_TIME_V1 keeps offsetBefore throughout a missing interval and throughout the
            // repeated interval's earlier occurrence. Both change offset at max(before, after).
            val cut = maxOf(change.dateTimeBefore, change.dateTimeAfter)
            if (cut >= after) break
            if (cut > localStart) append(cut)
        }
        append(after)
        return output
    }
}
