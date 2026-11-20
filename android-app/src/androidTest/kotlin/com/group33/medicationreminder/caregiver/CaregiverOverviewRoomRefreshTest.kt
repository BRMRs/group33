package com.group33.medicationreminder.caregiver

import android.content.Intent
import android.view.View
import android.view.ViewGroup
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
import com.group33.shared.contract.TimeResolutionPolicy
import com.group33.shared.model.ApiError
import com.group33.shared.model.AuthResponse
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import com.group33.shared.model.DoseStatus
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACCEPTED_TAKEN_AT_EPOCH_MILLIS = 1_797_000_060_000L

/** Exercises the debug Repository, its persistent Room cache, and the C3 host with fictional IDs. */
class CaregiverOverviewRoomRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val range = LocalDateRange("2026-10-01", "2026-10-30")

    @Test
    fun trustedRoomBackedOverviewRefreshesFromTheButtonAndRetainsCacheAfterFailure() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val caregiver = User("test-caregiver-$suffix", "Fixture Caregiver", UserRole.CAREGIVER)
        val elder = User("test-elder-$suffix", "Fixture Elder", UserRole.ELDER)
        val relationship = CareRelationshipView(
            id = "test-relationship-$suffix",
            elderId = elder.id,
            caregiverId = caregiver.id,
            elder = elder,
            caregiver = caregiver,
            status = CareRelationshipStatus.ACTIVE,
            createdAtEpochMillis = 1_797_000_000_000L,
        )
        val transport = OverviewScriptedHttp(caregiver, elder, relationship, range)
        val repository = DebugChainRepository(
            context = instrumentation.targetContext.applicationContext,
            baseUrl = "http://c3-overview-test.invalid",
            namespace = "c3-overview-$suffix",
            http = transport,
        )

        try {
            assertTrue(repository.login(UserRole.CAREGIVER, caregiver.displayName) is Outcome.Success)
            val session = withTimeout(TimeUnit.SECONDS.toMillis(5)) {
                repository.observeSession().first { it.readiness == AuthReadiness.READY }
            }
            val trustedSession = withTimeout(TimeUnit.SECONDS.toMillis(5)) {
                repository.services.trusted.first { it != null }
            }
            assertEquals(caregiver.id, session.user?.id)
            assertEquals(caregiver.id, trustedSession?.scope?.userId)

            assertTrue(repository.services.pairing.refresh() is Outcome.Success)
            val authorizedRelationships = withTimeout(TimeUnit.SECONDS.toMillis(5)) {
                repository.services.pairing.observeRelationships().first {
                    it.access == AccessState.AUTHORIZED && it.value.any { row -> row.id == relationship.id }
                }
            }
            val currentContext = CaregiverContextResolver.resolve(
                session,
                trustedSession,
                authorizedRelationships,
                relationship.id,
            ) as CaregiverContextState.Active
            assertEquals(elder.id, currentContext.context.elderId)

            // Prime the actual Room-backed observation with the first scripted server snapshot.
            assertTrue(repository.refresh(elder.id, range) is Outcome.Success)
            val firstRequest = transport.awaitDoseRequest(1)
            assertEquals("GET", firstRequest.method)
            assertEquals("/api/v1/elders/${elder.id}/dose-events?fromDate=${range.fromDate}&throughDate=${range.throughDate}", firstRequest.path)

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
                    host.bind(activity, repository, currentContext.context, range) { _, _ -> }
                }

                awaitUi(host) {
                    outcomeText(host) == activityString(host, R.string.c3_outcome_scheduled)
                }
                scenario.onActivity {
                    assertTrue(host.findViewById<TextView>(R.id.elder_name).text.toString().contains("Fixture Elder"))
                    assertEquals(1, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
                    assertEquals(it.getString(R.string.c3_outcome_scheduled), outcome(host).text.toString())
                    host.findViewById<View>(R.id.refresh_button).performClick()
                }

                transport.awaitDoseRequest(2)
                awaitUi(host) {
                    outcomeText(host) == activityString(host, R.string.c3_outcome_taken) &&
                        takenReportText(host) == expectedTakenReportText(host)
                }
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.c3_outcome_taken), outcome(host).text.toString())
                    assertEquals(View.VISIBLE, takenReport(host).visibility)
                    assertEquals(expectedTakenReportText(host), takenReport(host).text.toString())
                    assertEquals(1, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
                    assertTrue(host.findViewById<View>(R.id.refresh_button).isEnabled)
                    host.findViewById<View>(R.id.refresh_button).performClick()
                }

                transport.awaitDoseRequest(3)
                awaitUi(host) {
                    host.findViewById<View>(R.id.refresh_button).isEnabled &&
                        host.findViewById<View>(R.id.freshness).visibility == View.VISIBLE
                }
                scenario.onActivity { activity ->
                    assertEquals("A failed refresh must retain the last Room-backed result",
                        activity.getString(R.string.c3_outcome_taken), outcome(host).text.toString())
                    assertEquals("A failed refresh must retain the accepted Taken timestamp",
                        expectedTakenReportText(host), takenReport(host).text.toString())
                    assertEquals(1, host.findViewById<ViewGroup>(R.id.event_rows).childCount)
                    assertTrue(host.findViewById<TextView>(R.id.freshness).text.isNotEmpty())
                }
            }

            assertEquals(
                listOf(
                    "/api/v1/elders/${elder.id}/dose-events?fromDate=${range.fromDate}&throughDate=${range.throughDate}",
                    "/api/v1/elders/${elder.id}/dose-events?fromDate=${range.fromDate}&throughDate=${range.throughDate}",
                    "/api/v1/elders/${elder.id}/dose-events?fromDate=${range.fromDate}&throughDate=${range.throughDate}",
                ),
                transport.doseRequests.map { it.path },
            )
            assertTrue(transport.doseRequests.all { it.token == "scripted-session-token" })
        } finally {
            // Close the Room connection and repository scope; persistent data remains namespaced.
            repository.close()
        }
    }

    private fun outcome(host: CaregiverOverviewHost) =
        checkNotNull(host.findViewById<ViewGroup>(R.id.event_rows).getChildAt(0)
            ?.findViewById<TextView>(R.id.outcome))

    private fun outcomeText(host: CaregiverOverviewHost): String? =
        host.findViewById<ViewGroup>(R.id.event_rows).getChildAt(0)
            ?.findViewById<TextView>(R.id.outcome)?.text?.toString()

    private fun takenReport(host: CaregiverOverviewHost) =
        checkNotNull(host.findViewById<ViewGroup>(R.id.event_rows).getChildAt(0)
            ?.findViewById<TextView>(R.id.server_accepted_taken_report))

    private fun takenReportText(host: CaregiverOverviewHost): String? =
        host.findViewById<ViewGroup>(R.id.event_rows).getChildAt(0)
            ?.findViewById<TextView>(R.id.server_accepted_taken_report)?.text?.toString()

    private fun expectedTakenReportText(host: CaregiverOverviewHost): String = host.context.getString(
        R.string.c3_server_accepted_taken_report,
        host.context.caregiverInstant(ACCEPTED_TAKEN_AT_EPOCH_MILLIS),
    )

    private fun activityString(host: CaregiverOverviewHost, resource: Int): String =
        host.context.getString(resource)

    private suspend fun awaitUi(host: CaregiverOverviewHost, condition: () -> Boolean) {
        withTimeout(TimeUnit.SECONDS.toMillis(5)) {
            while (true) {
                var matched = false
                instrumentation.runOnMainSync { matched = condition() }
                if (matched) return@withTimeout
                delay(25)
            }
        }
    }

    private class OverviewScriptedHttp(
        private val caregiver: User,
        private val elder: User,
        private val relationship: CareRelationshipView,
        private val range: LocalDateRange,
    ) : ChainHttp("http://c3-overview-test.invalid") {
        data class Request(val method: String, val path: String, val token: String?)

        val doseRequests = CopyOnWriteArrayList<Request>()
        private val doseReadCount = AtomicInteger()
        private val protocolJson = ContractProtocol.json

        override suspend fun call(
            method: String,
            path: String,
            token: String?,
            operationId: String?,
            json: String?,
            photo: java.io.File?,
            photoType: String?,
        ): Reply {
            if (method == "POST" && path == "/api/v1/auth/anonymous") {
                val body = protocolJson.encodeToString(
                    AuthResponse.serializer(),
                    AuthResponse("scripted-session-token", "Bearer", 3_600L, caregiver),
                )
                return reply(body, status = 201)
            }
            if (method == "GET" && path == "/api/v1/pairing-requests") {
                val body = protocolJson.encodeToString(
                    ItemsResponse.serializer(com.group33.shared.contract.PairingRequestView.serializer()),
                    ItemsResponse(emptyList()),
                )
                return reply(body)
            }
            if (method == "GET" && path == "/api/v1/care-relationships") {
                val body = protocolJson.encodeToString(
                    ItemsResponse.serializer(CareRelationshipView.serializer()),
                    ItemsResponse(listOf(relationship)),
                )
                return reply(body)
            }
            if (method == "GET" && path == dosePath()) {
                doseRequests += Request(method, path, token)
                return when (doseReadCount.incrementAndGet()) {
                    1 -> reply(events(DoseStatus.SCHEDULED))
                    2 -> reply(events(DoseStatus.TAKEN))
                    else -> {
                        val requestId = "scripted-refresh-failure"
                        val body = ContractProtocol.json.encodeToString(
                            ApiError.serializer(),
                            ApiError("SERVICE_UNAVAILABLE", "Scripted refresh failure", true, requestId),
                        )
                        Reply(503, body.toByteArray(), "application/json", null, requestId)
                    }
                }
            }
            error("Unexpected scripted transport request: $method $path")
        }

        suspend fun awaitDoseRequest(number: Int): Request = withTimeout(TimeUnit.SECONDS.toMillis(5)) {
            while (doseRequests.size < number) delay(10)
            doseRequests[number - 1]
        }

        private fun dosePath() =
            "/api/v1/elders/${elder.id}/dose-events?fromDate=${range.fromDate}&throughDate=${range.throughDate}"

        private fun events(status: DoseStatus): String {
            val occurrence = OccurrenceKey("test-plan", "2026-10-21", "08:00")
            val evidence = OccurrenceGenerationEvidence(
                generationId = "test-generation",
                occurrence = occurrence,
                planRevision = 1,
                generatedAtEpochMillis = 1_797_000_000_000L,
                executionTimeZoneId = "Asia/Shanghai",
                plannedAtEpochMillis = 1_797_000_000_000L,
                resolvedOffsetSeconds = 28_800,
                resolutionPolicyVersion = TimeResolutionPolicy.P0_TIME_V1,
                sourceSnapshotVersion = "scripted-test-snapshot",
                sourceSnapshotServerAtEpochMillis = 1_797_000_000_000L,
            )
            val event = DoseEventView(
                id = "test-event",
                elderId = elder.id,
                occurrence = occurrence,
                planRevision = 1,
                medicationId = "test-medication",
                medicationSnapshot = MedicationText("Fixture medication", "One demo unit"),
                executionTimeZoneId = evidence.executionTimeZoneId,
                plannedAtEpochMillis = evidence.plannedAtEpochMillis,
                responseDeadlineEpochMillis = evidence.plannedAtEpochMillis + 7_200_000L,
                generationEvidence = evidence,
                status = status,
                lateTakenReports = emptyList(),
                serverUpdatedAtEpochMillis = evidence.generatedAtEpochMillis,
                takenRecordedAtEpochMillis = ACCEPTED_TAKEN_AT_EPOCH_MILLIS.takeIf { status == DoseStatus.TAKEN },
            )
            return protocolJson.encodeToString(
                ItemsResponse.serializer(DoseEventView.serializer()),
                ItemsResponse(listOf(event)),
            )
        }

        private fun reply(body: String, status: Int = 200) = Reply(
            status = status,
            bytes = body.toByteArray(),
            contentType = "application/json",
            retryAfter = null,
            requestId = "scripted-success",
        )
    }
}
