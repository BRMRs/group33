package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModelStore
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import java.time.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val range = LocalDateRange("2026-10-01", "2026-10-30")
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-caregiver")
    private val snapshot: ElderSnapshot by lazy {
        val json = ContractProtocol.json
        val examples = json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        json.decodeFromJsonElement(examples.getValue("snapshot"))
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun `default includes exactly thirty Elder local dates across leap day and year boundary`() {
        assertEquals(LocalDateRange("2024-01-31", "2024-02-29"), CaregiverHistoryViewModel.defaultRange(LocalDate.parse("2024-02-29")))
        assertEquals(LocalDateRange("2025-12-03", "2026-01-01"), CaregiverHistoryViewModel.defaultRange(LocalDate.parse("2026-01-01")))
    }

    @Test fun `initial read calls selected Elder and range without invoking action methods`() = runTest(dispatcher) {
        val repo = Repository(cached())
        val model = model(repo)
        assertEquals(HistoryMessage.LOADING, model.state.value.message)
        runCurrent()
        assertEquals(listOf(snapshot.elder.id to range), repo.queries)
        assertSame(repo.updates.value, model.state.value.cached)
        assertEquals(HistoryMessage.RESULTS, model.state.value.message)
    }

    @Test fun `one date and ninety inclusive dates are accepted but ninety one never queries`() = runTest(dispatcher) {
        val repo = Repository(cached(emptyList()))
        val model = model(repo)
        runCurrent()
        assertTrue(model.applyRange("2026-01-01", "2026-01-01")); runCurrent()
        assertEquals(LocalDateRange("2026-01-01", "2026-01-01"), repo.queries.last().second)
        assertTrue(model.applyRange("2026-01-01", "2026-03-31")); runCurrent()
        val count = repo.queries.size
        assertFalse(model.applyRange("2026-01-01", "2026-04-01")); runCurrent()
        assertEquals(count, repo.queries.size)
        assertEquals(HistoryRangeError.TOO_MANY_DATES, model.state.value.rangeError)
        assertEquals(LocalDateRange("2026-01-01", "2026-03-31"), model.state.value.range)
    }

    @Test fun `invalid or reversed drafts retain applied cache details and query`() = runTest(dispatcher) {
        val repo = Repository(cached())
        val model = model(repo); runCurrent()
        model.selectEvent(events().first().occurrence)
        val previous = model.state.value
        for ((from, through, error) in listOf(
            Triple("", "2026-10-30", HistoryRangeError.INVALID_DATE),
            Triple("2026-02-30", "2026-10-30", HistoryRangeError.INVALID_DATE),
            Triple("2026-10-02", "2026-10-01", HistoryRangeError.REVERSED),
        )) {
            assertFalse(model.applyRange(from, through)); runCurrent()
            assertEquals(error, model.state.value.rangeError)
            assertSame(previous.cached, model.state.value.cached)
            assertEquals(previous.range, model.state.value.range)
            assertEquals(previous.selectedEvent, model.state.value.selectedEvent)
        }
        assertEquals(1, repo.queries.size)
    }

    @Test fun `oversized initial range cannot query and a corrected range recovers`() = runTest(dispatcher) {
        val repo = Repository(cached(emptyList()))
        val model = CaregiverHistoryViewModel(repo, scope, snapshot.elder, LocalDateRange("2026-01-01", "2026-04-01"))
            .also { store.put("history", it) }
        runCurrent()
        assertTrue(repo.queries.isEmpty())
        assertEquals(HistoryRangeError.TOO_MANY_DATES, model.state.value.rangeError)
        assertTrue(model.applyRange("2026-10-01", "2026-10-30")); runCurrent()
        assertNull(model.state.value.rangeError)
        assertEquals(HistoryMessage.EMPTY, model.state.value.message)
    }

    @Test fun `missing local data authorized empty and unknown access are distinct`() = runTest(dispatcher) {
        val repo = Repository(cached(emptyList()).copy(hasLocalData = false))
        val model = model(repo); runCurrent()
        assertEquals(HistoryMessage.NO_LOCAL_DATA, model.state.value.message)
        repo.updates.value = cached(emptyList()); runCurrent()
        assertEquals(HistoryMessage.EMPTY, model.state.value.message)
        repo.updates.value = cached().copy(access = AccessState.UNKNOWN); runCurrent()
        assertEquals(HistoryMessage.ACCESS_UNKNOWN, model.state.value.message)
        assertTrue(model.state.value.events.isEmpty())
        assertNull(model.state.value.cached)
    }

    @Test fun `details track immutable occurrence and snapshot text rather than list position`() = runTest(dispatcher) {
        val first = events().first()
        val second = first.copy(occurrence = first.occurrence.copy(planId = "test-other-plan"),
            medicationSnapshot = first.medicationSnapshot.copy(displayName = "other test medication"))
        val repo = Repository(cached(listOf(first, second)))
        val model = model(repo); runCurrent()
        model.selectEvent(first.occurrence)
        assertEquals(first.medicationSnapshot, model.state.value.selectedEvent?.medicationSnapshot)
        repo.updates.value = cached(listOf(second, first)); runCurrent()
        assertEquals(first, model.state.value.selectedEvent)
        model.selectEvent(first.occurrence.copy(planId = "not-loaded"))
        assertEquals(first, model.state.value.selectedEvent)
        repo.updates.value = cached(listOf(second)); runCurrent()
        assertNull(model.state.value.selectedEvent)
        assertNull(model.state.value.selectedOccurrence)
    }

    @Test fun `query change clears old details and waiting source never shows previous date records`() = runTest(dispatcher) {
        val repo = Repository(cached())
        val model = model(repo); runCurrent()
        model.selectEvent(events().first().occurrence)
        repo.source = MutableStateFlow(cached(emptyList()))
        assertTrue(model.applyRange("2026-10-02", "2026-10-02"))
        assertNull(model.state.value.cached)
        assertNull(model.state.value.selectedOccurrence)
        runCurrent()
        assertEquals(LocalDateRange("2026-10-02", "2026-10-02"), repo.queries.last().second)
        assertEquals(HistoryMessage.EMPTY, model.state.value.message)
    }

    @Test fun `read failure preserves known records details last success and stale marker then retry recovers`() = runTest(dispatcher) {
        val known = cached()
        val repo = Repository(known)
        repo.source = flow { emit(known); throw IllegalStateException("test read failure") }
        val model = model(repo); runCurrent()
        assertSame(known, model.state.value.cached)
        assertEquals(HistoryMessage.RESULTS, model.state.value.message)
        assertTrue(model.state.value.isStale)
        assertTrue(model.state.value.observationFailed)
        assertEquals(123L, model.state.value.cached?.lastSuccessfulSyncAtEpochMillis)
        repo.source = repo.updates
        model.retryObservation(); runCurrent()
        assertFalse(model.state.value.observationFailed)
        assertFalse(model.state.value.isStale)
        assertEquals(2, repo.queries.size)
    }

    @Test fun `failure without data and an empty completed source are unavailable rather than empty history`() = runTest(dispatcher) {
        for (source in listOf(flow<Cached<List<DoseEventState>>> { throw IllegalStateException("test") }, emptyFlow())) {
            val repo = Repository(cached()).apply { this.source = source }
            val model = model(repo); runCurrent()
            assertEquals(HistoryMessage.UNAVAILABLE, model.state.value.message)
            assertTrue(model.state.value.observationFailed)
            assertFalse(model.state.value.isLoading)
            assertTrue(model.state.value.events.isEmpty())
        }
    }

    @Test fun `out of range adapter result is rejected without truncating it into a fake empty result`() = runTest(dispatcher) {
        val known = cached()
        val repo = Repository(known)
        val model = model(repo); runCurrent()
        repo.updates.value = cached(events().map { it.copy(occurrence = it.occurrence.copy(localDate = "2026-11-01")) })
        runCurrent()
        assertSame(known, model.state.value.cached)
        assertTrue(model.state.value.observationFailed)
        assertEquals(HistoryMessage.RESULTS, model.state.value.message)
    }

    @Test fun `unknown access with invalid old payload still immediately removes authorized data`() = runTest(dispatcher) {
        val repo = Repository(cached())
        val model = model(repo); runCurrent()
        model.selectEvent(events().first().occurrence)
        repo.updates.value = cached(events().map { it.copy(occurrence = it.occurrence.copy(localDate = "2026-11-01")) })
            .copy(access = AccessState.UNKNOWN)
        runCurrent()
        assertEquals(AccessState.UNKNOWN, model.state.value.access)
        assertTrue(model.state.value.events.isEmpty())
        assertNull(model.state.value.selectedEvent)
    }

    @Test fun `old noncooperative data and failure cannot replace a new Elder or date selection`() = runTest(dispatcher) {
        val late = LateSource()
        val a = Repository(cached()).apply { source = late }
        val model = model(a); runCurrent()
        late.emit(cached())
        model.selectEvent(events().first().occurrence)
        val b = Repository(cached(emptyList()))
        val dates = LocalDateRange("2026-10-02", "2026-10-03")
        model.selectContext(b, scope, snapshot.elder.copy(id = "other-test-elder"), dates); runCurrent()
        late.emit(cached().copy(access = AccessState.REVOKED))
        late.fail(IllegalStateException("late test failure")); runCurrent()
        assertEquals(2, late.delivered)
        assertEquals("other-test-elder", model.state.value.elder?.id)
        assertEquals(dates, model.state.value.range)
        assertEquals(HistoryMessage.EMPTY, model.state.value.message)
        assertFalse(model.state.value.observationFailed)
        assertNull(model.state.value.selectedOccurrence)
    }

    @Test fun `known revocation hides details cannot retry and remains revoked when returning`() = runTest(dispatcher) {
        val repo = Repository(cached())
        val model = model(repo); runCurrent()
        model.selectEvent(events().first().occurrence)
        repo.updates.value = cached().copy(access = AccessState.REVOKED); runCurrent()
        assertEquals(HistoryMessage.REVOKED, model.state.value.message)
        assertNull(model.state.value.cached)
        assertNull(model.state.value.selectedEvent)
        model.retryObservation(); runCurrent()
        assertEquals(1, repo.queries.size)
        val b = Repository(cached(emptyList()))
        model.selectContext(b, scope, snapshot.elder.copy(id = "other-test-elder"), range); runCurrent()
        model.selectContext(repo, scope, snapshot.elder, range); runCurrent()
        assertEquals(HistoryMessage.REVOKED, model.state.value.message)
        assertEquals(1, repo.queries.size)
    }

    @Test fun `session replacement and clear invalidate late observation even with same Elder id`() = runTest(dispatcher) {
        val late = LateSource()
        val a = Repository(cached()).apply { source = late }
        val model = model(a); runCurrent()
        val otherScope = scope.copy(environmentId = "test-local")
        val b = Repository(cached(emptyList()))
        model.selectContext(b, otherScope, snapshot.elder, range); runCurrent()
        model.clearContext()
        late.emit(cached()); late.finish(); runCurrent()
        assertEquals(1, late.delivered)
        assertEquals(HistoryMessage.SELECT_ELDER, model.state.value.message)
        assertNull(model.state.value.scope)
        assertNull(model.state.value.cached)
        assertFalse(model.applyRange("2026-10-01", "2026-10-01"))
    }

    private fun model(repository: DoseEventRepository) = CaregiverHistoryViewModel(repository, scope, snapshot.elder, range)
        .also { store.put("history", it) }
    private fun events() = snapshot.doseEvents.map {
        DoseEventState(it.occurrence, it.id, it.generationEvidence, it.medicationId, it.medicationSnapshot,
            it.responseDeadlineEpochMillis, it.status, it.status, emptyList())
    }
    private fun cached(events: List<DoseEventState> = events()) = Cached(events, true, 123L, false, AccessState.AUTHORIZED)

    private class Repository(initial: Cached<List<DoseEventState>>) : DoseEventRepository {
        val updates = MutableStateFlow(initial)
        var source: Flow<Cached<List<DoseEventState>>> = updates
        val queries = mutableListOf<Pair<String, LocalDateRange>>()
        override fun observeEvents(elderId: String, range: LocalDateRange): Flow<Cached<List<DoseEventState>>> {
            queries += elderId to range
            return source
        }
        override fun observeLocalAction(actionId: String): Flow<LocalActionReceipt?> = error("History must not observe an action")
        override suspend fun recordAction(occurrence: OccurrenceKey, kind: DoseActionKind): Outcome<LocalActionReceipt> = error("History must not record an action")
        override suspend fun retryAction(actionId: String): Outcome<RetryQueued> = error("History must not retry an action")
    }

    @OptIn(InternalCoroutinesApi::class)
    private class LateSource : Flow<Cached<List<DoseEventState>>> {
        private lateinit var collector: FlowCollector<Cached<List<DoseEventState>>>
        private lateinit var completion: Continuation<Unit>
        var delivered = 0
        override suspend fun collect(collector: FlowCollector<Cached<List<DoseEventState>>>) {
            this.collector = collector
            suspendCoroutine<Unit> { completion = it }
        }
        suspend fun emit(value: Cached<List<DoseEventState>>) { collector.emit(value); delivered++ }
        fun finish() { completion.resume(Unit) }
        fun fail(error: Exception) { completion.resumeWithException(error) }
    }
}
