package com.group33.medicationreminder.caregiver.data

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.DoseActionReceipt
import com.group33.shared.model.DoseStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverDoseEventMapperTest {
    private val json = ContractProtocol.json
    private val examples = json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json"))
            .bufferedReader()
            .use { it.readText() },
    ).jsonObject

    @Test
    fun `server late report retains both times without becoming a local report`() {
        val receipt = lateReceipt()

        val state = receipt.event.toDoseEventState()

        assertEquals(receipt.event.occurrence, state.occurrence)
        assertEquals(receipt.event.id, state.serverEventId)
        assertEquals(receipt.event.generationEvidence, state.generationEvidence)
        assertEquals(receipt.event.medicationId, state.medicationId)
        assertEquals(receipt.event.medicationSnapshot, state.medicationSnapshot)
        assertEquals(receipt.event.responseDeadlineEpochMillis, state.responseDeadlineEpochMillis)
        assertEquals(DoseStatus.MISSED, state.effectiveStatus)
        assertEquals(DoseStatus.MISSED, state.lastAcceptedStatus)
        assertTrue(state.localReports.isEmpty())
        assertEquals(1, state.serverAcceptedLateTakenReports.size)
        assertEquals(receipt.actionId, state.serverAcceptedLateTakenReports.single().actionId)
        assertEquals(
            receipt.event.lateTakenReports.single().recordedAtEpochMillis,
            state.serverAcceptedLateTakenReports.single().recordedAtEpochMillis,
        )
        assertEquals(
            receipt.event.lateTakenReports.single().receivedAtEpochMillis,
            state.serverAcceptedLateTakenReports.single().receivedAtEpochMillis,
        )
    }

    @Test
    fun `multiple server late reports keep source order and exact evidence`() {
        val event = lateReceipt().event
        val first = event.lateTakenReports.single()
        val second = first.copy(
            actionId = "00000000-0000-4000-8000-000000000099",
            recordedAtEpochMillis = first.recordedAtEpochMillis + 1_000L,
            receivedAtEpochMillis = first.receivedAtEpochMillis + 2_000L,
        )

        val state = event.copy(lateTakenReports = listOf(first, second)).toDoseEventState()

        assertEquals(
            listOf(first.actionId, second.actionId),
            state.serverAcceptedLateTakenReports.map { it.actionId },
        )
        assertEquals(
            listOf(first.recordedAtEpochMillis, second.recordedAtEpochMillis),
            state.serverAcceptedLateTakenReports.map { it.recordedAtEpochMillis },
        )
        assertEquals(
            listOf(first.receivedAtEpochMillis, second.receivedAtEpochMillis),
            state.serverAcceptedLateTakenReports.map { it.receivedAtEpochMillis },
        )
        assertTrue(state.localReports.isEmpty())
    }

    @Test
    fun `event without a server late report does not invent one`() {
        val event = lateReceipt().event.copy(lateTakenReports = emptyList())

        val state = event.toDoseEventState()

        assertTrue(state.serverAcceptedLateTakenReports.isEmpty())
        assertTrue(state.localReports.isEmpty())
        assertEquals(event.status, state.effectiveStatus)
        assertEquals(event.status, state.lastAcceptedStatus)
    }

    private fun lateReceipt(): DoseActionReceipt =
        json.decodeFromJsonElement(at("lateTaken.response"))

    private fun at(path: String): JsonElement =
        path.split('.').fold(examples as JsonElement) { node, part -> node.jsonObject.getValue(part) }
}
