package com.group33.server.auth

import com.group33.shared.model.UserRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PhoneAccountAvailabilityTest {
    private fun store(url: String) = PhoneAccountStore(url, "fictional", "PRIVATE_FIXTURE_PASSWORD",
        "FICTIONAL_PHONE_HASH_KEY_LONGER_THAN_32_BYTES")

    @Test fun `unsupported database configuration returns a sanitized temporary failure`() {
        val failure = assertThrows(RuntimeException::class.java) {
            store("jdbc:PRIVATE_UNSUPPORTED_FIXTURE").findByPhone("13800000077")
        }
        assertEquals("Phone account storage is temporarily unavailable", failure.message)
        assertNull(failure.cause)
    }

    @Test fun `malformed registration still fails validation before database access`() {
        assertNull(store("").register("short", UserRole.CAREGIVER, "Fictional"))
        assertNull(store("").register("A".repeat(43), UserRole.CAREGIVER, " "))
    }
}
