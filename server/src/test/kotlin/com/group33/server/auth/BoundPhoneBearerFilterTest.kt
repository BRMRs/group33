package com.group33.server.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.config.ApiSecurityHandlers
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

internal class BoundPhoneBearerFilterTest {
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000), ZoneOffset.UTC)
    private val jwt = BoundPhoneJwt(ByteArray(32) { 1 }, "https://fixture.invalid", "fixture-phone", clock)
    private val claims = BoundPhoneSessionClaims(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UserRole.CAREGIVER, "fixture-phone", clock.instant().epochSecond, clock.instant().epochSecond + 600)
    private val mapper = ObjectMapper()
    private fun filter() = BoundPhoneBearerFilter(jwt, ApiSecurityHandlers(mapper))

    @Test fun `valid profile enters business chain with no token credentials and context is cleared`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "bEaReR  ${jwt.issue(claims)}") }
        var called = false
        filter().doFilter(request, MockHttpServletResponse()) { _, _ ->
            called = true
            val authentication = SecurityContextHolder.getContext().authentication
            assertTrue(authentication.isAuthenticated)
            assertNull(authentication.credentials)
            val actual = assertInstanceOf(BoundPhoneSessionClaims::class.java, authentication.principal)
            assertEquals(claims.sessionId, actual.sessionId)
            assertFalse(authentication.toString().contains(claims.userId.toString()))
        }
        assertTrue(called); assertNull(SecurityContextHolder.getContext().authentication)
    }

    @ParameterizedTest @ValueSource(strings = ["absent", "duplicate", "basic", "blank", "space", "oversize", "signature", "profile"])
    fun `bad bearer cannot reach body binding or business work`(kind: String) {
        val request = MockHttpServletRequest().apply { setContent("malformed confidential body".toByteArray()) }
        when (kind) {
            "absent" -> Unit
            "duplicate" -> { request.addHeader("Authorization", "Bearer ${jwt.issue(claims)}"); request.addHeader("Authorization", "Bearer ${jwt.issue(claims)}") }
            "basic" -> request.addHeader("Authorization", "Basic invalid")
            "blank" -> request.addHeader("Authorization", "Bearer ")
            "space" -> request.addHeader("Authorization", "Bearer a b")
            "oversize" -> request.addHeader("Authorization", "Bearer " + "a".repeat(4097))
            "signature" -> request.addHeader("Authorization", "Bearer " + BoundPhoneJwt(ByteArray(32) { 2 }, "https://fixture.invalid", "fixture-phone", clock).issue(claims))
            "profile" -> request.addHeader("Authorization", "Bearer " + BoundPhoneJwt(ByteArray(32) { 1 }, "https://wrong.invalid", "fixture-phone", clock).issue(claims))
        }
        val response = MockHttpServletResponse()
        filter().doFilter(request, response) { _, _ -> fail("Invalid credentials reached the business/body chain") }
        assertEquals(401, response.status)
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val error = mapper.readTree(response.contentAsString)
        assertEquals("UNAUTHENTICATED", error["code"].asText())
        assertEquals(error["requestId"].asText(), response.getHeader("X-Request-ID"))
        assertFalse(response.contentAsString.contains("confidential"))
        assertNull(SecurityContextHolder.getContext().authentication)
    }
}
