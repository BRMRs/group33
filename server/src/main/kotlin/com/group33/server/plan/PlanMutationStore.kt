package com.group33.server.plan

import com.group33.shared.contract.MedicationView
import com.group33.shared.contract.ReminderPlanView
import com.group33.shared.contract.UpdateReminderPlanResponse
import java.util.UUID

internal enum class PlanMutationAction { UPDATE, DEACTIVATE }
internal data class PlanMutationKey(val actorId: String, val action: PlanMutationAction, val operationId: UUID)
internal class PlanMutationOperation(val installationId: String, val elderId: String, val planId: String,
    val fingerprint: String, val receipt: UpdateReminderPlanResponse? = null, val rejection: PlanMutationResult.Rejected? = null) {
    init { require((receipt == null) != (rejection == null)) }
    override fun toString() = "PlanMutationOperation(REDACTED)"
    fun detached() = PlanMutationOperation(installationId, elderId, planId, fingerprint,
        receipt?.let { it.copy(plan = PlanConfirmationCopies.plan(it.plan)) },
        rejection?.copy(violations = rejection.violations.toList()))
}
internal interface PlanMutationStore : PlanConfirmationStore {
    fun <T> mutationTransaction(work: (PlanMutationTransaction) -> T): T
}
internal interface PlanMutationTransaction : PlanConfirmationTransaction {
    fun plan(id: String): ReminderPlanView?
    fun medication(id: String): MedicationView?
    fun mutationOperation(key: PlanMutationKey): PlanMutationOperation?
    fun putMutationOperation(key: PlanMutationKey, value: PlanMutationOperation)
    fun updateMedication(value: MedicationView)
    fun updatePlan(value: ReminderPlanView, expectedRevision: Long)
}
