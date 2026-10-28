package com.group33.medicationreminder.elder.photo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Supplied by the authenticated host. No preview repository or invented principal is installed. */
interface ElderPhotoDependenciesOwner {
    val elderPhotoDependencies: ElderPhotoDependencies?
}

data class ElderPhotoDependencies(
    val photos: MedicationPhotoRepository,
    val commands: OnlineCommandRepository,
    val scope: StateFlow<RepositoryScope?>,
)

data class PhotoDraft(val id: String, val source: PhotoSource, val previewPath: String)
interface PhotoDraftStore {
    suspend fun importPhoto(uri: String): PhotoDraft
    suspend fun restore(): Pair<PhotoDraft, CommandContext?>?
    /** Persist before handing bytes to any adapter; unresolved sources must survive replacement. */
    suspend fun markAttempted(draft: PhotoDraft, command: CommandContext)
}

enum class PhotoStage {
    EMPTY, LOADING, PREVIEW, SUBMITTING, CONFIRMED, CONFIRMED_REFRESH,
    NOT_SUBMITTED, REJECTED, AWAITING, UNAVAILABLE, INVALID_PHOTO, RESTORED_PENDING,
}
data class ElderPhotoSubmitUiState(
    val draft: PhotoDraft? = null,
    val stage: PhotoStage = PhotoStage.EMPTY,
    val error: RepositoryError? = null,
) {
    val busy get() = stage == PhotoStage.LOADING || stage == PhotoStage.SUBMITTING
    val canSubmit get() = draft != null && stage in setOf(PhotoStage.PREVIEW, PhotoStage.NOT_SUBMITTED, PhotoStage.UNAVAILABLE, PhotoStage.REJECTED)
}

