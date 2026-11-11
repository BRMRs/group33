package com.group33.medicationreminder.elder

import com.group33.shared.contract.MedicationView
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.ReminderPlanView
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class LiveElderDosesTest {
    private val medicine = MedicationView("medicine", "elder", "Medicine", "1", true)
    private val plan = ReminderPlanView("plan", "elder", medicine.id, 2, listOf("02:30"),
        "2026-03-08", "America/New_York", true, 1, 1, "2026-03-08")

    @Test fun nextDayWindowUsesExecutionZoneAndFrozenGapOffset() {
        val now = Instant.parse("2026-03-08T05:00:00Z")
        val dose = nextElderOccurrences(listOf(plan), listOf(medicine), now, 45).single()
        assertEquals("02:30", dose.key.localTime)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), dose.plannedAt)
        assertEquals(-14_400, dose.offsetSeconds)
        assertEquals(45L, dose.planSnapshotAt)
        assertEquals(dose.key, nextElderOccurrences(listOf(plan), listOf(medicine),
            Instant.ofEpochMilli(dose.plannedAt + 1), 46).single().key)
        assertTrue(nextElderOccurrences(listOf(plan), listOf(medicine),
            Instant.ofEpochMilli(dose.plannedAt + 7_200_000), 47).isEmpty())
    }

    @Test fun firstSyncWithinResponseWindowCreatesDoseButDeadlineDoesNotReopenIt() {
        val planned = Instant.parse("2026-03-08T07:30:00Z")
        val withinWindow = nextElderOccurrences(listOf(plan), listOf(medicine), planned.plusSeconds(600), 45)
        assertEquals(listOf(OccurrenceKey("plan", "2026-03-08", "02:30")), withinWindow.map { it.key })
        assertNotNull(dueElderDose(withinWindow, Clock.fixed(planned.plusSeconds(600), ZoneOffset.UTC)))

        val atDeadline = nextElderOccurrences(listOf(plan), listOf(medicine), planned.plusSeconds(7_200), 46)
        assertTrue(atDeadline.isEmpty())
    }

    @Test fun groupedDoseHonorsExclusiveDeadlineAndSnooze() {
        val start = Instant.parse("2026-03-08T05:00:00Z")
        val dose = nextElderOccurrences(listOf(plan), listOf(medicine), start, 45).single()
        val other = dose.copy(key = dose.key.copy(planId = "another-plan"))
        fun due(offset: Long, rows: List<LocalElderOccurrence> = listOf(dose, other)) =
            dueElderDose(rows, Clock.fixed(Instant.ofEpochMilli(dose.plannedAt + offset), ZoneOffset.UTC))
        assertNull(due(-1))
        assertEquals(listOf(dose.key, other.key), due(0)?.keys)
        assertTrue(checkNotNull(due(0)).canSnooze)
        assertNull(due(1, listOf(dose.copy(snoozedUntil = dose.plannedAt + 600_000))))
        assertNotNull(due(600_000, listOf(dose.copy(snoozedUntil = dose.plannedAt + 600_000))))
        assertFalse(checkNotNull(due(7_199_999)).canSnooze)
        assertNull(due(7_200_000))
    }
}
