package com.group33.server.photo

import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.*
import com.group33.server.photo.storage.*
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.MedicationPhotoView
import com.group33.shared.contract.RequestPairingRequest
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MedicationPhotoServiceTest {
    @TempDir lateinit var temporary: Path
    private val elder = AnonymousUser("elder-photo", "installation-elder-photo", "虚构长者", UserRole.ELDER)
    private val caregiver = AnonymousUser("caregiver-photo", "installation-caregiver-photo", "虚构照护者", UserRole.CAREGIVER)
    private val otherElder = elder.copy(id = "other-elder", installationId = "other-elder-installation")
    private val otherCaregiver = caregiver.copy(id = "other-caregiver", installationId = "other-caregiver-installation")
    private val store = InMemoryPairingCodeStore()
    private val clock = PhotoClock()
    private val root get() = temporary.resolve("objects")
    private fun objects() = LocalFilePhotoObjectStore(root)
    private fun service(metadata: PhotoMetadataStore = store, storage: PhotoObjectStore = objects()) =
        MedicationPhotoService(metadata, storage, clock)
    private fun key() = UUID.randomUUID()

    @Test fun `owning elder uploads exact bytes before any pairing or medication exists`() {
        val bytes = image()
        val operation = key()
        val service = service()
        val view = uploaded(service.upload(elder, elder.id, operation, bytes, "image/png"))
        assertEquals(elder.id, view.elderId)
        assertEquals("image/png", view.contentType)
        assertEquals(clock.millis(), view.createdAtEpochMillis)
        assertEquals(listOf(view), listed(service.list(elder, elder.id)))
        assertArrayEquals(bytes, content(service.content(elder, view.id)).bytes)
        assertEquals(listOf("${view.id}.blob"), objectNames())
        val operationRecord = operation(operation)
        assertEquals(PhotoUploadState.READY, operationRecord.state)
        assertEquals(view, operationRecord.receipt)
        assertEquals(view.id, operationRecord.photoId.toString())
        assertNull(operationRecord.rejection)
    }

    @Test fun `pending caregiver has no access and explicit acceptance grants metadata and content access`() {
        val bytes = image()
        val service = service()
        val view = uploaded(service.upload(elder, elder.id, key(), bytes, "image/png"))
        val requestId = pendingPairing()
        inaccessible(service.list(caregiver, elder.id))
        inaccessible(service.content(caregiver, view.id))
        relationship(PairingDecisionService(store, clock).accept(elder, requestId, key()))
        assertEquals(listOf(view), listed(service.list(caregiver, elder.id)))
        assertArrayEquals(bytes, content(service.content(caregiver, view.id)).bytes)
    }

    @Test fun `revocation removes caregiver access while elder keeps the original photo`() {
        val relationship = paired()
        val bytes = image()
        val service = service()
        val view = uploaded(service.upload(elder, elder.id, key(), bytes, "image/png"))
        assertArrayEquals(bytes, content(service.content(caregiver, view.id)).bytes)
        relationship(PairingDecisionService(store, clock).revoke(caregiver, relationship.id, key()))
        inaccessible(service.list(caregiver, elder.id))
        inaccessible(service.content(caregiver, view.id))
        assertEquals(listOf(view), listed(service.list(elder, elder.id)))
        assertArrayEquals(bytes, content(service.content(elder, view.id)).bytes)
    }

    @Test fun `unrelated actors and missing resources have the same inaccessible result`() {
        paired()
        val service = service()
        val view = uploaded(service.upload(elder, elder.id, key(), image(), "image/png"))
        for (actor in listOf(otherElder, otherCaregiver)) {
            assertEquals(inaccessible(service.content(actor, "missing-photo")), inaccessible(service.content(actor, view.id)))
            assertEquals(inaccessible(service.list(actor, "missing-elder")), inaccessible(service.list(actor, elder.id)))
        }
        inaccessible(service.content(elder, "missing-photo"))
        inaccessible(service.list(caregiver, otherElder.id))
    }

    @Test fun `upload rejects another elder and caregiver before object io or reserving an intent`() {
        paired()
        val operation = key()
        val real = objects()
        var objectWrites = 0
        val observed = object : PhotoObjectStore by real {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey {
                objectWrites++
                return real.putIfAbsent(key, source)
            }
        }
        val service = service(storage = observed)
        inaccessible(service.upload(elder, otherElder.id, operation, image(), "image/png"))
        inaccessible(service.upload(caregiver, elder.id, operation, image(), "image/png"))
        rejected(service.upload(caregiver, caregiver.id, operation, image(), "image/png"), PhotoBusinessRejection.ROLE_NOT_ALLOWED)
        assertEquals(0, objectWrites)
        assertNull(store.photoTransaction { it.photoOperation(PhotoUploadKey(elder.id, operation)) })
        assertNull(store.photoTransaction { it.photoOperation(PhotoUploadKey(caregiver.id, operation)) })
        assertTrue(objectNames().isEmpty())
    }

    @Test fun `complete private object remains invisible while its operation is staging`() {
        paired()
        val operation = key()
        val bytes = image()
        val real = objects()
        lateinit var service: MedicationPhotoService
        val observed = object : PhotoObjectStore by real {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey {
                val stored = real.putIfAbsent(key, source)
                val staging = operation(operation)
                assertEquals(PhotoUploadState.STAGING, staging.state)
                assertNull(staging.receipt)
                assertEquals(key.objectId, staging.photoId)
                assertArrayEquals(bytes, real.read(key))
                assertTrue(listed(service.list(elder, elder.id)).isEmpty())
                assertTrue(listed(service.list(caregiver, elder.id)).isEmpty())
                inaccessible(service.content(elder, staging.photoId.toString()))
                inaccessible(service.content(caregiver, staging.photoId.toString()))
                return stored
            }
        }
        service = service(storage = observed)
        val view = uploaded(service.upload(elder, elder.id, operation, bytes, "image/png"))
        assertEquals(listOf(view), listed(service.list(caregiver, elder.id)))
    }

    @Test fun `uncertain successful object write recovers with the same photo identity and one ready result`() {
        val operation = key()
        val bytes = image()
        val real = objects()
        val loseResponse = AtomicBoolean(true)
        val uncertain = object : PhotoObjectStore by real {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey {
                val stored = real.putIfAbsent(key, source)
                if (loseResponse.compareAndSet(true, false)) throw PhotoObjectException(PhotoObjectFailure.UNAVAILABLE)
                return stored
            }
        }
        rejected(service(storage = uncertain).upload(elder, elder.id, operation, bytes, "image/png"), PhotoBusinessRejection.SERVICE_UNAVAILABLE)
        val staging = operation(operation)
        assertEquals(PhotoUploadState.STAGING, staging.state)
        assertNull(staging.receipt)
        assertEquals(listOf("${staging.photoId}.blob"), objectNames())
        assertTrue(listed(service().list(elder, elder.id)).isEmpty())
        clock.advance(5_000)
        val view = uploaded(service().upload(elder, elder.id, operation, bytes, "image/png"))
        assertEquals(staging.photoId.toString(), view.id)
        assertEquals(clock.millis(), view.createdAtEpochMillis)
        assertEquals(listOf(view), listed(service().list(elder, elder.id)))
        assertEquals(listOf("${view.id}.blob"), objectNames())
        assertArrayEquals(bytes, content(service().content(elder, view.id)).bytes)
    }

    @ParameterizedTest @ValueSource(strings = ["publication", "receipt"])
    fun `failure after ready metadata or receipt write rolls back both and same intent recovers`(failurePoint: String) {
        val operation = key()
        val bytes = image()
        val fail = AtomicBoolean(true)
        val broken = object : PhotoMetadataStore by store {
            override fun <T> photoTransaction(work: (PhotoMetadataTransaction) -> T): T = store.photoTransaction { tx ->
                work(object : PhotoMetadataTransaction by tx {
                    override fun publishPhoto(value: ReadyMedicationPhoto) {
                        tx.publishPhoto(value)
                        if (failurePoint == "publication" && fail.compareAndSet(true, false)) error("Injected publication failure")
                    }
                    override fun putPhotoOperation(key: PhotoUploadKey, value: PhotoUploadOperation) {
                        tx.putPhotoOperation(key, value)
                        if (value.state == PhotoUploadState.READY && failurePoint == "receipt" && fail.compareAndSet(true, false))
                            error("Injected receipt failure")
                    }
                })
            }
        }
        rejected(service(metadata = broken).upload(elder, elder.id, operation, bytes, "image/png"), PhotoBusinessRejection.SERVICE_UNAVAILABLE)
        val staging = operation(operation)
        assertEquals(PhotoUploadState.STAGING, staging.state)
        assertNull(staging.receipt)
        assertNull(store.photoTransaction { it.readyPhoto(staging.photoId.toString()) })
        assertTrue(listed(service().list(elder, elder.id)).isEmpty())
        assertEquals(listOf("${staging.photoId}.blob"), objectNames())
        inaccessible(service().content(elder, staging.photoId.toString()))
        clock.advance(1_000)
        val view = uploaded(service().upload(elder, elder.id, operation, bytes, "image/png"))
        assertEquals(staging.photoId.toString(), view.id)
        assertEquals(view, operation(operation).receipt)
        assertEquals(listOf(view), listed(service().list(elder, elder.id)))
    }

    @Test fun `accepted replay retains original time and controlled mime after service recreation`() {
        val operation = key()
        val bytes = image()
        val view = uploaded(service().upload(elder, elder.id, operation, bytes, "image/png"))
        clock.advance(31L * 24 * 60 * 60 * 1_000)
        assertEquals(view, uploaded(service().upload(elder, elder.id, operation, bytes, "IMAGE/PNG; profile=fictional")))
        assertEquals(listOf(view), listed(service().list(elder, elder.id)))
        assertEquals(listOf("${view.id}.blob"), objectNames())
    }

    @Test fun `accepted key cannot change bytes mime installation or target and original bytes remain immutable`() {
        val operation = key()
        val bytes = image()
        val service = service()
        val view = uploaded(service.upload(elder, elder.id, operation, bytes, "image/png"))
        rejected(service.upload(elder, elder.id, operation, image(0x00ff00), "image/png"), PhotoBusinessRejection.IDEMPOTENCY_KEY_REUSED)
        rejected(service.upload(elder, elder.id, operation, bytes, "image/jpeg"), PhotoBusinessRejection.IDEMPOTENCY_KEY_REUSED)
        rejected(service.upload(elder.copy(installationId = "changed-installation"), elder.id, operation, bytes, "image/png"),
            PhotoBusinessRejection.IDEMPOTENCY_KEY_REUSED)
        inaccessible(service.upload(elder, otherElder.id, operation, bytes, "image/png"))
        assertEquals(view, uploaded(service.upload(elder, elder.id, operation, bytes, "image/png")))
        assertArrayEquals(bytes, content(service.content(elder, view.id)).bytes)
        assertEquals(listOf(view), listed(service.list(elder, elder.id)))
    }

    @Test fun `rejected intent preserves its binding and never becomes accepted when payload is corrected under its key`() {
        val operation = key()
        val invalid = byteArrayOf(1, 2, 3)
        val service = service()
        val first = rejected(service.upload(elder, elder.id, operation, invalid, "image/png"), PhotoBusinessRejection.VALIDATION_FAILED)
        assertEquals(PhotoInputRejection.INVALID_IMAGE, first.inputReason)
        val original = operation(operation)
        assertEquals(PhotoUploadState.REJECTED, original.state)
        assertEquals(PhotoInputRejection.INVALID_IMAGE, original.rejection)
        assertNull(original.receipt)
        assertEquals(first, service.upload(elder, elder.id, operation, invalid, "image/png"))
        assertEquals(original.photoId, operation(operation).photoId)
        rejected(service.upload(elder, elder.id, operation, image(), "image/png"), PhotoBusinessRejection.IDEMPOTENCY_KEY_REUSED)
        rejected(service.upload(elder, elder.id, operation, invalid, "image/jpeg"), PhotoBusinessRejection.IDEMPOTENCY_KEY_REUSED)
        assertTrue(objectNames().isEmpty())
        assertTrue(listed(service.list(elder, elder.id)).isEmpty())
        val corrected = uploaded(service.upload(elder, elder.id, key(), image(), "image/png"))
        assertNotEquals(original.photoId.toString(), corrected.id)
        assertEquals(PhotoUploadState.REJECTED, operation(operation).state)
        assertNull(operation(operation).receipt)
    }

    @Test fun `concurrent identical intents converge on one immutable photo and accepted receipt`() {
        val operation = key()
        val bytes = image()
        objects()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { pool.submit(Callable {
                val service = service()
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                service.upload(elder, elder.id, operation, bytes, "image/png")
            }) }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { uploaded(it.get(15, TimeUnit.SECONDS)) }
            assertEquals(1, results.distinct().size)
            assertEquals(listOf(results.first()), listed(service().list(elder, elder.id)))
            assertEquals(results.first(), operation(operation).receipt)
            assertEquals(listOf("${results.first().id}.blob"), objectNames())
            assertArrayEquals(bytes, content(service().content(elder, results.first().id)).bytes)
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun `ready content detects missing or corrupt objects without exposing private bytes`() {
        val bytes = image()
        val service = service()
        val view = uploaded(service.upload(elder, elder.id, key(), bytes, "image/png"))
        val path = root.resolve("${view.id}.blob")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        Files.write(path, bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
        rejected(service.content(elder, view.id), PhotoBusinessRejection.SERVICE_UNAVAILABLE)
        Files.delete(path)
        rejected(service.content(elder, view.id), PhotoBusinessRejection.SERVICE_UNAVAILABLE)
        assertEquals(listOf(view), listed(service.list(elder, elder.id)))
    }

    @Test fun `caller mutation of a returned content array does not change stored photo bytes`() {
        val bytes = image()
        val service = service()
        val view = uploaded(service.upload(elder, elder.id, key(), bytes, "image/png"))
        val first = content(service.content(elder, view.id))
        assertEquals("image/png", first.contentType)
        first.bytes.fill(0)
        assertArrayEquals(bytes, content(service.content(elder, view.id)).bytes)
    }

    @Test fun `bounded metadata lock failure leaves no intent and retry can complete`() {
        val bounded = InMemoryPairingCodeStore(0)
        val service = service(metadata = bounded)
        val operation = key()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit { bounded.photoTransaction { held.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            rejected(service.upload(elder, elder.id, operation, image(), "image/png"), PhotoBusinessRejection.OPERATION_IN_PROGRESS)
            assertTrue(objectNames().isEmpty())
        } finally {
            release.countDown()
            future.get(10, TimeUnit.SECONDS)
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertNull(bounded.photoTransaction { it.photoOperation(PhotoUploadKey(elder.id, operation)) })
        uploaded(service.upload(elder, elder.id, operation, image(), "image/png"))
    }

    private fun pendingPairing(): String {
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store, clock) { "01357924" }.issue(elder, key())).view
        return assertInstanceOf(PairingRequestResult.Success::class.java,
            PairingRequestService(store, clock).request(caregiver, key(), RequestPairingRequest(code.code),
                InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 45)))).view.id
    }
    private fun paired(): CareRelationshipView = relationship(PairingDecisionService(store, clock).accept(elder, pendingPairing(), key()))
    private fun relationship(result: PairingDecisionResult) = assertInstanceOf(PairingDecisionResult.Relationship::class.java, result).view
    private fun uploaded(result: PhotoBusinessResult): MedicationPhotoView = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java, result).view
    private fun listed(result: PhotoBusinessResult) = assertInstanceOf(PhotoBusinessResult.Listed::class.java, result).items
    private fun content(result: PhotoBusinessResult) = assertInstanceOf(PhotoBusinessResult.Content::class.java, result)
    private fun rejected(result: PhotoBusinessResult, expected: PhotoBusinessRejection) =
        assertInstanceOf(PhotoBusinessResult.Rejected::class.java, result).also { assertEquals(expected, it.reason) }
    private fun inaccessible(result: PhotoBusinessResult) = rejected(result, PhotoBusinessRejection.RESOURCE_NOT_ACCESSIBLE)
    private fun operation(id: UUID) = checkNotNull(store.photoTransaction { it.photoOperation(PhotoUploadKey(elder.id, id)) })
    private fun objectNames() = Files.list(root).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
    private fun image(color: Int = 0xff0000): ByteArray {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        return try {
            for (x in 0 until 3) for (y in 0 until 2) image.setRGB(x, y, color)
            ByteArrayOutputStream().use { output -> check(ImageIO.write(image, "png", output)); output.toByteArray() }
        } finally { image.flush() }
    }
    private class PhotoClock : Clock() {
        private val now = AtomicLong(Instant.parse("2026-10-20T10:00:00Z").toEpochMilli())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now.get())
        override fun millis(): Long = now.get()
        fun advance(millis: Long) { now.addAndGet(millis) }
    }
}
