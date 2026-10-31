package com.group33.medicationreminder.caregiver

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.contract.ElderSnapshot
import com.group33.shared.model.ApiError
import com.group33.shared.model.DoseStatus
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production display policy using the existing contract fixture, not a second mapper. */
class CaregiverOverviewPresentationTest {
    private val range = LocalDateRange("2026-10-01", "2026-10-30")
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-caregiver")
    private val snapshot: ElderSnapshot by lazy {
        val json = ContractProtocol.json
        val examples = json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json")).bufferedReader().use { it.readText() },
        ).jsonObject
        json.decodeFromJsonElement(examples.getValue("snapshot"))
    }

    @Test fun `selection loading missing local data and authorized empty have distinct messages`() {
        val empty = state().copy(overview = cached().copy(value = overview().copy(events = emptyList())))
        val noData = empty.copy(overview = checkNotNull(empty.overview).copy(hasLocalData = false))
        assertEquals(OverviewMessage.SELECT_ELDER, display(state().copy(elderId = null)).message)
        assertEquals(OverviewMessage.LOADING, display(state().copy(overview = null, isLoading = true)).message)
        assertEquals(OverviewMessage.NO_LOCAL_DATA, display(noData).message)
        assertNull(display(noData).content)
        assertEquals(OverviewMessage.EMPTY, display(empty).message)
        assertTrue(checkNotNull(display(empty).content).events.isEmpty())
        assertTrue(display(empty).canOpenHistory)
    }

    @Test fun `results use frozen event text and occurrence rather than current medication or plan count`() {
        val event = overview().events.first()
        val changed = overview().copy(medications = emptyList(), events = listOf(event))
        val screen = display(state().copy(overview = cached().copy(value = changed)))
        val row = checkNotNull(screen.content).events.single()
        assertEquals(OverviewMessage.RESULTS, screen.message)
        assertEquals(event.medicationSnapshot, row.medication)
        assertEquals(event.occurrence, row.occurrence)
        assertEquals(event.generationEvidence.executionTimeZoneId, row.executionTimeZoneId)
        assertEquals(snapshot.elder.displayName, screen.content?.elderName)
    }

    @Test fun `pending effective taken does not claim server accepted taking medication`() {
        val original = overview().events.first()
        val report = LocalActionReceipt("test-action", original.occurrence, DoseActionKind.TAKEN,
            LocalActionClassification.TIMELY_TAKEN, 123L, SyncState.PENDING_SYNC)
        val event = original.copy(effectiveStatus = DoseStatus.TAKEN, lastAcceptedStatus = DoseStatus.DUE, localReports = listOf(report))
        val row = checkNotNull(display(withEvents(event)).content).events.single()
        assertEquals(DoseStatus.DUE, row.acceptedStatus)
        assertEquals(setOf(SyncState.PENDING_SYNC), row.localSyncStates)
    }

    @Test fun `unaccepted local event remains unknown even if its effective status is taken`() {
        val event = overview().events.first().copy(effectiveStatus = DoseStatus.TAKEN, lastAcceptedStatus = null)
        assertNull(checkNotNull(display(withEvents(event)).content).events.single().acceptedStatus)
    }

    @Test fun `late local report preserves accepted missed and separate sync states`() {
        val original = overview().events.first()
        val reports = SyncState.entries.mapIndexed { index, sync ->
            LocalActionReceipt("test-late-$index", original.occurrence, DoseActionKind.TAKEN,
                LocalActionClassification.LATE_TAKEN, 123L + index, sync)
        }
        val event = original.copy(effectiveStatus = DoseStatus.MISSED, lastAcceptedStatus = DoseStatus.MISSED, localReports = reports)
        val row = checkNotNull(display(withEvents(event)).content).events.single()
        assertEquals(DoseStatus.MISSED, row.acceptedStatus)
        assertEquals(SyncState.entries.toSet(), row.localSyncStates)
        // Full remote late-report details still require the task 08 adapter/contract work.
    }

    @Test fun `offline retains results capability and timestamp but marks them stale`() {
        val original = state()
        val failed = display(original.copy(refreshResult = Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE))))
        assertEquals(display(original).content?.events, failed.content?.events)
        assertEquals(original.overview?.value?.capability, failed.content?.capability)
        assertEquals(original.overview?.lastSuccessfulSyncAtEpochMillis, failed.content?.lastSuccessfulSyncAtEpochMillis)
        assertEquals(OverviewRefreshMessage.OFFLINE, failed.refreshMessage)
        assertTrue(checkNotNull(failed.content).isStale)
        assertTrue(failed.canRefresh)
    }

    @Test fun `observation failure and partial refresh retain records without success`() {
        val partial = Outcome.Success(SyncSummary(scope, snapshot.elder.id, SyncCompletion.PARTIAL, 999L, null))
        for (failed in listOf(state().copy(observationFailed = true), state().copy(refreshResult = partial))) {
            val screen = display(failed)
            assertEquals(OverviewMessage.RESULTS, screen.message)
            assertEquals(OverviewRefreshMessage.FAILED, screen.refreshMessage)
            assertTrue(checkNotNull(screen.content).isStale)
            assertEquals(cached().lastSuccessfulSyncAtEpochMillis, screen.content?.lastSuccessfulSyncAtEpochMillis)
        }
    }

    @Test fun `unknown revoked foreign elder and missing session cannot expose cached content`() {
        for (hidden in listOf(
            state().copy(access = AccessState.UNKNOWN),
            state().copy(access = AccessState.REVOKED),
            state().copy(elderId = "other-test-elder"),
            state().copy(scope = null),
            state().copy(overview = cached().copy(access = AccessState.UNKNOWN)),
        )) {
            assertNull(display(hidden).content)
            assertFalse(display(hidden).canOpenHistory)
        }
        assertEquals(OverviewMessage.ACCESS_UNKNOWN, display(state().copy(access = AccessState.UNKNOWN)).message)
        assertEquals(OverviewMessage.REVOKED, display(state().copy(access = AccessState.REVOKED)).message)
        assertFalse(display(state().copy(access = AccessState.REVOKED)).canRefresh)
    }

    @Test fun `access errors hide data without inventing permanent revocation or revealing existence`() {
        for (code in listOf("RESOURCE_NOT_ACCESSIBLE", "UNAUTHENTICATED", "ROLE_NOT_ALLOWED")) {
            val input = state().copy(refreshResult = Outcome.Failure(RepositoryError.Remote(ApiError(code, "not for display", false, "test"))))
            val screen = display(input)
            assertEquals(OverviewMessage.UNAVAILABLE, screen.message)
            assertNull(screen.content)
            assertFalse(screen.canOpenHistory)
            assertTrue(screen.canRefresh)
            assertEquals(AccessState.AUTHORIZED, input.access)
        }
    }

    @Test fun `session not ready hides content while generic server failure retains stale data`() {
        val noSession = display(state().copy(refreshResult = Outcome.Failure(RepositoryError.Local(LocalFailureCode.SESSION_NOT_READY))))
        assertEquals(OverviewMessage.UNAVAILABLE, noSession.message)
        assertNull(noSession.content)
        val failed = display(state().copy(refreshResult = Outcome.Failure(RepositoryError.Remote(ApiError("FUTURE_ERROR", "raw", true, "test")))))
        assertNotNull(failed.content)
        assertTrue(checkNotNull(failed.content).isStale)
        assertEquals(OverviewRefreshMessage.FAILED, failed.refreshMessage)
    }

    @Test fun `refreshing disables duplicate button and does not replace cached timestamp`() {
        val screen = display(state().copy(isRefreshing = true))
        assertFalse(screen.canRefresh)
        assertEquals(OverviewRefreshMessage.REFRESHING, screen.refreshMessage)
        assertEquals(cached().lastSuccessfulSyncAtEpochMillis, screen.content?.lastSuccessfulSyncAtEpochMillis)
    }

    @Test fun `complete receipt and missing capability never invent observed facts`() {
        val cached = cached().copy(value = overview().copy(capability = null), lastSuccessfulSyncAtEpochMillis = null)
        val success = state().copy(overview = cached, refreshResult = Outcome.Success(SyncSummary(scope, snapshot.elder.id, SyncCompletion.COMPLETE, 999L, null)))
        val screen = display(success)
        assertEquals(OverviewRefreshMessage.COMPLETE, screen.refreshMessage)
        assertNull(screen.content?.lastSuccessfulSyncAtEpochMillis)
        assertNull(screen.content?.capability)
    }

    @Test fun `local refresh errors explain recovery without dropping selected cached records`() {
        for ((code, expected) in listOf(
            LocalFailureCode.STORAGE_UNAVAILABLE to "STORAGE_UNAVAILABLE",
            LocalFailureCode.INVALID_INPUT to "INVALID_INPUT",
        )) {
            val screen = display(state().copy(refreshResult = Outcome.Failure(RepositoryError.Local(code))))
            assertEquals(expected, screen.refreshMessage.name)
            assertEquals(display(state()).content?.events, screen.content?.events)
            assertEquals(123L, screen.content?.lastSuccessfulSyncAtEpochMillis)
            assertTrue(checkNotNull(screen.content).isStale)
            assertTrue(screen.canRefresh)
        }
    }

    @Test fun `snapshot service errors in failure and partial receipts keep their typed explanation`() {
        for ((code, expected) in listOf(
            "SNAPSHOT_RANGE_TOO_LARGE" to "RANGE_TOO_LARGE",
            "VALIDATION_FAILED" to "INVALID_INPUT",
            "SERVICE_UNAVAILABLE" to "SERVICE_UNAVAILABLE",
            "RETRY_LATER" to "RETRY_LATER",
        )) {
            val error = RepositoryError.Remote(ApiError(code, "private server message", true, "test"))
            for (result in listOf(
                Outcome.Failure(error),
                Outcome.Success(SyncSummary(scope, snapshot.elder.id, SyncCompletion.PARTIAL, 999L, error)),
            )) {
                val input = state().copy(refreshResult = result)
                val screen = display(input)
                assertEquals(expected, screen.refreshMessage.name)
                assertEquals(range, input.range)
                assertEquals(snapshot.elder.id, input.elderId)
                assertEquals(display(state()).content?.events, screen.content?.events)
                assertEquals(123L, screen.content?.lastSuccessfulSyncAtEpochMillis)
                assertTrue(checkNotNull(screen.content).isStale)
                assertTrue(screen.canRefresh)
            }
        }
    }

    private fun display(state: CaregiverFeedbackState) = CaregiverOverviewPresentation.from(state)
    private fun state() = CaregiverFeedbackState(snapshot.elder.id, range, scope, AccessState.AUTHORIZED, cached(), isLoading = false)
    private fun cached() = Cached(overview(), true, 123L, false, AccessState.AUTHORIZED)
    private fun overview() = ElderOverview(snapshot.elder, snapshot.medications, snapshot.doseEvents.map {
        DoseEventState(it.occurrence, it.id, it.generationEvidence, it.medicationId, it.medicationSnapshot,
            it.responseDeadlineEpochMillis, it.status, it.status, emptyList())
    }, snapshot.capability)
    private fun withEvents(vararg events: DoseEventState) = state().copy(overview = cached().copy(value = overview().copy(events = events.toList())))
}
