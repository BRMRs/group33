package com.group33.server

import com.group33.server.auth.CareRelationshipAccess
import com.group33.server.web.ApiException
import com.group33.server.web.ScheduleController
import com.group33.shared.model.MedicationSchedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt

class ScheduleAuthorizationTest {
    private val store = InMemoryScheduleStore()
    private var active = true
    private val access = CareRelationshipAccess { caregiver, elder ->
        active && caregiver == "caregiver-1" && elder == "elder-1"
    }
    private val controller = ScheduleController(store, access)
    private val jwt = Jwt.withTokenValue("test-token").header("alg", "none")
        .subject("caregiver-1").claim("role", "CAREGIVER").build()
    private val plan = MedicationSchedule(
        "plan-1", "medication-1", "elder-1", "caregiver-1",
        listOf("08:00"), "2026-10-20", updatedAtEpochMillis = 1L,
    )

    @Test
    fun `granted relationship permits access and revocation removes it`() {
        assertEquals(plan, controller.create(jwt, plan))
        assertEquals(listOf(plan), controller.list(jwt))
        active = false
        assertEquals(emptyList<MedicationSchedule>(), controller.list(jwt))
        val error = assertThrows(ApiException::class.java) { controller.create(jwt, plan.copy(enabled = false)) }
        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals("RESOURCE_NOT_ACCESSIBLE", error.code)
        assertEquals(plan, store.all().single())
    }

    @Test
    fun `authorized caller still cannot save invalid dates and times or spoof their identity`() {
        listOf(
            plan.copy(startDate = "demo"),
            plan.copy(times = listOf("not-a-time")),
            plan.copy(caregiverId = "different-caregiver"),
            plan.copy(endDate = "2026-10-19"),
        ).forEach { invalid ->
            val error = assertThrows(ApiException::class.java) { controller.create(jwt, invalid) }
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status)
            assertEquals("VALIDATION_FAILED", error.code)
        }
        assertEquals(emptyList<MedicationSchedule>(), store.all())
    }

    @Test
    fun `unauthorized target is rejected before exposing validation details`() {
        val error = assertThrows(ApiException::class.java) {
            controller.create(jwt, plan.copy(elderId = "other-elder", startDate = "demo"))
        }
        assertEquals("RESOURCE_NOT_ACCESSIBLE", error.code)
        assertEquals(null, error.fieldErrors)
    }

    @Test
    fun `resource identity cannot be moved to another elder or owner through replacement`() {
        store.save(plan)
        listOf(plan.copy(elderId = "other-elder"), plan.copy(caregiverId = "other-caregiver")).forEach { moved ->
            assertThrows(ScheduleOwnershipConflict::class.java) { store.save(moved) }
        }
        assertEquals(listOf(plan), store.all())
    }

    @Test
    fun `existing inaccessible plan cannot be overwritten using its id`() {
        val original = plan.copy(elderId = "other-elder", caregiverId = "other-caregiver")
        store.save(original)
        val error = assertThrows(ApiException::class.java) { controller.create(jwt, plan) }
        assertEquals("RESOURCE_NOT_ACCESSIBLE", error.code)
        assertEquals(listOf(original), store.all())
    }
}
