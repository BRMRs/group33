package com.group33.medicationreminder.caregiver

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.DoseStatus
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Tests actual C3 mapping, beyond the pre-existing DTO-only late-report representation cases. */
class CaregiverEvidencePresentationTest {
    private val examples by lazy {
        ContractProtocol.json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json"))
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private val snapshot: ElderSnapshot by lazy { ContractProtocol.json.decodeFromJsonElement(examples.getValue("snapshot")) }
    private val late: DoseActionReceipt by lazy {
        ContractProtocol.json.decodeFromJsonElement(examples.getValue("lateTaken").jsonObject.getValue("response"))
    }

    @Test fun `synced late fixture maps its report time without replacing accepted Missed`() {
        val state = event(late.event)
        val report = late.event.lateTakenReports.single()
        val row = state.copy(localReports = listOf(LocalActionReceipt(late.actionId, state.occurrence,
            DoseActionKind.TAKEN, LocalActionClassification.LATE_TAKEN, report.recordedAtEpochMillis, SyncState.SYNCED)))
            .toCaregiverEventRow()
        assertEquals(DoseStatus.MISSED, row.acceptedStatus)
        assertEquals(listOf(CaregiverSyncedReport(LocalActionClassification.LATE_TAKEN, report.recordedAtEpochMillis)), row.syncedReports)
        assertNotEquals(report.receivedAtEpochMillis, row.syncedReports.single().recordedAtEpochMillis)
    }

    @Test fun `server accepted late reports keep their own times and never become local sync evidence`() {
        val state = event(snapshot.doseEvents.first())
        val remote = listOf(
            ServerAcceptedLateTakenReport("remote-1", 100L, 200L),
            ServerAcceptedLateTakenReport("remote-2", 300L, 400L),
        )

        val lateState = state.copy(lastAcceptedStatus = DoseStatus.MISSED,
            serverAcceptedLateTakenReports = remote)
        val row = lateState.toCaregiverEventRow()

        assertEquals(lateState.lastAcceptedStatus, row.acceptedStatus)
        assertEquals(DoseStatus.MISSED, row.acceptedStatus)
        assertEquals(emptyList<CaregiverSyncedReport>(), row.syncedReports)
        assertTrue(row.localSyncStates.isEmpty())
        assertFalse(row.hasUnlinkedReports)
        assertEquals(remote.map { CaregiverServerAcceptedLateReport(
            it.actionId, it.recordedAtEpochMillis, it.receivedAtEpochMillis,
        ) }, row.serverAcceptedLateReports)
    }

    @Test fun `accepted TAKEN timestamp maps independently from local and late report times`() {
        val state = event(snapshot.doseEvents.first()).copy(
            lastAcceptedStatus = DoseStatus.TAKEN,
            serverAcceptedTakenAtEpochMillis = 1_798_777_777_000L,
            localReports = listOf(LocalActionReceipt("local", event(snapshot.doseEvents.first()).occurrence,
                DoseActionKind.TAKEN, LocalActionClassification.LATE_TAKEN, 1_798_700_000_000L, SyncState.SYNCED)),
            serverAcceptedLateTakenReports = listOf(ServerAcceptedLateTakenReport("late", 1_798_711_000_000L,
                1_798_722_000_000L)),
        )

        val row = state.toCaregiverEventRow()

        assertEquals(1_798_777_777_000L, row.serverAcceptedTakenRecordedAtEpochMillis)
        assertEquals(1_798_700_000_000L, row.syncedReports.single().recordedAtEpochMillis)
        assertEquals(CaregiverServerAcceptedLateReport("late", 1_798_711_000_000L, 1_798_722_000_000L),
            row.serverAcceptedLateReports.single())
    }

    @Test fun `accepted TAKEN timestamp is absent when missing or status is not TAKEN`() {
        val state = event(snapshot.doseEvents.first())

        val missing = state.copy(lastAcceptedStatus = DoseStatus.TAKEN).toCaregiverEventRow()
        assertNull(missing.serverAcceptedTakenRecordedAtEpochMillis)
        val mismatched = state.copy(lastAcceptedStatus = DoseStatus.MISSED,
            serverAcceptedTakenAtEpochMillis = 1_798_777_777_000L).toCaregiverEventRow()
        assertEquals(DoseStatus.MISSED, mismatched.acceptedStatus)
        assertNull(mismatched.serverAcceptedTakenRecordedAtEpochMillis)
    }

    @Test fun `every local action classification hides unconfirmed details until Synced`() {
        val state = event(snapshot.doseEvents.first())
        for (classification in LocalActionClassification.entries) {
            for (sync in SyncState.entries) {
                val kind = if (classification == LocalActionClassification.REMIND_LATER) DoseActionKind.REMIND_LATER else DoseActionKind.TAKEN
                val report = LocalActionReceipt("test-action", state.occurrence, kind, classification, 123_456L, sync)
                val row = state.copy(localReports = listOf(report)).toCaregiverEventRow()
                assertEquals(setOf(sync), row.localSyncStates)
                assertEquals(if (sync == SyncState.SYNCED) listOf(CaregiverSyncedReport(classification, report.recordedAtEpochMillis)) else emptyList(),
                    row.syncedReports)
                assertEquals(state.lastAcceptedStatus, row.acceptedStatus)
            }
        }
    }

    @Test fun `multiple synced reports retain individual classification times and repository order`() {
        val state = event(snapshot.doseEvents.first())
        val reports = listOf(500L, 100L, 700L).mapIndexed { index, time ->
            LocalActionReceipt("test-$index", state.occurrence, DoseActionKind.TAKEN,
                LocalActionClassification.LATE_TAKEN, time, SyncState.SYNCED)
        }
        val row = state.copy(localReports = reports).toCaregiverEventRow()
        assertEquals(listOf(500L, 100L, 700L), row.syncedReports.map { it.recordedAtEpochMillis })
        assertEquals(setOf(SyncState.SYNCED), row.localSyncStates)
        assertEquals(state.lastAcceptedStatus, row.acceptedStatus)
    }

    @Test fun `foreign occurrence report is unavailable rather than attached to this result`() {
        val state = event(snapshot.doseEvents.first())
        val related = LocalActionReceipt("test-related", state.occurrence, DoseActionKind.TAKEN,
            LocalActionClassification.TIMELY_TAKEN, 100L, SyncState.PENDING_SYNC)
        val foreign = related.copy(actionId = "test-foreign", occurrence = state.occurrence.copy(planId = "other-plan"),
            syncState = SyncState.SYNCED, recordedAtEpochMillis = 999L)
        val row = state.copy(localReports = listOf(related, foreign)).toCaregiverEventRow()
        assertTrue(row.hasUnlinkedReports)
        assertEquals(setOf(SyncState.PENDING_SYNC), row.localSyncStates)
        assertTrue(row.syncedReports.isEmpty())
        assertEquals(state.lastAcceptedStatus, row.acceptedStatus)
    }

    @Test fun `canonical event alone does not invent a synced report or a receipt time`() {
        val state = event(snapshot.doseEvents.first())
        assertNotNull(state.serverEventId)
        val row = state.toCaregiverEventRow()
        assertTrue(row.syncedReports.isEmpty())
        assertTrue(row.localSyncStates.isEmpty())
        assertFalse(row.hasUnlinkedReports)
    }

    private fun event(event: DoseEventView) = DoseEventState(event.occurrence, event.id, event.generationEvidence,
        event.medicationId, event.medicationSnapshot, event.responseDeadlineEpochMillis, event.status, event.status, emptyList())
}
