package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Debug-only in-memory responses. Never contacts a server. */
    internal class PlanEditorFixture : ReminderPlanRepository, OnlineCommandRepository {
        private val scope = RepositoryScope("debug-only", "test-installation", "test-caregiver")
        val model = PlanSaveViewModel("elder", scope, this, this)
        var begins = 0
        val calls = mutableListOf<CommandContext>()
        val resumes = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var throws = false
        var beginFails = false
        var resultState = OnlineCommandState.AWAITING_CONFIRMATION
        var recoveredState = OnlineCommandState.CONFIRMED
        var recoveredScope = scope
        var cache = CacheState.REFRESH_REQUIRED
        var captured: ConfirmReminderPlanRequest? = null
        override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> {
            begins++
            if (beginFails) return Outcome.Failure(RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE))
            return Outcome.Success(CommandContext("00000000-0000-4000-8000-${begins.toString().padStart(12, '0')}", scope, kind, targetId))
        }
        override suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse> {
            calls += command
            captured = draft
            gate?.await()
            if (throws) error("transport failed after sending")
            return when (resultState) {
                OnlineCommandState.AWAITING_CONFIRMATION -> OnlineCommandResult.AwaitingConfirmation(command.operationId)
                OnlineCommandState.NOT_SUBMITTED -> OnlineCommandResult.NotSubmitted(command.operationId, RepositoryError.Local(LocalFailureCode.OFFLINE))
                OnlineCommandState.REJECTED -> OnlineCommandResult.Rejected(command.operationId,
                    RepositoryError.Remote(com.group33.shared.model.ApiError(message = "Rejected", code = "VALIDATION_FAILED",
                        retryable = false, requestId = "test-request")))
                OnlineCommandState.CONFIRMED -> OnlineCommandResult.Confirmed(command.operationId,
                    ConfirmReminderPlanResponse(MedicationView("med", elderId, "Demo", "Demo dose", true),
                        ReminderPlanView("plan", elderId, "med", 1, draft.plan.localTimes, draft.plan.startDate,
                            draft.plan.executionTimeZoneId, true, 1, 1)), cache)
            }
        }
        override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
            resumes += operationId
            return Outcome.Success(OnlineCommandRecord(operationId, recoveredScope, OnlineCommandKind.CONFIRM_PLAN,
                "elder", recoveredState, null, CacheState.APPLIED, null))
        }
        override fun observeCommands() = flowOf(emptyList<OnlineCommandRecord>())
        override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> = error("Not used")
        override fun observePlans(elderId: String): Flow<Cached<List<ReminderPlanView>>> = error("Not used")
        override fun observeMedications(elderId: String): Flow<Cached<List<MedicationView>>> = error("Not used")
        override fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>> = error("Not used")
        override suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse> = error("Not used")
        override suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView> = error("Not used")
    }

