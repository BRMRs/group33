package com.group33.server.photo.reconciliation

import com.group33.server.auth.AnonymousUser
import com.group33.server.migration.SchemaMigrations
import com.group33.server.pairing.PairingReceiptCipher
import com.group33.server.pairing.PostgresPairingCodeStore
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.server.photo.*
import com.group33.server.photo.storage.*
import com.group33.shared.contract.MedicationPhotoView
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
class PostgresPhotoReconciliationTest {
    @TempDir lateinit var temporary: Path
    private val schema = "s2_photo_inspect_" + UUID.randomUUID().toString().replace("-", "")
    private val elder = AnonymousUser("fictional-inspection-elder", "fictional-inspection-installation", "虚构长者", UserRole.ELDER)
    private lateinit var owner: PGSimpleDataSource
    private lateinit var reader: PGSimpleDataSource
    private val root get() = temporary.resolve("objects")
    private val readerRole get() = required("S2_PG_PHOTO_READER_USER")
    private val now = Instant.parse("2026-01-03T12:00:00Z")

    @BeforeEach fun prepare() {
        require(readerRole.matches(Regex("s2_photo_reader_[0-9a-f]+")))
        dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        owner = dataSource(schema)
        SchemaMigrations(owner, schema).migrate()
        LocalFilePhotoObjectStore(root)
        reader = dataSource(schema).apply { user = readerRole; password = required("S2_PG_PHOTO_READER_PASSWORD") }
        grantReader()
    }

