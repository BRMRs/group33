package com.group33.medicationreminder.integration

import android.content.Context
import androidx.room.withTransaction
import com.group33.medicationreminder.core.data.contract.*
import com.group33.medicationreminder.core.data.journal.*
import com.group33.medicationreminder.core.session.*
import com.group33.medicationreminder.core.reminder.InitialTiming
import com.group33.medicationreminder.caregiver.data.toDoseEventState
import com.group33.medicationreminder.elder.*
import com.group33.shared.contract.*
import com.group33.shared.model.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject

/** Debug-only composition of existing contracts, private session, journal and actual HTTP adapters. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DebugChainRepository(
    private val context: Context,
    baseUrl: String,
    namespace: String = "debug-online",
    private val http: ChainHttp = ChainHttp(baseUrl),
) : AuthRepository, PairingRepository, MedicationPhotoRepository, ReminderPlanRepository, OnlineCommandRepository, DoseEventRepository, CaregiverDashboardRepository {
    private val environment = "debug-anonymous:${http.baseUrl}"
    private val sessionStore = PrivateSessionStore(context, environment, namespace)
    private val cache = ChainCache.open(context)
    private val gate = Mutex()
    private val refreshGate = Mutex()
    private val doseSyncGate = Mutex()
    private val uploadGate = Mutex()
    private var authorityEpoch = 0L
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSession = MutableStateFlow(SessionView(AuthReadiness.RESTORING, null))
    private val mutableScope = MutableStateFlow<RepositoryScope?>(null)
    private val mutableTrusted = MutableStateFlow<TrustedSessionContext?>(null)
    private var currentLease: SessionLease? = null
    private var generation = 0L
    private val journals = mutableMapOf<RepositoryScope, RoomCommandJournal>()
    private val clock = AndroidJournalClock(context)
    private val json = ContractProtocol.json
    private val uploads = File(context.noBackupFilesDir, "debug-online-uploads").apply { mkdirs() }
    val services = ChainServices(this, this, this, this, this, mutableScope.asStateFlow(),
        mutableTrusted.asStateFlow(), ::resumePairingCode, this, this, ::signOut)

    init {
        jobs.launch {
            try {
            sessionStore.observeSession().collect { gate.withLock {
                val view = (sessionStore.restore() as? Outcome.Success)?.value
                    ?: SessionView(AuthReadiness.SIGNED_OUT, null)
                val lease = (sessionStore.acquire() as? Outcome.Success)?.value
                if (lease == null || view.readiness != AuthReadiness.READY) {
                    currentLease = null; mutableScope.value = null; mutableTrusted.value = null
                    mutableSession.value = SessionView(AuthReadiness.SIGNED_OUT, null)
                } else {
                    if (lease.generation != currentLease?.generation || lease.scope != currentLease?.scope) {
                        cache.documents().invalidateAuthority(scopeId(lease.scope))
                        currentLease = lease
                        mutableScope.value = lease.scope
                        mutableTrusted.value = TrustedSessionContext(lease.scope, ++generation)
                    }
                    mutableSession.value = view
                }
            } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                currentLease = null; mutableScope.value = null; mutableTrusted.value = null
                mutableSession.value = SessionView(AuthReadiness.SIGNED_OUT, null)
            }
        }
    }

    override fun observeSession(): Flow<SessionView> = mutableSession.asStateFlow()

    override suspend fun login(role: UserRole, displayName: String): Outcome<UserView> = safely {
        val attempt = gate.withLock {
            mutableScope.value = null; mutableTrusted.value = null
            mutableSession.value = SessionView(AuthReadiness.SIGNED_OUT, null)
            value(sessionStore.beginAuthentication())
        }
        val response = http.call("POST", "/api/v1/auth/anonymous", json = json.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(attempt.installationId, role, displayName)))
        if (response.status != 201) throw Failed(remote(response))
        val auth = json.decodeFromString(AuthResponse.serializer(), response.text())
        check(auth.tokenType.equals("Bearer", true) && auth.user.role == role)
        gate.withLock {
            val lease = value(sessionStore.completeAuthentication(attempt, auth.user, auth.accessToken, auth.expiresInSeconds))
            cache.documents().invalidateAuthority(scopeId(lease.scope))
            currentLease = lease; mutableScope.value = lease.scope
            mutableTrusted.value = TrustedSessionContext(lease.scope, ++generation)
            mutableSession.value = SessionView(AuthReadiness.READY, auth.user)
        }
        auth.user
    }

    private suspend fun signOut(): Outcome<Unit> = safely {
        gate.withLock {
            mutableScope.value = null; mutableTrusted.value = null; currentLease = null
            mutableSession.value = SessionView(AuthReadiness.SIGNED_OUT, null)
            value(sessionStore.clear())
        }
    }

    private fun scopeId(scope: RepositoryScope) = ChainCache.digest(listOf(scope.environmentId,
        scope.installationId, scope.userId).joinToString("") { "${it.length}:$it" })
    private fun rowId(scope: RepositoryScope, kind: String, target: String) = "${scopeId(scope)}:$kind:$target"
    private suspend fun lease() = value(sessionStore.acquire())
    private suspend fun isCurrent(lease: SessionLease) = sessionStore.credential(lease) is Outcome.Success &&
        mutableScope.value == lease.scope
    private suspend fun <T> publish(lease: SessionLease, block: suspend () -> T): T = gate.withLock {
        if (!isCurrent(lease)) throw Failed(local(LocalFailureCode.SESSION_NOT_READY))
        block()
    }

    private fun <T> collection(kind: String, target: String, serializer: KSerializer<T>, empty: T,
                               root: Boolean = false): Flow<Cached<T>> {
        val bound = mutableScope.value
        return mutableScope.flatMapLatest { scope ->
            if (scope == null || !root && scope != bound) flowOf(Cached(empty, false, null, true, AccessState.UNKNOWN))
            else cache.documents().observe(rowId(scope, kind, target)).map { row ->
                if (row == null) Cached(empty, false, null, true, AccessState.UNKNOWN)
                else Cached(json.decodeFromString(serializer, row.body), true, row.syncedAt, row.stale,
                    AccessState.valueOf(row.access))
            }
        }.catch { emit(Cached(empty, false, null, true, AccessState.UNKNOWN)) }
    }

    override fun observeRequests() = collection("requests", "", ListSerializer(PairingRequestView.serializer()), emptyList(), true)
    override fun observeRelationships() = collection("relationships", "", ListSerializer(CareRelationshipView.serializer()), emptyList(), true)
    override fun observePhotos(elderId: String) = collection("photos", elderId, ListSerializer(MedicationPhotoView.serializer()), emptyList())
    override fun observePlans(elderId: String) = collection("plans", elderId, ReminderPlanCollection.serializer(),
        ReminderPlanCollection(emptyList(), emptyList())).map { it.copyValue(it.value.items) }
    override fun observeMedications(elderId: String) = collection("medications", elderId, ListSerializer(MedicationView.serializer()), emptyList())
    // This create/read slice has no occurrence cache to overlay. Do not fabricate a complete preview.
    override fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>> =
        flowOf(Cached(emptyList(), false, null, true, AccessState.UNKNOWN))
    private fun <A, B> Cached<A>.copyValue(value: B) = Cached(value, hasLocalData, lastSuccessfulSyncAtEpochMillis, isStale, access)

    private suspend fun put(lease: SessionLease, kind: String, target: String, body: String) =
        cache.documents().put(ChainDocument(rowId(lease.scope, kind, target), scopeId(lease.scope), kind, target,
            body, System.currentTimeMillis()))

    private suspend fun get(lease: SessionLease, path: String): ChainHttp.Reply {
        val token = value(sessionStore.credential(lease)).value
        val reply = http.call("GET", path, token)
        handleAuthentication(lease, reply)
        if (reply.status !in 200..299) throw Failed(remote(reply))
        if (!isCurrent(lease)) throw Failed(local(LocalFailureCode.SESSION_NOT_READY))
        return reply
    }

    private suspend fun handleAuthentication(lease: SessionLease, reply: ChainHttp.Reply) {
        if (reply.status == 401) gate.withLock {
            if (value(sessionStore.invalidate(lease))) {
                currentLease = null; mutableScope.value = null; mutableTrusted.value = null
                mutableSession.value = SessionView(AuthReadiness.SIGNED_OUT, null)
            }
        }
        if (reply.status in setOf(403, 404)) publish(lease) {
            // A denied resource never leaves an authorized current-Elder/image behind.
            cache.documents().invalidateAuthority(scopeId(lease.scope))
            authorityEpoch++
        }
    }

    override suspend fun refresh(): Outcome<Unit> = safely {
        refreshGate.withLock {
            val l = lease()
            publish(l) {
                cache.documents().markStale(scopeId(l.scope), "requests", "")
                cache.documents().markStale(scopeId(l.scope), "relationships", "")
            }
            val requests = json.decodeFromString(ItemsResponse.serializer(PairingRequestView.serializer()),
                get(l, "/api/v1/pairing-requests").text()).items
            val relationships = json.decodeFromString(ItemsResponse.serializer(CareRelationshipView.serializer()),
                get(l, "/api/v1/care-relationships").text()).items
            publish(l) { cache.withTransaction {
                authorityEpoch++
                put(l, "requests", "", json.encodeToString(ListSerializer(PairingRequestView.serializer()), requests))
                put(l, "relationships", "", json.encodeToString(ListSerializer(CareRelationshipView.serializer()), relationships))
                relationships.filter { it.status == CareRelationshipStatus.REVOKED }.forEach {
                    cache.documents().revoke(scopeId(l.scope), it.elderId)
                }
            } }
        }
    }

    override suspend fun refresh(elderId: String): Outcome<SyncSummary> = safely {
        refreshGate.withLock {
            val l = lease()
            refreshPhotos(l, elderId)
            refreshPlans(l, elderId)
            SyncSummary(l.scope, elderId, SyncCompletion.COMPLETE, System.currentTimeMillis(), null)
        }
    }

    private suspend fun refreshPhotos(l: SessionLease, elderId: String) {
        publish(l) { cache.documents().markStale(scopeId(l.scope), "photos", elderId) }
        val photos = json.decodeFromString(ItemsResponse.serializer(MedicationPhotoView.serializer()),
            get(l, "/api/v1/elders/${segment(elderId)}/medication-photos").text()).items
        check(photos.all { it.elderId == elderId })
        publish(l) { put(l, "photos", elderId, json.encodeToString(ListSerializer(MedicationPhotoView.serializer()), photos)) }
    }

    private suspend fun refreshPlans(l: SessionLease, elderId: String) {
        publish(l) { cache.withTransaction {
            cache.documents().markStale(scopeId(l.scope), "plans", elderId)
            cache.documents().markStale(scopeId(l.scope), "medications", elderId)
        } }
        val plans = json.decodeFromString(ReminderPlanCollection.serializer(),
            get(l, "/api/v1/elders/${segment(elderId)}/reminder-plans").text())
        val medications = json.decodeFromString(ItemsResponse.serializer(MedicationView.serializer()),
            get(l, "/api/v1/elders/${segment(elderId)}/medications").text()).items
        check(plans.items.all { p -> p.elderId == elderId && medications.any { it.id == p.medicationId } } &&
            medications.all { it.elderId == elderId })
        publish(l) { cache.withTransaction {
            put(l, "plans", elderId, json.encodeToString(ReminderPlanCollection.serializer(), plans))
            put(l, "medications", elderId, json.encodeToString(ListSerializer(MedicationView.serializer()), medications))
        } }
    }

    internal suspend fun localElderDoses(
        expected: TrustedSessionContext,
        time: Clock,
        action: Pair<ElderDoseGroup, DoseActionKind>? = null,
        actionId: String? = null,
        recordedAtEpochMillis: Long? = null,
    ): Outcome<LocalDoseUpdate> = safely {
        val lease = lease()
        if (lease.scope != expected.scope) throw Failed(local(LocalFailureCode.SESSION_NOT_READY))
        publish(lease) {
            if (mutableTrusted.value != expected || mutableSession.value.user?.role != UserRole.ELDER)
                throw Failed(local(LocalFailureCode.SESSION_NOT_READY))
            val elderId = lease.scope.userId
            cache.withTransaction {
                val now = time.instant()
                val dao = cache.documents()
                val planRow = dao.read(rowId(lease.scope, "plans", elderId))
                val medicationRow = dao.read(rowId(lease.scope, "medications", elderId))
                val hasPlanSnapshot = planRow?.access == AccessState.AUTHORIZED.name &&
                    medicationRow?.access == AccessState.AUTHORIZED.name
                if (hasPlanSnapshot) {
                    val plans = json.decodeFromString(ReminderPlanCollection.serializer(), planRow.body).items
                        .filter { it.elderId == elderId }
                    val medications = json.decodeFromString(ListSerializer(MedicationView.serializer()), medicationRow.body)
                    nextElderOccurrences(plans, medications, now, planRow.syncedAt).forEach { occurrence ->
                        val id = rowId(lease.scope, "elder-dose", doseTarget(occurrence.key))
                        if (dao.read(id) == null) storeElderOccurrence(lease, occurrence)
                    }
                }
                val occurrences = dao.readSince(scopeId(lease.scope), "elder-dose",
                    now.minus(InitialTiming.responseWindow).toEpochMilli()).map {
                    json.decodeFromString(LocalElderOccurrence.serializer(), it.body)
                }
                if (action == null) return@withTransaction LocalDoseUpdate(occurrences, hasPlanSnapshot, false)
                val (requested, kind) = action
                val timestamp = recordedAtEpochMillis ?: now.toEpochMilli()
                if (actionId != null) {
                    val existing = dao.read(rowId(lease.scope, "elder-dose-action", actionId))
                    if (existing != null) {
                        val saved = json.decodeFromString(LocalElderAction.serializer(), existing.body)
                        return@withTransaction LocalDoseUpdate(
                            occurrences,
                            hasPlanSnapshot,
                            saved.occurrences == requested.keys && saved.kind == kind && saved.recordedAt == timestamp,
                        )
                    }
                }
                val actionClock = Clock.fixed(java.time.Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)
                val due = dueElderDose(occurrences, actionClock)
                if (due == null || due.plannedAt != requested.plannedAt || due.keys.toSet() != requested.keys.toSet() ||
                    (kind == DoseActionKind.REMIND_LATER && !due.canSnooze)) {
                    return@withTransaction LocalDoseUpdate(occurrences, hasPlanSnapshot, false)
                }
                val keys = due.keys.toSet()
                val updated = occurrences.map { occurrence ->
                    if (occurrence.key !in keys) occurrence
                    else when (kind) {
                        DoseActionKind.TAKEN -> occurrence.copy(takenAt = timestamp, snoozedUntil = null)
                        DoseActionKind.REMIND_LATER -> occurrence.copy(
                            snoozedUntil = timestamp + InitialTiming.snooze.toMillis())
                    }.also { storeElderOccurrence(lease, it) }
                }
                val receipt = LocalElderAction(actionId ?: UUID.randomUUID().toString(), due.keys, kind, timestamp)
                put(lease, "elder-dose-action", receipt.id, json.encodeToString(LocalElderAction.serializer(), receipt))
                LocalDoseUpdate(updated, hasPlanSnapshot, true)
            }
        }
    }

    internal fun executionScopeId(expected: TrustedSessionContext): String? =
        expected.takeIf { it == mutableTrusted.value }?.let { scopeId(it.scope) }

    internal fun generationEvidence(
        expected: TrustedSessionContext,
        occurrence: LocalElderOccurrence,
    ): OccurrenceGenerationEvidence? {
        val scope = expected.takeIf { it == mutableTrusted.value }?.scope ?: return null
        return OccurrenceGenerationEvidence(
            stableDoseId(scopeId(scope) + "/generation/" + doseTarget(occurrence.key)),
            occurrence.key,
            occurrence.planRevision,
            occurrence.generatedAt,
            occurrence.executionZone,
            occurrence.plannedAt,
            occurrence.offsetSeconds,
            TimeResolutionPolicy.P0_TIME_V1,
            "${occurrence.key.planId}:${occurrence.planRevision}",
            occurrence.planSnapshotAt,
        )
    }

    internal suspend fun syncLocalElderActions(expected: TrustedSessionContext): Outcome<Unit> = safely {
        doseSyncGate.withLock {
            val lease = lease()
            drainDoseActionBatches(readBatch = {
                publish(lease) {
                    if (lease.scope != expected.scope || mutableTrusted.value != expected ||
                        mutableSession.value.user?.role != UserRole.ELDER)
                        throw Failed(local(LocalFailureCode.SESSION_NOT_READY))
                    cache.documents().readKind(scopeId(lease.scope), "elder-dose-action", 25)
                }
            }) { row ->
                val action = json.decodeFromString(LocalElderAction.serializer(), row.body)
                val occurrences = publish(lease) {
                    action.occurrences.map { key ->
                        val stored = checkNotNull(cache.documents().read(rowId(lease.scope, "elder-dose", doseTarget(key))))
                        json.decodeFromString(LocalElderOccurrence.serializer(), stored.body)
                    }
                }
                val registration = OccurrenceRegistrationRequest(occurrences.map { item ->
                    OccurrenceGenerationEvidence(
                        stableDoseId(scopeId(lease.scope) + "/generation/" + doseTarget(item.key)),
                        item.key, item.planRevision, item.generatedAt, item.executionZone,
                        item.plannedAt, item.offsetSeconds, TimeResolutionPolicy.P0_TIME_V1,
                        "${item.key.planId}:${item.planRevision}", item.planSnapshotAt,
                    )
                })
                val path = "/api/v1/elders/${segment(lease.scope.userId)}"
                val registered = doseHttp(lease, "POST", "$path/occurrence-registrations",
                    stableDoseId("dose-registration/" + action.id),
                    json.encodeToString(OccurrenceRegistrationRequest.serializer(), registration))
                if (registered.status !in 200..299) {
                    if (registered.status in setOf(400, 422)) {
                        publish(lease) { cache.documents().put(row.copy(kind = "elder-dose-action-needs-attention")) }
                        return@drainDoseActionBatches
                    }
                    throw Failed(remote(registered))
                }
                val receipt = doseHttp(lease, "POST", "$path/dose-action-groups", action.id,
                    json.encodeToString(DebugDoseGroupRequest.serializer(),
                        DebugDoseGroupRequest(action.id, action.occurrences, action.kind, action.recordedAt)))
                if (receipt.status !in 200..299) {
                    if (receipt.status in setOf(400, 422)) {
                        publish(lease) { cache.documents().put(row.copy(kind = "elder-dose-action-needs-attention")) }
                        return@drainDoseActionBatches
                    }
                    throw Failed(remote(receipt))
                }
                val accepted = json.decodeFromString(DebugDoseGroupReceipt.serializer(), receipt.text())
                check(accepted.actionId == action.id && accepted.events.map { it.occurrence } == action.occurrences &&
                    accepted.events.all { it.elderId == lease.scope.userId })
                publish(lease) { cache.documents().put(row.copy(kind = "elder-dose-action-synced")) }
            }
        }
    }

    private fun stableDoseId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

    // These routes are optional in the local test runtime. A missing endpoint must not erase pairing.
    private suspend fun doseHttp(lease: SessionLease, method: String, path: String, operation: String? = null,
                                 body: String? = null): ChainHttp.Reply {
        val reply = http.call(method, path, value(sessionStore.credential(lease)).value, operation, body)
        if (reply.status == 401) handleAuthentication(lease, reply)
        if (!isCurrent(lease)) throw Failed(local(LocalFailureCode.SESSION_NOT_READY))
        return reply
    }

    private fun doseCollectionKind(range: LocalDateRange) = "dose-events:${range.fromDate}:${range.throughDate}"

    override fun observeEvents(elderId: String, range: LocalDateRange) =
        collection(doseCollectionKind(range), elderId, ListSerializer(DoseEventView.serializer()), emptyList())
            .map { rows -> rows.copyValue(rows.value.map { event ->
                event.toDoseEventState().copy(serverAcceptedTakenAtEpochMillis = event.takenRecordedAtEpochMillis)
            }) }

    override fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>> =
        observeRelationships().flatMapLatest { relationships ->
            val scope = mutableScope.value
            val elder = relationships.value.firstOrNull {
                it.elderId == elderId && it.caregiverId == scope?.userId && it.status == CareRelationshipStatus.ACTIVE
            }?.elder
            if (relationships.access != AccessState.AUTHORIZED || elder == null) emptyFlow()
            else combine(observeEvents(elderId, range), observeMedications(elderId)) { rows, medications ->
                rows.copyValue(ElderOverview(elder,
                    medications.value.takeIf { medications.access == AccessState.AUTHORIZED }.orEmpty(), rows.value, null))
            }
        }

    override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> = safely {
        refreshGate.withLock {
            val lease = lease()
            val kind = doseCollectionKind(range)
            publish(lease) { cache.documents().markStale(scopeId(lease.scope), kind, elderId) }
            val reply = doseHttp(lease, "GET", "/api/v1/elders/${segment(elderId)}/dose-events" +
                "?fromDate=${range.fromDate}&throughDate=${range.throughDate}")
            if (reply.status !in 200..299) {
                if (reply.status in setOf(403, 404)) publish(lease) {
                    cache.documents().invalidateKind(scopeId(lease.scope), kind, elderId)
                }
                throw Failed(remote(reply))
            }
            val rows = json.decodeFromString(ItemsResponse.serializer(DoseEventView.serializer()), reply.text()).items
            check(rows.all { it.elderId == elderId && it.occurrence.localDate in range.fromDate..range.throughDate })
            publish(lease) {
                put(lease, kind, elderId, json.encodeToString(ListSerializer(DoseEventView.serializer()), rows))
            }
            SyncSummary(lease.scope, elderId, SyncCompletion.COMPLETE, System.currentTimeMillis(), null)
        }
    }

    // Elder writes use the atomic group action entry, never the legacy single-occurrence action.
    override fun observeLocalAction(actionId: String): Flow<LocalActionReceipt?> = flowOf(null)
    override suspend fun recordAction(occurrence: OccurrenceKey, kind: DoseActionKind): Outcome<LocalActionReceipt> =
        Outcome.Failure(local(LocalFailureCode.INVALID_INPUT))
    override suspend fun retryAction(actionId: String): Outcome<RetryQueued> =
        Outcome.Failure(local(LocalFailureCode.INVALID_INPUT))

    private fun doseTarget(key: OccurrenceKey) = "${key.planId}/${key.localDate}/${key.localTime}"

    private suspend fun storeElderOccurrence(lease: SessionLease, occurrence: LocalElderOccurrence) {
        val target = doseTarget(occurrence.key)
        cache.documents().put(ChainDocument(rowId(lease.scope, "elder-dose", target), scopeId(lease.scope),
            "elder-dose", target, json.encodeToString(LocalElderOccurrence.serializer(), occurrence), occurrence.plannedAt))
    }

    override suspend fun loadContent(photoId: String): Outcome<PhotoContent> = safely {
        val l = lease()
        val epoch = gate.withLock { authorityEpoch }
        val reply = get(l, "/api/v1/medication-photos/${segment(photoId)}/content")
        check(reply.contentType in setOf("image/png", "image/jpeg"))
        publish(l) { if (epoch != authorityEpoch) throw Failed(local(LocalFailureCode.SESSION_NOT_READY)) }
        object : PhotoContent {
            override val contentType = checkNotNull(reply.contentType)
            override fun openStream() = ByteArrayInputStream(reply.bytes)
        }
    }

    private suspend fun journal(scope: RepositoryScope) = gate.withLock {
        journals.getOrPut(scope) { RoomCommandJournal.open(context, scope, "debug-online-journal") }
    }

    private inner class NetworkSession(val lease: SessionLease) : RecoverySession {
        override val scope = lease.scope
        override suspend fun isCurrent() = this@DebugChainRepository.isCurrent(lease)
    }

    private fun coordinator(
        j: RoomCommandJournal,
        recoveryLease: SessionLease? = null,
        onPairingCode: ((PairingCodeView) -> Unit)? = null,
    ): CommandRecoveryCoordinator {
        var pairingCodeBody: String? = null
        val adapter = object : CommandRecoveryAdapter {
            override suspend fun execute(claim: CommandClaim, session: RecoverySession): RecoveryReply {
                val (reply, body) = dispatch(claim, (session as NetworkSession).lease)
                if (onPairingCode != null) pairingCodeBody = body
                return reply
            }
            override suspend fun reconcile(record: OnlineCommandRecord, session: RecoverySession): Outcome<Unit> = safely {
                val l = (session as NetworkSession).lease
                when (record.commandKind) {
                    OnlineCommandKind.ISSUE_PAIRING_CODE -> {
                        if (onPairingCode == null) value(refresh())
                        else {
                            // A restart can leave an already confirmed record awaiting reconciliation.
                            val body = pairingCodeBody ?: replayPairingCode(l, record.operationId)
                            val code = json.decodeFromString(PairingCodeView.serializer(), body)
                            check(record.resultRef == CommandResultRef(CommandResultKind.PAIRING_CODE, code.id))
                            publish(l) { onPairingCode(code) }
                        }
                    }
                    OnlineCommandKind.SUBMIT_PHOTO -> refreshGate.withLock { refreshPhotos(l, l.scope.userId) }
                    OnlineCommandKind.CONFIRM_PLAN, OnlineCommandKind.UPDATE_PLAN, OnlineCommandKind.DEACTIVATE_PLAN ->
                        refreshGate.withLock { refreshPlans(l, checkNotNull(record.targetId)) }
                    else -> value(refresh())
                }
            }
        }
        return CommandRecoveryCoordinator(j, RecoveryAccess { safely { NetworkSession(recoveryLease ?: lease()) } },
            supported.associateWith { adapter }, clock::now)
    }

    override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> = safely {
        check(kind in supported)
        val l = lease(); val j = journal(l.scope)
        val unresolved = j.observe().first().any { it.commandKind == kind && it.targetId == targetId &&
            (it.state == OnlineCommandState.AWAITING_CONFIRMATION || it.state == OnlineCommandState.CONFIRMED && it.cacheState != CacheState.APPLIED) }
        if (unresolved) throw Failed(local(LocalFailureCode.SESSION_NOT_READY))
        value(coordinator(j).begin(kind, targetId))
    }
    override fun observeCommands(): Flow<List<OnlineCommandRecord>> = mutableScope.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else flow { emitAll(journal(s).observe()) }
    }
    override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> = recover(operationId, false)
    override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> = recover(operationId, true)
    private suspend fun recover(id: String, rejected: Boolean): Outcome<OnlineCommandRecord> = safely {
        val c = coordinator(journal(lease().scope))
        value(if (rejected) c.retryRejected(id) else c.resume(id))
    }

    private suspend fun resumePairingCode(id: String): Outcome<PairingCodeRecovery> = safely {
        val l = lease()
        val j = journal(l.scope)
        if (j.find(id)?.commandKind != OnlineCommandKind.ISSUE_PAIRING_CODE)
            throw Failed(local(LocalFailureCode.INVALID_INPUT))
        var code: PairingCodeView? = null
        val record = value(coordinator(j, l) { code = it }.resume(id))
        publish(l) { PairingCodeRecovery(record, code) }
    }

    private suspend fun replayPairingCode(l: SessionLease, id: String): String {
        val response = http.call("POST", "/api/v1/pairing-codes", value(sessionStore.credential(l)).value,
            id, json = "{}")
        handleAuthentication(l, response)
        if (response.status != 201) throw Failed(remote(response))
        return response.text()
    }

    private fun envelope(method: String, path: String, body: String = "{}") = JSONObject()
        .put("method", method).put("path", path).put("body", body)

    private suspend fun <T> submit(command: CommandContext, expectedKind: OnlineCommandKind, expectedTarget: String?,
                                   payload: JSONObject, decode: suspend (String) -> T): OnlineCommandResult<T> {
        var claimed: CommandClaim? = null
        var j: RoomCommandJournal? = null
        var known: T? = null
        var durableConfirmation = false
        try {
            val l = lease()
            if (command.scope != l.scope || command.commandKind != expectedKind || command.targetId != expectedTarget)
                return OnlineCommandResult.NotSubmitted(command.operationId, local(LocalFailureCode.INVALID_INPUT))
            j = journal(l.scope)
            if (!j.bind(command, payload.toString().toByteArray()))
                return OnlineCommandResult.NotSubmitted(command.operationId, local(LocalFailureCode.INVALID_INPUT))
            claimed = j.claim(command, ClaimMode.EXPLICIT, clock.now(), 125_000)
                ?: return OnlineCommandResult.AwaitingConfirmation(command.operationId)
            val (reply, raw) = withTimeoutOrNull(120_000) { dispatch(claimed, l) }
                ?: (RecoveryReply.Unknown to null)
            if (!isCurrent(l)) return OnlineCommandResult.AwaitingConfirmation(command.operationId)
            when (reply) {
                is RecoveryReply.Confirmed -> {
                    known = decode(checkNotNull(raw))
                    check(j.finish(claimed, CommandCompletion.Confirmed(reply.resultRef, CacheState.REFRESH_REQUIRED)))
                    durableConfirmation = true
                    // Persist receipt first; never put its potentially old business fields into Room.
                    coordinator(j).resume(command.operationId)
                    val state = j.find(command.operationId)
                    return OnlineCommandResult.Confirmed(command.operationId, known, state?.cacheState ?: CacheState.REFRESH_REQUIRED)
                }
                is RecoveryReply.Rejected -> {
                    j.finish(claimed, CommandCompletion.Rejected(reply.error))
                    return OnlineCommandResult.Rejected(command.operationId, reply.error)
                }
                else -> {
                    if (reply == RecoveryReply.InProgress) delay(1_000)
                    j.finish(claimed, CommandCompletion.Unknown)
                    return OnlineCommandResult.AwaitingConfirmation(command.operationId)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // A cache/readback error after durable confirmation must not downgrade acceptance.
            if (known != null && durableConfirmation)
                return OnlineCommandResult.Confirmed(command.operationId, known, CacheState.REFRESH_REQUIRED)
            if (claimed != null && j != null) {
                try { j.finish(claimed, CommandCompletion.Unknown) } catch (_: Exception) { /* Retain claim for recovery. */ }
                return OnlineCommandResult.AwaitingConfirmation(command.operationId)
            }
            return OnlineCommandResult.NotSubmitted(command.operationId, local(LocalFailureCode.STORAGE_UNAVAILABLE))
        }
    }

    private suspend fun dispatch(claim: CommandClaim, l: SessionLease): Pair<RecoveryReply, String?> {
        val p = JSONObject(claim.payloadBytes().toString(Charsets.UTF_8))
        val path = p.getString("path")
        val file = if (p.has("photo")) File(uploads, p.getString("photo")).also {
            check(it.parentFile?.canonicalPath == uploads.canonicalPath && it.name == "${claim.command.operationId}.image")
            check(it.length() == p.getLong("byteCount") && hash(it) == p.getString("sha256"))
        } else null
        val response = http.call(p.getString("method"), path, value(sessionStore.credential(l)).value,
            claim.command.operationId, if (file == null) p.getString("body") else null, file, p.optString("photoType"))
        handleAuthentication(l, response)
        if (response.status == 409 && runCatching { remote(response).error.code }.getOrNull() == "OPERATION_IN_PROGRESS")
            return RecoveryReply.InProgress to null
        if (response.status == 429 || response.status >= 500) {
            delay((response.retryAfter?.toLongOrNull()?.coerceIn(1, 120) ?: 1) * 1_000)
            return RecoveryReply.Unknown to null
        }
        if (response.status !in 200..299) return RecoveryReply.Rejected(remote(response)) to null
        val body = response.text()
        val id = if (body.isEmpty()) claim.command.targetId ?: claim.command.operationId
            else JSONObject(body).let { if (claim.command.commandKind == OnlineCommandKind.CONFIRM_PLAN) it.getJSONObject("plan").getString("id") else it.getString("id") }
        val resultKind = when (claim.command.commandKind) {
            OnlineCommandKind.ISSUE_PAIRING_CODE -> CommandResultKind.PAIRING_CODE
            OnlineCommandKind.REQUEST_PAIRING, OnlineCommandKind.CANCEL_PAIRING_REQUEST -> CommandResultKind.PAIRING_REQUEST
            OnlineCommandKind.RESPOND_PAIRING -> if (path.endsWith("/accept")) CommandResultKind.CARE_RELATIONSHIP else CommandResultKind.PAIRING_REQUEST
            OnlineCommandKind.REVOKE_RELATIONSHIP -> CommandResultKind.CARE_RELATIONSHIP
            OnlineCommandKind.CANCEL_PAIRING_CODE -> CommandResultKind.PAIRING_CODE
            OnlineCommandKind.SUBMIT_PHOTO -> CommandResultKind.PHOTO
            else -> CommandResultKind.REMINDER_PLAN
        }
        return RecoveryReply.Confirmed(CommandResultRef(resultKind, id)) to body
    }

    override suspend fun issueCode(command: CommandContext) = submit(command, OnlineCommandKind.ISSUE_PAIRING_CODE, null,
        envelope("POST", "/api/v1/pairing-codes")) { json.decodeFromString(PairingCodeView.serializer(), it) }
    override suspend fun requestPairing(code: String, command: CommandContext) = submit(command, OnlineCommandKind.REQUEST_PAIRING, null,
        envelope("POST", "/api/v1/pairing-requests", json.encodeToString(RequestPairingRequest.serializer(), RequestPairingRequest(code)))) {
            json.decodeFromString(PairingRequestView.serializer(), it) }
    override suspend fun respond(requestId: String, accept: Boolean, command: CommandContext): OnlineCommandResult<com.group33.medicationreminder.core.data.contract.PairingDecisionResult> =
        submit(command, OnlineCommandKind.RESPOND_PAIRING, requestId,
            envelope("POST", "/api/v1/pairing-requests/${segment(requestId)}/${if (accept) "accept" else "reject"}")) {
            val request = observeRequests().first().value.first { r -> r.id == requestId }
            if (accept) com.group33.medicationreminder.core.data.contract.PairingDecisionResult.Accepted(
                request.copy(status = PairingRequestStatus.ACCEPTED), json.decodeFromString(CareRelationshipView.serializer(), it))
            else com.group33.medicationreminder.core.data.contract.PairingDecisionResult.Rejected(json.decodeFromString(PairingRequestView.serializer(), it))
        }
    override suspend fun cancelCode(codeId: String, command: CommandContext) = submit(command, OnlineCommandKind.CANCEL_PAIRING_CODE, codeId,
        envelope("DELETE", "/api/v1/pairing-codes/${segment(codeId)}")) { Unit }
    override suspend fun cancelRequest(requestId: String, command: CommandContext) = submit(command, OnlineCommandKind.CANCEL_PAIRING_REQUEST, requestId,
        envelope("DELETE", "/api/v1/pairing-requests/${segment(requestId)}")) { Unit }
    override suspend fun revoke(relationshipId: String, command: CommandContext) = submit(command, OnlineCommandKind.REVOKE_RELATIONSHIP, relationshipId,
        envelope("POST", "/api/v1/care-relationships/${segment(relationshipId)}/revoke")) { json.decodeFromString(CareRelationshipView.serializer(), it) }

    override suspend fun submit(source: PhotoSource, command: CommandContext): OnlineCommandResult<MedicationPhotoView> {
        val l = try { lease() } catch (_: Exception) {
            return OnlineCommandResult.NotSubmitted(command.operationId, local(LocalFailureCode.SESSION_NOT_READY))
        }
        if (command.scope != l.scope) return OnlineCommandResult.NotSubmitted(command.operationId, local(LocalFailureCode.SESSION_NOT_READY))
        val payload = try { uploadGate.withLock { withContext(Dispatchers.IO) {
            require(source.byteCount in 1..5_242_880 && source.contentType in setOf("image/jpeg", "image/png"))
            val file = File(uploads, "${command.operationId}.image")
            if (!file.exists()) {
                val temporary = File.createTempFile("upload-", ".part", uploads)
                try {
                    source.openStream().use { input -> temporary.outputStream().use { out ->
                        val buffer = ByteArray(8192); var count = 0L
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            count += n; require(count <= source.byteCount); out.write(buffer, 0, n) }
                        out.fd.sync()
                    } }
                    require(temporary.length() == source.byteCount && hash(temporary) == source.sha256)
                    check(temporary.renameTo(file))
                } finally { temporary.delete() }
            }
            require(file.length() == source.byteCount && hash(file) == source.sha256)
            envelope("POST", "/api/v1/elders/${segment(l.scope.userId)}/medication-photos")
                .put("photo", file.name).put("photoType", source.contentType).put("byteCount", source.byteCount).put("sha256", source.sha256)
        } } } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { return OnlineCommandResult.NotSubmitted(command.operationId, local(LocalFailureCode.SOURCE_UNAVAILABLE)) }
        return submit(command, OnlineCommandKind.SUBMIT_PHOTO, null, payload) { json.decodeFromString(MedicationPhotoView.serializer(), it) }
    }

    override suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext) =
        submit(command, OnlineCommandKind.CONFIRM_PLAN, elderId,
            envelope("POST", "/api/v1/elders/${segment(elderId)}/reminder-plan-confirmations",
                json.encodeToString(ConfirmReminderPlanRequest.serializer(), draft.copy(plan = draft.plan.copy(localTimes = draft.plan.localTimes.sorted()))))) {
            json.decodeFromString(ConfirmReminderPlanResponse.serializer(), it) }
    override suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse> =
        OnlineCommandResult.NotSubmitted(command.operationId, local(LocalFailureCode.INVALID_INPUT))
    override suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView> =
        OnlineCommandResult.NotSubmitted(command.operationId, local(LocalFailureCode.INVALID_INPUT))

    private fun remote(reply: ChainHttp.Reply): RepositoryError.Remote {
        val error = runCatching { json.decodeFromString(ApiError.serializer(), reply.text()) }.getOrNull()
        if (error == null || error.requestId != reply.requestId) throw Failed(local(LocalFailureCode.OFFLINE))
        return RepositoryError.Remote(error)
    }
    private class Failed(val error: RepositoryError) : Exception()
    private fun local(code: LocalFailureCode) = RepositoryError.Local(code)
    private fun <T> value(outcome: Outcome<T>): T = when (outcome) {
        is Outcome.Success -> outcome.value
        is Outcome.Failure -> throw Failed(outcome.error)
    }
    private suspend fun <T> safely(block: suspend () -> T): Outcome<T> = try { Outcome.Success(block()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failed: Failed) { Outcome.Failure(failed.error) }
    catch (_: android.database.sqlite.SQLiteException) { Outcome.Failure(local(LocalFailureCode.STORAGE_UNAVAILABLE)) }
    catch (_: Exception) { Outcome.Failure(local(LocalFailureCode.OFFLINE)) }
    private fun segment(id: String) = id.also { require(it.matches(Regex("[A-Za-z0-9_-]+"))) }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(8192)
            while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private val supported = setOf(OnlineCommandKind.ISSUE_PAIRING_CODE, OnlineCommandKind.CANCEL_PAIRING_CODE,
        OnlineCommandKind.REQUEST_PAIRING, OnlineCommandKind.RESPOND_PAIRING, OnlineCommandKind.CANCEL_PAIRING_REQUEST,
        OnlineCommandKind.REVOKE_RELATIONSHIP, OnlineCommandKind.SUBMIT_PHOTO, OnlineCommandKind.CONFIRM_PLAN)

    internal fun close() {
        jobs.cancel(); journals.values.forEach { it.close() }; cache.close()
    }
}
