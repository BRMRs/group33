package com.group33.shared.contract

import com.group33.shared.model.ApiError
import com.group33.shared.model.DoseStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** W01 representation tests; not proof of any transaction, resolver or state reducer. */
class WireContractTest {
    private val json = ContractProtocol.json
    private val examples = json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json")).bufferedReader().use { it.readText() },
    ).jsonObject

    private fun at(path: String): JsonElement = path.split('.').fold(examples as JsonElement) { node, part -> node.jsonObject.getValue(part) }

    private inline fun <reified T> roundTrip(path: String): T {
        val value = json.decodeFromJsonElement<T>(at(path))
        val encoded = json.encodeToJsonElement(value)
        // Input fixtures may omit explicitly nullable defaults, e.g. ApiError.reason.
        assertEquals(value, json.decodeFromJsonElement<T>(encoded), path)
        at(path).jsonObject.forEach { (key, expected) -> assertEquals(expected, encoded.jsonObject[key], "$path.$key") }
        return value
    }

    @Test fun `W01 pairing terminal states and participant identities round trip`() {
        val code = roundTrip<PairingCodeView>("pairingCode")
        assertEquals("00123456", code.code)
        assertEquals(PairingRequestStatus.PENDING, roundTrip<PairingRequestView>("pendingPairing").status)
        assertEquals(PairingClosedReason.CODE_REPLACED, roundTrip<PairingRequestView>("terminalPairing").closedReason)
        val relation = roundTrip<CareRelationshipView>("relationship")
        assertEquals(relation.elderId, relation.elder.id)
        assertEquals(relation.caregiverId, relation.caregiver.id)
    }

    @Test fun `W01 photo confirmation retained revision collection and atomic update shapes round trip`() {
        roundTrip<MedicationPhotoView>("photo")
        val request = roundTrip<ConfirmReminderPlanRequest>("confirmPlanRequest")
        assertEquals("Asia/Shanghai", request.plan.executionTimeZoneId)
        roundTrip<ConfirmReminderPlanResponse>("confirmPlanResponse")
        roundTrip<PlanRevisionView>("retainedPlanRevision")
        val update = roundTrip<UpdateReminderPlanResponse>("updatePlanResponse")
        assertEquals(2L, update.plan.revision)
        roundTrip<ReminderPlanCollection>("planCollection")
    }

    @Test fun `W01 timely and late action facts preserve IDs frozen generation and separate classification`() {
        for (path in listOf("timelyOffline", "lateTaken")) {
            val request = roundTrip<DoseActionRequest>("$path.request")
            val receipt = roundTrip<DoseActionReceipt>("$path.response")
            assertEquals(at("$path.idempotencyKey").jsonPrimitive.content, request.actionId)
            assertEquals(request.actionId, receipt.actionId)
            assertEquals(request.generationEvidence, receipt.event.generationEvidence)
            assertEquals(7_200_000L, receipt.event.responseDeadlineEpochMillis - receipt.event.plannedAtEpochMillis)
        }
        val late = json.decodeFromJsonElement<DoseActionReceipt>(at("lateTaken.response"))
        assertEquals(DoseStatus.MISSED, late.event.status)
        assertEquals(DoseActionClassification.LATE_TAKEN, late.classification)
        assertEquals(late.actionId, late.event.lateTakenReports.single().actionId)
    }

    @Test fun `W01 registration is typed and correlates original generation not a new revision key`() {
        val request = roundTrip<OccurrenceRegistrationRequest>("occurrenceRegistration.request")
        val response = roundTrip<OccurrenceRegistrationResponse>("occurrenceRegistration.response")
        assertEquals(request.items.single().generationId, response.items.single().generationId)
        assertEquals(request.items.single().occurrence, response.items.single().event.occurrence)
        assertEquals(setOf("planId", "localDate", "localTime"), json.encodeToJsonElement(request.items.single().occurrence).jsonObject.keys)
    }

    @Test fun `W01 capability acknowledgment may refer to older report while current is newer`() {
        roundTrip<ReminderCapabilityReport>("unavailableCapability")
        roundTrip<ReminderCapabilityReport>("capabilityOutOfOrder.newerReport")
        roundTrip<ReminderCapabilityReceipt>("capabilityOutOfOrder.newerReceipt")
        roundTrip<ReminderCapabilityReport>("capabilityOutOfOrder.delayedOlderReport")
        val receipt = roundTrip<ReminderCapabilityReceipt>("capabilityOutOfOrder.delayedOlderReceipt")
        assertFalse(receipt.applied)
        assertNotEquals(receipt.reportId, receipt.current.reportId)
        assertEquals(2L, receipt.current.observationSequence)
    }

    @Test fun `W01 complete and photo-only snapshots decode without inventing a medication`() {
        roundTrip<ElderSnapshot>("snapshot")
        val photoOnly = roundTrip<ElderSnapshot>("photoOnlySnapshot")
        assertTrue(photoOnly.photos.isNotEmpty())
        assertTrue(photoOnly.medications.isEmpty())
        assertTrue(photoOnly.doseEvents.isEmpty())
    }

    @Test fun `W01 caregiver snapshot omitted capability stays unknown like explicit null`() {
        val snapshot = at("snapshot").jsonObject
        val omitted = json.decodeFromJsonElement<ElderSnapshot>(JsonObject(snapshot - "capability"))
        val explicitNull = json.decodeFromJsonElement<ElderSnapshot>(JsonObject(snapshot + ("capability" to JsonNull)))

        assertNull(omitted.capability)
        assertEquals(explicitNull, omitted)
        assertEquals(json.decodeFromJsonElement<ElderSnapshot>(snapshot).copy(capability = null), omitted)
    }

    @Test fun `W01 caregiver snapshot rejects malformed capability instead of treating it as unknown`() {
        val snapshot = at("snapshot").jsonObject
        val capability = snapshot.getValue("capability").jsonObject
        val malformedCapabilities = mapOf(
            "missing observation time" to JsonObject(capability - "observedAtEpochMillis"),
            "unknown delivery condition" to JsonObject(capability + ("deliveryCondition" to JsonPrimitive("UNKNOWN"))),
        )

        for ((reason, malformed) in malformedCapabilities) {
            assertFailsWith<SerializationException>(reason) {
                json.decodeFromJsonElement<ElderSnapshot>(JsonObject(snapshot + ("capability" to malformed)))
            }
        }
    }

    @Test fun `W01 error envelopes retain codes and nullable reasons`() {
        roundTrip<ApiError>("validationError")
        val progress = roundTrip<ApiError>("inProgressResponse.body")
        assertTrue(progress.retryable)
        assertEquals("OPERATION_IN_PROGRESS", progress.code)
        assertEquals("PAIRING_REQUEST_CLOSED", roundTrip<ApiError>("closedPairingRequestResponse.body").code)
    }

    @Test fun `W01 required fields unknown enums and null nonnullable fields fail decoding`() {
        val request = at("timelyOffline.request").jsonObject
        for (field in request.keys) {
            assertFailsWith<SerializationException>(field) { json.decodeFromJsonElement<DoseActionRequest>(JsonObject(request - field)) }
            assertFailsWith<SerializationException>(field) { json.decodeFromJsonElement<DoseActionRequest>(JsonObject(request + (field to JsonNull))) }
        }
        assertFailsWith<SerializationException> {
            json.decodeFromJsonElement<DoseActionRequest>(JsonObject(request + ("kind" to JsonPrimitive("MISSED"))))
        }
        assertFailsWith<SerializationException> {
            json.decodeFromJsonElement<OccurrenceGenerationEvidence>(JsonObject(request.getValue("generationEvidence").jsonObject + ("resolutionPolicyVersion" to JsonPrimitive("UNKNOWN"))))
        }
    }

    @Test fun `W01 documented optional omitted and null fields have equal typed meaning`() {
        val withNull = at("confirmPlanRequest.plan").jsonObject
        val omitted = JsonObject(withNull - "endDate")
        assertEquals(json.decodeFromJsonElement<ReminderPlanDraft>(withNull), json.decodeFromJsonElement<ReminderPlanDraft>(omitted))
        assertEquals(JsonNull, json.encodeToJsonElement(json.decodeFromJsonElement<ReminderPlanDraft>(omitted)).jsonObject["endDate"])
        val text = MedicationText("  药品 A  ", "Demo", "")
        assertEquals(text, json.decodeFromString<MedicationText>(json.encodeToString(text)))
        assertNotEquals(text, text.copy(displayName = text.displayName.trim()))
    }

    @Test fun `W01 integer milliseconds and revisions are Long without float conversion`() {
        val max = Long.MAX_VALUE - 1
        val code = PairingCodeView("id", "00123456", max)
        assertEquals(max, json.decodeFromString<PairingCodeView>(json.encodeToString(code)).expiresAtEpochMillis)
        assertEquals("$max", json.encodeToJsonElement(code).jsonObject.getValue("expiresAtEpochMillis").toString())
    }

    @Test fun `W01 remaining command inputs and typed empty collections round trip`() {
        val confirm = json.decodeFromJsonElement<ConfirmReminderPlanRequest>(at("confirmPlanRequest"))
        val update = UpdateReminderPlanRequest(1, confirm.plan, confirm.timeZoneAcknowledgement, confirm.medication)
        assertEquals(update, json.decodeFromString<UpdateReminderPlanRequest>(json.encodeToString(update)))
        val deactivate = DeactivateReminderPlanRequest(1)
        assertEquals(deactivate, json.decodeFromString<DeactivateReminderPlanRequest>(json.encodeToString(deactivate)))
        val pairing = RequestPairingRequest("00123456")
        assertEquals(pairing, json.decodeFromString<RequestPairingRequest>(json.encodeToString(pairing)))
        val empty = ItemsResponse<MedicationPhotoView>(emptyList())
        assertEquals("{\"items\":[]}", json.encodeToString(empty))
        assertEquals(empty, json.decodeFromString<ItemsResponse<MedicationPhotoView>>(json.encodeToString(empty)))
        assertFailsWith<SerializationException> { json.decodeFromString<ItemsResponse<MedicationPhotoView>>("{\"items\":null}") }
    }
}
