package com.group33.medicationreminder.elder

import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.AuthRepository
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.medicationreminder.core.data.contract.SessionView
import com.group33.shared.contract.UserView
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElderHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 08:30 Asia/Shanghai on 2026-10-22 — inside the 08:00 Due window. */
    private val openClock = Clock.fixed(Instant.parse("2026-10-22T00:30:00Z"), zone)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `anonymous elder session becomes ready and due card uses preview catalog`() = runTest {
        val auth = FakeAuthRepository()
        val doses = PreviewElderDoseRepository(openClock)
        val viewModel = ElderHomeViewModel(auth, doses, openClock, "长者", "示例药物 A", "示例药物 B", "1 片")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("长者", state.sessionDisplayName)
        assertEquals(UserRole.ELDER, auth.lastLoginRole)
        assertEquals("08:00", state.due?.plannedTimeText)
        assertEquals("示例药物 A", state.due?.medicationName)
        assertEquals(DoseStatus.DUE, state.due?.status)
        assertTrue(state.actionsEnabled)
        assertEquals(1, state.upcoming.size)
        assertFalse(state.loginError)
    }

    @Test
    fun `taken records locally and disables further actions`() = runTest {
        val viewModel = ElderHomeViewModel(
            FakeAuthRepository(), PreviewElderDoseRepository(openClock), openClock, "Elder", "A", "B", "1 tablet",
        )
        advanceUntilIdle()

        viewModel.onTaken()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DoseStatus.TAKEN, state.due?.status)
        assertFalse(state.actionsEnabled)
        assertEquals(ElderFeedback.ActionCompleted, state.feedback)
    }

    @Test
    fun `storage failure does not claim taken and re-enables actions`() = runTest {
        val doses = PreviewElderDoseRepository(openClock, failNextRecord = { true })
        val viewModel = ElderHomeViewModel(
            FakeAuthRepository(), doses, openClock, "Elder", "A", "B", "1 tablet",
        )
        advanceUntilIdle()

        viewModel.onTaken()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DoseStatus.DUE, state.due?.status)
        assertTrue(state.actionsEnabled)
        assertEquals(ElderFeedback.NotRecorded, state.feedback)
    }

    @Test
    fun `one millisecond before deadline still accepts taken`() = runTest {
        val planned = Instant.parse("2026-10-22T00:00:00Z").toEpochMilli()
        val deadline = planned + ElderDuePreviewCatalog.RESPONSE_WINDOW_MILLIS
        val clock = Clock.fixed(Instant.ofEpochMilli(deadline - 1), zone)
        val viewModel = ElderHomeViewModel(
            FakeAuthRepository(), PreviewElderDoseRepository(clock), clock, "Elder", "A", "B", "1 tablet",
        )
        advanceUntilIdle()
        assertEquals(DoseStatus.DUE, viewModel.uiState.value.due?.status)
        assertTrue(viewModel.uiState.value.actionsEnabled)

        viewModel.onTaken()
        advanceUntilIdle()
        assertEquals(DoseStatus.TAKEN, viewModel.uiState.value.due?.status)
    }

    @Test
    fun `exact deadline rejects taken and marks missed`() = runTest {
        val planned = Instant.parse("2026-10-22T00:00:00Z").toEpochMilli()
        val deadline = planned + ElderDuePreviewCatalog.RESPONSE_WINDOW_MILLIS
        val clock = Clock.fixed(Instant.ofEpochMilli(deadline), zone)
        val viewModel = ElderHomeViewModel(
            FakeAuthRepository(), PreviewElderDoseRepository(clock), clock, "Elder", "A", "B", "1 tablet",
        )
        advanceUntilIdle()
        assertEquals(DoseStatus.MISSED, viewModel.uiState.value.due?.status)
        assertFalse(viewModel.uiState.value.actionsEnabled)

        viewModel.onTaken()
        advanceUntilIdle()
        assertEquals(DoseStatus.MISSED, viewModel.uiState.value.due?.status)
        assertEquals(ElderFeedback.WindowClosed, viewModel.uiState.value.feedback)
    }

    @Test
    fun `one millisecond after deadline rejects taken`() = runTest {
        val planned = Instant.parse("2026-10-22T00:00:00Z").toEpochMilli()
        val deadline = planned + ElderDuePreviewCatalog.RESPONSE_WINDOW_MILLIS
        val clock = Clock.fixed(Instant.ofEpochMilli(deadline + 1), zone)
        val viewModel = ElderHomeViewModel(
            FakeAuthRepository(), PreviewElderDoseRepository(clock), clock, "Elder", "A", "B", "1 tablet",
        )
        advanceUntilIdle()
        assertEquals(DoseStatus.MISSED, viewModel.uiState.value.due?.status)
        assertFalse(viewModel.uiState.value.actionsEnabled)

        viewModel.onTaken()
        advanceUntilIdle()
        assertEquals(DoseStatus.MISSED, viewModel.uiState.value.due?.status)
        assertFalse(viewModel.uiState.value.actionsEnabled)
        assertEquals(ElderFeedback.WindowClosed, viewModel.uiState.value.feedback)
    }

    @Test
    fun `remind later shows next reminder when inside response window`() {
        val duePlanned = Instant.parse("2026-10-22T00:00:00Z").toEpochMilli()
        val outcome = ElderDuePreviewCatalog.remindLaterOutcome(
            recordedAtEpochMillis = duePlanned,
            responseDeadlineEpochMillis = duePlanned + 7_200_000L,
            zone = zone,
        )
        assertTrue(outcome is ElderRemindLaterOutcome.NextReminder)
    }

    @Test
    fun `preview recorder uses injected clock not wall clock`() = runTest {
        val doses = PreviewElderDoseRepository(openClock)
        val viewModel = ElderHomeViewModel(
            FakeAuthRepository(), doses, openClock, "Elder", "A", "B", "1 tablet",
        )
        advanceUntilIdle()
        viewModel.onRemindLater()
        advanceUntilIdle()
        val receiptTime = doses.statusOf(viewModel.uiState.value.due!!.occurrence)
        assertEquals(DoseStatus.SNOOZED, receiptTime)
        assertTrue(viewModel.uiState.value.feedback is ElderFeedback.RemindLaterNext)
    }

    private class FakeAuthRepository : AuthRepository {
        var lastLoginRole: UserRole? = null
        private val session = MutableStateFlow(SessionView(AuthReadiness.SIGNED_OUT, null))

        override fun observeSession(): Flow<SessionView> = session

        override suspend fun login(role: UserRole, displayName: String): Outcome<UserView> {
            lastLoginRole = role
            if (role != UserRole.ELDER) {
                return Outcome.Failure(RepositoryError.Local(LocalFailureCode.INVALID_INPUT))
            }
            val user = User(id = "install:test", displayName = displayName, role = role)
            session.value = SessionView(AuthReadiness.READY, user)
            return Outcome.Success(user)
        }
    }
}
