package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.PairingCodeView
import com.group33.shared.contract.PairingRequestView
import com.group33.shared.model.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** C1 pairing flow over the reviewed repository boundary. It never turns PENDING into access. */
class PairingWorkflowViewModel(
    private val repository: PairingRepository,
    private val commandContext: PairingCommandContextProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PairingWorkflowState())
    val state = mutableState.asStateFlow()
    private var requestGeneration = 0
    private var refreshGeneration = 0

    init {
        viewModelScope.launch {
            repository.observeRequests().collect { cached ->
                mutableState.value = mutableState.value.copy(
                    requests = if (cached.access == AccessState.AUTHORIZED) cached.value else emptyList(),
                )
            }
        }
    }

    fun setCode(value: String) {
        // Keep leading zeroes; only ASCII digits belong to the eight-character contract.
        if (value.length <= PAIRING_CODE_LENGTH && value.all { it in '0'..'9' }) {
            mutableState.value = mutableState.value.copy(code = value, notice = null)
        }
    }

    fun submitCode() {
        val code = state.value.code
        if (state.value.busy) return
        if (code.length != PAIRING_CODE_LENGTH) {
            mutableState.value = state.value.copy(notice = PairingNotice.InvalidCode)
            return
        }
        val context = commandContext.create(OnlineCommandKind.REQUEST_PAIRING, null)
        if (context == null) { publish(PairingNotice.SessionUnavailable); return }
        runCommand(context) { repository.requestPairing(code, context) }
    }

    fun issueCode() {
        if (state.value.busy) return
        val context = commandContext.create(OnlineCommandKind.ISSUE_PAIRING_CODE, null)
        if (context == null) { publish(PairingNotice.SessionUnavailable); return }
        runCommand(context) { repository.issueCode(context) }
    }

    fun cancelCode(codeId: String) = requestAction(codeId, OnlineCommandKind.CANCEL_PAIRING_CODE) { context ->
        repository.cancelCode(codeId, context)
    }

    fun cancel(requestId: String) = requestAction(requestId, OnlineCommandKind.CANCEL_PAIRING_REQUEST) { context ->
        repository.cancelRequest(requestId, context)
    }

    fun respond(requestId: String, accept: Boolean) = requestAction(requestId, OnlineCommandKind.RESPOND_PAIRING) { context ->
        repository.respond(requestId, accept, context)
    }

    fun revoke(relationshipId: String) = requestAction(relationshipId, OnlineCommandKind.REVOKE_RELATIONSHIP) { context ->
        repository.revoke(relationshipId, context)
    }

    /** Refresh is read-only. It does not create another request or replace an unknown operation. */
    fun refresh() {
        val generation = ++refreshGeneration
        viewModelScope.launch {
            val outcome = try { repository.refresh() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Outcome.Failure(RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE)) }
            if (generation != refreshGeneration) return@launch
            publish(when {
                outcome is Outcome.Failure -> PairingNotice.RefreshFailed
                state.value.notice == PairingNotice.ResultUnknown -> PairingNotice.ResultUnknown
                else -> PairingNotice.Refreshed
            })
        }
    }

    private fun requestAction(targetId: String, kind: OnlineCommandKind, action: suspend (CommandContext) -> OnlineCommandResult<*>): Boolean {
        if (targetId.isBlank() || state.value.busy) return false
        val context = commandContext.create(kind, targetId) ?: return publish(PairingNotice.SessionUnavailable)
        runCommand(context, action)
        return true
    }

    private fun runCommand(context: CommandContext, action: suspend (CommandContext) -> OnlineCommandResult<*>) {
        val generation = ++requestGeneration
        mutableState.value = state.value.copy(
            busy = true, notice = null, lastOperationId = context.operationId,
            lastCommandKind = context.commandKind,
        )
        viewModelScope.launch {
            val result = try { action(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OnlineCommandResult.AwaitingConfirmation(context.operationId) }
            if (generation != requestGeneration) return@launch
            val confirmedValue = (result as? OnlineCommandResult.Confirmed<*>)?.value
            val issuedCode = confirmedValue as? PairingCodeView
            val updatedRequest = when (confirmedValue) {
                is PairingRequestView -> confirmedValue
                is PairingDecisionResult -> confirmedValue.request
                else -> null
            }
            val requests = updatedRequest?.let { updated ->
                (state.value.requests.filterNot { it.id == updated.id } + updated)
            } ?: state.value.requests
            mutableState.value = state.value.copy(
                busy = false,
                requests = requests,
                issuedCode = issuedCode ?: state.value.issuedCode,
                notice = when (result) {
                    is OnlineCommandResult.Confirmed<*> -> PairingNotice.CommandConfirmed
                    is OnlineCommandResult.AwaitingConfirmation -> PairingNotice.ResultUnknown
                    is OnlineCommandResult.Rejected -> PairingNotice.Rejected(result.error.error)
                    is OnlineCommandResult.NotSubmitted -> PairingNotice.NotSubmitted(result.error.code)
                },
            )
        }
    }

    private fun publish(notice: PairingNotice): Boolean {
        mutableState.value = state.value.copy(notice = notice)
        return false
    }

    class Factory(private val repository: PairingRepository, private val commandContext: PairingCommandContextProvider) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PairingWorkflowViewModel(repository, commandContext) as T
    }

    companion object { const val PAIRING_CODE_LENGTH = 8 }
}

fun interface PairingCommandContextProvider {
    /** Must supply the authenticated installation/user scope; the screen cannot choose a principal. */
    fun create(kind: OnlineCommandKind, targetId: String?): CommandContext?
}

data class PairingWorkflowState(
    val code: String = "",
    val requests: List<PairingRequestView> = emptyList(),
    val issuedCode: PairingCodeView? = null,
    val busy: Boolean = false,
    val notice: PairingNotice? = null,
    val lastOperationId: String? = null,
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
