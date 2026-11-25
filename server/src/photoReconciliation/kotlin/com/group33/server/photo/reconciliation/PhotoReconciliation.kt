package com.group33.server.photo.reconciliation

import java.util.UUID

/** Private in-process identity, never part of a report or an App contract. */
class PhotoInspectionKey(val id: UUID, val bytes: Long, val sha256: String) {
    init { require(bytes in 1..5_242_880 && sha256.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString() = "PhotoInspectionKey(REDACTED)"
}

data class ProtectedPhotoOperations(val staging: Long, val rejected: Long) {
    init { require(staging >= 0 && rejected >= 0) }
}

interface PhotoMetadataSnapshot {
    fun protectedOperations(): ProtectedPhotoOperations
    fun readyAfter(after: UUID?, limit: Int): List<PhotoInspectionKey>
}

interface PhotoSnapshotSource {
    /** One primary, read-only Repeatable Read snapshot across every page. */
    fun <T> read(work: (PhotoMetadataSnapshot) -> T): T
}

enum class PhotoObjectObservation { VERIFIED, MISSING, INTEGRITY_MISMATCH, UNAVAILABLE }
fun interface PhotoObjectInspector { fun inspect(key: PhotoInspectionKey): PhotoObjectObservation }
enum class PhotoInspectionCompletion { COMPLETE, LIMIT_REACHED, DATABASE_UNAVAILABLE, OBJECT_UNAVAILABLE, INTERRUPTED }

data class PhotoInspectionLimits(
    val batchSize: Int = 25, val maxPhotos: Int = 100,
    val maxBytes: Long = 33_554_432, val maxMillis: Long = 30_000,
) {
    init {
        require(batchSize in 1..100 && maxPhotos in 1..1_000)
        require(maxBytes in 1..67_108_864 && maxMillis in 1..30_000)
    }
}

/** Aggregate observations, not a globally atomic Storage snapshot or permission to delete. */
data class PhotoInspectionReport(
    val completion: PhotoInspectionCompletion,
    val protectedStaging: Long, val protectedRejected: Long,
    val verified: Int, val missing: Int, val integrityMismatch: Int, val unavailable: Int,
) {
    val checked: Int get() = verified + missing + integrityMismatch + unavailable
    fun json() = """{"completion":"$completion","protectedStaging":$protectedStaging,"protectedRejected":$protectedRejected,"checked":$checked,"verified":$verified,"missing":$missing,"integrityMismatch":$integrityMismatch,"unavailable":$unavailable,"deletionAuthorized":false}"""
}

class PhotoReconciliationService(
    private val source: PhotoSnapshotSource, private val objects: PhotoObjectInspector,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun inspect(limits: PhotoInspectionLimits = PhotoInspectionLimits()): PhotoInspectionReport {
        val start = nanoTime()
        var protected = ProtectedPhotoOperations(0, 0)
        var verified = 0; var missing = 0; var mismatch = 0; var unavailable = 0
        fun report(completion: PhotoInspectionCompletion) = PhotoInspectionReport(
            completion, protected.staging, protected.rejected, verified, missing, mismatch, unavailable)
        fun stopped(): PhotoInspectionCompletion? = when {
            Thread.currentThread().isInterrupted -> PhotoInspectionCompletion.INTERRUPTED
            nanoTime() - start >= limits.maxMillis * 1_000_000 -> PhotoInspectionCompletion.LIMIT_REACHED
            else -> null
        }
        stopped()?.let { return report(it) }
        return try {
            source.read { snapshot ->
                protected = snapshot.protectedOperations()
                var cursor: UUID? = null
                var bytes = 0L
                while (true) {
                    stopped()?.let { return@read report(it) }
                    val count = verified + missing + mismatch + unavailable
                    val capacity = minOf(limits.batchSize, limits.maxPhotos - count)
                    // One lookahead distinguishes a complete exact boundary from truncation.
                    val page = snapshot.readyAfter(cursor, capacity + 1)
                    stopped()?.let { return@read report(it) }
                    check(page.size <= capacity + 1)
                    check(page.map { it.id }.distinct().size == page.size && page.none { it.id == cursor })
                    for (key in page.take(capacity)) {
                        stopped()?.let { return@read report(it) }
                        if (key.bytes > limits.maxBytes - bytes) return@read report(PhotoInspectionCompletion.LIMIT_REACHED)
                        val observation = try { objects.inspect(key) }
                            catch (failure: InterruptedException) { throw failure }
                            catch (_: Exception) { PhotoObjectObservation.UNAVAILABLE }
                        bytes += key.bytes
                        when (observation) {
                            PhotoObjectObservation.VERIFIED -> verified++
                            PhotoObjectObservation.MISSING -> missing++
                            PhotoObjectObservation.INTEGRITY_MISMATCH -> mismatch++
                            PhotoObjectObservation.UNAVAILABLE -> unavailable++
                        }
                        stopped()?.let { return@read report(it) }
                        if (observation == PhotoObjectObservation.UNAVAILABLE)
                            return@read report(PhotoInspectionCompletion.OBJECT_UNAVAILABLE)
                        cursor = key.id
                    }
                    if (page.size <= capacity) return@read report(PhotoInspectionCompletion.COMPLETE)
                    if (verified + missing + mismatch + unavailable >= limits.maxPhotos)
                        return@read report(PhotoInspectionCompletion.LIMIT_REACHED)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            report(PhotoInspectionCompletion.INTERRUPTED)
        } catch (_: Exception) {
            report(if (Thread.currentThread().isInterrupted) PhotoInspectionCompletion.INTERRUPTED
                else PhotoInspectionCompletion.DATABASE_UNAVAILABLE)
        }
    }
}
