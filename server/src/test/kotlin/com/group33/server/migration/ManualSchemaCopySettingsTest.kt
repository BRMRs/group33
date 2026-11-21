package com.group33.server.migration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ManualSchemaCopySettingsTest {
    @TempDir lateinit var directory: Path
    private val reference get() = ManualSchemaCopy.Reference("group33_fixture", "a".repeat(64), "none")

    @ParameterizedTest
    @ValueSource(strings = ["jdbc:postgresql://db.fictional.supabase.co:5432/postgres",
        "jdbc:postgresql://localhost:55432/test", "jdbc:postgresql://127.0.0.1/test?host=db.fictional.invalid",
        "jdbc:postgresql://user:password@127.0.0.1/test", "jdbc:postgresql://127.0.0.1/%74est"])
    fun remoteAmbiguousOrOverriddenConnectionsAreRejected(url: String) {
        assertThrows(IllegalArgumentException::class.java) { ManualSchemaCopy.requireLoopback(url, true) }
    }

    @Test fun privateReferencesRoundTripAndCannotOverwriteAnExistingReview() {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val file = directory.resolve("source.properties")
        ManualSchemaCopy.writeReference(file.toString(), reference)
        assertEquals(reference, ManualSchemaCopy.readReference(file.toString()))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
        assertThrows(Exception::class.java) { ManualSchemaCopy.writeReference(file.toString(), reference) }
        assertEquals(reference, ManualSchemaCopy.readReference(file.toString()))
        assertEquals("Reference(REDACTED)", reference.toString())
    }

    @Test fun unsafeAndSymlinkedReferencesCannotBeRead() {
        val file = directory.resolve("source.properties")
        ManualSchemaCopy.writeReference(file.toString(), reference)
        val link = directory.resolve("link.properties")
        Files.createSymbolicLink(link, file)
        assertThrows(Exception::class.java) { ManualSchemaCopy.readReference(link.toString()) }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        assertThrows(Exception::class.java) { ManualSchemaCopy.readReference(file.toString()) }
    }

    @Test fun privateReferenceRequiresExactFormatAndKnownFields() {
        val file = directory.resolve("source.properties")
        ManualSchemaCopy.writeReference(file.toString(), reference)
        Files.writeString(file, Files.readString(file) + "\nunreviewed=true\n")
        assertThrows(Exception::class.java) { ManualSchemaCopy.readReference(file.toString()) }
        assertThrows(Exception::class.java) { ManualSchemaCopy.Reference("public", "a".repeat(64), "none") }
        assertThrows(Exception::class.java) { ManualSchemaCopy.Reference("group33_fixture", "not-a-hash", "none") }
    }

    @Test fun duplicateReferenceFieldsAndRemoteLibraryCallsFailBeforeConnection() {
        val file = directory.resolve("source.properties")
        ManualSchemaCopy.writeReference(file.toString(), reference)
        Files.writeString(file, Files.readString(file) + "\ncatalog=" + "b".repeat(64) + "\n")
        assertThrows(Exception::class.java) { ManualSchemaCopy.readReference(file.toString()) }
        val remote = org.postgresql.ds.PGSimpleDataSource().apply { setURL("jdbc:postgresql://cloud.invalid/postgres") }
        assertThrows(IllegalArgumentException::class.java) { ManualSchemaCopy.inspect(remote, "group33_fixture", false) }
        val target = ManualSchemaCopy.Reference("group33_target", "b".repeat(64), "9:" + "c".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { ManualSchemaCopy.copy(remote, reference, target) }
    }

    @Test fun helpAndRemoteFailureNeverPrintPrivateSettings() {
        val out = mutableListOf<String>(); val errors = mutableListOf<String>()
        assertEquals(2, ManualSchemaCopy.run(emptyArray(), emptyMap(), out::add, errors::add))
        assertEquals(1, ManualSchemaCopy.run(arrayOf("copy"), mapOf("GROUP33_MIGRATION_URL" to
            "jdbc:postgresql://sensitive-user:sensitive-password@remote.invalid/test"), out::add, errors::add))
        assertTrue(out.isEmpty())
        assertFalse(errors.joinToString().contains("sensitive"))
        assertFalse(errors.joinToString().contains("remote.invalid"))
    }
}
