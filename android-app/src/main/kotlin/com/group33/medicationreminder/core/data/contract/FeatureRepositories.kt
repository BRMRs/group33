package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import kotlinx.coroutines.flow.Flow

/** Contract declarations only. No fake is registered as a production Room/HTTP adapter. */
interface AuthRepository {
    fun observeSession(): Flow<SessionView>
    suspend fun login(role: UserRole, displayName: String): Outcome<UserView>
}

interface PairingRepository {
    fun observeRequests(): Flow<Cached<List<PairingRequestView>>>
    fun observeRelationships(): Flow<Cached<List<CareRelationshipView>>>
    suspend fun issueCode(command: CommandContext): OnlineCommandResult<PairingCodeView>
    suspend fun cancelCode(codeId: String, command: CommandContext): OnlineCommandResult<Unit>
    suspend fun requestPairing(code: String, command: CommandContext): OnlineCommandResult<PairingRequestView>
    suspend fun respond(requestId: String, accept: Boolean, command: CommandContext): OnlineCommandResult<PairingDecisionResult>
    suspend fun cancelRequest(requestId: String, command: CommandContext): OnlineCommandResult<Unit>
    suspend fun revoke(relationshipId: String, command: CommandContext): OnlineCommandResult<CareRelationshipView>
    suspend fun refresh(): Outcome<Unit>
}

interface MedicationPhotoRepository {
    fun observePhotos(elderId: String): Flow<Cached<List<MedicationPhotoView>>>
    suspend fun submit(source: PhotoSource, command: CommandContext): OnlineCommandResult<MedicationPhotoView>
    suspend fun loadContent(photoId: String): Outcome<PhotoContent>
    suspend fun refresh(elderId: String): Outcome<SyncSummary>
}

interface ReminderPlanRepository {
    fun observePlans(elderId: String): Flow<Cached<List<ReminderPlanView>>>
    fun observeMedications(elderId: String): Flow<Cached<List<MedicationView>>>
    fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>>
    suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse>
    suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse>
    suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView>
}

interface DoseEventRepository {
    fun observeEvents(elderId: String, range: LocalDateRange): Flow<Cached<List<DoseEventState>>>
    fun observeLocalAction(actionId: String): Flow<LocalActionReceipt?>
    /** Success requires local atomic event/report/outbox persistence, not remote acceptance. */
    suspend fun recordAction(occurrence: OccurrenceKey, kind: DoseActionKind): Outcome<LocalActionReceipt>
    suspend fun retryAction(actionId: String): Outcome<RetryQueued>
}

interface CaregiverDashboardRepository {
    fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>>
    suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary>
}

interface ReminderCapabilityRepository {
    fun observeCapability(elderId: String): Flow<Cached<ReminderCapabilityView?>>
    suspend fun reportLocalCapability(): Outcome<CapabilityQueued>
}

interface LanguageSettingsRepository {
    fun observeChoice(): Flow<LanguageChoice>
    suspend fun setChoice(choice: LanguageChoice): Outcome<Unit>
}

interface OnlineCommandRepository {
    suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext>
    fun observeCommands(): Flow<List<OnlineCommandRecord>>
    /** Only unknown completion/cache reconciliation, never auto-submit NOT_SUBMITTED/REJECTED. */
    suspend fun resume(operationId: String): Outcome<OnlineCommandRecord>
    /** Explicit retry after fixing a precondition, same original key/payload, online only. */
    suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord>
}
