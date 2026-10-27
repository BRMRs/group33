package com.group33.server.photo

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

class PhotoInputValidatorTest {
    private val validator = PhotoInputValidator()

    @Test fun `real RGB JPEG preserves original bytes and metadata`() {
        val bytes = encoded("JPEG", 7, 5)
        val photo = accepted(bytes, "image/jpeg")
        assertEquals("image/jpeg", photo.contentType)
        assertEquals(7, photo.width)
        assertEquals(5, photo.height)
        assertEquals(bytes.size.toLong(), photo.byteCount)
        assertArrayEquals(bytes, photo.copyBytes())
        assertEquals(digest(bytes), photo.sha256)
    }

    @Test fun `real transparent PNG is accepted without reencoding`() {
        val bytes = encoded("PNG", 9, 4, BufferedImage.TYPE_INT_ARGB)
        val photo = accepted(bytes, "image/png")
        assertEquals("image/png", photo.contentType)
        assertEquals(9, photo.width)
        assertEquals(4, photo.height)
        assertArrayEquals(bytes, photo.copyBytes())
    }

    @Test fun `valid grayscale indexed and progressive images remain supported`() {
        for (kind in listOf(BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_BYTE_INDEXED, BufferedImage.TYPE_USHORT_GRAY)) {
            accepted(encoded("PNG", 6, 3, kind), "image/png")
        }
        accepted(encoded("JPEG", 6, 3, BufferedImage.TYPE_BYTE_GRAY), "image/jpeg")
        accepted(encoded("JPEG", 6, 3, progressive = true), "image/jpeg")
    }

    @Test fun `legal MIME case and parameters normalize to controlled type`() {
        val photo = accepted(encoded("PNG", 2, 2), "IMAGE/PNG; charset=UTF-8")
        assertEquals("image/png", photo.contentType)
    }

    @Test fun `unsupported wildcard missing and malformed declared types are distinct from bad content`() {
        val bytes = encoded("PNG", 2, 2)
        for (type in listOf("image/gif", "image/webp", "application/octet-stream", "image/*", "*/*", "", "not-a-media-type")) {
            rejected(bytes, type, PhotoInputRejection.UNSUPPORTED_TYPE)
        }
        rejected(encoded("GIF", 2, 2, BufferedImage.TYPE_BYTE_INDEXED), "image/gif", PhotoInputRejection.UNSUPPORTED_TYPE)
    }

    @Test fun `actual bytes must match claimed supported format`() {
        rejected(encoded("JPEG", 2, 2), "image/png", PhotoInputRejection.INVALID_IMAGE)
        rejected(encoded("PNG", 2, 2), "image/jpeg", PhotoInputRejection.INVALID_IMAGE)
        rejected(encoded("GIF", 2, 2, BufferedImage.TYPE_BYTE_INDEXED), "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `empty text and signature-only input cannot pass as a photo`() {
        for (bytes in listOf(byteArrayOf(), "fictional text renamed as a photo".toByteArray(),
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), byteArrayOf(255.toByte(), 216.toByte()))) {
            rejected(bytes, "image/png", PhotoInputRejection.INVALID_IMAGE)
        }
    }

    @Test fun `a valid PNG at exact file-byte limit is allowed and plus one byte is rejected`() {
        val original = encoded("PNG", 2, 2)
        val exact = paddedPng(original, PhotoInputValidator.MAX_FILE_BYTES)
        assertEquals(5_242_880, exact.size)
        assertEquals(exact.size.toLong(), accepted(exact, "image/png").byteCount)
        val larger = paddedPng(original, PhotoInputValidator.MAX_FILE_BYTES + 1)
        rejected(larger, "image/png", PhotoInputRejection.TOO_MANY_BYTES)
    }

    @Test fun `valid JPEG comment metadata at file-byte boundary is also allowed`() {
        val original = encoded("JPEG", 2, 2)
        val exact = paddedJpeg(original, PhotoInputValidator.MAX_FILE_BYTES)
        assertEquals(5_242_880L, accepted(exact, "image/jpeg").byteCount)
        rejected(paddedJpeg(original, PhotoInputValidator.MAX_FILE_BYTES + 1), "image/jpeg", PhotoInputRejection.TOO_MANY_BYTES)
    }

