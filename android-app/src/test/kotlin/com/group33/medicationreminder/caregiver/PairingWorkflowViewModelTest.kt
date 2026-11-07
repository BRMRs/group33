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
class PairingWorkflowViewModelTest {
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
        var gate: CompletableDeferred<Unit>? = null
        var result: OnlineCommandResult<PairingRequestView> = OnlineCommandResult.Confirmed(operation, request(), CacheState.APPLIED)
        var resumeResult: Outcome<OnlineCommandRecord> = Outcome.Success(record(OnlineCommandState.CONFIRMED, CacheState.APPLIED))
        var commandScope = scope
        var observationFails = false
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
        override suspend fun refresh(): Outcome<Unit> { refreshed++; return Outcome.Success(Unit) }
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
            cancelled += requestId; return OnlineCommandResult.Confirmed(operation, Unit, CacheState.APPLIED)
        }
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
            resumed += operationId
            return resumeResult.also { if (it is Outcome.Success) journal.value = listOf(it.value) }
        }
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> { retries++; error("must never auto-retry") }
        override suspend fun issueCode(command: CommandContext): OnlineCommandResult<PairingCodeView> = error("unused")
        override suspend fun cancelCode(codeId: String, command: CommandContext): OnlineCommandResult<Unit> = error("unused")
        override suspend fun respond(requestId: String, accept: Boolean, command: CommandContext): OnlineCommandResult<PairingDecisionResult> = error("unused")
        override suspend fun revoke(relationshipId: String, command: CommandContext): OnlineCommandResult<CareRelationshipView> = error("unused")
    }

    @Test fun `eight ASCII digits preserve leading zeros and do not fabricate cache rows`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.model.setCode("00123456"); f.model.submitCode(); advanceUntilIdle()
        assertEquals("00123456", f.submitted.single().first)
        assertEquals(scope, f.submitted.single().second.scope)
        assertEquals("", f.model.state.value.code)
        assertTrue(f.model.state.value.requests.isEmpty())
        f.requests.value = cached(listOf(request())); runCurrent()
        assertEquals(PairingRequestStatus.PENDING, f.model.state.value.requests.single().status)
    }

    @Test fun `invalid or incomplete code never begins a command`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.model.setCode("１２３４５６７８"); f.model.submitCode(); runCurrent()
        assertEquals(0, f.begins)
        assertEquals(PairingNotice.InvalidCode, f.model.state.value.notice)
        f.model.setCode("1234567"); f.model.submitCode(); runCurrent()
        assertEquals(0, f.begins)
    }

    @Test fun `unknown result blocks duplicate writes and refresh resumes original identity`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.result = OnlineCommandResult.AwaitingConfirmation(operation)
        f.model.setCode("00123456"); f.model.submitCode(); advanceUntilIdle()
        f.model.submitCode(); runCurrent()
        assertEquals(1, f.begins); assertTrue(f.model.state.value.needsRecovery)
        f.model.refresh(); advanceUntilIdle()
        assertEquals(listOf(operation), f.resumed); assertEquals(1, f.begins)
        assertFalse(f.model.state.value.needsRecovery)
        assertEquals(0, f.retries)
    }

    @Test fun `restored unresolved journal blocks submission before a new operation`() = runTest(dispatcher) {
        val f = Fixture(); f.journal.value = listOf(record()); runCurrent()
        f.model.setCode("12345678"); f.model.submitCode(); runCurrent()
        assertEquals(0, f.begins); assertTrue(f.model.state.value.needsRecovery)
    }

    @Test fun `failed recovery retains unknown state and never allocates a second identity`() = runTest(dispatcher) {
        val f = Fixture(); f.journal.value = listOf(record()); runCurrent()
        f.resumeResult = Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE))
        f.model.refresh(); advanceUntilIdle()
        assertTrue(f.model.state.value.needsRecovery)
        assertEquals(PairingNotice.ResultUnknown, f.model.state.value.notice)
        assertEquals(listOf(operation), f.resumed); assertEquals(0, f.begins)
    }

    @Test fun `recovery reports a definitive failure instead of a successful refresh`() = runTest(dispatcher) {
        val f = Fixture(); f.journal.value = listOf(record()); runCurrent()
        val error = com.group33.shared.model.ApiError("PAIRING_CODE_INVALID", "Invalid code", false, "request-id")
        f.resumeResult = Outcome.Success(record(OnlineCommandState.REJECTED).copy(
            error = RepositoryError.Remote(error)))
        f.model.refresh(); advanceUntilIdle()
        assertFalse(f.model.state.value.needsRecovery)
        assertEquals(PairingNotice.Rejected(error), f.model.state.value.notice)
        assertEquals(0, f.begins); assertEquals(0, f.retries)
    }

    @Test fun `elder session cannot use caregiver form`() = runTest(dispatcher) {
        val f = Fixture(); f.session.value = SessionView(AuthReadiness.READY, user.copy(role = UserRole.ELDER)); runCurrent()
        f.model.setCode("12345678"); f.model.submitCode(); advanceUntilIdle()
        assertEquals(0, f.begins); assertFalse(f.model.state.value.ready)
    }

    @Test fun `observation failure blocks writes until explicit refresh restores observation`() = runTest(dispatcher) {
        val f = Fixture(); f.observationFails = true; runCurrent()
        assertFalse(f.model.state.value.ready)
        f.model.setCode("12345678"); f.model.submitCode(); runCurrent()
        assertEquals(0, f.begins)
        f.observationFails = false; f.model.refresh(); advanceUntilIdle()
        assertTrue(f.model.state.value.ready); assertTrue(f.model.state.value.requestsKnown)
        assertEquals(0, f.begins)
    }

    @Test fun `rejected and not submitted operations are never automatically resumed or retried`() = runTest(dispatcher) {
        val f = Fixture(); f.journal.value = listOf(record(OnlineCommandState.REJECTED),
            record(OnlineCommandState.NOT_SUBMITTED).copy(operationId = "other")); runCurrent()
        f.model.refresh(); advanceUntilIdle()
        assertTrue(f.resumed.isEmpty()); assertEquals(0, f.retries); assertEquals(0, f.begins)
    }

    @Test fun `confirmed cache reconciliation still blocks a duplicate request`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.result = OnlineCommandResult.Confirmed(operation, request(), CacheState.REFRESH_REQUIRED)
        f.model.setCode("12345678"); f.model.submitCode(); advanceUntilIdle()
        assertTrue(f.model.state.value.needsRecovery)
        assertTrue(f.model.state.value.requests.isEmpty())
        f.model.submitCode(); runCurrent(); assertEquals(1, f.begins)
    }

    @Test fun `logout clears input and rows and ignores late noncancellable response`() = runTest(dispatcher) {
        val f = Fixture(); f.requests.value = cached(listOf(request())); runCurrent()
        f.gate = CompletableDeferred(); f.model.setCode("12345678"); f.model.submitCode(); runCurrent()
        f.trusted.value = null; f.session.value = SessionView(AuthReadiness.SIGNED_OUT, null); runCurrent()
        assertEquals("", f.model.state.value.code); assertTrue(f.model.state.value.requests.isEmpty())
        f.gate!!.complete(Unit); advanceUntilIdle()
        assertFalse(f.model.state.value.ready)
        assertEquals(PairingNotice.SessionUnavailable, f.model.state.value.notice)
    }

    @Test fun `same account new generation clears form and ignores old response`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.gate = CompletableDeferred(); f.model.setCode("12345678"); f.model.submitCode(); runCurrent()
        f.trusted.value = TrustedSessionContext(scope, 2); runCurrent()
        f.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("", f.model.state.value.code); assertNull(f.model.state.value.notice)
    }

    @Test fun `unknown cache is not an empty history and foreign rows are filtered`() = runTest(dispatcher) {
        val f = Fixture(); f.requests.value = Cached(emptyList(), false, null, true, AccessState.UNKNOWN); runCurrent()
        assertFalse(f.model.state.value.requestsKnown)
        f.requests.value = cached(listOf(request(owner = user.copy(id = "other")), request(PairingRequestStatus.EXPIRED))); runCurrent()
        assertEquals(PairingRequestStatus.EXPIRED, f.model.state.value.requests.single().status)
    }

    @Test fun `only observed pending requests can be cancelled`() = runTest(dispatcher) {
        val f = Fixture(); f.requests.value = cached(listOf(request(PairingRequestStatus.ACCEPTED))); runCurrent()
        f.model.cancel("request"); runCurrent(); assertEquals(0, f.begins)
        f.requests.value = cached(listOf(request())); runCurrent()
        f.model.cancel("request"); advanceUntilIdle()
        assertEquals(listOf("request"), f.cancelled)
        assertEquals(PairingRequestStatus.PENDING, f.model.state.value.requests.single().status)
    }

    @Test fun `wrong scope from command provider cannot submit`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.commandScope = scope.copy(userId = "other")
        f.model.setCode("12345678"); f.model.submitCode(); advanceUntilIdle()
        assertTrue(f.submitted.isEmpty())
        assertEquals(PairingNotice.NotSubmitted(LocalFailureCode.SESSION_NOT_READY), f.model.state.value.notice)
    }
}
