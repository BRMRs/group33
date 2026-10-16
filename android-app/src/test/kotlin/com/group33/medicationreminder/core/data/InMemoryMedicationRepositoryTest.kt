package com.group33.medicationreminder.core.data

import com.group33.shared.model.MedicationSchedule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryMedicationRepositoryTest {
    @Test
    fun saveScheduleReplacesScheduleWithSameId() = runBlocking {
        val repository = InMemoryMedicationRepository()
        val original = schedule(enabled = true)
        val updated = schedule(enabled = false)

        repository.saveSchedule(original)
        repository.saveSchedule(updated)

        assertEquals(listOf(updated), repository.observeSchedulesForCaregiver("caregiver-1").first())
    }

    @Test
    fun observationsAreScopedByCaregiverAndElderAndInclusiveDates() = runBlocking {
        val repository = InMemoryMedicationRepository()
        val date = LocalDate.parse("2026-10-20")
        val active = schedule(true).copy(startDate = date.toString(), endDate = date.toString())
        val records = listOf(
            active,
            active.copy(id = "another-elder", elderId = "elder-2"),
            active.copy(id = "another-caregiver", caregiverId = "caregiver-2", elderId = "elder-2"),
            active.copy(id = "disabled", enabled = false),
            active.copy(id = "future", startDate = "2026-10-21", endDate = null),
            active.copy(id = "past", startDate = "2026-10-18", endDate = "2026-10-19"),
        )
        records.forEach { repository.saveSchedule(it) }
        assertEquals(listOf(active), repository.observeSchedulesForElderOn("elder-1", date).first())
        assertEquals(records.filter { it.caregiverId == "caregiver-1" },
            repository.observeSchedulesForCaregiver("caregiver-1").first())
        assertEquals(emptyList<MedicationSchedule>(), repository.observeSchedulesForElderOn("unknown", date).first())
    }

    @Test
    fun disablingAPlanUpdatesAnExistingTodayObservation() = runBlocking {
        val repository = InMemoryMedicationRepository()
        val active = schedule(true)
        repository.saveSchedule(active)
        val changes = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeSchedulesForElderOn("elder-1", LocalDate.parse("2026-10-20")).take(2).toList()
        }
        repository.saveSchedule(active.copy(enabled = false))
        assertEquals(listOf(listOf(active), emptyList()), changes.await())
    }

    @Test
    fun invalidDemoDataIsRejectedWithoutChangingExistingData() = runBlocking {
        val repository = InMemoryMedicationRepository()
        val active = schedule(true)
        repository.saveSchedule(active)
        try {
            repository.saveSchedule(active.copy(startDate = "demo"))
            throw AssertionError("Expected invalid date to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(active), repository.observeSchedulesForCaregiver("caregiver-1").first())
        }
    }

    private fun schedule(enabled: Boolean) = MedicationSchedule(
        id = "schedule-1",
        medicationId = "medication-1",
        elderId = "elder-1",
        caregiverId = "caregiver-1",
        times = listOf("08:00"),
        startDate = "2026-10-09",
        enabled = enabled,
        updatedAtEpochMillis = 1L,
    )
}