    @Test fun `ordinary JPEG EXIF orientation is preserved rather than stripped`() {
        val original = encoded("JPEG", 7, 5)
        val exif = ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("Exif\u0000\u0000II".toByteArray(Charsets.US_ASCII)).putShort(42).putInt(8)
            .putShort(1).putShort(0x0112).putShort(3).putInt(1).putShort(6).putShort(0).putInt(0).array()
        val segment = ByteBuffer.allocate(exif.size + 4).putShort(0xffe1.toShort()).putShort((exif.size + 2).toShort()).put(exif).array()
        val bytes = original.copyOfRange(0, 2) + segment + original.copyOfRange(2, original.size)
        assertArrayEquals(bytes, accepted(bytes, "image/jpeg").copyBytes())
    }

    @Test fun `a real image at exact pixel limit is decoded and accepted`() {
        val photo = accepted(encoded("PNG", 5_000, 4_000, BufferedImage.TYPE_BYTE_GRAY), "image/png")
        assertEquals(20_000_000L, photo.width.toLong() * photo.height)
    }

    @Test fun `header dimensions above pixel budget are rejected before pixel decoding`() {
        // CRC-correct headers with deliberately mismatched tiny IDAT. A full decode would instead
        // produce INVALID_IMAGE; the budget reason proves rejection before raster allocation/read.
        val tiny = encoded("PNG", 2, 2)
        for ((width, height) in listOf(20_000_001 to 1, 65_536 to 65_536, Int.MAX_VALUE to Int.MAX_VALUE)) {
            rejected(withDimensions(tiny, width, height), "image/png", PhotoInputRejection.TOO_MANY_PIXELS)
        }
    }

