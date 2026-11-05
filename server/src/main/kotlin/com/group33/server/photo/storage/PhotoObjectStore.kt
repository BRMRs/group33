package com.group33.server.photo.storage

import java.io.InputStream
import java.util.UUID

/** Internal proposal only. Business authorization and READY/receipt publication are separate. */
internal data class PhotoObjectKey(val objectId: UUID, val byteCount: Long, val sha256: String) {
    init {
        require(byteCount in 1..MAX_BYTES)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val MAX_BYTES = 5_242_880L
    }
}

internal enum class PhotoObjectFailure { MISSING, INTEGRITY_MISMATCH, OBJECT_CONFLICT, UNAVAILABLE }

/** No path, raw codec/IO diagnostic, object bytes, or transport status escapes this seam. */
internal class PhotoObjectException(val failure: PhotoObjectFailure) : RuntimeException(failure.name)

internal interface PhotoObjectStore {
    /** Caller owns the stream. An already stored matching object may be replayed without reading it. */
    fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey

    /** Private bytes only. Caller must check current business access before invoking this method. */
    fun read(key: PhotoObjectKey): ByteArray
}
