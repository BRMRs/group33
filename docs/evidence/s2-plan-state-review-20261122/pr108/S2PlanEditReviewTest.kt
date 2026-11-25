package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Review-only counterexamples; the author source/tests stay unchanged. */
@OptIn(ExperimentalCoroutinesApi::class)
class S2PlanEditReviewTest {
    private val scope = RepositoryScope("review", "installation", "caregiver")
    private val command = CommandContext("00000000-0000-4000-8000-000000000001", scope,
        OnlineCommandKind.UPDATE_PLAN, "plan")
    private val plan = ReminderPlanView("plan", "elder", "med", 3, listOf("08:00"),
        "2026-11-01", "Asia/Shanghai", true, 1, 1)
    private val medication = MedicationView("med", "elder", "Fictional", "one", true, photoId = "photo")
    private val input = UpdateReminderPlanRequest(3,
        ReminderPlanDraft(listOf("09:00", "18:00"), "2026-11-04", "Asia/Shanghai"),
        TimeZoneAcknowledgement("Asia/Shanghai", false), MedicationText("Fictional", "one"))

    private inner class Plans : ReminderPlanRepository {
        val writes = mutableListOf<UpdateReminderPlanRequest>()
        var onUpdateEntry: (() -> Unit)? = null
        var gate: CompletableDeferred<Unit>? = null
        var result: OnlineCommandResult<UpdateReminderPlanResponse> = OnlineCommandResult.AwaitingConfirmation(command.operationId)
        override fun observePlans(elderId: String): Flow<Cached<List<ReminderPlanView>>> = error("Unexpected read")
        override fun observeMedications(elderId: String): Flow<Cached<List<MedicationView>>> = error("Unexpected read")
        override fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>> = error("Unexpected preview")
        override suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse> = error("Unexpected confirmation")
        override suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView> = error("Unexpected deactivation")
        override suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse> {
            assertEquals("plan",planId); assertEquals(this@S2PlanEditReviewTest.command,command)
            onUpdateEntry?.invoke()
            writes += draft
            gate?.await()
            return result
        }
    }
    private inner class Journal : OnlineCommandRepository {
        var resumes = 0
        var cancel = false
        var outcome: Outcome<OnlineCommandRecord> = Outcome.Success(record())
        override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> = error("Host owns begin")
        override fun observeCommands() = emptyFlow<List<OnlineCommandRecord>>()
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> = error("No implicit rejected replay")
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
            assertEquals(command.operationId,operationId);resumes++
            if(cancel) throw CancellationException("Fictional recovery interruption")
            return outcome
        }
    }
    private fun record() = OnlineCommandRecord(command.operationId,scope,OnlineCommandKind.UPDATE_PLAN,"plan",
        OnlineCommandState.CONFIRMED,CommandResultRef(CommandResultKind.REMINDER_PLAN,"plan"),CacheState.APPLIED,null)
    private fun operation(plans: Plans, journal: Journal, draft: PlanEditDraft = PlanEditDraft.open(plan,input), restored: OnlineCommandRecord? = null) =
        PlanEditOperation(draft,command,scope,plans,journal,restored)
    private fun confirmed() = OnlineCommandResult.Confirmed(command.operationId,
        UpdateReminderPlanResponse(medication,plan.copy(revision = 4)),CacheState.REFRESH_REQUIRED)

    @Test fun `caller mutation does not alter the already captured edit intent`() = runTest {
        val times=mutableListOf("09:00","18:00")
        val source=input.copy(plan=input.plan.copy(localTimes=times))
        val plans=Plans();val op=operation(plans,Journal(),PlanEditDraft.open(plan,source))
        times[0]="22:00";times.add("23:00")
        op.submit()
        assertEquals(input,plans.writes.single())
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION,op.state.value.status)
    }

    @Test fun `duplicate submit while update is suspended does not dispatch twice`() = runTest {
        val plans=Plans().also { it.gate=CompletableDeferred() };val op=operation(plans,Journal())
        val first=launch { op.submit() };runCurrent()
        assertTrue(op.state.value.busy);op.submit()
        assertEquals(1,plans.writes.size)
        plans.gate!!.complete(Unit);first.join()
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION,op.state.value.status)
        assertFalse(op.state.value.busy)
    }

    @Test fun `close after an actual dispatch discards a late confirmation without replay`() = runTest {
        val plans=Plans().also { it.gate=CompletableDeferred();it.result=confirmed() }
        val journal=Journal();val op=operation(plans,journal)
        val first=launch { op.submit() };runCurrent();assertEquals(1,plans.writes.size)
        op.close();plans.gate!!.complete(Unit);first.join()
        op.submit();op.resume()
        assertEquals(PlanEditOperationState(),op.state.value)
        assertEquals(1,plans.writes.size);assertEquals(0,journal.resumes)
    }

    @Test fun `foreign recovery identities keep the original command unknown`() = runTest {
        val malformed=listOf(
            record().copy(operationId="00000000-0000-4000-8000-000000000002"),
            record().copy(commandKind=OnlineCommandKind.DEACTIVATE_PLAN),
            record().copy(targetId="other-plan"),
            record().copy(resultRef=CommandResultRef(CommandResultKind.MEDICATION,"med")))
        for(r in malformed) {
            val plans=Plans();val journal=Journal().also { it.outcome=Outcome.Success(r) }
            val op=operation(plans,journal);op.submit();op.resume()
            assertEquals(OnlineCommandState.AWAITING_CONFIRMATION,op.state.value.status)
            assertEquals(RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE),op.state.value.error)
            op.submit();assertEquals(1,plans.writes.size)
        }
    }

    @Test fun `cancellation during recovery preserves unknown identity and allows later recovery`() = runTest {
        val plans=Plans();val journal=Journal();val op=operation(plans,journal);op.submit()
        journal.cancel=true
        try { op.resume();fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertFalse(op.state.value.busy)
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION,op.state.value.status)
        journal.cancel=false;op.resume()
        assertEquals(OnlineCommandState.CONFIRMED,op.state.value.status)
        assertEquals(1,plans.writes.size)
    }

    @Test fun `restored applied confirmation neither dispatches nor repeats recovery`() = runTest {
        val plans=Plans();val journal=Journal();val op=operation(plans,journal,restored=record())
        op.submit();op.resume()
        assertTrue(plans.writes.isEmpty());assertEquals(0,journal.resumes)
        assertEquals(OnlineCommandState.CONFIRMED,op.state.value.status)
        assertEquals(CacheState.APPLIED,op.state.value.cache)
    }

    @Test fun `adopting a reviewed revision does not retry the old rejected operation`() = runTest {
        val plans=Plans().also { it.result=OnlineCommandResult.Rejected(command.operationId,
            RepositoryError.Remote(ApiError("PLAN_VERSION_CONFLICT","Fictional conflict",false,"review"))) }
        val op=operation(plans,Journal());op.submit()
        val next=op.conflictedDraft()!!.offerLatest(plan.copy(revision=4)).keepInputAgainstReviewedRevision()
        assertEquals(input.copy(expectedRevision=4),next.input)
        op.submit();op.resume();assertEquals(1,plans.writes.size)
        assertEquals(3L,op.conflictedDraft()!!.input.expectedRevision)
    }

    @Test fun `temporary cache recovery failure cannot make a confirmed write retryable`() = runTest {
        val plans=Plans().also { it.result=confirmed() };val journal=Journal();val op=operation(plans,journal)
        op.submit();journal.outcome=Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE));op.resume()
        assertEquals(OnlineCommandState.CONFIRMED,op.state.value.status)
        assertEquals(CacheState.REFRESH_REQUIRED,op.state.value.cache)
        op.submit();assertEquals(1,plans.writes.size)
        journal.outcome=Outcome.Success(record());op.resume()
        assertEquals(CacheState.APPLIED,op.state.value.cache)
    }

    @Test fun `a response for another operation is not accepted as confirmation`() = runTest {
        val plans=Plans().also { it.result=OnlineCommandResult.Confirmed(
            "00000000-0000-4000-8000-000000000002",UpdateReminderPlanResponse(medication,plan),CacheState.APPLIED) }
        val op=operation(plans,Journal());op.submit()
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION,op.state.value.status)
        assertEquals(RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE),op.state.value.error)
        op.submit();assertEquals(1,plans.writes.size)
    }

    @Test fun `synchronous close from busy observer prevents an unstarted write`() = runTest {
        val plans=Plans();val op=operation(plans,Journal())
        val trace=mutableListOf<String>();plans.onUpdateEntry={ trace += "update-entry" }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler),start=CoroutineStart.UNDISPATCHED) {
            op.state.collect { if(it.busy) { op.close();trace += "close-returned" } }
        }
        op.submit()
        println("busy observer trace=$trace; writes=${plans.writes.size}; state=${op.state.value}")
        assertEquals("close-returned",trace.first())
        assertTrue("close ran before dispatch, so update must not be called",plans.writes.isEmpty())
        assertEquals(PlanEditOperationState(),op.state.value)
    }

    @Test fun `synchronous close from awaiting observer prevents an unstarted write`() = runTest {
        val plans=Plans();val op=operation(plans,Journal())
        val trace=mutableListOf<String>();plans.onUpdateEntry={ trace += "update-entry" }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler),start=CoroutineStart.UNDISPATCHED) {
            op.state.collect { if(it.status==OnlineCommandState.AWAITING_CONFIRMATION) { op.close();trace += "close-returned" } }
        }
        op.submit()
        println("awaiting observer trace=$trace; writes=${plans.writes.size}; state=${op.state.value}")
        assertEquals("close-returned",trace.first())
        assertTrue("close completed before Repository.update entry",plans.writes.isEmpty())
        assertEquals(PlanEditOperationState(),op.state.value)
    }
}
