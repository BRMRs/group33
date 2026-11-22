package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** External S2 contract probes. No production host, HTTP, Room or device claims. */
@OptIn(ExperimentalCoroutinesApi::class)
class S2PlanDeactivationReviewTest {
    private val scope = RepositoryScope("review", "installation", "caregiver")
    private val command = CommandContext("00000000-0000-4000-8000-000000000001", scope,
        OnlineCommandKind.DEACTIVATE_PLAN, "plan")
    private val plan = ReminderPlanView("plan", "elder", "med", 3, listOf("08:00"),
        "2026-11-01", "Asia/Shanghai", true, 1, 1)
    private inner class Plans : ReminderPlanRepository {
        var writes = 0
        var gate: CompletableDeferred<Unit>? = null
        var cancel = false
        var result: OnlineCommandResult<ReminderPlanView> = OnlineCommandResult.AwaitingConfirmation(command.operationId)
        override fun observePlans(elderId: String): Flow<Cached<List<ReminderPlanView>>> = error("Unexpected read")
        override fun observeMedications(elderId: String): Flow<Cached<List<MedicationView>>> = error("Unexpected read")
        override fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>> = error("Unexpected preview")
        override suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse> = error("Unexpected confirmation")
        override suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse> = error("Unexpected edit")
        override suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView> {
            assertEquals("plan", planId); assertEquals(3L, expectedRevision)
            assertEquals(this@S2PlanDeactivationReviewTest.command, command)
            writes++
            if (cancel) throw CancellationException("Fictional dispatch interruption")
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
            assertEquals(command.operationId, operationId); resumes++
            if (cancel) throw CancellationException("Fictional recovery interruption")
            return outcome
        }
    }
    private fun record() = OnlineCommandRecord(command.operationId, scope, OnlineCommandKind.DEACTIVATE_PLAN, "plan",
        OnlineCommandState.CONFIRMED, CommandResultRef(CommandResultKind.REMINDER_PLAN, "plan"), CacheState.APPLIED, null)
    private fun operation(plans: Plans, journal: Journal, restored: OnlineCommandRecord? = null) =
        PlanDeactivation(plan, command, scope, plans, journal, restored)
    private fun confirmed() = OnlineCommandResult.Confirmed(command.operationId,
        plan.copy(active = false, revision = 4), CacheState.REFRESH_REQUIRED)

    @Test fun `a suspended deactivation holds the original revision and dispatches once`() = runTest {
        val plans = Plans().also { it.gate = CompletableDeferred() }; val op = operation(plans, Journal())
        assertEquals(0, plans.writes)
        val pending = launch { op.deactivate() }; runCurrent()
        assertTrue(op.state.value.busy); op.deactivate(); assertEquals(1, plans.writes)
        plans.gate!!.complete(Unit); pending.join(); op.deactivate()
        assertEquals(1, plans.writes); assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        assertTrue(plan.active); assertEquals(3L, plan.revision)
    }
    @Test fun `closing after dispatch discards a late confirmation and prevents further IO`() = runTest {
        val plans = Plans().also { it.gate = CompletableDeferred(); it.result = confirmed() }
        val journal = Journal(); val op = operation(plans, journal)
        val pending = launch { op.deactivate() }; runCurrent(); assertEquals(1, plans.writes)
        op.close(); plans.gate!!.complete(Unit); pending.join(); op.deactivate(); op.resume()
        assertEquals(PlanDeactivationState(), op.state.value)
        assertEquals(1, plans.writes); assertEquals(0, journal.resumes)
    }
    @Test fun `dispatch cancellation propagates and preserves the unknown original operation`() = runTest {
        val plans = Plans().also { it.cancel = true }; val journal = Journal(); val op = operation(plans, journal)
        try { op.deactivate(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertFalse(op.state.value.busy); assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        plans.cancel = false; op.deactivate(); assertEquals(1, plans.writes)
        op.resume(); assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status)
        assertEquals(1, journal.resumes)
    }
    @Test fun `recovery cancellation preserves identity and can recover without another write`() = runTest {
        val plans = Plans(); val journal = Journal(); val op = operation(plans, journal); op.deactivate()
        journal.cancel = true
        try { op.resume(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertFalse(op.state.value.busy); assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        journal.cancel = false; op.resume()
        assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status); assertEquals(1, plans.writes)
    }
    @Test fun `restored applied confirmation neither dispatches nor resumes`() = runTest {
        val plans = Plans(); val journal = Journal(); val op = operation(plans, journal, record())
        op.deactivate(); op.resume()
        assertEquals(0, plans.writes); assertEquals(0, journal.resumes)
        assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status); assertEquals(CacheState.APPLIED, op.state.value.cache)
    }
    @Test fun `foreign receipt fields cannot confirm an unknown operation`() = runTest {
        val invalid = listOf(record().copy(operationId = "00000000-0000-4000-8000-000000000002"),
            record().copy(scope = scope.copy(installationId = "foreign")),
            record().copy(commandKind = OnlineCommandKind.UPDATE_PLAN), record().copy(targetId = "foreign"),
            record().copy(resultRef = CommandResultRef(CommandResultKind.MEDICATION, "med")),
            record().copy(resultRef = CommandResultRef(CommandResultKind.REMINDER_PLAN, "foreign")),
            record().copy(cacheState = null))
        for (receipt in invalid) {
            val plans = Plans(); val journal = Journal().also { it.outcome = Outcome.Success(receipt) }
            val op = operation(plans, journal); op.deactivate(); op.resume(); op.deactivate()
            assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
            assertEquals(RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE), op.state.value.error)
            assertEquals(1, plans.writes)
        }
    }
    @Test fun `a response for another command remains unknown and is not retried`() = runTest {
        val plans = Plans().also { it.result = OnlineCommandResult.Confirmed(
            "00000000-0000-4000-8000-000000000002", plan.copy(active = false), CacheState.APPLIED) }
        val op = operation(plans, Journal()); op.deactivate(); op.deactivate()
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, op.state.value.status)
        assertEquals(RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE), op.state.value.error)
        assertEquals(1, plans.writes)
    }
    @Test fun `cache failure and rejected receipt cannot downgrade a confirmed command`() = runTest {
        val plans = Plans().also { it.result = confirmed() }; val journal = Journal(); val op = operation(plans, journal)
        op.deactivate(); journal.outcome = Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE)); op.resume()
        assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status); assertEquals(CacheState.REFRESH_REQUIRED, op.state.value.cache)
        journal.outcome = Outcome.Success(record().copy(state = OnlineCommandState.REJECTED)); op.resume(); op.deactivate()
        assertEquals(OnlineCommandState.CONFIRMED, op.state.value.status); assertEquals(CacheState.REFRESH_REQUIRED, op.state.value.cache)
        assertEquals(1, plans.writes)
        journal.outcome = Outcome.Success(record()); op.resume(); assertEquals(CacheState.APPLIED, op.state.value.cache)
    }
}