    @Test fun `invalid zero dimensions cannot reach accepted result`() {
        rejected(withDimensions(encoded("PNG", 2, 2), 0, 2), "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `missing PNG end marker and trailing incomplete chunk are rejected`() {
        val bytes = encoded("PNG", 4, 4)
        rejected(bytes.copyOf(bytes.size - 12), "image/png", PhotoInputRejection.INVALID_IMAGE)
        rejected(bytes + byteArrayOf(0, 0, 0), "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `corrupt PNG CRC is rejected even when a decoder could recover pixels`() {
        val bytes = encoded("PNG", 4, 4)
        bytes[29] = (bytes[29].toInt() xor 1).toByte() // IHDR CRC, not raster content.
        rejected(bytes, "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `CRC-correct corrupt compressed pixels still require a real full decode`() {
        val bytes = encoded("PNG", 8, 8)
        val cursor = chunkOffset(bytes, "IDAT")
        val length = ByteBuffer.wrap(bytes).getInt(cursor)
        bytes[cursor + 8] = 0 // Invalid zlib method, with repaired chunk CRC.
        setCrc(bytes, cursor, length)
        rejected(bytes, "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `truncated JPEG must not be silently repaired into accepted content`() {
        val bytes = encoded("JPEG", 16, 16)
        rejected(bytes.copyOf(bytes.size - 2), "image/jpeg", PhotoInputRejection.INVALID_IMAGE)
        rejected(bytes.copyOf(bytes.size / 2), "image/jpeg", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `PNG chunk length overflow cannot allocate attacker-declared buffers`() {
        val bytes = encoded("PNG", 2, 2)
        ByteBuffer.wrap(bytes).putInt(8, Int.MAX_VALUE)
        rejected(bytes, "image/png", PhotoInputRejection.INVALID_IMAGE)
        ByteBuffer.wrap(bytes).putInt(8, -1)
        rejected(bytes, "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `invalid PNG chunk names are rejected even with a matching CRC`() {
        val original = encoded("PNG", 2, 2)
        val bytes = original.copyOfRange(0, 33) + chunk("pa1d", byteArrayOf()) + original.copyOfRange(33, original.size)
        rejected(bytes, "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `PNG pixel-data chunks cannot resume after an intervening ancillary chunk`() {
        val original = encoded("PNG", 2, 2)
        val consecutive = original.copyOf(original.size - 12) + chunk("IDAT", byteArrayOf()) +
            original.copyOfRange(original.size - 12, original.size)
        accepted(consecutive, "image/png") // Valid consecutive chunks remain legal.
        val bytes = original.copyOf(original.size - 12) + chunk("paDd", byteArrayOf()) +
            chunk("IDAT", byteArrayOf()) + original.copyOfRange(original.size - 12, original.size)
        rejected(bytes, "image/png", PhotoInputRejection.INVALID_IMAGE)
    }

    @Test fun `validated source and returned copies cannot be mutated by the caller`() {
        val bytes = encoded("PNG", 3, 3)
        val original = bytes.copyOf()
        val photo = accepted(bytes, "image/png")
        bytes.fill(0)
        photo.copyBytes().fill(1)
        assertArrayEquals(original, photo.copyBytes())
        assertEquals(digest(original), photo.sha256)
        assertFalse(photo.toString().contains(digest(original)))
    }

    @Test fun `concurrent calls keep independent readers and accepted byte ownership`() {
        val bytes = encoded("PNG", 3, 3)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..8).map { Callable { accepted(bytes, "image/png") } }
            val photos = executor.invokeAll(tasks).map { it.get() }
            assertTrue(photos.all { it.sha256 == digest(bytes) && it.width == 3 })
            assertEquals(8, photos.toSet().size)
        } finally { executor.shutdownNow() }
    }

    private fun accepted(bytes: ByteArray, type: String): ValidatedPhoto {
        val result = validator.validate(bytes, type)
        assertInstanceOf(PhotoInputValidation.Accepted::class.java, result)
        return (result as PhotoInputValidation.Accepted).photo
    }

    private fun rejected(bytes: ByteArray, type: String, reason: PhotoInputRejection) {
        assertEquals(PhotoInputValidation.Rejected(reason), validator.validate(bytes, type))
    }

    private fun encoded(format: String, width: Int, height: Int,
                        kind: Int = BufferedImage.TYPE_INT_RGB, progressive: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, kind)
        val writer = ImageIO.getImageWritersByFormatName(format).next()
        try {
            val bytes = ByteArrayOutputStream()
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                val params = writer.defaultWriteParam
                if (progressive) params.progressiveMode = ImageWriteParam.MODE_DEFAULT
                writer.write(null, IIOImage(image, null, null), params)
            }
            return bytes.toByteArray()
        } finally { writer.dispose(); image.flush() }
    }

    private fun paddedPng(original: ByteArray, size: Int): ByteArray {
        val padding = ByteArray(size - original.size - 12)
        val type = "paDd".toByteArray(Charsets.US_ASCII) // Valid private ancillary chunk.
        val chunk = ByteBuffer.allocate(padding.size + 12).putInt(padding.size).put(type).put(padding)
        chunk.putInt(CRC32().apply { update(type); update(padding) }.value.toInt())
        return original.copyOfRange(0, 33) + chunk.array() + original.copyOfRange(33, original.size)
    }

    private fun withDimensions(original: ByteArray, width: Int, height: Int): ByteArray = original.copyOf().also {
        ByteBuffer.wrap(it).putInt(16, width).putInt(20, height)
        setCrc(it, 8, 13)
    }

    private fun paddedJpeg(original: ByteArray, size: Int): ByteArray {
        val padding = ByteBuffer.allocate(size - original.size)
        while (padding.hasRemaining()) {
            var length = minOf(65_533, padding.remaining() - 4)
            if (padding.remaining() - length - 4 in 1..3) length -= 4
            padding.putShort(0xfffe.toShort()).putShort((length + 2).toShort())
            padding.position(padding.position() + length)
        }
        return original.copyOfRange(0, 2) + padding.array() + original.copyOfRange(2, original.size)
    }

    private fun setCrc(bytes: ByteArray, offset: Int, length: Int) {
        val crc = CRC32().apply { update(bytes, offset + 4, length + 4) }.value
        ByteBuffer.wrap(bytes).putInt(offset + 8 + length, crc.toInt())
    }

    private fun chunk(typeName: String, data: ByteArray): ByteArray {
        val type = typeName.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(data.size + 12).putInt(data.size).put(type).put(data)
            .putInt(CRC32().apply { update(type); update(data) }.value.toInt()).array()
    }

    private fun chunkOffset(bytes: ByteArray, type: String): Int {
        var cursor = 8
        while (cursor < bytes.size) {
            if (String(bytes, cursor + 4, 4, Charsets.US_ASCII) == type) return cursor
            cursor += ByteBuffer.wrap(bytes).getInt(cursor) + 12
        }
        error("Missing test fixture chunk")
    }

    private fun digest(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
