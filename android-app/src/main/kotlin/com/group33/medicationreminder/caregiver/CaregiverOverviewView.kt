package com.group33.medicationreminder.caregiver

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.databinding.ItemCaregiverOverviewEventBinding
import com.group33.medicationreminder.databinding.ViewCaregiverOverviewBinding
import com.group33.shared.contract.DeliveryCondition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * C3 screen component for the C1 host. Bind with the host's view LifecycleOwner and session-bound
 * ViewModel; history navigation stays with the host. No demo identity/Repository is installed here.
 * Rebind after detaching/reattaching this view. Production entry awaits C1 navigation and S1 wiring.
 */
class CaregiverOverviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val binding = ViewCaregiverOverviewBinding.inflate(LayoutInflater.from(context), this, true)
    private var collection: Job? = null

    init {
        ViewCompat.setAccessibilityHeading(binding.overviewTitle, true)
        render(CaregiverFeedbackState(null, null, null, isLoading = false))
    }

    @MainThread
    fun bind(
        owner: LifecycleOwner,
        viewModel: CaregiverFeedbackViewModel,
        onHistory: (elderId: String, range: LocalDateRange) -> Unit,
    ) {
        collection?.cancel()
        render(viewModel.state.value)
        binding.refreshButton.setOnClickListener {
            if (CaregiverOverviewPresentation.from(viewModel.state.value).canRefresh) viewModel.refresh()
        }
        binding.historyButton.setOnClickListener {
            val state = viewModel.state.value
            if (CaregiverOverviewPresentation.from(state).canOpenHistory) {
                onHistory(checkNotNull(state.elderId), checkNotNull(state.range))
            }
        }
        collection = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onDetachedFromWindow() {
        collection?.cancel()
        collection = null
        binding.refreshButton.setOnClickListener(null)
        binding.historyButton.setOnClickListener(null)
        render(CaregiverFeedbackState(null, null, null, isLoading = false))
        super.onDetachedFromWindow()
    }

    private fun render(state: CaregiverFeedbackState) {
        val screen = CaregiverOverviewPresentation.from(state)
        val content = screen.content
        binding.stateMessage.setText(when (screen.message) {
            OverviewMessage.SELECT_ELDER -> R.string.c3_select_elder
            OverviewMessage.LOADING -> R.string.c3_loading
            OverviewMessage.ACCESS_UNKNOWN -> R.string.c3_access_unknown
            OverviewMessage.REVOKED -> R.string.c3_revoked
            OverviewMessage.UNAVAILABLE -> R.string.c3_unavailable
            OverviewMessage.NO_LOCAL_DATA -> R.string.c3_no_local_data
            OverviewMessage.EMPTY -> R.string.c3_empty
            OverviewMessage.RESULTS -> R.string.c3_results
        })
        binding.elderName.showText(content?.let { context.getString(R.string.c3_elder, it.elderName) })
        binding.dateRange.showText(state.range?.let { context.getString(R.string.c3_range, it.fromDate, it.throughDate) })
        binding.refreshMessage.showText(when (screen.refreshMessage) {
            OverviewRefreshMessage.NONE -> null
            OverviewRefreshMessage.REFRESHING -> context.getString(R.string.c3_refreshing)
            OverviewRefreshMessage.COMPLETE -> context.getString(R.string.c3_refresh_complete)
            OverviewRefreshMessage.FAILED -> context.getString(R.string.c3_refresh_failed)
            OverviewRefreshMessage.OFFLINE -> context.getString(R.string.c3_offline)
            OverviewRefreshMessage.STORAGE_UNAVAILABLE -> context.getString(R.string.c3_refresh_storage_unavailable)
            OverviewRefreshMessage.INVALID_INPUT -> context.getString(R.string.c3_refresh_invalid_input)
            OverviewRefreshMessage.RANGE_TOO_LARGE -> context.getString(R.string.c3_refresh_range_too_large)
            OverviewRefreshMessage.SERVICE_UNAVAILABLE -> context.getString(R.string.c3_refresh_service_unavailable)
            OverviewRefreshMessage.RETRY_LATER -> context.getString(R.string.c3_refresh_retry_later)
        })
        binding.freshness.showText(content?.takeIf { it.isStale }?.let { context.getString(R.string.c3_stale) })
        binding.lastSync.showText(content?.let {
            it.lastSuccessfulSyncAtEpochMillis?.let { time -> context.getString(R.string.c3_last_sync, formatInstant(time)) }
                ?: context.getString(R.string.c3_sync_time_unknown)
        })
        binding.capability.showText(content?.let {
            val capability = it.capability
            if (capability == null) context.getString(R.string.c3_capability_unknown)
            else context.getString(
                R.string.c3_capability,
                context.getString(when (capability.deliveryCondition) {
                    DeliveryCondition.AVAILABLE -> R.string.c3_capability_available
                    DeliveryCondition.DEGRADED -> R.string.c3_capability_degraded
                    DeliveryCondition.UNAVAILABLE -> R.string.c3_capability_unavailable
                }),
                formatInstant(capability.observedAtEpochMillis),
            )
        })
        binding.capabilityDetails.showText(content?.capability?.let { context.capabilityDetails(it) })
        binding.refreshButton.isEnabled = screen.canRefresh
        binding.refreshButton.setText(if (state.isRefreshing) R.string.c3_refreshing else R.string.c3_refresh)
        binding.historyButton.isEnabled = screen.canOpenHistory
        binding.eventRows.removeAllViews()
        content?.events?.forEach { event ->
            val row = ItemCaregiverOverviewEventBinding.inflate(LayoutInflater.from(context), binding.eventRows, false)
            row.bindEvent(event)
            binding.eventRows.addView(row.root)
        }
    }

    private fun TextView.showText(value: String?) {
        text = value.orEmpty()
        isVisible = value != null
    }

    private fun formatInstant(epochMillis: Long): String = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}
