package com.group33.medicationreminder.caregiver.plan

import com.group33.shared.contract.*
import org.junit.Assert.*
import org.junit.Test

class PlanEditDraftTest {
    private val plan = ReminderPlanView("p", "e", "m", 3, listOf("08:00"), "2026-11-03", "Asia/Shanghai", true, 1, 1)
    private val input = UpdateReminderPlanRequest(3,
        ReminderPlanDraft(listOf("09:00"), "2026-11-04", "Asia/Shanghai"),
        TimeZoneAcknowledgement("Asia/Shanghai", false), MedicationText("Demo", "one"))

    @Test fun `conflict and refresh preserve input and original revision until explicit decision`() {
        val draft = PlanEditDraft.open(plan, input).revisionRejected().offerLatest(plan.copy(revision = 4))
        assertEquals(input, draft.input); assertEquals(plan, draft.confirmed)
        assertTrue(draft.conflict)
        val resolved = draft.keepInputAgainstReviewedRevision()
        assertEquals(input.copy(expectedRevision = 4), resolved.input)
        assertFalse(resolved.conflict); assertNull(resolved.latestForReview)
        assertEquals(3L, draft.input.expectedRevision)
    }
    @Test fun `typing cannot replace expected revision`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanEditDraft.open(plan, input).changeInput(input.copy(expectedRevision = 4))
        }
    }
    @Test fun `unrelated older and same revision records cannot become recovery targets`() {
        val draft = PlanEditDraft.open(plan, input).revisionRejected()
        for (invalid in listOf(plan, plan.copy(revision = 2), plan.copy(id = "other", revision = 4),
            plan.copy(elderId = "other", revision = 4), plan.copy(medicationId = "other", revision = 4))) {
            assertThrows(IllegalArgumentException::class.java) { draft.offerLatest(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft.offerLatest(plan.copy(revision = 5)).offerLatest(plan.copy(revision = 4))
        }
    }
    @Test fun `inactive latest and missing reviewed version cannot be accepted`() {
        val draft = PlanEditDraft.open(plan, input).revisionRejected()
        assertThrows(IllegalStateException::class.java) { draft.keepInputAgainstReviewedRevision() }
        assertThrows(IllegalStateException::class.java) {
            draft.offerLatest(plan.copy(revision = 4, active = false)).keepInputAgainstReviewedRevision()
        }
    }
    @Test fun `typing during conflict keeps recovery pending`() {
        val changed = input.copy(plan = input.plan.copy(localTimes = listOf("10:00")))
        val draft = PlanEditDraft.open(plan, input).revisionRejected().changeInput(changed)
        assertTrue(draft.conflict); assertEquals(changed, draft.input)
    }
}
