package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.proposal.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

/** Existing DTO payloads from one review fixture; not a production projection/reducer test. */
class V2AcceptanceWireTest {
    private val json = ContractProtocol.json
    private val packet = json.parseToJsonElement(checkNotNull(javaClass.getResource(
        "/contracts/proposals/v2-acceptance-candidates.json",
    )).readText()).jsonObject
    private inline fun <reified T> roundTrip(payload: JsonElement) {
        val decoded = json.decodeFromString<T>(payload.toString())
        assertEquals(payload, json.parseToJsonElement(json.encodeToString(decoded)))
    }
    @Test fun `all acceptance payloads retain source time revision and group across runtime wire`() {
        val entries = packet.getValue("wire").jsonArray.map { it.jsonObject }
        assertEquals(18, entries.size)
        assertEquals(entries.size, entries.map { it.getValue("name").jsonPrimitive.content }.distinct().size)
        for (entry in entries) {
            val value = entry.getValue("value")
            when (entry.getValue("type").jsonPrimitive.content) {
                "FrozenDoseGroup" -> roundTrip<FrozenDoseGroup>(value)
                "FollowUpFact" -> roundTrip<FollowUpFact>(value)
                "GroupIntent" -> roundTrip<GroupIntent>(value)
                "GroupIntentReceipt" -> roundTrip<GroupIntentReceipt>(value)
                "DeliveryAcknowledgement" -> roundTrip<DeliveryAcknowledgement>(value)
                else -> error("Unverified proposal DTO in acceptance catalog")
            }
        }
    }
}
