package com.group33.server.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = [
    "app.fusion.enabled=true",
    "app.fusion.scheme-code=test-scheme",
    "app.fusion.database-url=jdbc:h2:mem:phone-http-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "app.fusion.database-user=sa",
    "app.fusion.database-password=",
    "app.fusion.phone-hash-key=test-phone-hash-key-with-over-32-bytes",
    "app.jwt.secret=test-jwt-secret-with-at-least-32-bytes",
])
@AutoConfigureMockMvc
class PhoneAuthHttpTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var json: ObjectMapper
    @MockitoBean private lateinit var provider: FusionAuthProvider

    @Test
    fun `verified first login registers role and later login restores account`() {
        `when`(provider.verify("first-proof")).thenReturn("13800000001")
        `when`(provider.verify("second-proof")).thenReturn("13800000001")
        val first = mvc.perform(
            post("/api/v1/auth/fusion/verify").contentType(MediaType.APPLICATION_JSON)
                .content("""{"verifyToken":"first-proof"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val ticket = json.readTree(first).get("registrationTicket").asText()
        val register = """{"registrationTicket":"$ticket","role":"CAREGIVER","displayName":"Demo"}"""
        val registered = mvc.perform(
            post("/api/v1/auth/phone/register").contentType(MediaType.APPLICATION_JSON).content(register),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val session = json.readTree(registered)
        assertEquals("CAREGIVER", session["user"]["role"].asText())
        mvc.perform(get("/api/v1/schedules").header("Authorization", "Bearer ${session["accessToken"].asText()}"))
            .andExpect(status().isOk)
        mvc.perform(post("/api/v1/auth/phone/register").contentType(MediaType.APPLICATION_JSON).content(register))
            .andExpect(status().isUnprocessableEntity)
        val later = mvc.perform(
            post("/api/v1/auth/fusion/verify").contentType(MediaType.APPLICATION_JSON)
                .content("""{"verifyToken":"second-proof"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(session["user"]["id"].asText(), json.readTree(later)["user"]["id"].asText())
        assertEquals("CAREGIVER", json.readTree(later)["user"]["role"].asText())
    }
}
