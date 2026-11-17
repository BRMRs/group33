package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.group33.shared.model.ApiError
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanEditOperationTest {
    private val scope = RepositoryScope("test", "install", "user")
    private val command = CommandContext("00000000-0000-0000-0000-000000000001", scope, OnlineCommandKind.UPDATE_PLAN, "p")
    private val plan = ReminderPlanView("p", "e", "m", 1, listOf("08:00"), "2026-11-03", "Asia/Shanghai", true, 1, 1)
    private val draft = PlanEditDraft.open(plan, UpdateReminderPlanRequest(1,
        ReminderPlanDraft(listOf("09:00"), "2026-11-03", "Asia/Shanghai"), TimeZoneAcknowledgement("Asia/Shanghai", false)))
    private var writes = 0
    private var dispatchFailure: RuntimeException? = null
    private var resumes = 0
    private var resumeGate: CompletableDeferred<Unit>? = null
    private var result: OnlineCommandResult<UpdateReminderPlanResponse> = OnlineCommandResult.AwaitingConfirmation(command.operationId)
    private var record = OnlineCommandRecord(command.operationId, scope, OnlineCommandKind.UPDATE_PLAN, "p",
        OnlineCommandState.CONFIRMED, null, CacheState.REFRESH_REQUIRED, null)
    // A strict double rejects any method other than update; suspend update returns synchronously.
    private val plans = Proxy.newProxyInstance(ReminderPlanRepository::class.java.classLoader,
        arrayOf(ReminderPlanRepository::class.java)) { _, method, args ->
        check(method.name == "update")
        assertEquals("p", args[0]); assertEquals(draft.input, args[1]); assertEquals(command, args[2])
        writes++
        dispatchFailure?.let { throw it }
        result
    } as ReminderPlanRepository
    private val journal = object : OnlineCommandRepository {
        override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> = error("Host owns begin")
        override fun observeCommands() = emptyFlow<List<OnlineCommandRecord>>()
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
            assertEquals(command.operationId, operationId); resumes++
            resumeGate?.await()
            return Outcome.Success(record)
        }
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> = error("No automatic rejected retry")
    }
    private fun operation() = PlanEditOperation(draft, command, scope, plans, journal)
    @Test fun `synchronous busy listener close prevents dispatch`() = runTest {
        val op = operation()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            op.state.collect { if (it.busy) op.close() }
        }
        op.submit()
        assertEquals(0, writes); assertEquals(PlanEditOperationState(), op.state.value)
    }
    @Test fun `synchronous awaiting listener close prevents dispatch`() = runTest {
        val op = operation()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            op.state.collect { if (it.status == OnlineCommandState.AWAITING_CONFIRMATION) op.close() }
        }
        op.submit()
        assertEquals(0, writes); assertEquals(PlanEditOperationState(), op.state.value)
    }
    @Test fun `unknown duplicate submit never writes twice and recovery preserves identity`() = runTest {
        val op = operation(); op.submit(); op.submit()
        assertEquals(1, writes); op.resume()
        assertEquals(1, resumes); assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status)
        assertEquals(CacheState.REFRESH_REQUIRED, op.state.value.cache)
        record = record.copy(cacheState = CacheState.APPLIED); op.resume()
        assertEquals(CacheState.APPLIED, op.state.value.cache); op.resume(); assertEquals(2, resumes)
    }
    @Test fun `not submitted retains original payload for explicit retry`() = runTest {
        result = OnlineCommandResult.NotSubmitted(command.operationId, RepositoryError.Local(LocalFailureCode.OFFLINE))
        val op = operation(); op.submit(); op.resume(); assertEquals(0, resumes)
        result = OnlineCommandResult.AwaitingConfirmation(command.operationId); op.submit(); assertEquals(2, writes)
    }
    @Test fun `foreign recovery and confirmed downgrade are rejected`() = runTest {
        val op = operation(); op.submit()
        record = record.copy(scope = scope.copy(userId = "other")); op.resume()
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        record = record.copy(scope = scope); op.resume()
        record = record.copy(state = OnlineCommandState.REJECTED); op.resume()
        assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status)
    }
    @Test fun `closed consumer cannot dispatch or retain private operation state`() = runTest {
        val op = operation(); op.submit(); op.close(); op.submit(); op.resume()
        assertEquals(1, writes); assertEquals(0, resumes); assertEquals(PlanEditOperationState(), op.state.value)
    }
    @Test fun `wrong command scope is refused before IO`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanEditOperation(draft, command, scope.copy(userId = "other"), plans, journal)
        }
        assertEquals(0, writes)
    }
    @Test fun `dispatch exception remains unknown and cannot cause a second write`() = runTest {
        dispatchFailure = IllegalStateException("lost response")
        val op = operation(); op.submit(); op.submit()
        assertEquals(1, writes)
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        assertFalse(op.state.value.busy)
        op.resume(); assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status)
    }
    @Test fun `dispatch cancellation propagates while keeping recoverable unknown state`() = runTest {
        dispatchFailure = kotlinx.coroutines.CancellationException("cancelled")
        val op = operation()
        try { op.submit(); fail("Cancellation must propagate") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        assertFalse(op.state.value.busy)
        dispatchFailure = null; op.submit(); assertEquals(1, writes)
    }
    @Test fun `only definitive version rejection opens preserved draft and never auto retries`() = runTest {
        val op = operation(); assertNull(op.conflictedDraft())
        result = OnlineCommandResult.Rejected(command.operationId,
            RepositoryError.Remote(ApiError("PLAN_VERSION_CONFLICT", "conflict", false, "test")))
        op.submit(); op.submit(); op.resume()
        assertEquals(1, writes); assertEquals(0, resumes)
        assertEquals(draft.input, op.conflictedDraft()?.input)
        assertTrue(op.conflictedDraft()!!.conflict)
    }
    @Test fun `restored unknown command resumes but cannot submit reconstructed payload`() = runTest {
        val op = PlanEditOperation(draft, command, scope, plans, journal,
            record.copy(state = OnlineCommandState.AWAITING_CONFIRMATION, cacheState = null))
        op.submit(); assertEquals(0, writes)
        op.resume(); assertEquals(1, resumes)
        assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status)
    }
    @Test fun `restored not submitted command is not replayed`() = runTest {
        val op = PlanEditOperation(draft, command, scope, plans, journal,
            record.copy(state = OnlineCommandState.NOT_SUBMITTED, cacheState = null))
        op.submit(); op.resume(); assertEquals(0, writes); assertEquals(0, resumes)
    }
    @Test fun `close during recovery discards late receipt and concurrent resume is single flight`() = runTest {
        val op = operation(); op.submit()
        resumeGate = CompletableDeferred()
        val pending = launch { op.resume() }; runCurrent()
        op.resume(); assertEquals(1, resumes)
        op.close(); resumeGate!!.complete(Unit); pending.join()
        assertEquals(PlanEditOperationState(), op.state.value)
        assertNull(op.conflictedDraft())
    }
    @Test fun `restoration rejects wrong target and malformed confirmed receipt`() {
        for (bad in listOf(record.copy(targetId = "other"), record.copy(cacheState = null),
            record.copy(resultRef = CommandResultRef(CommandResultKind.REMINDER_PLAN, "other")))) {
            assertThrows(IllegalArgumentException::class.java) {
                PlanEditOperation(draft, command, scope, plans, journal, bad)
            }
        }
    }
}
