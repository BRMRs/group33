package com.group33.medicationreminder.integration

import com.group33.medicationreminder.caregiver.CaregiverHistoryViewModel
import com.group33.medicationreminder.core.data.contract.CaregiverElderContext
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import java.time.LocalDate

/** Local input captured by the future agreed date owner, not a wire DTO or an identity claim. */
internal class CaregiverFeedbackDate(
    val context: CaregiverElderContext,
    val today: LocalDate,
) {
    override fun toString(): String = "CaregiverFeedbackDate(redacted)"
}

/**
 * C1 consumption adapter for an explicitly supplied date. It does not select a time zone,
 * consult the device clock, infer a date from plans, or cache a previous Elder's range.
 * Production wiring awaits the date-source decision in #15; null means unavailable.
 * Call on the host's serialized context thread, as with the existing renderer.
 */
internal class CaregiverFeedbackRangeInput(
    private val currentContext: () -> CaregiverElderContext?,
    private val readDate: () -> CaregiverFeedbackDate?,
) {
    fun resolve(requestedContext: CaregiverElderContext): LocalDateRange? {
        if (!isCurrent(requestedContext)) return null
        val date = readDate() ?: return null
        if (!date.context.isCurrentFor(requestedContext)) return null
        val range = CaregiverHistoryViewModel.defaultRange(date.today)
        // A callback may synchronously invalidate/switch context while supplying the date.
        return range.takeIf { isCurrent(requestedContext) }
    }

    private fun isCurrent(expected: CaregiverElderContext): Boolean =
        currentContext()?.let(expected::isCurrentFor) == true
}