class ElderPhotoSubmitViewModel(
    private val store: PhotoDraftStore,
    private val dependencies: ElderPhotoDependencies?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ElderPhotoSubmitUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var command: CommandContext? = null

    init {
        val restorationTicket = generation
        viewModelScope.launch {
            val ticket = restorationTicket
            try {
                store.restore()?.let { (draft, originalCommand) ->
                    if (ticket == generation && (originalCommand == null || dependencies?.scope?.value == originalCommand.scope)) {
                        command = originalCommand
                        mutableState.value = ElderPhotoSubmitUiState(draft,
                            if (originalCommand != null) PhotoStage.RESTORED_PENDING else PhotoStage.PREVIEW)
                    }

                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* A missing/corrupt draft never becomes a successful submission. */ }
        }
        val initialScope = dependencies?.scope?.value
        if (dependencies != null) viewModelScope.launch {
            var previous = initialScope
            dependencies.scope.collect { scope ->
                if (scope != previous) {
                    previous = scope
                    generation++
                    command = null
                    mutableState.value = ElderPhotoSubmitUiState()
                }
            }
        }
    }

    fun select(uri: String) {
        val ticket = ++generation
        command = null
        mutableState.value = ElderPhotoSubmitUiState(stage = PhotoStage.LOADING)
        viewModelScope.launch {
            try {
                val draft = store.importPhoto(uri)
                if (ticket == generation) mutableState.value = ElderPhotoSubmitUiState(draft, PhotoStage.PREVIEW)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (ticket == generation) mutableState.value = ElderPhotoSubmitUiState(stage = PhotoStage.INVALID_PHOTO)
            }
        }
    }

    fun submit() {
        val current = state.value
        if (!current.canSubmit) return
        if (current.stage == PhotoStage.REJECTED) { retryRejected(); return }
        val draft = current.draft ?: return
        val deps = dependencies
        val scope = deps?.scope?.value
        if (deps == null || scope == null) {
            mutableState.value = current.copy(stage = PhotoStage.UNAVAILABLE)
            return
        }
        val ticket = generation
        mutableState.value = current.copy(stage = PhotoStage.SUBMITTING, error = null)
        viewModelScope.launch {
            var mayHaveSent = false
            try {
                val context = command ?: when (val begun = deps.commands.begin(OnlineCommandKind.SUBMIT_PHOTO, null)) {
                    is Outcome.Success -> begun.value
                    is Outcome.Failure -> {
                        if (ticket == generation) mutableState.value = current.copy(stage = PhotoStage.NOT_SUBMITTED, error = begun.error)
                        return@launch
                    }
                }
                if (ticket != generation || deps.scope.value != scope) return@launch
                if (context.scope != scope || context.commandKind != OnlineCommandKind.SUBMIT_PHOTO || context.targetId != null) {
                    mutableState.value = current.copy(stage = PhotoStage.UNAVAILABLE)
                    return@launch
                }
                command = context
                store.markAttempted(draft, context)
                if (ticket != generation || deps.scope.value != scope) return@launch
                mayHaveSent = true
                val result = deps.photos.submit(draft.source, context)
                if (ticket != generation || deps.scope.value != scope) return@launch
                mutableState.value = when {
                    result.operationId != context.operationId -> current.copy(stage = PhotoStage.AWAITING)
                    result is OnlineCommandResult.Confirmed && result.value.elderId != scope.userId -> current.copy(stage = PhotoStage.AWAITING)
                    result is OnlineCommandResult.Confirmed -> current.copy(stage = if (result.cacheState == CacheState.APPLIED) PhotoStage.CONFIRMED else PhotoStage.CONFIRMED_REFRESH)
                    result is OnlineCommandResult.NotSubmitted -> current.copy(stage = PhotoStage.NOT_SUBMITTED, error = result.error)
                    result is OnlineCommandResult.Rejected -> current.copy(stage = PhotoStage.REJECTED, error = result.error)
                    else -> current.copy(stage = PhotoStage.AWAITING)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (ticket == generation) mutableState.value = current.copy(
                    stage = if (mayHaveSent) PhotoStage.AWAITING else PhotoStage.NOT_SUBMITTED,
                    error = if (mayHaveSent) null else RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE),
                )
            }
        }
    }

    private fun retryRejected() {
        val deps = dependencies ?: return
        val context = command ?: return
        if (deps.scope.value != context.scope) return
        val current = state.value
        val ticket = generation
        mutableState.value = current.copy(stage = PhotoStage.SUBMITTING)
        viewModelScope.launch {
            val result = try { deps.commands.retryRejected(context.operationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == generation) mutableState.value = current.copy(stage = PhotoStage.AWAITING)
                return@launch
            }
            if (ticket != generation || deps.scope.value != context.scope) return@launch
            mutableState.value = if (result is Outcome.Success) applyRecord(current, context, result.value)
                else if ((result as? Outcome.Failure)?.error == RepositoryError.Local(LocalFailureCode.OFFLINE)) current
                else current.copy(stage = PhotoStage.AWAITING)
        }
    }

    /** Host-driven reconciliation; no technical retry/sync task is shown to the elder. */
    fun reconcile() {
        val deps = dependencies ?: return
        val context = command ?: return
        val current = state.value
        if (current.stage !in setOf(PhotoStage.AWAITING, PhotoStage.CONFIRMED_REFRESH, PhotoStage.RESTORED_PENDING)) return
        val ticket = generation
        viewModelScope.launch {
            val result = try { deps.commands.resume(context.operationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@launch }
            if (ticket != generation || deps.scope.value != context.scope || result !is Outcome.Success) return@launch
            mutableState.value = applyRecord(current, context, result.value)
        }
    }

    private fun applyRecord(current: ElderPhotoSubmitUiState, context: CommandContext, record: OnlineCommandRecord): ElderPhotoSubmitUiState {
        if (record.operationId != context.operationId || record.scope != context.scope ||
            record.commandKind != OnlineCommandKind.SUBMIT_PHOTO || record.targetId != context.targetId) return current.copy(
                stage = if (current.stage == PhotoStage.CONFIRMED_REFRESH) current.stage else PhotoStage.AWAITING)
        return when (record.state) {
            OnlineCommandState.CONFIRMED -> if (record.resultRef?.kind == CommandResultKind.PHOTO && record.resultRef.id.isNotBlank()) current.copy(
                stage = if (record.cacheState == CacheState.APPLIED) PhotoStage.CONFIRMED else PhotoStage.CONFIRMED_REFRESH,
            ) else current.copy(stage = if (current.stage == PhotoStage.CONFIRMED_REFRESH) current.stage else PhotoStage.AWAITING)
            OnlineCommandState.NOT_SUBMITTED -> if (current.stage == PhotoStage.CONFIRMED_REFRESH) current else current.copy(stage = PhotoStage.NOT_SUBMITTED, error = record.error)
            OnlineCommandState.REJECTED -> if (current.stage == PhotoStage.CONFIRMED_REFRESH) current else current.copy(stage = PhotoStage.REJECTED, error = record.error)
            OnlineCommandState.AWAITING_CONFIRMATION -> if (current.stage == PhotoStage.CONFIRMED_REFRESH) current else current.copy(stage = PhotoStage.AWAITING)
        }
    }
}
