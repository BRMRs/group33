package com.group33.server.config

import com.group33.server.auth.InMemoryAnonymousIdentityStore
import com.group33.server.pairing.InMemoryPairingCodeStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal class PersistenceRuntimeConfigurationTest {
    @Test fun `default mode uses memory and unknown mode never falls back`() {
        val runtime = PersistenceRuntimeConfiguration().create(MockEnvironment())
        assertInstanceOf(InMemoryAnonymousIdentityStore::class.java, runtime.identities)
        assertInstanceOf(InMemoryPairingCodeStore::class.java, runtime.pairing)
        assertThrows(IllegalArgumentException::class.java) { PersistenceRuntimeConfiguration().create(MockEnvironment().withProperty("app.persistence.mode", "postgrez")) }
    }
    @ParameterizedTest @ValueSource(strings = ["remote", "userinfo", "query", "schema", "default-jwt", "short-key", "public-key", "missing-key", "missing-password"])
    fun `invalid private configuration is rejected before connection without leaking values`(kind: String) {
        val directory = Files.createTempDirectory("s2-runtime-settings-"); val file = directory.resolve("receipt.key")
        Files.write(file, ByteArray(32) { 7 }); Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        val marker = "PRIVATE_TEST_CONFIG_SHOULD_NEVER_APPEAR"
        val env = MockEnvironment().withProperty("app.persistence.mode", "postgres-local")
            .withProperty("app.persistence.jdbc-url", "jdbc:postgresql://127.0.0.1:1/fixture")
            .withProperty("app.persistence.username", marker).withProperty("app.persistence.password", marker)
            .withProperty("app.persistence.schema", "fixture").withProperty("app.persistence.receipt-key-file", file.toString())
            .withProperty("app.jwt.secret", "FICTIONAL_TEST_ONLY_SIGNING_CONFIGURATION_2026")
        when (kind) {
            "remote" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://example.invalid/fixture")
            "userinfo" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://$marker@127.0.0.1/fixture")
            "query" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://127.0.0.1/fixture?password=$marker")
            "schema" -> env.setProperty("app.persistence.schema", "bad; schema")
            "default-jwt" -> env.setProperty("app.jwt.secret", "GROUP33_LOCAL_DEVELOPMENT_SECRET_CHANGE_BEFORE_DEPLOYMENT_2026")
            "short-key" -> Files.write(file, ByteArray(31))
            "public-key" -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
            "missing-key" -> Files.delete(file)
            "missing-password" -> env.setProperty("app.persistence.password", "")
        }
        try {
            val failure = assertThrows(IllegalStateException::class.java) { PersistenceRuntimeConfiguration().create(env) }
            assertFalse(failure.toString().contains(marker)); assertNull(failure.cause)
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
}
