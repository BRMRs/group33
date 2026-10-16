package com.group33.server.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.readValue
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** Real Boot ObjectMapper + HTTP conversion, NOT implementations of the future P0 endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(WireTransportContractTest.ContractProbe::class)
class WireTransportContractTest(
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val mvc: MockMvc,
) {
    private val json = ContractProtocol.json
    private val examples = json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json")).bufferedReader().use { it.readText() },
    ).jsonObject
    private fun at(path: String): JsonElement = path.split('.').fold(examples as JsonElement) { node, part -> node.jsonObject.getValue(part) }

    private inline fun <reified T> verify(path: String): T {
        val source = at(path)
        val shared = json.decodeFromJsonElement<T>(source)
        val server = mapper.readValue<T>(source.toString())
        assertEquals(shared, server, path)
        val serverJson = json.parseToJsonElement(mapper.writeValueAsString(server))
        assertEquals(json.encodeToJsonElement(shared), serverJson, path)
        assertEquals(shared, json.decodeFromJsonElement<T>(serverJson), path)
        return server
    }

    @Test fun `W01 Jackson and kotlinx agree on identity and terminal pairing`() {
        verify<PairingCodeView>("pairingCode")
        verify<PairingRequestView>("pendingPairing")
        verify<PairingRequestView>("terminalPairing")
        verify<CareRelationshipView>("relationship")
    }

    @Test fun `W01 Jackson and kotlinx agree on photo plan revision and edit shapes`() {
        verify<MedicationPhotoView>("photo")
        verify<ConfirmReminderPlanRequest>("confirmPlanRequest")
        verify<ConfirmReminderPlanResponse>("confirmPlanResponse")
        verify<PlanRevisionView>("retainedPlanRevision")
        verify<UpdateReminderPlanResponse>("updatePlanResponse")
        verify<ReminderPlanCollection>("planCollection")
    }

    @Test fun `W01 Jackson and kotlinx agree on generation registration and timely late reports`() {
        verify<OccurrenceRegistrationRequest>("occurrenceRegistration.request")
        verify<OccurrenceRegistrationResponse>("occurrenceRegistration.response")
        for (path in listOf("timelyOffline", "lateTaken")) {
            verify<DoseActionRequest>("$path.request")
            verify<DoseActionReceipt>("$path.response")
        }
    }

    @Test fun `W01 Jackson and kotlinx agree on snapshot and capability receipt ordering representation`() {
        verify<ElderSnapshot>("snapshot")
        verify<ElderSnapshot>("photoOnlySnapshot")
        verify<ReminderCapabilityReport>("unavailableCapability")
        verify<ReminderCapabilityReport>("capabilityOutOfOrder.newerReport")
        verify<ReminderCapabilityReceipt>("capabilityOutOfOrder.newerReceipt")
        verify<ReminderCapabilityReceipt>("capabilityOutOfOrder.delayedOlderReceipt")
    }

    @Test fun `W01 structured errors retain optional nulls and retry flags`() {
        verify<ApiError>("validationError")
        verify<ApiError>("closedPairingRequestResponse.body")
        val error = verify<ApiError>("inProgressResponse.body")
        assertTrue(error.retryable)
    }

    @Test fun `W01 omitted optional fields and explicit null have equal cross-serializer meaning`() {
        val draft = at("confirmPlanRequest.plan").jsonObject
        val withoutEndDate = JsonObject(draft - "endDate")
        assertEquals(json.decodeFromJsonElement<ReminderPlanDraft>(draft), mapper.readValue(withoutEndDate.toString(), ReminderPlanDraft::class.java))
        val confirm = json.decodeFromJsonElement<ConfirmReminderPlanRequest>(at("confirmPlanRequest"))
        val update = UpdateReminderPlanRequest(1L, confirm.plan, confirm.timeZoneAcknowledgement)
        val withNull = json.encodeToJsonElement(update).jsonObject
        val withoutMedication = JsonObject(withNull - "medication")
        assertEquals(update, mapper.readValue(withoutMedication.toString(), UpdateReminderPlanRequest::class.java))
        assertEquals(update, json.decodeFromJsonElement<UpdateReminderPlanRequest>(withoutMedication))
    }

    @Test fun `W01 Jackson must not silently turn missing null or fractional required integers into zero`() {
        val original = at("timelyOffline.request").jsonObject
        for (field in original.keys) {
            for (invalid in listOf(JsonObject(original - field), JsonObject(original + (field to JsonNull)))) {
                assertThrows(JsonProcessingException::class.java, { mapper.readValue(invalid.toString(), DoseActionRequest::class.java) }, field)
            }
        }
        val fractional = JsonObject(original + ("planRevision" to JsonPrimitive(1.5)))
        assertThrows(JsonProcessingException::class.java) { mapper.readValue(fractional.toString(), DoseActionRequest::class.java) }
        val unknown = JsonObject(original + ("arbitraryUserId" to JsonPrimitive("other-elder")))
        assertThrows(JsonProcessingException::class.java) { mapper.readValue(unknown.toString(), DoseActionRequest::class.java) }
    }

    @Test fun `W01 epoch milliseconds preserve Long precision across serializers`() {
        val value = PairingCodeView("id", "00123456", Long.MAX_VALUE - 1)
        assertEquals(value, mapper.readValue(json.encodeToString(value), PairingCodeView::class.java))
        assertEquals(value, json.decodeFromString<PairingCodeView>(mapper.writeValueAsString(value)))
    }

    @Test fun `W01 actual MVC conversion accepts Shared payload and emits Shared-decodable action`() {
        val input = at("timelyOffline.request")
        val response = mvc.perform(post("/__contract-probe/action")
            .with(jwt()).header(ContractProtocol.IDEMPOTENCY_HEADER, at("timelyOffline.idempotencyKey").jsonPrimitive.content)
            .contentType(MediaType.APPLICATION_JSON).content(input.toString()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn().response.contentAsString
        assertEquals(json.decodeFromJsonElement<DoseActionRequest>(input), json.decodeFromString<DoseActionRequest>(response))
    }

    @Test fun `W01 actual MVC conversion returns structured 400 for malformed wire models`() {
        val original = at("timelyOffline.request").jsonObject
        val variants = listOf(
            JsonObject(original - "generationEvidence"),
            JsonObject(original + ("planRevision" to JsonNull)),
            JsonObject(original - "planRevision"),
            JsonObject(original + ("kind" to JsonPrimitive("MISSED"))),
        )
        for (variant in variants) {
            val body = mvc.perform(post("/__contract-probe/action").with(jwt())
                .header(ContractProtocol.IDEMPOTENCY_HEADER, at("timelyOffline.idempotencyKey").jsonPrimitive.content)
                .contentType(MediaType.APPLICATION_JSON).content(variant.toString()))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andReturn().response.contentAsString
            assertFalse(json.decodeFromString<ApiError>(body).retryable)
        }
    }

    @Test fun `W01 probe requires authentication through real application security`() {
        mvc.perform(post("/__contract-probe/action").contentType(MediaType.APPLICATION_JSON)
            .content(at("timelyOffline.request").toString()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test fun `W01 typed command inputs and generic list wrapper decode without erasing element type`() {
        val confirmation = json.decodeFromJsonElement<ConfirmReminderPlanRequest>(at("confirmPlanRequest"))
        val update = UpdateReminderPlanRequest(1, confirmation.plan, confirmation.timeZoneAcknowledgement, confirmation.medication)
        assertEquals(update, mapper.readValue<UpdateReminderPlanRequest>(json.encodeToString(update)))
        val deactivate = DeactivateReminderPlanRequest(1)
        assertEquals(deactivate, mapper.readValue<DeactivateReminderPlanRequest>(json.encodeToString(deactivate)))
        val request = RequestPairingRequest("00123456")
        assertEquals(request, mapper.readValue<RequestPairingRequest>(json.encodeToString(request)))
        val list = ItemsResponse(listOf(json.decodeFromJsonElement<MedicationPhotoView>(at("photo"))))
        assertEquals(list, mapper.readValue<ItemsResponse<MedicationPhotoView>>(json.encodeToString(list)))
        val empty = ItemsResponse<MedicationPhotoView>(emptyList())
        assertEquals("{\"items\":[]}", mapper.writeValueAsString(empty))
        assertThrows(JsonProcessingException::class.java) { mapper.readValue<ItemsResponse<MedicationPhotoView>>("{\"items\":null}") }
    }

    @Test fun `W01 missing required command header returns structured 400 not a generic server error`() {
        mvc.perform(post("/__contract-probe/action").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content(at("timelyOffline.request").toString()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
    }

    /** This controller exists ONLY in test sources; it proves conversion, not dose acceptance. */
    @RestController
    class ContractProbe {
        @PostMapping("/__contract-probe/action")
        fun echo(
            @RequestHeader(ContractProtocol.IDEMPOTENCY_HEADER) idempotencyKey: String,
            @RequestBody request: DoseActionRequest,
        ): DoseActionRequest = request
    }
}
