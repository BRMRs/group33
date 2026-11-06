package com.group33.server.migration

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class MigrationSettingsTest {
    @TempDir lateinit var directory: Path
    private lateinit var password: Path
    private fun settings() = mapOf("GROUP33_MIGRATION_URL" to "jdbc:postgresql://127.0.0.1:55432/group33_s2_local",
        "GROUP33_MIGRATION_USER" to "fictional_migration_user", "GROUP33_MIGRATION_SCHEMA" to "group33_fixture",
        "GROUP33_MIGRATION_PASSWORD_FILE" to password.toString())

    @BeforeEach fun prepare() {
        password = directory.resolve("password")
        Files.writeString(password, "FICTIONAL_PRIVATE_MIGRATION_PASSWORD\n")
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-------"))
    }

    @Test fun `private configuration is redacted and never defaults to public`() {
        val value = MigrationSettings.from(settings())
        assertEquals("MigrationSettings(REDACTED)", value.toString())
        assertEquals("group33_fixture", value.schema)
        val source = assertInstanceOf(PGSimpleDataSource::class.java, value.source)
        assertEquals("group33_fixture", source.currentSchema)
        assertEquals("FICTIONAL_PRIVATE_MIGRATION_PASSWORD", source.password)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "public", "information_schema", "pg_catalog", "pg_private", "A", "a;DROP SCHEMA public", "a.b", "\"a\""])
    fun `reserved missing and unquoted unsafe schemas fail without private values`(schema: String) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MigrationSettings.from(settings() + ("GROUP33_MIGRATION_SCHEMA" to schema))
        }
        assertNull(failure.cause)
        assertEquals("Invalid private migration settings", failure.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["jdbc:postgresql:group33", "jdbc:postgresql://user:secret@host/db",
        "jdbc:postgresql://localhost/db?sslmode=disable", "jdbc:postgresql://localhost/db#secret",
        "jdbc:postgresql://localhost/", "jdbc:postgresql://localhost:0/db"])
    fun `ambiguous connection strings cannot override private settings or TLS`(url: String) {
        assertThrows(IllegalArgumentException::class.java) {
            MigrationSettings.from(settings() + ("GROUP33_MIGRATION_URL" to url))
        }
    }

    @Test fun `non loopback configuration requires a certificate and forces hostname verification`() {
        val remote = settings() + ("GROUP33_MIGRATION_URL" to "jdbc:postgresql://db.invalid/group33_fixture")
        assertThrows(IllegalArgumentException::class.java) { MigrationSettings.from(remote) }
        val cert = directory.resolve("root.crt").also { Files.writeString(it, "FICTIONAL_CERTIFICATE_NOT_USED_FOR_CONNECTION") }
        val source = MigrationSettings.from(remote + ("GROUP33_MIGRATION_SSL_ROOT_CERT_FILE" to cert.toString())).source as PGSimpleDataSource
        assertEquals("verify-full", source.sslMode)
        assertEquals(cert.toAbsolutePath().toString(), source.sslRootCert)
    }

    @Test fun `password files cannot be public symbolic missing empty or multiline`() {
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-r--r--"))
        assertThrows(IllegalArgumentException::class.java) { MigrationSettings.from(settings()) }
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-------"))
        listOf("", "\n", "secret\nsecond-line", "secret\u0000suffix").forEach { value ->
            Files.writeString(password, value)
            assertThrows(IllegalArgumentException::class.java) { MigrationSettings.from(settings()) }
        }
        Files.delete(password)
        assertThrows(IllegalArgumentException::class.java) { MigrationSettings.from(settings()) }
        val target = directory.resolve("target").also { Files.writeString(it, "FICTIONAL_SECRET") }
        Files.createSymbolicLink(password, target)
        assertThrows(IllegalArgumentException::class.java) { MigrationSettings.from(settings()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["clean", "repair", "baseline", "undo", "info", "--password=FICTIONAL_PRIVATE_VALUE"])
    fun `destructive and credential command arguments are never exposed`(arg: String) {
        val output = mutableListOf<String>()
        assertEquals(2, SchemaMigrationCli.run(arrayOf(arg), settings(), output::add, output::add))
        assertEquals(listOf("Usage: schema-migration migrate|validate"), output)
    }

    @Test fun `missing settings fail with nonzero status and no exception or input echoed`() {
        val output = mutableListOf<String>()
        assertEquals(1, SchemaMigrationCli.run(arrayOf("migrate"), emptyMap(), output::add, output::add))
        assertEquals(listOf("Schema migration failed; verify private settings and migration history"), output)
    }

    @Test fun `initial versioned SQL exactly preserves the inherited prototype constraints and triggers`() {
        val names = listOf("pairing-code", "pairing-request", "pairing-decision", "pairing-directory", "anonymous-identity",
            "medication-photo", "plan-confirmation", "plan-mutation", "occurrence-registration")
        names.forEachIndexed { index, name ->
            val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V${index + 1}__${name.replace('-', '_')}.sql")).use { it.readBytes() }
            val original = checkNotNull(javaClass.getResourceAsStream("/db/prototypes/$name-store-v${index + 1}.sql")).use { it.readBytes() }
            assertArrayEquals(original, migration, "Version ${index + 1} changed inherited semantics")
        }
    }
}
