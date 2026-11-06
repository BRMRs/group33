package com.group33.server.contract

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ConfirmReminderPlanRequest
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.ReminderPlanDraft
import com.group33.shared.contract.RequestPairingRequest
import com.group33.shared.contract.TimeZoneAcknowledgement
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.ApiError
import com.group33.shared.model.FieldError
import com.group33.shared.model.UserRole
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import org.springframework.web.bind.annotation.RestController

/** W01 transport checks only; not evidence of pairing authorization or persistence. Refs #12. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FeatureTransportContractReviewTest.PairingProbe::class)
class FeatureTransportContractReviewTest(
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val mvc: MockMvc,
) {
    private val json = ContractProtocol.json
    private val stringCode = """{"code":"00123456"}"""
    private val numericCodes = listOf("""{"code":12345678}""", """{"code":12345678.0}""")
    private val login = AnonymousLoginRequest("00000001", UserRole.CAREGIVER, "  照护者 001  ")
    private val medication = MedicationText("  药盒 001  ", "0.5 片", "")
    private val plan = ReminderPlanDraft(listOf("08:00", "20:00"), "2026-10-23", "Asia/Shanghai")
    private val error = ApiError("VALIDATION_FAILED", "  错误 001  ", false, "00000001",
        listOf(FieldError("medication.dosageText", "INVALID")))

    private inline fun <reified T> verifyStrings(value: T) {
        val payload = json.encodeToString(value)
        assertEquals(value, mapper.readValue<T>(payload))
        assertEquals(value, json.decodeFromString<T>(mapper.writeValueAsString(value)))
    }

    private inline fun <reified T> rejectNumericStringFields(value: T, vararg fields: String) {
        val source = json.parseToJsonElement(json.encodeToString(value)).jsonObject
        for (field in fields) {
            for (number in listOf(JsonPrimitive(12345678), JsonPrimitive(1.25))) {
                rejectBoth<T>(JsonObject(source + (field to number)).toString())
            }
        }
    }

    private inline fun <reified T> rejectBoth(payload: String) {
        assertThrows(SerializationException::class.java, {
            json.decodeFromString<T>(payload)
        }, payload)
        assertThrows(JsonProcessingException::class.java, {
            mapper.readValue<T>(payload)
        }, payload)
    }

    @Test fun `W01 Shared and Jackson preserve leading zeros in a string pairing code`() {
        val expected = RequestPairingRequest("00123456")
        assertEquals(expected, json.decodeFromString<RequestPairingRequest>(stringCode))
        assertEquals(expected, mapper.readValue<RequestPairingRequest>(stringCode))
    }

    @Test fun `W01 Shared rejects a numeric pairing code`() {
        for (payload in numericCodes) {
            assertThrows(SerializationException::class.java, {
                json.decodeFromString<RequestPairingRequest>(payload)
            }, payload)
        }
    }

    @Test fun `W01 Jackson rejects a numeric pairing code`() {
        for (payload in numericCodes) {
            assertThrows(JsonProcessingException::class.java, {
                mapper.readValue<RequestPairingRequest>(payload)
            }, payload)
        }
    }

    @Test fun `W01 legal login text and numeric-looking IDs keep their exact string values`() {
        verifyStrings(login)
        verifyStrings(login.copy(displayName = "00123456"))
    }

    @Test fun `W01 nested medication text nullable fields and time lists retain their wire meaning`() {
        val confirmation = ConfirmReminderPlanRequest("00000002", medication, plan,
            TimeZoneAcknowledgement("Asia/Shanghai", true))
        verifyStrings(confirmation)
        verifyStrings(confirmation.copy(medication = medication.copy(instructions = null)))
    }

    @Test fun `W01 structured errors preserve nested text and nullable reason`() {
        verifyStrings(error)
        verifyStrings(error.copy(reason = "00123456"))
    }

    @Test fun `W01 numeric values cannot become strings in login medication plan or error fields`() {
        rejectNumericStringFields(login, "installationId", "displayName")
        rejectNumericStringFields(medication, "displayName", "dosageText", "instructions")
        rejectNumericStringFields(plan, "startDate", "executionTimeZoneId", "endDate")
        rejectNumericStringFields(error, "code", "message", "requestId", "reason")
        rejectNumericStringFields(error.fieldErrors!!.single(), "field", "code")
    }

    @Test fun `W01 numeric list elements cannot become string times`() {
        val source = json.parseToJsonElement(json.encodeToString(plan)).jsonObject
        for (number in listOf(JsonPrimitive(800), JsonPrimitive(8.5))) {
            rejectBoth<ReminderPlanDraft>(JsonObject(source + ("localTimes" to JsonArray(listOf(number)))).toString())
        }
    }

    @Test fun `W01 MVC preserves the string pairing code including leading zeros`() {
        val response = mvc.perform(post("/__contract-probe/pairing-code")
            .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(stringCode))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("00123456"))
            .andReturn().response
        assertEquals(RequestPairingRequest("00123456"),
            json.decodeFromString<RequestPairingRequest>(response.contentAsString))
    }

    @Test fun `W01 MVC rejects a numeric pairing code with a correlated nonretryable error`() {
        for (payload in numericCodes) {
            val response = mvc.perform(post("/__contract-probe/pairing-code")
                .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn().response
            val error = json.decodeFromString<ApiError>(response.contentAsString)
            assertTrue(error.requestId.isNotBlank())
            assertEquals(error.requestId, response.getHeader("X-Request-ID"))
        }
    }

    /** Test-only echo: uses Boot's real HTTP conversion without implementing a pairing API. */
    @RestController
    class PairingProbe {
        @PostMapping("/__contract-probe/pairing-code")
        fun echo(@RequestBody request: RequestPairingRequest): RequestPairingRequest = request
    }
}
