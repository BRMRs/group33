package com.group33.medicationreminder.integration

import androidx.lifecycle.ViewModelStore
import com.group33.medicationreminder.R
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
class ChainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val caregiver = User("caregiver", "Caregiver", UserRole.CAREGIVER)
    private val elderA = User("elder-a", "Elder A", UserRole.ELDER)
    private val elderB = User("elder-b", "Elder B", UserRole.ELDER)
    private val scope = RepositoryScope("test", "installation", caregiver.id)
    private fun <T> cached(value: T) = Cached(value, true, 1L, false, AccessState.AUTHORIZED)
    private fun relation(id: String, elder: User) = CareRelationshipView(id, elder.id, caregiver.id, elder, caregiver, CareRelationshipStatus.ACTIVE, 1L)
    private fun photo(elder: User) = MedicationPhotoView("photo-${elder.id}", elder.id, "image/png", 1L)
    private val failure = Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private inner class Fixture(user: User = caregiver) : AuthRepository, PairingRepository, MedicationPhotoRepository, ReminderPlanRepository, OnlineCommandRepository {
        private val scope = this@ChainViewModelTest.scope.copy(userId = user.id)
        val session = MutableStateFlow(SessionView(AuthReadiness.READY, user))
        val trusted = MutableStateFlow<TrustedSessionContext?>(TrustedSessionContext(scope, 1))
        val relationships = MutableStateFlow(cached(listOf(relation("rel-a", elderA), relation("rel-b", elderB))))
        val photos = listOf(elderA, elderB).associate { it.id to MutableStateFlow(cached(listOf(photo(it)))) }
        val plans = MutableStateFlow(cached(emptyList<ReminderPlanView>()))
        val records = MutableStateFlow<List<OnlineCommandRecord>>(emptyList())
        var pairingResult: Outcome<Unit> = Outcome.Success(Unit)
        var signOutResult: Outcome<Unit> = Outcome.Success(Unit)
        var signOutCalls = 0
        var signOutGate: CompletableDeferred<Unit>? = null
        val operationId = "b501ee90-4f5c-4bf1-a6b4-dcfc64634aea"
        var submitted: CommandContext? = null
        var resumedOperationId: String? = null
        var revokeResult: OnlineCommandResult<CareRelationshipView> =
            OnlineCommandResult.NotSubmitted(operationId, RepositoryError.Local(LocalFailureCode.OFFLINE))
        var resumeResult: Outcome<OnlineCommandRecord> = failure
        var pairingCodeRecovery: Outcome<PairingCodeRecovery> = failure
        var refreshGate: CompletableDeferred<Unit>? = null
        val model = ChainViewModel(ChainServices(this, this, this, this, this, MutableStateFlow(scope), trusted, { id ->
            resumedOperationId = id
            pairingCodeRecovery.also { outcome ->
                if (outcome is Outcome.Success) records.value = listOf(outcome.value.record)
            }
        }) {
            signOutCalls++
            trusted.value = null
            session.value = SessionView(AuthReadiness.SIGNED_OUT, null)
            signOutGate?.await()
            signOutResult
        }).also { model -> stores += ViewModelStore().apply { put("chain", model) } }
        override fun observeSession() = session
        override suspend fun login(role: UserRole, displayName: String): Outcome<UserView> = error("Not used")
        override fun observeRelationships() = relationships
        override fun observeRequests() = flowOf(cached(emptyList<PairingRequestView>()))
        override suspend fun refresh() = pairingResult
        override fun observePhotos(elderId: String) = photos.getValue(elderId)
        override fun observePlans(elderId: String) = plans
        override fun observeMedications(elderId: String) = flowOf(cached(emptyList<MedicationView>()))
        override suspend fun refresh(elderId: String): Outcome<SyncSummary> {
            // Model a read whose cancellation cannot complete until its transport returns.
            refreshGate?.let { withContext(NonCancellable) { it.await() } }
            return Outcome.Success(SyncSummary(scope, elderId, SyncCompletion.COMPLETE, 1L, null))
        }
        override fun observeCommands() = records
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
            resumedOperationId = operationId
            return resumeResult
        }
        override suspend fun begin(kind: OnlineCommandKind, targetId: String?) =
            Outcome.Success(CommandContext(operationId, scope, kind, targetId))
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> = error("Not used")
        override suspend fun issueCode(command: CommandContext): OnlineCommandResult<PairingCodeView> = error("Not used")
        override suspend fun cancelCode(codeId: String, command: CommandContext): OnlineCommandResult<Unit> = error("Not used")
        override suspend fun requestPairing(code: String, command: CommandContext): OnlineCommandResult<PairingRequestView> {
            submitted = command
            records.value = listOf(pending().copy(operationId = command.operationId, commandKind = command.commandKind))
            return OnlineCommandResult.AwaitingConfirmation(command.operationId)
        }
        override suspend fun respond(requestId: String, accept: Boolean, command: CommandContext): OnlineCommandResult<PairingDecisionResult> = error("Not used")
        override suspend fun cancelRequest(requestId: String, command: CommandContext): OnlineCommandResult<Unit> = error("Not used")
        override suspend fun revoke(relationshipId: String, command: CommandContext): OnlineCommandResult<CareRelationshipView> {
            assertEquals(relationshipId, command.targetId)
            submitted = command
            return revokeResult
        }
        override suspend fun submit(source: PhotoSource, command: CommandContext): OnlineCommandResult<MedicationPhotoView> = error("Not used")
        override suspend fun loadContent(photoId: String): Outcome<PhotoContent> = failure
        override fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>> = error("Not used")
        override suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse> = error("Not used")
        override suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse> = error("Not used")
        override suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView> = error("Not used")
        fun pending() = OnlineCommandRecord("operation", scope, OnlineCommandKind.CONFIRM_PLAN, elderA.id,
            OnlineCommandState.AWAITING_CONFIRMATION, null, null, null)
    }

    @Test fun `failed durable sign-out stays isolated and exposes retry`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.model.select("rel-a"); runCurrent()
        val clear = CompletableDeferred<Unit>()
        f.signOutGate = clear
        f.signOutResult = Outcome.Failure(RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE))
        f.model.logout(); runCurrent()
        f.model.logout(); runCurrent()
        assertEquals(1, f.signOutCalls)
        assertEquals(SignOutStatus.CLEARING, f.model.state.value.signOut)
        assertNull(f.trusted.value)
        assertTrue(f.model.state.value.photos.isEmpty())
        clear.complete(Unit); runCurrent()
        assertEquals(SignOutStatus.FAILED, f.model.state.value.signOut)
        assertEquals(AuthReadiness.SIGNED_OUT, f.model.state.value.session.readiness)
        f.signOutResult = Outcome.Success(Unit)
        f.model.logout(); runCurrent()
        assertEquals(2, f.signOutCalls)
        assertEquals(SignOutStatus.COMPLETE, f.model.state.value.signOut)
    }

    @Test fun `pairing with no receipt stays pending and recovery reuses the submitted ID`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.model.request("123456"); runCurrent()
        assertEquals(OnlineCommandKind.REQUEST_PAIRING, f.submitted!!.commandKind)
        assertEquals(R.string.chain_pending, f.model.state.value.message)
        f.model.recover(); runCurrent()
        assertEquals(f.submitted!!.operationId, f.resumedOperationId)
        assertEquals(R.string.chain_pending, f.model.state.value.message)
    }

    @Test fun `restored Elder journal exposes recovery and publishes the original code`() = runTest(dispatcher) {
        val f = Fixture(elderA)
        val record = f.pending().copy(operationId = f.operationId, commandKind = OnlineCommandKind.ISSUE_PAIRING_CODE,
            targetId = null)
        f.records.value = listOf(record)
        runCurrent()
        assertEquals(R.string.chain_ready, f.model.state.value.message)
        assertTrue(f.model.state.value.hasPendingPairing)
        val code = PairingCodeView("original-code-id", "01234567", Long.MAX_VALUE)
        f.pairingCodeRecovery = Outcome.Success(PairingCodeRecovery(record.copy(
            state = OnlineCommandState.CONFIRMED, cacheState = CacheState.APPLIED), code))
        f.model.recover(); runCurrent()
        assertEquals(record.operationId, f.resumedOperationId)
        assertEquals(code, f.model.state.value.code)
        assertEquals(R.string.chain_code_ready, f.model.state.value.message)
        assertFalse(f.model.state.value.hasPendingPairing)
    }

    @Test fun `offline revocation replaces the previous refresh success`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.model.select("rel-a"); runCurrent()
        f.model.refresh(); runCurrent()
        assertEquals(R.string.chain_refreshed, f.model.state.value.message)
        f.model.revoke("rel-a"); runCurrent()
        assertEquals("rel-a", f.submitted!!.targetId)
        assertEquals(R.string.chain_not_submitted, f.model.state.value.message)
        f.model.refresh(reportStatus = false); runCurrent()
        assertEquals(R.string.chain_not_submitted, f.model.state.value.message)
    }

    @Test fun `offline photo read has an explicit retry target`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.model.select("rel-a"); runCurrent()
        f.model.viewPhoto(photo(elderA).id); runCurrent()
        assertNull(f.model.state.value.viewed)
        assertEquals(photo(elderA).id, f.model.state.value.failedPhotoId)
        f.model.select("rel-b"); runCurrent()
        assertNull(f.model.state.value.failedPhotoId)
    }

    @Test fun `empty plans require current authorized data`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        f.model.select("rel-a"); runCurrent()
        f.plans.value = Cached(emptyList(), false, null, true, AccessState.AUTHORIZED); runCurrent()
        assertFalse(f.model.state.value.plansKnown)
        f.plans.value = cached(emptyList()); runCurrent()
        assertTrue(f.model.state.value.plansKnown)
        f.plans.value = f.plans.value.copy(isStale = true); runCurrent()
        assertFalse(f.model.state.value.plansKnown)
    }

    @Test fun `late old Elder cache cannot publish during selection cancellation`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        val release = CompletableDeferred<Unit>(); f.refreshGate = release
        try {
            f.model.select("rel-a"); runCurrent()
            assertEquals(listOf(photo(elderA)), f.model.state.value.photos)
            f.model.select("rel-b"); runCurrent()
            assertEquals(elderB.id, (f.model.state.value.context as CaregiverContextState.Active).context.elderId)
            f.photos.getValue(elderA.id).value = cached(listOf(photo(elderA).copy(id = "late-old-photo")))
            runCurrent()
            assertTrue(f.model.state.value.photos.isEmpty())
        } finally { release.complete(Unit); runCurrent() }
        assertEquals(listOf(photo(elderB)), f.model.state.value.photos)
    }

    @Test fun `revocation cannot be undone by a late cache emission`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent()
        val release = CompletableDeferred<Unit>(); f.refreshGate = release
        try {
            f.model.select("rel-a"); runCurrent()
            f.relationships.value = cached(emptyList()); runCurrent()
            assertEquals(CaregiverContextState.NoActiveElder, f.model.state.value.context)
            f.photos.getValue(elderA.id).value = cached(listOf(photo(elderA).copy(id = "late-revoked-photo")))
            runCurrent()
            assertTrue(f.model.state.value.photos.isEmpty())
        } finally { release.complete(Unit); runCurrent() }
    }

    @Test fun `replacement relationship for same Elder reattaches cached collections`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.model.select("rel-a"); runCurrent()
        f.relationships.value = cached(listOf(relation("replacement", elderA)))
        f.model.select("replacement"); runCurrent()
        assertEquals("replacement", (f.model.state.value.context as CaregiverContextState.Active).context.relationshipId)
        assertEquals(listOf(photo(elderA)), f.model.state.value.photos)
    }

    @Test fun `offline recovery never reports successful refresh`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.pairingResult = failure
        f.model.recover(); runCurrent()
        assertEquals(R.string.chain_failed, f.model.state.value.message)
        assertTrue(f.model.state.value.stale)
    }

    @Test fun `failed command recovery stays pending despite successful relationship refresh`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.records.value = listOf(f.pending())
        f.model.recover(); runCurrent()
        assertEquals(R.string.chain_pending, f.model.state.value.message)
    }

    @Test fun `unknown recovery receipt remains pending`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.records.value = listOf(f.pending())
        f.resumeResult = Outcome.Success(f.pending())
        f.model.recover(); runCurrent()
        assertEquals(R.string.chain_pending, f.model.state.value.message)
    }

    @Test fun `confirmed receipt requiring cache refresh remains pending`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.records.value = listOf(f.pending())
        f.resumeResult = Outcome.Success(f.pending().copy(state = OnlineCommandState.CONFIRMED, cacheState = CacheState.REFRESH_REQUIRED))
        f.model.recover(); runCurrent()
        assertEquals(R.string.chain_pending, f.model.state.value.message)
    }

    @Test fun `confirmed and reconciled receipt reports successful refresh`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.records.value = listOf(f.pending())
        f.resumeResult = Outcome.Success(f.pending().copy(state = OnlineCommandState.CONFIRMED, cacheState = CacheState.APPLIED))
        f.model.recover(); runCurrent()
        assertEquals(R.string.chain_refreshed, f.model.state.value.message)
    }

    @Test fun `definitive rejection reports failure rather than unknown completion`() = runTest(dispatcher) {
        val f = Fixture(); runCurrent(); f.records.value = listOf(f.pending())
        f.resumeResult = Outcome.Success(f.pending().copy(state = OnlineCommandState.REJECTED))
        f.model.recover(); runCurrent()
        assertEquals(R.string.chain_failed, f.model.state.value.message)
    }
}
