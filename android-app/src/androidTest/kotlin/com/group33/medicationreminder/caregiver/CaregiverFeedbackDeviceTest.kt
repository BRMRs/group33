package com.group33.medicationreminder.caregiver

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Real Android main dispatcher; fictional Repository callbacks, not Room/HTTP or screen evidence. */
@RunWith(AndroidJUnit4::class)
class CaregiverFeedbackDeviceTest {
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-user")
    private val dates = LocalDateRange("2026-10-01", "2026-10-30")

    @Test(timeout = 15_000)
    fun currentRevocationClearsCacheAndBlocksLateResults() = verifyRevocation("A")

    @Test(timeout = 15_000)
    fun currentRevocationWithForeignPayloadStillRevokesSelectedElder() = verifyRevocation("B")

    @Test(timeout = 15_000)
    fun obsoleteCallbacksCannotRevokeOrRestoreReplacementContext() = onMain {
        val replacements = listOf("B" to scope, "A" to scope.copy(userId = "other-test-user"), "A" to scope)
        for ((elder, nextScope) in replacements) {
            val old = DeviceRepository("A")
            val next = DeviceRepository(elder, nextScope)
            val store = ViewModelStore()
            try {
                val model = CaregiverFeedbackViewModel(old, "A", dates, scope)
                store.put("feedback", model)
                assertSame(old.initial, model.state.value.overview)
                model.refresh()
                assertTrue(model.state.value.isRefreshing)
                model.selectContext(next, nextScope, elder, dates)
                assertSame(next.initial, model.state.value.overview)
                model.refresh()
                assertTrue(model.state.value.isRefreshing)

                old.emit(old.initial.copy(access = AccessState.REVOKED))
                old.completeRefresh()
                old.emit(old.initial)
                assertEquals(elder, model.state.value.elderId)
                assertEquals(nextScope, model.state.value.scope)
                assertEquals(AccessState.AUTHORIZED, model.state.value.access)
                assertSame(next.initial, model.state.value.overview)
                assertNull(model.state.value.refreshResult)
                assertTrue(model.state.value.isRefreshing)

                next.completeRefresh()
                assertTrue(model.state.value.isRefreshComplete)
            } finally {
                store.clear()
                old.finish()
                next.finish()
            }
        }
    }

    private fun verifyRevocation(payloadElder: String) = onMain {
        val repository = DeviceRepository("A")
        val store = ViewModelStore()
        try {
            val model = CaregiverFeedbackViewModel(repository, "A", dates, scope)
            store.put("feedback", model)
            assertSame(repository.initial, model.state.value.overview)
            model.refresh()
            assertTrue(model.state.value.isRefreshing)
            repository.emit(cached(payloadElder).copy(access = AccessState.REVOKED))
            assertRevoked(model)
            model.refresh()
            assertEquals(1, repository.refreshCalls)

            repository.completeRefresh()
            repository.emit(repository.initial)
            assertRevoked(model)
            assertNull(model.state.value.refreshResult)
            assertFalse(model.state.value.isRefreshComplete)
        } finally {
            store.clear()
            repository.finish()
        }
    }

    private fun assertRevoked(model: CaregiverFeedbackViewModel) {
        assertEquals("A", model.state.value.elderId)
        assertEquals(scope, model.state.value.scope)
        assertEquals(AccessState.REVOKED, model.state.value.access)
        assertNull(model.state.value.overview)
        assertFalse(model.state.value.isRefreshing)
    }

    private fun onMain(block: suspend () -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            block()
        }
    }

    private fun cached(elder: String) = Cached(
        ElderOverview(User(elder, "Fictional Elder", UserRole.ELDER), emptyList(), emptyList(), null),
        true, 1_800_000_000_000L, false, AccessState.AUTHORIZED,
    )

    /** Intentionally delivers callbacks after cancellation to exercise the ViewModel generation guard. */
    @OptIn(InternalCoroutinesApi::class)
    private inner class DeviceRepository(elder: String, context: RepositoryScope = scope) : CaregiverDashboardRepository {
        val initial = cached(elder)
        private val success = Outcome.Success(SyncSummary(context, elder, SyncCompletion.COMPLETE, 1_800_000_001_000L, null))
        private lateinit var collector: FlowCollector<Cached<ElderOverview>>
        private var observation: Continuation<Unit>? = null
        private var reply: Continuation<Outcome<SyncSummary>>? = null
        var refreshCalls = 0
            private set

        override fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>> {
            assertEquals(initial.value.elder.id, elderId)
            assertEquals(dates, range)
            return object : Flow<Cached<ElderOverview>> {
                override suspend fun collect(collector: FlowCollector<Cached<ElderOverview>>) {
                    this@DeviceRepository.collector = collector
                    collector.emit(initial)
                    suspendCoroutine<Unit> { observation = it }
                }
            }
        }

        override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
            assertEquals(initial.value.elder.id, elderId)
            assertEquals(dates, range)
            refreshCalls++
            check(reply == null)
            return suspendCoroutine { reply = it }
        }

        suspend fun emit(value: Cached<ElderOverview>) { collector.emit(value) }

        fun completeRefresh() {
            val pending = checkNotNull(reply)
            reply = null
            pending.resume(success)
        }

        fun finish() {
            if (reply != null) completeRefresh()
            val pending = observation
            observation = null
            pending?.resume(Unit)
        }
    }
}
