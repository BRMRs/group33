package com.group33.server.photo.reconciliation

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PhotoReconciliationServiceTest {
    private fun key(i: Int, bytes: Long = 4) = PhotoInspectionKey(UUID(0, i.toLong()), bytes, "a".repeat(64))
    private fun source(keys: List<PhotoInspectionKey>, protected: ProtectedPhotoOperations = ProtectedPhotoOperations(0, 0)) =
        object : PhotoSnapshotSource {
            override fun <T> read(work: (PhotoMetadataSnapshot) -> T): T = work(object : PhotoMetadataSnapshot {
                override fun protectedOperations() = protected
                override fun readyAfter(after: UUID?, limit: Int) = keys.filter { after == null || it.id > after }.take(limit)
            })
        }

    @Test fun `lookahead proves exact photo limit complete across multiple pages`() {
        val seen = mutableListOf<UUID>()
        val result = PhotoReconciliationService(source((1..3).map(::key)), { seen.add(it.id); PhotoObjectObservation.VERIFIED })
            .inspect(PhotoInspectionLimits(batchSize = 1, maxPhotos = 3))
        assertEquals(PhotoInspectionCompletion.COMPLETE, result.completion)
        assertEquals(3, result.verified)
        assertEquals((1..3).map { key(it).id }, seen)
    }

    @Test fun `truncated scan cannot claim complete even when checked objects are healthy`() {
        val result = PhotoReconciliationService(source((1..4).map(::key)), { PhotoObjectObservation.VERIFIED })
            .inspect(PhotoInspectionLimits(batchSize = 2, maxPhotos = 3))
        assertEquals(PhotoInspectionCompletion.LIMIT_REACHED, result.completion)
        assertEquals(3, result.checked)
    }

    @Test fun `byte budget refuses next object without skipping it or spending its bytes`() {
        val seen = mutableListOf<UUID>()
        val result = PhotoReconciliationService(source(listOf(key(1, 3), key(2, 4))), { seen.add(it.id); PhotoObjectObservation.VERIFIED })
            .inspect(PhotoInspectionLimits(maxBytes = 6))
        assertEquals(PhotoInspectionCompletion.LIMIT_REACHED, result.completion)
        assertEquals(listOf(key(1).id), seen)
    }

    @Test fun `missing and mismatching observations are findings not permission to delete`() {
        val result = PhotoReconciliationService(source(listOf(key(1), key(2))), {
            if (it.id == key(1).id) PhotoObjectObservation.MISSING else PhotoObjectObservation.INTEGRITY_MISMATCH
        }).inspect()
        assertEquals(PhotoInspectionCompletion.COMPLETE, result.completion)
        assertEquals(1, result.missing); assertEquals(1, result.integrityMismatch)
        assertTrue(result.json().contains("\"deletionAuthorized\":false"))
        assertFalse(result.json().contains(key(1).id.toString()))
    }

    @Test fun `unavailable storage stops with an incomplete result and preserves partial observations`() {
        val result = PhotoReconciliationService(source(listOf(key(1), key(2), key(3))), {
            if (it.id == key(1).id) PhotoObjectObservation.VERIFIED else PhotoObjectObservation.UNAVAILABLE
        }).inspect()
        assertEquals(PhotoInspectionCompletion.OBJECT_UNAVAILABLE, result.completion)
        assertEquals(1, result.verified); assertEquals(1, result.unavailable)
        assertEquals(2, result.checked)
    }

    @Test fun `database outage is never a successful empty inventory or raw exception output`() {
        val unavailable = object : PhotoSnapshotSource {
            override fun <T> read(work: (PhotoMetadataSnapshot) -> T): T = error("private-schema password fictional-secret")
        }
        val result = PhotoReconciliationService(unavailable, { error("No object access") }).inspect()
        assertEquals(PhotoInspectionCompletion.DATABASE_UNAVAILABLE, result.completion)
        assertEquals(0, result.checked)
        assertFalse(result.json().contains("private")); assertFalse(result.toString().contains("secret"))
    }

    @Test fun `staging and rejected reservations remain protected without probing or expiring them`() {
        val result = PhotoReconciliationService(source(emptyList(), ProtectedPhotoOperations(7, 2)), {
            error("Unpublished reservation has no trusted content key")
        }).inspect()
        assertEquals(PhotoInspectionCompletion.COMPLETE, result.completion)
        assertEquals(7, result.protectedStaging); assertEquals(2, result.protectedRejected)
        assertEquals(0, result.checked)
    }

    @Test fun `interruption before opening database performs no IO and remains interrupted`() {
        Thread.currentThread().interrupt()
        try {
            val unavailable = object : PhotoSnapshotSource {
                override fun <T> read(work: (PhotoMetadataSnapshot) -> T): T = error("Must not open")
            }
            val result = PhotoReconciliationService(unavailable, { error("Must not inspect") }).inspect()
            assertEquals(PhotoInspectionCompletion.INTERRUPTED, result.completion)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `interruption during object read is preserved instead of being counted as missing`() {
        try {
            val result = PhotoReconciliationService(source(listOf(key(1))), { throw InterruptedException("private path") }).inspect()
            assertEquals(PhotoInspectionCompletion.INTERRUPTED, result.completion)
            assertEquals(0, result.missing)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `elapsed budget uses monotonic time and does not claim a complete final page`() {
        val time = AtomicLong()
        val result = PhotoReconciliationService(source(listOf(key(1))), {
            time.set(2_000_000); PhotoObjectObservation.VERIFIED
        }, time::get).inspect(PhotoInspectionLimits(maxMillis = 1))
        assertEquals(PhotoInspectionCompletion.LIMIT_REACHED, result.completion)
        assertEquals(1, result.verified)
    }

    @Test fun `unsupported commands never read private settings and have stable errors`() {
        for (args in listOf(emptyArray(), arrayOf("delete"), arrayOf("inspect", "private-secret"))) {
            val output = mutableListOf<String>()
            assertEquals(64, PhotoReconciliationCli.execute(args, emptyMap(), output::add))
            assertEquals(listOf("{\"error\":\"INVALID_PHOTO_INSPECTION_INPUT\"}"), output)
        }
    }

    @Test fun `empty page arriving after elapsed budget is not complete empty success`() {
        val time = AtomicLong()
        val delayed = object : PhotoSnapshotSource {
            override fun <T> read(work: (PhotoMetadataSnapshot) -> T): T = work(object : PhotoMetadataSnapshot {
                override fun protectedOperations() = ProtectedPhotoOperations(0, 0)
                override fun readyAfter(after: UUID?, limit: Int): List<PhotoInspectionKey> {
                    time.set(2_000_000); return emptyList()
                }
            })
        }
        val result = PhotoReconciliationService(delayed, { error("No object read") }, time::get)
            .inspect(PhotoInspectionLimits(maxMillis = 1))
        assertEquals(PhotoInspectionCompletion.LIMIT_REACHED, result.completion)
        assertEquals(0, result.checked)
    }
}
