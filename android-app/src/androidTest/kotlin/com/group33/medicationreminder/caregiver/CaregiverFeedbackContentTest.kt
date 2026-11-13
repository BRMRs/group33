package com.group33.medicationreminder.caregiver

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

class CaregiverFeedbackContentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dates = LocalDateRange("2026-10-01", "2026-10-30")
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-caregiver")

    @Test fun overviewHistoryRoutingPreservesExplicitDatesWithoutImplicitRefresh() {
        val repository = Repository()
        withWindow { scenario, slot ->
            lateinit var content: CaregiverFeedbackContent
            lateinit var oldRefresh: View
            scenario.onActivity { activity ->
                content = CaregiverFeedbackContent(activity, slot, repository, repository) { elder, range ->
                    assertEquals("elder-a", elder)
                    assertEquals(dates, range)
                    content.show(CaregiverFeedbackPage.HISTORY, context("elder-a"), range)
                }
                content.show(CaregiverFeedbackPage.OVERVIEW, context("elder-a"), dates)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(slot.getChildAt(0) is CaregiverOverviewHost)
                assertTrue(slot.findViewById<TextView>(R.id.elder_name).text.contains("Private elder-a"))
                oldRefresh = slot.findViewById(R.id.refresh_button)
                oldRefresh.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(listOf("elder-a" to dates), repository.refreshes)
                slot.findViewById<View>(R.id.history_button).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val history = slot.getChildAt(0)
                assertTrue(history is CaregiverHistoryHost)
                assertEquals(listOf("elder-a" to dates), repository.historyQueries)
                content.show(CaregiverFeedbackPage.HISTORY, context("elder-a"), dates)
                assertSame(history, slot.getChildAt(0))
                assertEquals(1, repository.historyQueries.size)
                assertEquals(1, repository.refreshes.size)
                // The removed overview's listener cannot issue another refresh.
                oldRefresh.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(1, repository.refreshes.size) }
        }
    }

    @Test fun clearAndNewElderCannotRetainOldContentOrActions() {
        val repository = Repository()
        withWindow { scenario, slot ->
            lateinit var content: CaregiverFeedbackContent
            lateinit var oldHistory: View
            var historyCalls = 0
            scenario.onActivity { activity ->
                content = CaregiverFeedbackContent(activity, slot, repository, repository) { _, _ -> historyCalls++ }
                content.show(CaregiverFeedbackPage.OVERVIEW, context("elder-a"), dates)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                oldHistory = slot.findViewById(R.id.history_button)
                content.clear()
                assertEquals(View.GONE, slot.visibility)
                assertEquals(0, slot.childCount)
                oldHistory.performClick()
                repository.overview("elder-a").value = repository.cached("elder-a", "Late private A")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(0, historyCalls)
                assertEquals(0, slot.childCount)
                content.show(CaregiverFeedbackPage.OVERVIEW, context("elder-b"), dates)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(slot.findViewById<TextView>(R.id.elder_name).text.contains("Private elder-b"))
                repository.overview("elder-a").value = repository.cached("elder-a", "Late old observer A")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val label = slot.findViewById<TextView>(R.id.elder_name).text.toString()
                assertTrue(label.contains("Private elder-b"))
                assertFalse(label.contains("Late"))
                content.clear()
            }
        }
    }

    private fun context(elder: String) = CaregiverElderContext(scope, 1, "relationship-$elder", elder, "Trusted $elder")

    private fun withWindow(block: (ActivityScenario<CaregiverHistoryTestActivity>, FrameLayout) -> Unit) {
        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            lateinit var slot: FrameLayout
            scenario.onActivity { activity ->
                slot = FrameLayout(activity)
                activity.container.addView(slot, FrameLayout.LayoutParams(-1, -1))
            }
            block(scenario, slot)
        }
    }

    private class Repository : CaregiverDashboardRepository, DoseEventRepository {
        private val values = mutableMapOf<String, MutableStateFlow<Cached<ElderOverview>>>()
        val refreshes = mutableListOf<Pair<String, LocalDateRange>>()
        val historyQueries = mutableListOf<Pair<String, LocalDateRange>>()
        fun overview(elder: String) = values.getOrPut(elder) { MutableStateFlow(cached(elder, "Private $elder")) }
        fun cached(elder: String, label: String) = Cached(
            ElderOverview(User(elder, label, UserRole.ELDER), emptyList(), emptyList(), null),
            hasLocalData = true, lastSuccessfulSyncAtEpochMillis = 1L, isStale = false, access = AccessState.AUTHORIZED,
        )
        override fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>> = overview(elderId)
        override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
            refreshes += elderId to range
            return Outcome.Success(SyncSummary(
                RepositoryScope("test-cloud", "test-installation", "test-caregiver"), elderId, SyncCompletion.COMPLETE, 1L, null,
            ))
        }
        override fun observeEvents(elderId: String, range: LocalDateRange): Flow<Cached<List<DoseEventState>>> {
            historyQueries += elderId to range
            return flowOf(Cached(emptyList(), true, 1L, false, AccessState.AUTHORIZED))
        }
        override fun observeLocalAction(actionId: String): Flow<LocalActionReceipt?> = flowOf(null)
        override suspend fun recordAction(occurrence: OccurrenceKey, kind: DoseActionKind): Outcome<LocalActionReceipt> =
            error("C3 must not record Elder actions")
        override suspend fun retryAction(actionId: String): Outcome<RetryQueued> = error("Not a history navigation action")
    }
}
