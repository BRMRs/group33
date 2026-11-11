package com.group33.server.plan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.CodeStoreBusy
import com.group33.server.pairing.PairingCodeIssuanceService
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

internal enum class PlanMutationRejection {
    RESOURCE_NOT_ACCESSIBLE, ROLE_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED, PLAN_VERSION_CONFLICT,
    VALIDATION_FAILED, OVERLAPPING_PLAN, OPERATION_IN_PROGRESS, SERVICE_UNAVAILABLE,
}
internal sealed interface PlanMutationResult {
    class Accepted(val view: UpdateReminderPlanResponse) : PlanMutationResult { override fun toString() = "PlanMutationAccepted(REDACTED)" }
    data class Rejected(val reason: PlanMutationRejection, val violations: List<ConfirmationViolation> = emptyList()) : PlanMutationResult
}

/** Current authorization, original receipt, expected revision and prospective version share one guard. */
internal class ReminderPlanMutationService(private val store: PlanMutationStore, private val clock: Clock = Clock.systemUTC()) {
    fun update(actor: AnonymousUser, planId: String, operationId: UUID, request: UpdateReminderPlanRequest): PlanMutationResult {
        val input = request.copy(plan = request.plan.copy(localTimes = request.plan.localTimes.toList()))
        val canonical = input.copy(plan = input.plan.copy(localTimes = input.plan.localTimes.sorted()))
        return mutate(actor, planId, operationId, PlanMutationAction.UPDATE, input.expectedRevision, canonical) { tx, old, medication ->
            val violations = validate(input)
            if (violations.isNotEmpty()) return@mutate PlanMutationResult.Rejected(PlanMutationRejection.VALIDATION_FAILED, violations)
            if (old.active && (PlanSelfOverlapValidator.hasCollision(input.plan) || tx.plans(old.elderId).any {
                    it.id != old.id && it.elderId == old.elderId && it.active && it.medicationId == old.medicationId &&
                        PlanCrossOverlapValidator.hasCollision(input.plan, ReminderPlanDraft(it.localTimes, it.startDate, it.executionTimeZoneId, it.endDate))
                })) return@mutate PlanMutationResult.Rejected(PlanMutationRejection.OVERLAPPING_PLAN,
                    listOf(ConfirmationViolation("plan.localTimes", "DUPLICATE_RESOLVED_INSTANT")))
            val text = input.medication
            val updatedMedication = text?.let { medication.copy(displayName = it.displayName, dosageText = it.dosageText, instructions = it.instructions) } ?: medication
            val now = clock.millis()
            val plan = old.copy(revision = old.revision + 1, localTimes = canonical.plan.localTimes, startDate = input.plan.startDate,
                endDate = input.plan.endDate, executionTimeZoneId = input.plan.executionTimeZoneId,
                effectiveFromEpochMillis = now, confirmedAtEpochMillis = now)
            tx.updateMedication(updatedMedication)
            tx.updatePlan(plan, old.revision)
            tx.insertRevision(PlanConfirmationCopies.initialRevision(ConfirmReminderPlanResponse(updatedMedication, plan)))
            PlanMutationResult.Accepted(UpdateReminderPlanResponse(updatedMedication, PlanConfirmationCopies.plan(plan)))
        }
    }

    fun deactivate(actor: AnonymousUser, planId: String, operationId: UUID, request: DeactivateReminderPlanRequest): PlanMutationResult =
        mutate(actor, planId, operationId, PlanMutationAction.DEACTIVATE, request.expectedRevision, request) { tx, old, medication ->
            val now = clock.millis()
            val plan = old.copy(revision = old.revision + 1, active = false, effectiveFromEpochMillis = now, confirmedAtEpochMillis = now)
            tx.updatePlan(plan, old.revision)
            tx.insertRevision(PlanConfirmationCopies.initialRevision(ConfirmReminderPlanResponse(medication, plan)))
            PlanMutationResult.Accepted(UpdateReminderPlanResponse(medication, PlanConfirmationCopies.plan(plan)))
        }

