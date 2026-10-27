package com.group33.server.plan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.CodeStoreBusy
import com.group33.server.pairing.PairingCodeIssuanceService
import com.group33.server.photo.storage.PhotoObjectStore
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

internal enum class PlanConfirmationRejection {
    RESOURCE_NOT_ACCESSIBLE, ROLE_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED, VALIDATION_FAILED, OVERLAPPING_PLAN,
    OPERATION_IN_PROGRESS, SERVICE_UNAVAILABLE,
}
internal data class ConfirmationViolation(val field: String, val code: String)
internal sealed interface PlanConfirmationResult {
    class Confirmed(val view: ConfirmReminderPlanResponse) : PlanConfirmationResult { override fun toString() = "PlanConfirmed(REDACTED)" }
    class Medications(val items: List<MedicationView>) : PlanConfirmationResult { override fun toString() = "Medications(REDACTED)" }
    class Plans(val value: ReminderPlanCollection) : PlanConfirmationResult { override fun toString() = "Plans(REDACTED)" }
    data class Rejected(val reason: PlanConfirmationRejection, val violations: List<ConfirmationViolation> = emptyList()) : PlanConfirmationResult
}

/** Plain local application candidate. No HTTP bean or PostgreSQL plan adapter is registered in this slice. */
internal class ReminderPlanConfirmationService(private val store: PlanConfirmationStore, private val objects: PhotoObjectStore,
    private val clock: Clock = Clock.systemUTC()) {
    fun confirm(actor: AnonymousUser, elderId: String, operationId: UUID, request: ConfirmReminderPlanRequest): PlanConfirmationResult {
        // Detach the decoded collection before validation, hashing, or waiting for the transaction.
        val input = request.copy(plan = request.plan.copy(localTimes = request.plan.localTimes.toList()))
        return safely {
            store.confirmationTransaction { tx ->
                if (actor.role != UserRole.CAREGIVER) return@confirmationTransaction (
                    if (actor.id == elderId) rejected(PlanConfirmationRejection.ROLE_NOT_ALLOWED) else inaccessible())
                if (tx.activeRelationship(elderId)?.caregiverId != actor.id) return@confirmationTransaction inaccessible()
                val key = PlanConfirmationKey(actor.id, operationId)
                val canonical = input.copy(plan = input.plan.copy(localTimes = input.plan.localTimes.sorted()))
                val fingerprint = PairingCodeIssuanceService.hash("${ContractProtocol.CANONICALIZATION_VERSION}:CONFIRM_REMINDER_PLAN:$elderId:" +
                    mapper.writeValueAsString(canonical))
                val old = tx.confirmationOperation(key)
                if (old != null && (old.installationId != actor.installationId || old.elderId != elderId || old.fingerprint != fingerprint))
                    return@confirmationTransaction rejected(PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED)
                old?.receipt?.let { return@confirmationTransaction PlanConfirmationResult.Confirmed(it) }
                fun reject(reason: PlanConfirmationRejection, fields: List<ConfirmationViolation> = emptyList()): PlanConfirmationResult.Rejected {
                    val failure = PlanConfirmationResult.Rejected(reason, fields)
                    tx.putConfirmationOperation(key, PlanConfirmationOperation(actor.installationId, elderId, fingerprint, rejection = failure))
                    return failure
                }
                val violations = validate(input)
                if (violations.isNotEmpty()) return@confirmationTransaction reject(PlanConfirmationRejection.VALIDATION_FAILED, violations)
                val photo = tx.readyPhoto(input.photoId)
                // Missing, unready and another Elder's private photo have the same non-disclosing failure.
                if (photo == null || photo.view.elderId != elderId) return@confirmationTransaction reject(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
                if (PlanSelfOverlapValidator.hasCollision(input.plan)) return@confirmationTransaction reject(PlanConfirmationRejection.OVERLAPPING_PLAN,
                    listOf(ConfirmationViolation("plan.localTimes", "DUPLICATE_RESOLVED_INSTANT")))
                // Keep the complete request binding even when an otherwise READY private object is
                // temporarily unavailable. Explicit retry may re-evaluate the same original input.
                try { objects.read(photo.objectKey) }
                catch (failure: Exception) {
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    return@confirmationTransaction reject(PlanConfirmationRejection.SERVICE_UNAVAILABLE)
                }
                val now = clock.millis()
                val medication = MedicationView(UUID.randomUUID().toString(), elderId, input.medication.displayName,
                    input.medication.dosageText, true, input.medication.instructions, input.photoId)
                val draft = input.plan
                val plan = ReminderPlanView(UUID.randomUUID().toString(), elderId, medication.id, 1L,
                    canonical.plan.localTimes, draft.startDate, draft.executionTimeZoneId, true, now, now, draft.endDate)
                val receipt = PlanConfirmationCopies.receipt(ConfirmReminderPlanResponse(medication, plan))
                tx.insertMedication(medication)
                tx.insertPlan(plan)
                tx.insertRevision(PlanConfirmationCopies.initialRevision(receipt))
                tx.putConfirmationOperation(key, PlanConfirmationOperation(actor.installationId, elderId, fingerprint, receipt = receipt))
                PlanConfirmationResult.Confirmed(receipt)
            }
        }
    }

    fun medications(actor: AnonymousUser, elderId: String): PlanConfirmationResult = safely {
        store.confirmationTransaction { tx -> if (canRead(tx, actor, elderId)) PlanConfirmationResult.Medications(tx.medications(elderId)) else inaccessible() }
    }
    fun plans(actor: AnonymousUser, elderId: String): PlanConfirmationResult = safely {
        store.confirmationTransaction { tx -> if (canRead(tx, actor, elderId))
            PlanConfirmationResult.Plans(ReminderPlanCollection(tx.plans(elderId), tx.revisions(elderId))) else inaccessible() }
    }
    private fun canRead(tx: PlanConfirmationTransaction, actor: AnonymousUser, elderId: String) =
        actor.role == UserRole.ELDER && actor.id == elderId || actor.role == UserRole.CAREGIVER && tx.activeRelationship(elderId)?.caregiverId == actor.id
    private fun safely(work: () -> PlanConfirmationResult): PlanConfirmationResult = try { work() }
        catch (_: CodeStoreBusy) { rejected(PlanConfirmationRejection.OPERATION_IN_PROGRESS) }
        catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            rejected(PlanConfirmationRejection.SERVICE_UNAVAILABLE)
        }
    private fun inaccessible() = rejected(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
    private fun rejected(reason: PlanConfirmationRejection) = PlanConfirmationResult.Rejected(reason)

    private companion object { val mapper = jacksonObjectMapper() }

    private fun validate(input: ConfirmReminderPlanRequest): List<ConfirmationViolation> = buildList {
        if (input.photoId.isBlank()) add(ConfirmationViolation("photoId", "REQUIRED"))
        if (input.medication.displayName.isBlank()) add(ConfirmationViolation("medication.displayName", "REQUIRED"))
        if (input.medication.dosageText.isBlank()) add(ConfirmationViolation("medication.dosageText", "REQUIRED"))
        addAll(ReminderPlanInputValidator.validate(input.plan).map { ConfirmationViolation(it.field, it.failure.name) })
        val ack = input.timeZoneAcknowledgement
        if (ack.executionTimeZoneId != input.plan.executionTimeZoneId) add(ConfirmationViolation("timeZoneAcknowledgement.executionTimeZoneId", "EXECUTION_ZONE_MISMATCH"))
        val knownZones = ZoneId.getAvailableZoneIds()
        for ((field, zone) in listOf("caregiverTimeZoneId" to ack.caregiverTimeZoneId, "observedElderTimeZoneId" to ack.observedElderTimeZoneId)) {
            if (zone != null && zone !in knownZones) add(ConfirmationViolation("timeZoneAcknowledgement.$field", "INVALID_TIME_ZONE"))
        }
        if ((ack.caregiverTimeZoneId != input.plan.executionTimeZoneId || ack.observedElderTimeZoneId != input.plan.executionTimeZoneId)
            && !ack.mismatchAcknowledged) add(ConfirmationViolation("timeZoneAcknowledgement.mismatchAcknowledged", "ACKNOWLEDGEMENT_REQUIRED"))
    }
}
