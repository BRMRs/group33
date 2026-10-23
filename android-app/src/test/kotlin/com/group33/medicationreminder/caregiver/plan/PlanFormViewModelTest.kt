package com.group33.medicationreminder.caregiver.plan

import org.junit.Assert.*
import org.junit.Test

class PlanFormViewModelTest {
    private val valid = PlanFormInput("Demo medication", "Demo dose", "With fictional meal",
        listOf("08:00", "20:00"), "2024-02-29", "", "Asia/Shanghai")

    private fun check(input: PlanFormInput) = PlanFormViewModel().apply {
        updateInput(input)
        validate()
    }

    @Test fun `valid fields map to existing draft without changing entered text`() {
        val result = check(valid).state.value.validatedFields!!
        assertEquals(valid.displayName, result.medication.displayName)
        assertEquals(valid.dosageText, result.medication.dosageText)
        assertEquals(valid.instructions, result.medication.instructions)
        assertEquals(valid.localTimes, result.plan.localTimes)
        assertEquals(valid.startDate, result.plan.startDate)
        assertEquals(valid.executionTimeZoneId, result.plan.executionTimeZoneId)
        assertNull(result.plan.endDate)
    }

    @Test fun `required fields produce errors and preserve input`() {
        val input = PlanFormInput(displayName = " ", dosageText = "\t")
        val state = check(input).state.value
        assertEquals(input, state.input)
        assertEquals(setOf(PlanFormField.NAME, PlanFormField.DOSAGE, PlanFormField.TIMES,
            PlanFormField.START_DATE, PlanFormField.TIME_ZONE), state.errors.keys)
        assertTrue(state.errors.values.all { it == PlanFormError.REQUIRED })
        assertNull(state.validatedFields)
    }

    @Test fun `times require exact format and reject duplicates`() {
        for (time in listOf("8:00", "24:00", "12:60", "08:00:00", " 08:00")) {
            assertEquals(time, PlanFormError.INVALID_TIME,
                check(valid.copy(localTimes = listOf(time))).state.value.errors[PlanFormField.TIMES])
        }
        assertEquals(PlanFormError.DUPLICATE_TIME,
            check(valid.copy(localTimes = listOf("08:00", "08:00"))).state.value.errors[PlanFormField.TIMES])
        assertNotNull(check(valid.copy(localTimes = listOf("00:00", "23:59"))).state.value.validatedFields)
    }

    @Test fun `invalid calendar dates in either endpoint are rejected`() {
        for (date in listOf("2026-02-29", "2024-04-31", "2024-2-29", "not-date")) {
            assertEquals(date, PlanFormError.INVALID_DATE,
                check(valid.copy(startDate = date)).state.value.errors[PlanFormField.START_DATE])
            assertEquals(date, PlanFormError.INVALID_DATE,
                check(valid.copy(endDate = date)).state.value.errors[PlanFormField.END_DATE])
        }
    }

    @Test fun `inclusive same day is allowed but reversed dates are rejected`() {
        assertNotNull(check(valid.copy(endDate = valid.startDate)).state.value.validatedFields)
        val state = check(valid.copy(endDate = "2024-02-28")).state.value
        assertEquals(PlanFormError.END_BEFORE_START, state.errors[PlanFormField.END_DATE])
        assertEquals("2024-02-28", state.input.endDate)
        assertNull(state.validatedFields)
    }

    @Test fun `zone must be a known region rather than a raw offset`() {
        for (zone in listOf("Unknown/City", "+08:00")) {
            assertEquals(PlanFormError.INVALID_ZONE,
                check(valid.copy(executionTimeZoneId = zone)).state.value.errors[PlanFormField.TIME_ZONE])
        }
    }

    @Test fun `editing clears previously validated fields and errors`() {
        val model = check(valid)
        model.updateInput(valid.copy(displayName = ""))
        assertNull(model.state.value.validatedFields)
        assertFalse(model.validate())
        model.updateInput(valid)
        assertTrue(model.state.value.errors.isEmpty())
        assertTrue(model.validate())
    }

    @Test fun `caller list changes cannot silently change the form`() {
        val times = mutableListOf("08:00")
        val model = check(valid.copy(localTimes = times, instructions = ""))
        times.add("24:00")
        assertEquals(listOf("08:00"), model.state.value.input.localTimes)
        assertEquals(listOf("08:00"), model.state.value.validatedFields!!.plan.localTimes)
        assertNull(model.state.value.validatedFields!!.medication.instructions)
    }
}
