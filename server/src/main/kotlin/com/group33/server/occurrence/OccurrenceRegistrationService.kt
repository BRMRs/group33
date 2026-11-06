package com.group33.server.occurrence

import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.CodeStoreBusy
import com.group33.server.plan.PlanSelfOverlapValidator
import com.group33.shared.contract.*
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.UserRole
import java.time.*
import java.util.UUID

internal enum class RegistrationRejection {
    RESOURCE_NOT_ACCESSIBLE, ROLE_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED, VALIDATION_FAILED, OPERATION_IN_PROGRESS, SERVICE_UNAVAILABLE,
}
internal data class RegistrationViolation(val field: String, val reason: String)
internal sealed interface RegistrationResult {
    class Accepted(val response: OccurrenceRegistrationResponse) : RegistrationResult {
        override fun toString() = "RegistrationAccepted(REDACTED)"
    }
    data class Rejected(val reason: RegistrationRejection, val violations: List<RegistrationViolation> = emptyList()) : RegistrationResult
}

/** Existing r1 DTO preparation only. Trusted current actors enter here; no HTTP/Bean or V2 cutover is registered. */
internal class OccurrenceRegistrationService(private val store: OccurrenceRegistrationStore,
    private val clock: Clock = Clock.systemUTC(), private val newId: () -> UUID = UUID::randomUUID,
    private val resolve: (LocalDateTime, ZoneId) -> Instant = PlanSelfOverlapValidator::resolve) {
    fun register(actor: AnonymousUser, elderId: String, operationId: UUID, input: OccurrenceRegistrationRequest): RegistrationResult {
        if (actor.id != elderId) return rejected(RegistrationRejection.RESOURCE_NOT_ACCESSIBLE)
        if (actor.role != UserRole.ELDER) return rejected(RegistrationRejection.ROLE_NOT_ALLOWED)
        val request = RegistrationCopies.request(input)
        val binding = RegistrationBinding(actor.installationId, elderId, request)
        val key = RegistrationKey(actor.id, operationId)
        return try {
            store.registrationTransaction { tx ->
                val previous = tx.registrationOperation(key)
                if (previous != null && !previous.binding.matches(binding))
                    return@registrationTransaction rejected(RegistrationRejection.IDEMPOTENCY_KEY_REUSED)
                previous?.response?.let { return@registrationTransaction RegistrationResult.Accepted(RegistrationCopies.response(it)) }
                // Check every plan's owner before exposing validation details or storing a rejected binding.
                val plans = request.items.map { evidence ->
                    val id = uuid(evidence.occurrence.planId)?.toString()
                    val plan = id?.let(tx::plan)
                    if (plan == null || plan.elderId != elderId || plan.id != evidence.occurrence.planId)
                        return@registrationTransaction rejected(RegistrationRejection.RESOURCE_NOT_ACCESSIBLE)
                    plan
                }
                fun reject(violations: List<RegistrationViolation>): RegistrationResult.Rejected {
                    val result = RegistrationResult.Rejected(RegistrationRejection.VALIDATION_FAILED, violations)
                    tx.putRegistrationOperation(key, RegistrationOperation(binding, rejection = result))
                    return result
                }
                val violations = mutableListOf<RegistrationViolation>()
                if (request.items.isEmpty()) violations += RegistrationViolation("items", "REQUIRED")
                if (request.items.map { it.generationId }.distinct().size != request.items.size)
                    violations += RegistrationViolation("items", "DUPLICATE_GENERATION_ID")
                if (request.items.map { it.occurrence }.distinct().size != request.items.size)
                    violations += RegistrationViolation("items", "DUPLICATE_OCCURRENCE")
                val revisions = tx.revisions(elderId)
                val registered = request.items.map { evidence ->
                    uuid(evidence.generationId)?.let { tx.generation(actor.id, it) } ?: tx.occurrence(evidence.occurrence)
                }
                request.items.forEachIndexed { index, evidence ->
                    val existing = registered[index]
                    if (existing != null) {
                        if (existing.installationId != actor.installationId || existing.receipt.event.elderId != elderId ||
                            existing.receipt.event.generationEvidence != evidence)
                            violations += RegistrationViolation("items[$index].generationEvidence", "CONTRADICTORY_GENERATION_EVIDENCE")
                        // An accepted generation's frozen instant is not reinterpreted under a later TZDB.
                    } else {
                        val revision = revisions.singleOrNull { it.id == plans[index].id && it.revision == evidence.planRevision }
                        violations += validate(evidence, revision).map { RegistrationViolation("items[$index]." + it.field, it.reason) }
                    }
                }
                if (violations.isNotEmpty()) return@registrationTransaction reject(violations)
                val receivedAt = clock.millis()
                val pending = mutableListOf<RegisteredOccurrence>()
                val receipts = request.items.mapIndexed { index, evidence ->
                    val existing = registered[index]
                    if (existing != null) {
                        RegistrationCopies.receipt(existing.receipt)
                    } else {
                        val revision = revisions.single { it.id == evidence.occurrence.planId && it.revision == evidence.planRevision }
                        val deadline = Math.addExact(evidence.plannedAtEpochMillis, RESPONSE_WINDOW)
                        val status = when { receivedAt < evidence.plannedAtEpochMillis -> DoseStatus.SCHEDULED
                            receivedAt < deadline -> DoseStatus.DUE; else -> DoseStatus.MISSED }
                        val event = DoseEventView(newId().toString(), elderId, evidence.occurrence, evidence.planRevision,
                            revision.medicationId, revision.medicationSnapshot, evidence.executionTimeZoneId, evidence.plannedAtEpochMillis,
                            deadline, evidence, status, emptyList(), receivedAt)
                        val receipt = OccurrenceRegistrationReceipt(evidence.generationId, receivedAt, event)
                        pending += RegisteredOccurrence(actor.installationId, key, receipt)
                        receipt
                    }
                }
                // No business writes until the complete batch has validated, including contradictions.
                pending.forEach(tx::insertOccurrence)
                val response = OccurrenceRegistrationResponse(receipts)
                tx.putRegistrationOperation(key, RegistrationOperation(binding, response = response))
                RegistrationResult.Accepted(RegistrationCopies.response(response))
            }
        } catch (_: CodeStoreBusy) { rejected(RegistrationRejection.OPERATION_IN_PROGRESS) }
          catch (failure: Exception) {
              if (failure is InterruptedException) Thread.currentThread().interrupt()
              rejected(RegistrationRejection.SERVICE_UNAVAILABLE)
          }
    }

    private fun validate(e: OccurrenceGenerationEvidence, revision: PlanRevisionView?): List<RegistrationViolation> = buildList {
        fun invalid(field: String, reason: String) { add(RegistrationViolation(field, reason)) }
        if (uuid(e.generationId)?.toString() != e.generationId) invalid("generationId", "UUID_REQUIRED")
        if (e.planRevision <= 0 || revision == null) invalid("planRevision", "CONFIRMED_REVISION_REQUIRED")
        if (e.generatedAtEpochMillis < 0) invalid("generatedAtEpochMillis", "INVALID_TIMESTAMP")
        if (e.sourceSnapshotVersion.isBlank()) invalid("sourceSnapshotVersion", "REQUIRED")
        if (e.sourceSnapshotServerAtEpochMillis < 0) invalid("sourceSnapshotServerAtEpochMillis", "INVALID_TIMESTAMP")
        if (revision == null) return@buildList
        if (!revision.active) invalid("planRevision", "INACTIVE_CONFIRMED_REVISION")
        if (revision.executionTimeZoneId != e.executionTimeZoneId) invalid("executionTimeZoneId", "CONFIRMED_ZONE_MISMATCH")
        try {
            require(e.occurrence.localDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            require(e.occurrence.localTime.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
            val date = LocalDate.parse(e.occurrence.localDate); val time = LocalTime.parse(e.occurrence.localTime)
            if (date < LocalDate.parse(revision.startDate) || revision.endDate?.let { date > LocalDate.parse(it) } == true)
                invalid("occurrence.localDate", "OUTSIDE_CONFIRMED_RANGE")
            if (e.occurrence.localTime !in revision.localTimes) invalid("occurrence.localTime", "NOT_IN_CONFIRMED_PLAN")
            val zone = ZoneId.of(revision.executionTimeZoneId)
            val instant = resolve(date.atTime(time), zone)
            if (instant.toEpochMilli() != e.plannedAtEpochMillis || instant.atZone(zone).offset.totalSeconds != e.resolvedOffsetSeconds)
                invalid("plannedAtEpochMillis", "FROZEN_TIME_CONFLICT")
            Math.addExact(e.plannedAtEpochMillis, RESPONSE_WINDOW)
        } catch (_: IllegalArgumentException) { invalid("occurrence", "INVALID_LOCAL_DATE_TIME") }
          catch (_: DateTimeException) { invalid("occurrence", "INVALID_LOCAL_DATE_TIME") }
          catch (_: ArithmeticException) { invalid("plannedAtEpochMillis", "INVALID_TIMESTAMP") }
        // Source context is retained evidence, never an authorization token or tamper-proof clock.
    }
    private fun rejected(reason: RegistrationRejection) = RegistrationResult.Rejected(reason)
    private fun uuid(text: String): UUID? = try { UUID.fromString(text).takeIf { it.toString().equals(text, true) } } catch (_: IllegalArgumentException) { null }
    private companion object { const val RESPONSE_WINDOW = 7_200_000L }
}
