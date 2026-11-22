package com.group33.medicationreminder.integration

import com.group33.medicationreminder.core.data.contract.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverHistorySyncRepositoryTest {
    private val range = LocalDateRange("2026-11-12", "2026-11-26")
    private val scope = RepositoryScope("test", "installation", "caregiver")
    private fun cached(access: AccessState) = Cached<List<DoseEventState>>(emptyList(),
        access == AccessState.AUTHORIZED, null, false, access)
    private class Fixture {
        var current = true
        val rows = MutableStateFlow(Cached<List<DoseEventState>>(emptyList(), false, null, true, AccessState.UNKNOWN))
        var firstEmissionGate: CompletableDeferred<Unit>? = null
        val reads = mutableListOf<Pair<String, LocalDateRange>>()
        val refreshes = mutableListOf<Pair<String, LocalDateRange>>()
        var refresh: suspend () -> Outcome<SyncSummary> = {
            Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE))
        }
        val events = Proxy.newProxyInstance(DoseEventRepository::class.java.classLoader,
            arrayOf(DoseEventRepository::class.java)) { _, method, args ->
            check(method.name == "observeEvents")
            reads += (args[0] as String) to (args[1] as LocalDateRange)
            flow {
                firstEmissionGate?.await()
                emitAll(rows)
            }
        } as DoseEventRepository
        val dashboard = object : CaregiverDashboardRepository {
            override fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>> =
                error("History must not open overview")
            override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
                refreshes += elderId to range
                return refresh()
            }
        }
        val repository = CaregiverHistorySyncRepository(events, dashboard, "elder") { current }
    }

    @Test fun `no read means no sync and current read syncs exactly its selected range`() = runTest {
        val f = Fixture()
        val flow = f.repository.observeEvents("elder", range)
        assertTrue(f.refreshes.isEmpty())
        val seen = mutableListOf<Cached<List<DoseEventState>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(seen) }
        runCurrent()
        assertEquals(listOf("elder" to range), f.reads)
        assertEquals(listOf("elder" to range), f.refreshes)
        assertEquals(AccessState.UNKNOWN, seen.last().access)
    }

    @Test fun `successful receipt alone does not fabricate authorized data`() = runTest {
        val f = Fixture()
        f.refresh = { Outcome.Success(SyncSummary(scope, "elder", SyncCompletion.COMPLETE, 1L, null)) }
        val seen = mutableListOf<Cached<List<DoseEventState>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.repository.observeEvents("elder", range).toList(seen) }
        runCurrent()
        assertEquals(listOf(AccessState.UNKNOWN), seen.map { it.access })
        f.rows.value = cached(AccessState.AUTHORIZED)
        runCurrent()
        assertEquals(AccessState.AUTHORIZED, seen.last().access)
    }

    @Test fun `old context or different Elder cannot start an observation or refresh`() = runTest {
        val f = Fixture()
        f.current = false
        assertTrue(f.repository.observeEvents("elder", range).toList().isEmpty())
        f.current = true
        assertTrue(f.repository.observeEvents("other", range).toList().isEmpty())
        assertTrue(f.reads.isEmpty())
        assertTrue(f.refreshes.isEmpty())
    }

    @Test fun `late old context cache and refresh results cannot publish`() = runTest {
        val f = Fixture()
        val receipt = CompletableDeferred<Unit>()
        f.refresh = { withContext(NonCancellable) { receipt.await() }; Outcome.Success(
            SyncSummary(scope, "elder", SyncCompletion.COMPLETE, 1L, null)) }
        val seen = mutableListOf<Cached<List<DoseEventState>>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.repository.observeEvents("elder", range).toList(seen)
        }
        runCurrent()
        f.current = false
        f.rows.value = cached(AccessState.AUTHORIZED)
        receipt.complete(Unit)
        runCurrent()
        assertEquals(listOf(AccessState.UNKNOWN), seen.map { it.access })
        job.cancel()
    }

    @Test fun `known revoked observation cannot initiate another refresh`() = runTest {
        val f = Fixture()
        f.rows.value = cached(AccessState.REVOKED)
        val seen = mutableListOf<Cached<List<DoseEventState>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.repository.observeEvents("elder", range).toList(seen) }
        runCurrent()
        assertEquals(AccessState.REVOKED, seen.single().access)
        assertTrue(f.refreshes.isEmpty())
    }

    @Test fun `cancelled range read cancels refresh without retry`() = runTest {
        val f = Fixture()
        var cancelled = false
        f.refresh = { try { awaitCancellation() } finally { cancelled = true } }
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.repository.observeEvents("elder", range).collect()
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(listOf("elder" to range), f.refreshes)
    }

    @Test fun `asynchronous first revoked cache never starts refresh`() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.firstEmissionGate = gate
        f.rows.value = cached(AccessState.REVOKED)
        val seen = mutableListOf<Cached<List<DoseEventState>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.repository.observeEvents("elder", range).toList(seen)
        }
        runCurrent()
        assertTrue(f.refreshes.isEmpty())
        assertTrue(seen.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(AccessState.REVOKED, seen.single().access)
        assertTrue(f.refreshes.isEmpty())
    }

    @Test fun `asynchronous non revoked first cache starts one refresh and keeps observing`() = runTest {
        for (access in listOf(AccessState.UNKNOWN, AccessState.AUTHORIZED)) {
            val f = Fixture()
            val gate = CompletableDeferred<Unit>()
            f.firstEmissionGate = gate
            f.rows.value = cached(access)
            val seen = mutableListOf<Cached<List<DoseEventState>>>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.repository.observeEvents("elder", range).toList(seen)
            }
            runCurrent()
            assertTrue(f.refreshes.isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("elder" to range), f.refreshes)
            assertEquals(access, seen.single().access)
            f.rows.value = cached(AccessState.REVOKED)
            runCurrent()
            assertEquals(AccessState.REVOKED, seen.last().access)
            assertEquals(1, f.refreshes.size)
            job.cancelAndJoin()
        }
    }

    @Test fun `cancellation before first cache cannot start refresh`() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.firstEmissionGate = gate
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.repository.observeEvents("elder", range).collect()
        }
        runCurrent()
        job.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("elder" to range), f.reads)
        assertTrue(f.refreshes.isEmpty())
    }

    @Test fun `context invalidated before first cache cannot publish or refresh`() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.firstEmissionGate = gate
        val seen = mutableListOf<Cached<List<DoseEventState>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.repository.observeEvents("elder", range).toList(seen)
        }
        runCurrent()
        f.current = false
        gate.complete(Unit)
        runCurrent()
        assertTrue(seen.isEmpty())
        assertTrue(f.refreshes.isEmpty())
    }

    @Test fun `later revoked cache cancels in flight refresh and cannot restart it`() = runTest {
        val f = Fixture()
        var cancelled = false
        f.refresh = { try { awaitCancellation() } finally { cancelled = true } }
        val seen = mutableListOf<Cached<List<DoseEventState>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.repository.observeEvents("elder", range).toList(seen)
        }
        runCurrent()
        f.rows.value = cached(AccessState.REVOKED)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(AccessState.REVOKED, seen.last().access)
        f.rows.value = cached(AccessState.AUTHORIZED)
        runCurrent()
        assertEquals(1, f.refreshes.size)
    }
}
