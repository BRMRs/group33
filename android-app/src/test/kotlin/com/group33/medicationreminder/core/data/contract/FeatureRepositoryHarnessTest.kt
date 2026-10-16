package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** All doubles below are scripted caller harnesses. They prove types, NOT production rules. */
class FeatureRepositoryHarnessTest {
    private val json = ContractProtocol.json
    private val examples = json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json")).bufferedReader().use { it.readText() },
    ).jsonObject
    private inline fun <reified T> fixture(name: String): T = json.decodeFromJsonElement(examples.getValue(name))
    private val scope = RepositoryScope("cloud-demo", "install-1", "caregiver-1")
    private fun context(kind: OnlineCommandKind): CommandContext {
        val elderOnly = kind in setOf(OnlineCommandKind.ISSUE_PAIRING_CODE, OnlineCommandKind.CANCEL_PAIRING_CODE,
            OnlineCommandKind.RESPOND_PAIRING, OnlineCommandKind.SUBMIT_PHOTO)
        val actorScope = if (elderOnly) scope.copy(installationId = "elder-install-1", userId = "elder-1") else scope
        return CommandContext(java.util.UUID.randomUUID().toString(), actorScope, kind, null)
    }
    private fun <T> cached(value: T) = Cached(value, true, 1L, true, AccessState.AUTHORIZED)
    private val sync = SyncSummary(scope, "elder-1", SyncCompletion.COMPLETE, 1L, null)

    @Test fun `C1 Auth caller uses typed identity session and login result without receiving a token`() = runTest {
        val user = fixture<CareRelationshipView>("relationship").caregiver
        val roles = mutableListOf<UserRole>()
        val repository: AuthRepository = object : AuthRepository {
            override fun observeSession() = flowOf(SessionView(AuthReadiness.READY, user))
            override suspend fun login(role: UserRole, displayName: String): Outcome<UserView> {
                roles += role
                return Outcome.Success(user)
            }
        }
        assertEquals(user, (repository.login(UserRole.CAREGIVER, "Demo") as Outcome.Success).value)
        assertEquals(listOf(UserRole.CAREGIVER), roles)
        assertEquals(user.id, repository.observeSession().first().user?.id)
        assertFalse(SessionView::class.java.declaredFields.any { it.name.contains("token", ignoreCase = true) })
    }

    @Test fun `C1 E Pairing callers can express pending accept reject cancellation and revocation`() = runTest {
        val code = fixture<PairingCodeView>("pairingCode")
        val pending = fixture<PairingRequestView>("pendingPairing")
        val relation = fixture<CareRelationshipView>("relationship")
        val repository: PairingRepository = object : PairingRepository {
            override fun observeRequests() = flowOf(cached(listOf(pending)))
            override fun observeRelationships() = flowOf(cached(listOf(relation)))
            override suspend fun issueCode(command: CommandContext) = OnlineCommandResult.Confirmed(command.operationId, code, CacheState.APPLIED)
            override suspend fun cancelCode(codeId: String, command: CommandContext) = OnlineCommandResult.Confirmed(command.operationId, Unit, CacheState.APPLIED)
            override suspend fun requestPairing(code: String, command: CommandContext) = OnlineCommandResult.Confirmed(command.operationId, pending, CacheState.APPLIED)
            override suspend fun respond(requestId: String, accept: Boolean, command: CommandContext): OnlineCommandResult<PairingDecisionResult> {
                val decision = if (accept) PairingDecisionResult.Accepted(pending.copy(status = PairingRequestStatus.ACCEPTED, relationshipId = relation.id), relation)
                    else PairingDecisionResult.Rejected(pending.copy(status = PairingRequestStatus.REJECTED, closedReason = PairingClosedReason.ELDER_REJECTED))
                return OnlineCommandResult.Confirmed(command.operationId, decision, CacheState.APPLIED)
            }
            override suspend fun cancelRequest(requestId: String, command: CommandContext) = OnlineCommandResult.Confirmed(command.operationId, Unit, CacheState.APPLIED)
            override suspend fun revoke(relationshipId: String, command: CommandContext) = OnlineCommandResult.Confirmed(command.operationId, relation.copy(status = CareRelationshipStatus.REVOKED, revokedAtEpochMillis = 1L), CacheState.APPLIED)
            override suspend fun refresh() = Outcome.Success(Unit)
        }
        assertEquals(PairingRequestStatus.PENDING, repository.observeRequests().first().value.single().status)
        assertEquals(code, (repository.issueCode(context(OnlineCommandKind.ISSUE_PAIRING_CODE)) as OnlineCommandResult.Confirmed).value)
        assertEquals(pending, (repository.requestPairing(code.code, context(OnlineCommandKind.REQUEST_PAIRING)) as OnlineCommandResult.Confirmed).value)
        assertTrue((repository.respond(pending.id, true, context(OnlineCommandKind.RESPOND_PAIRING)) as OnlineCommandResult.Confirmed).value is PairingDecisionResult.Accepted)
        assertTrue((repository.respond(pending.id, false, context(OnlineCommandKind.RESPOND_PAIRING)) as OnlineCommandResult.Confirmed).value is PairingDecisionResult.Rejected)
        assertEquals(CareRelationshipStatus.REVOKED, (repository.revoke(relation.id, context(OnlineCommandKind.REVOKE_RELATIONSHIP)) as OnlineCommandResult.Confirmed).value.status)
        assertEquals(Unit, (repository.cancelCode(code.id, context(OnlineCommandKind.CANCEL_PAIRING_CODE)) as OnlineCommandResult.Confirmed).value)
        assertEquals(Unit, (repository.cancelRequest(pending.id, context(OnlineCommandKind.CANCEL_PAIRING_REQUEST)) as OnlineCommandResult.Confirmed).value)
        assertEquals(relation.id, repository.observeRelationships().first().value.single().id)
        assertTrue(repository.refresh() is Outcome.Success)
    }

    @Test fun `E C2 photo submit and view callers use retained source and authorized content abstractions`() = runTest {
        val photo = fixture<MedicationPhotoView>("photo")
        val bytes = byteArrayOf(1, 2, 3) // deliberately not a valid image: no upload validator is being tested
        val source = object : PhotoSource {
            override val contentType = "image/jpeg"
            override val byteCount = 3L
            override val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            override fun openStream() = ByteArrayInputStream(bytes)
        }
        val seenSources = mutableListOf<PhotoSource>()
        val repository: MedicationPhotoRepository = object : MedicationPhotoRepository {
            override fun observePhotos(elderId: String) = flowOf(cached(listOf(photo).filter { it.elderId == elderId }))
            override suspend fun submit(source: PhotoSource, command: CommandContext): OnlineCommandResult<MedicationPhotoView> {
                seenSources += source
                return OnlineCommandResult.Confirmed(command.operationId, photo, CacheState.APPLIED)
            }
            override suspend fun loadContent(photoId: String): Outcome<PhotoContent> = Outcome.Success(object : PhotoContent {
                override val contentType = photo.contentType
                override fun openStream() = ByteArrayInputStream(bytes)
            })
            override suspend fun refresh(elderId: String) = Outcome.Success(sync)
        }
        assertEquals(photo, (repository.submit(source, context(OnlineCommandKind.SUBMIT_PHOTO)) as OnlineCommandResult.Confirmed).value)
        assertSame(source, seenSources.single())
        assertArrayEquals(bytes, (repository.loadContent(photo.id) as Outcome.Success).value.openStream().use { it.readBytes() })
        assertEquals(photo.id, repository.observePhotos(photo.elderId).first().value.single().id)
        assertTrue(repository.refresh(photo.elderId) is Outcome.Success)
    }

    @Test fun `E Dose caller records an occurrence and action kind not arbitrary status or user identity`() = runTest {
        val wire = json.decodeFromJsonElement<DoseActionRequest>(examples.getValue("timelyOffline").jsonObject.getValue("request"))
        val local = LocalActionReceipt(wire.actionId, wire.occurrence, wire.kind, LocalActionClassification.TIMELY_TAKEN, wire.recordedAtEpochMillis, SyncState.PENDING_SYNC)
        val inputs = mutableListOf<Pair<OccurrenceKey, DoseActionKind>>()
        val repository: DoseEventRepository = object : DoseEventRepository {
            override fun observeEvents(elderId: String, range: LocalDateRange): Flow<Cached<List<DoseEventState>>> = flowOf(cached(emptyList()))
            override fun observeLocalAction(actionId: String) = flowOf(local.takeIf { it.actionId == actionId })
            override suspend fun recordAction(occurrence: OccurrenceKey, kind: DoseActionKind): Outcome<LocalActionReceipt> {
                inputs += occurrence to kind
                return Outcome.Success(local)
            }
            override suspend fun retryAction(actionId: String) = Outcome.Success(RetryQueued(actionId))
        }
        assertEquals(local, (repository.recordAction(wire.occurrence, wire.kind) as Outcome.Success).value)
        assertEquals(listOf(wire.occurrence to wire.kind), inputs)
        assertEquals(local, repository.observeLocalAction(local.actionId).first())
        assertEquals(local.actionId, (repository.retryAction(local.actionId) as Outcome.Success).value.actionId)
        assertTrue(repository.observeEvents("elder-1", LocalDateRange("2026-10-20", "2026-10-20")).first().value.isEmpty())
    }

    @Test fun `C3 E dashboard refresh and capability callers keep cache freshness outside dose status`() = runTest {
        val snapshot = fixture<ElderSnapshot>("snapshot")
        val overview = ElderOverview(snapshot.elder, snapshot.medications, emptyList(), snapshot.capability)
        val dashboard: CaregiverDashboardRepository = object : CaregiverDashboardRepository {
            override fun observeOverview(elderId: String, range: LocalDateRange) = flowOf(cached(overview))
            override suspend fun refresh(elderId: String, range: LocalDateRange) = Outcome.Success(sync)
        }
        val capability: ReminderCapabilityRepository = object : ReminderCapabilityRepository {
            override fun observeCapability(elderId: String) = flowOf(cached(snapshot.capability))
            override suspend fun reportLocalCapability() = Outcome.Success(CapabilityQueued("report-id", 2))
        }
        val range = LocalDateRange("2026-10-20", "2026-10-20")
        assertTrue(dashboard.observeOverview("elder-1", range).first().isStale)
        assertEquals(sync, (dashboard.refresh("elder-1", range) as Outcome.Success).value)
        assertEquals(snapshot.capability, capability.observeCapability("elder-1").first().value)
        assertEquals(2L, (capability.reportLocalCapability() as Outcome.Success).value.observationSequence)
    }

    @Test fun `C1 language caller uses local choices with no new service DTO`() = runTest {
        val languageState = MutableStateFlow(LanguageChoice.FOLLOW_SYSTEM)
        val repository: LanguageSettingsRepository = object : LanguageSettingsRepository {
            override fun observeChoice(): Flow<LanguageChoice> = languageState
            override suspend fun setChoice(choice: LanguageChoice): Outcome<Unit> {
                languageState.value = choice // Test memory only; not settings persistence.
                return Outcome.Success(Unit)
            }
        }
        assertEquals(LanguageChoice.FOLLOW_SYSTEM, repository.observeChoice().first())
        assertTrue(repository.setChoice(LanguageChoice.SIMPLIFIED_CHINESE) is Outcome.Success)
        assertEquals(LanguageChoice.SIMPLIFIED_CHINESE, repository.observeChoice().first())
    }

    @Test fun `S1 common command caller separates automatic resume from explicit rejected retry`() = runTest {
        val command = context(OnlineCommandKind.UPDATE_PLAN)
        val record = OnlineCommandRecord(command.operationId, scope, command.commandKind, "plan-1", OnlineCommandState.AWAITING_CONFIRMATION, null, null, null)
        val calls = mutableListOf<String>()
        val repository: OnlineCommandRepository = object : OnlineCommandRepository {
            override suspend fun begin(kind: OnlineCommandKind, targetId: String?) = Outcome.Success(command)
            override fun observeCommands() = flowOf(listOf(record))
            override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> {
                calls += "resume:$operationId"
                return Outcome.Success(record)
            }
            override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> {
                calls += "explicit:$operationId"
                return Outcome.Success(record)
            }
        }
        assertEquals(command, (repository.begin(command.commandKind, "plan-1") as Outcome.Success).value)
        assertEquals(record, repository.observeCommands().first().single())
        assertEquals(record.operationId, (repository.resume(command.operationId) as Outcome.Success).value.operationId)
        assertEquals(listOf("resume:${command.operationId}"), calls)
        repository.retryRejected(command.operationId)
        assertEquals(listOf("resume:${command.operationId}", "explicit:${command.operationId}"), calls)
    }
}
