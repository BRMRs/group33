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
