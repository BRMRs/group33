package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanDeactivationTest {
    private val scope = RepositoryScope("test", "install", "user")
    private val command = CommandContext("00000000-0000-0000-0000-000000000001", scope, OnlineCommandKind.DEACTIVATE_PLAN, "p")
    private val plan = ReminderPlanView("p", "e", "m", 3, listOf("08:00"), "2026-11-05", "Asia/Shanghai", true, 1, 1)
    private var writes = 0
    private var resumes = 0
    private var failure: RuntimeException? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var result: OnlineCommandResult<ReminderPlanView> = OnlineCommandResult.AwaitingConfirmation(command.operationId)
    private var record = OnlineCommandRecord(command.operationId, scope, OnlineCommandKind.DEACTIVATE_PLAN, "p",
        OnlineCommandState.CONFIRMED, CommandResultRef(CommandResultKind.REMINDER_PLAN, "p"), CacheState.REFRESH_REQUIRED, null)
    private val plans = Proxy.newProxyInstance(ReminderPlanRepository::class.java.classLoader,
        arrayOf(ReminderPlanRepository::class.java)) { _, method, args ->
        check(method.name == "deactivate") { "No other plan operation allowed" }
        assertEquals("p", args[0]); assertEquals(3L, args[1]); assertEquals(command, args[2])
        writes++; failure?.let { throw it }; result
    } as ReminderPlanRepository
    private val journal = object : OnlineCommandRepository {
        override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> = error("Host begins explicit intent")
        override fun observeCommands() = emptyFlow<List<OnlineCommandRecord>>()
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
            assertEquals(command.operationId, operationId); resumes++; gate?.await(); return Outcome.Success(record)
        }
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> = error("No automatic rejected retry")
    }
    private fun operation(restored: OnlineCommandRecord? = null) = PlanDeactivation(plan, command, scope, plans, journal, restored)

    @Test fun `explicit action is required and unknown double tap never dispatches twice`() = runTest {
        val op = operation(); assertEquals(0, writes)
        op.deactivate(); op.deactivate(); assertEquals(1, writes)
        assertTrue(plan.active); assertEquals(3L, plan.revision)
        op.resume(); assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status)
        assertEquals(CacheState.REFRESH_REQUIRED, op.state.value.cache)
        record = record.copy(cacheState = CacheState.APPLIED); op.resume(); op.resume(); assertEquals(2, resumes)
    }
    @Test fun `not submitted retry retains original revision and command`() = runTest {
        result = OnlineCommandResult.NotSubmitted(command.operationId, RepositoryError.Local(LocalFailureCode.OFFLINE))
        val op = operation(); op.deactivate(); op.resume(); assertEquals(0, resumes)
        op.deactivate(); assertEquals(2, writes)
    }
    @Test fun `version rejection does not auto replay or replace original plan`() = runTest {
        result = OnlineCommandResult.Rejected(command.operationId, RepositoryError.Remote(ApiError("PLAN_VERSION_CONFLICT", "conflict", false, "test")))
        val op = operation(); op.deactivate(); op.deactivate(); op.resume()
        assertEquals(1, writes); assertEquals(0, resumes); assertTrue(plan.active)
        assertEquals(OnlineCommandState.REJECTED, op.state.value.status)
    }
    @Test fun `both synchronous close notifications prevent IO`() = runTest {
        for (onBusy in listOf(true, false)) {
            val op = operation()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                op.state.collect { if (if (onBusy) it.busy else it.status == OnlineCommandState.AWAITING_CONFIRMATION) op.close() }
            }
            op.deactivate(); assertEquals(0, writes); assertEquals(PlanDeactivationState(), op.state.value)
            observer.cancel()
        }
    }
    @Test fun `dispatch failure stays unknown and recovery uses original identity`() = runTest {
        failure = IllegalStateException("lost response")
        val op = operation(); op.deactivate(); op.deactivate(); assertEquals(1, writes)
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        op.resume(); assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status)
    }
    @Test fun `close ignores late recovery and concurrent resume`() = runTest {
        val op = operation(); op.deactivate(); gate = CompletableDeferred()
        val pending = launch { op.resume() }; runCurrent(); op.resume(); assertEquals(1, resumes)
        op.close(); gate!!.complete(Unit); pending.join()
        assertEquals(PlanDeactivationState(), op.state.value)
    }
    @Test fun `reconstruction cannot replay any supplied payload`() = runTest {
        for (status in listOf(OnlineCommandState.NOT_SUBMITTED, OnlineCommandState.REJECTED, OnlineCommandState.AWAITING_CONFIRMATION)) {
            val op = operation(record.copy(state = status, cacheState = null))
            op.deactivate(); assertEquals(0, writes)
        }
    }
    @Test fun `foreign receipts and confirmed downgrade cannot overwrite current state`() = runTest {
        val op = operation(); op.deactivate()
        record = record.copy(scope = scope.copy(userId = "other")); op.resume()
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        record = record.copy(scope = scope); op.resume()
        record = record.copy(state = OnlineCommandState.REJECTED); op.resume()
        assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status)
    }
    @Test fun `inactive plan or wrong command scope fails before IO`() {
        assertThrows(IllegalArgumentException::class.java) { PlanDeactivation(plan.copy(active = false), command, scope, plans, journal) }
        assertThrows(IllegalArgumentException::class.java) { PlanDeactivation(plan, command.copy(commandKind = OnlineCommandKind.UPDATE_PLAN), scope, plans, journal) }
        assertThrows(IllegalArgumentException::class.java) { PlanDeactivation(plan, command, scope.copy(userId = "other"), plans, journal) }
        assertEquals(0, writes)
    }
}
