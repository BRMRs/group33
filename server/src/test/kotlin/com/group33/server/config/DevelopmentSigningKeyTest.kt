package com.group33.server.config

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DevelopmentSigningKeyTest {
    @Test
    fun `local placeholder produces fresh private signing material for each server context`() {
        val placeholder = "GROUP33_LOCAL_DEVELOPMENT_SECRET_CHANGE_BEFORE_DEPLOYMENT_2026"
        val first = SecurityConfig().jwtSecretKey(placeholder).encoded
        val second = SecurityConfig().jwtSecretKey(placeholder).encoded
        assertEquals(32, first.size)
        assertFalse(first.contentEquals(placeholder.toByteArray(StandardCharsets.UTF_8)))
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `explicit private configuration retains stable signing material`() {
        val fixture = "FICTIONAL_TEST_ONLY_EXPLICIT_SIGNING_CONFIGURATION"
        assertArrayEquals(fixture.toByteArray(StandardCharsets.UTF_8), SecurityConfig().jwtSecretKey(fixture).encoded)
    }

    @Test
    fun `short explicit configuration is still rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SecurityConfig().jwtSecretKey("short") }
    }
}
