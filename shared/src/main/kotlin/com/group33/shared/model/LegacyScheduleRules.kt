package com.group33.shared.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Validation for the legacy development model, not the revision-aware P0 plan contract. */
object LegacyScheduleRules {
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

    fun validate(schedule: MedicationSchedule): List<FieldError> = buildList {
        listOf(
            "id" to schedule.id,
            "medicationId" to schedule.medicationId,
            "elderId" to schedule.elderId,
            "caregiverId" to schedule.caregiverId,
        ).forEach { (field, value) ->
            if (value.isBlank()) add(FieldError(field, "REQUIRED"))
        }
        if (schedule.times.isEmpty()) add(FieldError("times", "REQUIRED"))
        if (schedule.times.any { !timePattern.matches(it) }) {
            add(FieldError("times", "INVALID_LOCAL_TIME"))
        }
        if (schedule.times.distinct().size != schedule.times.size) {
            add(FieldError("times", "DUPLICATE_LOCAL_TIME"))
        }
        val start = parseDate(schedule.startDate)
        val end = schedule.endDate?.let(::parseDate)
        if (start == null) add(FieldError("startDate", "INVALID_DATE"))
        if (schedule.endDate != null && end == null) add(FieldError("endDate", "INVALID_DATE"))
        if (start != null && end != null && end < start) {
            add(FieldError("endDate", "DATE_BEFORE_START"))
        }
        if (schedule.updatedAtEpochMillis < 0) {
            add(FieldError("updatedAtEpochMillis", "INVALID_INSTANT"))
        }
    }

    fun occursOn(schedule: MedicationSchedule, date: LocalDate): Boolean {
        if (!schedule.enabled || validate(schedule).isNotEmpty()) return false
        val start = parseDate(schedule.startDate) ?: return false
        val end = schedule.endDate?.let(::parseDate)
        return date >= start && (end == null || date <= end)
    }

    private fun parseDate(value: String): LocalDate? {
        if (!datePattern.matches(value)) return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
