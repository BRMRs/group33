package com.group33.medicationreminder.caregiver

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputLayout
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.databinding.ItemCaregiverOverviewEventBinding
import com.group33.medicationreminder.databinding.ViewCaregiverHistoryBinding
import com.group33.medicationreminder.databinding.ViewCaregiverHistoryHeaderBinding
import com.group33.medicationreminder.databinding.ViewCaregiverHistoryDetailsBinding
import com.group33.shared.contract.OccurrenceKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** C3 read-only screen. All controls scroll with the list, including at 200% font on a narrow phone. */
class CaregiverHistoryView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val binding = ViewCaregiverHistoryBinding.inflate(LayoutInflater.from(context), this, true)
    private val header = ViewCaregiverHistoryHeaderBinding.inflate(LayoutInflater.from(context), binding.root, false)
    private val adapter = EventsAdapter()
    private var datePicker: DialogFragment? = null
    private var bindingGeneration = Any()
    private var collection: Job? = null
    private var rangeErrorDescription: String? = null
    private var renderedSelection: Triple<RepositoryScope?, String?, LocalDateRange?>? = null

    init {
        binding.root.setItemsCanFocus(true)
        binding.root.addHeaderView(header.root, null, false)
        binding.root.adapter = adapter
        ViewCompat.setAccessibilityHeading(header.historyTitle, true)
        for (input in listOf(header.historyFromLayout, header.historyThroughLayout)) {
            input.setTextInputAccessibilityDelegate(object : TextInputLayout.AccessibilityDelegate(input) {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    // Keep Material hints/actions; expose the existing range-level error on both fields.
                    rangeErrorDescription?.let {
                        info.isContentInvalid = true
                        info.error = it
                    }
                }
            })
        }
        render(CaregiverHistoryState(null, null, null, isLoading = false))
    }

    /** Use the host's view LifecycleOwner; rebind after detaching/reattaching the component. */
    @MainThread
    fun bind(owner: LifecycleOwner, viewModel: CaregiverHistoryViewModel, fragmentManager: FragmentManager) {
        bindingGeneration = Any()
        dismissDatePicker()
        (fragmentManager.findFragmentByTag(DATE_PICKER_TAG) as? DialogFragment)?.dismissAllowingStateLoss()
        collection?.cancel()
        render(viewModel.state.value)
        header.historyApply.setOnClickListener {
            viewModel.applyRange(header.historyFrom.text.toString(), header.historyThrough.text.toString())
        }
        header.historyRetry.setOnClickListener { viewModel.retryObservation() }
        header.historyPickRange.setOnClickListener { showDatePicker(owner, viewModel, fragmentManager) }
        adapter.onSelect = { occurrence ->
            val state = viewModel.state.value
            if (Triple(state.scope, state.elder?.id, state.range) == renderedSelection &&
                state.events.any { it.occurrence == occurrence }) viewModel.selectEvent(occurrence)
        }
        adapter.onClose = { occurrence ->
            if (viewModel.state.value.selectedOccurrence == occurrence) viewModel.closeDetails()
        }
        binding.root.setOnItemClickListener { _, _, position, _ ->
            adapter.rows.getOrNull(position - binding.root.headerViewsCount)?.takeUnless { it.isDetail }
                ?.let { adapter.onSelect?.invoke(it.event.occurrence) }
        }
        collection = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect { render(it) } }
        }
    }

    private fun showDatePicker(owner: LifecycleOwner, viewModel: CaregiverHistoryViewModel, manager: FragmentManager) {
        if (datePicker != null || manager.isStateSaved || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val state = viewModel.state.value
        val applied = state.range ?: return
        if (state.elder == null || state.scope == null || state.access == AccessState.REVOKED) return
        val generation = bindingGeneration
        val selection = Triple(state.scope, state.elder.id, applied)
        // Calendar values are local dates, without a timestamp or phone-zone conversion.
        val draft = runCatching {
            val from = LocalDate.parse(header.historyFrom.text.toString().trim())
            val through = LocalDate.parse(header.historyThrough.text.toString().trim())
            require(!through.isBefore(from))
            from to through
        }.getOrElse { LocalDate.parse(applied.fromDate) to LocalDate.parse(applied.throughDate) }
        fun applyDates(fromText: String, throughText: String) {
            val current = viewModel.state.value
            if (bindingGeneration === generation && isAttachedToWindow &&
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                current.access != AccessState.REVOKED &&
                Triple(current.scope, current.elder?.id, current.range) == selection) {
                header.historyFrom.setText(fromText)
                header.historyThrough.setText(throughText)
                // Reading is explicit: confirming dates must not query or replace the current results.
            }
        }
        val picker = CaregiverHistoryDateRangeDialog.create(draft.first.toString(), draft.second.toString())
        picker.onDates = ::applyDates
        picker.onClosed = { if (datePicker === picker) datePicker = null }
        datePicker = picker
        picker.show(manager, DATE_PICKER_TAG)
    }

    private fun dismissDatePicker() {
        datePicker?.let {
            (it as? CaregiverHistoryDateRangeDialog)?.let { picker -> picker.onDates = null; picker.onClosed = null }
            it.dismissAllowingStateLoss()
        }
        datePicker = null
    }

    override fun onDetachedFromWindow() {
        bindingGeneration = Any()
        dismissDatePicker()
        collection?.cancel()
        collection = null
        header.historyApply.setOnClickListener(null)
        header.historyRetry.setOnClickListener(null)
        header.historyPickRange.setOnClickListener(null)
        adapter.onSelect = null
        adapter.onClose = null
        binding.root.onItemClickListener = null
        render(CaregiverHistoryState(null, null, null, isLoading = false))
        super.onDetachedFromWindow()
    }

    private fun render(state: CaregiverHistoryState) {
        val first = binding.root.firstVisiblePosition
        val anchor = adapter.rows.getOrNull(first - binding.root.headerViewsCount)
        val topOffset = (binding.root.getChildAt(0)?.top ?: binding.root.paddingTop) - binding.root.paddingTop
        val selection = Triple(state.scope, state.elder?.id, state.range)
        val contextChanged = selection != renderedSelection
        if (contextChanged || state.access == AccessState.REVOKED) dismissDatePicker()
        if (contextChanged) {
            header.historyFrom.setText(state.range?.fromDate.orEmpty())
            header.historyThrough.setText(state.range?.throughDate.orEmpty())
            renderedSelection = selection
            binding.root.setSelection(0)
        }
        val editable = state.elder != null && state.scope != null && state.access != AccessState.REVOKED
        header.historyFrom.isEnabled = editable
        header.historyThrough.isEnabled = editable
        header.historyApply.isEnabled = editable
        header.historyPickRange.isEnabled = editable
        header.historyElder.showText(state.elder?.takeIf { state.authorized }?.let { context.getString(R.string.c3_elder, it.displayName) })
        header.historyAppliedRange.showText(state.range?.let { context.getString(R.string.c3_history_applied_range, it.fromDate, it.throughDate) })
        rangeErrorDescription = state.rangeError?.let {
            context.getString(when (it) {
                HistoryRangeError.INVALID_DATE -> R.string.c3_history_invalid_date
                HistoryRangeError.REVERSED -> R.string.c3_history_reversed
                HistoryRangeError.TOO_MANY_DATES -> R.string.c3_history_too_long
            })
        }
        header.historyRangeError.showText(rangeErrorDescription)
        header.historyState.setText(when (state.message) {
            HistoryMessage.SELECT_ELDER -> R.string.c3_select_elder
            HistoryMessage.LOADING -> R.string.c3_loading
            HistoryMessage.ACCESS_UNKNOWN -> R.string.c3_access_unknown
            HistoryMessage.REVOKED -> R.string.c3_revoked
            HistoryMessage.UNAVAILABLE -> R.string.c3_unavailable
            HistoryMessage.NO_LOCAL_DATA -> R.string.c3_no_local_data
            HistoryMessage.EMPTY -> R.string.c3_empty
            HistoryMessage.RESULTS -> R.string.c3_results
        })
        header.historyReadError.isVisible = state.observationFailed
        header.historyRetry.isVisible = state.observationFailed && editable && state.rangeError != HistoryRangeError.TOO_MANY_DATES
        header.historyFreshness.showText(state.cached?.takeIf { state.authorized && it.hasLocalData }?.let { cached ->
            listOfNotNull(
                context.getString(R.string.c3_stale).takeIf { state.isStale },
                cached.lastSuccessfulSyncAtEpochMillis?.let { context.getString(R.string.c3_last_sync, formatInstant(it)) }
                    ?: context.getString(R.string.c3_sync_time_unknown),
            ).joinToString("\n")
        })
        // Clear actual recycled/removed nodes immediately, before the ListView's next layout.
        for (index in 0 until binding.root.childCount) {
            when (val old = binding.root.getChildAt(index).tag) {
                is ItemCaregiverOverviewEventBinding -> old.clearEvent()
                is ViewCaregiverHistoryDetailsBinding -> {
                    old.historyDetailEvent.clearEvent()
                    old.historyInstructions.text = ""
                    old.historyCloseDetails.setOnClickListener(null)
                    old.root.isVisible = false
                }
            }
        }
        val selected = state.selectedEvent?.occurrence
        adapter.selected = selected
        adapter.rows = buildList {
            for (event in state.events.sortedByDescending { it.generationEvidence.plannedAtEpochMillis }) {
                add(HistoryRow(event))
                if (event.occurrence == selected) add(HistoryRow(event, isDetail = true))
            }
        }
        adapter.notifyDataSetChanged()
        if (!contextChanged) {
            val restored = anchor?.let { previous ->
                adapter.rows.indexOfFirst { it.event.occurrence == previous.event.occurrence && it.isDetail == previous.isDetail }
            } ?: -1
            if (restored >= 0) {
                binding.root.setSelectionFromTop(restored + binding.root.headerViewsCount, topOffset)
            } else if (anchor != null) {
                val eventRow = adapter.rows.indexOfFirst { !it.isDetail && it.event.occurrence == anchor.event.occurrence }
                if (eventRow >= 0) binding.root.setSelectionFromTop(eventRow + binding.root.headerViewsCount, 0)
            } else if (first == 0) {
                binding.root.setSelectionFromTop(0, topOffset)
            }
        }
    }

    private data class HistoryRow(val event: DoseEventState, val isDetail: Boolean = false)

    private inner class EventsAdapter : BaseAdapter() {
        var rows: List<HistoryRow> = emptyList()
        var selected: OccurrenceKey? = null
        var onSelect: ((OccurrenceKey) -> Unit)? = null
        var onClose: ((OccurrenceKey) -> Unit)? = null
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position].isDetail) 1 else 0
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = !rows[position].isDetail
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = rows[position]
            val selection = renderedSelection
            if (item.isDetail) {
                val detail = convertView?.tag as? ViewCaregiverHistoryDetailsBinding
                    ?: ViewCaregiverHistoryDetailsBinding.inflate(LayoutInflater.from(context), parent, false)
                        .also { it.root.tag = it }
                detail.root.isVisible = true
                ViewCompat.setAccessibilityHeading(detail.historyDetailTitle, true)
                detail.historyDetailEvent.bindEvent(item.event.row())
                detail.historyInstructions.text = item.event.medicationSnapshot.instructions?.let {
                    context.getString(R.string.c3_history_instructions, it)
                } ?: context.getString(R.string.c3_history_no_instructions)
                detail.historyCloseDetails.setOnClickListener {
                    if (selection == renderedSelection) onClose?.invoke(item.event.occurrence)
                }
                return detail.root
            }
            val row = convertView?.tag as? ItemCaregiverOverviewEventBinding
                ?: ItemCaregiverOverviewEventBinding.inflate(LayoutInflater.from(context), parent, false).also { it.root.tag = it }
            row.bindEvent(item.event.row())
            row.root.isSelected = item.event.occurrence == selected
            row.root.setOnClickListener {
                if (selection == renderedSelection) onSelect?.invoke(item.event.occurrence)
            }
            return row.root
        }
    }

    private fun ItemCaregiverOverviewEventBinding.clearEvent() {
        medication.text = ""
        dosage.text = ""
        plannedTime.text = ""
        outcome.text = ""
        localSync.text = ""
        syncedReports.text = ""
        reportLinkError.text = ""
        root.setOnClickListener(null)
    }

    private fun DoseEventState.row() = toCaregiverEventRow()

    private fun TextView.showText(value: String?) {
        text = value.orEmpty()
        isVisible = value != null
    }

    private companion object { const val DATE_PICKER_TAG = "c3.history.date-range" }

    private fun formatInstant(epochMillis: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault())
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
}
