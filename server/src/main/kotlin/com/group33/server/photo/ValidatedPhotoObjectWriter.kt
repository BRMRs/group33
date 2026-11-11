package com.group33.server.photo

import com.group33.server.photo.storage.PhotoObjectException
import com.group33.server.photo.storage.PhotoObjectFailure
import com.group33.server.photo.storage.PhotoObjectKey
import com.group33.server.photo.storage.PhotoObjectStore
import java.util.UUID

/** Internal object-write result, never an upload receipt, READY state or permission to read. */
internal sealed interface PhotoObjectWriteResult {
    data class Stored(val key: PhotoObjectKey, val contentType: String, val width: Int, val height: Int) : PhotoObjectWriteResult
    data class Rejected(val reason: PhotoInputRejection) : PhotoObjectWriteResult
    data class StorageFailed(val reason: PhotoObjectFailure) : PhotoObjectWriteResult
}

/**
 * Connects actual input validation to private object storage without re-encoding the photo.
 * The application layer must authorize first and supply its stable, server-allocated object ID.
 * Retrying an uncertain write must reuse that ID and payload. Stored does not commit business metadata.
 */
internal class ValidatedPhotoObjectWriter(private val store: PhotoObjectStore) {
    private val validator = PhotoInputValidator()

    fun write(objectId: UUID, bytes: ByteArray, declaredContentType: String): PhotoObjectWriteResult {
        val photo = when (val validation = validator.validate(bytes, declaredContentType)) {
            is PhotoInputValidation.Rejected -> return PhotoObjectWriteResult.Rejected(validation.reason)
            is PhotoInputValidation.Accepted -> validation.photo
        }
        val key = PhotoObjectKey(objectId, photo.byteCount, photo.sha256)
        return try {
            // The validated copy is used even if the caller later changes its original byte array.
            photo.copyBytes().inputStream().use { store.putIfAbsent(key, it) }
            PhotoObjectWriteResult.Stored(key, photo.contentType, photo.width, photo.height)
        } catch (error: PhotoObjectException) {
            PhotoObjectWriteResult.StorageFailed(error.failure)
        }
    }
}
