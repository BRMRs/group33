package com.group33.server.contract

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.proposal.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/** Existing DTO payloads from one review fixture; not a production projection/reducer test. */
@SpringBootTest
class V2AcceptanceWireTest(@Autowired private val mapper: ObjectMapper) {
    private val json = ContractProtocol.json
    private fun fixture(name: String) = json.parseToJsonElement(checkNotNull(
        javaClass.getResource("/contracts/proposals/$name"),
    ).readText()).jsonObject
    private val packet = fixture("v2-acceptance-candidates.json")
    private inline fun <reified T> roundTrip(payload: JsonElement) {
        val decoded = json.decodeFromString<T>(payload.toString())
        assertEquals(payload, json.parseToJsonElement(json.encodeToString(decoded)))
        val boot = mapper.readValue<T>(payload.toString())
        assertEquals(decoded, boot)
        assertEquals(payload, json.parseToJsonElement(mapper.writeValueAsString(boot)))
        assertEquals(decoded, json.decodeFromString<T>(mapper.writeValueAsString(boot)))
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

    @Test fun `published base and additional vectors are the vectors under test`() {
        val base = fixture("v2-vectors.json")
        roundTrip<GroupIntent>(base.getValue("valid"))
        val candidates = base.getValue("invalid").jsonArray
        base.getValue("wireRejectedIndexes").jsonArray.forEach { index ->
            val payload = candidates[index.jsonPrimitive.int]
            assertThrows(Exception::class.java) { json.decodeFromString<GroupIntent>(payload.toString()) }
            assertThrows(Exception::class.java) { mapper.readValue<GroupIntent>(payload.toString()) }
        }
        base.getValue("domainRejectedIndexes").jsonArray.forEach { index ->
            roundTrip<GroupIntent>(candidates[index.jsonPrimitive.int])
        }

        fixture("v2-additional-vectors.json").getValue("vectors").jsonArray.forEach { vector ->
            val entry = vector.jsonObject
            val value = entry.getValue("value")
            when (entry.getValue("type").jsonPrimitive.content) {
                "CaregiverTakeover" -> roundTrip<CaregiverTakeover>(value)
                "CaregiverTakeoverAcknowledgement" -> roundTrip<CaregiverTakeoverAcknowledgement>(value)
                "CaregiverContact" -> roundTrip<CaregiverContact>(value)
                "FollowUpCommand" -> roundTrip<FollowUpCommand>(value)
                "DispatchedProbe" -> roundTrip<DispatchedProbe>(value)
                "ProbeReply" -> roundTrip<ProbeReply>(value)
                "DeliveryAcknowledgement" -> roundTrip<DeliveryAcknowledgement>(value)
                "GroupIntentReceipt" -> roundTrip<GroupIntentReceipt>(value)
                else -> error("Unverified proposal DTO in additional catalog")
            }
        }
    }
}
