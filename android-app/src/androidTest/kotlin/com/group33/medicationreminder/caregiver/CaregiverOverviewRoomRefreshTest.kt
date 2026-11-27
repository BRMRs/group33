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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

            ActivityScenario.launch<CaregiverOverviewHostTestActivity>(
                Intent(instrumentation.targetContext, CaregiverOverviewHostTestActivity::class.java),
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

    @Test
    fun wrongRoleEventReadPreservesStaleOverviewWithoutASeparateRelationshipRefresh() =
        assertDeniedEventReadClearsOverview(403)

    @Test
    fun inaccessibleEventReadClearsOverviewWithoutASeparateRelationshipRefresh() =
        assertDeniedEventReadClearsOverview(404)

    @Test
    fun wrongRoleEventReadPreservesRecordsAfterDisplayOnlyRebind() =
        assertDeniedEventReadClearsOverview(403, checkTerminalAccess = true)

    @Test
    fun inaccessibleEventReadCannotBeRetriedByRebindingTheSameTrustedIdentity() =
        assertDeniedEventReadClearsOverview(404, checkTerminalAccess = true)

    private fun assertDeniedEventReadClearsOverview(status: Int, checkTerminalAccess: Boolean = false) = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val caregiver = User("denied-caregiver-$suffix", "Fixture Caregiver", UserRole.CAREGIVER)
        val elder = User("denied-elder-$suffix", "Fixture Elder", UserRole.ELDER)
        val relationship = CareRelationshipView("denied-relationship-$suffix", elder.id, caregiver.id,
            elder, caregiver, CareRelationshipStatus.ACTIVE, 1_797_000_000_000L)
        val transport = OverviewScriptedHttp(caregiver, elder, relationship, range, DoseStatus.TAKEN)
        val repository = DebugChainRepository(instrumentation.targetContext.applicationContext,
            "http://c3-overview-test.invalid", "c3-overview-denied-$suffix", transport)
        try {
            assertTrue(repository.login(UserRole.CAREGIVER, caregiver.displayName) is Outcome.Success)
            val session = withTimeout(5_000) { repository.observeSession().first { it.readiness == AuthReadiness.READY } }
            val trusted = withTimeout(5_000) { repository.services.trusted.first { it != null } }
            assertTrue(repository.services.pairing.refresh() is Outcome.Success)
            val relationships = withTimeout(5_000) { repository.services.pairing.observeRelationships()
                .first { it.access == AccessState.AUTHORIZED } }
            val active = CaregiverContextResolver.resolve(session, trusted, relationships, relationship.id)
                as CaregiverContextState.Active
            assertTrue(repository.refresh(elder.id, range) is Outcome.Success)

            ActivityScenario.launch<CaregiverOverviewHostTestActivity>(
                Intent(instrumentation.targetContext, CaregiverOverviewHostTestActivity::class.java),
            ).use { scenario ->
                lateinit var host: CaregiverOverviewHost
                lateinit var oldRow: View
                val historyOpens = AtomicInteger()
                scenario.onActivity { activity ->
                    host = CaregiverOverviewHost(activity)
                    activity.container.addView(host, ViewGroup.LayoutParams(-1, -1))
                    host.bind(activity, repository, active.context, range) { _, _ -> historyOpens.incrementAndGet() }
                }
                awaitUi(host) { takenReportText(host) == expectedTakenReportText(host) }
                val relationshipReads = transport.relationshipReads.get()
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.c3_outcome_taken), outcome(host).text.toString())
                    assertTrue(host.findViewById<TextView>(R.id.elder_name).text.contains(elder.displayName))
                    oldRow = host.findViewById<ViewGroup>(R.id.event_rows).getChildAt(0)
                    assertTrue(oldRow.isAttachedToWindow)
                    transport.deniedStatus = status
                    host.findViewById<View>(R.id.refresh_button).performClick()
                }
                transport.awaitDoseRequest(2)
                if (status == 403) {
                    val retained = withTimeout(5_000) { repository.observeEvents(elder.id, range)
                        .first { it.isStale && it.access == AccessState.AUTHORIZED } }
                    assertTrue(retained.value.isNotEmpty())
                    awaitUi(host) { host.findViewById<TextView>(R.id.refresh_message).text.toString() ==
                        activityString(host, R.string.c3_refresh_authorization_failed) }
                    scenario.onActivity {
                        assertEquals(it.getString(R.string.c3_outcome_taken), outcome(host).text.toString())
                        assertTrue(host.findViewById<View>(R.id.history_button).isEnabled)
                        assertTrue(host.findViewById<View>(R.id.refresh_button).isEnabled)
                        assertTrue(oldRow.isAttachedToWindow)
                        assertFalse(visibleText(host).contains("DENIED-RAW-SERVER-DETAIL"))
                        if (checkTerminalAccess) host.bind(it, repository,
                            active.context.copy(elderDisplayName = "Renamed fixture elder"), range) { _, _ ->
                            historyOpens.incrementAndGet()
                        }
                    }
                    if (checkTerminalAccess) awaitUi(host) { takenReportText(host) == expectedTakenReportText(host) }
                    assertEquals(2, transport.doseRequests.size)
                    assertEquals(relationshipReads, transport.relationshipReads.get())
                    assertEquals(AuthReadiness.READY, repository.observeSession().first().readiness)
                    return@use
                }
                // The event response itself invalidates the real Room projection. The relationship
                // cache remains authorized: no pairing refresh/revocation drives this regression.
                val denied = withTimeout(5_000) { repository.observeEvents(elder.id, range)
                    .first { it.access != AccessState.AUTHORIZED } }
                val unavailableMessage = if (denied.access == AccessState.REVOKED) R.string.c3_revoked
                    else R.string.c3_unavailable
                awaitUi(host) { host.findViewById<ViewGroup>(R.id.event_rows).childCount == 0 &&
                    !host.findViewById<View>(R.id.refresh_button).isEnabled &&
                    host.findViewById<TextView>(R.id.state_message).text.toString() ==
                        activityString(host, unavailableMessage) }
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(unavailableMessage),
                        host.findViewById<TextView>(R.id.state_message).text.toString())
                    assertTrue(host.findViewById<TextView>(R.id.elder_name).text.isEmpty())
                    assertTrue(host.findViewById<TextView>(R.id.last_sync).text.isEmpty())
                    assertNull(oldRow.parent)
                    assertFalse(oldRow.isAttachedToWindow)
                    assertNull(host.findViewById<TextView>(R.id.server_accepted_taken_report))
                    assertFalse(host.findViewById<View>(R.id.history_button).isEnabled)
                    assertFalse(visibleText(host).contains("DENIED-RAW-SERVER-DETAIL"))
                    // Callbacks also reject stale actions; disabled widgets alone are insufficient.
                    host.findViewById<View>(R.id.refresh_button).performClick()
                    host.findViewById<View>(R.id.history_button).performClick()
                }
                instrumentation.waitForIdleSync()
                assertEquals(2, transport.doseRequests.size)
                assertEquals(0, historyOpens.get())
                assertEquals(relationshipReads, transport.relationshipReads.get())
                assertEquals(AuthReadiness.READY, repository.observeSession().first().readiness)
                assertEquals(trusted, repository.services.trusted.value)
                val retained = repository.services.pairing.observeRelationships().first()
                assertEquals(AccessState.AUTHORIZED, retained.access)
                assertEquals(CareRelationshipStatus.ACTIVE, retained.value.single().status)
                assertTrue(transport.doseRequests.all { it.method == "GET" &&
                    it.path == "/api/v1/elders/${elder.id}/dose-events?fromDate=${range.fromDate}&throughDate=${range.throughDate}" &&
                    it.token == "scripted-session-token" })
                if (checkTerminalAccess) {
                    scenario.onActivity { activity ->
                        // A display-name update is not a new session/relationship authorization.
                        host.bind(activity, repository,
                            active.context.copy(elderDisplayName = "Renamed fixture elder"), range) { _, _ ->
                            historyOpens.incrementAndGet()
                        }
                        assertFalse("An event-read denial must survive a display-only rebind of the same trusted identity",
                            host.findViewById<View>(R.id.refresh_button).isEnabled)
                        host.findViewById<View>(R.id.refresh_button).performClick()
                    }
                    instrumentation.waitForIdleSync()
                    assertEquals("The denied identity must not send another event read", 2, transport.doseRequests.size)
                }
            }
        } finally { repository.close() }
    }

    private fun visibleText(view: View): String = when {
        view.visibility != View.VISIBLE -> ""
        view is TextView -> view.text.toString()
        view is ViewGroup -> (0 until view.childCount).joinToString("\n") { visibleText(view.getChildAt(it)) }
        else -> ""
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
        private val initialStatus: DoseStatus = DoseStatus.SCHEDULED,
    ) : ChainHttp("http://c3-overview-test.invalid") {
        data class Request(val method: String, val path: String, val token: String?)

        val doseRequests = CopyOnWriteArrayList<Request>()
        val relationshipReads = AtomicInteger()
        @Volatile var deniedStatus: Int? = null
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
                relationshipReads.incrementAndGet()
                val body = protocolJson.encodeToString(
                    ItemsResponse.serializer(CareRelationshipView.serializer()),
                    ItemsResponse(listOf(relationship)),
                )
                return reply(body)
            }
            if (method == "GET" && path == dosePath()) {
                doseRequests += Request(method, path, token)
                deniedStatus?.let { status ->
                    val requestId = "scripted-event-denied"
                    val body = protocolJson.encodeToString(ApiError.serializer(), ApiError(
                        if (status == 403) "ROLE_NOT_ALLOWED" else "RESOURCE_NOT_ACCESSIBLE",
                        "DENIED-RAW-SERVER-DETAIL", false, requestId))
                    return Reply(status, body.toByteArray(), "application/json", null, requestId)
                }
                return when (doseReadCount.incrementAndGet()) {
                    1 -> reply(events(initialStatus))
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
