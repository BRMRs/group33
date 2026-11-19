package com.group33.shared.contract

import com.group33.shared.contract.proposal.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Review-fixture invariants only. No production projection or recovery handler exists here. */
class V2AcceptanceCandidatesTest {
    private val json = ContractProtocol.json
    private val packet = json.parseToJsonElement(checkNotNull(javaClass.getResource(
        "/contracts/proposals/v2-acceptance-candidates.json",
    )).readText()).jsonObject
    private val scenarios = packet.getValue("scenarios").jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun scenario(key: String) = scenarios.getValue(key).jsonObject
    private fun wire(ref: String) = packet.getValue("wire").jsonArray
        .map { it.jsonObject }.single { it.text("name") == ref }.getValue("value")
    private inline fun <reified T> decode(ref: String): T = json.decodeFromString(wire(ref).toString())

    @Test fun `packet is explicitly an unaccepted review envelope`() {
        assertEquals("PROPOSED_NOT_ACCEPTED", packet.text("status"))
        assertEquals("TEST_REVIEW_ENVELOPE_NOT_PRODUCTION_WIRE", packet.text("scope"))
        assertEquals("PENDING_E_S1_SERVER", scenario("rejectedSnoozeRecovery").text("decision"))
        assertEquals(5, scenarios.size)
    }

    @Test fun `no-event candidate retains group link caregiver source and unknown actual time`() {
        val s = scenario("noCanonicalEvent"); val expected = s.getValue("expected").jsonObject
        val group = decode<FrozenDoseGroup>(s.text("group"))
        val fact = decode<FollowUpFact>(s.text("followUp"))
        assertTrue(s.getValue("canonicalEvents").jsonArray.isEmpty())
        assertFalse(expected.flag("fabricateElderEvent"))
        assertEquals(group.doseGroupId, fact.command.doseGroupId)
        assertEquals("CAREGIVER", expected.text("source"))
        assertEquals("fictional-caregiver", fact.actorId)
        assertEquals(expected.number("currentRevision"), fact.revision)
        assertEquals(expected.text("effectiveOutcome"), fact.command.outcome.name)
        assertEquals(ActualTimeKnowledge.UNKNOWN, fact.command.actualTime.knowledge)
        assertNull(fact.command.actualTime.epochMillis)
        assertTrue(fact.receivedAtEpochMillis > fact.command.enteredAtEpochMillis)
    }

    @Test fun `correction candidate keeps both sources and receipt separate from current conclusion`() {
        val s = scenario("correctionAndReplay"); val expected = s.getValue("expected").jsonObject
        val facts = s.getValue("followUps").jsonArray.map { decode<FollowUpFact>(it.jsonPrimitive.content) }
        val late = decode<GroupIntent>(s.text("lateElderIntent"))
        val receipt = decode<GroupIntentReceipt>(s.text("replayedReceipt"))
        assertEquals(listOf(1L, 2L), facts.map { it.revision })
        assertEquals(listOf(FollowUpOutcome.TAKEN, FollowUpOutcome.NOT_TAKEN), facts.map { it.command.outcome })
        assertEquals(facts.first().command.followUpId, facts.last().command.followUpId)
        assertEquals(facts.last().revision - 1, facts.last().command.expectedRevision)
        assertEquals(facts.last().revision, expected.number("currentRevision"))
        assertEquals(facts.last().command.outcome.name, expected.text("effectiveOutcome"))
        assertEquals("CAREGIVER", expected.text("source"))
        assertEquals(facts.map { it.revision }, expected.getValue("retainedFollowUpRevisions").jsonArray.map { it.jsonPrimitive.long })
        assertEquals(GroupIntentKind.TAKEN, late.kind)
        assertEquals(late.actionId, receipt.actionId)
        assertEquals(facts.last().command.doseGroupId, receipt.doseGroupId)
        assertEquals(late.group.doseGroupId, receipt.doseGroupId)
        assertTrue(late.recordedAtEpochMillis < facts.last().receivedAtEpochMillis)
        assertTrue(s.number("lateElderReceivedAtEpochMillis") > facts.last().receivedAtEpochMillis)
        assertEquals(s.number("lateElderReceivedAtEpochMillis"), receipt.receivedAtEpochMillis)
        assertTrue(expected.flag("retainLateElderFact"))
        assertFalse(expected.flag("receiptIsCurrentProjection"))
    }

