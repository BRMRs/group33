package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModelStore
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.Cached
import com.group33.medicationreminder.core.data.contract.CaregiverDashboardRepository
import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.medicationreminder.core.data.contract.ElderOverview
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.core.data.contract.SyncCompletion
import com.group33.medicationreminder.core.data.contract.SyncSummary
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ElderSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

/** The double controls inputs/timing; assertions exercise the real production ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverFeedbackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val range = LocalDateRange("2026-10-01", "2026-10-30")
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-caregiver")
    private val offline = RepositoryError.Local(LocalFailureCode.OFFLINE)
    private val t0 = 1_800_000_000_000L
    private val snapshot: ElderSnapshot by lazy {
        val json = ContractProtocol.json
        val examples = json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json")).bufferedReader().use { it.readText() },
        ).jsonObject
        json.decodeFromJsonElement(examples.getValue("snapshot"))
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun `initial observation uses selected Elder and dates without starting a network refresh`() = runTest(dispatcher) {
        val known = cached()
        val repository = repository(known)
        val model = model(repository)
        assertTrue(model.state.value.isLoading)
        runCurrent()

        assertEquals(listOf(snapshot.elder.id to range), repository.observations)
        assertTrue(repository.refreshes.isEmpty())
        assertSame(known, model.state.value.overview)
        assertFalse(model.state.value.isLoading)
        assertFalse(model.state.value.isRefreshing)
    }

    @Test fun `offline refresh preserves nonempty cache last sync and last known capability`() = runTest(dispatcher) {
        val known = cached()
        assertTrue(known.value.events.isNotEmpty())
        assertNotNull(known.value.capability)
        val repository = repository(known)
        val response = CompletableDeferred<Outcome<SyncSummary>>()
        repository.onRefresh = { response.await() }
        val model = model(repository)
        runCurrent()

        model.refresh()
        runCurrent()
        assertTrue(model.state.value.isRefreshing)
        assertSame(known, model.state.value.overview)
        response.complete(Outcome.Failure(offline))
        runCurrent()

        val state = model.state.value
        assertEquals(listOf(snapshot.elder.id to range), repository.refreshes)
        assertEquals(snapshot.elder.id, state.elderId)
        assertEquals(range, state.range)
        assertSame(known, state.overview)
        assertEquals(t0, state.overview?.lastSuccessfulSyncAtEpochMillis)
        assertEquals(known.value.capability, state.overview?.value?.capability)
        assertEquals(offline, state.refreshError)
        assertTrue(state.isStale)
        assertFalse(known.isStale)
        assertFalse(state.isRefreshing)
        assertFalse(state.isRefreshComplete)
    }

    @Test fun `offline without local data does not invent records or a successful sync time`() = runTest(dispatcher) {
        val empty = cached().copy(
            value = ElderOverview(snapshot.elder, emptyList(), emptyList(), null),
            hasLocalData = false,
            lastSuccessfulSyncAtEpochMillis = null,
        )
        val repository = repository(empty)
        repository.onRefresh = { Outcome.Failure(offline) }
        val model = model(repository)
        runCurrent()
        model.refresh()
        runCurrent()

        assertFalse(model.state.value.isLoading)
        assertFalse(model.state.value.overview!!.hasLocalData)
        assertTrue(model.state.value.overview!!.value.events.isEmpty())
        assertNull(model.state.value.overview!!.lastSuccessfulSyncAtEpochMillis)
        assertNull(model.state.value.overview!!.value.capability)
        assertEquals(offline, model.state.value.refreshError)
        assertFalse(model.state.value.isRefreshing)
    }

    @Test fun `successful retry clears error but only observation changes data and sync time`() = runTest(dispatcher) {
        val known = cached()
        val repository = repository(known)
        repository.onRefresh = { Outcome.Failure(offline) }
        val model = model(repository)
        runCurrent()
        model.refresh()
        runCurrent()
        assertEquals(offline, model.state.value.refreshError)

        repository.onRefresh = { Outcome.Success(summary(SyncCompletion.COMPLETE)) }
        model.refresh()
        runCurrent()
        assertTrue(model.state.value.isRefreshComplete)
        assertNull(model.state.value.refreshError)
        assertSame(known, model.state.value.overview)
        assertEquals(t0, model.state.value.overview!!.lastSuccessfulSyncAtEpochMillis)

        val fresh = known.copy(
            value = known.value.copy(capability = known.value.capability!!.copy(observationSequence = 2)),
            lastSuccessfulSyncAtEpochMillis = t0 + 1_000,
        )
        repository.updates.value = fresh
        runCurrent()
        assertSame(fresh, model.state.value.overview)
        assertFalse(model.state.value.isStale)
    }

    @Test fun `partial and failed summaries are not complete success and retain typed details`() = runTest(dispatcher) {
        for (completion in listOf(SyncCompletion.PARTIAL, SyncCompletion.FAILED)) {
            val known = cached()
            val repository = repository(known)
            val summary = summary(completion).copy(error = offline)
            repository.onRefresh = { Outcome.Success(summary) }
            val model = model(repository)
            runCurrent()
            model.refresh()
            runCurrent()

            assertEquals(Outcome.Success(summary), model.state.value.refreshResult)
            assertEquals(offline, model.state.value.refreshError)
            assertSame(known, model.state.value.overview)
            assertTrue(model.state.value.isStale)
            assertFalse(model.state.value.isRefreshComplete)
            assertFalse(model.state.value.isRefreshing)
        }
    }

    @Test fun `incomplete summary without error is still not complete success`() = runTest(dispatcher) {
        val repository = repository(cached())
        repository.onRefresh = { Outcome.Success(summary(SyncCompletion.PARTIAL)) }
        val model = model(repository)
        runCurrent()
        model.refresh()
        runCurrent()

        assertNull(model.state.value.refreshError)
        assertTrue(model.state.value.hasRefreshFailure)
        assertTrue(model.state.value.isStale)
        assertFalse(model.state.value.isRefreshComplete)
    }

    @Test fun `repeated taps share one in flight refresh and allow a later retry`() = runTest(dispatcher) {
        val repository = repository(cached())
        val response = CompletableDeferred<Outcome<SyncSummary>>()
        repository.onRefresh = { response.await() }
        val model = model(repository)
        runCurrent()
        model.refresh()
        model.refresh()
        runCurrent()
        model.refresh()
        assertEquals(1, repository.refreshes.size)
        response.complete(Outcome.Success(summary(SyncCompletion.COMPLETE)))
        runCurrent()
        model.refresh()
        runCurrent()
        assertEquals(2, repository.refreshes.size)
    }

    @Test fun `cancellation finishes refresh without inventing a typed failure`() = runTest(dispatcher) {
        val known = cached()
        val repository = repository(known)
        repository.onRefresh = { throw CancellationException("cancelled test request") }
        val model = model(repository)
        runCurrent()
        model.refresh()
        runCurrent()

        assertSame(known, model.state.value.overview)
        assertFalse(model.state.value.isRefreshing)
        assertNull(model.state.value.refreshResult)
        assertFalse(model.state.value.refreshFailedUnexpectedly)
    }

    @Test fun `unexpected refresh failure retains cache and recovers without guessing an error code`() = runTest(dispatcher) {
        val known = cached()
        val repository = repository(known)
        repository.onRefresh = { throw IllegalStateException("test-only failure") }
        val model = model(repository)
        runCurrent()
        model.refresh()
        runCurrent()

        assertSame(known, model.state.value.overview)
        assertTrue(model.state.value.refreshFailedUnexpectedly)
        assertNull(model.state.value.refreshError)
        assertTrue(model.state.value.isStale)
        assertFalse(model.state.value.isRefreshing)
        repository.onRefresh = { Outcome.Success(summary(SyncCompletion.COMPLETE)) }
        model.refresh()
        runCurrent()
        assertFalse(model.state.value.refreshFailedUnexpectedly)
        assertTrue(model.state.value.isRefreshComplete)
    }

    @Test fun `refresh receipt cannot hide an observation failure`() = runTest(dispatcher) {
        val known = cached()
        val repository = repository(known)
        repository.source = flow { emit(known); throw IllegalStateException("test-only read failure") }
        val model = model(repository)
        runCurrent()
        model.refresh()
        runCurrent()

        assertSame(known, model.state.value.overview)
        assertEquals(Outcome.Success(summary(SyncCompletion.COMPLETE)), model.state.value.refreshResult)
        assertTrue(model.state.value.observationFailed)
        assertFalse(model.state.value.isRefreshComplete)
    }

    @Test fun `failed observation retains cache and refresh restarts the observation`() = runTest(dispatcher) {
        val known = cached()
        val repository = repository(known)
        repository.source = flow { emit(known); throw IllegalStateException("test-only read failure") }
        val model = model(repository)
        runCurrent()
        assertSame(known, model.state.value.overview)
        assertTrue(model.state.value.observationFailed)
        assertTrue(model.state.value.isStale)
        assertFalse(model.state.value.isLoading)

        val fresh = known.copy(lastSuccessfulSyncAtEpochMillis = t0 + 1_000)
        repository.updates.value = fresh
        repository.source = repository.updates
        model.refresh()
        runCurrent()
        assertEquals(2, repository.observations.size)
        assertSame(fresh, model.state.value.overview)
        assertFalse(model.state.value.observationFailed)
    }

    private fun model(repository: CaregiverDashboardRepository) =
        CaregiverFeedbackViewModel(repository, snapshot.elder.id, range, scope).also { store.put("feedback", it) }

    @Test fun `empty observation completion stops loading and can recover through refresh`() = runTest(dispatcher) {
        val known = cached()
        val repository = repository(known).apply { source = emptyFlow() }
        val model = model(repository)
        runCurrent()

        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.observationFailed)
        assertNull(model.state.value.overview)

        // A successful receipt cannot turn an empty observation into a loaded cache.
        model.refresh()
        runCurrent()
        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.observationFailed)
        assertFalse(model.state.value.isRefreshComplete)
        assertNull(model.state.value.overview)

        // A finite observation that actually emits a cache is valid and clears the failure.
        repository.source = flow { emit(known) }
        model.refresh()
        runCurrent()
        assertFalse(model.state.value.isLoading)
        assertFalse(model.state.value.observationFailed)
        assertSame(known, model.state.value.overview)
        assertEquals(t0, model.state.value.overview?.lastSuccessfulSyncAtEpochMillis)
        assertTrue(model.state.value.isRefreshComplete)
        assertEquals(listOf(snapshot.elder.id to range, snapshot.elder.id to range), repository.refreshes)
    }

    private fun summary(completion: SyncCompletion) = SyncSummary(scope, snapshot.elder.id, completion, t0 + 1_000, null)

    private fun cached(): Cached<ElderOverview> {
        val events = snapshot.doseEvents.map { event ->
            DoseEventState(
                event.occurrence, event.id, event.generationEvidence, event.medicationId,
                event.medicationSnapshot, event.responseDeadlineEpochMillis, event.status, event.status, emptyList(),
            )
        }
        return Cached(ElderOverview(snapshot.elder, snapshot.medications, events, snapshot.capability), true, t0, false, AccessState.AUTHORIZED)
    }

    private fun repository(initial: Cached<ElderOverview>) = ControlledRepository(initial, summary(SyncCompletion.COMPLETE))

    private class ControlledRepository(initial: Cached<ElderOverview>, success: SyncSummary) : CaregiverDashboardRepository {
        val updates = MutableStateFlow(initial)
        var source: Flow<Cached<ElderOverview>> = updates
        val observations = mutableListOf<Pair<String, LocalDateRange>>()
        val refreshes = mutableListOf<Pair<String, LocalDateRange>>()
        var onRefresh: suspend () -> Outcome<SyncSummary> = { Outcome.Success(success) }

        override fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>> {
            observations += elderId to range
            return source
        }

        override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
            refreshes += elderId to range
            return onRefresh()
        }
    }
}
