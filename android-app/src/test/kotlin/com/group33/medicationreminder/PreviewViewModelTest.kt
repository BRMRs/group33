package com.group33.medicationreminder

import com.group33.medicationreminder.caregiver.CaregiverHomeViewModel
import com.group33.medicationreminder.core.data.DemoPreview
import com.group33.medicationreminder.core.data.InMemoryMedicationRepository
import com.group33.medicationreminder.elder.ElderHomeViewModel
import com.group33.shared.model.LegacyScheduleRules
import com.group33.shared.model.MedicationSchedule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {
    @Test
    fun demoCreationUsesValidDateAndDistinctIdsForRapidTaps() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = InMemoryMedicationRepository()
            val clock = Clock.fixed(Instant.parse("2026-10-19T16:01:00Z"), ZoneId.of("Asia/Shanghai"))
            val viewModel = CaregiverHomeViewModel(repository, clock)
            viewModel.addDemoSchedule()
            viewModel.addDemoSchedule()
            val plans = viewModel.schedules.first()
            assertEquals(2, plans.size)
            assertNotEquals(plans[0].id, plans[1].id)
            plans.forEach {
                assertEquals("2026-10-20", it.startDate)
                assertEquals(DemoPreview.ELDER_ID, it.elderId)
                assertTrue(LegacyScheduleRules.validate(it).isEmpty())
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun todayRefreshesAcrossMidnightWhileScreenIsObserved() = runTest {
        var instant = Instant.parse("2026-10-20T15:59:30Z")
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
            override fun instant(): Instant = instant
        }
        val repository = InMemoryMedicationRepository()
        repository.saveSchedule(MedicationSchedule(
            "plan-1", "medication-1", DemoPreview.ELDER_ID, DemoPreview.CAREGIVER_ID,
            listOf("08:00"), "2026-10-20", "2026-10-20", updatedAtEpochMillis = 1L,
        ))
        var displayed = emptyList<MedicationSchedule>()
        val viewModel = ElderHomeViewModel(repository, clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.schedules.collect { displayed = it }
        }
        runCurrent()
        assertEquals(1, displayed.size)
        instant = Instant.parse("2026-10-20T16:00:30Z")
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(displayed.isEmpty())
    }
}