    private fun mutate(actor: AnonymousUser, planId: String, id: UUID, action: PlanMutationAction, expected: Long, payload: Any,
        work: (PlanMutationTransaction, ReminderPlanView, MedicationView) -> PlanMutationResult): PlanMutationResult = try {
        store.mutationTransaction { tx ->
            val canonicalId = try { UUID.fromString(planId).toString().takeIf { it.equals(planId, ignoreCase = true) } } catch (_: IllegalArgumentException) { null }
            val old = canonicalId?.let(tx::plan) ?: return@mutationTransaction reject(PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
            if (actor.role != UserRole.CAREGIVER) return@mutationTransaction reject(
                if (actor.id == old.elderId) PlanMutationRejection.ROLE_NOT_ALLOWED else PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
            if (tx.activeRelationship(old.elderId)?.caregiverId != actor.id)
                return@mutationTransaction reject(PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
            val fingerprint = PairingCodeIssuanceService.hash("${ContractProtocol.CANONICALIZATION_VERSION}:$action:${old.id}:" + mapper.writeValueAsString(payload))
            val key = PlanMutationKey(actor.id, action, id)
            val previous = tx.mutationOperation(key)
            if (previous != null && (previous.installationId != actor.installationId || previous.planId != old.id ||
                    previous.elderId != old.elderId || previous.fingerprint != fingerprint))
                return@mutationTransaction reject(PlanMutationRejection.IDEMPOTENCY_KEY_REUSED)
            previous?.receipt?.let { return@mutationTransaction PlanMutationResult.Accepted(it) }
            val result = when {
                expected <= 0 -> PlanMutationResult.Rejected(PlanMutationRejection.VALIDATION_FAILED,
                    listOf(ConfirmationViolation("expectedRevision", "POSITIVE_REVISION_REQUIRED")))
                expected != old.revision -> reject(PlanMutationRejection.PLAN_VERSION_CONFLICT)
                old.revision == Long.MAX_VALUE -> PlanMutationResult.Rejected(PlanMutationRejection.VALIDATION_FAILED,
                    listOf(ConfirmationViolation("expectedRevision", "REVISION_LIMIT_REACHED")))
                else -> work(tx, old, checkNotNull(tx.medication(old.medicationId)))
            }
            tx.putMutationOperation(key, PlanMutationOperation(actor.installationId, old.elderId, old.id, fingerprint,
                (result as? PlanMutationResult.Accepted)?.view, result as? PlanMutationResult.Rejected))
            result
        }
    } catch (_: CodeStoreBusy) { reject(PlanMutationRejection.OPERATION_IN_PROGRESS) }
      catch (failure: Exception) {
          if (failure is InterruptedException) Thread.currentThread().interrupt()
          reject(PlanMutationRejection.SERVICE_UNAVAILABLE)
      }

    private fun reject(reason: PlanMutationRejection) = PlanMutationResult.Rejected(reason)
    private fun validate(input: UpdateReminderPlanRequest): List<ConfirmationViolation> = buildList {
        input.medication?.let {
            if (it.displayName.isBlank()) add(ConfirmationViolation("medication.displayName", "REQUIRED"))
            if (it.dosageText.isBlank()) add(ConfirmationViolation("medication.dosageText", "REQUIRED"))
        }
        addAll(ReminderPlanInputValidator.validate(input.plan).map { ConfirmationViolation(it.field, it.failure.name) })
        val ack = input.timeZoneAcknowledgement
        if (ack.executionTimeZoneId != input.plan.executionTimeZoneId)
            add(ConfirmationViolation("timeZoneAcknowledgement.executionTimeZoneId", "EXECUTION_ZONE_MISMATCH"))
        for ((name, zone) in listOf("caregiverTimeZoneId" to ack.caregiverTimeZoneId, "observedElderTimeZoneId" to ack.observedElderTimeZoneId))
            if (zone != null && zone !in ZoneId.getAvailableZoneIds()) add(ConfirmationViolation("timeZoneAcknowledgement.$name", "INVALID_TIME_ZONE"))
        if ((ack.caregiverTimeZoneId != input.plan.executionTimeZoneId || ack.observedElderTimeZoneId != input.plan.executionTimeZoneId) && !ack.mismatchAcknowledged)
            add(ConfirmationViolation("timeZoneAcknowledgement.mismatchAcknowledged", "ACKNOWLEDGEMENT_REQUIRED"))
    }
    private companion object { val mapper = jacksonObjectMapper() }
}
