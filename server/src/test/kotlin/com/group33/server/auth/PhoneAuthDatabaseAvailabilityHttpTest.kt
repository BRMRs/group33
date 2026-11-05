package com.group33.server.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/** Real store/HTTP path; loopback port1 deliberately has no database or supplier. */
@SpringBootTest(properties = [
    "app.fusion.enabled=true", "app.fusion.scheme-code=fictional-scheme",
    "app.fusion.database-url=jdbc:postgresql://127.0.0.1:1/PRIVATE_DATABASE_UNAVAILABLE_FIXTURE",
    "app.fusion.database-user=fictional", "app.fusion.database-password=FICTIONAL_PRIVATE_PASSWORD",
    "app.fusion.phone-hash-key=FICTIONAL_PHONE_HASH_KEY_LONGER_THAN_32_BYTES",
    "app.jwt.secret=FICTIONAL_PHONE_SIGNING_KEY_LONGER_THAN_32_BYTES",
])
@AutoConfigureMockMvc
class PhoneAuthDatabaseAvailabilityHttpTest(@Autowired private val mvc: MockMvc, @Autowired private val json: ObjectMapper) {
    @MockitoBean lateinit var provider: FusionAuthProvider
    @MockitoBean lateinit var jwt: JwtService

    private fun unavailable(path: String, body: String) {
        val response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.retryable").value(true))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andReturn().response
        assertEquals(json.readTree(response.contentAsString)["requestId"].asText(), response.getHeader("X-Request-ID"))
        for (secret in listOf("PRIVATE", "127.0.0.1", "jdbc:", "13800000077", "fictional-proof", "A".repeat(43))) {
            assertFalse(response.contentAsString.contains(secret))
        }
        verifyNoInteractions(jwt)
    }

    @Test fun `verified login database outage is retryable unavailable without invalidating another session`() {
        `when`(provider.verify("fictional-proof")).thenReturn("13800000077")
        unavailable("/api/v1/auth/fusion/verify", """{"verifyToken":"fictional-proof"}""")
        verify(provider).verify("fictional-proof")
    }

    @Test fun `registration database outage is unavailable rather than invalid ticket or signed in`() {
        unavailable("/api/v1/auth/phone/register",
            """{"registrationTicket":"${"A".repeat(43)}","role":"CAREGIVER","displayName":"Fictional"}""")
        verifyNoInteractions(provider)
    }
}
