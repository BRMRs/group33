package com.group33.server.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.shared.contract.HelpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class HelpWireContractTest(@Autowired private val mapper: ObjectMapper) {
    private val valid = """{"actionId":"00000000-0000-4000-8000-000000000300","elderId":"elder-1","groupId":"durable-group-id","occurrenceIds":["00000000-0000-4000-8000-000000000100"],"occurredAtEpochMillis":1795875600001}"""

    @Test fun `Boot decodes the same minimal Help identity`() {
        val request = mapper.readValue(valid, HelpRequest::class.java)
        assertEquals("00000000-0000-4000-8000-000000000300", request.actionId)
        assertEquals("durable-group-id", request.groupId)
    }

    @Test fun `Boot rejects missing unknown and invalid Help fields`() {
        listOf(
            valid.replace(",\"occurredAtEpochMillis\":1795875600001", ""),
            valid.dropLast(1) + ",\"actorId\":\"forged\"}",
            valid.replace("00000000-0000-4000-8000-000000000300", "not-an-action"),
        ).forEach { input -> assertThrows(Exception::class.java) {
            mapper.readValue(input, HelpRequest::class.java)
        } }
    }
}
