package com.group33.medicationreminder.caregiver.plan

import android.content.Context
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.OnlineCommandState
import com.group33.shared.contract.MedicationPhotoView
import com.group33.shared.contract.TimeZoneAcknowledgement
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Unregistered component. A trusted host owns access, decoding and scope-bound ViewModels.
 * Bind a new pair of ViewModels for each account/Elder editor; call clear on access loss.
 * A bitmap is actual decoded content, not a PhotoContent handle or authorization evidence.
 */
class PlanEditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ScrollView(context, attrs) {
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = androidx.core.content.ContextCompat.getColor(context, id)
    private fun surface(fill: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(16).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    private fun label(parent: LinearLayout, size: Float = 16f) = TextView(context).apply {
        textSize = size; setTextColor(color(R.color.group33_text_primary))
        setLineSpacing(dp(3).toFloat(), 1f)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private val heading = label(column, 28f).apply {
        setText(R.string.c2_editor_heading); setTypeface(typeface, Typeface.BOLD)
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    private val elder = label(column, 16f).apply {
        setTextColor(color(R.color.group33_text_secondary)); setPadding(0, dp(8), 0, dp(20))
    }
    private fun section(title: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = surface(color(R.color.group33_surface))
        column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        label(this, 18f).apply {
            setText(title); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
    }
    private val photoSection = section(R.string.c2_editor_section_photo)
    private val photo = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = surface(color(R.color.group33_background))
        clipToOutline = true
        setPadding(dp(8), dp(8), dp(8), dp(8))
        photoSection.addView(this, LinearLayout.LayoutParams(-1, dp(156)))
    }
    private val viewed = CheckBox(context).apply {
        setText(R.string.c2_editor_photo_viewed); textSize = 16f
        setTextColor(color(R.color.group33_text_primary))
        buttonTintList = ColorStateList.valueOf(color(R.color.group33_action))
        minHeight = dp(48)

    }
    private val fields = linkedMapOf<PlanFormField, EditText>()
    private fun field(parent: LinearLayout, key: PlanFormField?, title: Int, hint: Int? = null): EditText {
        val edit = EditText(context).apply {
            id = View.generateViewId(); textSize = 16f; minHeight = dp(56); minimumHeight = dp(56)
            setTextColor(color(R.color.group33_text_primary))
            setHintTextColor(color(R.color.group33_text_secondary))
            background = surface(color(R.color.group33_background))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnFocusChangeListener { _, focused ->
                background = surface(color(R.color.group33_background),
                    if (focused) color(R.color.group33_action) else null)
            }
        }
        label(parent, 14f).apply {
            setText(title); labelFor = edit.id; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        hint?.let { edit.setHint(it) }
        parent.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        key?.let { fields[it] = edit }
        return edit
    }
    private val medicationSection = section(R.string.c2_editor_section_medication)
    private val name = field(medicationSection, PlanFormField.NAME, R.string.c2_plan_medication_name)
    private val dosage = field(medicationSection, PlanFormField.DOSAGE, R.string.c2_per_dose)
    private val instructions = field(medicationSection, null, R.string.c2_plan_instructions).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    private val ocr = PhotoTextSuggestions(context) { candidate ->
        if (viewed.isChecked && name.isEnabled && name.text.isBlank()) {
            name.setText(candidate); true
        } else false
    }.also { medicationSection.addView(it) }
    private val scheduleSection = section(R.string.c2_editor_section_schedule)
    private val times = field(scheduleSection, PlanFormField.TIMES, R.string.c2_plan_times, R.string.c2_editor_times_hint).apply {
        keyListener = null; showSoftInputOnFocus = false; isCursorVisible = false
        visibility = View.GONE
    }
    private val frequency = Button(context).apply {
        minHeight = dp(48); isAllCaps = false; scheduleSection.addView(this)
        setOnClickListener { openFrequencyPicker() }
    }
    private val timeRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; scheduleSection.addView(this) }
    private val timeError = label(scheduleSection).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val start = field(scheduleSection, PlanFormField.START_DATE, R.string.c2_plan_start_date, R.string.c2_editor_date_placeholder).apply { calendarField(this, false) }
    private val end = field(scheduleSection, PlanFormField.END_DATE, R.string.c2_plan_end_date, R.string.c2_editor_date_placeholder).apply { calendarField(this, true) }
    private val dateHelp = label(scheduleSection, 14f).apply {
        setText(R.string.c2_editor_calendar_help); setTextColor(color(R.color.group33_text_secondary))
    }
    private val zoneSection = section(R.string.c2_editor_section_zone)
    private val zone = field(zoneSection, PlanFormField.TIME_ZONE, R.string.c2_plan_execution_zone).apply {
        keyListener = null; showSoftInputOnFocus = false; isCursorVisible = false
        setHint(R.string.c2_choose_zone)
        setOnClickListener { openZonePicker() }
    }
    private val comparison = label(zoneSection, 14f).apply {
        setTextColor(color(R.color.group33_text_secondary))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = surface(color(R.color.group33_background))
    }
    private val acknowledgement = CheckBox(context).apply {
        setText(R.string.c2_plan_zone_acknowledgement); textSize = 16f
        setTextColor(color(R.color.group33_text_primary))
        buttonTintList = ColorStateList.valueOf(color(R.color.group33_action))
        minHeight = dp(48); zoneSection.addView(this)
    }
    private val status = label(column).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = surface(color(R.color.group33_navigation_selected))
    }
    private val photoConfirmation = viewed.also { photoSection.addView(it) }
    private val action = Button(context).apply {
        minHeight = dp(56); textSize = 16f; isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.group33_surface), color(R.color.group33_text_secondary))))
        background = surface(color(R.color.group33_action))
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.group33_action), color(R.color.group33_navigation_selected)))
        column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    private var binding: Job? = null
    private var operation: Job? = null
    private var form: PlanFormViewModel? = null
    private var save: PlanSaveViewModel? = null
    private var photoMetadata: MedicationPhotoView? = null
    private var caregiverZone: String? = null
    private var elderZone: String? = null
    private var rejectionReviewed = false
    private var updating = false
    private var generation = 0
    private var zoneDialog: AlertDialog? = null
    private var frequencyDialog: AlertDialog? = null
    private var dateDialog: DatePickerDialog? = null
    private var timeDialog: TimePickerDialog? = null

    private fun openTimePicker(index: Int?) {
        if (!times.isEnabled || form == null) return
        timeDialog?.dismiss()
        val token = generation
        val entries = input().localTimes
        val selected = index?.let { entries.getOrNull(it) }?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(8, 0)
        val dialog = TimePickerDialog(context, R.style.C2TimeDialog, { _, hour, minute ->
            if (token == generation && times.isEnabled && form != null) {
                val value = String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)
                val updated = input().localTimes.toMutableList()
                if (index == null) updated.add(value) else if (index in updated.indices) updated[index] = value
                times.setText(updated.joinToString("\n"))
            }
        }, selected.hour, selected.minute, true)
        timeDialog = dialog
        dialog.setOnDismissListener { if (timeDialog === dialog) timeDialog = null }
        dialog.show()
        listOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE).forEach {
            dialog.getButton(it).apply {
                setTextColor(color(R.color.group33_action)); minHeight = dp(48)
                backgroundTintList = ColorStateList.valueOf(color(R.color.group33_surface))
                isAllCaps = false
            }
        }
    }

    private fun openZonePicker() {
        if (!zone.isEnabled || form == null) return
        zoneDialog?.dismiss()
        val token = generation
        val known = ZoneId.getAvailableZoneIds()
        val ids = (listOfNotNull(elderZone, "Asia/Shanghai", caregiverZone) + known.sorted()).filter { it in known }.distinct()
        val labels = ids.map { id ->
            val title = java.util.TimeZone.getTimeZone(id).getDisplayName(false, java.util.TimeZone.LONG, androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0] ?: java.util.Locale.getDefault())
            "$title ($id)"
        }.toTypedArray()
        val search = EditText(context).apply {
            setHint(R.string.c2_search_zone); contentDescription = context.getString(R.string.c2_search_zone)
            setSingleLine(true); minHeight = dp(48)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val list = ListView(context).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        val empty = TextView(context).apply { setText(R.string.c2_no_zone_results); setPadding(dp(16),dp(12),dp(16),dp(12)) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),0)
            isFocusableInTouchMode = true
            addView(search); addView(empty); addView(list, LinearLayout.LayoutParams(-1,dp(300)))
        }
        var filtered = ids.indices.toList()
        val searchable = ids.mapIndexed { index, id ->
            val tz = java.util.TimeZone.getTimeZone(id)
            labels[index] + " " + tz.getDisplayName(false, java.util.TimeZone.LONG, java.util.Locale.SIMPLIFIED_CHINESE) +
                " " + tz.getDisplayName(false, java.util.TimeZone.LONG, java.util.Locale.ENGLISH)
        }
        fun filter(query: String) {
            filtered = ids.indices.filter { searchable[it].contains(query.trim(), ignoreCase = true) }
            list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, filtered.map { labels[it] })
            empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            val selected = filtered.indexOfFirst { ids[it] == zone.text.toString() }
            if (selected >= 0) list.setItemChecked(selected, true)
        }
        filter("")
        search.doAfterTextChanged { filter(it.toString()) }
        list.setOnItemClickListener { _, _, position, _ ->
            if (generation == token && zone.isEnabled && form != null) zone.setText(ids[filtered[position]])
            zoneDialog?.dismiss()
        }
        zoneDialog = AlertDialog.Builder(context).setTitle(R.string.c2_choose_zone)
            .setView(content).setNegativeButton(android.R.string.cancel, null).create()
        zoneDialog?.show()
    }

    private fun openFrequencyPicker() {
        if (!times.isEnabled || form == null) return
        frequencyDialog?.dismiss()
        val token = generation
        val count = input().localTimes.size.coerceAtLeast(1)
        val picker = NumberPicker(context).apply {
            minValue = 1; maxValue = maxOf(24, count); value = count
            wrapSelectorWheel = false
            contentDescription = context.getString(R.string.c2_daily_frequency)
        }
        frequencyDialog = AlertDialog.Builder(context)
            .setTitle(R.string.c2_daily_frequency).setView(picker)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                picker.clearFocus()
                if (token == generation && times.isEnabled && form != null) {
                    val entries = input().localTimes.toMutableList()
                    while (entries.size > picker.value) entries.removeAt(entries.lastIndex)
                    while (entries.size < picker.value) {
                        val next = (0 until 1440).map { (it + 480) % 1440 }
                            .map { String.format(java.util.Locale.ROOT, "%02d:%02d", it / 60, it % 60) }
                            .first { it !in entries && (it.endsWith(":00") || entries.size >= 24) }
                        entries.add(next)
                    }
                    times.setText(entries.joinToString("\n"))
                }
            }.setNegativeButton(android.R.string.cancel, null).create()
        frequencyDialog?.show()
    }

    private fun renderTimes() {
        frequency.text = context.getString(R.string.c2_daily_count, input().localTimes.size)
        frequency.isEnabled = times.isEnabled
        timeRows.removeAllViews()
        input().localTimes.forEachIndexed { index, value ->
            val row = LinearLayout(context)
            val edit = Button(context).apply {
                text = value; minHeight = dp(48); isAllCaps = false; isEnabled = times.isEnabled
                contentDescription = context.getString(R.string.c2_editor_edit_time, value)
                setOnClickListener { openTimePicker(index) }
            }
            row.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
            timeRows.addView(row)
        }
    }

    private fun calendarField(edit: EditText, optional: Boolean) {
        edit.keyListener = null
        edit.showSoftInputOnFocus = false
        edit.isCursorVisible = false
        edit.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.c2_calendar, 0)
        edit.compoundDrawablePadding = dp(12)
        edit.setOnClickListener {
            if (!edit.isEnabled) return@setOnClickListener
            dateDialog?.dismiss()
            val requestedGeneration = generation
            val selected = runCatching { LocalDate.parse(edit.text) }.getOrElse {
                runCatching { LocalDate.parse(start.text) }.getOrElse { LocalDate.now() }
            }
            fun stillCurrent() = generation == requestedGeneration && edit.isEnabled && form != null
            val dialog = DatePickerDialog(context, R.style.C2CalendarDialog,
                { _, year, month, day ->
                    if (stillCurrent()) edit.setText(LocalDate.of(year, month + 1, day).toString())
                }, selected.year, selected.monthValue - 1, selected.dayOfMonth)
            dialog.setTitle(if (optional) R.string.c2_plan_end_date else R.string.c2_plan_start_date)
            if (optional) dialog.setButton(android.content.DialogInterface.BUTTON_NEUTRAL,
                context.getString(R.string.c2_editor_no_end)) { _, _ ->
                if (stillCurrent()) edit.setText("")
            }
            dialog.setOnDismissListener { if (dateDialog === dialog) dateDialog = null }
            dateDialog = dialog
            dialog.show()
            // Explicit contrast: platform dialog buttons can inherit unsuitable text styling.
            listOf(android.content.DialogInterface.BUTTON_POSITIVE,
                android.content.DialogInterface.BUTTON_NEGATIVE,
                android.content.DialogInterface.BUTTON_NEUTRAL).forEach { which ->
                dialog.getButton(which)?.apply {
                    setTextColor(color(R.color.group33_action))
                    backgroundTintList = ColorStateList.valueOf(color(R.color.group33_surface))
                    minHeight = dp(48)
                    textSize = 16f
                    isAllCaps = false
                }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).apply {
                setText(R.string.c2_editor_confirm_date)
                setTextColor(color(R.color.group33_surface))
                backgroundTintList = null
                background = surface(color(R.color.group33_action))
            }
        }
    }

    init {
        column.setPadding(dp(16), dp(16), dp(16), dp(24))
        column.isFocusableInTouchMode = true
        column.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        setBackgroundColor(color(R.color.group33_background))
        addView(column)
        isFillViewport = true
        (fields.values + instructions).forEach { edit -> edit.doAfterTextChanged {
            if (!updating) {
                form?.updateInput(input())
                acknowledgement.isChecked = false
                renderZones()
                if (edit === times) renderTimes()
            }
        } }
        viewed.setOnCheckedChangeListener { _, checked ->
            if (checked && name.text.isBlank()) ocr.recognize() else if (!checked) ocr.cancel()
        }
        clear()
        column.requestFocus()
    }

    /** decodedPhoto must match metadata and have been obtained by the trusted host. */
    fun bind(owner: LifecycleOwner, elderName: String, metadata: MedicationPhotoView?, decodedPhoto: Bitmap?,
             caregiverTimeZone: String?, elderTimeZone: String?,
             formModel: PlanFormViewModel, saveModel: PlanSaveViewModel) {
        clear()
        val boundGeneration = generation
        form = formModel; save = saveModel
        caregiverZone = caregiverTimeZone; elderZone = elderTimeZone
        elder.text = context.getString(R.string.c2_editor_for_elder, elderName)
        photo.contentDescription = context.getString(R.string.c2_editor_photo_description, elderName)
        val usablePhoto = metadata != null && decodedPhoto != null && !decodedPhoto.isRecycled
        photoMetadata = metadata.takeIf { usablePhoto }
        if (usablePhoto) photo.setImageBitmap(decodedPhoto) else photo.setImageDrawable(null)
        ocr.bind(decodedPhoto.takeIf { usablePhoto })
        viewed.isEnabled = usablePhoto
        val initial = formModel.state.value.input
        updating = true
        name.setText(initial.displayName); dosage.setText(initial.dosageText)
        instructions.setText(initial.instructions); times.setText(initial.localTimes.joinToString("\n"))
        start.setText(initial.startDate); end.setText(initial.endDate); zone.setText(initial.executionTimeZoneId.ifBlank { elderTimeZone?.takeIf { it in ZoneId.getAvailableZoneIds() }.orEmpty() })
        updating = false
        renderTimes()
        renderZones()
        post { if (generation == boundGeneration) scrollTo(0, 0) }
        action.setOnClickListener {
            if (operation?.isActive == true || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@setOnClickListener
            if (saveModel.state.value.status == PlanSaveStatus.REJECTED && !rejectionReviewed) {
                rejectionReviewed = true
                name.requestFocus()
                action.setText(R.string.c2_plan_save)
                return@setOnClickListener
            }
            when (presentation(saveModel.state.value).action) {
                PlanDisplayAction.CHECK_RESULT, PlanDisplayAction.REFRESH_CACHE ->
                    operation = owner.lifecycleScope.launch { saveModel.reconcile() }
                PlanDisplayAction.SAVE, PlanDisplayAction.RETRY_UNSENT, PlanDisplayAction.REVIEW_INPUT -> {
                    formModel.updateInput(input())
                    if (!formModel.validate()) return@setOnClickListener
                    if (!viewed.isChecked || !photo.isShown || photoMetadata == null) {
                        status.setText(R.string.c2_plan_photo_required); return@setOnClickListener
                    }
                    if (acknowledgement.visibility == View.VISIBLE && !acknowledgement.isChecked) {
                        status.setText(R.string.c2_editor_zone_required); return@setOnClickListener
                    }
                    val ack = TimeZoneAcknowledgement(zone.text.toString(), acknowledgement.isChecked, caregiverZone, elderZone)
                    operation = owner.lifecycleScope.launch { saveModel.save(input(), photoMetadata, ack) }
                }
                PlanDisplayAction.NONE -> Unit
            }
        }
        binding = owner.lifecycleScope.launch {
            try {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { formModel.state.collect { state ->
                        fields.forEach { (key, edit) -> edit.error = state.errors[key]?.let { context.getString(errorResource(it)) } }
                        timeError.text = state.errors[PlanFormField.TIMES]?.let { context.getString(errorResource(it)) }.orEmpty()
                    } }
                    launch { saveModel.state.collect { renderSave(it) } }
                }
            } finally {
                if (generation == boundGeneration) clear()
            }
        }
    }

    fun clear() {
        generation++
        ocr.clear()
        zoneDialog?.dismiss(); zoneDialog = null
        frequencyDialog?.dismiss(); frequencyDialog = null
        dateDialog?.dismiss(); dateDialog = null
        timeDialog?.dismiss(); timeDialog = null
        rejectionReviewed = false
        form = null; save = null
        binding?.cancel(); binding = null
        operation?.cancel(); operation = null
        photoMetadata = null; caregiverZone = null; elderZone = null
        photo.setImageDrawable(null); photo.contentDescription = null
        viewed.isChecked = false; viewed.isEnabled = false
        acknowledgement.isChecked = false; acknowledgement.visibility = View.GONE
        updating = true
        (fields.values + instructions).forEach { it.setText(""); it.error = null; it.isEnabled = false }
        updating = false
        renderTimes(); timeError.text = ""
        elder.text = ""; comparison.text = ""
        status.setText(R.string.c2_editor_no_context)
        action.isEnabled = false; action.setText(R.string.c2_plan_save); action.setOnClickListener(null)
    }

    private fun input() = PlanFormInput(name.text.toString(), dosage.text.toString(), instructions.text.toString(),
        times.text.toString().split('\n').filter { it.isNotEmpty() }, start.text.toString(), end.text.toString(), zone.text.toString())

    private fun renderZones() {
        val execution = zone.text.toString()
        val known = ZoneId.getAvailableZoneIds()
        val unknown = context.getString(R.string.c2_plan_zone_unknown)
        val matched = execution in known && caregiverZone == execution && elderZone == execution
        acknowledgement.visibility = if (matched) View.GONE else View.VISIBLE
        val canConfirm = execution in known && runCatching {
            LocalDate.parse(start.text); LocalTime.parse(input().localTimes.first())
        }.isSuccess
        acknowledgement.isEnabled = zone.isEnabled && canConfirm
        if (!canConfirm) acknowledgement.isChecked = false
        val summary = context.getString(R.string.c2_plan_zone_comparison,
            caregiverZone?.takeIf { it in known } ?: unknown, elderZone?.takeIf { it in known } ?: unknown,
            execution.takeIf { it in known } ?: unknown)
        val example = runCatching {
            val at = LocalDate.parse(start.text).atTime(LocalTime.parse(input().localTimes.first())).atZone(ZoneId.of(execution))
            val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX")
            fun inZone(id: String?) = id?.takeIf { it in known }?.let { at.withZoneSameInstant(ZoneId.of(it)).format(format) } ?: unknown
            context.getString(R.string.c2_editor_zone_example, at.format(format), inZone(caregiverZone), inZone(elderZone))
        }.getOrElse { context.getString(R.string.c2_editor_example_pending) }
        comparison.text = if (matched) context.getString(R.string.c2_zone_follows_elder, execution)
            else "$summary\n$example"
        zone.visibility = if (matched) View.GONE else View.VISIBLE
        for (i in 0 until zoneSection.childCount) {
            zoneSection.getChildAt(i).takeIf { it.labelFor == zone.id }?.visibility = zone.visibility
        }
    }

    private fun renderSave(state: PlanSaveState) {
        val p = presentation(state)
        status.text = context.getString(p.title) + "\n" + context.getString(p.description)
        action.text = if (state.status == PlanSaveStatus.REJECTED && rejectionReviewed) context.getString(R.string.c2_plan_save)
            else p.actionLabel?.let(context::getString) ?: context.getString(p.title)
        if (state.inputError != null) status.append("\n" + context.getString(when (state.inputError) {
            PlanSaveInputError.PHOTO_REQUIRED -> R.string.c2_plan_photo_required
            PlanSaveInputError.TIME_ZONE_CONFIRMATION_REQUIRED -> R.string.c2_editor_zone_required
            PlanSaveInputError.WRONG_ELDER -> R.string.c2_editor_no_context
            PlanSaveInputError.INVALID_FIELDS -> R.string.c2_plan_required_error
        }))
        action.isEnabled = p.action != PlanDisplayAction.NONE
        val editable = state.status in setOf(PlanSaveStatus.IDLE, PlanSaveStatus.NOT_SUBMITTED, PlanSaveStatus.REJECTED)
        (fields.values + instructions).forEach { it.isEnabled = editable }
        ocr.setEditable(editable)
        renderTimes()
        if (!editable) { frequencyDialog?.dismiss(); frequencyDialog = null }
        if (!editable) { timeDialog?.dismiss(); timeDialog = null }
        if (!editable) { zoneDialog?.dismiss(); zoneDialog = null }
        renderZones()
        viewed.isEnabled = editable && photoMetadata != null
    }

    private fun presentation(state: PlanSaveState): PlanSavePresentation = when (state.status) {
        PlanSaveStatus.IDLE -> PlanSavePresentations.editing()
        PlanSaveStatus.SUBMITTING -> PlanSavePresentations.submitting()
        else -> PlanSavePresentations.forCommand(OnlineCommandState.valueOf(state.status.name), state.cacheState)
    }

    private fun errorResource(error: PlanFormError) = when (error) {
        PlanFormError.REQUIRED -> R.string.c2_plan_required_error
        PlanFormError.INVALID_TIME -> R.string.c2_plan_time_error
        PlanFormError.DUPLICATE_TIME -> R.string.c2_plan_duplicate_time_error
        PlanFormError.INVALID_DATE -> R.string.c2_plan_date_error
        PlanFormError.END_BEFORE_START -> R.string.c2_plan_date_order_error
        PlanFormError.INVALID_ZONE -> R.string.c2_plan_zone_error
    }
}
