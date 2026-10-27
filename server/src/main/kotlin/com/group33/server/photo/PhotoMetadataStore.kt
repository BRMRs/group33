package com.group33.server.photo

import com.group33.server.pairing.PairingDirectoryStore
import com.group33.server.pairing.PairingDirectoryTransaction
import com.group33.server.photo.storage.PhotoObjectKey
import com.group33.shared.contract.MedicationPhotoView
import java.util.UUID

internal enum class PhotoUploadState { STAGING, REJECTED, READY }
internal data class PhotoUploadKey(val actorId: String, val operationId: UUID)
internal class PhotoUploadOperation(
    val installationId: String,
    val elderId: String,
    val fingerprint: String,
    val photoId: UUID,
    val state: PhotoUploadState,
    val receipt: MedicationPhotoView? = null,
    val rejection: PhotoInputRejection? = null,
) {
    init {
        require((state == PhotoUploadState.READY) == (receipt != null))
        require((state == PhotoUploadState.REJECTED) == (rejection != null))
        receipt?.let { require(it.id == photoId.toString() && it.elderId == elderId) }
    }
    override fun toString() = "PhotoUploadOperation(REDACTED)"
}
internal class ReadyMedicationPhoto(val view: MedicationPhotoView, val objectKey: PhotoObjectKey) {
    override fun toString() = "ReadyMedicationPhoto(REDACTED)"
}
/** Metadata and current relationship reads share the very same pairing transaction/guard. */
internal interface PhotoMetadataStore : PairingDirectoryStore {
    fun <T> photoTransaction(work: (PhotoMetadataTransaction) -> T): T
}
internal interface PhotoMetadataTransaction : PairingDirectoryTransaction {
    fun photoOperation(key: PhotoUploadKey): PhotoUploadOperation?
    fun putPhotoOperation(key: PhotoUploadKey, value: PhotoUploadOperation)
    fun readyPhoto(id: String): ReadyMedicationPhoto?
    fun readyPhotos(elderId: String): List<MedicationPhotoView>
    fun publishPhoto(value: ReadyMedicationPhoto)
}
