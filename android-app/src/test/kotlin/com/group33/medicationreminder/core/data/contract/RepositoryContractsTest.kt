package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.DoseStatus
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Interface/model harness only: no assertion here proves a production Room/HTTP adapter. */
class RepositoryContractsTest {
    private val json = ContractProtocol.json
    private val examples = json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json")).bufferedReader().use { it.readText() },
    ).jsonObject
    private fun at(path: String): JsonElement = path.split('.').fold(examples as JsonElement) { node, part -> node.jsonObject.getValue(part) }
    private val scope = RepositoryScope("cloud-demo", "install-1", "caregiver-1")
    private val context = CommandContext(
        "00000000-0000-4000-8000-000000000005", scope, OnlineCommandKind.UPDATE_PLAN, "plan-1",
    )

    @Test fun `W01 Android consumes identical Shared registration action snapshot and error types`() {
        val registration = json.decodeFromJsonElement<OccurrenceRegistrationRequest>(at("occurrenceRegistration.request"))
        val action = json.decodeFromJsonElement<DoseActionRequest>(at("timelyOffline.request"))
        assertEquals(registration.items.single(), action.generationEvidence)
        val snapshot = json.decodeFromJsonElement<ElderSnapshot>(at("photoOnlySnapshot"))
        assertEquals(1, snapshot.photos.size)
        assertTrue(snapshot.medications.isEmpty())
        val error = json.decodeFromJsonElement<ApiError>(at("inProgressResponse.body"))
        assertTrue(error.retryable)
        assertEquals("OPERATION_IN_PROGRESS", error.code)
    }

    @Test fun `R02 R04 interface preserves original command and distinguishes unknown from confirmed cache-pending`() = runTest {
        val prior = json.decodeFromJsonElement<ConfirmReminderPlanResponse>(at("confirmPlanResponse"))
        val accepted = json.decodeFromJsonElement<UpdateReminderPlanResponse>(at("updatePlanResponse"))
        val repository = PlanProbe(prior, OnlineCommandResult.AwaitingConfirmation(context.operationId))
        val throughInterface: ReminderPlanRepository = repository
        val request = UpdateReminderPlanRequest(1, json.decodeFromJsonElement<ConfirmReminderPlanRequest>(at("confirmPlanRequest")).plan,
            json.decodeFromJsonElement<ConfirmReminderPlanRequest>(at("confirmPlanRequest")).timeZoneAcknowledgement)
        val unknown = throughInterface.update("plan-1", request, context)
        assertTrue(unknown is OnlineCommandResult.AwaitingConfirmation)
        assertEquals(context, repository.received.single())
        assertEquals(1L, throughInterface.observePlans("elder-1").first().value.single().revision)
        repository.updateResult = OnlineCommandResult.Confirmed(context.operationId, accepted, CacheState.REFRESH_REQUIRED)
        val confirmed = throughInterface.update("plan-1", request, context)
        assertTrue(confirmed is OnlineCommandResult.Confirmed)
        confirmed as OnlineCommandResult.Confirmed
        assertEquals(CacheState.REFRESH_REQUIRED, confirmed.cacheState)
        assertEquals(2L, confirmed.value.plan.revision)
        assertEquals(listOf(context, context), repository.received)
        assertEquals(1L, throughInterface.observePlans("elder-1").first().value.single().revision)
    }

    @Test fun `R01 interface observation is explicitly Elder scoped without issuing a network write`() = runTest {
        val prior = json.decodeFromJsonElement<ConfirmReminderPlanResponse>(at("confirmPlanResponse"))
        val repository = PlanProbe(prior, OnlineCommandResult.AwaitingConfirmation(context.operationId))
        val throughInterface: ReminderPlanRepository = repository
        assertEquals("elder-1", throughInterface.observePlans("elder-1").first().value.single().elderId)
        assertTrue(throughInterface.observePlans("elder-2").first().value.isEmpty())
        assertTrue(repository.received.isEmpty())
    }

    @Test fun `R04 command context retains app scope version UUID and does not contain payload or token`() {
        assertEquals(ContractProtocol.CANONICALIZATION_VERSION, context.canonicalizationVersion)
        assertEquals(context.operationId, UUID.fromString(context.operationId).toString())
        assertNotEquals(context, context.copy(scope = scope.copy(environmentId = "local-demo")))
        assertFalse(CommandContext::class.java.declaredFields.any { it.name in setOf("accessToken", "payload", "httpRequest") })
        assertThrows(IllegalArgumentException::class.java) { context.copy(operationId = "invalid") }
    }

    @Test fun `U01 local late report can retain Missed independently of Pending Synced and Needs attention`() {
        val late = json.decodeFromJsonElement<DoseActionReceipt>(at("lateTaken.response"))
        val report = LocalActionReceipt(late.actionId, late.event.occurrence, DoseActionKind.TAKEN,
            LocalActionClassification.LATE_TAKEN, late.event.lateTakenReports.single().recordedAtEpochMillis, SyncState.PENDING_SYNC)
        val state = DoseEventState(late.event.occurrence, late.event.id, late.event.generationEvidence,
            late.event.medicationId, late.event.medicationSnapshot, late.event.responseDeadlineEpochMillis,
            DoseStatus.MISSED, DoseStatus.MISSED, listOf(report))
        for (sync in SyncState.entries) {
            val represented = state.copy(localReports = listOf(report.copy(syncState = sync)))
            assertEquals(DoseStatus.MISSED, represented.effectiveStatus)
            assertEquals(sync, represented.localReports.single().syncState)
        }
        assertFalse(DoseStatus.entries.any { it.name == "PENDING_SYNC" || it.name == "LATE_TAKEN" })
    }

    @Test fun `R02 result variants keep rejection local pre-send failure and unknown distinct`() {
        val error = json.decodeFromJsonElement<ApiError>(at("validationError"))
        val outcomes: List<OnlineCommandResult<Nothing>> = listOf(
            OnlineCommandResult.Rejected(context.operationId, RepositoryError.Remote(error)),
            OnlineCommandResult.NotSubmitted(context.operationId, RepositoryError.Local(LocalFailureCode.OFFLINE)),
            OnlineCommandResult.AwaitingConfirmation(context.operationId),
        )
        assertEquals(listOf(OnlineCommandState.REJECTED, OnlineCommandState.NOT_SUBMITTED, OnlineCommandState.AWAITING_CONFIRMATION), outcomes.map { it.status })
        assertTrue(outcomes.all { it.operationId == context.operationId })
    }

    @Test fun `R01 local date range is inclusive and rejects invalid order or malformed date`() {
        assertEquals(LocalDateRange("2026-10-20", "2026-10-20").fromDate, "2026-10-20")
        assertThrows(IllegalArgumentException::class.java) { LocalDateRange("2026-10-21", "2026-10-20") }
        assertThrows(java.time.format.DateTimeParseException::class.java) { LocalDateRange("not-date", "2026-10-20") }
    }

    @Test fun `R04 photo source interface supports reopening exact bytes without leaking a server URL`() {
        val bytes = "fictional test bytes, not a decodable upload".toByteArray()
        val source: PhotoSource = object : PhotoSource {
            override val contentType = "image/jpeg"
            override val byteCount = bytes.size.toLong()
            override val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            override fun openStream() = ByteArrayInputStream(bytes)
        }
        assertArrayEquals(source.openStream().use { it.readBytes() }, source.openStream().use { it.readBytes() })
        assertEquals(64, source.sha256.length)
        assertEquals(bytes.size.toLong(), source.byteCount)
        assertFalse(PhotoSource::class.java.methods.any { it.name.contains("Url") })
    }

    /** Scripted test probe only. It tests caller-facing types, not cache/network persistence. */
    private class PlanProbe(
        private val prior: ConfirmReminderPlanResponse,
        var updateResult: OnlineCommandResult<UpdateReminderPlanResponse>,
    ) : ReminderPlanRepository {
        val received = mutableListOf<CommandContext>()
        private fun <T> cached(value: T) = Cached(value, true, 1L, false, AccessState.AUTHORIZED)
        override fun observePlans(elderId: String) = flowOf(cached(listOf(prior.plan).filter { it.elderId == elderId }))
        override fun observeMedications(elderId: String) = flowOf(cached(listOf(prior.medication).filter { it.elderId == elderId }))
        override fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>> = flowOf(cached(emptyList()))
        override suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse> {
            received += command
            return updateResult
        }
        override suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse> =
            error("Not exercised by this caller contract test")
        override suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView> =
            error("Not exercised by this caller contract test")
    }
}
