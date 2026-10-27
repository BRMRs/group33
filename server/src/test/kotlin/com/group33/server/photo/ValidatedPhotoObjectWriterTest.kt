package com.group33.server.photo

import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.server.photo.storage.PhotoObjectException
import com.group33.server.photo.storage.PhotoObjectFailure
import com.group33.server.photo.storage.PhotoObjectKey
import com.group33.server.photo.storage.PhotoObjectStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ValidatedPhotoObjectWriterTest {
    @TempDir lateinit var temporary: Path
    private val objectId = UUID.fromString("b4c35b2c-0723-48cd-880b-996948c63446")

    @ParameterizedTest
    @ValueSource(strings = ["png", "jpeg"])
    fun `validated image is stored exactly and readable through a fresh adapter`(format: String) {
        val bytes = image(format)
        val root = temporary.resolve("objects")
        val result = stored(ValidatedPhotoObjectWriter(LocalFilePhotoObjectStore(root)).write(objectId, bytes, "image/$format"))
        assertEquals(objectId, result.key.objectId)
        assertEquals(bytes.size.toLong(), result.key.byteCount)
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), result.key.sha256)
        assertEquals("image/$format", result.contentType)
        assertEquals(3, result.width)
        assertEquals(2, result.height)
        assertArrayEquals(bytes, LocalFilePhotoObjectStore(root).read(result.key))
        assertEquals(listOf("$objectId.blob"), fileNames(root))
        assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(root.resolve("$objectId.blob")))
    }

    @Test
    fun `corrupt image is rejected before any storage call or filesystem write`() {
        assertRejectedWithoutStorage(byteArrayOf(1, 2, 3), "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test
    fun `declared MIME mismatch is rejected before storage`() {
        assertRejectedWithoutStorage(image("png"), "image/jpeg", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test
    fun `unsupported MIME is rejected before storage`() {
        assertRejectedWithoutStorage(image("png"), "image/gif", PhotoInputRejection.UNSUPPORTED_TYPE)
    }

    @Test
    fun `oversized input is rejected before storage`() {
        assertRejectedWithoutStorage(ByteArray(PhotoInputValidator.MAX_FILE_BYTES + 1), "image/png", PhotoInputRejection.TOO_MANY_BYTES)
    }

    @Test
    fun `truncated valid image is rejected before storage`() {
        val bytes = image("png")
        assertRejectedWithoutStorage(bytes.copyOf(bytes.size - 12), "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test
    fun `same ID and payload replay creates one object and different bytes never overwrite it`() {
        val root = temporary.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val writer = ValidatedPhotoObjectWriter(store)
        val bytes = image("png")
        val first = stored(writer.write(objectId, bytes, "image/png"))
        assertEquals(first, writer.write(objectId, bytes, "IMAGE/PNG"))
        assertEquals(PhotoObjectWriteResult.StorageFailed(PhotoObjectFailure.OBJECT_CONFLICT), writer.write(objectId, image("png", 0x00ff00), "image/png"))
        assertArrayEquals(bytes, store.read(first.key))
        assertEquals(listOf("$objectId.blob"), fileNames(root))
    }

    @Test
    fun `invalid retry cannot use an existing stored object to bypass validation`() {
        val root = temporary.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val writer = ValidatedPhotoObjectWriter(store)
        val bytes = image("png")
        val first = stored(writer.write(objectId, bytes, "image/png"))
        assertEquals(PhotoObjectWriteResult.Rejected(PhotoInputRejection.INVALID_IMAGE), writer.write(objectId, byteArrayOf(1), "image/png"))
        assertArrayEquals(bytes, store.read(first.key))
    }

    @Test
    fun `caller mutation after validation cannot alter written bytes or hash`() {
        val root = temporary.resolve("objects")
        val realStore = LocalFilePhotoObjectStore(root)
        val original = image("png")
        val expected = original.copyOf()
        val changingCaller = object : PhotoObjectStore by realStore {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey {
                original.fill(0)
                return realStore.putIfAbsent(key, source)
            }
        }
        val result = stored(ValidatedPhotoObjectWriter(changingCaller).write(objectId, original, "image/png"))
        assertArrayEquals(expected, realStore.read(result.key))
        assertTrue(original.all { it == 0.toByte() })
    }

    @Test
    fun `uncertain post write failure retries the same object without overwriting or duplicating`() {
        val root = temporary.resolve("objects")
        val realStore = LocalFilePhotoObjectStore(root)
        var loseResponse = true
        val uncertainStore = object : PhotoObjectStore by realStore {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey {
                val result = realStore.putIfAbsent(key, source)
                if (loseResponse) {
                    loseResponse = false
                    throw PhotoObjectException(PhotoObjectFailure.UNAVAILABLE)
                }
                return result
            }
        }
        val writer = ValidatedPhotoObjectWriter(uncertainStore)
        val bytes = image("png")
        assertEquals(PhotoObjectWriteResult.StorageFailed(PhotoObjectFailure.UNAVAILABLE), writer.write(objectId, bytes, "image/png"))
        assertEquals(listOf("$objectId.blob"), fileNames(root))
        val retried = stored(writer.write(objectId, bytes, "image/png"))
        assertArrayEquals(bytes, realStore.read(retried.key))
        assertEquals(listOf("$objectId.blob"), fileNames(root))
    }

    @Test
    fun `unavailable private root does not report stored and explicit retry can succeed`() {
        val root = temporary.resolve("objects")
        val writer = ValidatedPhotoObjectWriter(LocalFilePhotoObjectStore(root))
        val bytes = image("png")
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"))
        try {
            assertEquals(PhotoObjectWriteResult.StorageFailed(PhotoObjectFailure.UNAVAILABLE), writer.write(objectId, bytes, "image/png"))
            assertTrue(fileNames(root).isEmpty())
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        }
        val result = stored(writer.write(objectId, bytes, "image/png"))
        assertArrayEquals(bytes, LocalFilePhotoObjectStore(root).read(result.key))
    }

    @Test
    fun `concurrent different validated payloads publish one immutable object`() {
        val root = temporary.resolve("objects")
        val store = LocalFilePhotoObjectStore(root)
        val bytes = listOf(image("png"), image("png", 0x00ff00))
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { i -> executor.submit(Callable {
                val writer = ValidatedPhotoObjectWriter(LocalFilePhotoObjectStore(root))
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                writer.write(objectId, bytes[i % 2], "image/png")
            }) }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            val written = results.filterIsInstance<PhotoObjectWriteResult.Stored>()
            assertEquals(4, written.size)
            assertEquals(1, written.map { it.key }.distinct().size)
            assertEquals(4, results.count { it == PhotoObjectWriteResult.StorageFailed(PhotoObjectFailure.OBJECT_CONFLICT) })
            assertTrue(bytes.any { it.contentEquals(store.read(written.first().key)) })
            assertEquals(listOf("$objectId.blob"), fileNames(root))
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun assertRejectedWithoutStorage(bytes: ByteArray, type: String, expected: PhotoInputRejection) {
        val root = temporary.resolve("objects")
        val realStore = LocalFilePhotoObjectStore(root)
        var calls = 0
        val observed = object : PhotoObjectStore by realStore {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey {
                calls++
                return realStore.putIfAbsent(key, source)
            }
        }
        assertEquals(PhotoObjectWriteResult.Rejected(expected), ValidatedPhotoObjectWriter(observed).write(objectId, bytes, type))
        assertEquals(0, calls)
        assertTrue(fileNames(root).isEmpty())
    }

    private fun stored(result: PhotoObjectWriteResult) = assertInstanceOf(PhotoObjectWriteResult.Stored::class.java, result)

    private fun fileNames(root: Path): List<String> = Files.list(root).use { paths ->
        paths.map { it.fileName.toString() }.sorted().toList()
    }

    private fun image(format: String, color: Int = 0xff0000): ByteArray {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        return try {
            for (x in 0 until 3) for (y in 0 until 2) image.setRGB(x, y, color)
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, format, output))
                output.toByteArray()
            }
        } finally {
            image.flush()
        }
    }
}
