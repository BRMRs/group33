package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanSaveViewModelTest {
    private val scope = RepositoryScope("test", "installation", "caregiver")
    private val input = PlanFormInput("Demo", "Demo dose", localTimes = listOf("08:00"),
        startDate = "2026-10-23", executionTimeZoneId = "Asia/Shanghai")
    private val photo = MedicationPhotoView("photo", "elder", "image/png", 1)
    private val ack = TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai")

    private inner class Fixture : ReminderPlanRepository, OnlineCommandRepository {
        val model = PlanSaveViewModel("elder", scope, this, this)
        var begins = 0
        val calls = mutableListOf<CommandContext>()
        val resumes = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var throws = false
        var beginFails = false
        var resultState = OnlineCommandState.AWAITING_CONFIRMATION
        var recoveredState = OnlineCommandState.CONFIRMED
        var recoveredScope = scope
        var cache = CacheState.REFRESH_REQUIRED
        var captured: ConfirmReminderPlanRequest? = null
        override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> {
            begins++
            if (beginFails) return Outcome.Failure(RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE))
            return Outcome.Success(CommandContext("00000000-0000-4000-8000-${begins.toString().padStart(12, '0')}", scope, kind, targetId))
        }
        override suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse> {
            calls += command
            captured = draft
            gate?.await()
            if (throws) error("transport failed after sending")
            return when (resultState) {
                OnlineCommandState.AWAITING_CONFIRMATION -> OnlineCommandResult.AwaitingConfirmation(command.operationId)
                OnlineCommandState.NOT_SUBMITTED -> OnlineCommandResult.NotSubmitted(command.operationId, RepositoryError.Local(LocalFailureCode.OFFLINE))
                OnlineCommandState.REJECTED -> OnlineCommandResult.Rejected(command.operationId,
                    RepositoryError.Remote(com.group33.shared.model.ApiError(message = "Rejected", code = "VALIDATION_FAILED",
                        retryable = false, requestId = "test-request")))
                OnlineCommandState.CONFIRMED -> OnlineCommandResult.Confirmed(command.operationId,
                    ConfirmReminderPlanResponse(MedicationView("med", elderId, "Demo", "Demo dose", true),
                        ReminderPlanView("plan", elderId, "med", 1, draft.plan.localTimes, draft.plan.startDate,
                            draft.plan.executionTimeZoneId, true, 1, 1)), cache)
            }
        }
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
            resumes += operationId
            return Outcome.Success(OnlineCommandRecord(operationId, recoveredScope, OnlineCommandKind.CONFIRM_PLAN,
                "elder", recoveredState, null, cache, null))
        }
        override fun observeCommands() = flowOf(emptyList<OnlineCommandRecord>())
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> = error("Not used")
        override fun observePlans(elderId: String): Flow<Cached<List<ReminderPlanView>>> = error("Not used")
        override fun observeMedications(elderId: String): Flow<Cached<List<MedicationView>>> = error("Not used")
        override fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>> = error("Not used")
        override suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse> = error("Not used")
        override suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView> = error("Not used")
    }

    @Test fun `invalid fields missing photo and wrong Elder never begin a command`() = runTest {
        val f = Fixture()
        f.model.save(input.copy(displayName = ""), photo, ack)
        assertEquals(PlanSaveInputError.INVALID_FIELDS, f.model.state.value.inputError)
        f.model.save(input, null, ack)
        assertEquals(PlanSaveInputError.PHOTO_REQUIRED, f.model.state.value.inputError)
        f.model.save(input, photo.copy(elderId = "other"), ack)
        assertEquals(PlanSaveInputError.WRONG_ELDER, f.model.state.value.inputError)
        assertEquals(0, f.begins)
    }

    @Test fun `unknown or different zone needs explicit acknowledgement`() = runTest {
        val f = Fixture()
        val unknown = ack.copy(observedElderTimeZoneId = null)
        f.model.save(input, photo, unknown)
        assertEquals(PlanSaveInputError.TIME_ZONE_CONFIRMATION_REQUIRED, f.model.state.value.inputError)
        f.model.save(input, photo, ack.copy(caregiverTimeZoneId = "Europe/London"))
        assertEquals(0, f.begins)
        f.model.save(input, photo, unknown.copy(mismatchAcknowledged = true))
        assertEquals(1, f.calls.size)
    }

    @Test fun `duplicate taps and unknown result cannot create another plan`() = runTest {
        val f = Fixture().apply { gate = CompletableDeferred() }
        val saving = launch { f.model.save(input, photo, ack) }
        runCurrent()
        assertEquals(PlanSaveStatus.SUBMITTING, f.model.state.value.status)
        f.model.save(input, photo, ack)
        assertEquals(1, f.calls.size)
        f.gate!!.complete(Unit)
        saving.join()
        f.model.save(input.copy(displayName = "Changed"), photo, ack)
        assertEquals(1, f.begins)
        assertEquals("Demo", f.model.state.value.request!!.medication.displayName)
        f.model.reconcile()
        assertEquals(listOf(f.calls.single().operationId), f.resumes)
        assertEquals(PlanSaveStatus.CONFIRMED, f.model.state.value.status)
    }

    @Test fun `confirmed cache pending remains saved and recovery cannot downgrade it`() = runTest {
        val f = Fixture().apply { resultState = OnlineCommandState.CONFIRMED }
        f.model.save(input, photo, ack)
        assertEquals(PlanSaveStatus.CONFIRMED, f.model.state.value.status)
        assertEquals(CacheState.REFRESH_REQUIRED, f.model.state.value.cacheState)
        f.recoveredState = OnlineCommandState.NOT_SUBMITTED
        f.model.reconcile()
        assertEquals(PlanSaveStatus.CONFIRMED, f.model.state.value.status)
        f.model.save(input, photo, ack)
        assertEquals(1, f.calls.size)
    }

    @Test fun `offline retains request and explicit resubmission reuses intent`() = runTest {
        val f = Fixture().apply { resultState = OnlineCommandState.NOT_SUBMITTED }
        f.model.save(input, photo, ack)
        assertEquals(PlanSaveStatus.NOT_SUBMITTED, f.model.state.value.status)
        assertEquals(input.dosageText, f.model.state.value.request!!.medication.dosageText)
        f.model.reconcile()
        assertTrue(f.resumes.isEmpty())
        f.model.save(input, photo, ack)
        assertEquals(1, f.begins)
        assertEquals(f.calls[0], f.calls[1])
    }

    @Test fun `rejected payload is not replayed and corrected content gets new intent`() = runTest {
        val f = Fixture().apply { resultState = OnlineCommandState.REJECTED }
        f.model.save(input, photo, ack)
        assertEquals(PlanSaveStatus.REJECTED, f.model.state.value.status)
        f.model.save(input, photo, ack)
        f.model.reconcile()
        assertEquals(1, f.calls.size)
        assertTrue(f.resumes.isEmpty())
        f.model.save(input.copy(displayName = "Corrected"), photo, ack)
        assertEquals(2, f.begins)
        assertNotEquals(f.calls[0].operationId, f.calls[1].operationId)
    }

    @Test fun `unexpected dispatch failure stays unknown and foreign recovery is ignored`() = runTest {
        val f = Fixture().apply { throws = true }
        f.model.save(input, photo, ack)
        assertEquals(PlanSaveStatus.AWAITING_CONFIRMATION, f.model.state.value.status)
        f.recoveredScope = scope.copy(environmentId = "other")
        f.model.reconcile()
        assertEquals(PlanSaveStatus.AWAITING_CONFIRMATION, f.model.state.value.status)
        f.model.save(input, photo, ack)
        assertEquals(1, f.begins)
    }

    @Test fun `journal creation failure never calls plan repository`() = runTest {
        val f = Fixture().apply { beginFails = true }
        f.model.save(input, photo, ack)
        assertEquals(PlanSaveStatus.NOT_SUBMITTED, f.model.state.value.status)
        assertTrue(f.calls.isEmpty())
        assertNotNull(f.model.state.value.request)
    }

    @Test fun `cancellation after dispatch keeps identity for reconciliation`() = runTest {
        val f = Fixture().apply { gate = CompletableDeferred() }
        val job = launch { f.model.save(input, photo, ack) }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(PlanSaveStatus.AWAITING_CONFIRMATION, f.model.state.value.status)
        f.model.reconcile()
        assertEquals(PlanSaveStatus.CONFIRMED, f.model.state.value.status)
        assertEquals(1, f.begins)
    }
}