    @Test fun `each delivery stage coexists with accepted and unaccepted action evidence`() {
        val s = scenario("independentEvidence")
        assertEquals(GroupIntentKind.HELP, decode<GroupIntent>(s.text("intent")).kind)
        val observations = s.getValue("observations").jsonArray.map { it.jsonObject }
        assertEquals(16, observations.size)
        assertEquals(16, observations.map { it.text("localState") to it.text("delivery") }.distinct().size)
        for (o in observations) {
            val delivery = decode<DeliveryAcknowledgement>(o.text("delivery"))
            assertEquals("delivery" + delivery.stage.name, o.text("delivery"))
            assertEquals(o.text("localState") == "SERVER_ACCEPTED", o.flag("serverAccepted"))
            assertEquals(o.text("localState").startsWith("SERVER_"), o.flag("remoteKnowsLocalAction"))
            assertFalse(o.flag("expectedTaken"))
        }
        for (stage in DeliveryStage.entries) {
            val sameStage = observations.filter { it.text("delivery") == "delivery" + stage.name }
            assertEquals(setOf(false, true), sameStage.map { it.flag("serverAccepted") }.toSet())
        }
    }

    @Test fun `offline timing candidates use original action and deadline including tie boundaries`() {
        val s = scenario("offlineSnooze"); val planned = s.number("plannedAtEpochMillis")
        val deadline = s.number("deadlineEpochMillis")
        assertEquals(planned + 7_200_000, deadline)
        val cases = s.getValue("cases").jsonArray.map { it.jsonObject }
        val outcomes = mutableSetOf<Boolean>()
        for (c in cases) {
            val action = decode<GroupIntent>(c.text("intent")); val e = c.getValue("expected").jsonObject
            val timely = action.recordedAtEpochMillis in planned until deadline
            val target = action.recordedAtEpochMillis + 600_000
            assertEquals(GroupIntentKind.REMIND_LATER, action.kind)
            assertEquals(planned, action.group.plannedAtEpochMillis)
            assertEquals(timely, e.flag("actionInsideWindow"))
            assertEquals(target, e.number("nominalTargetEpochMillis"))
            assertNotEquals(c.number("serverReceivedAtEpochMillis") + 600_000, target)
            assertEquals(if (timely && target < deadline) JsonPrimitive(target) else JsonNull, e.getValue("activeTargetEpochMillis"))
            val repeat = timely && target < deadline && c.number("observedAtEpochMillis") in target until deadline
            assertEquals(repeat, e.flag("repeatAtObservation")); outcomes.add(repeat)
        }
        assertEquals(setOf(false, true), outcomes)
        val targets = cases.map { it.getValue("expected").jsonObject.number("nominalTargetEpochMillis") }
        assertTrue(targets.containsAll(listOf(deadline - 1, deadline, deadline + 1)))
        assertTrue(cases.any { decode<GroupIntent>(it.text("intent")).recordedAtEpochMillis == deadline })
        assertTrue(cases.any { decode<GroupIntent>(it.text("intent")).recordedAtEpochMillis < planned })
    }

    @Test fun `pending rejection candidate preserves record and never blindly restores old prompt`() {
        val s = scenario("rejectedSnoozeRecovery")
        val intent = decode<GroupIntent>(s.text("intent"))
        assertTrue(s.number("serverRejectedAtEpochMillis") > intent.recordedAtEpochMillis)
        val cases = s.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(setOf("NONE", "AT_CUTOFF", "KNOWN_TAKEN", "KNOWN_HELP", "ACKNOWLEDGED_TAKEOVER", "REVOKED_AUTHORIZATION", "OBSOLETE_EXECUTION_SCOPE"), cases.map { it.text("guard") }.toSet())
        for (c in cases) {
            assertTrue(c.flag("preserveRejectedRecord"))
            assertFalse(c.flag("serverAccepted")); assertFalse(c.flag("createNewOccurrence"))
            assertFalse(c.flag("resetDeadline"))
            assertEquals(c.text("guard") == "NONE", c.flag("candidateRestoreOriginalPrompt"))
            if (c.flag("candidateRestoreOriginalPrompt")) {
                assertTrue(c.number("observedAtEpochMillis") < intent.group.plannedAtEpochMillis + 7_200_000)
            }
        }
    }
}
