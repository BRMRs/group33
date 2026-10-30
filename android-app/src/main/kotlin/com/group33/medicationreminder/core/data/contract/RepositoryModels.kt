package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.DoseStatus
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID

/** Scope is supplied by the app/session adapter, never a user-selected HTTP principal. */
data class RepositoryScope(val environmentId: String, val installationId: String, val userId: String) {
    init { require(listOf(environmentId, installationId, userId).all { it.isNotBlank() }) }
}

enum class AccessState { AUTHORIZED, UNKNOWN, REVOKED }
enum class SyncState { PENDING_SYNC, SYNCED, NEEDS_ATTENTION }
enum class CacheState { APPLIED, REFRESH_REQUIRED }
enum class OnlineCommandState { NOT_SUBMITTED, AWAITING_CONFIRMATION, CONFIRMED, REJECTED }
enum class LocalFailureCode { OFFLINE, STORAGE_UNAVAILABLE, SOURCE_UNAVAILABLE, SESSION_NOT_READY, INVALID_INPUT }

sealed interface RepositoryError {
    data class Remote(val error: ApiError) : RepositoryError
    data class Local(val code: LocalFailureCode) : RepositoryError
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: RepositoryError) : Outcome<Nothing>
}

data class Cached<T>(
    val value: T,
    val hasLocalData: Boolean,
    val lastSuccessfulSyncAtEpochMillis: Long?,
    val isStale: Boolean,
    val access: AccessState,
)

enum class AuthReadiness { SIGNED_OUT, RESTORING, READY }
data class SessionView(val readiness: AuthReadiness, val user: UserView?)

data class LocalDateRange(val fromDate: String, val throughDate: String) {
    init { require(!LocalDate.parse(fromDate).isAfter(LocalDate.parse(throughDate))) }
}

enum class LanguageChoice { FOLLOW_SYSTEM, ENGLISH, SIMPLIFIED_CHINESE }
enum class LocalActionClassification { TIMELY_TAKEN, LATE_TAKEN, REMIND_LATER }

data class LocalActionReceipt(
    val actionId: String,
    val occurrence: OccurrenceKey,
    val kind: DoseActionKind,
    val classification: LocalActionClassification,
    val recordedAtEpochMillis: Long,
    val syncState: SyncState,
)

/** Late-taken evidence already accepted by the service and returned by a read. */
data class ServerAcceptedLateTakenReport(
    val actionId: String,
    val recordedAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
)

data class DoseEventState(
    val occurrence: OccurrenceKey,
    val serverEventId: String?,
    val generationEvidence: OccurrenceGenerationEvidence,
    val medicationId: String,
    val medicationSnapshot: MedicationText,
    val responseDeadlineEpochMillis: Long,
    val effectiveStatus: DoseStatus,
    val lastAcceptedStatus: DoseStatus?,
    val localReports: List<LocalActionReceipt>,
    val serverAcceptedLateTakenReports: List<ServerAcceptedLateTakenReport> = emptyList(),
)

/** A calculated candidate has no canonical event ID, status or action history. */
data class PlanPreview(
    val occurrence: OccurrenceKey,
    val planRevision: Long,
    val executionTimeZoneId: String,
    val plannedAtEpochMillis: Long,
)

sealed interface PairingDecisionResult {
    val request: PairingRequestView
    data class Accepted(override val request: PairingRequestView, val relationship: CareRelationshipView) : PairingDecisionResult
    data class Rejected(override val request: PairingRequestView) : PairingDecisionResult
}

enum class OnlineCommandKind {
    ISSUE_PAIRING_CODE, CANCEL_PAIRING_CODE, REQUEST_PAIRING, RESPOND_PAIRING,
    CANCEL_PAIRING_REQUEST, REVOKE_RELATIONSHIP, SUBMIT_PHOTO, CONFIRM_PLAN, UPDATE_PLAN, DEACTIVATE_PLAN,
}

data class CommandContext(
    val operationId: String,
    val scope: RepositoryScope,
    val commandKind: OnlineCommandKind,
    val targetId: String?,
    val canonicalizationVersion: String = ContractProtocol.CANONICALIZATION_VERSION,
) {
    init {
        require(UUID.fromString(operationId).toString() == operationId) { "operationId must be a canonical UUID" }
        require(canonicalizationVersion == ContractProtocol.CANONICALIZATION_VERSION)
    }
}

sealed interface OnlineCommandResult<out T> {
    val operationId: String
    val status: OnlineCommandState
    data class Confirmed<T>(override val operationId: String, val value: T, val cacheState: CacheState) : OnlineCommandResult<T> {
        override val status = OnlineCommandState.CONFIRMED
    }
    data class AwaitingConfirmation(override val operationId: String) : OnlineCommandResult<Nothing> {
        override val status = OnlineCommandState.AWAITING_CONFIRMATION
    }
    data class Rejected(override val operationId: String, val error: RepositoryError.Remote) : OnlineCommandResult<Nothing> {
        override val status = OnlineCommandState.REJECTED
    }
    data class NotSubmitted(override val operationId: String, val error: RepositoryError.Local) : OnlineCommandResult<Nothing> {
        override val status = OnlineCommandState.NOT_SUBMITTED
    }
}

enum class CommandResultKind {
    USER, PAIRING_CODE, PAIRING_REQUEST, CARE_RELATIONSHIP, PHOTO, MEDICATION, REMINDER_PLAN, DOSE_EVENT, CAPABILITY,
}
data class CommandResultRef(val kind: CommandResultKind, val id: String)
data class OnlineCommandRecord(
    val operationId: String,
    val scope: RepositoryScope,
    val commandKind: OnlineCommandKind,
    val targetId: String?,
    val state: OnlineCommandState,
    val resultRef: CommandResultRef?,
    val cacheState: CacheState?,
    val error: RepositoryError?,
)

data class RetryQueued(val actionId: String)
data class CapabilityQueued(val reportId: String, val observationSequence: Long)
enum class SyncCompletion { COMPLETE, PARTIAL, FAILED }
data class SyncSummary(
    val scope: RepositoryScope,
    val elderId: String,
    val completion: SyncCompletion,
    val lastSuccessfulSyncAtEpochMillis: Long?,
    val error: RepositoryError?,
)

/** Adapter must own a private durable source; opening is repeatable until the intent resolves. */
interface PhotoSource {
    val contentType: String
    val byteCount: Long
    val sha256: String
    fun openStream(): InputStream
}

/** Each returned stream is owned/closed by the caller, not a public permanent URL. */
interface PhotoContent {
    val contentType: String
    fun openStream(): InputStream
}

data class ElderOverview(
    val elder: UserView,
    val medications: List<MedicationView>,
    val events: List<DoseEventState>,
    val capability: ReminderCapabilityView?,
)
