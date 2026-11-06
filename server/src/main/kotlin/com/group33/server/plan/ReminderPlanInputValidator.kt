package com.group33.server.plan

import com.group33.shared.contract.ReminderPlanDraft
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Internal diagnostics, not new wire error codes. HTTP mapping belongs to the plan service slice. */
internal enum class PlanInputFailure {
    REQUIRED, INVALID_LOCAL_TIME, DUPLICATE_LOCAL_TIME, INVALID_DATE, DATE_BEFORE_START, INVALID_TIME_ZONE,
}

internal data class PlanInputViolation(val field: String, val failure: PlanInputFailure)

/**
 * Validates a decoded P0 plan draft without normalizing or mutating it.
 * An empty result proves input shape only, not authorization, overlap safety or a confirmed plan.
 * Missing/null required JSON fields remain the transport decoder's responsibility.
 */
internal object ReminderPlanInputValidator {
    private val timePattern = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val availableZoneIds = ZoneId.getAvailableZoneIds().toSet()

    fun validate(plan: ReminderPlanDraft): List<PlanInputViolation> = buildList {
        if (plan.localTimes.isEmpty()) add(violation("localTimes", PlanInputFailure.REQUIRED))
        if (plan.localTimes.any { !timePattern.matches(it) }) {
            add(violation("localTimes", PlanInputFailure.INVALID_LOCAL_TIME))
        }
        // Reject duplicates before any later canonicalization. Never deduplicate to make input valid.
        if (plan.localTimes.distinct().size != plan.localTimes.size) {
            add(violation("localTimes", PlanInputFailure.DUPLICATE_LOCAL_TIME))
        }
        val start = parseDate(plan.startDate)
        val end = plan.endDate?.let(::parseDate)
        if (start == null) add(violation("startDate", PlanInputFailure.INVALID_DATE))
        // Only null is open-ended; an empty/blank wire string is not an absent editor field.
        if (plan.endDate != null && end == null) add(violation("endDate", PlanInputFailure.INVALID_DATE))
        if (start != null && end != null && end < start) {
            add(violation("endDate", PlanInputFailure.DATE_BEFORE_START))
        }
        // Region/alias IDs from the runtime TZDB, consistent with C2. ZoneId.of alone also accepts offsets.
        if (plan.executionTimeZoneId !in availableZoneIds) {
            add(violation("executionTimeZoneId", PlanInputFailure.INVALID_TIME_ZONE))
        }
    }

    private fun parseDate(value: String): LocalDate? {
        if (!datePattern.matches(value)) return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun violation(field: String, failure: PlanInputFailure) = PlanInputViolation("plan.$field", failure)
}
