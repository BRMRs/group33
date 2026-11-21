package com.group33.shared.contract

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.proposal.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Registry-skew fixture, not a claim to have installed multiple historical tzdb versions. */
class ExecutionZoneCompatibilityTest {
    private val futurePayload = """{"elderId":"fictional-e","availability":"AVAILABLE","current":{"elderId":"fictional-e","executionSourceId":"opaque-source","report":{"reportId":"00000000-0000-4000-8000-abcdef012345","observationSequence":3,"ianaTimeZoneId":"Future/Zone_Not_In_Local_Registry","observedAtEpochMillis":1},"serverReceivedAtEpochMillis":2}}"""
    private fun decode() = ContractProtocol.json.decodeFromString<ExecutionZoneContext>(futurePayload)

    @Test fun `new registry zone remains decodable as wire without local lookup`() {
        val context = decode()
        assertEquals("Future/Zone_Not_In_Local_Registry", context.current!!.report.ianaTimeZoneId)
        assertEquals(context, ContractProtocol.json.decodeFromString<ExecutionZoneContext>(ContractProtocol.json.encodeToString(context)))
    }
    @Test fun `canonical lowercase UUID is explicit for report and original receipt`() {
        val lower = "00000000-0000-4000-8000-abcdef012345"
        val report = ExecutionZoneReport(lower, 1, "UTC", 0)
        assertEquals(lower, report.reportId)
        assertEquals(lower, ExecutionZoneReceipt(lower, 0).reportId)
        val payload = """{"reportId":"00000000-0000-4000-8000-ABCDEF012345","observationSequence":1,"ianaTimeZoneId":"UTC","observedAtEpochMillis":0}"""
        assertFails { ContractProtocol.json.decodeFromString<ExecutionZoneReport>(payload) }
        assertFails { ExecutionZoneReceipt(lower.uppercase(), 0) }
    }

    @Test fun `older registry publishes unavailable without changing raw newest context`() {
        val raw = decode(); val before = ContractProtocol.json.encodeToString(raw)
        val local = raw.forLocalRuntime { it in setOf("UTC", "Asia/Shanghai") }
        assertEquals(ExecutionZoneAvailability.UNAVAILABLE, local.availability)
        assertEquals(null, local.current)
        assertEquals(raw.elderId, local.elderId)
        assertEquals(before, ContractProtocol.json.encodeToString(raw))
        assertEquals(ExecutionZoneAvailability.UNAVAILABLE, raw.forLocalRuntime().availability)
    }
    @Test fun `recognized registry preserves original context and unavailable never guesses a zone`() {
        val raw = decode()
        assertEquals(raw, raw.forLocalRuntime { it == "Future/Zone_Not_In_Local_Registry" })
        val unavailable = ExecutionZoneContext(raw.elderId, ExecutionZoneAvailability.UNAVAILABLE)
        assertEquals(unavailable, unavailable.forLocalRuntime { error("Unavailable context must not invent a zone lookup") })
    }
    @Test fun `failed local registry lookup also produces explicit unavailable`() {
        val local = decode().forLocalRuntime { throw IllegalStateException("Fictional unavailable registry") }
        assertEquals(ExecutionZoneAvailability.UNAVAILABLE, local.availability)
        assertEquals(null, local.current)
    }
    @Test fun `wire syntax still rejects offsets empty components and unbounded names`() {
        for (zone in listOf("", "+08:00", "America//New_York", "../Zone", "Europe/..", "Area/" + "x".repeat(256))) {
            assertFails { ExecutionZoneReport("00000000-0000-4000-8000-abcdef012345", 1, zone, 0) }
        }
    }
}
