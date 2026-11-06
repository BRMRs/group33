package com.group33.server.photo

import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.server.photo.storage.PhotoObjectKey
import com.group33.server.photo.storage.PhotoObjectStore
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.MedicationPhotoView
import com.group33.shared.contract.RequestPairingRequest
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
class PostgresMedicationPhotoTest {
    @TempDir lateinit var temporary: Path
    private val schema = "s2_photo_it_" + UUID.randomUUID().toString().replace("-", "")
    private val elder = AnonymousUser("fictional-photo-elder", "fictional-photo-installation", "虚构长者", UserRole.ELDER)
    private val caregiver = AnonymousUser("fictional-photo-caregiver", "fictional-caregiver-installation", "虚构照护者", UserRole.CAREGIVER)
    private val nextCaregiver = caregiver.copy(id = "fictional-next-caregiver", installationId = "fictional-next-installation")
    private val clock = PhotoClock()
    private val codeSequence = AtomicInteger()
    private lateinit var source: PGSimpleDataSource
    private lateinit var cipherKey: ByteArray
    private val root get() = temporary.resolve("objects")

    @BeforeEach fun prepare() {
        cipherKey = Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))
        source = dataSource(schema)
        dataSource().connection.use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") } }
        installSchema()
        objects()
    }

    @AfterEach fun cleanup() {
        dataSource().connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    private fun installSchema() {
        val ddl = listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql",
            "pairing-directory-store-v4.sql", "anonymous-identity-store-v5.sql", "medication-photo-store-v6.sql")
            .joinToString("\n") { checkNotNull(javaClass.getResource("/db/prototypes/$it")).readText() }
        source.connection.use { connection ->
            connection.autoCommit = false
            ddl.split(';').filter { it.isNotBlank() }.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
            connection.commit()
        }
    }

    private fun store() = PostgresPairingCodeStore(dataSource(schema), PairingReceiptCipher(cipherKey), 15_000)
    private fun objects() = LocalFilePhotoObjectStore(root)
    private fun service(storage: PhotoObjectStore = objects()) = MedicationPhotoService(store(), storage, clock)
    private fun upload(operation: UUID = UUID.randomUUID(), bytes: ByteArray = image()) =
        uploaded(service().upload(elder, elder.id, operation, bytes, "image/png"))

    @Test fun `original success receipt survives independent store and object store reconstruction`() {
        val operation = UUID.randomUUID()
        val bytes = image()
        val original = upload(operation, bytes)
        clock.advance(86_400_000)
        val newer = upload(bytes = image(0x00ff00))
        assertNotEquals(original.id, newer.id)
        clock.advance(86_400_000)
        val replay = uploaded(service().upload(elder, elder.id, operation, bytes, "IMAGE/PNG; profile=fictional"))
        assertEquals(original, replay)
        assertEquals(original, operation(operation).receipt)
        assertEquals(original.createdAtEpochMillis.toString(), scalar(
            "SELECT receipt_created_at FROM photo_upload_operation WHERE operation_id = '$operation'::uuid"))
        assertEquals(listOf(original, newer), listed(service().list(elder, elder.id)))
        assertArrayEquals(bytes, content(service().content(elder, original.id)).bytes)
        assertEquals(listOf(2, 2), counts())
        assertEquals(2, objectNames().size)
    }

    @Test fun `accepted photo receipt and private bytes survive an actual PostgreSQL restart`() {
        val operation = UUID.randomUUID()
        val bytes = image()
        val original = upload(operation, bytes)
        fun control(action: String) {
            val child = ProcessBuilder(required("S2_PG_PYTHON"), required("S2_PG_MANAGER_SCRIPT"), action)
                .redirectErrorStream(true).start()
            try {
                assertTrue(child.waitFor(40, TimeUnit.SECONDS), "Local database control did not finish")
                assertEquals(0, child.exitValue(), "Local database control failed")
            } finally { if (child.isAlive) child.destroyForcibly() }
        }
        try { control("stop") } finally { control("start") }
        clock.advance(120_000)
        assertEquals(original, upload(operation, bytes))
        assertEquals(original, operation(operation).receipt)
        assertEquals(listOf(original), listed(service().list(elder, elder.id)))
        assertArrayEquals(bytes, content(service().content(elder, original.id)).bytes)
        assertEquals(listOf(1, 1), counts())
        assertEquals(listOf("${original.id}.blob"), objectNames())
    }

    @Test fun `actual receipt SQL failure rolls back ready metadata and preserves staging recovery`() = checkPublicationRollback(false)

    @Test fun `actual deferred commit failure rolls back ready metadata and receipt together`() = checkPublicationRollback(true)

    @Test fun `slow ready receipt write is cancelled while original private staging object remains recoverable`() {
        val id = UUID.randomUUID(); val bytes = image()
        execute("""CREATE FUNCTION slow_photo_ready() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
            IF NEW.state = 'READY' THEN PERFORM pg_sleep(30); END IF; RETURN NEW; END $$""")
        execute("CREATE TRIGGER slow_photo_ready AFTER INSERT OR UPDATE ON photo_upload_operation FOR EACH ROW EXECUTE FUNCTION slow_photo_ready()")
        val started = System.nanoTime()
        rejected(service().upload(elder, elder.id, id, bytes, "image/png"), PhotoBusinessRejection.SERVICE_UNAVAILABLE)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) in 3_500..8_000)
        val staging = operation(id)
        assertEquals(PhotoUploadState.STAGING, staging.state)
        assertNull(staging.receipt)
        assertEquals(listOf(0, 1), counts())
        assertEquals(listOf("${staging.photoId}.blob"), objectNames())
        assertArrayEquals(bytes, objects().read(objectKey(staging.photoId, bytes)))
        assertTrue(listed(service().list(elder, elder.id)).isEmpty())
        execute("DROP TRIGGER slow_photo_ready ON photo_upload_operation")
        val recovered = upload(id, bytes)
        assertEquals(staging.photoId.toString(), recovered.id)
        assertEquals(recovered, upload(id, bytes))
        assertEquals(listOf(1, 1), counts())
        assertArrayEquals(bytes, content(service().content(elder, recovered.id)).bytes)
    }

    private fun checkPublicationRollback(deferred: Boolean) {
        val operation = UUID.randomUUID()
        val bytes = image()
        execute("""CREATE FUNCTION fail_fixture_photo_receipt() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN IF NEW.operation_id = '$operation'::uuid AND NEW.state = 'READY' THEN
              RAISE EXCEPTION 'fictional photo receipt failure'; END IF; RETURN NEW; END ${'$'}${'$'}""")
        val declaration = if (deferred) "CREATE CONSTRAINT TRIGGER fixture_photo_failure AFTER INSERT OR UPDATE"
            else "CREATE TRIGGER fixture_photo_failure BEFORE INSERT OR UPDATE"
        val timing = if (deferred) "DEFERRABLE INITIALLY DEFERRED" else ""
        execute("$declaration ON photo_upload_operation $timing FOR EACH ROW EXECUTE FUNCTION fail_fixture_photo_receipt()")
        rejected(service().upload(elder, elder.id, operation, bytes, "image/png"), PhotoBusinessRejection.SERVICE_UNAVAILABLE)
        val staging = operation(operation)
        assertEquals(PhotoUploadState.STAGING, staging.state)
        assertNull(staging.receipt)
        assertNull(staging.rejection)
        assertEquals(listOf(0, 1), counts())
        assertNull(store().photoTransaction { it.readyPhoto(staging.photoId.toString()) })
        assertTrue(listed(service().list(elder, elder.id)).isEmpty())
        inaccessible(service().content(elder, staging.photoId.toString()))
        assertEquals(listOf("${staging.photoId}.blob"), objectNames())
        assertArrayEquals(bytes, objects().read(objectKey(staging.photoId, bytes)))
        execute("DROP TRIGGER fixture_photo_failure ON photo_upload_operation")
        clock.advance(1_000)
        val recovered = upload(operation, bytes)
        assertEquals(staging.photoId.toString(), recovered.id)
        assertEquals(clock.millis(), recovered.createdAtEpochMillis)
        assertEquals(recovered, operation(operation).receipt)
        assertEquals(listOf(recovered), listed(service().list(elder, elder.id)))
        assertArrayEquals(bytes, content(service().content(elder, recovered.id)).bytes)
        assertEquals(listOf(1, 1), counts())
        assertEquals(listOf("${recovered.id}.blob"), objectNames())
    }

    @Test fun `real pairing acceptance and current revocation govern existing photo access`() {
        val bytes = image()
        val view = upload(bytes = bytes)
        val photos = service()
        val request = pendingPairing()
        inaccessible(photos.list(caregiver, elder.id))
        inaccessible(photos.content(caregiver, view.id))
        val acceptance = UUID.randomUUID()
        val accepted = relationship(PairingDecisionService(store(), clock).accept(elder, request, acceptance))
        assertEquals(listOf(view), listed(photos.list(caregiver, elder.id)))
        assertArrayEquals(bytes, content(photos.content(caregiver, view.id)).bytes)
        relationship(PairingDecisionService(store(), clock).revoke(caregiver, accepted.id, UUID.randomUUID()))
        assertEquals(accepted, relationship(PairingDecisionService(store(), clock).accept(elder, request, acceptance)))
        inaccessible(photos.list(caregiver, elder.id))
        inaccessible(photos.content(caregiver, view.id))
        inaccessible(photos.content(caregiver, UUID.randomUUID().toString()))
        relationship(PairingDecisionService(store(), clock).accept(elder, pendingPairing(nextCaregiver), UUID.randomUUID()))
        assertEquals(listOf(view), listed(photos.list(nextCaregiver, elder.id)))
        assertArrayEquals(bytes, content(photos.content(nextCaregiver, view.id)).bytes)
        inaccessible(photos.content(caregiver, view.id))
        assertArrayEquals(bytes, content(photos.content(elder, view.id)).bytes)
    }

    @Test fun `private content read and relationship revocation serialize on the same database guard`() {
        val bytes = image()
        val view = upload(bytes = bytes)
        val accepted = relationship(PairingDecisionService(store(), clock).accept(elder, pendingPairing(), UUID.randomUUID()))
        val real = objects()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heldObjects = object : PhotoObjectStore by real {
            override fun read(key: PhotoObjectKey): ByteArray {
                reading.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return real.read(key)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val readingPhoto = pool.submit(Callable { service(heldObjects).content(caregiver, view.id) })
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val revoke = pool.submit(Callable {
                PairingDecisionService(store(), clock).revoke(caregiver, accepted.id, UUID.randomUUID())
            })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name = '$schema' AND wait_event_type = 'Lock'") == "0") {
                check(System.nanoTime() < deadline) { "No actual revocation lock waiter observed" }
                Thread.sleep(10)
            }
            assertFalse(revoke.isDone)
            release.countDown()
            assertArrayEquals(bytes, content(readingPhoto.get(10, TimeUnit.SECONDS)).bytes)
            relationship(revoke.get(10, TimeUnit.SECONDS))
            inaccessible(service().content(caregiver, view.id))
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun `eight independent stores converge on one photo object and immutable operation receipt`() {
        val operation = UUID.randomUUID()
        val bytes = image()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val uploads = (0 until 8).map { pool.submit(Callable {
                val photos = service()
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                photos.upload(elder, elder.id, operation, bytes, "image/png")
            }) }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = uploads.map { uploaded(it.get(20, TimeUnit.SECONDS)) }
            assertEquals(1, results.distinct().size)
            assertEquals(results.first(), operation(operation).receipt)
            assertEquals(listOf(results.first()), listed(service().list(elder, elder.id)))
            assertEquals(listOf(1, 1), counts())
            assertEquals(listOf("${results.first().id}.blob"), objectNames())
            assertArrayEquals(bytes, content(service().content(elder, results.first().id)).bytes)
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun `database constraints reject incomplete ready state and a receipt without metadata`() {
        val operation = UUID.randomUUID()
        val photo = UUID.randomUUID()
        assertSqlState("23514") {
            execute("""INSERT INTO photo_upload_operation
                (actor_id, operation_id, installation_id, elder_id, fingerprint, photo_id, state)
                VALUES ('${elder.id}', '$operation', '${elder.installationId}', '${elder.id}', '${fingerprint()}', '$photo', 'READY')""")
        }
        assertSqlState("23503") {
            execute("""INSERT INTO photo_upload_operation
                (actor_id, operation_id, installation_id, elder_id, fingerprint, photo_id, state,
                 receipt_photo_id, receipt_elder_id, receipt_content_type, receipt_created_at)
                VALUES ('${elder.id}', '$operation', '${elder.installationId}', '${elder.id}', '${fingerprint()}', '$photo', 'READY',
                 '$photo', '${elder.id}', 'image/png', ${clock.millis()})""")
        }
        assertEquals(listOf(0, 0), counts())
        assertTrue(objectNames().isEmpty())
    }

    @Test fun `database key and unique photo reservation constraints prevent conflicting new bindings`() {
        val operation = UUID.randomUUID()
        val photo = UUID.randomUUID()
        val original = PhotoUploadOperation(elder.installationId, elder.id, fingerprint(), photo, PhotoUploadState.STAGING)
        store().photoTransaction { it.putPhotoOperation(PhotoUploadKey(elder.id, operation), original) }
        fun insert(nextOperation: UUID, installation: String) = execute("""INSERT INTO photo_upload_operation
            (actor_id, operation_id, installation_id, elder_id, fingerprint, photo_id, state)
            VALUES ('${elder.id}', '$nextOperation', '$installation', '${elder.id}', '${fingerprint()}', '$photo', 'STAGING')""")
        assertSqlState("23505") { insert(operation, "fictional-changed-installation") }
        assertSqlState("23505") { insert(UUID.randomUUID(), elder.installationId) }
        assertEquals(elder.installationId, operation(operation).installationId)
        assertEquals(photo, operation(operation).photoId)
        assertEquals(listOf(0, 1), counts())
    }

    @Test fun `rejected retry retains its reserved identity and postgres upsert rejects all binding replacements`() {
        val operation = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3)
        val originalResult = rejected(service().upload(elder, elder.id, operation, bytes, "image/png"), PhotoBusinessRejection.VALIDATION_FAILED)
        val original = operation(operation)
        assertEquals(PhotoUploadState.REJECTED, original.state)
        assertEquals(originalResult, service().upload(elder, elder.id, operation, bytes, "image/png"))
        assertEquals(original.photoId, operation(operation).photoId)
        val replacements = listOf(
            PhotoUploadOperation("fictional-changed-installation", original.elderId, original.fingerprint, original.photoId,
                original.state, rejection = original.rejection),
            PhotoUploadOperation(original.installationId, "fictional-changed-elder", original.fingerprint, original.photoId,
                original.state, rejection = original.rejection),
            PhotoUploadOperation(original.installationId, original.elderId, fingerprint("changed"), original.photoId,
                original.state, rejection = original.rejection),
            PhotoUploadOperation(original.installationId, original.elderId, original.fingerprint, UUID.randomUUID(),
                original.state, rejection = original.rejection),
        )
        replacements.forEach { replacement ->
            assertThrows(IllegalStateException::class.java) {
                store().photoTransaction { it.putPhotoOperation(PhotoUploadKey(elder.id, operation), replacement) }
            }
        }
        rejected(service().upload(elder, elder.id, operation, image(), "image/png"), PhotoBusinessRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(original.fingerprint, operation(operation).fingerprint)
        assertEquals(original.photoId, operation(operation).photoId)
        assertEquals(listOf(0, 1), counts())
        assertTrue(objectNames().isEmpty())
    }

    @Test fun `ready success cannot be downgraded or replace its original metadata receipt or object key`() {
        val operation = UUID.randomUUID()
        val bytes = image()
        val view = upload(operation, bytes)
        val original = operation(operation)
        val key = PhotoUploadKey(elder.id, operation)
        listOf(PhotoUploadState.STAGING, PhotoUploadState.REJECTED).forEach { state ->
            assertThrows(IllegalStateException::class.java) {
                store().photoTransaction { it.putPhotoOperation(key, PhotoUploadOperation(original.installationId, original.elderId,
                    original.fingerprint, original.photoId, state,
                    rejection = if (state == PhotoUploadState.REJECTED) PhotoInputRejection.INVALID_IMAGE else null)) }
            }
        }
        val replacement = view.copy(createdAtEpochMillis = view.createdAtEpochMillis + 1)
        assertThrows(IllegalStateException::class.java) {
            store().photoTransaction { it.putPhotoOperation(key, PhotoUploadOperation(original.installationId, original.elderId,
                original.fingerprint, original.photoId, PhotoUploadState.READY, receipt = replacement)) }
        }
        assertThrows(IllegalStateException::class.java) {
            store().photoTransaction { it.publishPhoto(ReadyMedicationPhoto(replacement, objectKey(original.photoId, bytes))) }
        }
        assertThrows(IllegalStateException::class.java) {
            store().photoTransaction { it.publishPhoto(ReadyMedicationPhoto(view,
                PhotoObjectKey(original.photoId, bytes.size.toLong(), "0".repeat(64)))) }
        }
        assertEquals(view, operation(operation).receipt)
        assertEquals(view, store().photoTransaction { it.readyPhoto(view.id) }?.view)
        assertArrayEquals(bytes, content(service().content(elder, view.id)).bytes)
        assertEquals(listOf(1, 1), counts())
    }

    @Test fun `v1 through v6 schema can be reapplied without losing accepted photo evidence`() {
        val operation = UUID.randomUUID()
        val bytes = image()
        val original = upload(operation, bytes)
        installSchema()
        assertEquals(original, upload(operation, bytes))
        assertEquals(original, operation(operation).receipt)
        assertEquals(listOf(1, 1), counts())
        assertArrayEquals(bytes, content(service().content(elder, original.id)).bytes)
    }

    private fun pendingPairing(who: AnonymousUser = caregiver): String {
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store(), clock) { codeSequence.incrementAndGet().toString().padStart(8, '0') }
                .issue(elder, UUID.randomUUID())).view
        return assertInstanceOf(PairingRequestResult.Success::class.java,
            PairingRequestService(store(), clock).request(who, UUID.randomUUID(), RequestPairingRequest(code.code),
                InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 45)))).view.id
    }
    private fun relationship(result: PairingDecisionResult): CareRelationshipView =
        assertInstanceOf(PairingDecisionResult.Relationship::class.java, result).view
    private fun uploaded(result: PhotoBusinessResult): MedicationPhotoView = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java, result).view
    private fun listed(result: PhotoBusinessResult) = assertInstanceOf(PhotoBusinessResult.Listed::class.java, result).items
    private fun content(result: PhotoBusinessResult) = assertInstanceOf(PhotoBusinessResult.Content::class.java, result)
    private fun rejected(result: PhotoBusinessResult, reason: PhotoBusinessRejection) =
        assertInstanceOf(PhotoBusinessResult.Rejected::class.java, result).also { assertEquals(reason, it.reason) }
    private fun inaccessible(result: PhotoBusinessResult) = rejected(result, PhotoBusinessRejection.RESOURCE_NOT_ACCESSIBLE)
    private fun operation(id: UUID) = checkNotNull(store().photoTransaction { it.photoOperation(PhotoUploadKey(elder.id, id)) })
    private fun counts() = listOf("medication_photo", "photo_upload_operation").map { scalar("SELECT count(*) FROM $it").toInt() }
    private fun objectNames() = Files.list(root).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
    private fun fingerprint(value: String = "fictional photo intent") = PairingCodeIssuanceService.hash(value)
    private fun objectKey(id: UUID, bytes: ByteArray) = PhotoObjectKey(id, bytes.size.toLong(),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
    private fun execute(sql: String) { source.connection.use { it.createStatement().use { statement -> statement.execute(sql) } } }
    private fun scalar(sql: String): String = source.connection.use {
        it.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) } }
    }
    private fun assertSqlState(expected: String, work: () -> Unit) {
        assertEquals(expected, assertThrows(SQLException::class.java) { work() }.sqlState)
    }
    private fun image(color: Int = 0xff0000): ByteArray {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        return try {
            for (x in 0 until 3) for (y in 0 until 2) image.setRGB(x, y, color)
            ByteArrayOutputStream().use { output -> check(ImageIO.write(image, "png", output)); output.toByteArray() }
        } finally { image.flush() }
    }
    private class PhotoClock : Clock() {
        private val time = AtomicLong(Instant.parse("2026-10-20T10:00:00Z").toEpochMilli())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun millis(): Long = time.get()
        fun advance(millis: Long) { time.addAndGet(millis) }
    }
}
