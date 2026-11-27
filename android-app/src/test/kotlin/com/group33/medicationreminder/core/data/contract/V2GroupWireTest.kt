package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.proposal.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.Assert.*

/** Contract vectors only; not proof of live HTTP, authority, persistence or device delivery. */
class V2GroupWireTest {
    @Test fun `proposed vector preserves identity and exact payload`() {
        val payload = """{"actionId":"00000000-0000-4000-8000-000000000025","group":{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400000,"members":[{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000}]},"kind":"HELP","recordedAtEpochMillis":1792454401000}"""
        val value = ContractProtocol.json.decodeFromString<GroupIntent>(payload)
        assertEquals(value, ContractProtocol.json.decodeFromString<GroupIntent>(ContractProtocol.json.encodeToString(value)))
        assertEquals(GroupIntentKind.HELP, value.kind)
        assertEquals(1, value.group.members.size)
    }
    @Test fun `malformed or ambiguous vectors fail closed`() {
        for (payload in listOf(
            """{"actionId":"00000000-0000-4000-8000-000000000025","group":{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400000,"members":[{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000}]},"kind":"CAREGIVER_TAKEN_OVER","recordedAtEpochMillis":1792454401000}""",
            """{"actionId":"00000000-0000-4000-8000-000000000025","group":{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400000,"members":[]},"kind":"HELP","recordedAtEpochMillis":1792454401000}""",
            """{"actionId":"00000000-0000-4000-8000-000000000025","group":{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400000,"members":[{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000},{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000}]},"kind":"HELP","recordedAtEpochMillis":1792454401000}""",
            """{"actionId":"00000000-0000-4000-8000-000000000025","group":{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400001,"members":[{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000}]},"kind":"HELP","recordedAtEpochMillis":1792454401000}""",
            """{"actionId":"00000000-0000-4000-8000-000000000025","group":{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400000,"members":[{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000}]},"kind":"HELP","recordedAtEpochMillis":1792454401000,"actorId":"forged"}""",
            """{"group":{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400000,"members":[{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000}]},"kind":"HELP","recordedAtEpochMillis":1792454401000}""",
            """{"actionId":"00000000-0000-4000-8000-000000000025","group":{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400000,"members":[{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000}]},"kind":"HELP","recordedAtEpochMillis":1.5}""",
            """{"actionId":"00000000-0000-4000-8000-000000000025","group":null,"kind":"HELP","recordedAtEpochMillis":1792454401000}"""
        ).filterIndexed { index, _ -> index !in setOf(1, 2, 3) }) {
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<GroupIntent>(payload) }
        }
    }

    @Test fun `typed CaregiverTakeover has separate round trip identity`() {
        val payload = """{"actionId":"00000000-0000-4000-8000-000000000001","doseGroupId":"00000000-0000-4000-8000-000000000002","executionScopeId":"fictional-env/install/elder","recordedAtEpochMillis":1}"""
        val value = ContractProtocol.json.decodeFromString<CaregiverTakeover>(payload)
        assertEquals(value, ContractProtocol.json.decodeFromString<CaregiverTakeover>(ContractProtocol.json.encodeToString(value)))
    }

    @Test fun `typed FollowUpCommand has separate round trip identity`() {
        val payload = """{"actionId":"00000000-0000-4000-8000-000000000001","followUpId":"00000000-0000-4000-8000-000000000003","doseGroupId":"00000000-0000-4000-8000-000000000002","expectedRevision":0,"outcome":"UNABLE_TO_CONFIRM","enteredAtEpochMillis":1,"actualTime":{"knowledge":"UNKNOWN","epochMillis":null}}"""
        val value = ContractProtocol.json.decodeFromString<FollowUpCommand>(payload)
        assertEquals(value, ContractProtocol.json.decodeFromString<FollowUpCommand>(ContractProtocol.json.encodeToString(value)))
    }

    @Test fun `typed DispatchedProbe has separate round trip identity`() {
        val payload = """{"roundId":"00000000-0000-4000-8000-000000000001","attemptId":"00000000-0000-4000-8000-000000000002","doseGroupId":"00000000-0000-4000-8000-000000000003","phase":"AFTER_FIVE_MINUTES","dispatchedAtEpochMillis":1}"""
        val value = ContractProtocol.json.decodeFromString<DispatchedProbe>(payload)
        assertEquals(value, ContractProtocol.json.decodeFromString<DispatchedProbe>(ContractProtocol.json.encodeToString(value)))
    }

    @Test fun `typed ProbeReply has separate round trip identity`() {
        val payload = """{"roundId":"00000000-0000-4000-8000-000000000001","attemptId":"00000000-0000-4000-8000-000000000002"}"""
        val value = ContractProtocol.json.decodeFromString<ProbeReply>(payload)
        assertEquals(value, ContractProtocol.json.decodeFromString<ProbeReply>(ContractProtocol.json.encodeToString(value)))
    }

    @Test fun `typed DeliveryAcknowledgement has separate round trip identity`() {
        val payload = """{"alertId":"00000000-0000-4000-8000-000000000001","deliveryId":"00000000-0000-4000-8000-000000000002","attemptId":"00000000-0000-4000-8000-000000000003","fencingToken":1,"stage":"DEVICE_RECEIVED","recordedAtEpochMillis":1}"""
        val value = ContractProtocol.json.decodeFromString<DeliveryAcknowledgement>(payload)
        assertEquals(value, ContractProtocol.json.decodeFromString<DeliveryAcknowledgement>(ContractProtocol.json.encodeToString(value)))
    }

    @Test fun `typed GroupIntentReceipt has separate round trip identity`() {
        val payload = """{"actionId":"00000000-0000-4000-8000-000000000001","doseGroupId":"00000000-0000-4000-8000-000000000002","receivedAtEpochMillis":2}"""
        val value = ContractProtocol.json.decodeFromString<GroupIntentReceipt>(payload)
        assertEquals(value, ContractProtocol.json.decodeFromString<GroupIntentReceipt>(ContractProtocol.json.encodeToString(value)))
    }

    @Test fun `caregiver actual time may be explicitly unknown and correction version is required`() {
        run {
            val payload = """{"actionId":"00000000-0000-4000-8000-000000000001","followUpId":"00000000-0000-4000-8000-000000000002","doseGroupId":"00000000-0000-4000-8000-000000000003","expectedRevision":1,"outcome":"TAKEN","enteredAtEpochMillis":1,"actualTime":{"knowledge":"UNKNOWN","epochMillis":null}}"""
            val value = ContractProtocol.json.decodeFromString<FollowUpCommand>(payload)
            assertEquals(value, ContractProtocol.json.decodeFromString<FollowUpCommand>(ContractProtocol.json.encodeToString(value)))
        }
        run { val payload = """{"actionId":"00000000-0000-4000-8000-000000000001","followUpId":"00000000-0000-4000-8000-000000000002","doseGroupId":"00000000-0000-4000-8000-000000000003","expectedRevision":1,"outcome":"TAKEN","enteredAtEpochMillis":1}"""
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<FollowUpCommand>(payload) }
        }
        run { val payload = """{"actionId":"00000000-0000-4000-8000-000000000001","followUpId":"00000000-0000-4000-8000-000000000002","doseGroupId":"00000000-0000-4000-8000-000000000003","expectedRevision":1,"outcome":"ELDER_TAKEN","enteredAtEpochMillis":1,"actualTime":{"knowledge":"UNKNOWN","epochMillis":null}}"""
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<FollowUpCommand>(payload) }
        }
    }

    @Test fun `server fact keeps source and receipt time distinct from actual time`() {
        run {
            val payload = """{"command":{"actionId":"00000000-0000-4000-8000-000000000001","followUpId":"00000000-0000-4000-8000-000000000002","doseGroupId":"00000000-0000-4000-8000-000000000003","expectedRevision":1,"outcome":"TAKEN","enteredAtEpochMillis":1,"actualTime":{"knowledge":"UNKNOWN","epochMillis":null}},"actorId":"fictional-caregiver","revision":2,"receivedAtEpochMillis":3}"""
            val value = ContractProtocol.json.decodeFromString<FollowUpFact>(payload)
            assertEquals(value, ContractProtocol.json.decodeFromString<FollowUpFact>(ContractProtocol.json.encodeToString(value)))
        }
    }

    @Test fun `reply does not accept caller supplied trusted receipt time`() {
        run {
            val payload = """{"roundId":"00000000-0000-4000-8000-000000000001","attemptId":"00000000-0000-4000-8000-000000000002"}"""
            val value = ContractProtocol.json.decodeFromString<ProbeReply>(payload)
            assertEquals(value, ContractProtocol.json.decodeFromString<ProbeReply>(ContractProtocol.json.encodeToString(value)))
        }
        run { val payload = """{"roundId":"00000000-0000-4000-8000-000000000001","attemptId":"00000000-0000-4000-8000-000000000002","receivedAtEpochMillis":1}"""
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<ProbeReply>(payload) }
        }
    }

    @Test fun `read ack and contacted business fact cannot be conflated`() {
        run {
            val payload = """{"alertId":"00000000-0000-4000-8000-000000000001","deliveryId":"00000000-0000-4000-8000-000000000002","attemptId":"00000000-0000-4000-8000-000000000003","fencingToken":2,"stage":"READ","recordedAtEpochMillis":1}"""
            val value = ContractProtocol.json.decodeFromString<DeliveryAcknowledgement>(payload)
            assertEquals(value, ContractProtocol.json.decodeFromString<DeliveryAcknowledgement>(ContractProtocol.json.encodeToString(value)))
        }
        run { val payload = """{"alertId":"00000000-0000-4000-8000-000000000001","deliveryId":"00000000-0000-4000-8000-000000000002","attemptId":"00000000-0000-4000-8000-000000000003","fencingToken":2,"stage":"CONTACTED","recordedAtEpochMillis":1}"""
            assertThrows(Exception::class.java) { ContractProtocol.json.decodeFromString<DeliveryAcknowledgement>(payload) }
        }
    }
}
