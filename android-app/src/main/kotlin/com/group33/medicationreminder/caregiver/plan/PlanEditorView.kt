package com.group33.medicationreminder.caregiver.plan

import android.content.Context
import android.graphics.Bitmap
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
    private fun text() = TextView(context).apply { textSize = 16f; column.addView(this) }
    private val elder = text()
    private val photo = ImageView(context).apply {
        adjustViewBounds = true
        column.addView(this, LinearLayout.LayoutParams(-1, dp(200)))
    }
    private val viewed = CheckBox(context).apply {
        setText(R.string.c2_editor_photo_viewed); minHeight = dp(48); column.addView(this)
    }
    private val fields = linkedMapOf<PlanFormField, EditText>()
    private fun field(key: PlanFormField?, label: Int, hint: Int? = null): EditText {
        val edit = EditText(context).apply { id = View.generateViewId(); textSize = 16f; minHeight = dp(48); minimumHeight = dp(48) }
        text().apply { setText(label); labelFor = edit.id }
        hint?.let { edit.setHint(it) }
        column.addView(edit)
        key?.let { fields[it] = edit }
        return edit
    }
    private val name = field(PlanFormField.NAME, R.string.c2_plan_medication_name)
    private val dosage = field(PlanFormField.DOSAGE, R.string.c2_plan_dosage)
    private val instructions = field(null, R.string.c2_plan_instructions)
    private val times = field(PlanFormField.TIMES, R.string.c2_plan_times, R.string.c2_editor_times_hint)
    private val start = field(PlanFormField.START_DATE, R.string.c2_plan_start_date, R.string.c2_plan_date_hint)
    private val end = field(PlanFormField.END_DATE, R.string.c2_plan_end_date, R.string.c2_plan_date_hint)
    private val zone = field(PlanFormField.TIME_ZONE, R.string.c2_plan_execution_zone)
    private val comparison = text()
    private val acknowledgement = CheckBox(context).apply {
        setText(R.string.c2_plan_zone_acknowledgement); minHeight = dp(48); column.addView(this)
    }
    private val status = text().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val action = Button(context).apply { minHeight = dp(48); column.addView(this) }
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

    init {
        column.setPadding(dp(16), dp(16), dp(16), dp(24))
        addView(column)
        isFillViewport = true
        (fields.values + instructions).forEach { edit -> edit.doAfterTextChanged {
            if (!updating) {
                form?.updateInput(input())
                acknowledgement.isChecked = false
                renderZones()
            }
        } }
        clear()
    }

    /** decodedPhoto must match metadata and have been obtained by the trusted host. */
    fun bind(owner: LifecycleOwner, elderName: String, metadata: MedicationPhotoView?, decodedPhoto: Bitmap?,
             caregiverTimeZone: String?, elderTimeZone: String?,
             formModel: PlanFormViewModel, saveModel: PlanSaveViewModel) {
        clear()
        val boundGeneration = generation
        form = formModel; save = saveModel
        caregiverZone = caregiverTimeZone; elderZone = elderTimeZone
        elder.text = elderName
        photo.contentDescription = context.getString(R.string.c2_editor_photo_description, elderName)
        val usablePhoto = metadata != null && decodedPhoto != null && !decodedPhoto.isRecycled
        photoMetadata = metadata.takeIf { usablePhoto }
        photo.setImageBitmap(decodedPhoto.takeIf { usablePhoto })
        viewed.isEnabled = usablePhoto
        val initial = formModel.state.value.input
        updating = true
        name.setText(initial.displayName); dosage.setText(initial.dosageText)
        instructions.setText(initial.instructions); times.setText(initial.localTimes.joinToString("\n"))
        start.setText(initial.startDate); end.setText(initial.endDate); zone.setText(initial.executionTimeZoneId)
        updating = false
        renderZones()
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
        val summary = context.getString(R.string.c2_plan_zone_comparison,
            caregiverZone?.takeIf { it in known } ?: unknown, elderZone?.takeIf { it in known } ?: unknown,
            execution.takeIf { it in known } ?: unknown)
        val example = runCatching {
            val at = LocalDate.parse(start.text).atTime(LocalTime.parse(input().localTimes.first())).atZone(ZoneId.of(execution))
            fun inZone(id: String?) = id?.takeIf { it in known }?.let { at.withZoneSameInstant(ZoneId.of(it)).toString() } ?: unknown
            context.getString(R.string.c2_editor_zone_example, at.toString(), inZone(caregiverZone), inZone(elderZone))
        }.getOrElse { context.getString(R.string.c2_editor_example_pending) }
        comparison.text = "$summary\n$example"
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
        acknowledgement.isEnabled = editable
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
