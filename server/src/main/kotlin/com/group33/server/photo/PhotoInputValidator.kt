package com.group33.server.photo

import org.springframework.http.MediaType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Server-private reasons; HTTP/field-error mapping belongs to the future upload adapter. */
internal enum class PhotoInputRejection(val errorCode: String) {
    TOO_MANY_BYTES("PHOTO_TOO_LARGE"),
    UNSUPPORTED_TYPE("PHOTO_TYPE_UNSUPPORTED"),
    INVALID_IMAGE("VALIDATION_FAILED"),
    TOO_MANY_PIXELS("VALIDATION_FAILED"),
}

internal sealed interface PhotoInputValidation {
    data class Accepted(val photo: ValidatedPhoto) : PhotoInputValidation
    data class Rejected(val reason: PhotoInputRejection) : PhotoInputValidation
}

/** Owns the exact bytes that were validated. No data-class toString exposes photo bytes. */
internal class ValidatedPhoto internal constructor(
    private val source: ByteArray,
    val contentType: String,
    val width: Int,
    val height: Int,
) {
    val byteCount: Long = source.size.toLong()
    val sha256: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source))
    fun copyBytes(): ByteArray = source.copyOf()
}

/** Unregistered, bounded file validator; not an upload API, authorization check or Storage adapter.
 * The caller supplies only file bytes, not a multipart envelope. Header dimensions are checked
 * before full raster decoding. Decoder warnings are conservatively rejected, including JPEG
 * repair of premature EOF. No global ImageIO settings or disk-cache configuration is changed.
 */
internal class PhotoInputValidator {
    fun validate(fileBytes: ByteArray, declaredContentType: String): PhotoInputValidation {
        if (fileBytes.size > MAX_FILE_BYTES) return rejected(PhotoInputRejection.TOO_MANY_BYTES)
        val expectedType = supportedType(declaredContentType)
            ?: return rejected(PhotoInputRejection.UNSUPPORTED_TYPE)
        if (fileBytes.isEmpty()) return rejected(PhotoInputRejection.INVALID_IMAGE)
        val source = fileBytes.copyOf()
        try {
            MemoryCacheImageInputStream(ByteArrayInputStream(source)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return rejected(PhotoInputRejection.INVALID_IMAGE)
                val reader = readers.next()
                try {
                    val actualType = when (reader.formatName.uppercase(Locale.ROOT)) {
                        "JPEG", "JPG" -> "image/jpeg"
                        "PNG" -> "image/png"
                        else -> null
                    }
                    if (actualType != expectedType) return rejected(PhotoInputRejection.INVALID_IMAGE)
                    if (actualType == "image/png" && !completePng(source)) {
                        return rejected(PhotoInputRejection.INVALID_IMAGE)
                    }
                    var warning = false
                    reader.addIIOReadWarningListener { _, _ -> warning = true }
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) return rejected(PhotoInputRejection.INVALID_IMAGE)
                    if (width.toLong() * height.toLong() > MAX_PIXELS) {
                        return rejected(PhotoInputRejection.TOO_MANY_PIXELS)
                    }
                    val image = reader.read(0) ?: return rejected(PhotoInputRejection.INVALID_IMAGE)
                    try {
                        if (warning || image.width != width || image.height != height) {
                            return rejected(PhotoInputRejection.INVALID_IMAGE)
                        }
                        return PhotoInputValidation.Accepted(ValidatedPhoto(source, actualType, width, height))
                    } finally {
                        image.flush()
                    }
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: IOException) {
            return rejected(PhotoInputRejection.INVALID_IMAGE)
        } catch (_: RuntimeException) {
            // Malformed codec input may also raise runtime exceptions; no raw diagnostic escapes.
            return rejected(PhotoInputRejection.INVALID_IMAGE)
        }
    }

    private fun supportedType(value: String): String? = try {
        val type = MediaType.parseMediaType(value)
        when ("${type.type}/${type.subtype}".lowercase(Locale.ROOT)) {
            "image/jpeg" -> "image/jpeg"
            "image/png" -> "image/png"
            else -> null
        }
    } catch (_: IllegalArgumentException) { null }

    /** ImageIO can return pixels without checking every PNG CRC or requiring IEND.
     * Check the bounded chunk envelope/integrity too; the codec still validates pixel data.
     */
    private fun completePng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size || !PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) return false
        val buffer = ByteBuffer.wrap(bytes)
        var cursor = PNG_SIGNATURE.size
        var sawHeader = false
        var sawData = false
        var dataEnded = false
        while (cursor <= bytes.size - 12) {
            val length = buffer.getInt(cursor).toLong() and 0xffff_ffffL
            val end = cursor.toLong() + length + 12
            if (end > bytes.size) return false
            if (!(cursor + 4 until cursor + 8).all {
                val value = bytes[it].toInt() and 0xff
                value in 65..90 || value in 97..122
            }) return false
            val type = buffer.getInt(cursor + 4)
            // Unknown critical chunks can change pixel interpretation. ImageIO's metadata-ignoring
            // path may silently skip them; enforce this independently of decoder warnings.
            if (bytes[cursor + 4].toInt() and 0x20 == 0 && type != IHDR && type != PLTE && type != IDAT && type != IEND) return false
            if (!sawHeader && (type != IHDR || length != 13L)) return false
            if (sawHeader && type == IHDR) return false
            val crc = CRC32().apply { update(bytes, cursor + 4, length.toInt() + 4) }.value
            val expected = buffer.getInt(cursor + 8 + length.toInt()).toLong() and 0xffff_ffffL
            if (crc != expected) return false
            sawHeader = true
            if (type == IDAT) {
                if (dataEnded) return false
                sawData = true
            } else if (sawData) dataEnded = true
            if (type == IEND) return length == 0L && sawData && end == bytes.size.toLong()
            cursor = end.toInt()
        }
        return false
    }

    private fun rejected(reason: PhotoInputRejection) = PhotoInputValidation.Rejected(reason)

    companion object {
        const val MAX_FILE_BYTES = 5_242_880
        const val MAX_PIXELS = 20_000_000L
        private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private const val IHDR = 0x49484452
        private const val PLTE = 0x504c5445
        private const val IDAT = 0x49444154
        private const val IEND = 0x49454e44
    }
}
