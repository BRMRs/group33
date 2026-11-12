package com.group33.medicationreminder.caregiver

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.Cached
import com.group33.medicationreminder.core.data.contract.CaregiverDashboardRepository
import com.group33.medicationreminder.core.data.contract.CaregiverElderContext
import com.group33.medicationreminder.core.data.contract.ElderOverview
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.core.data.contract.SyncCompletion
import com.group33.medicationreminder.core.data.contract.SyncSummary
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.TimeResolutionPolicy
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverOverviewHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val range = LocalDateRange("2026-10-01", "2026-10-30")
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-caregiver")

    @Test
    fun refreshButtonUsesSuppliedElderAndDateRange() {
        val repository = FakeRepository("test-elder", "Private elder A")
        val trustedContext = context(generation = 1, relationshipId = "relationship-a")

        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val host = CaregiverOverviewHost(activity)
                activity.container.addView(host, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                host.bind(activity, repository, trustedContext, range) { _, _ -> }
                host.findViewById<View>(R.id.refresh_button).performClick()
            }

            val request = runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(5)) { repository.refreshRequest.await() }
            }
            assertEquals("test-elder", request.first)
            assertEquals(range, request.second)
        }
    }

    @Test
    fun contextReplacementAndClearRemovePreviousElderContent() {
        val firstRepository = FakeRepository("test-elder", "Private elder A")
        val secondRepository = FakeRepository("test-elder", "Private elder B")
        val firstContext = context(generation = 1, relationshipId = "relationship-a")
        // Same scope and Elder ID, but a new session and relationship must rebind the VM.
        val secondContext = context(generation = 2, relationshipId = "relationship-b")

        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            lateinit var host: CaregiverOverviewHost
            scenario.onActivity { activity ->
                host = CaregiverOverviewHost(activity)
                activity.container.addView(host, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                host.bind(activity, firstRepository, firstContext, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(elderName(host).contains("Private elder A"))
                assertEquals(1, host.findViewById<ViewGroup>(R.id.event_rows).childCount)

                host.bind(activity, secondRepository, secondContext, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(elderName(host).contains("Private elder B"))
                firstRepository.updates.value = firstRepository.cached("Old data from elder A")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                // An emission from the previous Repository must not replace the active Elder.
                assertTrue(elderName(host).contains("Private elder B"))
                assertFalse(elderName(host).contains("Old data from elder A"))
                assertEquals(1, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
                host.bind(activity, secondRepository, null, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(activityText(it, R.string.c3_select_elder),
                    it.findViewById<TextView>(R.id.state_message).text.toString())
                assertEquals("", elderName(host))
                assertEquals(0, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
                assertFalse(host.findViewById<View>(R.id.refresh_button).isEnabled)

                secondRepository.updates.value = secondRepository.cached("Late stale update")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals("", elderName(host))
                assertEquals(0, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
            }
        }
    }

    @Test
    fun revokedAccessRecoversForNewRelationshipWithSameSessionAndElder() {
        assertRevokedAccessRecoversForTrustedContext(
            nextContext = context(generation = 1, relationshipId = "relationship-b"),
        )
    }

    @Test
    fun detachedHostRebindsCurrentContextAndRestoresObservationAndActions() {
        val repository = FakeRepository("test-elder", "Private elder A")
        val trustedContext = context(1, "relationship-a")
        var historyRequest: Pair<String, LocalDateRange>? = null
        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            lateinit var host: CaregiverOverviewHost
            scenario.onActivity { activity ->
                host = CaregiverOverviewHost(activity)
                activity.container.addView(host)
                assertTrue(host.isAttachedToWindow)
                host.bind(activity, repository, trustedContext, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Private elder A")
                activity.container.removeView(host)
                assertFalse(host.isAttachedToWindow)
                assertEmptyContent(host)
                repository.updates.value = repository.cached("Current authorized cache")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEmptyContent(host)
                activity.container.addView(host)
                assertTrue(host.isAttachedToWindow)
                // Attaching alone must not restore the previous private content or listeners.
                assertEmptyContent(host)
                host.bind(activity, repository, trustedContext, range) { elder, dates ->
                    historyRequest = elder to dates
                }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Current authorized cache")
                repository.updates.value = repository.cached("Fresh observation after rebind")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Fresh observation after rebind")
                host.findViewById<View>(R.id.history_button).performClick()
                host.findViewById<View>(R.id.refresh_button).performClick()
            }
            assertEquals("test-elder" to range, historyRequest)
            assertEquals("test-elder" to range, runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(5)) { repository.refreshRequest.await() }
            })
        }
    }

    @Test
    fun detachedHostReboundWithoutCurrentContextCannotRedisplayOldAuthorizedValues() {
        val repository = FakeRepository("test-elder", "Private elder A")
        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            lateinit var host: CaregiverOverviewHost
            scenario.onActivity { activity ->
                host = CaregiverOverviewHost(activity)
                activity.container.addView(host)
                host.bind(activity, repository, context(1, "relationship-a"), range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Private elder A")
                repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertRevokedContent(host, activity)
                activity.container.removeView(host)
                assertFalse(host.isAttachedToWindow)
                assertEmptyContent(host)
                activity.container.addView(host)
                assertTrue(host.isAttachedToWindow)
                // The caller no longer has an Active trusted context after revocation.
                host.bind(activity, repository, null, range) { _, _ -> error("No history without context") }
                repository.updates.value = repository.cached("Late stale authorized value")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEmptyContent(host)
                assertFalse(host.findViewById<View>(R.id.refresh_button).performClick())
                assertFalse(host.findViewById<View>(R.id.history_button).performClick())
                assertFalse(repository.refreshRequest.isCompleted)
            }
        }
    }

    @Test
    fun detachedHostRebindsNewIdentityWithoutOldRepositoryRepopulation() {
        val oldRepository = FakeRepository("test-elder", "Private elder A")
        val currentRepository = FakeRepository("test-elder", "Private elder B")
        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            lateinit var host: CaregiverOverviewHost
            scenario.onActivity { activity ->
                host = CaregiverOverviewHost(activity)
                activity.container.addView(host)
                host.bind(activity, oldRepository, context(1, "relationship-a"), range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Private elder A")
                activity.container.removeView(host)
                assertFalse(host.isAttachedToWindow)
                assertEmptyContent(host)
                activity.container.addView(host)
                assertTrue(host.isAttachedToWindow)
                host.bind(activity, currentRepository, context(2, "relationship-b"), range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Private elder B")
                oldRepository.updates.value = oldRepository.cached("Late old identity")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Private elder B")
                assertFalse(elderName(host).contains("Late old identity"))
                host.findViewById<View>(R.id.refresh_button).performClick()
            }
            assertEquals("test-elder" to range, runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(5)) { currentRepository.refreshRequest.await() }
            })
            assertFalse(oldRepository.refreshRequest.isCompleted)
        }
    }

    private fun assertEmptyContent(host: CaregiverOverviewHost) {
        assertEquals("", elderName(host))
        assertEquals(0, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
        assertFalse(host.findViewById<View>(R.id.refresh_button).isEnabled)
        assertFalse(host.findViewById<View>(R.id.history_button).isEnabled)
    }

    @Test
    fun revokedAccessRecoversForNewSessionGenerationWithSameRelationshipAndElder() {
        assertRevokedAccessRecoversForTrustedContext(
            nextContext = context(generation = 2, relationshipId = "relationship-a"),
        )
    }

    @Test
    fun revokedAccessCannotReturnFromDisplayRepositoryRangeOrClearRebindChanges() {
        val repository = FakeRepository("test-elder", "Private elder A")
        val replacementRepository = FakeRepository("test-elder", "Private elder B")
        val trustedContext = context(generation = 1, relationshipId = "relationship-a")
        val changedRange = LocalDateRange("2026-10-02", "2026-10-29")

        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            lateinit var host: CaregiverOverviewHost
            scenario.onActivity { activity ->
                host = CaregiverOverviewHost(activity)
                activity.container.addView(host, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                host.bind(activity, repository, trustedContext, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Private elder A")
                repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertRevokedContent(host, activity)
                // An authorized late value from the revoked subscription cannot restore its rows.
                repository.updates.value = repository.cached("Late old authorized value")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertRevokedContent(host, activity)

                // Display text is not trusted relationship or session identity.
                host.bind(activity, repository, trustedContext.copy(elderDisplayName = "Renamed elder"), range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertRevokedContent(host, activity)
                // Replacing the Repository alone does not change trusted identity.
                host.bind(activity, replacementRepository, trustedContext, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertRevokedContent(host, activity)
                // Changing the requested date range alone does not restore revoked access.
                host.bind(activity, replacementRepository, trustedContext, changedRange) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertRevokedContent(host, activity)
                host.bind(activity, replacementRepository, null, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(activityText(activity, R.string.c3_select_elder),
                    activity.findViewById<TextView>(R.id.state_message).text.toString())
                assertEquals(0, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
                assertFalse(host.findViewById<View>(R.id.refresh_button).isEnabled)
                // Clearing and rebinding the same trusted scope/Elder also cannot erase revocation.
                host.bind(activity, replacementRepository, trustedContext, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> assertRevokedContent(host, activity) }
        }
    }

    private fun assertRevokedAccessRecoversForTrustedContext(nextContext: CaregiverElderContext) {
        val oldRepository = FakeRepository("test-elder", "Private elder A")
        val currentRepository = FakeRepository("test-elder", "Private elder B")
        val firstContext = context(generation = 1, relationshipId = "relationship-a")

        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            lateinit var host: CaregiverOverviewHost
            scenario.onActivity { activity ->
                host = CaregiverOverviewHost(activity)
                activity.container.addView(host, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                host.bind(activity, oldRepository, firstContext, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Private elder A")
                oldRepository.updates.value = oldRepository.updates.value.copy(access = AccessState.REVOKED)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertRevokedContent(host, activity)
                host.bind(activity, currentRepository, nextContext, range) { _, _ -> }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> assertAuthorizedContent(host, activity, "Private elder B") }

            // Updating the old flow after the new trusted identity is bound cannot replace its UI.
            scenario.onActivity { oldRepository.updates.value = oldRepository.cached("Late old elder data") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertAuthorizedContent(host, activity, "Private elder B")
                host.findViewById<View>(R.id.refresh_button).performClick()
            }

            val request = runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(5)) { currentRepository.refreshRequest.await() }
            }
            assertEquals("test-elder", request.first)
            assertEquals(range, request.second)
        }
    }

    private fun assertAuthorizedContent(
        host: CaregiverOverviewHost,
        activity: CaregiverHistoryTestActivity,
        elderLabel: String,
    ) {
        assertEquals(activity.getString(R.string.c3_results),
            activity.findViewById<TextView>(R.id.state_message).text.toString())
        assertTrue(elderName(host).contains(elderLabel))
        assertEquals(1, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
        assertTrue(host.findViewById<View>(R.id.refresh_button).isEnabled)
    }

    private fun assertRevokedContent(host: CaregiverOverviewHost, activity: CaregiverHistoryTestActivity) {
        assertEquals(activityText(activity, R.string.c3_revoked),
            activity.findViewById<TextView>(R.id.state_message).text.toString())
        assertEquals("", elderName(host))
        assertEquals(0, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
        assertFalse(host.findViewById<View>(R.id.refresh_button).isEnabled)
    }

    private fun context(generation: Long, relationshipId: String) = CaregiverElderContext(
        scope = scope,
        sessionGeneration = generation,
        relationshipId = relationshipId,
        elderId = "test-elder",
        elderDisplayName = "Trusted elder label",
    )

    private fun elderName(host: CaregiverOverviewHost) =
        host.findViewById<TextView>(R.id.elder_name).text.toString()

    private fun activityText(activity: CaregiverHistoryTestActivity, resource: Int) =
        activity.getString(resource)

    private class FakeRepository(
        private val elderId: String,
        private val elderName: String,
    ) : CaregiverDashboardRepository {
        val updates = MutableStateFlow(cached(elderName))
        val refreshRequest = CompletableDeferred<Pair<String, LocalDateRange>>()

        override fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>> = updates

        override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
            refreshRequest.complete(elderId to range)
            return Outcome.Success(
                SyncSummary(
                    RepositoryScope("test-cloud", "test-installation", "test-caregiver"),
                    elderId,
                    SyncCompletion.COMPLETE,
                    10L,
                    null,
                ),
            )
        }

        fun cached(name: String = elderName): Cached<ElderOverview> {
            val occurrence = OccurrenceKey("test-plan", "2026-10-21", "08:00")
            val plannedAt = 1_792_612_800_000L
            val evidence = OccurrenceGenerationEvidence(
                generationId = "test-generation",
                occurrence = occurrence,
                planRevision = 1,
                generatedAtEpochMillis = 1_792_584_000_000L,
                executionTimeZoneId = "Asia/Shanghai",
                plannedAtEpochMillis = plannedAt,
                resolvedOffsetSeconds = 28_800,
                resolutionPolicyVersion = TimeResolutionPolicy.P0_TIME_V1,
                sourceSnapshotVersion = "test-snapshot",
                sourceSnapshotServerAtEpochMillis = 1_792_584_000_000L,
            )
            val event = com.group33.medicationreminder.core.data.contract.DoseEventState(
                occurrence = occurrence,
                serverEventId = "test-event",
                generationEvidence = evidence,
                medicationId = "test-medication",
                medicationSnapshot = MedicationText("Test medication", "One tablet"),
                responseDeadlineEpochMillis = plannedAt + 7_200_000L,
                effectiveStatus = DoseStatus.TAKEN,
                lastAcceptedStatus = DoseStatus.TAKEN,
                localReports = emptyList(),
            )
            return Cached(
                ElderOverview(
                    elder = User(elderId, name, UserRole.ELDER),
                    medications = emptyList(),
                    events = listOf(event),
                    capability = null,
                ),
                hasLocalData = true,
                lastSuccessfulSyncAtEpochMillis = 1L,
                isStale = false,
                access = AccessState.AUTHORIZED,
            )
        }
    }
}
