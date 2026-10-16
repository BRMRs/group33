package com.group33.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.UserRole
import com.group33.shared.model.MedicationSchedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.blankOrNullString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class ApplicationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jwtEncoder: JwtEncoder,
    @Autowired private val scheduleStore: ScheduleStore,
) {
    @Test
    fun `health endpoint is public`() {
        mockMvc.get("/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ok") }
            }
    }

    @Test
    fun `schedule endpoint requires a JWT`() {
        mockMvc.get("/api/v1/schedules")
            .andExpect {
                status { isUnauthorized() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                jsonPath("$.code") { value("UNAUTHENTICATED") }
                jsonPath("$.message") { value(not(blankOrNullString())) }
                jsonPath("$.requestId") { value(not(blankOrNullString())) }
                header { string("WWW-Authenticate", "Bearer") }
            }
    }

    @Test
    fun `anonymous login returns a JWT and user`() {
        val request = AnonymousLoginRequest(
            installationId = "test-installation-001",
            role = UserRole.CAREGIVER,
            displayName = "Test Caregiver",
        )

        mockMvc.post("/api/v1/auth/anonymous") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.accessToken") { value(not(blankOrNullString())) }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.user.role") { value("CAREGIVER") }
        }
    }

    @Test
    fun `JWT grants access to the caller's schedules`() {
        val request = AnonymousLoginRequest(
            installationId = "test-installation-002",
            role = UserRole.ELDER,
            displayName = "Test Elder",
        )
        val loginResponse = mockMvc.post("/api/v1/auth/anonymous") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andReturn().response.contentAsString
        val accessToken = objectMapper.readTree(loginResponse)["accessToken"].asText()

        mockMvc.get("/api/v1/schedules") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            content { json("[]") }
        }
    }

    @Test
    fun `invalid login fields return structured 422 instead of empty 401`() {
        val response = mockMvc.post("/api/v1/auth/anonymous") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"installationId":"x","role":"ELDER","displayName":""}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.retryable") { value(false) }
            jsonPath("$.fieldErrors.length()") { value(2) }
            jsonPath("$.fieldErrors[0].field") { value("installationId") }
            jsonPath("$.fieldErrors[1].field") { value("displayName") }
        }.andReturn().response
        val error = objectMapper.readValue(response.contentAsString, com.group33.shared.model.ApiError::class.java)
        assertNotNull(error.requestId)
        assertEquals(error.requestId, response.getHeader("X-Request-ID"))
    }

    @Test
    fun `malformed JSON and missing required fields have safe 400 errors`() {
        listOf("{", "{}", """{"installationId":"example-installation","role":"DOCTOR","displayName":"Demo"}""").forEach { invalid ->
            mockMvc.post("/api/v1/auth/anonymous") {
                contentType = MediaType.APPLICATION_JSON
                content = invalid
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("MALFORMED_REQUEST") }
                jsonPath("$.message") { value("Request body is malformed") }
                jsonPath("$.retryable") { value(false) }
            }
        }
    }

    @Test
    fun `wrong content type is a structured client error`() {
        mockMvc.post("/api/v1/auth/anonymous") {
            contentType = MediaType.TEXT_PLAIN
            content = "unsupported request"
        }.andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.code") { value("UNSUPPORTED_MEDIA_TYPE") }
            jsonPath("$.retryable") { value(false) }
        }
    }

    @Test
    fun `installation identity is stable and role changes return structured conflict`() {
        val installationId = UUID.randomUUID().toString()
        val first = login(UserRole.ELDER, installationId)
        val repeated = login(UserRole.ELDER, installationId)
        assertEquals(first.userId, repeated.userId)
        mockMvc.post("/api/v1/auth/anonymous") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(AnonymousLoginRequest(installationId, UserRole.CAREGIVER, "Demo"))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INSTALLATION_ROLE_CONFLICT") }
            jsonPath("$.fieldErrors[0].code") { value("ROLE_FIXED") }
        }
    }

    @Test
    fun `invalid and expired Bearer tokens use the same JSON envelope`() {
        val now = Instant.now()
        val expiredClaims = JwtClaimsSet.builder()
            .issuer("group33-server").subject("expired-user").claim("role", "ELDER")
            .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600)).build()
        val expired = jwtEncoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), expiredClaims,
        )).tokenValue
        listOf("invalid.token.value", expired).forEach { token ->
            mockMvc.get("/api/v1/schedules") {
                header("Authorization", "Bearer $token")
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHENTICATED") }
                jsonPath("$.retryable") { value(false) }
                jsonPath("$.requestId") { value(not(blankOrNullString())) }
            }
        }
    }

    @Test
    fun `unpaired caregiver cannot create plans for any elder and write has no effect`() {
        val caregiver = login(UserRole.CAREGIVER)
        val elder = login(UserRole.ELDER)
        listOf(elder.userId, "nonexistent-elder").forEach { elderId ->
            val plan = MedicationSchedule(
                UUID.randomUUID().toString(), "medication-1", elderId, caregiver.userId,
                listOf("08:00"), "2026-10-20", updatedAtEpochMillis = 1L,
            )
            mockMvc.post("/api/v1/schedules") {
                header("Authorization", "Bearer ${caregiver.token}")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(plan)
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("RESOURCE_NOT_ACCESSIBLE") }
                jsonPath("$.message") { value("Resource is not accessible") }
            }
            assertEquals(false, scheduleStore.all().any { it.id == plan.id })
        }
    }

    @Test
    fun `list protects elder self access and does not trust caregiverId as authorization`() {
        val elder = login(UserRole.ELDER)
        val otherElder = login(UserRole.ELDER)
        val caregiver = login(UserRole.CAREGIVER)
        val own = MedicationSchedule(
            UUID.randomUUID().toString(), "medication-1", elder.userId, caregiver.userId,
            listOf("08:00"), "2026-10-20", updatedAtEpochMillis = 1L,
        )
        scheduleStore.save(own)
        mockMvc.get("/api/v1/schedules") {
            header("Authorization", "Bearer ${elder.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(own.id) }
        }
        listOf(caregiver, otherElder).forEach { caller ->
            mockMvc.get("/api/v1/schedules") {
                header("Authorization", "Bearer ${caller.token}")
            }.andExpect {
                status { isOk() }
                content { json("[]") }
            }
        }
    }

    @Test
    fun `elder write and unknown resource have structured errors`() {
        val elder = login(UserRole.ELDER)
        val plan = MedicationSchedule(
            "plan-elder-write", "medication-1", elder.userId, "caregiver-1",
            listOf("08:00"), "2026-10-20", updatedAtEpochMillis = 1L,
        )
        mockMvc.post("/api/v1/schedules") {
            header("Authorization", "Bearer ${elder.token}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(plan)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ROLE_NOT_ALLOWED") }
        }
        mockMvc.get("/api/v1/not-a-resource") {
            header("Authorization", "Bearer ${elder.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_ACCESSIBLE") }
        }
    }

    private data class Session(val token: String, val userId: String)

    private fun login(role: UserRole, installationId: String = UUID.randomUUID().toString()): Session {
        val response = mockMvc.post("/api/v1/auth/anonymous") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(AnonymousLoginRequest(installationId, role, "Demo"))
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val json = objectMapper.readTree(response)
        return Session(json["accessToken"].asText(), json["user"]["id"].asText())
    }
}
