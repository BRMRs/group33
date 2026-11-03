package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import androidx.annotation.MainThread

data class PlanEditOperationState(
    val status: OnlineCommandState = OnlineCommandState.NOT_SUBMITTED,
    val cache: CacheState? = null,
    val error: RepositoryError? = null,
    val busy: Boolean = false,
)

/** One immutable edit intent. Context comes from journal.begin(UPDATE_PLAN, planId).
 * Host owns validation/authorization and discards this consumer on scope invalidation.
 * Durable payload binding and process recovery remain repository responsibilities.
 */
@MainThread
class PlanEditOperation(
    draft: PlanEditDraft,
    private val command: CommandContext,
    trustedScope: RepositoryScope,
    private val plans: ReminderPlanRepository,
    private val journal: OnlineCommandRepository,
    restoredRecord: OnlineCommandRecord? = null,
) {
    private val planId = draft.confirmed.id
    private val payload = draft.input.copy(plan = draft.input.plan.copy(localTimes = draft.input.plan.localTimes.toList()))
    private val originalDraft = draft.changeInput(payload)
    private val recoveryOnly = restoredRecord != null
    private val mutex = Mutex()
    private var closed = false
    private val mutableState = MutableStateFlow(PlanEditOperationState())
    val state = mutableState.asStateFlow()
    init {
        require(!draft.conflict && draft.confirmed.active)
        require(payload.expectedRevision == draft.confirmed.revision)
        require(command.scope == trustedScope && command.targetId == planId && command.commandKind == OnlineCommandKind.UPDATE_PLAN)
        restoredRecord?.let {
            validateRecord(it)
            mutableState.value = PlanEditOperationState(it.state, it.cacheState, it.error)
        }
    }

    /** Only a definitive version rejection opens draft recovery; timeout does not. */
    fun conflictedDraft(): PlanEditDraft? = originalDraft.takeIf {
        !closed && state.value.status == OnlineCommandState.REJECTED &&
            (state.value.error as? RepositoryError.Remote)?.error?.code == "PLAN_VERSION_CONFLICT"
    }?.revisionRejected()

    fun close() { closed = true; mutableState.value = PlanEditOperationState() }

    suspend fun submit() = singleFlight {
        if (recoveryOnly || state.value.status != OnlineCommandState.NOT_SUBMITTED) return@singleFlight
        // Once dispatched, exceptions/cancellation cannot prove that the server did not commit.
        mutableState.value = state.value.copy(status = OnlineCommandState.AWAITING_CONFIRMATION)
        val result = plans.update(planId, payload, command)
        if (closed) return@singleFlight
        require(result.operationId == command.operationId)
        mutableState.value = when (result) {
            is OnlineCommandResult.Confirmed -> PlanEditOperationState(result.status, result.cacheState)
            is OnlineCommandResult.Rejected -> PlanEditOperationState(result.status, error = result.error)
            is OnlineCommandResult.NotSubmitted -> PlanEditOperationState(result.status, error = result.error)
            is OnlineCommandResult.AwaitingConfirmation -> PlanEditOperationState(result.status)
        }
    }

    suspend fun resume() = singleFlight {
        val current = state.value
        if (current.status != OnlineCommandState.AWAITING_CONFIRMATION &&
            !(current.status == OnlineCommandState.CONFIRMED && current.cache == CacheState.REFRESH_REQUIRED)) return@singleFlight
        when (val result = journal.resume(command.operationId)) {
            is Outcome.Failure -> if (!closed) mutableState.value = state.value.copy(error = result.error)
            is Outcome.Success -> {
                val record = result.value
                validateRecord(record)
                // Cache reconciliation cannot downgrade a confirmed server commit.
                require(current.status != OnlineCommandState.CONFIRMED || record.state == OnlineCommandState.CONFIRMED)
                if (!closed) mutableState.value = PlanEditOperationState(record.state, record.cacheState, record.error)
            }
        }
    }

    private fun validateRecord(record: OnlineCommandRecord) {
        require(record.operationId == command.operationId && record.scope == command.scope &&
            record.commandKind == command.commandKind && record.targetId == planId)
        require(record.resultRef == null ||
            (record.resultRef.kind == CommandResultKind.REMINDER_PLAN && record.resultRef.id == planId))
        require(record.state != OnlineCommandState.CONFIRMED || record.cacheState != null)
    }

    private suspend fun singleFlight(action: suspend () -> Unit) {
        if (closed || !mutex.tryLock()) return
        try {
            mutableState.value = state.value.copy(busy = true)
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!closed) mutableState.value = state.value.copy(error = RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE))
        } finally {
            if (!closed) mutableState.value = state.value.copy(busy = false)
            mutex.unlock()
        }
    }
}
