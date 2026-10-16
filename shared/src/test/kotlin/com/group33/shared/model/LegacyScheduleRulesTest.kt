package com.group33.shared.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyScheduleRulesTest {
    private val schedule = MedicationSchedule(
        "plan-1", "medication-1", "elder-1", "caregiver-1",
        listOf("08:00", "20:00"), "2026-10-20", "2026-10-22", updatedAtEpochMillis = 1L,
    )

    @Test
    fun `date range is inclusive and disabled plans are excluded`() {
        assertFalse(LegacyScheduleRules.occursOn(schedule, LocalDate.parse("2026-10-19")))
        assertTrue(LegacyScheduleRules.occursOn(schedule, LocalDate.parse("2026-10-20")))
        assertTrue(LegacyScheduleRules.occursOn(schedule, LocalDate.parse("2026-10-22")))
        assertFalse(LegacyScheduleRules.occursOn(schedule, LocalDate.parse("2026-10-23")))
        assertFalse(LegacyScheduleRules.occursOn(schedule.copy(enabled = false), LocalDate.parse("2026-10-20")))
        assertTrue(LegacyScheduleRules.occursOn(schedule.copy(endDate = null), LocalDate.parse("2027-01-01")))
    }

    @Test
    fun `dates must be real zero padded ISO dates`() {
        listOf("demo", "2026-02-29", "2026-02-30", "2026-1-01", "2026-13-01").forEach { invalid ->
            assertTrue(LegacyScheduleRules.validate(schedule.copy(startDate = invalid)).contains(
                FieldError("startDate", "INVALID_DATE"),
            ))
        }
        assertTrue(LegacyScheduleRules.validate(schedule.copy(startDate = "2024-02-29")).isEmpty())
    }

    @Test
    fun `reject invalid end dates reversed ranges and malformed times`() {
        assertTrue(LegacyScheduleRules.validate(schedule.copy(endDate = "demo")).contains(
            FieldError("endDate", "INVALID_DATE"),
        ))
        assertTrue(LegacyScheduleRules.validate(schedule.copy(endDate = "2026-10-19")).contains(
            FieldError("endDate", "DATE_BEFORE_START"),
        ))
        listOf("not-a-time", "8:00", "24:00", "08:60", "08:00:00").forEach { invalid ->
            assertTrue(LegacyScheduleRules.validate(schedule.copy(times = listOf(invalid))).contains(
                FieldError("times", "INVALID_LOCAL_TIME"),
            ))
        }
    }

    @Test
    fun `reject empty duplicate times blank identifiers and negative timestamps`() {
        assertTrue(LegacyScheduleRules.validate(schedule.copy(times = emptyList())).contains(
            FieldError("times", "REQUIRED"),
        ))
        assertEquals(listOf(FieldError("times", "DUPLICATE_LOCAL_TIME")),
            LegacyScheduleRules.validate(schedule.copy(times = listOf("08:00", "08:00"))))
        assertTrue(LegacyScheduleRules.validate(schedule.copy(elderId = " ")).contains(
            FieldError("elderId", "REQUIRED"),
        ))
        assertTrue(LegacyScheduleRules.validate(schedule.copy(updatedAtEpochMillis = -1)).contains(
            FieldError("updatedAtEpochMillis", "INVALID_INSTANT"),
        ))
    }
}
