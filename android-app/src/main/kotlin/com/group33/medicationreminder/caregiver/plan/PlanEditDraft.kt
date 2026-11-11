package com.group33.medicationreminder.caregiver.plan

import com.group33.shared.contract.ReminderPlanView
import com.group33.shared.contract.UpdateReminderPlanRequest

/** Local draft only. This does not authorize, validate or submit a command.
 * The host must discard it on session/Elder/access changes and freeze a separate
 * command payload before submitting; unknown operations must never be rebased.
 */
@ConsistentCopyVisibility
data class PlanEditDraft private constructor(
    val confirmed: ReminderPlanView,
    val input: UpdateReminderPlanRequest,
    val conflict: Boolean = false,
    val latestForReview: ReminderPlanView? = null,
) {
    companion object {
        fun open(confirmed: ReminderPlanView, input: UpdateReminderPlanRequest): PlanEditDraft {
            require(confirmed.active)
            require(input.expectedRevision == confirmed.revision)
            return PlanEditDraft(confirmed, input)
        }
    }

    /** Ordinary typing cannot silently change the concurrency precondition. */
    fun changeInput(next: UpdateReminderPlanRequest): PlanEditDraft {
        require(next.expectedRevision == confirmed.revision)
        return copy(input = next)
    }

    fun revisionRejected(): PlanEditDraft = copy(conflict = true, latestForReview = null)

    /** Called only with an authorized, scope-checked repository read by the host. */
    fun offerLatest(latest: ReminderPlanView): PlanEditDraft {
        require(conflict)
        require(latest.id == confirmed.id && latest.elderId == confirmed.elderId &&
            latest.medicationId == confirmed.medicationId)
        require(latest.revision > confirmed.revision)
        require(latestForReview == null || latest.revision >= latestForReview.revision)
        return copy(latestForReview = latest)
    }

    /** Explicit user decision. Creates a new draft intent, never retries an old command.
     * Input is preserved for revalidation; dates/time zones may now require new confirmation.
     */
    fun keepInputAgainstReviewedRevision(): PlanEditDraft {
        check(conflict)
        val latest = checkNotNull(latestForReview)
        check(latest.active) { "An inactive plan cannot be silently reactivated" }
        return PlanEditDraft(latest, input.copy(expectedRevision = latest.revision))
    }
}
