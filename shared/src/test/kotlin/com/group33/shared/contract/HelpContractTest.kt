package com.group33.shared.contract

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class HelpContractTest {
    private val valid = """{"actionId":"00000000-0000-4000-8000-000000000300","elderId":"elder-1","groupId":"durable-group-id","occurrenceIds":["00000000-0000-4000-8000-000000000100"],"occurredAtEpochMillis":1795875600001}"""

    @Test fun `minimal Help request preserves durable identity`() {
        val request = ContractProtocol.json.decodeFromString<HelpRequest>(valid)
        assertEquals("durable-group-id", request.groupId)
        assertEquals(listOf("00000000-0000-4000-8000-000000000100"), request.occurrenceIds)
        assertEquals(request, ContractProtocol.json.decodeFromString<HelpRequest>(
            ContractProtocol.json.encodeToString(request)))
    }

    @Test fun `malformed or incomplete Help identity is rejected`() {
        listOf(
            valid.replace("00000000-0000-4000-8000-000000000300", "not-an-action"),
            valid.replace("[\"00000000-0000-4000-8000-000000000100\"]", "[]"),
            valid.replace(",\"occurredAtEpochMillis\":1795875600001", ""),
            valid.dropLast(1) + ",\"actorId\":\"forged\"}",
        ).forEach { input -> assertFailsWith<Exception> {
            ContractProtocol.json.decodeFromString<HelpRequest>(input)
        } }
    }
}
