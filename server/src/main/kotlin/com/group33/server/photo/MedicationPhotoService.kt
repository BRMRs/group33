package com.group33.server.photo

import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.CodeStoreBusy
import com.group33.server.pairing.PairingCodeIssuanceService
import com.group33.server.photo.storage.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import org.springframework.http.MediaType
import java.time.Clock
import java.util.UUID

internal enum class PhotoBusinessRejection {
    RESOURCE_NOT_ACCESSIBLE, ROLE_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED, OPERATION_IN_PROGRESS, SERVICE_UNAVAILABLE,
    PHOTO_TOO_LARGE, PHOTO_TYPE_UNSUPPORTED, VALIDATION_FAILED,
}
internal sealed interface PhotoBusinessResult {
    class Uploaded(val view: MedicationPhotoView) : PhotoBusinessResult { override fun toString() = "PhotoUploaded(REDACTED)" }
    class Listed(val items: List<MedicationPhotoView>) : PhotoBusinessResult { override fun toString() = "PhotoList(REDACTED)" }
    class Content(val bytes: ByteArray, val contentType: String) : PhotoBusinessResult { override fun toString() = "PhotoContent(REDACTED)" }
    data class Rejected(val reason: PhotoBusinessRejection, val inputReason: PhotoInputRejection? = null) : PhotoBusinessResult
}

/** Upload owns no public object URLs. STAGING is retained as an indefinite recovery reservation; no cleanup is implemented. */
internal class MedicationPhotoService(private val metadata: PhotoMetadataStore, private val objects: PhotoObjectStore,
    private val clock: Clock = Clock.systemUTC()) {
    fun upload(actor: AnonymousUser, elderId: String, operationId: UUID, bytes: ByteArray, declaredType: String): PhotoBusinessResult {
        if (actor.id != elderId) return rejected(PhotoBusinessRejection.RESOURCE_NOT_ACCESSIBLE)
        if (actor.role != UserRole.ELDER) return rejected(PhotoBusinessRejection.ROLE_NOT_ALLOWED)
        val source = bytes.copyOf()
        val mime = canonicalMime(declaredType)
        val digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source))
        val fingerprint = PairingCodeIssuanceService.hash("${ContractProtocol.CANONICALIZATION_VERSION}:SUBMIT_PHOTO:$elderId:${source.size}:$digest:$mime")
        val key = PhotoUploadKey(actor.id, operationId)
        var staged: PhotoUploadOperation? = null
        val prepared = safely {
            metadata.photoTransaction { tx ->
                val old = tx.photoOperation(key)
                if (old != null && (old.installationId != actor.installationId || old.elderId != elderId || old.fingerprint != fingerprint))
                    return@photoTransaction rejected(PhotoBusinessRejection.IDEMPOTENCY_KEY_REUSED)
                old?.receipt?.let { return@photoTransaction PhotoBusinessResult.Uploaded(it) }
                val validation = PhotoInputValidator().validate(source, declaredType)
                if (validation is PhotoInputValidation.Rejected) {
                    tx.putPhotoOperation(key, PhotoUploadOperation(actor.installationId, elderId, fingerprint,
                        old?.photoId ?: UUID.randomUUID(), PhotoUploadState.REJECTED, rejection = validation.reason))
                    return@photoTransaction inputRejected(validation.reason)
                }
                val value = PhotoUploadOperation(actor.installationId, elderId, fingerprint, old?.photoId ?: UUID.randomUUID(), PhotoUploadState.STAGING)
                tx.putPhotoOperation(key, value)
                staged = value
                null
            }
        }
        if (prepared != null) return prepared
        val intent = checkNotNull(staged)
        // IO occurs after the stable intent is committed. An uncertain write never allocates a new object identity.
        val objectKey = PhotoObjectKey(intent.photoId, source.size.toLong(), digest)
        try {
            source.inputStream().use { objects.putIfAbsent(objectKey, it) }
            objects.read(objectKey) // confirm immutable object availability/integrity before publication
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return rejected(PhotoBusinessRejection.SERVICE_UNAVAILABLE)
        }
        return safely {
            metadata.photoTransaction { tx ->
                val current = checkNotNull(tx.photoOperation(key))
                check(current.fingerprint == fingerprint && current.photoId == intent.photoId)
                current.receipt?.let { return@photoTransaction PhotoBusinessResult.Uploaded(it) }
                val view = MedicationPhotoView(intent.photoId.toString(), elderId, mime, clock.millis())
                tx.publishPhoto(ReadyMedicationPhoto(view, objectKey))
                tx.putPhotoOperation(key, PhotoUploadOperation(actor.installationId, elderId, fingerprint, intent.photoId,
                    PhotoUploadState.READY, receipt = view))
                PhotoBusinessResult.Uploaded(view)
            }
        } ?: error("Publication must produce a result")
    }

    fun list(actor: AnonymousUser, elderId: String): PhotoBusinessResult = safely {
        metadata.photoTransaction { tx ->
            if (!authorized(tx, actor, elderId)) rejected(PhotoBusinessRejection.RESOURCE_NOT_ACCESSIBLE)
            else PhotoBusinessResult.Listed(tx.readyPhotos(elderId))
        }
    } ?: error("Listing must produce a result")

    fun content(actor: AnonymousUser, photoId: String): PhotoBusinessResult = safely {
        metadata.photoTransaction { tx ->
            val photo = tx.readyPhoto(photoId)
            if (photo == null || !authorized(tx, actor, photo.view.elderId)) return@photoTransaction rejected(PhotoBusinessRejection.RESOURCE_NOT_ACCESSIBLE)
            // The guard remains held through reading private bytes; revocation and content authorization serialize.
            PhotoBusinessResult.Content(objects.read(photo.objectKey), photo.view.contentType)
        }
    } ?: error("Content must produce a result")

    private fun authorized(tx: PhotoMetadataTransaction, actor: AnonymousUser, elderId: String) =
        (actor.role == UserRole.ELDER && actor.id == elderId) ||
            (actor.role == UserRole.CAREGIVER && tx.activeRelationship(elderId)?.caregiverId == actor.id)
    private fun safely(work: () -> PhotoBusinessResult?): PhotoBusinessResult? = try { work() }
        catch (_: CodeStoreBusy) { rejected(PhotoBusinessRejection.OPERATION_IN_PROGRESS) }
        catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            rejected(PhotoBusinessRejection.SERVICE_UNAVAILABLE)
        }
    private fun canonicalMime(value: String) = try {
        val type = MediaType.parseMediaType(value)
        "${type.type.lowercase(java.util.Locale.ROOT)}/${type.subtype.lowercase(java.util.Locale.ROOT)}"
    } catch (_: IllegalArgumentException) { "invalid:$value" }
    private fun inputRejected(reason: PhotoInputRejection) = PhotoBusinessResult.Rejected(
        when (reason) {
            PhotoInputRejection.TOO_MANY_BYTES -> PhotoBusinessRejection.PHOTO_TOO_LARGE
            PhotoInputRejection.UNSUPPORTED_TYPE -> PhotoBusinessRejection.PHOTO_TYPE_UNSUPPORTED
            else -> PhotoBusinessRejection.VALIDATION_FAILED
        }, reason)
    private fun rejected(reason: PhotoBusinessRejection) = PhotoBusinessResult.Rejected(reason)
}
