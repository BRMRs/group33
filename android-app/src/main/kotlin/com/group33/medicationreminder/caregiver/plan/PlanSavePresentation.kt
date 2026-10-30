package com.group33.medicationreminder.caregiver.plan

import androidx.annotation.StringRes
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.CacheState
import com.group33.medicationreminder.core.data.contract.OnlineCommandState

/** Display intent only: the host must separately enforce validation, access and command identity. */
enum class PlanDisplayAction { NONE, SAVE, RETRY_UNSENT, CHECK_RESULT, REVIEW_INPUT, REFRESH_CACHE }

data class PlanSavePresentation(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val action: PlanDisplayAction,
    @StringRes val actionLabel: Int?,
)

/** Pure resource mapping, not a state machine or permission to invoke a Repository.
 * No dependency on the unmerged editor ViewModel, session host or production adapters.
 */
object PlanSavePresentations {
    fun editing() = PlanSavePresentation(R.string.c2_plan_editing_title,
        R.string.c2_plan_editing_description, PlanDisplayAction.SAVE, R.string.c2_plan_save)

    fun submitting() = PlanSavePresentation(R.string.c2_plan_submitting_title,
        R.string.c2_plan_submitting_description, PlanDisplayAction.NONE, null)

    fun forCommand(state: OnlineCommandState, cache: CacheState? = null): PlanSavePresentation = when (state) {
        OnlineCommandState.NOT_SUBMITTED -> PlanSavePresentation(R.string.c2_plan_unsent_title,
            R.string.c2_plan_unsent_description, PlanDisplayAction.RETRY_UNSENT, R.string.c2_plan_retry_unsent)
        OnlineCommandState.AWAITING_CONFIRMATION -> PlanSavePresentation(R.string.c2_plan_unknown_title,
            R.string.c2_plan_unknown_description, PlanDisplayAction.CHECK_RESULT, R.string.c2_plan_check_result)
        OnlineCommandState.REJECTED -> PlanSavePresentation(R.string.c2_plan_rejected_title,
            R.string.c2_plan_rejected_description, PlanDisplayAction.REVIEW_INPUT, R.string.c2_plan_review_input)
        OnlineCommandState.CONFIRMED -> when (cache) {
            CacheState.APPLIED -> PlanSavePresentation(R.string.c2_plan_saved_title,
                R.string.c2_plan_saved_description, PlanDisplayAction.NONE, null)
            CacheState.REFRESH_REQUIRED -> PlanSavePresentation(R.string.c2_plan_saved_title,
                R.string.c2_plan_cache_pending_description, PlanDisplayAction.REFRESH_CACHE, R.string.c2_plan_refresh_cache)
            null -> PlanSavePresentation(R.string.c2_plan_saved_title,
                R.string.c2_plan_cache_unknown_description, PlanDisplayAction.NONE, null)
        }
    }
}
