package com.group33.server.plan

import com.group33.shared.contract.ReminderPlanDraft
import java.time.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PlanSelfOverlapValidatorTest {
    private fun plan(zone: String, start: String, end: String?, vararg times: String) = ReminderPlanDraft(times.toList(), start, zone, end)

    @ParameterizedTest @CsvSource(
        "America/New_York,2026-03-08T02:30,2026-03-08T07:30:00Z",
        "America/New_York,2026-11-01T01:30,2026-11-01T05:30:00Z",
        "Australia/Lord_Howe,2026-10-04T02:15,2026-10-03T15:45:00Z",
        "Pacific/Apia,2011-12-30T12:00,2011-12-30T22:00:00Z",
        "Asia/Shanghai,2026-01-01T08:00,2026-01-01T00:00:00Z")
    fun `P0 golden resolutions use whole gap and earlier fold instant`(zone: String, local: String, expected: String) {
        val nominal = LocalDateTime.parse(local); val id = ZoneId.of(zone)
        assertEquals(Instant.parse(expected), PlanSelfOverlapValidator.resolve(nominal, id))
        assertEquals(nominal.atZone(id).toInstant(), PlanSelfOverlapValidator.resolve(nominal, id))
    }

    @ParameterizedTest @CsvSource(
        "America/New_York,2026-03-08,2026-03-08,02:30,03:30,true",
        "America/New_York,2026-03-09,2026-03-09,02:30,03:30,false",
        "America/New_York,2026-03-07,2026-03-07,02:30,03:30,false",
        "America/New_York,2026-03-01,2026-03-08,02:30,03:30,true",
        "America/New_York,2026-03-09,2027-03-13,02:30,03:30,false",
        "America/New_York,2026-11-01,2026-11-01,01:30,02:30,false",
        "Australia/Lord_Howe,2026-10-04,2026-10-04,02:15,02:45,true",
        "Australia/Lord_Howe,2026-10-04,2026-10-04,02:15,03:15,false",
        "Etc/UTC,0000-01-01,9999-12-31,00:00,23:59,false")
    fun `collisions respect entire inclusive range and actual transition length`(zone: String, start: String, end: String,
        a: String, b: String, expected: Boolean) {
        assertEquals(expected, PlanSelfOverlapValidator.hasCollision(plan(zone, start, end, a, b)))
    }

    @Test fun `open end and far future cannot be approximated by seven day or year horizon`() {
        assertTrue(PlanSelfOverlapValidator.hasCollision(plan("America/New_York", "2026-03-09", null, "02:30", "03:30")))
        assertTrue(PlanSelfOverlapValidator.hasCollision(plan("America/New_York", "9998-12-31", "9999-12-31", "02:30", "03:30")))
        assertFalse(PlanSelfOverlapValidator.hasCollision(plan("America/New_York", "0000-01-01", null, "02:30")))
        assertFalse(PlanSelfOverlapValidator.hasCollision(plan("America/New_York", "0000-01-01", null, "08:00", "20:00")))
    }

    @Test fun `full date skip can collide across nominal dates with only one local time`() {
        assertTrue(PlanSelfOverlapValidator.hasCollision(plan("Pacific/Apia", "2011-12-30", "2011-12-31", "12:00")))
        assertFalse(PlanSelfOverlapValidator.hasCollision(plan("Pacific/Apia", "2011-12-30", "2011-12-30", "12:00")))
        assertFalse(PlanSelfOverlapValidator.hasCollision(plan("Pacific/Apia", "2011-12-31", "2011-12-31", "12:00")))
    }

    @Test fun `algorithm agrees with exhaustive nominal-minute reference near every historical TZDB transition`() {
        // All installed zones and actual historical gaps: compare small transition windows against a
        // structurally independent atZone + duplicate-Instant reference, including date-line and second shifts.
        var windows = 0
        for (name in ZoneId.getAvailableZoneIds().sorted()) {
            val zone = ZoneId.of(name)
            for (transition in zone.rules.transitions.filter { it.isGap }) {
                val start = transition.dateTimeBefore.toLocalDate().minusDays(1)
                val end = transition.dateTimeAfter.toLocalDate().plusDays(1)
                if (start.year !in 0..9999 || end.year !in 0..9999) continue
                val inputs = listOf(listOf("00:00", "12:00", "23:59"), listOf("01:30", "02:00", "02:30", "03:00", "03:30"))
                for (times in inputs) {
                    val seen = mutableSetOf<Instant>(); var duplicate = false; var date = start
                    while (date <= end) {
                        for (time in times) if (!seen.add(date.atTime(LocalTime.parse(time)).atZone(zone).toInstant())) duplicate = true
                        date = date.plusDays(1)
                    }
                    assertEquals(duplicate, PlanSelfOverlapValidator.hasCollision(ReminderPlanDraft(times, start.toString(), name, end.toString())), name)
                    windows++
                }
            }
        }
        assertTrue(windows > 10_000, "Exercise actual installed historical transition data")
    }

    @Test fun `invalid shapes must be rejected before collision logic rather than normalized`() {
        for (bad in listOf(plan("Etc/UTC", "2026-01-01", null, "08:00", "08:00"),
            plan("+08:00", "2026-01-01", null, "08:00"), plan("Etc/UTC", "2026-02-30", null, "08:00"))) {
            assertThrows(IllegalArgumentException::class.java) { PlanSelfOverlapValidator.hasCollision(bad) }
        }
    }
}
