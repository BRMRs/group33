package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import androidx.annotation.MainThread

data class PlanDeactivationState(
    val status: OnlineCommandState = OnlineCommandState.NOT_SUBMITTED,
    val cache: CacheState? = null,
    val error: RepositoryError? = null,
    val busy: Boolean = false,
)

/** One explicit deactivation intent. Host supplies trusted scope and journal DEACTIVATE_PLAN context.
 * Call deactivate only for a user action; construction never dispatches. Existing generated
 * events/history are untouched. Host closes on access/session changes. No production adapter
 * or event writer is installed here; repository owns durable payload and server validation.
 */
@MainThread
class PlanDeactivation(
    confirmed: com.group33.shared.contract.ReminderPlanView,
    private val command: CommandContext,
    trustedScope: RepositoryScope,
    private val plans: ReminderPlanRepository,
    private val journal: OnlineCommandRepository,
    restoredRecord: OnlineCommandRecord? = null,
) {
    private val planId = confirmed.id
    private val expectedRevision = confirmed.revision
    private val recoveryOnly = restoredRecord != null
    private val mutex = Mutex()
    private var closed = false
    private val mutableState = MutableStateFlow(PlanDeactivationState())
    val state = mutableState.asStateFlow()
    init {
        require(confirmed.active)
        require(command.scope == trustedScope && command.targetId == planId && command.commandKind == OnlineCommandKind.DEACTIVATE_PLAN)
        restoredRecord?.let {
            validateRecord(it)
            mutableState.value = PlanDeactivationState(it.state, it.cacheState, it.error)
        }
    }

    fun close() { closed = true; mutableState.value = PlanDeactivationState() }

    suspend fun deactivate() = singleFlight {
        if (recoveryOnly || state.value.status != OnlineCommandState.NOT_SUBMITTED) return@singleFlight
        // Once dispatched, exceptions/cancellation cannot prove that the server did not commit.
        mutableState.value = state.value.copy(status = OnlineCommandState.AWAITING_CONFIRMATION)
        if (closed) return@singleFlight
        val result = plans.deactivate(planId, expectedRevision, command)
        if (closed) return@singleFlight
        require(result.operationId == command.operationId)
        mutableState.value = when (result) {
            is OnlineCommandResult.Confirmed -> PlanDeactivationState(result.status, result.cacheState)
            is OnlineCommandResult.Rejected -> PlanDeactivationState(result.status, error = result.error)
            is OnlineCommandResult.NotSubmitted -> PlanDeactivationState(result.status, error = result.error)
            is OnlineCommandResult.AwaitingConfirmation -> PlanDeactivationState(result.status)
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
                if (!closed) mutableState.value = PlanDeactivationState(record.state, record.cacheState, record.error)
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
            if (!closed) action()
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
