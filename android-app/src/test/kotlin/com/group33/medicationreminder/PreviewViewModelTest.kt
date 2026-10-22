package com.group33.medicationreminder

import com.group33.medicationreminder.caregiver.CaregiverHomeViewModel
import com.group33.medicationreminder.core.data.DemoPreview
import com.group33.medicationreminder.core.data.InMemoryMedicationRepository
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.AuthRepository
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.SessionView
import com.group33.medicationreminder.elder.ElderHomeViewModel
import com.group33.medicationreminder.elder.PreviewElderDoseRepository
import com.group33.shared.contract.UserView
import com.group33.shared.model.LegacyScheduleRules
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    fun elderDuePreviewLoadsAfterAnonymousElderLogin() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val clock = Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
            val auth = object : AuthRepository {
                private val session = MutableStateFlow(SessionView(AuthReadiness.SIGNED_OUT, null))
                override fun observeSession(): Flow<SessionView> = session
                override suspend fun login(role: UserRole, displayName: String): Outcome<UserView> {
                    val user = User("install:preview", displayName, role)
                    session.value = SessionView(AuthReadiness.READY, user)
                    return Outcome.Success(user)
                }
            }
            val viewModel = ElderHomeViewModel(
                auth, PreviewElderDoseRepository(), clock, "Elder",
                "Sample medication A", "Sample medication B", "1 tablet",
            )
            advanceUntilIdle()
            assertEquals("08:00", viewModel.uiState.value.due?.plannedTimeText)
            assertEquals("Elder", viewModel.uiState.value.sessionDisplayName)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
