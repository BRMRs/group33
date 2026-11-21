package com.group33.medicationreminder.integration

import com.group33.medicationreminder.core.data.contract.CaregiverElderContext
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.*
import org.junit.Test

class CaregiverFeedbackRangeInputTest {
    private val scope = RepositoryScope("fixture", "installation-a", "caregiver-a")
    private val context = CaregiverElderContext(scope, 1, "relationship-a", "elder-a", "Fixture Elder")
    private val today = LocalDate.parse("2026-11-16")

    @Test fun `uses the supplied today and existing inclusive thirty date rule`() {
        val input = CaregiverFeedbackRangeInput({ context }) { CaregiverFeedbackDate(context, today) }
        assertEquals(LocalDateRange("2026-10-18", "2026-11-16"), input.resolve(context))
    }

    @Test fun `month year and leap day boundaries retain exactly thirty dates`() {
        for (date in listOf("2026-01-01", "2024-03-01", "2026-03-01")) {
            val supplied = LocalDate.parse(date)
            val input = CaregiverFeedbackRangeInput({ context }) { CaregiverFeedbackDate(context, supplied) }
            val range = requireNotNull(input.resolve(context))
            assertEquals(date, range.throughDate)
            assertEquals(30L, ChronoUnit.DAYS.between(LocalDate.parse(range.fromDate), supplied) + 1)
        }
    }

    @Test fun `missing date never falls back to device or previous date`() {
        var date: CaregiverFeedbackDate? = CaregiverFeedbackDate(context, today)
        val input = CaregiverFeedbackRangeInput({ context }) { date }
        assertNotNull(input.resolve(context))
        date = null
        assertNull(input.resolve(context))
    }

    @Test fun `signed out context does not read a private date`() {
        var calls = 0
        val input = CaregiverFeedbackRangeInput({ null }) { calls++; CaregiverFeedbackDate(context, today) }
        assertNull(input.resolve(context))
        assertEquals(0, calls)
    }

    @Test fun `stale caller is rejected before consulting the date owner`() {
        var calls = 0
        val input = CaregiverFeedbackRangeInput({ context.copy(sessionGeneration = 2) }) {
            calls++; CaregiverFeedbackDate(context, today)
        }
        assertNull(input.resolve(context))
        assertEquals(0, calls)
    }

    @Test fun `date from another environment installation user session relationship or elder is rejected`() {
        val otherContexts = listOf(
            context.copy(scope = scope.copy(environmentId = "other")),
            context.copy(scope = scope.copy(installationId = "installation-b")),
            context.copy(scope = scope.copy(userId = "caregiver-b")),
            context.copy(sessionGeneration = 2),
            context.copy(relationshipId = "relationship-b"),
            context.copy(elderId = "elder-b"),
        )
        for (other in otherContexts) {
            val input = CaregiverFeedbackRangeInput({ context }) { CaregiverFeedbackDate(other, today) }
            assertNull(input.resolve(context))
        }
    }

    @Test fun `context loss while resolving discards the captured date`() {
        var active: CaregiverElderContext? = context
        val input = CaregiverFeedbackRangeInput({ active }) {
            active = null
            CaregiverFeedbackDate(context, today)
        }
        assertNull(input.resolve(context))
    }

    @Test fun `switch while resolving cannot expose the previous elders range`() {
        var active = context
        val input = CaregiverFeedbackRangeInput({ active }) {
            active = context.copy(elderId = "elder-b", relationshipId = "relationship-b")
            CaregiverFeedbackDate(context, today)
        }
        assertNull(input.resolve(context))
    }

    @Test fun `same account restored session requires a newly scoped date`() {
        var active = context
        var date = CaregiverFeedbackDate(context, today)
        val input = CaregiverFeedbackRangeInput({ active }) { date }
        assertNotNull(input.resolve(context))
        active = context.copy(sessionGeneration = 2)
        assertNull(input.resolve(active))
        date = CaregiverFeedbackDate(active, today.plusDays(1))
        assertEquals("2026-11-17", input.resolve(active)?.throughDate)
    }

    @Test fun `new input date is read without retaining the previous range`() {
        var date = CaregiverFeedbackDate(context, today)
        val input = CaregiverFeedbackRangeInput({ context }) { date }
        assertEquals("2026-11-16", input.resolve(context)?.throughDate)
        date = CaregiverFeedbackDate(context, today.plusDays(1))
        assertEquals("2026-11-17", input.resolve(context)?.throughDate)
    }

    @Test fun `display name update does not change the authorization context`() {
        val renamed = context.copy(elderDisplayName = "Updated fixture name")
        val input = CaregiverFeedbackRangeInput({ renamed }) { CaregiverFeedbackDate(context, today) }
        assertNotNull(input.resolve(renamed))
    }
}
