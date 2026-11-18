package com.group33.medicationreminder.caregiver

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.group33.medicationreminder.R
import com.group33.medicationreminder.databinding.ViewCaregiverHistoryDateDialogBinding
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.max

/** One calendar interaction at every font size. Only the weekday/day grid scrolls horizontally. */
internal class CaregiverHistoryDateRangeDialog : DialogFragment() {
    var onDates: ((String, String) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    private var from: LocalDate? = null
    private var through: LocalDate? = null
    private lateinit var month: YearMonth
    private lateinit var binding: ViewCaregiverHistoryDateDialogBinding
    private val cells = mutableListOf<Pair<LocalDate, MaterialButton>>()
    private var minimumCellWidth = 0
    private var cellHeight = 0
    private var columnWidth = 0
    private val locale: Locale get() = ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        from = (savedInstanceState ?: requireArguments()).getString(FROM)?.let(LocalDate::parse)
        through = (savedInstanceState ?: requireArguments()).getString(THROUGH)?.let(LocalDate::parse)
        month = savedInstanceState?.getString(MONTH)?.let(YearMonth::parse) ?: YearMonth.from(checkNotNull(from))
        binding = ViewCaregiverHistoryDateDialogBinding.inflate(requireActivity().layoutInflater)
        ViewCompat.setAccessibilityHeading(binding.historyCalendarMonth, true)
        binding.historyCalendarPrevious.setOnClickListener { moveMonth(-1) }
        binding.historyCalendarNext.setOnClickListener { moveMonth(1) }
        binding.historyDialogCancel.setOnClickListener { dismiss() }
        binding.historyDialogConfirm.setOnClickListener {
            val start = from
            val end = through
            if (start != null && end != null) {
                onDates?.invoke(start.toString(), end.toString())
                dismiss()
            }
        }
        binding.historyCalendarScroll.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) resizeColumns()
        }
        measureCells()
        drawMonth()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.c3_history_pick_range)
            .setBackgroundInsetStart(0)
            .setBackgroundInsetEnd(0)
            .setView(binding.root)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Use the current window, including on a fold/rotation; the floating window keeps system-bar bounds.
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        binding.historyCalendarScroll.post { resizeColumns() }
    }

    private fun dayButton() = requireActivity().layoutInflater.inflate(R.layout.item_caregiver_history_calendar_day,
        binding.historyCalendarGrid, false) as MaterialButton

    private fun measureCells() {
        val sample = dayButton()
        sample.setTypeface(sample.typeface, Typeface.BOLD)
        // Measure all possible day labels at the actual font scale, including the selected weight.
        for (day in 1..31) {
            sample.text = NumberFormat.getIntegerInstance(locale).format(day)
            sample.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            minimumCellWidth = max(minimumCellWidth, sample.measuredWidth)
            cellHeight = max(cellHeight, sample.measuredHeight)
        }
        for (day in java.time.DayOfWeek.values()) {
            val label = weekday(weekdayText(day))
            label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            minimumCellWidth = max(minimumCellWidth, label.measuredWidth)
            cellHeight = max(cellHeight, label.measuredHeight)
        }
    }

    // Some Android narrow-standalone providers reduce every Chinese weekday to the same first glyph.
    private fun weekdayText(day: java.time.DayOfWeek) = resources.getStringArray(R.array.c3_history_weekdays)[day.value - 1]

    private fun weekday(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun drawMonth() {
        val grid = binding.historyCalendarGrid
        grid.removeAllViews()
        cells.clear()
        binding.historyCalendarMonth.text = month.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale))
        binding.historyCalendarPrevious.isEnabled = runCatching { month.minusMonths(1) }.isSuccess
        binding.historyCalendarNext.isEnabled = runCatching { month.plusMonths(1) }.isSuccess
        val firstDay = WeekFields.of(locale).firstDayOfWeek
        for (offset in 0..6) grid.addView(weekday(weekdayText(firstDay.plus(offset.toLong()))))
        val blanks = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
        repeat(blanks) { grid.addView(Space(requireContext())) }
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val button = dayButton()
            button.text = NumberFormat.getIntegerInstance(locale).format(day)
            button.tag = date
            button.setOnClickListener {
                val start = from
                if (start == null || through != null || date.isBefore(start)) {
                    from = date
                    through = null
                } else through = date
                updateSelection()
            }
            cells += date to button
            grid.addView(button)
        }
        while (grid.childCount % 7 != 0) grid.addView(Space(requireContext()))
        columnWidth = 0
        resizeColumns()
        updateSelection()
    }

    private fun resizeColumns() {
        val width = max(minimumCellWidth, binding.historyCalendarScroll.width / 7)
        if (width != columnWidth) {
            columnWidth = width
            for (i in 0 until binding.historyCalendarGrid.childCount) {
                binding.historyCalendarGrid.getChildAt(i).layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(i / 7), GridLayout.spec(i % 7)).apply {
                    this.width = width
                    height = cellHeight
                }
            }
        }
        binding.historyCalendarScrollHint.isVisible = width * 7 > binding.historyCalendarScroll.width
    }

    private fun updateSelection() {
        val start = from
        val end = through
        binding.historyCalendarFrom.text = getString(R.string.c3_history_calendar_from, start?.toString() ?: getString(R.string.c3_history_date_not_selected))
        binding.historyCalendarThrough.text = getString(R.string.c3_history_calendar_through, end?.toString() ?: getString(R.string.c3_history_date_not_selected))
        binding.historyDialogConfirm.isEnabled = start != null && end != null
        for ((date, button) in cells) {
            val selected = start != null && !date.isBefore(start) && !date.isAfter(end ?: start)
            button.isChecked = selected
            button.setTypeface(button.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            val state = when {
                date == start && date == end -> R.string.c3_history_same_day
                date == start -> R.string.c3_history_start_day
                date == end -> R.string.c3_history_end_day
                selected -> R.string.c3_history_between_days
                else -> R.string.c3_history_unselected_day
            }
            button.contentDescription = getString(state, date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)))
        }
    }

    private fun moveMonth(amount: Long) {
        month = runCatching { month.plusMonths(amount) }.getOrElse { return }
        drawMonth()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(FROM, from?.toString())
        outState.putString(THROUGH, through?.toString())
        outState.putString(MONTH, month.toString())
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onClosed?.invoke()
        onClosed = null
        onDates = null
    }

    companion object {
        private const val FROM = "from"
        private const val THROUGH = "through"
        private const val MONTH = "month"
        fun create(from: String, through: String) = CaregiverHistoryDateRangeDialog().apply {
            arguments = Bundle().apply { putString(FROM, from); putString(THROUGH, through) }
        }
    }
}
