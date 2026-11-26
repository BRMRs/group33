package com.group33.server.contract

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.proposal.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/** Contract vectors only; not proof of live HTTP, authority, persistence or device delivery. */
@SpringBootTest
class ExecutionZoneWireTest(@Autowired private val mapper: ObjectMapper) {
    @Test fun `proposed vector preserves identity and exact payload`() {
        val payload = """{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":2,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1794895200000}"""
        val value = mapper.readValue<ExecutionZoneReport>(payload)
        assertEquals(ContractProtocol.json.decodeFromString<ExecutionZoneReport>(payload), value)
        assertEquals(value, ContractProtocol.json.decodeFromString<ExecutionZoneReport>(mapper.writeValueAsString(value)))
        assertEquals(value, ContractProtocol.json.decodeFromString<ExecutionZoneReport>(ContractProtocol.json.encodeToString(value)))
        assertEquals("Asia/Shanghai", value.ianaTimeZoneId)
    }
    @Test fun `malformed or ambiguous vectors fail closed`() {
        for (payload in listOf(
            """{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":0,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1794895200000}""",
            """{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":2,"ianaTimeZoneId":"+08:00","observedAtEpochMillis":1794895200000}""",
            """{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":2,"ianaTimeZoneId":"Not//AZone","observedAtEpochMillis":1794895200000}""",
            """{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":2,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":-1}""",
            """{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":2,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1794895200000,"actorId":"forged"}""",
            """{"observationSequence":2,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1794895200000}""",
            """{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":1.5,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1794895200000}""",
            """{"reportId":null,"observationSequence":2,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1794895200000}"""
        )) { assertThrows(Exception::class.java) { mapper.readValue<ExecutionZoneReport>(payload) }
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<ExecutionZoneReport>(payload) } }
    }

    @Test fun `unavailable is explicit and current context cannot belong to another elder`() {
        run {
            val payload = """{"elderId":"fictional-e","current":null,"availability":"UNAVAILABLE"}"""
            val value = ContractProtocol.json.decodeFromString<ExecutionZoneContext>(payload)
            assertEquals(value, ContractProtocol.json.decodeFromString<ExecutionZoneContext>(ContractProtocol.json.encodeToString(value)))
            assertEquals(value, mapper.readValue<ExecutionZoneContext>(payload))
        }
        run {
            val payload = """{"elderId":"fictional-e","current":{"elderId":"fictional-e","executionSourceId":"opaque-execution-source","report":{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":2,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1},"serverReceivedAtEpochMillis":2},"availability":"AVAILABLE"}"""
            val value = ContractProtocol.json.decodeFromString<ExecutionZoneContext>(payload)
            assertEquals(value, ContractProtocol.json.decodeFromString<ExecutionZoneContext>(ContractProtocol.json.encodeToString(value)))
            assertEquals(value, mapper.readValue<ExecutionZoneContext>(payload))
        }
        run { val payload = """{"elderId":"another-e","current":{"elderId":"fictional-e","executionSourceId":"opaque-execution-source","report":{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":2,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1},"serverReceivedAtEpochMillis":2},"availability":"AVAILABLE"}"""
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<ExecutionZoneContext>(payload) }
            assertThrows(Exception::class.java) { mapper.readValue<ExecutionZoneContext>(payload) }
        }
        run { val payload = """{"elderId":"fictional-e"}"""
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<ExecutionZoneContext>(payload) }
            assertThrows(Exception::class.java) { mapper.readValue<ExecutionZoneContext>(payload) }
        }
    }

    @Test fun `receipt acknowledges original report without claiming current context`() {
        run {
            val payload = """{"reportId":"00000000-0000-4000-8000-000000000015","receivedAtEpochMillis":2}"""
            val value = ContractProtocol.json.decodeFromString<ExecutionZoneReceipt>(payload)
            assertEquals(value, ContractProtocol.json.decodeFromString<ExecutionZoneReceipt>(ContractProtocol.json.encodeToString(value)))
            assertEquals(value, mapper.readValue<ExecutionZoneReceipt>(payload))
        }
        run { val payload = """{"reportId":"00000000-0000-4000-8000-000000000015","receivedAtEpochMillis":2,"current":{"elderId":"fictional-e","executionSourceId":"opaque-execution-source","report":{"reportId":"00000000-0000-4000-8000-000000000015","observationSequence":2,"ianaTimeZoneId":"Asia/Shanghai","observedAtEpochMillis":1},"serverReceivedAtEpochMillis":2}}"""
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<ExecutionZoneReceipt>(payload) }
            assertThrows(Exception::class.java) { mapper.readValue<ExecutionZoneReceipt>(payload) }
        }
    }
}
