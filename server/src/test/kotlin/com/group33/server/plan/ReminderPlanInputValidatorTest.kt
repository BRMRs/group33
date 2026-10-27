package com.group33.server.plan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.group33.shared.contract.ReminderPlanDraft
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ReminderPlanInputValidatorTest {
    private val valid = ReminderPlanDraft(listOf("08:00"), "2026-10-26", "Asia/Shanghai")

    @Test
    fun `accepts open ended plan and all 1440 unique minute values without imposing a new cap`() {
        val times = (0 until 24).flatMap { h -> (0 until 60).map { m -> "%02d:%02d".format(Locale.ROOT, h, m) } }
        assertTrue(ReminderPlanInputValidator.validate(valid.copy(localTimes = times)).isEmpty())
    }

    @Test
    fun `requires at least one time`() {
        assertOnly(valid.copy(localTimes = emptyList()), "localTimes", PlanInputFailure.REQUIRED)
    }

    @ParameterizedTest
    @ValueSource(strings = ["8:00", "08:0", "24:00", "23:60", "-1:00", "08:00:00", " 08:00", "08:00 ", "", "０８:００", "08:00\n"])
    fun `rejects malformed times without padding trimming or rolling over`(value: String) {
        assertOnly(valid.copy(localTimes = listOf(value)), "localTimes", PlanInputFailure.INVALID_LOCAL_TIME)
    }

    @Test
    fun `rejects nonadjacent duplicates without removing them`() {
        val times = mutableListOf("20:00", "08:00", "20:00")
        assertOnly(valid.copy(localTimes = times), "localTimes", PlanInputFailure.DUPLICATE_LOCAL_TIME)
        assertEquals(listOf("20:00", "08:00", "20:00"), times)
    }

    @Test
    fun `reports malformed and duplicate values independently`() {
        assertEquals(listOf(
            violation("localTimes", PlanInputFailure.INVALID_LOCAL_TIME),
            violation("localTimes", PlanInputFailure.DUPLICATE_LOCAL_TIME),
        ), ReminderPlanInputValidator.validate(valid.copy(localTimes = listOf("8:00", "8:00"))))
    }

    @Test
    fun `valid permutations stay valid and retain original order`() {
        val times = mutableListOf("23:59", "00:00", "08:00")
        val plan = valid.copy(localTimes = times)
        val before = plan.copy(localTimes = times.toList())
        assertTrue(ReminderPlanInputValidator.validate(plan).isEmpty())
        assertEquals(before, plan)
        assertTrue(ReminderPlanInputValidator.validate(plan.copy(localTimes = times.reversed())).isEmpty())
        assertEquals(before, plan)
    }

    @Test
    fun `inclusive end permits a single day`() {
        assertTrue(ReminderPlanInputValidator.validate(valid.copy(endDate = valid.startDate)).isEmpty())
    }

    @Test
    fun `rejects end before start but accepts next day across year boundary`() {
        assertOnly(valid.copy(startDate = "2027-01-01", endDate = "2026-12-31"), "endDate", PlanInputFailure.DATE_BEFORE_START)
        assertTrue(ReminderPlanInputValidator.validate(valid.copy(startDate = "2026-12-31", endDate = "2027-01-01")).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["2000-02-29", "2024-02-29", "2400-02-29", "0000-01-01", "9999-12-31"])
    fun `accepts real four digit ISO dates without imposing an undocumented age limit`(date: String) {
        assertTrue(ReminderPlanInputValidator.validate(valid.copy(startDate = date, endDate = date)).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["1900-02-29", "2100-02-29", "2026-02-29", "2026-04-31", "2026-13-01", "2026-00-01", "2026-01-00", "2026-1-01", "26-01-01", "+10000-01-01", " 2026-10-26", "2026-10-26 ", "２０２６-１０-２６", "2026-10-26T00:00:00", "", " "])
    fun `rejects impossible and noncanonical dates in either field`(date: String) {
        assertOnly(valid.copy(startDate = date), "startDate", PlanInputFailure.INVALID_DATE)
        assertOnly(valid.copy(endDate = date), "endDate", PlanInputFailure.INVALID_DATE)
    }

    @Test
    fun `invalid start does not produce a spurious date ordering error`() {
        assertOnly(valid.copy(startDate = "2026-02-30", endDate = "2025-01-01"), "startDate", PlanInputFailure.INVALID_DATE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Asia/Shanghai", "America/New_York", "Europe/London", "Asia/Kathmandu", "Pacific/Apia", "Etc/UTC", "UTC", "US/Eastern"])
    fun `accepts registered zones and aliases without replacing supplied identifier`(zone: String) {
        val plan = valid.copy(executionTimeZoneId = zone)
        assertTrue(ReminderPlanInputValidator.validate(plan).isEmpty())
        assertEquals(zone, plan.executionTimeZoneId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "asia/shanghai", " Asia/Shanghai", "Asia/Shanghai ", "Mars/Olympus", "+08:00", "Z", "UTC+08:00", "GMT+08:00", "CST"])
    fun `rejects unknown zones abbreviations and unregistered offset strings`(zone: String) {
        assertOnly(valid.copy(executionTimeZoneId = zone), "executionTimeZoneId", PlanInputFailure.INVALID_TIME_ZONE)
    }

    @Test
    fun `DST gap and overlap local times remain valid input for later occurrence resolution`() {
        for (date in listOf("2026-03-08", "2026-11-01")) {
            val plan = valid.copy(startDate = date, executionTimeZoneId = "America/New_York", localTimes = listOf("01:30", "02:30"))
            assertTrue(ReminderPlanInputValidator.validate(plan).isEmpty())
            assertEquals(listOf("01:30", "02:30"), plan.localTimes)
        }
    }

    @Test
    fun `reports all independent invalid fields with stable field paths and no input values`() {
        val errors = ReminderPlanInputValidator.validate(valid.copy(
            localTimes = emptyList(), startDate = "invalid-start", endDate = "invalid-end", executionTimeZoneId = "private-input",
        ))
        assertEquals(listOf(
            violation("localTimes", PlanInputFailure.REQUIRED),
            violation("startDate", PlanInputFailure.INVALID_DATE),
            violation("endDate", PlanInputFailure.INVALID_DATE),
            violation("executionTimeZoneId", PlanInputFailure.INVALID_TIME_ZONE),
        ), errors)
        assertFalse(errors.toString().contains("private-input"))
        assertTrue(ReminderPlanInputValidator.validate(valid).isEmpty())
    }

    @Test
    fun `decoded absent and null end dates are open ended but empty string is invalid`() {
        val mapper = jacksonObjectMapper()
        val prefix = """{"localTimes":["08:00"],"startDate":"2026-10-26","executionTimeZoneId":"Asia/Shanghai""""
        val absent = mapper.readValue<ReminderPlanDraft>("$prefix}")
        val explicitNull = mapper.readValue<ReminderPlanDraft>("$prefix,\"endDate\":null}")
        assertEquals(absent, explicitNull)
        assertTrue(ReminderPlanInputValidator.validate(absent).isEmpty())
        assertTrue(ReminderPlanInputValidator.validate(explicitNull).isEmpty())
        assertOnly(mapper.readValue("$prefix,\"endDate\":\"\"}"), "endDate", PlanInputFailure.INVALID_DATE)
    }

    private fun assertOnly(plan: ReminderPlanDraft, field: String, failure: PlanInputFailure) {
        assertEquals(listOf(violation(field, failure)), ReminderPlanInputValidator.validate(plan))
    }

    private fun violation(field: String, failure: PlanInputFailure) = PlanInputViolation("plan.$field", failure)
}
