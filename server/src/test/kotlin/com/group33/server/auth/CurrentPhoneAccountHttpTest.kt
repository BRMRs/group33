package com.group33.server.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.mockito.Mockito.*
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.time.Instant
import java.util.UUID
import org.hamcrest.Matchers.containsString

/** Actual signed bearer strings traverse decoder/security, not security-test jwt() injection. */
@SpringBootTest
@AutoConfigureMockMvc
class CurrentPhoneAccountHttpTest(@Autowired private val mvc: MockMvc, @Autowired private val encoder: JwtEncoder) {
    @MockitoBean lateinit var accounts: PhoneAccountStore
    private val id = UUID.randomUUID().toString()
    private fun token(role: String = "CAREGIVER", issuer: String = "group33-server", expired: Boolean = false, type: String = "phone"): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder().issuer(issuer).subject(id).claim("type", type).claim("role", role)
            .issuedAt(now.minusSeconds(300)).expiresAt(if (expired) now.minusSeconds(120) else now.plusSeconds(300)).build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }
    private fun rejected(bearer: String) {
        val response = mvc.perform(get("/api/v1/schedules").header("Authorization", "Bearer $bearer"))
            .andExpect(status().isUnauthorized).andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.retryable").value(false)).andReturn().response
        assertFalse(response.contentAsString.contains(id))
        assertFalse(response.contentAsString.contains("PRIVATE"))
    }
    @BeforeEach fun current() { `when`(accounts.findByUserId(id)).thenReturn(User(id, "Fictional", UserRole.CAREGIVER)) }

    @Test fun `valid current phone account passes existing legacy read without provider call`() {
        mvc.perform(get("/api/v1/schedules").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(content().json("[]"))
        verify(accounts).findByUserId(id)
    }
    @Test fun `removed account invalidates later use of the same still signed token`() {
        val bearer = token()
        mvc.perform(get("/api/v1/schedules").header("Authorization", "Bearer $bearer")).andExpect(status().isOk)
        `when`(accounts.findByUserId(id)).thenReturn(null)
        rejected(bearer)
    }
    @Test fun `different current role refuses a signed claim rather than trusting it`() { rejected(token(role = "ELDER")) }
    @Test fun `temporary database failure is retryable service failure and preserves the same valid session`() {
        `when`(accounts.findByUserId(id)).thenThrow(IllegalStateException("PRIVATE_CONFIGURATION_FIXTURE"))
        val bearer = token()
        val response = mvc.perform(get("/api/v1/schedules").header("Authorization", "Bearer $bearer"))
            .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.retryable").value(true)).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("WWW-Authenticate")).andReturn().response
        assertFalse(response.contentAsString.contains("PRIVATE")); assertFalse(response.contentAsString.contains(id))
        doReturn(User(id, "Fictional", UserRole.CAREGIVER)).`when`(accounts).findByUserId(id)
        mvc.perform(get("/api/v1/schedules").header("Authorization", "Bearer $bearer")).andExpect(status().isOk)
    }
    @Test fun `expired and wrong issuer tokens never reach the account database`() {
        clearInvocations(accounts)
        rejected(token(expired = true)); rejected(token(issuer = "wrong-fixture-issuer"))
        verifyNoInteractions(accounts)
    }
    @Test fun `invalid signature never reaches the account database`() {
        clearInvocations(accounts)
        val parts = token().split('.'); val signature = parts[2]
        rejected(parts[0] + "." + parts[1] + "." + (if (signature[0] == 'A') 'B' else 'A') + signature.drop(1))
        verifyNoInteractions(accounts)
    }
    @Test fun `anonymous bearer keeps existing behavior without phone account reads`() {
        clearInvocations(accounts)
        mvc.perform(get("/api/v1/schedules").header("Authorization", "Bearer ${token(type = "anonymous")}"))
            .andExpect(status().isOk)
        verifyNoInteractions(accounts)
    }
}
