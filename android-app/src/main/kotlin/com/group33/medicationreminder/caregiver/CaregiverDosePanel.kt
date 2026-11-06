package com.group33.medicationreminder.caregiver

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.button.MaterialButton
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.UserView
import com.group33.shared.model.DoseStatus
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Both pages consume the same session-bound cache and only present accepted results. */
internal class CaregiverDosePanel(
    private val activity: AppCompatActivity,
    private val host: LinearLayout,
    private val dashboard: CaregiverDashboardRepository,
    private val events: DoseEventRepository,
) {
    private val header = text().apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
    private val result = text().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val refresh = MaterialButton(activity).apply {
        setText(R.string.dose_records_refresh)
        setOnClickListener { overview?.refresh() }
        host.addView(this)
    }
    private val store = ViewModelStore()
    private var current: CaregiverElderContext? = null
    private var overview: CaregiverFeedbackViewModel? = null
    private var history: CaregiverHistoryViewModel? = null
    private var overviewJob: Job? = null
    private var historyJob: Job? = null
    private var showHistory = false

    private fun text() = TextView(activity).apply { textSize = 18f; host.addView(this) }

    fun show(context: CaregiverElderContext?, elder: UserView?, records: Boolean) {
        host.isVisible = context != null && elder != null
        if (context != current) {
            clear()
            if (context == null || elder == null) return
            current = context
            // Cover the current day in any plan zone without inferring one from the caregiver device.
            val today = LocalDate.now(ZoneOffset.UTC)
            val range = LocalDateRange(today.minusDays(29).toString(), today.plusDays(1).toString())
            val models = ViewModelProvider.create(store, viewModelFactory {
                initializer { CaregiverFeedbackViewModel(dashboard, elder.id, range, context.scope) }
                initializer { CaregiverHistoryViewModel(events, context.scope, elder, range) }
            })
            val overviewModel = models[CaregiverFeedbackViewModel::class]
            val historyModel = models[CaregiverHistoryViewModel::class]
            overview = overviewModel
            history = historyModel
            overviewJob = activity.lifecycleScope.launch { overviewModel.state.collect { render() } }
            historyJob = activity.lifecycleScope.launch { historyModel.state.collect { render() } }
            overviewModel.refresh()
        } else if (records != showHistory) {
            overview?.refresh()
        }
        showHistory = records
        render()
    }

    private fun render() {
        val state = overview?.state?.value ?: return
        val historical = history?.state?.value ?: return
        header.setText(if (showHistory) R.string.dose_records_history else R.string.dose_records_overview)
        refresh.isEnabled = !state.isRefreshing
        val cache = state.overview
        val rows = if (showHistory) historical.events else cache?.value?.events.orEmpty()
        val accepted = rows.filter {
            it.lastAcceptedStatus == DoseStatus.TAKEN && it.serverAcceptedTakenAtEpochMillis != null
        }.sortedByDescending { it.serverAcceptedTakenAtEpochMillis }
        val authorized = state.access == AccessState.AUTHORIZED && cache?.access == AccessState.AUTHORIZED
        result.text = when {
            !authorized || cache?.hasLocalData != true -> activity.getString(
                if (state.isRefreshing || state.isLoading) R.string.dose_records_loading else R.string.dose_records_unavailable)
            accepted.isEmpty() -> activity.getString(R.string.dose_records_empty)
            showHistory -> accepted.joinToString("\n\n", transform = ::formatTaken)
            else -> formatTaken(accepted.first())
        }
        if (authorized && state.isStale) {
            result.append("\n" + activity.getString(R.string.chain_stale))
        }
    }

    private fun formatTaken(event: DoseEventState): String =
        activity.getString(R.string.dose_records_taken, event.medicationSnapshot.displayName,
            activity.caregiverInstant(checkNotNull(event.serverAcceptedTakenAtEpochMillis)))

    fun clear() {
        current = null
        overviewJob?.cancel()
        historyJob?.cancel()
        store.clear()
        overview = null
        history = null
        result.text = ""
    }
}
