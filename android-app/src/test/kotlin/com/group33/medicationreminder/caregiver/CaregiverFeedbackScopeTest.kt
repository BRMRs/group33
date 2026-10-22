package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModelStore
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ElderSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverFeedbackScopeTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-user")
    private val dates = LocalDateRange("2026-10-01", "2026-10-30")
    private val otherDates = LocalDateRange("2026-09-01", "2026-09-30")
    private val offline = RepositoryError.Local(LocalFailureCode.OFFLINE)
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

    @Test fun `selection clears old data and errors before observing the next Elder`() = runTest(dispatcher) {
        val a = repository("A")
        a.onRefresh = { Outcome.Failure(offline) }
        val b = repository("B")
        val model = model(a)
        runCurrent()
        model.refresh()
        runCurrent()
        assertEquals(offline, model.state.value.refreshError)

        model.selectContext(b, scope, "B", otherDates)
        assertEquals("B", model.state.value.elderId)
        assertEquals(otherDates, model.state.value.range)
        assertNull(model.state.value.overview)
        assertNull(model.state.value.refreshError)
        assertNull(model.state.value.refreshResult)
        assertTrue(model.state.value.isLoading)
        runCurrent()
        assertSame(b.updates.value, model.state.value.overview)
        assertEquals(listOf("B" to otherDates), b.observations)
    }

    @Test fun `late A success and typed failure cannot overwrite B or finish B refresh`() = runTest(dispatcher) {
        for (late in listOf(Outcome.Success(summary("A")), Outcome.Failure(offline))) {
            val a = repository("A")
            val oldReply = pendingReply(a)
            val b = repository("B")
            val newReply = pendingReply(b)
            val model = model(a)
            runCurrent()
            model.refresh()
            runCurrent()
            model.selectContext(b, scope, "B", dates)
            runCurrent()
            model.refresh()
            runCurrent()
            oldReply.await().resume(late)
            runCurrent()

            assertEquals(1, a.completedRefreshes)
            assertSame(b.updates.value, model.state.value.overview)
            assertNull(model.state.value.refreshResult)
            assertTrue(model.state.value.isRefreshing)
            newReply.await().resume(Outcome.Success(summary("B")))
            runCurrent()
            assertTrue(model.state.value.isRefreshComplete)
        }
    }

    @Test fun `late A exception cannot set B error or stop B loading indicator`() = runTest(dispatcher) {
        val a = repository("A")
        val oldReply = pendingReply(a)
        val b = repository("B")
        val newReply = pendingReply(b)
        val model = model(a)
        runCurrent()
        model.refresh()
        runCurrent()
        model.selectContext(b, scope, "B", dates)
        runCurrent()
        model.refresh()
        runCurrent()
        oldReply.await().resumeWithException(IllegalStateException("test-only late failure"))
        runCurrent()

        assertTrue(model.state.value.isRefreshing)
        assertFalse(model.state.value.refreshFailedUnexpectedly)
        newReply.await().resume(Outcome.Success(summary("B")))
        runCurrent()
    }

    @Test fun `returning to A does not accept an earlier A refresh`() = runTest(dispatcher) {
        val a = repository("A")
        val oldReply = pendingReply(a)
        val nextA = repository("A")
        val newReply = pendingReply(nextA)
        val model = model(a)
        runCurrent()
        model.refresh()
        runCurrent()
        model.selectContext(repository("B"), scope, "B", dates)
        runCurrent()
        model.selectContext(nextA, scope, "A", dates)
        runCurrent()
        model.refresh()
        runCurrent()
        oldReply.await().resume(Outcome.Success(summary("A")))
        runCurrent()

        assertTrue(model.state.value.isRefreshing)
        assertNull(model.state.value.refreshResult)
        newReply.await().resume(Outcome.Success(summary("A")))
        runCurrent()
    }

    @Test fun `same Elder is isolated across dates user environment installation and same-context replacement`() = runTest(dispatcher) {
        val replacements = listOf(
            scope to otherDates,
            scope.copy(userId = "other-test-user") to dates,
            scope.copy(environmentId = "test-lan") to dates,
            scope.copy(installationId = "other-test-installation") to dates,
            scope to dates,
        )
        for ((nextScope, nextDates) in replacements) {
            val old = repository("A")
            val oldReply = pendingReply(old)
            val next = repository("A", nextScope)
            val model = model(old)
            runCurrent()
            model.refresh()
            runCurrent()
            model.selectContext(next, nextScope, "A", nextDates)
            assertNull(model.state.value.overview)
            runCurrent()
            oldReply.await().resume(Outcome.Failure(offline))
            runCurrent()
            assertEquals(nextScope, model.state.value.scope)
            assertEquals(nextDates, model.state.value.range)
            assertSame(next.updates.value, model.state.value.overview)
            assertNull(model.state.value.refreshResult)
        }
    }

    @Test fun `old observer callback is ignored even if its source ignores cancellation`() = runTest(dispatcher) {
        val a = repository("A")
        val late = LateObservation()
        a.source = late
        val b = repository("B")
        val model = model(a)
        runCurrent()
        late.emit(a.updates.value)
        model.selectContext(b, scope, "B", dates)
        runCurrent()
        late.emit(a.updates.value)
        assertSame(b.updates.value, model.state.value.overview)
        late.finish()
        runCurrent()
    }

    @Test fun `revocation hides cache and late success or authorized callbacks cannot restore it`() = runTest(dispatcher) {
        val a = repository("A")
        val late = LateObservation()
        a.source = late
        val reply = pendingReply(a)
        val model = model(a)
        runCurrent()
        late.emit(a.updates.value)
        model.refresh()
        runCurrent()
        late.emit(a.updates.value.copy(access = AccessState.REVOKED))

        assertEquals(AccessState.REVOKED, model.state.value.access)
        assertNull(model.state.value.overview)
        assertFalse(model.state.value.isRefreshing)
        reply.await().resume(Outcome.Success(summary("A")))
        late.emit(a.updates.value)
        runCurrent()
        assertNull(model.state.value.overview)
        assertNull(model.state.value.refreshResult)
        assertFalse(model.state.value.isRefreshComplete)
        late.finish()
        runCurrent()
    }

    @Test fun `return to revoked Elder retains denial across date changes without blocking other scope`() = runTest(dispatcher) {
        val a = repository("A")
        val model = model(a)
        runCurrent()
        a.updates.value = a.updates.value.copy(access = AccessState.REVOKED)
        runCurrent()
        model.selectContext(repository("B"), scope, "B", dates)
        runCurrent()
        val revokedA = repository("A")
        model.selectContext(revokedA, scope, "A", otherDates)
        model.refresh()
        runCurrent()
        assertEquals(AccessState.REVOKED, model.state.value.access)
        assertNull(model.state.value.overview)
        assertTrue(revokedA.observations.isEmpty())
        assertTrue(revokedA.refreshes.isEmpty())

        val otherScope = scope.copy(userId = "other-test-user")
        val otherUserA = repository("A", otherScope)
        model.selectContext(otherUserA, otherScope, "A", dates)
        runCurrent()
        assertEquals(AccessState.AUTHORIZED, model.state.value.access)
        assertSame(otherUserA.updates.value, model.state.value.overview)
    }

    @Test fun `unknown access hides content and a success receipt alone cannot authorize it`() = runTest(dispatcher) {
        val a = repository("A")
        val model = model(a)
        runCurrent()
        a.updates.value = a.updates.value.copy(access = AccessState.UNKNOWN)
        runCurrent()
        model.refresh()
        runCurrent()
        assertNull(model.state.value.overview)
        assertEquals(AccessState.UNKNOWN, model.state.value.access)
        assertFalse(model.state.value.isRefreshComplete)
        a.updates.value = a.updates.value.copy(access = AccessState.AUTHORIZED)
        runCurrent()
        assertSame(a.updates.value, model.state.value.overview)
    }

    @Test fun `foreign Elder cache including a revocation is not applied to the current Elder`() = runTest(dispatcher) {
        val a = repository("A")
        val correct = a.updates.value
        val model = model(a)
        runCurrent()
        for (access in listOf(AccessState.AUTHORIZED, AccessState.REVOKED)) {
            a.updates.value = cached("B").copy(access = access)
            runCurrent()
            assertSame(correct, model.state.value.overview)
            assertEquals(AccessState.AUTHORIZED, model.state.value.access)
            assertTrue(model.state.value.observationFailed)
        }
    }

    @Test fun `mismatched summary Elder or scope is rejected without replacing the correct cache`() = runTest(dispatcher) {
        for (bad in listOf(summary("B"), summary("A").copy(scope = scope.copy(environmentId = "test-lan")))) {
            val a = repository("A")
            a.onRefresh = { Outcome.Success(bad) }
            val model = model(a)
            runCurrent()
            model.refresh()
            runCurrent()
            assertSame(a.updates.value, model.state.value.overview)
            assertNull(model.state.value.refreshResult)
            assertTrue(model.state.value.refreshFailedUnexpectedly)
            assertFalse(model.state.value.isRefreshComplete)
        }
    }

    @Test fun `initial revoked cache prevents even an immediately requested refresh`() = runTest(dispatcher) {
        val a = repository("A")
        a.updates.value = a.updates.value.copy(access = AccessState.REVOKED)
        val model = model(a)
        model.refresh()
        runCurrent()
        assertEquals(AccessState.REVOKED, model.state.value.access)
        assertNull(model.state.value.overview)
        assertTrue(a.refreshes.isEmpty())
    }

    @Test fun `clearing context removes all selection state and blocks old or new work until rebound`() = runTest(dispatcher) {
        val a = repository("A")
        val reply = pendingReply(a)
        val model = model(a)
        runCurrent()
        model.refresh()
        runCurrent()
        model.clearContext()
        model.refresh()
        reply.await().resume(Outcome.Success(summary("A")))
        runCurrent()
        assertNull(model.state.value.scope)
        assertNull(model.state.value.elderId)
        assertNull(model.state.value.range)
        assertNull(model.state.value.overview)
        assertNull(model.state.value.refreshResult)
        assertFalse(model.state.value.isRefreshing)
        assertEquals(1, a.refreshes.size)
        model.selectContext(a, scope, "A", dates)
        runCurrent()
        assertSame(a.updates.value, model.state.value.overview)
    }

    @Test fun `session cleared during immediate observation restart cannot start an old refresh`() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val a = repository("A")
        a.source = flowOf(a.updates.value)
        val model = model(a)
        a.onObserve = { model.clearContext() }
        model.refresh()
        runCurrent()

        assertTrue(a.refreshes.isEmpty())
        assertNull(model.state.value.scope)
        assertNull(model.state.value.overview)
        assertNull(model.state.value.refreshResult)
        assertFalse(model.state.value.isRefreshing)
    }

    private fun model(a: CaregiverDashboardRepository) =
        CaregiverFeedbackViewModel(a, "A", dates, scope).also { store.put("feedback", it) }

    private fun cached(elder: String) = Cached(
        ElderOverview(snapshot.elder.copy(id = elder), emptyList(), emptyList(), snapshot.capability),
        true, 1_800_000_000_000L, false, AccessState.AUTHORIZED,
    )

    private fun summary(elder: String, context: RepositoryScope = scope) =
        SyncSummary(context, elder, SyncCompletion.COMPLETE, 1_800_000_001_000L, null)

    private fun repository(elder: String, context: RepositoryScope = scope) = ControlledRepository(cached(elder), summary(elder, context))

    /** Unlike suspendCancellableCoroutine, this deliberately permits a response after cancellation. */
    private fun pendingReply(repository: ControlledRepository): CompletableDeferred<Continuation<Outcome<SyncSummary>>> {
        val reply = CompletableDeferred<Continuation<Outcome<SyncSummary>>>()
        repository.onRefresh = { suspendCoroutine { reply.complete(it) } }
        return reply
    }

    private class ControlledRepository(initial: Cached<ElderOverview>, success: SyncSummary) : CaregiverDashboardRepository {
        val updates = MutableStateFlow(initial)
        var source: Flow<Cached<ElderOverview>> = updates
        val observations = mutableListOf<Pair<String, LocalDateRange>>()
        val refreshes = mutableListOf<Pair<String, LocalDateRange>>()
        var completedRefreshes = 0
        var onObserve: () -> Unit = {}
        var onRefresh: suspend () -> Outcome<SyncSummary> = { Outcome.Success(success) }
        override fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>> {
            onObserve()
            observations += elderId to range
            return source
        }
        override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
            refreshes += elderId to range
            val result = onRefresh()
            completedRefreshes++
            return result
        }
    }

    /** Adversarial test source: allows old callbacks after cancel instead of relying on Flow cooperation. */
    @OptIn(InternalCoroutinesApi::class)
    private class LateObservation : Flow<Cached<ElderOverview>> {
        private lateinit var collector: FlowCollector<Cached<ElderOverview>>
        private lateinit var finished: Continuation<Unit>
        override suspend fun collect(collector: FlowCollector<Cached<ElderOverview>>) {
            this.collector = collector
            suspendCoroutine<Unit> { finished = it }
        }
        suspend fun emit(value: Cached<ElderOverview>) { collector.emit(value) }
        fun finish() { finished.resume(Unit) }
    }
}
