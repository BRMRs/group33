package com.group33.medicationreminder.caregiver

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import com.group33.medicationreminder.core.data.contract.CaregiverDashboardRepository
import com.group33.medicationreminder.core.data.contract.CaregiverElderContext
import com.group33.medicationreminder.core.data.contract.DoseEventRepository
import com.group33.medicationreminder.core.data.contract.LocalDateRange

internal enum class CaregiverFeedbackPage { OVERVIEW, HISTORY }

/**
 * Composes existing C3 Hosts in a dedicated caller-owned, bounded viewport. The caller supplies
 * trusted context and explicit dates; this class does not choose a default time zone, authorize
 * an Elder, install services, or change the application's navigation destinations.
 */
internal class CaregiverFeedbackContent(
    private val activity: AppCompatActivity,
    private val slot: FrameLayout,
    private val dashboard: CaregiverDashboardRepository,
    private val events: DoseEventRepository,
    private val onHistory: (String, LocalDateRange) -> Unit,
) {
    private val overview = CaregiverOverviewHost(activity)
    private val history = CaregiverHistoryHost(activity)
    private var page: CaregiverFeedbackPage? = null
    private var current: CaregiverElderContext? = null
    private var range: LocalDateRange? = null

    init {
        require(slot.childCount == 0) { "C3 requires a dedicated empty viewport" }
        slot.visibility = View.GONE
    }

    @MainThread
    fun show(page: CaregiverFeedbackPage, context: CaregiverElderContext, range: LocalDateRange) {
        if (this.page != page) {
            clear()
            val child = if (page == CaregiverFeedbackPage.OVERVIEW) overview else history
            slot.addView(child, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        this.page = page
        current = context
        this.range = range
        slot.visibility = View.VISIBLE
        when (page) {
            CaregiverFeedbackPage.OVERVIEW -> overview.bind(activity, dashboard, context, range) { elder, dates ->
                // A retained callback from a replaced page/context cannot navigate for old data.
                if (this.page == CaregiverFeedbackPage.OVERVIEW &&
                    current?.let(context::isCurrentFor) == true && elder == context.elderId && dates == this.range
                ) onHistory(elder, dates)
            }
            CaregiverFeedbackPage.HISTORY -> history.bind(
                activity, events, context, range, activity.supportFragmentManager,
            )
        }
    }

    /** Called for other destinations, sign-out, unavailable context, or caller disposal. */
    @MainThread
    fun clear() {
        current = null
        page = null
        val previous = range
        range = null
        if (previous != null) {
            overview.bind(activity, dashboard, null, previous) { _, _ -> }
            history.bind(activity, events, null, previous, activity.supportFragmentManager)
        }
        slot.removeAllViews()
        slot.visibility = View.GONE
    }
}
