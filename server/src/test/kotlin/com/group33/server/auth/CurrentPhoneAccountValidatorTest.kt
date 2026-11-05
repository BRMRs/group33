package com.group33.server.auth

import com.group33.server.config.SecurityConfig
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import java.util.UUID

class CurrentPhoneAccountValidatorTest {
    private val id = UUID.randomUUID().toString()
    private fun token(subject: String = id, role: String = "CAREGIVER", type: String = "phone") = Jwt.withTokenValue("fixture")
        .header("alg", "HS256").subject(subject).claim("role", role).claim("type", type)
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()

    @Test fun `current stored identity and fixed role are both required`() {
        val current = User(id, "Fictional", UserRole.CAREGIVER)
        assertFalse(CurrentPhoneAccountValidator { current }.validate(token()).hasErrors())
        assertTrue(CurrentPhoneAccountValidator { current }.validate(token(role = "ELDER")).hasErrors())
        assertTrue(CurrentPhoneAccountValidator { current.copy(id = UUID.randomUUID().toString()) }.validate(token()).hasErrors())
    }

    @Test fun `missing current account produces generic invalid token`() {
        val result = CurrentPhoneAccountValidator { null }.validate(token())
        assertTrue(result.hasErrors())
        assertEquals("invalid_token", result.errors.single().errorCode)
    }

    @Test fun `temporary account failure remains distinguishable without exposing private cause`() {
        val failure = assertThrows(PhoneAccountReadUnavailable::class.java) {
            CurrentPhoneAccountValidator { error("PRIVATE_DATABASE_FIXTURE") }.validate(token())
        }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("PRIVATE"))
    }

    @ParameterizedTest @ValueSource(strings = ["", "not-an-id", "1-1-1-1-1", "' OR 1=1 --", "00000000-0000-4000-8000-00000000000Z"])
    fun `invalid subject is rejected before account lookup`(subject: String) {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val result = CurrentPhoneAccountValidator { calls.incrementAndGet(); null }.validate(token(subject))
        assertTrue(result.hasErrors()); assertEquals(0, calls.get())
        assertNull(PhoneAccountStore("", "", "", "").findByUserId(subject))
    }

    @Test fun `unsupported phone role is rejected before account lookup`() {
        var called = false
        assertTrue(CurrentPhoneAccountValidator { called = true; null }.validate(token(role = "ADMIN")).hasErrors())
        assertFalse(called)
    }

    @Test fun `anonymous path does not acquire phone account configuration`() {
        var called = false
        assertFalse(CurrentPhoneAccountValidator { called = true; null }.validate(token(type = "anonymous")).hasErrors())
        assertFalse(called)
    }

    @Test fun `retain PR81 private default signing while explicit private signing stays stable`() {
        val config = SecurityConfig()
        val sentinel = "GROUP33_LOCAL_DEVELOPMENT_SECRET_CHANGE_BEFORE_DEPLOYMENT_2026"
        assertFalse(config.jwtSecretKey(sentinel).encoded.contentEquals(config.jwtSecretKey(sentinel).encoded))
        assertFalse(config.jwtSecretKey(sentinel).encoded.contentEquals(sentinel.toByteArray()))
        val privateKey = "FICTIONAL_EXPLICIT_PHONE_VALIDATION_SIGNING_KEY_2026"
        assertArrayEquals(config.jwtSecretKey(privateKey).encoded, config.jwtSecretKey(privateKey).encoded)
    }
}
