package com.group33.medicationreminder.caregiver.plan

import androidx.lifecycle.ViewModel
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.ReminderPlanDraft
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlanFormInput(
    val displayName: String = "",
    val dosageText: String = "",
    val instructions: String = "",
    val localTimes: List<String> = emptyList(),
    val startDate: String = "",
    val endDate: String = "",
    val executionTimeZoneId: String = "",
)

enum class PlanFormField { NAME, DOSAGE, TIMES, START_DATE, END_DATE, TIME_ZONE }
enum class PlanFormError { REQUIRED, INVALID_TIME, DUPLICATE_TIME, INVALID_DATE, END_BEFORE_START, INVALID_ZONE }

/** Locally valid fields only, not an authorized or confirmed plan. */
data class ValidatedPlanFields(val medication: MedicationText, val plan: ReminderPlanDraft)

data class PlanFormState(
    val input: PlanFormInput = PlanFormInput(),
    val errors: Map<PlanFormField, PlanFormError> = emptyMap(),
    val validatedFields: ValidatedPlanFields? = null,
)

/** Input preparation only. Photo readiness, timezone acknowledgement and saving belong to the editor integration. */
class PlanFormViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(PlanFormState())
    val state = mutableState.asStateFlow()

    fun updateInput(input: PlanFormInput) {
        // Copy the caller's list so later mutations cannot change an already validated form.
        mutableState.value = PlanFormState(input.copy(localTimes = input.localTimes.toList()))
    }

    fun validate(): Boolean {
        val input = state.value.input
        val errors = mutableMapOf<PlanFormField, PlanFormError>()
        if (input.displayName.isBlank()) errors[PlanFormField.NAME] = PlanFormError.REQUIRED
        if (input.dosageText.isBlank()) errors[PlanFormField.DOSAGE] = PlanFormError.REQUIRED
        when {
            input.localTimes.isEmpty() -> errors[PlanFormField.TIMES] = PlanFormError.REQUIRED
            input.localTimes.any { !TIME.matches(it) } -> errors[PlanFormField.TIMES] = PlanFormError.INVALID_TIME
            input.localTimes.distinct().size != input.localTimes.size -> errors[PlanFormField.TIMES] = PlanFormError.DUPLICATE_TIME
        }
        val start = parseDate(input.startDate)
        val end = input.endDate.takeIf { it.isNotEmpty() }?.let(::parseDate)
        if (start == null) errors[PlanFormField.START_DATE] =
            if (input.startDate.isEmpty()) PlanFormError.REQUIRED else PlanFormError.INVALID_DATE
        if (input.endDate.isNotEmpty() && end == null) errors[PlanFormField.END_DATE] = PlanFormError.INVALID_DATE
        if (start != null && end != null && end < start) errors[PlanFormField.END_DATE] = PlanFormError.END_BEFORE_START
        if (input.executionTimeZoneId !in ZoneId.getAvailableZoneIds()) errors[PlanFormField.TIME_ZONE] =
            if (input.executionTimeZoneId.isBlank()) PlanFormError.REQUIRED else PlanFormError.INVALID_ZONE

        val fields = if (errors.isEmpty()) ValidatedPlanFields(
            // Only an empty editor means no instructions; preserve all supplied text for intent identity.
            MedicationText(input.displayName, input.dosageText, input.instructions.takeIf { it.isNotEmpty() }),
            ReminderPlanDraft(input.localTimes.toList(), input.startDate, input.executionTimeZoneId,
                input.endDate.takeIf { it.isNotEmpty() }),
        ) else null
        mutableState.value = PlanFormState(input, errors.toMap(), fields)
        return fields != null
    }

    private fun parseDate(value: String): LocalDate? {
        if (!DATE.matches(value)) return null
        return try { LocalDate.parse(value) } catch (_: DateTimeParseException) { null }
    }

    private companion object {
        val TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    }
}
