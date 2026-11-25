package com.group33.server.photo.reconciliation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalPhotoReconciliationTest {
    @TempDir lateinit var temporary: Path
    private val content = "fictional-private-photo".toByteArray()
    private fun key() = PhotoInspectionKey(UUID.randomUUID(), content.size.toLong(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)))
    private fun root() = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    private fun put(root: Path, key: PhotoInspectionKey, bytes: ByteArray = content): Path = Files.write(root.resolve("${key.id}.blob"), bytes).also {
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("r--------"))
    }

    @Test fun `real file is streamed and verified without changing bytes permissions mtime or unrelated residue`() {
        val root = root(); val key = key(); val file = put(root, key)
        val old = FileTime.fromMillis(1_000)
        Files.setLastModifiedTime(file, old)
        val part = Files.write(root.resolve(".upload-fictional.part"), content)
        val unknown = Files.write(root.resolve("unknown.blob"), content)
        assertEquals(PhotoObjectObservation.VERIFIED, LocalPhotoObjectInspector(root).inspect(key))
        assertArrayEquals(content, Files.readAllBytes(file)); assertArrayEquals(content, Files.readAllBytes(part))
        assertArrayEquals(content, Files.readAllBytes(unknown))
        assertEquals(old, Files.getLastModifiedTime(file))
        assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(file))
    }

    @Test fun `missing published object is a finding while unavailable root remains incomplete`() {
        val root = root(); val reader = LocalPhotoObjectInspector(root)
        assertEquals(PhotoObjectObservation.MISSING, reader.inspect(key()))
        Files.delete(root)
        assertEquals(PhotoObjectObservation.UNAVAILABLE, reader.inspect(key()))
        assertFalse(Files.exists(root))
    }

    @Test fun `length and same length hash corruption are detected without mutation`() {
        val root = root(); val a = key(); val b = key()
        val short = put(root, a, content.copyOf(content.size - 1))
        val bad = content.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val changed = put(root, b, bad)
        val reader = LocalPhotoObjectInspector(root)
        assertEquals(PhotoObjectObservation.INTEGRITY_MISMATCH, reader.inspect(a))
        assertEquals(PhotoObjectObservation.INTEGRITY_MISMATCH, reader.inspect(b))
        assertArrayEquals(bad, Files.readAllBytes(changed))
        assertEquals((content.size - 1).toLong(), Files.size(short))
    }

    @Test fun `object symlink is refused without reading or modifying its external target`() {
        val root = root(); val key = key()
        val outside = Files.write(temporary.resolve("outside"), content)
        Files.createSymbolicLink(root.resolve("${key.id}.blob"), outside)
        assertEquals(PhotoObjectObservation.UNAVAILABLE, LocalPhotoObjectInspector(root).inspect(key))
        assertArrayEquals(content, Files.readAllBytes(outside))
        assertTrue(Files.isSymbolicLink(root.resolve("${key.id}.blob")))
    }

    @Test fun `missing or symlink roots are not created resolved into or permission repaired`() {
        val missing = temporary.resolve("not-created")
        assertThrows(Exception::class.java) { LocalPhotoObjectInspector(missing) }
        assertFalse(Files.exists(missing))
        val real = root(); val linked = Files.createSymbolicLink(temporary.resolve("link"), real)
        assertThrows(Exception::class.java) { LocalPhotoObjectInspector(linked) }
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rwxr-x---"))
        assertThrows(Exception::class.java) { LocalPhotoObjectInspector(real) }
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(real))
    }

    @Test fun `publicly readable object is unavailable rather than returned as healthy`() {
        val root = root(); val key = key(); val file = put(root, key)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        assertEquals(PhotoObjectObservation.UNAVAILABLE, LocalPhotoObjectInspector(root).inspect(key))
    }

    @Test fun `settings reject public password files and do not leak names paths or values`() {
        val root = root(); val password = Files.writeString(temporary.resolve("private-password"), "fictional-secret")
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-r--r--"))
        val env = settings(root, password)
        val output = mutableListOf<String>()
        assertEquals(64, PhotoReconciliationCli.execute(arrayOf("inspect"), env, output::add))
        assertEquals(listOf("{\"error\":\"INVALID_PHOTO_INSPECTION_SETTINGS\"}"), output)
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-------"))
        val accepted = PhotoInspectionSettings.from(env)
        assertEquals("PhotoInspectionSettings(REDACTED)", accepted.toString())
        val source = accepted.source as org.postgresql.ds.PGSimpleDataSource
        assertEquals(listOf("127.0.0.1"), source.serverNames.toList())
        assertEquals(5, source.socketTimeout)
    }

    @Test fun `invalid schemas oversized budgets and missing roots are sanitized before any connection`() {
        val root = root(); val password = Files.writeString(temporary.resolve("password"), "fictional-secret")
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-------"))
        val env = settings(root, password)
        for ((name, value) in listOf("SCHEMA" to "public", "SCHEMA" to "private; DROP TABLE x", "PORT" to "-1", "MAX_PHOTOS" to "1001", "MAX_BYTES" to "999999999999", "OBJECT_ROOT" to temporary.resolve("missing").toString())) {
            val output = mutableListOf<String>()
            assertEquals(64, PhotoReconciliationCli.execute(arrayOf("inspect"), env + ("GROUP33_PHOTO_INSPECT_$name" to value), output::add))
            assertEquals(listOf("{\"error\":\"INVALID_PHOTO_INSPECTION_SETTINGS\"}"), output)
        }
        assertFalse(Files.exists(temporary.resolve("missing")))
    }

    private fun settings(root: Path, password: Path) = mapOf(
        "GROUP33_PHOTO_INSPECT_DATABASE" to "fictional_database", "GROUP33_PHOTO_INSPECT_PORT" to "55432",
        "GROUP33_PHOTO_INSPECT_USER" to "fictional_reader", "GROUP33_PHOTO_INSPECT_SCHEMA" to "fictional_schema",
        "GROUP33_PHOTO_INSPECT_PASSWORD_FILE" to password.toString(), "GROUP33_PHOTO_INSPECT_OBJECT_ROOT" to root.toString())
}
