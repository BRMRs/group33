package com.group33.medicationreminder.elder.photo

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.MedicationPhotoView
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElderPhotoSubmitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = RepositoryScope("test", "installation", "elder")
    private val context = CommandContext("00000000-0000-4000-8000-000000000001", scope, OnlineCommandKind.SUBMIT_PHOTO, null)
    private val source = object : PhotoSource {
        override val contentType = "image/png"
        override val byteCount = 1L
        override val sha256 = "test-only"
        override fun openStream() = ByteArrayInputStream(byteArrayOf(1))
    }
    private val draft = PhotoDraft("A", source, "test-only")
    private val store = Store()
    private val photos = Photos()
    private val commands = Commands()
    private val scopes = MutableStateFlow<RepositoryScope?>(scope)
    private val dependencies get() = ElderPhotoDependencies(photos, commands, scopes)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun selected(deps: ElderPhotoDependencies? = dependencies): ElderPhotoSubmitViewModel {
        val model = ElderPhotoSubmitViewModel(store, deps)
        model.select("A")
        dispatcher.scheduler.runCurrent()
        return model
    }
    private fun stage(model: ElderPhotoSubmitViewModel) = model.state.value.stage

    @Test fun `preview never submits until explicit use`() = runTest(dispatcher) {
        val model = selected()
        assertEquals(PhotoStage.PREVIEW, stage(model))
        assertEquals(0, photos.calls)
        assertEquals(0, commands.begins)
    }
    @Test fun `missing real adapter truthfully keeps local photo`() = runTest(dispatcher) {
        val model = selected(null)
        model.submit(); runCurrent()
        assertEquals(PhotoStage.UNAVAILABLE, stage(model))
        assertSame(draft, model.state.value.draft)
        assertEquals(0, photos.calls)
    }
    @Test fun `double tap submits once and marks private source first`() = runTest(dispatcher) {
        val pending = CompletableDeferred<OnlineCommandResult<MedicationPhotoView>>()
        photos.action = { assertEquals(1, store.marked); pending.await() }
        val model = selected()
        model.submit(); model.submit(); runCurrent()
        assertEquals(1, photos.calls)
        assertEquals(1, commands.begins)
        pending.complete(OnlineCommandResult.Confirmed(context.operationId, photo(), CacheState.APPLIED)); runCurrent()
        assertEquals(PhotoStage.CONFIRMED, stage(model))
        model.submit(); runCurrent(); assertEquals(1, photos.calls)
    }
    @Test fun `confirmed refresh required stays confirmed on offline reconciliation`() = runTest(dispatcher) {
        photos.action = { OnlineCommandResult.Confirmed(it.operationId, photo(), CacheState.REFRESH_REQUIRED) }
        val model = selected(); model.submit(); runCurrent(); model.reconcile(); runCurrent()
        assertEquals(PhotoStage.CONFIRMED_REFRESH, stage(model))
        assertEquals(context.operationId, commands.resumed)
        assertEquals(1, photos.calls)
    }
    @Test fun `unknown resume offline keeps awaiting and original operation`() = runTest(dispatcher) {
        photos.action = { OnlineCommandResult.AwaitingConfirmation(it.operationId) }
        val model = selected(); model.submit(); runCurrent(); model.reconcile(); runCurrent(); model.submit(); runCurrent()
        assertEquals(PhotoStage.AWAITING, stage(model))
        assertEquals(context.operationId, commands.resumed)
        assertEquals(1, photos.calls)
    }
    @Test fun `network exception after adapter entry is unknown not definite failure`() = runTest(dispatcher) {
        photos.action = { throw IOException() }
        val model = selected(); model.submit(); runCurrent()
        assertEquals(PhotoStage.AWAITING, stage(model))
    }
    @Test fun `source retention failure prevents adapter call`() = runTest(dispatcher) {
        store.failMark = true
        val model = selected(); model.submit(); runCurrent()
        assertEquals(PhotoStage.NOT_SUBMITTED, stage(model))
        assertEquals(0, photos.calls)
    }
    @Test fun `not submitted retains source and reuses command on explicit retry`() = runTest(dispatcher) {
        photos.action = { OnlineCommandResult.NotSubmitted(it.operationId, RepositoryError.Local(LocalFailureCode.OFFLINE)) }
        val model = selected(); model.submit(); runCurrent(); model.submit(); runCurrent()
        assertEquals(PhotoStage.NOT_SUBMITTED, stage(model))
        assertEquals(1, commands.begins)
        assertEquals(2, photos.calls)
        assertSame(draft, model.state.value.draft)
    }
    @Test fun `late A result cannot replace B preview or clear B submitting`() = runTest(dispatcher) {
        val first = CompletableDeferred<OnlineCommandResult<MedicationPhotoView>>()
        val second = CompletableDeferred<OnlineCommandResult<MedicationPhotoView>>()
        photos.action = { if (photos.calls == 1) first.await() else second.await() }
        val model = selected(); model.submit(); runCurrent()
        model.select("B"); runCurrent(); model.submit(); runCurrent()
        first.complete(OnlineCommandResult.Confirmed(context.operationId, photo(), CacheState.APPLIED)); runCurrent()
        assertEquals("B", model.state.value.draft?.id)
        assertEquals(PhotoStage.SUBMITTING, stage(model))
        assertEquals(2, store.marked)
        second.complete(OnlineCommandResult.AwaitingConfirmation(context.operationId)); runCurrent()
    }
    @Test fun `scope change hides photo and ignores old callback`() = runTest(dispatcher) {
        val pending = CompletableDeferred<OnlineCommandResult<MedicationPhotoView>>()
        photos.action = { pending.await() }
        val model = selected(); model.submit(); runCurrent()
        scopes.value = scope.copy(userId = "other"); runCurrent()
        pending.complete(OnlineCommandResult.Confirmed(context.operationId, photo(), CacheState.APPLIED)); runCurrent()
        assertEquals(PhotoStage.EMPTY, stage(model))
        assertNull(model.state.value.draft)
    }
    @Test fun `foreign command never reaches photo adapter`() = runTest(dispatcher) {
        commands.beginResult = Outcome.Success(context.copy(scope = scope.copy(userId = "other")))
        val model = selected(); model.submit(); runCurrent()
        assertEquals(PhotoStage.UNAVAILABLE, stage(model)); assertEquals(0, photos.calls)
    }
    @Test fun `wrong receipt operation never becomes success`() = runTest(dispatcher) {
        photos.action = { OnlineCommandResult.Confirmed("other", photo(), CacheState.APPLIED) }
        val model = selected(); model.submit(); runCurrent()
        assertEquals(PhotoStage.AWAITING, stage(model))
    }
    @Test fun `resume validates photo result kind and scope`() = runTest(dispatcher) {
        photos.action = { OnlineCommandResult.AwaitingConfirmation(it.operationId) }
        val model = selected(); model.submit(); runCurrent()
        commands.resumeResult = Outcome.Success(record(CommandResultKind.REMINDER_PLAN))
        model.reconcile(); runCurrent(); assertEquals(PhotoStage.AWAITING, stage(model))
        commands.resumeResult = Outcome.Success(record(CommandResultKind.PHOTO))
        model.reconcile(); runCurrent(); assertEquals(PhotoStage.CONFIRMED, stage(model))
    }
    @Test fun `attempted draft restoration blocks duplicate upload`() = runTest(dispatcher) {
        store.restored = draft to context
        val model = ElderPhotoSubmitViewModel(store, dependencies); runCurrent()
        assertEquals(PhotoStage.RESTORED_PENDING, stage(model))
        model.submit(); runCurrent(); assertEquals(0, photos.calls)
    }
    @Test fun `invalid source offers recovery without success`() = runTest(dispatcher) {
        store.failImport = true
        val model = selected(); assertEquals(PhotoStage.INVALID_PHOTO, stage(model))
        assertEquals(0, photos.calls)
    }

    @Test fun `rejected explicit retry uses original command without another submit`() = runTest(dispatcher) {
        photos.action = { OnlineCommandResult.Rejected(it.operationId, RepositoryError.Remote(com.group33.shared.model.ApiError("INVALID_PHOTO", "unused", false, "test"))) }
        val model = selected(); model.submit(); runCurrent()
        assertEquals(PhotoStage.REJECTED, stage(model))
        commands.resumeResult = Outcome.Success(record(CommandResultKind.PHOTO))
        model.submit(); runCurrent()
        assertEquals(context.operationId, commands.retried)
        assertEquals(1, photos.calls)
        assertEquals(1, commands.begins)
        assertEquals(PhotoStage.CONFIRMED, stage(model))
    }

    private fun photo() = MedicationPhotoView("photo", "elder", "image/png", 0)
    private fun record(kind: CommandResultKind) = OnlineCommandRecord(context.operationId, scope,
        OnlineCommandKind.SUBMIT_PHOTO, null, OnlineCommandState.CONFIRMED, CommandResultRef(kind, "photo"), CacheState.APPLIED, null)
    private inner class Store : PhotoDraftStore {
        var marked = 0
        var failMark = false
        var failImport = false
        var restored: Pair<PhotoDraft, CommandContext?>? = null
        override suspend fun restore() = restored
        override suspend fun importPhoto(uri: String): PhotoDraft {
            if (failImport) throw IOException()
            return if (uri == "A") draft else draft.copy(id = uri)
        }
        override suspend fun markAttempted(draft: PhotoDraft, command: CommandContext) { if (failMark) throw IOException(); marked++ }
    }
    private inner class Photos : MedicationPhotoRepository {
        var calls = 0
        var action: suspend (CommandContext) -> OnlineCommandResult<MedicationPhotoView> = {
            OnlineCommandResult.Confirmed(it.operationId, photo(), CacheState.APPLIED)
        }
        override suspend fun submit(source: PhotoSource, command: CommandContext): OnlineCommandResult<MedicationPhotoView> { calls++; return action(command) }
        override fun observePhotos(elderId: String) = flowOf(Cached(emptyList<MedicationPhotoView>(), false, null, false, AccessState.UNKNOWN))
        override suspend fun loadContent(photoId: String): Outcome<PhotoContent> = error("unused")
        override suspend fun refresh(elderId: String): Outcome<SyncSummary> = error("unused")
    }
    private inner class Commands : OnlineCommandRepository {
        var begins = 0
        var resumed: String? = null
        var retried: String? = null
        var beginResult: Outcome<CommandContext> = Outcome.Success(context)
        var resumeResult: Outcome<OnlineCommandRecord> = Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE))
        override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> { begins++; return beginResult }
        override fun observeCommands() = flowOf(emptyList<OnlineCommandRecord>())
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> { resumed = operationId; return resumeResult }
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> { retried = operationId; return resumeResult }
    }
}
