package com.group33.medicationreminder.integration

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.*
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.lang.reflect.Proxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real shared renderer/layout and C1 context resolver; strict test-only service fixtures. */
class CaregiverFeedbackEntryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dates = LocalDateRange("2026-10-01", "2026-10-30")

    @Test fun explicitDatesRouteFullPagesAndPreservePairingAndPlanSections() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act {
            assertEquals(1, h.providerCalls)
            assertNotNull(findText(h.root, it.getString(R.string.chain_no_plans)))
            h.root.findViewById<View>(R.id.refresh_button).performClick()
        }
        h.await { h.fixture.refreshes.size == 1 }
        h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
        h.await { find(h.root, CaregiverHistoryHost::class.java) != null }
        h.act {
            assertEquals(CaregiverDestination.RECORDS, h.lastDestination)
            assertEquals(listOf("elder" to dates), h.fixture.historyQueries)
            assertEquals(1, h.providerCalls)
            h.content.show(CaregiverDestination.SETTINGS)
        }
        h.await { find(h.root, CaregiverHistoryHost::class.java) == null }
        h.act { activity ->
            assertTrue(allViews(h.root).filterIsInstance<EditText>().any {
                it.hint?.toString() == activity.getString(R.string.chain_enter_code) && it.isShown
            })
            assertEquals(1, h.fixture.refreshes.size)
        }
    }

    @Test fun viewportFollowsActualAvailableHeightAndContextLossClearsContent() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java)?.parent is FrameLayout }
        var oldHeight = 0
        h.await {
            val slot = find(h.root, CaregiverOverviewHost::class.java)?.parent as? FrameLayout
            val viewport = h.root.findViewById<View>(R.id.caregiver_scroll)
            slot != null && slot.height > 0 && slot.height == viewport.height - viewport.paddingTop - viewport.paddingBottom
        }
        h.act { activity ->
            oldHeight = (find(h.root, CaregiverOverviewHost::class.java)!!.parent as FrameLayout).height
            val density = activity.resources.displayMetrics.density
            h.root.layoutParams = FrameLayout.LayoutParams((320 * density).toInt(), (480 * density).toInt())
        }
        h.await {
            val slot = find(h.root, CaregiverOverviewHost::class.java)?.parent as? FrameLayout
            val viewport = h.root.findViewById<View>(R.id.caregiver_scroll)
            slot != null && slot.height > 0 && slot.height != oldHeight &&
                slot.height == viewport.height - viewport.paddingTop - viewport.paddingBottom
        }
        h.act { h.fixture.trusted.value = null }
        h.await { find(h.root, CaregiverOverviewHost::class.java) == null }
        h.act {
            h.content.close()
            h.fixture.trusted.value = TrustedSessionContext(h.fixture.scope, 2)
        }
        instrumentation.waitForIdleSync()
        h.act { assertNull(find(h.root, CaregiverOverviewHost::class.java)) }
    }

    @Test fun callerWithoutDateProviderKeepsExistingPanelInsteadOfInventingADefault() = withEntry(false) { h ->
        h.await { findText(h.root, instrumentation.targetContext.getString(R.string.dose_records_overview)) != null }
        h.act {
            assertNull(find(h.root, CaregiverOverviewHost::class.java))
            assertNull(find(h.root, CaregiverHistoryHost::class.java))
            assertEquals(0, h.providerCalls)
        }
    }

    private fun withEntry(explicitDates: Boolean = true, block: (Harness) -> Unit) {
        ActivityScenario.launch<CaregiverHistoryTestActivity>(
            Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
        ).use { scenario ->
            val h = Harness(scenario)
            h.act { activity ->
                h.root = activity.layoutInflater.inflate(R.layout.activity_caregiver_home, activity.container, false) as ViewGroup
                activity.container.addView(h.root, FrameLayout.LayoutParams(-1, -1))
                val model = ChainViewModel(h.fixture.services)
                h.store.put("entry", model)
                h.content = OnlineFeatureContent(activity, h.root.findViewById(R.id.feature_content),
                    UserRole.CAREGIVER, model, h.fixture.services,
                    navigate = { destination -> h.lastDestination = destination; h.content.show(destination) },
                    feedbackRange = if (explicitDates) ({ _ -> h.providerCalls++; dates }) else null)
            }
            try { block(h) } finally { h.act { h.content.close(); h.store.clear() } }
        }
    }

    private class Harness(val scenario: ActivityScenario<CaregiverHistoryTestActivity>) {
        val fixture = Services()
        val store = ViewModelStore()
        lateinit var root: ViewGroup
        lateinit var content: OnlineFeatureContent
        var providerCalls = 0
        var lastDestination: CaregiverDestination? = null
        fun act(action: (CaregiverHistoryTestActivity) -> Unit) = scenario.onActivity { action(it) }
        fun await(condition: () -> Boolean) = runBlocking {
            withTimeout(5_000) {
                while (true) {
                    var result = false
                    act { result = condition() }
                    if (result) break
                    delay(25)
                }
            }
        }
    }

    private class Services {
        val scope = RepositoryScope("test", "installation", "caregiver")
        val trusted = MutableStateFlow<TrustedSessionContext?>(TrustedSessionContext(scope, 1))
        val caregiver = UserView("caregiver", "Fixture caregiver", UserRole.CAREGIVER)
        val elder = UserView("elder", "Fixture elder", UserRole.ELDER)
        val refreshes = mutableListOf<Pair<String, LocalDateRange>>()
        val historyQueries = mutableListOf<Pair<String, LocalDateRange>>()
        private fun <T> cached(value: T) = Cached(value, true, 1L, false, AccessState.AUTHORIZED)
        private val summary = SyncSummary(scope, "elder", SyncCompletion.COMPLETE, 1L, null)
        private val dashboard = object : CaregiverDashboardRepository {
            override fun observeOverview(elderId: String, range: LocalDateRange) = flowOf(cached(
                ElderOverview(User("elder", "Fixture elder", UserRole.ELDER), emptyList(), emptyList(), null)))
            override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
                refreshes += elderId to range
                return Outcome.Success(summary)
            }
        }
        private val events = strict<DoseEventRepository> { method, args -> when (method) {
            "observeEvents" -> {
                historyQueries += (args[0] as String) to (args[1] as LocalDateRange)
                flowOf(cached(emptyList<DoseEventState>()))
            }
            else -> error("Unexpected history operation: $method")
        } }
        val services = ChainServices(
            auth = strict { method, _ -> when (method) {
                "observeSession" -> flowOf(SessionView(AuthReadiness.READY, caregiver))
                else -> error("Unexpected auth operation: $method")
            } },
            pairing = strict { method, _ -> when (method) {
                "observeRequests" -> flowOf(cached(emptyList<PairingRequestView>()))
                "observeRelationships" -> flowOf(cached(listOf(CareRelationshipView(
                    "relationship", elder.id, caregiver.id, elder, caregiver, CareRelationshipStatus.ACTIVE, 1L))))
                "refresh" -> Outcome.Success(Unit)
                else -> error("Unexpected pairing write: $method")
            } },
            photos = strict { method, _ -> when (method) {
                "observePhotos" -> flowOf(cached(emptyList<MedicationPhotoView>()))
                "refresh" -> Outcome.Success(summary)
                else -> error("Unexpected photo operation: $method")
            } },
            plans = strict { method, _ -> when (method) {
                "observePlans" -> flowOf(cached(emptyList<ReminderPlanView>()))
                "observeMedications" -> flowOf(cached(emptyList<MedicationView>()))
                else -> error("Unexpected plan operation: $method")
            } },
            commands = strict { method, _ -> when (method) {
                "observeCommands" -> flowOf(emptyList<OnlineCommandRecord>())
                else -> error("Unexpected command write: $method")
            } },
            scope = MutableStateFlow(scope), trusted = trusted,
            resumePairingCode = { error("No recovery command in this test") },
            doseEvents = events, caregiverDashboard = dashboard,
            signOut = { error("No sign-out command in this test") },
        )
    }

    companion object {
        /** No silent success for unrelated methods; suspend calls return immediate fixture values. */
        private inline fun <reified T> strict(crossinline answer: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "Strict ${T::class.java.simpleName} fixture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> answer(method.name, args ?: emptyArray())
                }
            } as T

        private fun allViews(root: View): List<View> = listOf(root) +
            if (root is ViewGroup) (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) } else emptyList()
        private fun <T : View> find(root: View, type: Class<T>): T? = allViews(root).firstOrNull(type::isInstance)?.let(type::cast)
        private fun findText(root: View, text: String) = allViews(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
    }
}
