package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModelStore
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S2PairingReceiptReviewTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val user = User("caregiver", "Caregiver", UserRole.CAREGIVER)
    private val scope = RepositoryScope("test", "installation", user.id)
    private val operation = "b501ee90-4f5c-4bf1-a6b4-dcfc64634aea"
    private fun cached(rows: List<PairingRequestView>) = Cached(rows, true, 1L, false, AccessState.AUTHORIZED)
    private fun request(status: PairingRequestStatus = PairingRequestStatus.PENDING, owner: User = user) =
        PairingRequestView("request", "elder", owner, status, 600_000L)
    private fun record(state: OnlineCommandState = OnlineCommandState.AWAITING_CONFIRMATION,
                       cache: CacheState? = null) = OnlineCommandRecord(operation, scope,
        OnlineCommandKind.REQUEST_PAIRING, null, state, null, cache, null)

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private inner class Fixture : AuthRepository, PairingRepository, OnlineCommandRepository {
        val session = MutableStateFlow(SessionView(AuthReadiness.READY, user))
        val trusted = MutableStateFlow<TrustedSessionContext?>(TrustedSessionContext(scope, 1))
        val requests = MutableStateFlow(cached(emptyList()))
        val journal = MutableStateFlow<List<OnlineCommandRecord>>(emptyList())
        var begins = 0
        val submitted = mutableListOf<Pair<String, CommandContext>>()
        val cancelled = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        var retries = 0
        var refreshed = 0
        var refreshResult: Outcome<Unit> = Outcome.Success(Unit)
        var cancelResult: OnlineCommandResult<Unit> = OnlineCommandResult.Confirmed(operation, Unit, CacheState.APPLIED)
        var gate: CompletableDeferred<Unit>? = null
        var result: OnlineCommandResult<PairingRequestView> = OnlineCommandResult.Confirmed(operation, request(), CacheState.APPLIED)
        var resumeResult: Outcome<OnlineCommandRecord> = Outcome.Success(record(OnlineCommandState.CONFIRMED, CacheState.APPLIED))
        var commandScope = scope
        var observationFails = false
        var resumeGate: CompletableDeferred<Unit>? = null
        val model = PairingWorkflowViewModel(this, this, this, trusted).also {
            stores += ViewModelStore().apply { put("pairing", it) }
        }
        override fun observeSession() = session
        override suspend fun login(role: UserRole, displayName: String): Outcome<UserView> = error("unused")
        override fun observeRequests() = flow {
            if (observationFails) error("local observation unavailable")
            emitAll(requests)
        }
        override fun observeRelationships() = flowOf(Cached(emptyList<CareRelationshipView>(), true, 1L, false, AccessState.AUTHORIZED))
        override suspend fun refresh(): Outcome<Unit> { refreshed++; return refreshResult }
        override fun observeCommands() = journal
        override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> {
            begins++; return Outcome.Success(CommandContext(operation, commandScope, kind, targetId))
        }
        override suspend fun requestPairing(code: String, command: CommandContext): OnlineCommandResult<PairingRequestView> {
            submitted += code to command
            gate?.let { withContext(NonCancellable) { it.await() } }
            return result
        }
        override suspend fun cancelRequest(requestId: String, command: CommandContext): OnlineCommandResult<Unit> {
            cancelled += requestId; return cancelResult
        }
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
            resumed += operationId
            resumeGate?.let { withContext(NonCancellable) { it.await() } }
            return resumeResult
        }
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> { retries++; error("must never auto-retry") }
        override suspend fun issueCode(command: CommandContext): OnlineCommandResult<PairingCodeView> = error("unused")
        override suspend fun cancelCode(codeId: String, command: CommandContext): OnlineCommandResult<Unit> = error("unused")
        override suspend fun respond(requestId: String, accept: Boolean, command: CommandContext): OnlineCommandResult<PairingDecisionResult> = error("unused")
        override suspend fun revoke(relationshipId: String, command: CommandContext): OnlineCommandResult<CareRelationshipView> = error("unused")
    }

    @Test fun `a foreign immediate receipt cannot confirm or release the original request`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.result = OnlineCommandResult.Confirmed("00000000-0000-4000-8000-000000000002", request(), CacheState.APPLIED)
        f.model.setCode("00123456"); f.model.submitCode(); advanceUntilIdle()
        assertTrue("foreign receipt must leave original command unknown", f.model.state.value.needsRecovery)
        assertEquals("00123456", f.model.state.value.code)
        assertEquals(PairingNotice.ResultUnknown, f.model.state.value.notice)
        f.model.submitCode(); runCurrent(); assertEquals(1, f.begins)
        f.model.refresh(); advanceUntilIdle(); assertEquals(listOf(operation), f.resumed)
    }
    @Test fun `same scope foreign operation recovery cannot unlock a new write`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.result = OnlineCommandResult.AwaitingConfirmation(operation)
        f.model.setCode("00123456"); f.model.submitCode(); advanceUntilIdle()
        f.resumeResult = Outcome.Success(record(OnlineCommandState.CONFIRMED, CacheState.APPLIED).copy(operationId="00000000-0000-4000-8000-000000000002"))
        f.model.refresh(); advanceUntilIdle()
        assertTrue("wrong operation must not clear original unknown", f.model.state.value.needsRecovery)
        assertEquals("00123456", f.model.state.value.code)
        f.model.submitCode(); runCurrent(); assertEquals(1, f.begins)
    }
    @Test fun `same operation recovery with another command kind cannot unlock a new write`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.result = OnlineCommandResult.AwaitingConfirmation(operation)
        f.model.setCode("00123456"); f.model.submitCode(); advanceUntilIdle()
        f.resumeResult = Outcome.Success(record(OnlineCommandState.CONFIRMED, CacheState.APPLIED).copy(commandKind=OnlineCommandKind.UPDATE_PLAN))
        f.model.refresh(); advanceUntilIdle()
        assertTrue("wrong kind must leave original command unknown", f.model.state.value.needsRecovery)
        f.model.submitCode(); runCurrent(); assertEquals(1, f.begins)
    }
    @Test fun `cancellation recovery for another target cannot unlock another cancellation`() = runTest(dispatcher) {
        val f = Fixture(); f.requests.value=cached(listOf(request())); runCurrent()
        f.cancelResult=OnlineCommandResult.AwaitingConfirmation(operation)
        f.model.cancel("request"); advanceUntilIdle()
        f.resumeResult=Outcome.Success(record(OnlineCommandState.CONFIRMED, CacheState.APPLIED).copy(commandKind=OnlineCommandKind.CANCEL_PAIRING_REQUEST,targetId="foreign-request"))
        f.model.refresh(); advanceUntilIdle()
        assertTrue("wrong cancellation target must leave original command unknown", f.model.state.value.needsRecovery)
        f.model.cancel("request"); runCurrent(); assertEquals(1, f.begins)
    }
    @Test fun `valid exact recovery still confirms the original request without a new write`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.result=OnlineCommandResult.AwaitingConfirmation(operation)
        f.model.setCode("00123456"); f.model.submitCode(); advanceUntilIdle()
        f.model.refresh(); advanceUntilIdle()
        assertEquals(listOf(operation), f.resumed); assertFalse(f.model.state.value.needsRecovery)
        assertEquals("", f.model.state.value.code); assertEquals(1, f.begins)
    }
    @Test fun `malformed result references and missing cache confirmation preserve original recovery`() = runTest(dispatcher) {
        val malformed=listOf(record(OnlineCommandState.CONFIRMED, CacheState.APPLIED).copy(resultRef=CommandResultRef(CommandResultKind.MEDICATION,"med")),
            record(OnlineCommandState.CONFIRMED), record(OnlineCommandState.CONFIRMED, CacheState.APPLIED).copy(targetId="unexpected"))
        for (receipt in malformed) {
            val f=Fixture(); runCurrent(); f.result=OnlineCommandResult.AwaitingConfirmation(operation)
            f.model.setCode("00123456"); f.model.submitCode(); advanceUntilIdle()
            f.resumeResult=Outcome.Success(receipt); f.model.refresh(); advanceUntilIdle()
            assertTrue(f.model.state.value.needsRecovery); assertEquals("00123456", f.model.state.value.code)
            f.model.submitCode(); runCurrent(); assertEquals(1,f.begins)
        }
    }
    @Test fun `restored confirmed cache pending cannot be downgraded by recovery`() = runTest(dispatcher) {
        val f=Fixture(); f.journal.value=listOf(record(OnlineCommandState.CONFIRMED,CacheState.REFRESH_REQUIRED)); runCurrent()
        f.resumeResult=Outcome.Success(record()); f.model.refresh(); advanceUntilIdle()
        assertTrue(f.model.state.value.needsRecovery); assertEquals(PairingNotice.ResultUnknown,f.model.state.value.notice)
        f.model.setCode("00123456"); f.model.submitCode(); runCurrent(); assertEquals(0,f.begins)
    }
    @Test fun `valid cancellation receipt clears only its original recovery`() = runTest(dispatcher) {
        val f=Fixture(); f.requests.value=cached(listOf(request())); runCurrent()
        f.cancelResult=OnlineCommandResult.AwaitingConfirmation(operation); f.model.cancel("request"); advanceUntilIdle()
        f.resumeResult=Outcome.Success(record(OnlineCommandState.CONFIRMED,CacheState.APPLIED).copy(
            commandKind=OnlineCommandKind.CANCEL_PAIRING_REQUEST,targetId="request",resultRef=CommandResultRef(CommandResultKind.PAIRING_REQUEST,"request")))
        f.model.refresh(); advanceUntilIdle()
        assertFalse(f.model.state.value.needsRecovery); assertEquals(1,f.begins); assertEquals(listOf(operation),f.resumed)
    }
    @Test fun `late recovery from a previous generation cannot restore cleared input`() = runTest(dispatcher) {
        val f=Fixture(); runCurrent(); f.result=OnlineCommandResult.AwaitingConfirmation(operation)
        f.model.setCode("00123456"); f.model.submitCode(); advanceUntilIdle()
        f.resumeGate=CompletableDeferred(); f.model.refresh(); runCurrent()
        f.trusted.value=TrustedSessionContext(scope,2); runCurrent()
        f.resumeGate!!.complete(Unit); advanceUntilIdle()
        assertEquals("",f.model.state.value.code); assertNull(f.model.state.value.notice)
        assertFalse(f.model.state.value.needsRecovery); assertEquals(1,f.begins)
    }
}
