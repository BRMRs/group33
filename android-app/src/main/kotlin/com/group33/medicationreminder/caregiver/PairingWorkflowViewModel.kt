package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.PairingRequestView
import com.group33.shared.model.ApiError
import com.group33.shared.model.UserRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Real caregiver form. Business rows come only from the authenticated repository cache. */
class PairingWorkflowViewModel(
    private val repository: PairingRepository,
    private val commands: OnlineCommandRepository,
    private val auth: AuthRepository,
    private val trusted: StateFlow<TrustedSessionContext?>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PairingWorkflowState())
    val state = mutableState.asStateFlow()
    private var current: TrustedSessionContext? = null
    private var session: SessionView? = null
    private var work: Job? = null
    private var records = emptyList<OnlineCommandRecord>()
    private var unresolved: String? = null
    private val observationRetry = MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            combine(auth.observeSession(), trusted) { session, context ->
                this@PairingWorkflowViewModel.session = session
                context?.takeIf { session.readiness == AuthReadiness.READY &&
                    session.user?.role == UserRole.CAREGIVER && session.user.id == it.scope.userId }
            }.distinctUntilChanged().collectLatest { context ->
                current = context
                work?.cancel(); work = null; records = emptyList(); unresolved = null
                mutableState.value = PairingWorkflowState(notice = if (context == null) PairingNotice.SessionUnavailable else null)
                if (context == null) return@collectLatest
                try {
                    combine(repository.observeRequests(), commands.observeCommands()) { cached, journal -> cached to journal }
                        .retryWhen { error, _ ->
                            if (error is CancellationException) throw error
                            if (!isCurrent(context)) return@retryWhen false
                            val attempt = observationRetry.value
                            mutableState.value = state.value.copy(ready = false, requests = emptyList(),
                                requestsKnown = false, notice = PairingNotice.RefreshFailed)
                            observationRetry.first { it != attempt }
                            true
                        }
                        .collect { (cached, journal) ->
                            if (!isCurrent(context)) return@collect
                            records = journal.filter { it.scope == context.scope && it.commandKind in KINDS }
                            if (records.any { it.operationId == unresolved && !it.needsRecovery() }) unresolved = null
                            val authorized = cached.access == AccessState.AUTHORIZED
                            mutableState.value = state.value.copy(
                                ready = true,
                                requests = if (authorized) cached.value.filter { it.caregiver.id == context.scope.userId &&
                                    it.caregiver.role == UserRole.CAREGIVER } else emptyList(),
                                requestsKnown = authorized && cached.hasLocalData,
                                stale = cached.isStale,
                                needsRecovery = needsRecovery(),
                            )
                        }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (isCurrent(context)) mutableState.value = state.value.copy(ready = false,
                        requests = emptyList(), requestsKnown = false, notice = PairingNotice.RefreshFailed)
                }
            }
        }
    }

    private fun isCurrent(context: TrustedSessionContext): Boolean = current == context && trusted.value == context &&
        session?.let { it.readiness == AuthReadiness.READY && it.user?.id == context.scope.userId &&
            it.user.role == UserRole.CAREGIVER } == true

    fun setCode(value: String) {
        if (!state.value.busy && value.length <= 8 && value.all { it in '0'..'9' }) {
            mutableState.value = state.value.copy(code = value,
                notice = state.value.notice.takeUnless { it == PairingNotice.InvalidCode })
        }
    }

    fun submitCode() {
        val code = state.value.code
        if (code.length != 8) { mutableState.value = state.value.copy(notice = PairingNotice.InvalidCode); return }
        submit(OnlineCommandKind.REQUEST_PAIRING, null) { repository.requestPairing(code, it) }
    }

    fun cancel(requestId: String) {
        if (state.value.requests.none { it.id == requestId && it.status == PairingRequestStatus.PENDING }) return
        submit(OnlineCommandKind.CANCEL_PAIRING_REQUEST, requestId) { repository.cancelRequest(requestId, it) }
    }

    private fun submit(kind: OnlineCommandKind, target: String?, action: suspend (CommandContext) -> OnlineCommandResult<*>) {
        val context = current ?: return
        if (!isCurrent(context) || !state.value.ready || state.value.busy || needsRecovery()) return
        mutableState.value = state.value.copy(busy = true, notice = null, lastCommandKind = kind)
        work = viewModelScope.launch {
            var operation: String? = null
            try {
                when (val begun = commands.begin(kind, target)) {
                    is Outcome.Failure -> if (isCurrent(context)) publishFailure(begun.error)
                    is Outcome.Success -> {
                        val command = begun.value
                        if (!isCurrent(context)) return@launch
                        if (command.scope != context.scope || command.commandKind != kind || command.targetId != target) {
                            publishFailure(RepositoryError.Local(LocalFailureCode.SESSION_NOT_READY)); return@launch
                        }
                        operation = command.operationId
                        val result = action(command)
                        if (!isCurrent(context)) return@launch
                        unresolved = result.operationId.takeIf { result is OnlineCommandResult.AwaitingConfirmation ||
                            result is OnlineCommandResult.Confirmed<*> && result.cacheState == CacheState.REFRESH_REQUIRED }
                        mutableState.value = state.value.copy(
                            code = if (kind == OnlineCommandKind.REQUEST_PAIRING && result is OnlineCommandResult.Confirmed<*>) "" else state.value.code,
                            needsRecovery = needsRecovery(), notice = when (result) {
                            is OnlineCommandResult.Confirmed<*> -> if (unresolved == null) PairingNotice.CommandConfirmed else PairingNotice.ResultUnknown
                            is OnlineCommandResult.AwaitingConfirmation -> PairingNotice.ResultUnknown
                            is OnlineCommandResult.Rejected -> PairingNotice.Rejected(result.error.error)
                            is OnlineCommandResult.NotSubmitted -> PairingNotice.NotSubmitted(result.error.code)
                        })
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isCurrent(context)) {
                    unresolved = operation
                    mutableState.value = state.value.copy(needsRecovery = needsRecovery(),
                        notice = if (operation == null) PairingNotice.NotSubmitted(LocalFailureCode.STORAGE_UNAVAILABLE) else PairingNotice.ResultUnknown)
                }
            } finally { if (isCurrent(context)) mutableState.value = state.value.copy(busy = false) }
        }
    }

    /** Resume only unknown/cache reconciliation with original operation IDs; never create/retry a write. */
    fun refresh() {
        val context = current ?: return
        if (!isCurrent(context) || state.value.busy) return
        if (!state.value.ready) observationRetry.value++
        mutableState.value = state.value.copy(busy = true)
        work = viewModelScope.launch {
            try {
                var failed = false
                var recoveredError: RepositoryError? = null
                val ids = (records.filter { it.needsRecovery() }.map { it.operationId } + listOfNotNull(unresolved)).distinct()
                for (id in ids) {
                    if (!isCurrent(context)) return@launch
                    when (val result = commands.resume(id)) {
                        is Outcome.Failure -> failed = true
                        is Outcome.Success -> {
                            if (!isCurrent(context)) return@launch
                            if (result.value.scope != context.scope) { failed = true; continue }
                            records = records.filterNot { it.operationId == id } + result.value
                            if (result.value.state == OnlineCommandState.REJECTED || result.value.state == OnlineCommandState.NOT_SUBMITTED) {
                                recoveredError = result.value.error ?: RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE)
                            }
                            if (id == unresolved && !result.value.needsRecovery()) unresolved = null
                            if (result.value.commandKind == OnlineCommandKind.REQUEST_PAIRING &&
                                result.value.state == OnlineCommandState.CONFIRMED) {
                                mutableState.value = state.value.copy(code = "")
                            }
                        }
                    }
                }
                if (!isCurrent(context)) return@launch
                if (repository.refresh() is Outcome.Failure) failed = true
                val error = recoveredError
                if (isCurrent(context)) mutableState.value = state.value.copy(needsRecovery = needsRecovery(), notice = when {
                    needsRecovery() -> PairingNotice.ResultUnknown
                    failed -> PairingNotice.RefreshFailed
                    error is RepositoryError.Remote -> PairingNotice.Rejected(error.error)
                    error is RepositoryError.Local -> PairingNotice.NotSubmitted(error.code)
                    else -> PairingNotice.Refreshed
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isCurrent(context)) mutableState.value = state.value.copy(notice = PairingNotice.RefreshFailed) }
            finally { if (isCurrent(context)) mutableState.value = state.value.copy(busy = false) }
        }
    }

    private fun publishFailure(error: RepositoryError) {
        mutableState.value = state.value.copy(notice = when (error) {
            is RepositoryError.Local -> PairingNotice.NotSubmitted(error.code)
            is RepositoryError.Remote -> PairingNotice.Rejected(error.error)
        })
    }
    private fun needsRecovery() = unresolved != null || records.any { it.needsRecovery() }
    private fun OnlineCommandRecord.needsRecovery() = state == OnlineCommandState.AWAITING_CONFIRMATION ||
        state == OnlineCommandState.CONFIRMED && cacheState == CacheState.REFRESH_REQUIRED
    private companion object {
        val KINDS = setOf(OnlineCommandKind.REQUEST_PAIRING, OnlineCommandKind.CANCEL_PAIRING_REQUEST)
    }
}

data class PairingWorkflowState(
    val code: String = "",
    val requests: List<PairingRequestView> = emptyList(),
    val ready: Boolean = false,
    val requestsKnown: Boolean = false,
    val stale: Boolean = false,
    val busy: Boolean = false,
    val needsRecovery: Boolean = false,
    val notice: PairingNotice? = null,
    val lastCommandKind: OnlineCommandKind? = null,
)

sealed interface PairingNotice {
    data object InvalidCode : PairingNotice
    data object SessionUnavailable : PairingNotice
    data object CommandConfirmed : PairingNotice
    data object ResultUnknown : PairingNotice
    data object RefreshFailed : PairingNotice
    data object Refreshed : PairingNotice
    data class Rejected(val error: ApiError) : PairingNotice
    data class NotSubmitted(val code: LocalFailureCode) : PairingNotice
}
