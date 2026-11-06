package com.group33.server.plan

import com.group33.shared.contract.ReminderPlanDraft
import java.time.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class PlanCrossOverlapValidatorTest {
    private fun draft(times: List<String>, zone: String = "Etc/UTC", start: String = "2026-03-01", end: String? = "2026-03-31") =
        ReminderPlanDraft(times, start, zone, end)
    @Test fun `equal instants across different zones collide and one minute spacing is allowed`() {
        assertTrue(PlanCrossOverlapValidator.hasCollision(draft(listOf("08:00")), draft(listOf("16:00"), "Asia/Shanghai")))
        assertFalse(PlanCrossOverlapValidator.hasCollision(draft(listOf("08:00")), draft(listOf("08:01"))))
    }
    @Test fun `disjoint local dates may still collide across the international date line`() {
        assertTrue(PlanCrossOverlapValidator.hasCollision(draft(listOf("23:00"), "Etc/UTC", "2026-03-01", "2026-03-01"),
            draft(listOf("13:00"), "Pacific/Kiritimati", "2026-03-02", "2026-03-02")))
        assertFalse(PlanCrossOverlapValidator.hasCollision(draft(listOf("23:00"), start="2026-03-01",end="2026-03-01"),
            draft(listOf("23:00"),start="2026-03-02",end="2026-03-02")))
    }
    @Test fun `spring gap shifts full duration and fall fold selects earlier instant only`() {
        val gap=draft(listOf("02:30"),"America/New_York","2026-03-08","2026-03-08")
        assertTrue(PlanCrossOverlapValidator.hasCollision(gap,draft(listOf("07:30"),start="2026-03-08",end="2026-03-08")))
        val fold=draft(listOf("01:30"),"America/New_York","2026-11-01","2026-11-01")
        assertTrue(PlanCrossOverlapValidator.hasCollision(fold,draft(listOf("05:30"),start="2026-11-01",end="2026-11-01")))
        assertFalse(PlanCrossOverlapValidator.hasCollision(fold,draft(listOf("06:30"),start="2026-11-01",end="2026-11-01")))
    }
    @Test fun `whole skipped date and historical second offsets are handled`() {
        assertTrue(PlanCrossOverlapValidator.hasCollision(draft(listOf("12:00"),"Pacific/Apia","2011-12-30","2011-12-30"),
            draft(listOf("12:00"),"Pacific/Apia","2011-12-31","2011-12-31")))
        assertFalse(PlanCrossOverlapValidator.hasCollision(draft(listOf("12:00"),"Europe/Paris","1890-01-01","1890-01-01"),
            draft(listOf("12:00"),"Etc/UTC","1890-01-01","1890-01-01")))
    }
    @Test fun `collision after seven days and far future recurring transitions are found`() {
        assertTrue(PlanCrossOverlapValidator.hasCollision(draft(listOf("02:30"),"America/New_York","2026-03-09",null),
            draft(listOf("07:30"),start="9999-01-01",end="9999-12-31")))
        assertTimeout(Duration.ofSeconds(15)) {
            assertFalse(PlanCrossOverlapValidator.hasCollision(draft(listOf("02:30"),"America/New_York","0000-01-01",null),
                draft(listOf("02:31"),"America/New_York","0000-01-01",null)))
        }
    }
    @Test fun `supported endpoints are inclusive and cross year carry is exact`() {
        for (date in listOf("0000-01-01","9999-12-31"))
            assertTrue(PlanCrossOverlapValidator.hasCollision(draft(listOf("00:00","23:59"),start=date,end=date),draft(listOf("23:59"),start=date,end=date)))
        assertFalse(PlanCrossOverlapValidator.hasCollision(draft(listOf("23:59"),start="9999-12-31",end="9999-12-31"),
            draft(listOf("00:00"),"Pacific/Kiritimati","9999-12-31","9999-12-31")))
    }
    @ParameterizedTest @ValueSource(strings=["2026-03-01","2026-10-25","2011-12-25","1890-01-01"])
    fun `segment algorithm agrees with independent finite daily Java resolver oracle in both directions`(start: String) {
        val zones=listOf("Etc/UTC","America/New_York","Australia/Lord_Howe","Pacific/Apia","Europe/Paris","Asia/Shanghai")
        val first=LocalDate.parse(start); val end=first.plusDays(14).toString()
        fun oracle(p: ReminderPlanDraft): Set<Instant> = buildSet {
            var date=LocalDate.parse(p.startDate)
            while (date<=LocalDate.parse(p.endDate)) {
                p.localTimes.forEach { add(date.atTime(LocalTime.parse(it)).atZone(ZoneId.of(p.executionTimeZoneId)).toInstant()) }
                date=date.plusDays(1)
            }
        }
        for (a in zones.indices) for (b in zones.indices) {
            val left=draft(listOf("00:00","01:30","02:00","02:30","03:30","12:00","23:59"),zones[a],start,end)
            val right=draft(listOf("00:00","05:30","06:30","07:30","13:00","23:59"),zones[b],first.plusDays(2).toString(),end)
            val expected=oracle(left).intersect(oracle(right)).isNotEmpty()
            assertEquals(expected,PlanCrossOverlapValidator.hasCollision(left,right),"$start ${zones[a]} ${zones[b]}")
            assertEquals(expected,PlanCrossOverlapValidator.hasCollision(right,left))
        }
    }
}
