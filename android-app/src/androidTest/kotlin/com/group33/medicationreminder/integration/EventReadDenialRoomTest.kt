package com.group33.medicationreminder.integration

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.caregiver.CaregiverHistoryViewModel
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Current main Repository and actual Room. Every HTTP response/identity is fictional. No C3 Host required. */
class EventReadDenialRoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dates = LocalDateRange("2026-10-21", "2026-10-22")
    private val otherDates = LocalDateRange("2026-10-21", "2026-10-21")
    private val json = ContractProtocol.json

    @Test fun denied403ClearsPayloadAndLatchesHistoryAcrossPrecachedDates() = runBlocking { denied(403) }
    @Test fun denied404ClearsPayloadAndLatchesHistoryAcrossPrecachedDates() = runBlocking { denied(404) }

    private suspend fun denied(status: Int) = fixture { f ->
        f.seed(dates); f.seed(otherDates)
        val originalSync = f.repository.observeEvents(f.elder.id, dates).first().lastSuccessfulSyncAtEpochMillis
        assertNotNull(originalSync)
        val history = f.history(dates)
        await { history.state.value.authorized && history.state.value.events.isNotEmpty() }
        onMain { history.selectEvent(history.state.value.events.first().occurrence) }
        assertNotNull(history.state.value.selectedEvent)
        val relationshipReads = f.http.relationshipReads.get()
        f.http.denial = status
        assertTrue(f.repository.refresh(f.elder.id, dates) is Outcome.Failure)
        val observed = withTimeout(5_000) { f.repository.observeEvents(f.elder.id, dates).first { it.access != AccessState.AUTHORIZED } }
        val raw = f.raw(f.elder.id, dates)
        println("EVENT_DENIAL status=$status access=${observed.access} rows=${observed.value.size} emptyPayload=${raw.first == "[]"}")
        assertEquals(AccessState.REVOKED, observed.access)
        assertTrue(observed.value.isEmpty())
        assertEquals(originalSync, observed.lastSuccessfulSyncAtEpochMillis)
        assertEquals("[]", raw.first); assertEquals("REVOKED", raw.second)
        await { history.state.value.access == AccessState.REVOKED }
        assertTrue(history.state.value.events.isEmpty()); assertNull(history.state.value.selectedEvent)
        val calls = f.http.eventReads.size
        onMain { assertTrue(history.applyRange(otherDates.fromDate, otherDates.throughDate)) }
        assertEquals(AccessState.REVOKED, history.state.value.access)
        assertTrue(history.state.value.events.isEmpty()); assertNull(history.state.value.selectedEvent)
        assertEquals(calls, f.http.eventReads.size)
        assertEquals(relationshipReads, f.http.relationshipReads.get())
        assertEquals(AuthReadiness.READY, f.repository.observeSession().first().readiness)
        assertEquals(CareRelationshipStatus.ACTIVE, f.repository.observeRelationships().first().value.single { it.elderId == f.elder.id }.status)
    }

    @Test fun denialWithoutCachedEventsStillPublishesTerminalAccess() = runBlocking { fixture { f ->
        f.http.denial = 404
        assertTrue(f.repository.refresh(f.elder.id, dates) is Outcome.Failure)
        val cached = withTimeout(5_000) { f.repository.observeEvents(f.elder.id, dates).first() }
        assertEquals(AccessState.REVOKED, cached.access); assertTrue(cached.value.isEmpty())
        assertNull(cached.lastSuccessfulSyncAtEpochMillis)
        assertEquals("[]" to "REVOKED", f.raw(f.elder.id, dates))
        assertTrue(f.repository.services.signOut() is Outcome.Success)
        assertTrue(f.repository.login(UserRole.CAREGIVER, f.caregiver.displayName) is Outcome.Success)
        val afterLogin = f.repository.observeEvents(f.elder.id, dates).first()
        assertEquals(AccessState.UNKNOWN, afterLogin.access)
        assertNull(afterLogin.lastSuccessfulSyncAtEpochMillis)
    } }

    @Test fun unavailable503RetainsAuthorizedStaleEventsAndCanRecover() = runBlocking { fixture { f ->
        f.seed(dates); val old = f.raw(f.elder.id, dates)
        val oldSync = f.repository.observeEvents(f.elder.id, dates).first().lastSuccessfulSyncAtEpochMillis
        f.http.denial = 503
        assertTrue(f.repository.refresh(f.elder.id, dates) is Outcome.Failure)
        val cached = withTimeout(5_000) { f.repository.observeEvents(f.elder.id, dates).first() }
        assertEquals(AccessState.AUTHORIZED, cached.access); assertTrue(cached.value.isNotEmpty()); assertTrue(cached.isStale)
        assertEquals(old, f.raw(f.elder.id, dates)); assertEquals(oldSync, cached.lastSuccessfulSyncAtEpochMillis)
        f.http.denial = null; f.seed(dates)
        assertFalse(f.repository.observeEvents(f.elder.id, dates).first().isStale)
    } }

    @Test fun deniedTargetDoesNotInvalidateAnotherEldersCachedEvents() = runBlocking { fixture { f ->
        val another = f.elder.id + "-another"
        f.seed(dates); assertTrue(f.repository.refresh(another, dates) is Outcome.Success)
        val old = f.raw(another, dates)
        // Epoch zero is a real successful timestamp, not a missing-sync marker.
        f.database.openHelper.writableDatabase.execSQL(
            "UPDATE chain_documents SET syncedAt=0 WHERE target=? AND kind=?",
            arrayOf(f.elder.id, "dose-events:${dates.fromDate}:${dates.throughDate}"))
        f.http.denial = 403
        assertTrue(f.repository.refresh(f.elder.id, dates) is Outcome.Failure)
        val denied = f.repository.observeEvents(f.elder.id, dates).first()
        assertEquals(AccessState.REVOKED, denied.access)
        assertEquals(0L, denied.lastSuccessfulSyncAtEpochMillis)
        assertEquals(AccessState.AUTHORIZED, f.repository.observeEvents(another, dates).first().access)
        assertEquals(old, f.raw(another, dates))
    } }

    @Test fun lateOldSessionDenialCannotPublishIntoTheReplacementSession() = runBlocking { fixture { f ->
        f.seed(dates)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.http.delayDenial = entered to release
        val pending = CoroutineScope(currentCoroutineContext()).async { f.repository.refresh(f.elder.id, dates) }
        try {
            withTimeout(5_000) { entered.await() }
            assertTrue(f.repository.services.signOut() is Outcome.Success)
            assertTrue(f.repository.login(UserRole.CAREGIVER, f.caregiver.displayName) is Outcome.Success)
            withTimeout(5_000) { f.repository.observeSession().first { it.readiness == AuthReadiness.READY } }
            // Reauthentication invalidates the former cache authority even when the retained installation/user match.
            val afterLogin = f.raw(f.elder.id, dates)
            assertEquals("UNKNOWN", afterLogin.second)
            release.complete(Unit); assertTrue(withTimeout(5_000) { pending.await() } is Outcome.Failure)
            assertEquals(AuthReadiness.READY, f.repository.observeSession().first().readiness)
            assertEquals(afterLogin, f.raw(f.elder.id, dates))
            assertEquals(AccessState.UNKNOWN, f.repository.observeEvents(f.elder.id, dates).first().access)
        } finally { release.complete(Unit); pending.cancelAndJoin() }
    } }

    private suspend fun fixture(work: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try {
            assertTrue(f.repository.login(UserRole.CAREGIVER, f.caregiver.displayName) is Outcome.Success)
            withTimeout(5_000) { f.repository.observeSession().first { it.readiness == AuthReadiness.READY } }
            withTimeout(5_000) { f.repository.services.trusted.first { it != null } }
            assertTrue(f.repository.services.pairing.refresh() is Outcome.Success)
            work(f)
        } finally { onMain { f.store.clear() }; f.database.close(); f.repository.close() }
    }
    private inner class Fixture {
        private val suffix = UUID.randomUUID().toString()
        val caregiver = User("denial-caregiver-$suffix", "Fictional caregiver", UserRole.CAREGIVER)
        val elder = User("denial-elder-$suffix", "Fictional elder", UserRole.ELDER)
        val relationship = CareRelationshipView("denial-relation-$suffix", elder.id, caregiver.id, elder, caregiver,
            CareRelationshipStatus.ACTIVE, 1_797_000_000_000L)
        val http = Script(caregiver, elder, relationship)
        val repository = DebugChainRepository(instrumentation.targetContext.applicationContext,
            "http://event-denial.invalid", "denial-$suffix", http)
        val database = ChainCache.open(instrumentation.targetContext.applicationContext)
        val store = ViewModelStore()
        suspend fun seed(range: LocalDateRange) { assertTrue(repository.refresh(elder.id, range) is Outcome.Success) }
        suspend fun history(range: LocalDateRange): CaregiverHistoryViewModel {
            val scope = checkNotNull(repository.services.scope.value)
            lateinit var result: CaregiverHistoryViewModel
            onMain { result = CaregiverHistoryViewModel(repository, scope, UserView(elder.id, elder.displayName, elder.role), range); store.put("history", result) }
            return result
        }
        fun raw(target: String, range: LocalDateRange): Pair<String, String> {
            database.openHelper.readableDatabase.query("SELECT body, access FROM chain_documents WHERE target=? AND kind=?",
                arrayOf(target, "dose-events:${range.fromDate}:${range.throughDate}")).use { rows ->
                assertTrue(rows.moveToFirst()); val result = rows.getString(0) to rows.getString(1); assertFalse(rows.moveToNext()); return result
            }
        }
    }
    private inner class Script(val caregiver: User, val elder: User, val relationship: CareRelationshipView) : ChainHttp("http://event-denial.invalid") {
        @Volatile var denial: Int? = null
        @Volatile var delayDenial: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
        val eventReads = CopyOnWriteArrayList<String>()
        val relationshipReads = AtomicInteger()
        override suspend fun call(method: String, path: String, token: String?, operationId: String?, json: String?, photo: java.io.File?, photoType: String?): Reply {
            if (method == "POST" && path == "/api/v1/auth/anonymous") return reply(this@EventReadDenialRoomTest.json.encodeToString(AuthResponse.serializer(),
                AuthResponse("fictional-session-token", "Bearer", 3_600, caregiver)), 201)
            if (method == "GET" && path == "/api/v1/pairing-requests") return reply(this@EventReadDenialRoomTest.json.encodeToString(ItemsResponse.serializer(PairingRequestView.serializer()), ItemsResponse(emptyList())))
            if (method == "GET" && path == "/api/v1/care-relationships") {
                relationshipReads.incrementAndGet()
                return reply(this@EventReadDenialRoomTest.json.encodeToString(ItemsResponse.serializer(CareRelationshipView.serializer()), ItemsResponse(listOf(relationship,
                    relationship.copy(id = relationship.id + "-another", elderId = elder.id + "-another", elder = elder.copy(id = elder.id + "-another"))))))
            }
            if (method == "GET" && path.startsWith("/api/v1/elders/") && path.contains("/dose-events?")) {
                eventReads += path
                delayDenial?.let { (entered, release) -> entered.complete(Unit); release.await(); return denied(404) }
                denial?.let { return denied(it) }
                val target = path.removePrefix("/api/v1/elders/").substringBefore("/dose-events?")
                val date = path.substringAfter("fromDate=").substringBefore('&'); val key = OccurrenceKey("fictional-plan", date, "08:00")
                val planned = java.time.LocalDate.parse(date).atTime(8, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
                val evidence = OccurrenceGenerationEvidence("fictional-generation-$date", key, 1, planned, "Asia/Shanghai", planned,
                    28_800, TimeResolutionPolicy.P0_TIME_V1, "fictional-snapshot", planned)
                val row = DoseEventView("fictional-event-$date", target, key, 1, "fictional-medication", MedicationText("Fictional medicine", "Fixture only"),
                    "Asia/Shanghai", planned, planned + 7_200_000, evidence, DoseStatus.TAKEN, emptyList(), planned, takenRecordedAtEpochMillis = planned)
                return reply(this@EventReadDenialRoomTest.json.encodeToString(ItemsResponse.serializer(DoseEventView.serializer()), ItemsResponse(listOf(row))))
            }
            error("Unexpected fictional request")
        }
        private fun denied(status: Int) = reply(this@EventReadDenialRoomTest.json.encodeToString(ApiError.serializer(),
            ApiError(if (status == 403) "ROLE_NOT_ALLOWED" else if (status == 404) "RESOURCE_NOT_ACCESSIBLE" else "SERVICE_UNAVAILABLE", "FIXTURE_PRIVATE_DETAIL", status == 503, "fixture-request")), status)
        private fun reply(body: String, status: Int = 200) = Reply(status, body.toByteArray(), "application/json", null, "fixture-request")
    }
    private fun onMain(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(20) }
}
