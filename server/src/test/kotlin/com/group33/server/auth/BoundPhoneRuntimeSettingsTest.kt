package com.group33.server.auth

import com.group33.server.config.PersistenceRuntime
import com.group33.server.config.PersistenceRuntimeConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment

internal class BoundPhoneRuntimeSettingsTest {
    @TempDir lateinit var directory: Path

    private fun configuration(): MutableMap<String, String> {
        for ((name, value) in listOf("jwt" to 1, "phone" to 2, "receipt" to 3)) {
            val file = directory.resolve(name)
            Files.write(file, ByteArray(32) { value.toByte() })
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
        return mutableMapOf("app.bound-phone.enabled" to "true", "app.persistence.mode" to "postgres-local",
            "app.persistence.require-versioned-schema" to "true", "app.jwt.secret" to "FICTIONAL_LEGACY_SIGNING_KEY_FOR_TESTS_2026",
            "app.persistence.receipt-key-file" to directory.resolve("receipt").toString(),
            "app.bound-phone.jwt-key-file" to directory.resolve("jwt").toString(),
            "app.bound-phone.phone-hash-key-file" to directory.resolve("phone").toString(),
            "app.bound-phone.jwt-issuer" to "https://phone.fixture.invalid",
            "app.bound-phone.jwt-audience" to "group33-phone-fixture",
            "app.bound-phone.access-key-id" to "FICTIONAL_KEY_ID", "app.bound-phone.access-key-secret" to "FICTIONAL_KEY_SECRET",
            "app.bound-phone.scheme-code" to "fictional-scheme", "app.bound-phone.android-package" to "com.group33.medicationreminder",
            "app.bound-phone.android-signature" to "ab".repeat(16), "app.bound-phone.trusted-proxy-cidrs" to "192.0.2.0/24")
    }

    private fun environment(values: Map<String, String>) = MockEnvironment().apply {
        values.forEach { (key, value) -> setProperty(key, value) }
    }

    private fun rejected(values: Map<String, String>) {
        val failure = assertThrows(IllegalStateException::class.java) { BoundPhoneRuntimeSettings.read(environment(values)) }
        assertEquals("Bound phone private runtime configuration is invalid", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("FICTIONAL"))
        assertFalse(failure.toString().contains(directory.toString()))
    }

    @Test fun `explicit complete private configuration binds one redacted snapshot`() {
        val settings = BoundPhoneRuntimeSettings.read(environment(configuration()))
        assertEquals("group33-phone-fixture", settings.audience)
        assertEquals(86400, settings.sessionSeconds)
        assertEquals(60, settings.allowanceSeconds)
        assertArrayEquals(ByteArray(32) { 1 }, settings.jwtKey)
        assertArrayEquals(ByteArray(32) { 2 }, settings.phoneHashKey)
        assertEquals("BoundPhoneRuntimeSettings(redacted)", settings.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["app.bound-phone.jwt-key-file", "app.bound-phone.phone-hash-key-file",
        "app.persistence.receipt-key-file", "app.bound-phone.jwt-issuer", "app.bound-phone.jwt-audience",
        "app.bound-phone.access-key-id", "app.bound-phone.access-key-secret", "app.bound-phone.scheme-code",
        "app.bound-phone.android-package", "app.bound-phone.android-signature", "app.bound-phone.trusted-proxy-cidrs"])
    fun `missing private input cannot fall back to legacy or supplier credential discovery`(key: String) {
        val values = configuration(); values.remove(key); rejected(values)
    }

    @ParameterizedTest @ValueSource(strings = ["memory", "unversioned", "fusion", "forwarding", "issuer", "audience",
        "package", "signature", "quota", "clients", "duration", "allowance", "proxy"])
    fun `invalid mode profile provider and transport settings fail without private diagnostics`(kind: String) {
        val values = configuration()
        val change = when (kind) {
            "memory" -> "app.persistence.mode" to "memory"
            "unversioned" -> "app.persistence.require-versioned-schema" to "false"
            "fusion" -> "app.fusion.enabled" to "true"
            "forwarding" -> "server.forward-headers-strategy" to "framework"
            "issuer" -> "app.bound-phone.jwt-issuer" to "http://phone.fixture.invalid"
            "audience" -> "app.bound-phone.jwt-audience" to "profile with spaces"
            "package" -> "app.bound-phone.android-package" to "wrong.fixture.package"
            "signature" -> "app.bound-phone.android-signature" to "not-an-md5"
            "quota" -> "app.bound-phone.requests-per-minute" to "121"
            "clients" -> "app.bound-phone.max-tracked-clients" to "10001"
            "duration" -> "app.bound-phone.session-seconds" to "86401"
            "allowance" -> "app.bound-phone.clock-allowance-seconds" to "61"
            else -> "app.bound-phone.trusted-proxy-cidrs" to "0.0.0.0/0"
        }
        values[change.first] = change.second; rejected(values)
    }

    @ParameterizedTest @ValueSource(strings = ["jwt", "phone", "receipt", "length", "permission", "symlink"])
    fun `key reuse malformed files and unsafe permissions are rejected`(kind: String) {
        val values = configuration()
        when (kind) {
            "jwt", "phone", "receipt" -> Files.write(directory.resolve(kind), ByteArray(32) { 2 })
            "length" -> Files.write(directory.resolve("jwt"), ByteArray(31))
            "permission" -> Files.setPosixFilePermissions(directory.resolve("jwt"), PosixFilePermissions.fromString("rw-r--r--"))
            "symlink" -> { Files.delete(directory.resolve("jwt")); Files.createSymbolicLink(directory.resolve("jwt"), directory.resolve("phone")) }
        }
        // For the phone-key case duplicate the receipt key instead of retaining the original key.
        if (kind == "phone") Files.write(directory.resolve("phone"), ByteArray(32) { 3 })
        rejected(values)
    }

    @Test fun `disabled mode creates no phone beans and reads no private phone files`() {
        ApplicationContextRunner().withUserConfiguration(BoundPhoneRuntimeConfiguration::class.java,
            PersistenceRuntimeConfiguration::class.java).run { context ->
            assertNull(context.startupFailure)
            assertEquals(0, context.getBeansOfType(BoundPhoneAuthentication::class.java).size)
            assertNull(context.getBean(PersistenceRuntime::class.java).primary)
        }
    }

    @Test fun `invalid enabled settings fail in actual Bean dependency resolution before persistence`() {
        val values = configuration().apply {
            remove("app.bound-phone.jwt-key-file")
            put("app.persistence.jdbc-url", "jdbc:postgresql://127.0.0.1:1/fixture")
        }
        ApplicationContextRunner().withUserConfiguration(BoundPhoneRuntimeConfiguration::class.java,
            PersistenceRuntimeConfiguration::class.java).withPropertyValues(*values.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                val failure = checkNotNull(context.startupFailure)
                val messages = generateSequence(failure) { it.cause }.map { it.message.orEmpty() }.joinToString()
                assertTrue(messages.contains("Bound phone private runtime configuration is invalid"))
                assertFalse(messages.contains("PostgreSQL persistence initialization failed"))
                assertFalse(messages.contains("FICTIONAL"))
            }
    }

    @Test fun `unknown enable flag cannot silently select the disabled mode`() {
        ApplicationContextRunner().withUserConfiguration(BoundPhoneRuntimeConfiguration::class.java,
            PersistenceRuntimeConfiguration::class.java).withPropertyValues("app.bound-phone.enabled=yes")
            .run { context -> assertNotNull(context.startupFailure) }
    }
}
