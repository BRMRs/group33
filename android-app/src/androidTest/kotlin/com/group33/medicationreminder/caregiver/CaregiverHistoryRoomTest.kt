package com.group33.medicationreminder.caregiver

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.CaregiverContextResolver
import com.group33.medicationreminder.core.data.contract.CaregiverContextState
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.integration.ChainHttp
import com.group33.medicationreminder.integration.DebugChainRepository
import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.DoseEventView
import com.group33.shared.contract.ItemsResponse
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.PairingRequestView
import com.group33.shared.contract.TimeResolutionPolicy
import com.group33.shared.model.AuthResponse
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Debug Repository/Room; scripted transport is not live Server acceptance. */
class CaregiverHistoryRoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val originalRange = LocalDateRange("2026-10-21", "2026-10-22")
    private val nextRange = LocalDateRange("2026-10-21", "2026-10-21")

    @Test fun historyObservesRoomByExplicitRangeAndClearsOnRelationshipRevocation() = runBlocking<Unit> {
        val suffix = UUID.randomUUID().toString()
        val caregiver = User("history-caregiver-$suffix", "Fixture caregiver", UserRole.CAREGIVER)
        val elder = User("history-elder-$suffix", "Fixture elder", UserRole.ELDER)
        val relationship = CareRelationshipView("history-relation-$suffix", elder.id, caregiver.id,
            elder, caregiver, CareRelationshipStatus.ACTIVE, 1_797_000_000_000L)
        val transport = HistoryHttp(caregiver, elder, relationship)
        val repository = DebugChainRepository(instrumentation.targetContext.applicationContext,
            "http://c3-history-test.invalid", "c3-history-$suffix", transport)
        try {
            assertTrue(repository.login(UserRole.CAREGIVER, caregiver.displayName) is Outcome.Success)
            val session = withTimeout(5_000) { repository.observeSession().first { it.readiness == AuthReadiness.READY } }
            val trusted = withTimeout(5_000) { repository.services.trusted.first { it != null } }
            assertTrue(repository.services.pairing.refresh() is Outcome.Success)
            val relationships = withTimeout(5_000) { repository.services.pairing.observeRelationships()
                .first { it.access == AccessState.AUTHORIZED } }
            val active = CaregiverContextResolver.resolve(session, trusted, relationships, relationship.id)
                as CaregiverContextState.Active
            // The caller's sync source fills Room; the history Read button is not a network refresh.
            assertTrue(repository.refresh(elder.id, originalRange) is Outcome.Success)

            ActivityScenario.launch<CaregiverHistoryTestActivity>(
                Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
            ).use { scenario ->
                lateinit var host: CaregiverHistoryHost
                scenario.onActivity { activity ->
                    host = CaregiverHistoryHost(activity)
                    activity.container.addView(host, ViewGroup.LayoutParams(-1, -1))
                    host.bind(activity, repository, active.context, originalRange, activity.supportFragmentManager)
                }
                awaitUi { host.findViewById<ListView>(R.id.history_list).adapter.count == 3 }
                scenario.onActivity { activity ->
                    assertTrue(host.findViewById<TextView>(R.id.history_elder).text.contains(elder.displayName))
                    val list = host.findViewById<ListView>(R.id.history_list)
                    assertEquals(activity.getString(R.string.c3_outcome_missed),
                        list.adapter.getView(1, null, list).findViewById<TextView>(R.id.outcome).text.toString())
                    host.findViewById<TextView>(R.id.history_from).text = nextRange.fromDate
                    host.findViewById<TextView>(R.id.history_through).text = nextRange.throughDate
                    host.findViewById<View>(R.id.history_apply).performClick()
                }
                awaitUi { host.findViewById<TextView>(R.id.history_state).text.toString() ==
                    host.context.getString(R.string.c3_access_unknown) }
                scenario.onActivity {
                    assertEquals("An uncached date range must not show the old range's rows", 1,
                        host.findViewById<ListView>(R.id.history_list).adapter.count)
                    assertFalse(host.findViewById<TextView>(R.id.history_elder).text.contains(elder.displayName))
                }
                assertEquals("Applying dates must not issue HTTP requests", 1, transport.doseRequests.size)

                assertTrue(repository.refresh(elder.id, nextRange) is Outcome.Success)
                awaitUi { host.findViewById<ListView>(R.id.history_list).adapter.count == 2 }
                scenario.onActivity { activity ->
                    val list = host.findViewById<ListView>(R.id.history_list)
                    assertEquals(activity.getString(R.string.c3_outcome_taken),
                        list.adapter.getView(1, null, list).findViewById<TextView>(R.id.outcome).text.toString())
                    assertTrue(host.findViewById<TextView>(R.id.history_applied_range).text.contains(nextRange.throughDate))
                }
                assertEquals(listOf(transport.path(elder.id, originalRange), transport.path(elder.id, nextRange)),
                    transport.doseRequests.map { it.first })
                assertTrue(transport.doseRequests.all { it.second == "history-script-token" })

                transport.revoked = true
                assertTrue(repository.services.pairing.refresh() is Outcome.Success)
                awaitUi { host.findViewById<TextView>(R.id.history_state).text.toString() ==
                    host.context.getString(R.string.c3_revoked) }
                scenario.onActivity {
                    assertEquals(1, host.findViewById<ListView>(R.id.history_list).adapter.count)
                    assertTrue(host.findViewById<TextView>(R.id.history_elder).text.isEmpty())
                    assertFalse(host.findViewById<View>(R.id.history_apply).isEnabled)
                }
            }
        } finally { repository.close() }
    }

    private suspend fun awaitUi(condition: () -> Boolean) = withTimeout(5_000) {
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return@withTimeout
            delay(25)
        }
    }

    private class HistoryHttp(
        val caregiver: User,
        val elder: User,
        val relationship: CareRelationshipView,
    ) : ChainHttp("http://c3-history-test.invalid") {
        val doseRequests = CopyOnWriteArrayList<Pair<String, String?>>()
        @Volatile var revoked = false
        private val json = ContractProtocol.json

        override suspend fun call(method: String, path: String, token: String?, operationId: String?,
            json: String?, photo: java.io.File?, photoType: String?): Reply {
            val body = when {
                method == "POST" && path == "/api/v1/auth/anonymous" -> return reply(
                    this.json.encodeToString(AuthResponse.serializer(),
                        AuthResponse("history-script-token", "Bearer", 3_600L, caregiver)), 201)
                method == "GET" && path == "/api/v1/pairing-requests" -> this.json.encodeToString(
                    ItemsResponse.serializer(PairingRequestView.serializer()), ItemsResponse(emptyList()))
                method == "GET" && path == "/api/v1/care-relationships" -> this.json.encodeToString(
                    ItemsResponse.serializer(CareRelationshipView.serializer()), ItemsResponse(listOf(
                        relationship.copy(status = if (revoked) CareRelationshipStatus.REVOKED else CareRelationshipStatus.ACTIVE))))
                method == "GET" && path == path(elder.id, LocalDateRange("2026-10-21", "2026-10-22")) -> {
                    doseRequests += path to token
                    events(listOf(event("2026-10-21", DoseStatus.TAKEN), event("2026-10-22", DoseStatus.MISSED)))
                }
                method == "GET" && path == path(elder.id, LocalDateRange("2026-10-21", "2026-10-21")) -> {
                    doseRequests += path to token
                    events(listOf(event("2026-10-21", DoseStatus.TAKEN)))
                }
                else -> error("Unexpected scripted request: $method $path")
            }
            return reply(body)
        }

        fun path(elderId: String, range: LocalDateRange) =
            "/api/v1/elders/$elderId/dose-events?fromDate=${range.fromDate}&throughDate=${range.throughDate}"

        private fun events(items: List<DoseEventView>) =
            json.encodeToString(ItemsResponse.serializer(DoseEventView.serializer()), ItemsResponse(items))

        private fun event(date: String, status: DoseStatus): DoseEventView {
            val key = OccurrenceKey("history-test-plan", date, "08:00")
            val planned = java.time.LocalDate.parse(date).atTime(8, 0)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            val evidence = OccurrenceGenerationEvidence("history-test-$date", key, 1, planned,
                "Asia/Shanghai", planned, 28_800, TimeResolutionPolicy.P0_TIME_V1,
                "history-test-snapshot", planned)
            return DoseEventView("history-event-$date", elder.id, key, 1, "history-test-medication",
                MedicationText("Fixture medication $date", "One demo unit"), "Asia/Shanghai", planned,
                planned + 7_200_000L, evidence, status, emptyList(), planned,
                takenRecordedAtEpochMillis = planned.takeIf { status == DoseStatus.TAKEN })
        }

        private fun reply(body: String, status: Int = 200) =
            Reply(status, body.toByteArray(), "application/json", null, "history-test-request")
    }
}
