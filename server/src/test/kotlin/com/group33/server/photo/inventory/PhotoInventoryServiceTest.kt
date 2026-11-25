package com.group33.server.photo.inventory

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PhotoInventoryServiceTest {
    private val ids = List(4) { UUID.randomUUID() }
    private fun entries() = ids.map { PhotoInventoryEntry(InventoryEntryKind.OBJECT, it) } +
        listOf(PhotoInventoryEntry(InventoryEntryKind.TEMPORARY), PhotoInventoryEntry(InventoryEntryKind.UNSAFE))
    private class Source(private val lookup: (Set<UUID>) -> Map<UUID, PhotoReference>) : PhotoInventorySource {
        var opens = 0
        override fun <T> read(work: (PhotoInventorySnapshot) -> T): T { opens++; return work(PhotoInventorySnapshot(lookup)) }
    }
    private class Cursor(private val entries: List<PhotoInventoryEntry>) : PhotoInventoryCursor {
        var calls = 0; var closed = false; var stable = true; var unchanged = true
        override fun next() = entries.getOrNull(calls++)
        override fun unchanged(entry: PhotoInventoryEntry) = unchanged
        override fun stableDirectory() = stable
        override fun close() { closed = true }
    }

    @Test fun `bounded batches distinguish references recovery protection candidates and uninspected files`() {
        val all = entries(); val batches = mutableListOf<Int>()
        val source = Source { batch -> batches.add(batch.size); batch.associateWith { PhotoReference.entries[ids.indexOf(it)] } }
        val cursor = Cursor(all)
        val result = PhotoInventoryService(source, PhotoInventoryObjects { cursor }).inspect(PhotoInventoryLimits(batchSize = 2))
        assertEquals(InventoryCompletion.COMPLETE, result.completion)
        assertEquals(listOf(1,1,1,1,1,1), listOf(result.referenced,result.stagingProtected,result.rejectedProtected,result.unmatchedCandidates,result.temporary,result.unsafe))
        assertEquals(listOf(2,2),batches); assertEquals(1,source.opens); assertTrue(cursor.closed)
        assertTrue(result.json().contains("\"deletionAuthorized\":false")); assertTrue(result.json().contains("\"globallyConsistent\":false"))
        ids.forEach { assertFalse(result.json().contains(it.toString())) }
        assertFalse(all.first().toString().contains(ids.first().toString()))
    }

    @Test fun `exact maximum can complete but one additional object requires incomplete result`() {
        for ((size,status) in listOf(3 to InventoryCompletion.COMPLETE,4 to InventoryCompletion.LIMIT_REACHED)) {
            val cursor = Cursor(entries().take(size))
            val report = PhotoInventoryService(Source { batch -> batch.associateWith { PhotoReference.UNMATCHED } }, PhotoInventoryObjects { cursor })
                .inspect(PhotoInventoryLimits(batchSize = 2,maxEntries = 3))
            assertEquals(status,report.completion); assertEquals(3,report.checked); assertEquals(4,cursor.calls)
            assertTrue(cursor.closed)
        }
    }

    @Test fun `database failure after a completed prefix retains only observations and never healthy empty`() {
        var page = 0
        val cursor = Cursor(entries())
        val report = PhotoInventoryService(Source { batch ->
            if (++page == 2) error("fictional private database details")
            batch.associateWith { PhotoReference.STAGING_PROTECTED }
        },PhotoInventoryObjects { cursor }).inspect(PhotoInventoryLimits(batchSize = 1))
        assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,report.completion); assertEquals(1,report.checked)
        assertFalse(report.json().contains("fictional")); assertTrue(cursor.closed)
    }

    @Test fun `missing or extra database mapping fails closed rather than inventing unmatched rows`() {
        for (mapping in listOf(emptyMap(),mapOf(UUID.randomUUID() to PhotoReference.UNMATCHED))) {
            val report = PhotoInventoryService(Source { mapping },PhotoInventoryObjects { Cursor(entries()) }).inspect()
            assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,report.completion); assertEquals(0,report.checked)
        }
    }

    @Test fun `storage failure cannot become a successful empty directory`() {
        val report = PhotoInventoryService(Source { emptyMap() },PhotoInventoryObjects { throw InventoryStorageUnavailable() }).inspect()
        assertEquals(InventoryCompletion.OBJECT_UNAVAILABLE,report.completion); assertEquals(0,report.checked)
    }

    @Test fun `observed mutation invalidates classification before it is counted`() {
        for (directory in listOf(true,false)) {
            val cursor = Cursor(entries())
            val source = Source { batch ->
                if (directory) cursor.stable = false else cursor.unchanged = false
                batch.associateWith { PhotoReference.UNMATCHED }
            }
            val report = PhotoInventoryService(source,PhotoInventoryObjects { cursor }).inspect()
            assertEquals(InventoryCompletion.CONCURRENT_CHANGE,report.completion); assertEquals(0,report.checked)
            assertTrue(cursor.closed)
        }
    }

    @Test fun `monotonic deadline expires between operations without adding unverified counts`() {
        var elapsed = 0L
        val source = Source { batch -> elapsed = 2_000_000; batch.associateWith { PhotoReference.UNMATCHED } }
        val cursor = Cursor(entries())
        val report = PhotoInventoryService(source,PhotoInventoryObjects { cursor }) { elapsed }
            .inspect(PhotoInventoryLimits(maxMillis = 1))
        assertEquals(InventoryCompletion.LIMIT_REACHED,report.completion); assertEquals(0,report.checked); assertTrue(cursor.closed)
    }

    @Test fun `interrupted execution touches no source and preserves interruption`() {
        val source = Source { error("must not query") }
        Thread.currentThread().interrupt()
        try {
            val report = PhotoInventoryService(source,PhotoInventoryObjects { error("must not open") }).inspect()
            assertEquals(InventoryCompletion.INTERRUPTED,report.completion); assertEquals(0,source.opens)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `empty directory still validates its database and closes the directory stream`() {
        val source = Source { error("no IDs to query") }; val cursor = Cursor(emptyList())
        val report = PhotoInventoryService(source,PhotoInventoryObjects { cursor }).inspect()
        assertEquals(InventoryCompletion.COMPLETE,report.completion); assertEquals(0,report.checked)
        assertEquals(1,source.opens); assertTrue(cursor.closed)
    }

    @Test fun `inventory limits reject unlimited and oversized inputs`() {
        assertThrows(IllegalArgumentException::class.java) { PhotoInventoryLimits(batchSize = 101) }
        assertThrows(IllegalArgumentException::class.java) { PhotoInventoryLimits(maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { PhotoInventoryLimits(maxEntries = 1001) }
        assertThrows(IllegalArgumentException::class.java) { PhotoInventoryLimits(maxMillis = 30_001) }
    }
}
