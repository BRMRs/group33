package com.group33.medicationreminder.caregiver.plan

import androidx.lifecycle.ViewModel
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class PlanSaveStatus { IDLE, SUBMITTING, NOT_SUBMITTED, REJECTED, AWAITING_CONFIRMATION, CONFIRMED }
enum class PlanSaveInputError { INVALID_FIELDS, PHOTO_REQUIRED, WRONG_ELDER, TIME_ZONE_CONFIRMATION_REQUIRED }

data class PlanSaveState(
    val status: PlanSaveStatus = PlanSaveStatus.IDLE,
    val request: ConfirmReminderPlanRequest? = null,
    val operationId: String? = null,
    val error: RepositoryError? = null,
    val inputError: PlanSaveInputError? = null,
    val cacheState: CacheState? = null,
)

/** One new-plan editor, bound to one authenticated scope and Elder. No production adapter is supplied here. */
class PlanSaveViewModel(
    private val elderId: String,
    private val scope: RepositoryScope,
    private val plans: ReminderPlanRepository,
    private val commands: OnlineCommandRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PlanSaveState())
    val state = mutableState.asStateFlow()
    private val lock = Mutex()
    private var context: CommandContext? = null

    init { require(elderId.isNotBlank()) }

    /** Call from the UI coroutine. Duplicate taps and unresolved/confirmed saves never start another intent. */
    suspend fun save(input: PlanFormInput, photo: MedicationPhotoView?, acknowledgement: TimeZoneAcknowledgement) {
        if (!lock.tryLock()) return
        var dispatched = false
        try {
            if (state.value.status in setOf(PlanSaveStatus.AWAITING_CONFIRMATION, PlanSaveStatus.CONFIRMED)) return
            val form = PlanFormViewModel().apply { updateInput(input) }
            val invalid = when {
                !form.validate() -> PlanSaveInputError.INVALID_FIELDS
                photo == null || photo.id.isBlank() -> PlanSaveInputError.PHOTO_REQUIRED
                photo.elderId != elderId -> PlanSaveInputError.WRONG_ELDER
                !validAcknowledgement(input.executionTimeZoneId, acknowledgement) -> PlanSaveInputError.TIME_ZONE_CONFIRMATION_REQUIRED
                else -> null
            }
            if (invalid != null) {
                mutableState.value = state.value.copy(inputError = invalid)
                return
            }
            val fields = checkNotNull(form.state.value.validatedFields)
            val request = ConfirmReminderPlanRequest(checkNotNull(photo).id, fields.medication, fields.plan, acknowledgement)
            val previous = state.value
            // Unchanged rejections need the journal's explicit retryRejected flow, not another confirm call.
            if (previous.status == PlanSaveStatus.REJECTED && previous.request == request) return
            // Same unsent payload keeps its original intent; changed content starts a new one.
            if (previous.request != request) context = null
            mutableState.value = PlanSaveState(PlanSaveStatus.SUBMITTING, request, context?.operationId)
            if (context == null) {
                when (val begun = commands.begin(OnlineCommandKind.CONFIRM_PLAN, elderId)) {
                    is Outcome.Failure -> {
                        mutableState.value = state.value.copy(status = PlanSaveStatus.NOT_SUBMITTED, error = begun.error)
                        return
                    }
                    is Outcome.Success -> {
                        val value = begun.value
                        require(value.scope == scope && value.commandKind == OnlineCommandKind.CONFIRM_PLAN && value.targetId == elderId)
                        context = value
                        mutableState.value = state.value.copy(operationId = value.operationId)
                    }
                }
            }
            val command = checkNotNull(context)
            // Once dispatch may have started, exceptions/cancellation cannot prove that no save occurred.
            dispatched = true
            val result = plans.confirm(elderId, request, command)
            require(result.operationId == command.operationId)
            mutableState.value = when (result) {
                is OnlineCommandResult.Confirmed -> state.value.copy(status = PlanSaveStatus.CONFIRMED, cacheState = result.cacheState)
                is OnlineCommandResult.AwaitingConfirmation -> state.value.copy(status = PlanSaveStatus.AWAITING_CONFIRMATION)
                is OnlineCommandResult.Rejected -> state.value.copy(status = PlanSaveStatus.REJECTED, error = result.error)
                is OnlineCommandResult.NotSubmitted -> state.value.copy(status = PlanSaveStatus.NOT_SUBMITTED, error = result.error)
            }
        } catch (cancelled: CancellationException) {
            if (state.value.status == PlanSaveStatus.SUBMITTING) mutableState.value = state.value.copy(
                status = if (dispatched) PlanSaveStatus.AWAITING_CONFIRMATION else PlanSaveStatus.NOT_SUBMITTED)
            throw cancelled
        } catch (_: Exception) {
            if (state.value.status == PlanSaveStatus.SUBMITTING) mutableState.value = state.value.copy(
                status = if (dispatched) PlanSaveStatus.AWAITING_CONFIRMATION else PlanSaveStatus.NOT_SUBMITTED,
                error = if (dispatched) null else RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE))
            // Unexpected adapter errors after dispatch remain unknown; no raw error text reaches the UI.
        } finally { lock.unlock() }
    }

    /** Reconcile only the original unknown save or confirmed cache update, never create or replay a rejected intent. */
    suspend fun reconcile() {
        if (!lock.tryLock()) return
        try {
            val current = state.value
            val command = context ?: return
            if (current.status != PlanSaveStatus.AWAITING_CONFIRMATION &&
                !(current.status == PlanSaveStatus.CONFIRMED && current.cacheState == CacheState.REFRESH_REQUIRED)) return
            when (val outcome = commands.resume(command.operationId)) {
                is Outcome.Failure -> mutableState.value = current.copy(error = outcome.error)
                is Outcome.Success -> {
                    val record = outcome.value
                    require(record.operationId == command.operationId && record.scope == scope &&
                        record.commandKind == command.commandKind && record.targetId == elderId)
                    // A confirmed service write cannot be downgraded by stale recovery data.
                    if (current.status == PlanSaveStatus.CONFIRMED && record.state != OnlineCommandState.CONFIRMED) return
                    mutableState.value = current.copy(
                        status = when (record.state) {
                            OnlineCommandState.CONFIRMED -> PlanSaveStatus.CONFIRMED
                            OnlineCommandState.AWAITING_CONFIRMATION -> PlanSaveStatus.AWAITING_CONFIRMATION
                            OnlineCommandState.REJECTED -> PlanSaveStatus.REJECTED
                            OnlineCommandState.NOT_SUBMITTED -> PlanSaveStatus.NOT_SUBMITTED
                        },
                        cacheState = record.cacheState, error = record.error,
                    )
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // Retain the known status and original ID when recovery cannot be trusted.
        } finally { lock.unlock() }
    }

    private fun validAcknowledgement(zone: String, ack: TimeZoneAcknowledgement): Boolean {
        if (ack.executionTimeZoneId != zone) return false
        val known = ZoneId.getAvailableZoneIds()
        val matched = ack.caregiverTimeZoneId in known && ack.observedElderTimeZoneId in known &&
            ack.caregiverTimeZoneId == zone && ack.observedElderTimeZoneId == zone
        return matched || ack.mismatchAcknowledged
    }
}
