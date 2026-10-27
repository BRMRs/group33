package com.group33.server.plan

import com.group33.server.photo.PhotoMetadataStore
import com.group33.server.photo.PhotoMetadataTransaction
import com.group33.shared.contract.*
import java.util.Collections
import java.util.UUID

internal data class PlanConfirmationKey(val actorId: String, val operationId: UUID)
internal class PlanConfirmationOperation(val installationId: String, val elderId: String, val fingerprint: String,
    val receipt: ConfirmReminderPlanResponse? = null, val rejection: PlanConfirmationResult.Rejected? = null) {
    init { require((receipt == null) != (rejection == null)) }
    override fun toString() = "PlanConfirmationOperation(REDACTED)"
}

/** First-confirmation seam only; current relation, ready photo, resources, revision and receipt share one guard. */
internal interface PlanConfirmationStore : PhotoMetadataStore {
    fun <T> confirmationTransaction(work: (PlanConfirmationTransaction) -> T): T
}
internal interface PlanConfirmationTransaction : PhotoMetadataTransaction {
    fun confirmationOperation(key: PlanConfirmationKey): PlanConfirmationOperation?
    fun putConfirmationOperation(key: PlanConfirmationKey, value: PlanConfirmationOperation)
    fun insertMedication(value: MedicationView)
    fun insertPlan(value: ReminderPlanView)
    fun insertRevision(value: PlanRevisionView)
    fun medications(elderId: String): List<MedicationView>
    fun plans(elderId: String): List<ReminderPlanView>
    fun revisions(elderId: String): List<PlanRevisionView>
}

/** Shared wire DTOs contain List, so the process-local store must detach and freeze caller-owned lists. */
internal object PlanConfirmationCopies {
    fun plan(value: ReminderPlanView) = value.copy(localTimes = Collections.unmodifiableList(value.localTimes.toList()))
    fun revision(value: PlanRevisionView) = value.copy(localTimes = Collections.unmodifiableList(value.localTimes.toList()))
    fun receipt(value: ConfirmReminderPlanResponse) = value.copy(plan = plan(value.plan))
    fun operation(value: PlanConfirmationOperation) = PlanConfirmationOperation(value.installationId, value.elderId,
        value.fingerprint, value.receipt?.let(::receipt), value.rejection?.copy(violations = value.rejection.violations.toList()))
    fun initialRevision(response: ConfirmReminderPlanResponse): PlanRevisionView {
        val p = response.plan; val m = response.medication
        return PlanRevisionView(p.id, p.elderId, p.medicationId, p.revision, p.localTimes.toList(), p.startDate,
            p.executionTimeZoneId, p.active, p.effectiveFromEpochMillis, p.confirmedAtEpochMillis,
            MedicationText(m.displayName, m.dosageText, m.instructions), p.endDate)
    }
}