    @AfterEach fun cleanup() {
        dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    private fun grantReader() = sql("GRANT USAGE ON SCHEMA $schema TO $readerRole; GRANT SELECT ON ALL TABLES IN SCHEMA $schema TO $readerRole")
    private fun sql(sql: String) = owner.connection.use { it.createStatement().use { s -> s.execute(sql) } }
    private fun scalar(sql: String) = owner.connection.use { it.createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getString(1) } } }
    private fun states() = scalar("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY operation_id)::text, '[]') FROM photo_upload_operation t") +
        scalar("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]') FROM medication_photo t")
    private fun objectStore() = LocalFilePhotoObjectStore(root)
    private fun service(objects: PhotoObjectStore = objectStore()): MedicationPhotoService {
        val cipher = PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE"))))
        return MedicationPhotoService(PostgresPairingCodeStore(owner, cipher), objects, Clock.fixed(now, ZoneOffset.UTC))
    }
    private fun image() = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
    private fun upload(id: UUID = UUID.randomUUID()): MedicationPhotoView {
        val value = service().upload(elder, elder.id, id, image(), "image/png")
        assertTrue(value is PhotoBusinessResult.Uploaded)
        return (value as PhotoBusinessResult.Uploaded).view
    }
    private fun inspect(limits: PhotoInspectionLimits = PhotoInspectionLimits(), objects: PhotoObjectInspector = LocalPhotoObjectInspector(root)) =
        PhotoReconciliationService(JdbcPhotoSnapshotSource(reader, schema), objects).inspect(limits)

    @Test fun `select-only non-owner reads real uploaded objects and leaves all rows and files unchanged`() {
        upload(); upload()
        val before = states()
        val result = inspect(PhotoInspectionLimits(batchSize = 1))
        assertEquals(PhotoInspectionCompletion.COMPLETE, result.completion)
        assertEquals(2, result.verified); assertEquals(0, result.protectedStaging)
        assertEquals(before, states())
        reader.connection.use { c ->
            assertEquals("42501", assertThrows(SQLException::class.java) {
                c.createStatement().use { it.execute("DELETE FROM medication_photo") }
            }.sqlState)
            c.createStatement().use { s -> s.executeQuery("SELECT has_schema_privilege(current_user, '$schema', 'CREATE'), rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user").use { r ->
                assertTrue(r.next()); assertFalse(r.getBoolean(1)); assertFalse(r.getBoolean(2)); assertFalse(r.getBoolean(3))
            } }
        }
    }

    @Test fun `aged unknown write stays protected and same operation still recovers its original photo after inspection`() {
        val operation = UUID.randomUUID()
        val real = objectStore()
        val uncertain = object : PhotoObjectStore {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream) = real.putIfAbsent(key, source)
            override fun read(key: PhotoObjectKey): ByteArray = throw PhotoObjectException(PhotoObjectFailure.UNAVAILABLE)
        }
        val first = service(uncertain).upload(elder, elder.id, operation, image(), "image/png")
        assertEquals(PhotoBusinessRejection.SERVICE_UNAVAILABLE, (first as PhotoBusinessResult.Rejected).reason)
        val photo = scalar("SELECT photo_id FROM photo_upload_operation WHERE operation_id = '$operation'")
        val path = root.resolve("$photo.blob")
        val old = FileTime.fromMillis(now.minusSeconds(172_800).toEpochMilli())
        Files.setLastModifiedTime(path, old)
        val before = states(); val bytes = Files.readAllBytes(path)
        val report = inspect()
        assertEquals(PhotoInspectionCompletion.COMPLETE, report.completion)
        assertEquals(1, report.protectedStaging); assertEquals(0, report.checked)
        assertEquals(before, states()); assertArrayEquals(bytes, Files.readAllBytes(path))
        assertEquals(old, Files.getLastModifiedTime(path))
        val recovered = upload(operation)
        assertEquals(photo, recovered.id)
        assertEquals(recovered, upload(operation))
        assertEquals(1, inspect().verified)
        assertEquals("1", scalar("SELECT count(*) FROM photo_upload_operation"))
        assertEquals("1", scalar("SELECT count(*) FROM medication_photo"))
    }

    @Test fun `ready missing or corrupt bytes are reported while rejected evidence and unrelated files remain`() {
        val missing = upload(); val corrupt = upload()
        Files.delete(root.resolve("${missing.id}.blob"))
        val corruptedPath = root.resolve("${corrupt.id}.blob")
        val changed = Files.readAllBytes(corruptedPath).also { it[0] = (it[0].toInt() xor 1).toByte() }
        Files.setPosixFilePermissions(corruptedPath, PosixFilePermissions.fromString("rw-------"))
        Files.write(corruptedPath, changed)
        service().upload(elder, elder.id, UUID.randomUUID(), byteArrayOf(1), "image/png")
        val residue = Files.write(root.resolve(".upload-fictional.part"), byteArrayOf(8, 9))
        val before = states(); val report = inspect(PhotoInspectionLimits(batchSize = 1))
        assertEquals(PhotoInspectionCompletion.COMPLETE, report.completion)
        assertEquals(1, report.missing); assertEquals(1, report.integrityMismatch); assertEquals(1, report.protectedRejected)
        assertEquals(before, states()); assertArrayEquals(changed, Files.readAllBytes(corruptedPath))
        assertArrayEquals(byteArrayOf(8, 9), Files.readAllBytes(residue))
        assertEquals(corrupt, (service().upload(elder, elder.id,
            UUID.fromString(scalar("SELECT operation_id FROM photo_upload_operation WHERE photo_id = '${corrupt.id}'")), image(), "image/png") as PhotoBusinessResult.Uploaded).view)
    }

    @Test fun `concurrent staging publication between pages cannot change the original primary snapshot`() {
        upload(); upload()
        val operation = UUID.randomUUID(); val real = objectStore()
        service(object : PhotoObjectStore {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream) = real.putIfAbsent(key, source)
            override fun read(key: PhotoObjectKey): ByteArray = throw PhotoObjectException(PhotoObjectFailure.UNAVAILABLE)
        }).upload(elder, elder.id, operation, image(), "image/png")
        val first = AtomicBoolean(true); val files = LocalPhotoObjectInspector(root)
        val result = inspect(PhotoInspectionLimits(batchSize = 1), PhotoObjectInspector { key ->
            if (first.getAndSet(false)) upload(operation)
            files.inspect(key)
        })
        assertEquals(PhotoInspectionCompletion.COMPLETE, result.completion)
        assertEquals(2, result.verified); assertEquals(1, result.protectedStaging)
        assertEquals("3", scalar("SELECT count(*) FROM medication_photo"))
        assertEquals(3, inspect().verified)
    }

    @Test fun `database-enforced read only rejects a write even hidden inside a selected view`() {
        upload()
        sql("ALTER TABLE medication_photo RENAME TO inspection_original_photo")
        sql("CREATE TABLE inspection_write_probe (value integer)")
        sql("""CREATE FUNCTION inspection_attempt_write(v bigint) RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER AS
            ${'$'}f${'$'} BEGIN INSERT INTO $schema.inspection_write_probe VALUES (1); RETURN v; END; ${'$'}f${'$'}""")
        sql("CREATE VIEW medication_photo AS SELECT id, object_id, inspection_attempt_write(byte_count) AS byte_count, sha256 FROM inspection_original_photo")
        grantReader()
        val error = assertThrows(SQLException::class.java) {
            JdbcPhotoSnapshotSource(reader, schema).read { it.readyAfter(null, 2) }
        }
        assertEquals("25006", error.sqlState)
        assertEquals(PhotoInspectionCompletion.DATABASE_UNAVAILABLE, inspect().completion)
        assertEquals("0", scalar("SELECT count(*) FROM inspection_write_probe"))
    }

    @Test fun `real database lock has a bounded failure and original data is readable after release`() {
        upload(); val before = states()
        owner.connection.use { held ->
            held.autoCommit = false
            held.createStatement().use { it.execute("LOCK TABLE photo_upload_operation IN ACCESS EXCLUSIVE MODE") }
            val start = System.nanoTime()
            val report = inspect()
            assertEquals(PhotoInspectionCompletion.DATABASE_UNAVAILABLE, report.completion)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 6_000)
            held.rollback()
        }
        assertEquals(before, states()); assertEquals(1, inspect().verified)
    }

    @Test fun `slow metadata expression is cancelled by server statement budget without a false empty success`() {
        upload()
        sql("ALTER TABLE medication_photo RENAME TO inspection_original_photo")
        sql("""CREATE FUNCTION inspection_slow(v bigint) RETURNS bigint LANGUAGE plpgsql AS
            ${'$'}f${'$'} BEGIN PERFORM pg_sleep(30); RETURN v; END; ${'$'}f${'$'}""")
        sql("CREATE VIEW medication_photo AS SELECT id, object_id, inspection_slow(byte_count) AS byte_count, sha256 FROM inspection_original_photo")
        grantReader()
        val start = System.nanoTime(); val report = inspect()
        assertEquals(PhotoInspectionCompletion.DATABASE_UNAVAILABLE, report.completion)
        assertEquals(0, report.checked); assertTrue((System.nanoTime() - start) / 1_000_000 < 7_000)
        sql("DROP VIEW medication_photo; ALTER TABLE inspection_original_photo RENAME TO medication_photo")
        assertEquals(1, inspect().verified)
    }

    @Test fun `row and byte caps cannot turn a partially checked actual collection into complete`() {
        upload(); upload(); upload()
        val before = states()
        assertEquals(PhotoInspectionCompletion.LIMIT_REACHED, inspect(PhotoInspectionLimits(maxPhotos = 2)).completion)
        assertEquals(PhotoInspectionCompletion.COMPLETE, inspect(PhotoInspectionLimits(batchSize = 1, maxPhotos = 3)).completion)
        val bytes = image().size.toLong()
        val limited = inspect(PhotoInspectionLimits(maxBytes = bytes))
        assertEquals(PhotoInspectionCompletion.LIMIT_REACHED, limited.completion); assertEquals(1, limited.verified)
        assertEquals(before, states())
    }

    @Test fun `missing table or denied permissions is unavailable not a healthy empty schema`() {
        val before = states()
        sql("REVOKE SELECT ON photo_upload_operation FROM $readerRole")
        val denied = inspect()
        assertEquals(PhotoInspectionCompletion.DATABASE_UNAVAILABLE, denied.completion)
        assertEquals(0, denied.checked); assertFalse(denied.json().contains(schema))
        grantReader()
        val absent = PhotoReconciliationService(JdbcPhotoSnapshotSource(reader, "${schema}_absent"), LocalPhotoObjectInspector(root)).inspect()
        assertEquals(PhotoInspectionCompletion.DATABASE_UNAVAILABLE, absent.completion)
        assertEquals(before, states()); assertEquals(PhotoInspectionCompletion.COMPLETE, inspect().completion)
    }

    @Test fun `schema owner application credential is refused even though a read only transaction could run`() {
        upload(); val before = states()
        val report = PhotoReconciliationService(JdbcPhotoSnapshotSource(owner, schema), LocalPhotoObjectInspector(root)).inspect()
        assertEquals(PhotoInspectionCompletion.DATABASE_UNAVAILABLE, report.completion)
        assertEquals(0, report.checked); assertEquals(before, states())
        assertEquals(1, inspect().verified)
    }

    @Test fun `non owner with write grant is rejected before inspection and can recover after grant is removed`() {
        upload(); val before = states()
        sql("GRANT INSERT ON medication_photo TO $readerRole")
        val report = inspect()
        assertEquals(PhotoInspectionCompletion.DATABASE_UNAVAILABLE, report.completion)
        assertEquals(0, report.checked); assertEquals(before, states())
        sql("REVOKE INSERT ON medication_photo FROM $readerRole")
        assertEquals(1, inspect().verified)
    }
}
