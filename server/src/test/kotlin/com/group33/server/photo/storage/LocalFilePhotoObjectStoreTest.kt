package com.group33.server.photo.storage

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalFilePhotoObjectStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `real png and jpeg bytes survive adapter recreation and reads are independent`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        for (format in listOf("png", "jpeg")) {
            val bytes = image(format)
            assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
            val key = key(bytes)
            assertEquals(key, store.putIfAbsent(key, ByteArrayInputStream(bytes)))
            val reopened = LocalFilePhotoObjectStore(root)
            assertArrayEquals(bytes, reopened.read(key))
            reopened.read(key).fill(0)
            assertArrayEquals(bytes, reopened.read(key))
        }
    }

    @Test
    fun `root and temporary and installed objects have private posix permissions`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        store.putIfAbsent(key, ByteArrayInputStream(bytes))
        assertEquals("rwx------", permissions(root))
        assertEquals("r--------", permissions(path(root, key)))
        assertEquals(1, entries(root).size)
    }

    @Test
    fun `existing same identity replays without requiring the original stream again`() {
        val root = directory.resolve("objects")
        val bytes = image()
        val key = key(bytes)
        LocalFilePhotoObjectStore(root).putIfAbsent(key, ByteArrayInputStream(bytes))
        val unreadable = object : InputStream() {
            override fun read(): Int = throw IOException("must not reopen expired source")
        }
        assertEquals(key, LocalFilePhotoObjectStore(root).putIfAbsent(key, unreadable))
        assertArrayEquals(bytes, LocalFilePhotoObjectStore(root).read(key))
        assertEquals(1, entries(root).size)
    }

    @Test
    fun `same id with different bytes never overwrites the first object`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val original = image(value = 0x123456)
        val replacement = image(value = 0x654321)
        val key = key(original)
        store.putIfAbsent(key, ByteArrayInputStream(original))
        val conflicting = key(replacement, key.objectId)
        assertFailure(PhotoObjectFailure.OBJECT_CONFLICT) {
            store.putIfAbsent(conflicting, ByteArrayInputStream(replacement))
        }
        assertArrayEquals(original, store.read(key))
        assertEquals(1, entries(root).size)
    }

    @Test
    fun `short long and wrong digest streams publish no object or partial residue`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        for (input in listOf(bytes.dropLast(1).toByteArray(), bytes + 1, bytes.reversedArray())) {
            assertFailure(PhotoObjectFailure.INTEGRITY_MISMATCH) {
                store.putIfAbsent(key, ByteArrayInputStream(input))
            }
            assertTrue(entries(root).isEmpty())
        }
        assertEquals(key, store.putIfAbsent(key, ByteArrayInputStream(bytes)))
    }

    @Test
    fun `source io failure after partial write leaves no installed object and same intent retries`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        val failed = object : InputStream() {
            var first = true
            override fun read(): Int = throw IOException("synthetic private detail")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw IOException("synthetic private detail")
                first = false
                bytes.copyInto(buffer, offset, 0, 10)
                return 10
            }
        }
        val error = assertFailure(PhotoObjectFailure.UNAVAILABLE) { store.putIfAbsent(key, failed) }
        assertFalse(error.toString().contains("private detail"))
        assertNull(error.cause)
        assertTrue(entries(root).isEmpty())
        assertEquals(key, store.putIfAbsent(key, ByteArrayInputStream(bytes)))
    }

    @Test
    fun `caller retains stream ownership on success and failure`() {
        val store = LocalFilePhotoObjectStore(directory.resolve("objects"))
        val bytes = image()
        for (content in listOf(bytes, bytes.dropLast(1).toByteArray())) {
            val input = object : ByteArrayInputStream(content) {
                var wasClosed = false
                override fun close() { wasClosed = true; super.close() }
            }
            val key = key(bytes)
            if (content.size == bytes.size) store.putIfAbsent(key, input)
            else assertFailure(PhotoObjectFailure.INTEGRITY_MISMATCH) { store.putIfAbsent(key, input) }
            assertFalse(input.wasClosed)
            input.close()
        }
    }

    @Test
    fun `object remains absent during an incomplete write then appears complete`() {
        val root = directory.resolve("objects")
        val writer = LocalFilePhotoObjectStore(root)
        val reader = LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                started.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit(Callable { writer.putIfAbsent(key, source) })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFailure(PhotoObjectFailure.MISSING) { reader.read(key) }
            val partial = entries(root).single()
            assertTrue(partial.fileName.toString().endsWith(".part"))
            assertEquals("rw-------", permissions(partial))
            proceed.countDown()
            assertEquals(key, future.get(5, TimeUnit.SECONDS))
            assertArrayEquals(bytes, reader.read(key))
            assertEquals(1, entries(root).size)
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `eight independent adapters converge on one immutable object`() {
        val root = directory.resolve("objects")
        LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map {
                pool.submit(Callable {
                    val store = LocalFilePhotoObjectStore(root)
                    release.await()
                    store.putIfAbsent(key, ByteArrayInputStream(bytes))
                })
            }
            release.countDown()
            results.forEach { assertEquals(key, it.get(10, TimeUnit.SECONDS)) }
            assertArrayEquals(bytes, LocalFilePhotoObjectStore(root).read(key))
            assertEquals(1, entries(root).size)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `concurrent conflicting content has exactly one winner without replacement`() {
        val root = directory.resolve("objects")
        LocalFilePhotoObjectStore(root)
        val id = UUID.randomUUID()
        val inputs = listOf(image(value = 0x123456), image(value = 0x654321))
        val keys = inputs.map { key(it, id) }
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0..7).map { index ->
                pool.submit(Callable {
                    release.await()
                    val which = index % 2
                    try {
                        LocalFilePhotoObjectStore(root).putIfAbsent(keys[which], ByteArrayInputStream(inputs[which]))
                        which to null
                    } catch (error: PhotoObjectException) { which to error.failure }
                })
            }
            release.countDown()
            val finished = results.map { it.get(10, TimeUnit.SECONDS) }
            val winners = finished.filter { it.second == null }.map { it.first }.toSet()
            assertEquals(1, winners.size)
            assertEquals(4, finished.count { it.second == PhotoObjectFailure.OBJECT_CONFLICT })
            val winner = winners.single()
            assertArrayEquals(inputs[winner], LocalFilePhotoObjectStore(root).read(keys[winner]))
            assertEquals(1, entries(root).size)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `symbolic link roots and existing nonprivate roots are rejected without chmod`() {
        val target = Files.createDirectory(directory.resolve("target"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val link = Files.createSymbolicLink(directory.resolve("link"), target)
        assertFailure(PhotoObjectFailure.UNAVAILABLE) { LocalFilePhotoObjectStore(link) }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFailure(PhotoObjectFailure.UNAVAILABLE) { LocalFilePhotoObjectStore(target) }
        assertEquals("rwxr-xr-x", permissions(target))
    }

    @Test
    fun `symbolic link and directory object targets are rejected without following them`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        val outside = Files.write(directory.resolve("outside"), bytes)
        Files.createSymbolicLink(path(root, key), outside)
        assertFailure(PhotoObjectFailure.UNAVAILABLE) { store.read(key) }
        assertFailure(PhotoObjectFailure.UNAVAILABLE) { store.putIfAbsent(key, ByteArrayInputStream(bytes)) }
        assertArrayEquals(bytes, Files.readAllBytes(outside))
        Files.delete(path(root, key))
        Files.createDirectory(path(root, key))
        assertFailure(PhotoObjectFailure.UNAVAILABLE) { store.read(key) }
    }

    @Test
    fun `root and object permission drift fails closed`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        store.putIfAbsent(key, ByteArrayInputStream(bytes))
        Files.setPosixFilePermissions(path(root, key), PosixFilePermissions.fromString("rw-r--r--"))
        assertFailure(PhotoObjectFailure.UNAVAILABLE) { store.read(key) }
        Files.setPosixFilePermissions(path(root, key), PosixFilePermissions.fromString("r--------"))
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFailure(PhotoObjectFailure.UNAVAILABLE) { store.read(key) }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        assertArrayEquals(bytes, store.read(key))
    }

    @Test
    fun `truncated same-size corrupt and oversized files are detected before returning bytes`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        store.putIfAbsent(key, ByteArrayInputStream(bytes))
        Files.setPosixFilePermissions(path(root, key), PosixFilePermissions.fromString("rw-------"))
        for (corrupt in listOf(bytes.dropLast(1).toByteArray(), bytes.reversedArray(), bytes + 1)) {
            Files.write(path(root, key), corrupt)
            assertFailure(PhotoObjectFailure.INTEGRITY_MISMATCH) { store.read(key) }
        }
        assertFailure(PhotoObjectFailure.OBJECT_CONFLICT) { store.putIfAbsent(key, ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `object byte upper bound is inclusive and invalid identities fail before io`() {
        val store = LocalFilePhotoObjectStore(directory.resolve("objects"))
        val bytes = ByteArray(PhotoObjectKey.MAX_BYTES.toInt()) { (it % 251).toByte() }
        val key = key(bytes)
        store.putIfAbsent(key, ByteArrayInputStream(bytes))
        assertArrayEquals(bytes, store.read(key))
        for (size in listOf(0L, -1L, PhotoObjectKey.MAX_BYTES + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { PhotoObjectKey(UUID.randomUUID(), size, key.sha256) }
        }
        for (hash in listOf("../outside", key.sha256.uppercase(), "0".repeat(63))) {
            assertThrows(IllegalArgumentException::class.java) { PhotoObjectKey(UUID.randomUUID(), 1, hash) }
        }
    }

    @Test
    fun `aged objects and partial files survive reconstruction without unsafe age cleanup`() {
        val root = directory.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val bytes = image()
        val key = key(bytes)
        store.putIfAbsent(key, ByteArrayInputStream(bytes))
        val partial = Files.write(root.resolve(".upload-synthetic.part"), bytes)
        val old = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"))
        Files.setLastModifiedTime(partial, old)
        Files.setLastModifiedTime(path(root, key), old)
        assertArrayEquals(bytes, LocalFilePhotoObjectStore(root).read(key))
        assertTrue(Files.exists(partial))
        assertEquals(2, entries(root).size)
    }

    @Test
    fun `halted writer leaves only private partial and original intent can recover`() {
        val root = directory.resolve("objects")
        val bytes = image()
        val key = key(bytes)
        val source = Files.write(directory.resolve("synthetic.png"), bytes)
        val process = probe("crash-write", root, key, source)
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child process timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(23, process.exitValue(), output)
        } finally { process.destroyForcibly() }
        val store = LocalFilePhotoObjectStore(root)
        assertFailure(PhotoObjectFailure.MISSING) { store.read(key) }
        val orphan = entries(root).single()
        assertTrue(orphan.fileName.toString().endsWith(".part"))
        assertEquals(10, Files.size(orphan))
        assertEquals("rw-------", permissions(orphan))
        assertEquals(key, store.putIfAbsent(key, ByteArrayInputStream(bytes)))
        assertArrayEquals(bytes, store.read(key))
        assertTrue(Files.exists(orphan), "cleanup needs a separate reviewed lease/reference protocol")
    }

    @Test
    fun `separate writer and reader jvms retain the original photo`() {
        val root = directory.resolve("objects")
        val bytes = image()
        val key = key(bytes)
        val source = Files.write(directory.resolve("synthetic.png"), bytes)
        for (mode in listOf("write", "read")) {
            val process = probe(mode, root, key, source)
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child process timed out")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), output)
                assertEquals("OK", output.trim())
            } finally { process.destroyForcibly() }
        }
        assertArrayEquals(bytes, LocalFilePhotoObjectStore(root).read(key))
    }

    private fun key(bytes: ByteArray, id: UUID = UUID.randomUUID()) =
        PhotoObjectKey(id, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun path(root: Path, key: PhotoObjectKey) = root.resolve("${key.objectId}.blob")
    private fun entries(root: Path): List<Path> = Files.list(root).use { it.toList() }
    private fun permissions(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
    private fun assertFailure(expected: PhotoObjectFailure, work: () -> Unit): PhotoObjectException =
        assertThrows(PhotoObjectException::class.java, work).also { assertEquals(expected, it.failure) }

    private fun probe(mode: String, root: Path, key: PhotoObjectKey, source: Path): Process {
        val classpath = listOf(PhotoStoreProcessProbe::class.java, LocalFilePhotoObjectStore::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        return ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
            PhotoStoreProcessProbe::class.java.name, mode, root.toString(), key.objectId.toString(),
            key.byteCount.toString(), key.sha256, source.toString(),
        ).redirectErrorStream(true).start()
    }

    private fun image(format: String = "png", value: Int = 0x123456): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, value)
        return ByteArrayOutputStream().use { out ->
            check(ImageIO.write(image, format, out))
            image.flush()
            out.toByteArray()
        }
    }
}
