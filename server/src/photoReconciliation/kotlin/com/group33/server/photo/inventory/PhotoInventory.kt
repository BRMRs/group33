package com.group33.server.photo.inventory

import java.util.UUID

enum class InventoryEntryKind { OBJECT, TEMPORARY, UNSAFE }
class PhotoInventoryEntry(val kind: InventoryEntryKind, val objectId: UUID? = null, internal val observed: Any? = null) {
    init { require((kind == InventoryEntryKind.OBJECT) == (objectId != null)) }
    override fun toString() = "PhotoInventoryEntry(REDACTED)"
}
interface PhotoInventoryCursor : AutoCloseable {
    fun next(): PhotoInventoryEntry?
    fun unchanged(entry: PhotoInventoryEntry): Boolean
    fun stableDirectory(): Boolean
}
fun interface PhotoInventoryObjects { fun open(): PhotoInventoryCursor }
enum class PhotoReference { REFERENCED, STAGING_PROTECTED, REJECTED_PROTECTED, UNMATCHED }
fun interface PhotoInventorySnapshot { fun references(ids: Set<UUID>): Map<UUID, PhotoReference> }
interface PhotoInventorySource { fun <T> read(work: (PhotoInventorySnapshot) -> T): T }
class InventoryStorageUnavailable : RuntimeException("PHOTO_INVENTORY_STORAGE_UNAVAILABLE")
enum class InventoryCompletion { COMPLETE, LIMIT_REACHED, DATABASE_UNAVAILABLE, OBJECT_UNAVAILABLE, CONCURRENT_CHANGE, INTERRUPTED }
data class PhotoInventoryLimits(val batchSize: Int = 25, val maxEntries: Int = 100, val maxMillis: Long = 30_000) {
    init { require(batchSize in 1..100 && maxEntries in 1..1_000 && maxMillis in 1..30_000) }
}
/** Counts are observations in the selected scope, never a deletion plan or an atomic Storage snapshot. */
data class PhotoInventoryReport(
    val completion: InventoryCompletion, val referenced: Int, val stagingProtected: Int,
    val rejectedProtected: Int, val unmatchedCandidates: Int, val temporary: Int, val unsafe: Int,
) {
    val checked: Int get() = referenced + stagingProtected + rejectedProtected + unmatchedCandidates + temporary + unsafe
    fun json() = """{"completion":"$completion","checked":$checked,"referenced":$referenced,"stagingProtected":$stagingProtected,"rejectedProtected":$rejectedProtected,"unmatchedCandidates":$unmatchedCandidates,"temporary":$temporary,"unsafe":$unsafe,"globallyConsistent":false,"deletionAuthorized":false}"""
}

class PhotoInventoryService(
    private val source: PhotoInventorySource, private val objects: PhotoInventoryObjects,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun inspect(limits: PhotoInventoryLimits = PhotoInventoryLimits()): PhotoInventoryReport {
        val start = nanoTime()
        val counts = IntArray(6)
        fun report(status: InventoryCompletion) = PhotoInventoryReport(status, counts[0], counts[1], counts[2], counts[3], counts[4], counts[5])
        fun stopped(): InventoryCompletion? = when {
            Thread.currentThread().isInterrupted -> InventoryCompletion.INTERRUPTED
            nanoTime() - start >= limits.maxMillis * 1_000_000 -> InventoryCompletion.LIMIT_REACHED
            else -> null
        }
        stopped()?.let { return report(it) }
        return try {
            source.read { snapshot ->
                objects.open().use { cursor ->
                    while (true) {
                        stopped()?.let { return@read report(it) }
                        if (!cursor.stableDirectory()) return@read report(InventoryCompletion.CONCURRENT_CHANGE)
                        val capacity = minOf(limits.batchSize, limits.maxEntries - counts.sum())
                        if (capacity == 0) {
                            val more = cursor.next() // One bounded lookahead proves an exact full-directory boundary.
                            stopped()?.let { return@read report(it) }
                            return@read report(when {
                                !cursor.stableDirectory() -> InventoryCompletion.CONCURRENT_CHANGE
                                more != null -> InventoryCompletion.LIMIT_REACHED
                                else -> InventoryCompletion.COMPLETE
                            })
                        }
                        val page = ArrayList<PhotoInventoryEntry>(capacity)
                        while (page.size < capacity) {
                            stopped()?.let { return@read report(it) }
                            page.add(cursor.next() ?: break)
                        }
                        stopped()?.let { return@read report(it) }
                        val ids = page.mapNotNull { it.objectId }.toSet()
                        val references = if (ids.isEmpty()) emptyMap() else snapshot.references(ids)
                        check(references.keys == ids)
                        stopped()?.let { return@read report(it) }
                        if (!cursor.stableDirectory()) return@read report(InventoryCompletion.CONCURRENT_CHANGE)
                        for (entry in page) {
                            if (!cursor.unchanged(entry)) return@read report(InventoryCompletion.CONCURRENT_CHANGE)
                            val bucket = when (entry.kind) {
                                InventoryEntryKind.TEMPORARY -> 4
                                InventoryEntryKind.UNSAFE -> 5
                                InventoryEntryKind.OBJECT -> when (references.getValue(requireNotNull(entry.objectId))) {
                                    PhotoReference.REFERENCED -> 0
                                    PhotoReference.STAGING_PROTECTED -> 1
                                    PhotoReference.REJECTED_PROTECTED -> 2
                                    PhotoReference.UNMATCHED -> 3
                                }
                            }
                            counts[bucket]++
                        }
                        stopped()?.let { return@read report(it) }
                        if (!cursor.stableDirectory()) return@read report(InventoryCompletion.CONCURRENT_CHANGE)
                        if (page.size < capacity) return@read report(InventoryCompletion.COMPLETE)
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); report(InventoryCompletion.INTERRUPTED)
        } catch (_: InventoryStorageUnavailable) {
            report(InventoryCompletion.OBJECT_UNAVAILABLE)
        } catch (_: Exception) {
            report(if (Thread.currentThread().isInterrupted) InventoryCompletion.INTERRUPTED else InventoryCompletion.DATABASE_UNAVAILABLE)
        }
    }
}
