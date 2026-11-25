package com.group33.server.occurrence

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.auth.AnonymousUser
import com.group33.server.config.PersistenceRuntime
import com.group33.server.pairing.CodeStoreBusy
import com.group33.server.pairing.VerifiedPairingActorResolver
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.*
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID

/** Occurrence projections and the original operation receipt share the registration transaction. */
internal class DebugDoseGroups(private val store: OccurrenceRegistrationStore, private val clock: Clock) {
    fun submit(actor: AnonymousUser, elderId: String, input: DebugDoseGroupRequest): DebugDoseGroupReceipt {
        if (actor.id != elderId) inaccessible()
        if (actor.role != UserRole.ELDER) fail(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED")
        if (input.occurrences.size !in 1..100 || input.occurrences.distinct().size != input.occurrences.size ||
            input.occurrences.any { canonicalUuid(it.planId) == null })
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED")
        val operationId = canonicalUuid(input.actionId) ?: fail(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST")
        val request = input.copy(occurrences = Collections.unmodifiableList(input.occurrences.toList()))
        return store.registrationTransaction { tx ->
            val key = RegistrationKey(actor.id, operationId)
            tx.doseGroupOperation(key)?.let {
                if (it.installationId != actor.installationId || it.request != request)
                    fail(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
                return@registrationTransaction it.receipt
            }
            val now = clock.millis()
            val group = request.occurrences.map { occurrence ->
                val registered = tx.occurrence(occurrence) ?: inaccessible()
                val event = registered.receipt.event
                if (event.elderId != actor.id || registered.installationId != actor.installationId) inaccessible()
                tx.recordedDoseEvent(event.id) ?: RecordedDoseEvent(event, Long.MIN_VALUE)
            }
            val planned = group.first().event.plannedAtEpochMillis
            val deadline = group.first().event.responseDeadlineEpochMillis
            if (group.any { it.event.plannedAtEpochMillis != planned || it.event.responseDeadlineEpochMillis != deadline } ||
                request.recordedAtEpochMillis !in planned until deadline || request.recordedAtEpochMillis > now)
                fail(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED")
            val applied = group.map { old ->
                val event = old.event
                if (event.status == DoseStatus.TAKEN ||
                    (request.kind == DoseActionKind.REMIND_LATER && request.recordedAtEpochMillis < old.lastRecordedAt)) old
                else {
                    val updated = when (request.kind) {
                        DoseActionKind.TAKEN -> event.copy(status = DoseStatus.TAKEN,
                            takenRecordedAtEpochMillis = request.recordedAtEpochMillis, snoozedUntilEpochMillis = null,
                            serverUpdatedAtEpochMillis = now)
                        DoseActionKind.REMIND_LATER -> {
                            val until = Math.addExact(request.recordedAtEpochMillis, 600_000L)
                            if (until >= deadline) fail(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED")
                            event.copy(status = if (now < until) DoseStatus.SNOOZED
                                else if (now < deadline) DoseStatus.DUE else DoseStatus.MISSED,
                                snoozedUntilEpochMillis = until, serverUpdatedAtEpochMillis = now)
                        }
                    }
                    RecordedDoseEvent(updated, maxOf(old.lastRecordedAt, request.recordedAtEpochMillis))
                }
            }
            val receipt = DebugDoseGroupReceipt(request.actionId, now, Collections.unmodifiableList(applied.map { it.event }))
            applied.zip(group).forEach { (updated, previous) ->
                if (updated !== previous) tx.putRecordedDoseEvent(updated)
            }
            tx.insertDoseGroupOperation(key, DoseGroupOperation(actor.installationId, request, receipt))
            receipt
        }
    }

    fun read(actor: AnonymousUser, elderId: String, from: LocalDate, through: LocalDate): List<DoseEventView> =
        store.registrationTransaction { tx ->
            val authorized = when (actor.role) {
                UserRole.ELDER -> actor.id == elderId
                UserRole.CAREGIVER -> tx.activeRelationship(elderId)?.caregiverId == actor.id
            }
            if (!authorized) inaccessible()
            val now = clock.millis()
            tx.recordedDoseEvents(elderId, from.toString(), through.toString()).map { event ->
                if (event.status == DoseStatus.TAKEN) event
                else if (now >= event.responseDeadlineEpochMillis) event.copy(status = DoseStatus.MISSED)
                else if ((event.snoozedUntilEpochMillis ?: Long.MIN_VALUE) <= now) event.copy(status = DoseStatus.DUE)
                else event
            }.sortedByDescending { it.plannedAtEpochMillis }.toList()
        }

    private fun inaccessible(): Nothing = fail(HttpStatus.NOT_FOUND, "RESOURCE_NOT_ACCESSIBLE")
    private fun fail(status: HttpStatus, code: String): Nothing = throw ApiException(status, code, "Dose group request failed")
}

@RestController
@ConditionalOnProperty(name = ["app.dose-group.enabled"], havingValue = "true")
internal class DebugDoseGroupController(
    private val actors: VerifiedPairingActorResolver,
    runtime: PersistenceRuntime,
    clock: Clock,
    mapper: ObjectMapper,
) {
    private val groups = DebugDoseGroups(checkNotNull(runtime.pairing as? OccurrenceRegistrationStore), clock)

    private val reader = mapper.readerFor(JsonNode::class.java).with(
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    @PostMapping("/api/v1/elders/{elderId}/dose-action-groups", consumes = ["application/json"])
    fun submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
               @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> = reply {
        val actor = actors.resolve(jwt)
        val key = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER)).singleOrNull()
        if (request.parameterMap.isNotEmpty() || body.length > 32_768 ||
            key == null || canonicalUuid(key) == null) malformed()
        val input = decode(body)
        if (input.actionId != key) malformed()
        groups.submit(actor, elderId, input)
    }

    @GetMapping("/api/v1/elders/{elderId}/dose-events")
    fun events(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
               @RequestParam fromDate: String, @RequestParam throughDate: String): ResponseEntity<*> = reply {
        val actor = actors.resolve(jwt)
        val from = date(fromDate)
        val through = date(throughDate)
        if (through < from || ChronoUnit.DAYS.between(from, through) >= 90) malformed()
        ItemsResponse(groups.read(actor, elderId, from, through))
    }

    private fun decode(body: String): DebugDoseGroupRequest {
        val root: JsonNode = try { reader.readValue<JsonNode?>(body) ?: malformed() }
            catch (_: JsonProcessingException) { malformed() }
        if (!root.isObject || root.fieldNames().asSequence().toSet() !=
            setOf("actionId", "occurrences", "kind", "recordedAtEpochMillis")) malformed()
        val kind = DoseActionKind.entries.singleOrNull { it.name == text(root, "kind") } ?: malformed()
        val recordedAt = root["recordedAtEpochMillis"].takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.longValue() ?: malformed()
        val occurrences = root["occurrences"].takeIf { it.isArray && it.size() in 1..100 } ?: malformed()
        return DebugDoseGroupRequest(text(root, "actionId"), occurrences.map { node ->
            if (!node.isObject || node.fieldNames().asSequence().toSet() != setOf("planId", "localDate", "localTime")) malformed()
            OccurrenceKey(text(node, "planId"), text(node, "localDate"), text(node, "localTime"))
        }, kind, recordedAt)
    }

    private fun text(node: JsonNode, field: String) = node[field]?.takeIf { it.isTextual }?.textValue() ?: malformed()

    private fun date(value: String): LocalDate =
        runCatching { LocalDate.parse(value).also { require(it.toString() == value && value.length == 10) } }
            .getOrElse { malformed() }

    private fun reply(block: () -> Any): ResponseEntity<*> = try {
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(block())
    } catch (error: ApiException) {
        failure(error.status, error.code, false)
    } catch (_: CodeStoreBusy) {
        failure(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true)
    }

    private fun failure(status: HttpStatus, code: String, retryable: Boolean): ResponseEntity<*> {
        val error = ApiErrors.body(code, "Dose group request failed", retryable)
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", error.requestId).body(error)
    }

    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed dose group request")
}

private fun canonicalUuid(value: String): UUID? =
    runCatching { UUID.fromString(value).takeIf { it.toString() == value } }.getOrNull()
