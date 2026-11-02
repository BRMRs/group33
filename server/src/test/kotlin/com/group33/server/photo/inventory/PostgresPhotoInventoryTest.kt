package com.group33.server.photo.inventory

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
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
class PostgresPhotoInventoryTest {
    @TempDir lateinit var temporary: Path
    private val schema = "s2_photo_inventory_" + UUID.randomUUID().toString().replace("-", "")
    private val elder = AnonymousUser("fictional-inventory-elder","fictional-inventory-installation","Fictional",UserRole.ELDER)
    private val now = Instant.parse("2026-01-03T12:00:00Z")
    private lateinit var owner: PGSimpleDataSource
    private lateinit var reader: PGSimpleDataSource
    private val readerRole get() = required("S2_PG_PHOTO_READER_USER")
    private val root get() = temporary.resolve("objects")

    @BeforeEach fun prepare() {
        require(readerRole.matches(Regex("s2_photo_reader_[0-9a-f]+")))
        dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        owner = dataSource(schema); SchemaMigrations(owner,schema).migrate()
        LocalFilePhotoObjectStore(root)
        reader = dataSource(schema).apply { user = readerRole; password = required("S2_PG_PHOTO_READER_PASSWORD") }
        grantReader()
    }
    @AfterEach fun cleanup() {
        dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }
    private fun sql(query: String) = owner.connection.use { it.createStatement().use { s -> s.execute(query) } }
    private fun grantReader() { sql("GRANT USAGE ON SCHEMA $schema TO $readerRole; GRANT SELECT ON ALL TABLES IN SCHEMA $schema TO $readerRole") }
    private fun scalar(query: String) = owner.connection.use { it.createStatement().use { s -> s.executeQuery(query).use { row -> check(row.next()); row.getString(1) } } }
    private fun states() = listOf("medication_photo","photo_upload_operation","medication").joinToString("|") {
        scalar("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]') FROM $it t")
    }
    private fun file(id: UUID, suffix: String = ".blob") = Files.write(root.resolve("$id$suffix"),byteArrayOf(1,2,3)).also {
        Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rw-------"))
    }
    private fun image() = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",it) }.toByteArray()
    private fun service(objects: PhotoObjectStore = LocalFilePhotoObjectStore(root)): MedicationPhotoService {
        val cipher = PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE"))))
        return MedicationPhotoService(PostgresPairingCodeStore(owner,cipher),objects,Clock.fixed(now,ZoneOffset.UTC))
    }
    private fun upload(operation: UUID = UUID.randomUUID()): MedicationPhotoView {
        val result = service().upload(elder,elder.id,operation,image(),"image/png")
        assertTrue(result is PhotoBusinessResult.Uploaded)
        return (result as PhotoBusinessResult.Uploaded).view
    }
    private fun stage(operation: UUID): String {
        val objects = LocalFilePhotoObjectStore(root)
        val uncertain = object : PhotoObjectStore {
            override fun putIfAbsent(key: PhotoObjectKey, source: InputStream) = objects.putIfAbsent(key,source)
            override fun read(key: PhotoObjectKey): ByteArray = throw PhotoObjectException(PhotoObjectFailure.UNAVAILABLE)
        }
        val result = service(uncertain).upload(elder,elder.id,operation,image(),"image/png")
        assertEquals(PhotoBusinessRejection.SERVICE_UNAVAILABLE,(result as PhotoBusinessResult.Rejected).reason)
        return scalar("SELECT photo_id FROM photo_upload_operation WHERE operation_id = '$operation'")
    }
    private fun inspect(limits: PhotoInventoryLimits = PhotoInventoryLimits(), source: PhotoInventorySource = JdbcPhotoInventorySource(reader,schema)) =
        PhotoInventoryService(source,LocalPhotoInventory(root)).inspect(limits)

    @Test fun `real published objects candidates and parts are classified with every row and private file unchanged`() {
        val published = upload(); val residue = file(UUID.randomUUID())
        val part = Files.write(root.resolve(".upload-fictional.part"),byteArrayOf(4,5)).also {
            Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rw-------"))
        }
        val files = listOf(root.resolve("${published.id}.blob"),residue,part)
        files.forEach { Files.setLastModifiedTime(it,FileTime.fromMillis(1000)) }
        val rows = states(); val bytes = files.map { Files.readAllBytes(it) }; val permissions = files.map { Files.getPosixFilePermissions(it) }
        val report = inspect(PhotoInventoryLimits(batchSize = 1))
        assertEquals(InventoryCompletion.COMPLETE,report.completion)
        assertEquals(1,report.referenced); assertEquals(1,report.unmatchedCandidates); assertEquals(1,report.temporary)
        assertEquals(rows,states()); files.forEachIndexed { i,p ->
            assertArrayEquals(bytes[i],Files.readAllBytes(p)); assertEquals(FileTime.fromMillis(1000),Files.getLastModifiedTime(p))
            assertEquals(permissions[i],Files.getPosixFilePermissions(p)); assertFalse(report.json().contains(p.toString()))
        }
        assertFalse(report.json().contains(published.id)); assertFalse(report.json().contains(elder.id))
        assertTrue(report.json().contains("\"deletionAuthorized\":false"))
    }

    @Test fun `older than 24 hours unknown upload stays reserved and the same original key recovers its photo and receipt`() {
        val operation = UUID.randomUUID(); val id = stage(operation); val path = root.resolve("$id.blob")
        Files.setLastModifiedTime(path,FileTime.fromMillis(now.minusSeconds(172_800).toEpochMilli()))
        val before = states(); val bytes = Files.readAllBytes(path); val modified = Files.getLastModifiedTime(path)
        val report = inspect()
        assertEquals(InventoryCompletion.COMPLETE,report.completion); assertEquals(1,report.stagingProtected); assertEquals(0,report.unmatchedCandidates)
        assertEquals(before,states()); assertArrayEquals(bytes,Files.readAllBytes(path)); assertEquals(modified,Files.getLastModifiedTime(path))
        val recovered = upload(operation); assertEquals(id,recovered.id); assertEquals(recovered,upload(operation))
        assertEquals(1,inspect().referenced); assertEquals("1",scalar("SELECT count(*) FROM photo_upload_operation"))
    }

    @Test fun `rejected operation identity protects a matching residual object without erasing rejection evidence`() {
        val operation = UUID.randomUUID()
        val result = service().upload(elder,elder.id,operation,byteArrayOf(1),"image/png")
        assertTrue(result is PhotoBusinessResult.Rejected)
        val id = UUID.fromString(scalar("SELECT photo_id FROM photo_upload_operation WHERE operation_id = '$operation'"))
        val residue = file(id); val before = states()
        val report = inspect()
        assertEquals(InventoryCompletion.COMPLETE,report.completion); assertEquals(1,report.rejectedProtected); assertEquals(0,report.unmatchedCandidates)
        assertEquals(before,states()); assertArrayEquals(byteArrayOf(1,2,3),Files.readAllBytes(residue))
    }

    @Test fun `medication reference protects an object even when published metadata is absent in a diagnostic view`() {
        val id = UUID.randomUUID(); file(id)
        // Deliberately incomplete diagnostic view, not a claim that the normal FK permits this state.
        sql("ALTER TABLE medication RENAME TO inventory_original_medication")
        sql("CREATE VIEW medication AS SELECT '$id'::uuid AS photo_id")
        grantReader(); val before = states()
        val report = inspect()
        assertEquals(InventoryCompletion.COMPLETE,report.completion); assertEquals(1,report.referenced); assertEquals(0,report.unmatchedCandidates)
        assertEquals(before,states())
    }

    @Test fun `retained accepted receipt protects an object with no ready row in a diagnostic view`() {
        val id = UUID.randomUUID(); file(id)
        sql("ALTER TABLE photo_upload_operation RENAME TO inventory_original_operations")
        sql("CREATE VIEW photo_upload_operation AS SELECT '$id'::uuid AS photo_id, '$id'::uuid AS receipt_photo_id, 'READY'::text AS state")
        grantReader(); val before = states()
        val report = inspect()
        assertEquals(InventoryCompletion.COMPLETE,report.completion); assertEquals(1,report.referenced)
        assertEquals(0,report.unmatchedCandidates); assertEquals(before,states())
    }

    @Test fun `publication during matching preserves one repeatable read snapshot and original operation identity`() {
        val operation = UUID.randomUUID(); val id = stage(operation)
        val real = JdbcPhotoInventorySource(reader,schema)
        val concurrent = object : PhotoInventorySource {
            override fun <T> read(work: (PhotoInventorySnapshot) -> T) = real.read { snapshot -> work(PhotoInventorySnapshot { ids ->
                val before = snapshot.references(ids)
                val recovered = upload(operation); assertEquals(id,recovered.id)
                assertEquals(before,snapshot.references(ids))
                before
            }) }
        }
        val report = inspect(source = concurrent)
        assertEquals(InventoryCompletion.COMPLETE,report.completion); assertEquals(1,report.stagingProtected)
        assertEquals(1,inspect().referenced); assertEquals(id,upload(operation).id)
    }

    @Test fun `new real upload during matching is reported as directory change rather than complete coverage`() {
        upload(); Files.setLastModifiedTime(root,FileTime.fromMillis(1000))
        val real = JdbcPhotoInventorySource(reader,schema)
        val concurrent = object : PhotoInventorySource {
            override fun <T> read(work: (PhotoInventorySnapshot) -> T) = real.read { snapshot -> work(PhotoInventorySnapshot { ids ->
                val result = snapshot.references(ids); upload(); result
            }) }
        }
        val report = inspect(source = concurrent)
        assertEquals(InventoryCompletion.CONCURRENT_CHANGE,report.completion); assertEquals(0,report.checked)
        assertEquals(2,inspect().referenced); assertEquals("2",scalar("SELECT count(*) FROM medication_photo"))
    }

    @Test fun `database read only refuses a hidden selected write and leaves the probe table empty`() {
        val id = UUID.randomUUID(); file(id)
        sql("ALTER TABLE medication RENAME TO inventory_original_medication")
        sql("CREATE TABLE inventory_write_probe(value integer)")
        sql("""CREATE FUNCTION inventory_write(v uuid) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER AS
            ${'$'}f${'$'} BEGIN INSERT INTO $schema.inventory_write_probe VALUES (1); RETURN v; END; ${'$'}f${'$'}""")
        sql("CREATE VIEW medication AS SELECT $schema.inventory_write('$id'::uuid) AS photo_id")
        grantReader()
        val failure = assertThrows(SQLException::class.java) { JdbcPhotoInventorySource(reader,schema).read { it.references(setOf(id)) } }
        assertEquals("25006",failure.sqlState)
        assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,inspect().completion)
        assertEquals("0",scalar("SELECT count(*) FROM inventory_write_probe"))
    }

    @Test fun `real table lock is bounded and removed lock permits the original inventory`() {
        upload(); val before = states()
        owner.connection.use { held ->
            held.autoCommit = false
            held.createStatement().use { it.execute("LOCK TABLE photo_upload_operation IN ACCESS EXCLUSIVE MODE") }
            val start = System.nanoTime(); val report = inspect()
            assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,report.completion)
            assertTrue((System.nanoTime()-start)/1_000_000 < 6_000); held.rollback()
        }
        assertEquals(before,states()); assertEquals(1,inspect().referenced)
    }

    @Test fun `slow lookup is cancelled by statement budget without returning healthy empty inventory`() {
        val id = UUID.randomUUID(); file(id)
        sql("ALTER TABLE medication RENAME TO inventory_original_medication")
        sql("""CREATE FUNCTION inventory_slow(v uuid) RETURNS uuid LANGUAGE plpgsql AS
            ${'$'}f${'$'} BEGIN PERFORM pg_sleep(30); RETURN v; END; ${'$'}f${'$'}""")
        sql("CREATE VIEW medication AS SELECT $schema.inventory_slow('$id'::uuid) AS photo_id")
        grantReader()
        val start = System.nanoTime(); val report = inspect()
        assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,report.completion); assertEquals(0,report.checked)
        assertTrue((System.nanoTime()-start)/1_000_000 < 7_000)
    }

    @Test fun `owner table writer and column writer are rejected before matching`() {
        upload(); val before = states()
        assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,inspect(source = JdbcPhotoInventorySource(owner,schema)).completion)
        for (grant in listOf("INSERT","UPDATE(photo_id)")) {
            sql("GRANT $grant ON medication TO $readerRole")
            assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,inspect().completion)
            sql("REVOKE $grant ON medication FROM $readerRole")
        }
        assertEquals(before,states()); assertEquals(1,inspect().referenced)
    }

    @Test fun `missing permissions on a reference table fail even for an empty directory`() {
        val before = states(); sql("REVOKE SELECT ON medication FROM $readerRole")
        val report = inspect()
        assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,report.completion); assertEquals(0,report.checked)
        grantReader(); assertEquals(InventoryCompletion.COMPLETE,inspect().completion); assertEquals(before,states())
    }

    @Test fun `actual object count at the maximum completes while a truncated prefix stays incomplete`() {
        upload(); upload(); val before = states()
        val limited = inspect(PhotoInventoryLimits(batchSize = 1,maxEntries = 1))
        assertEquals(InventoryCompletion.LIMIT_REACHED,limited.completion); assertEquals(1,limited.checked)
        val complete = inspect(PhotoInventoryLimits(batchSize = 1,maxEntries = 2))
        assertEquals(InventoryCompletion.COMPLETE,complete.completion); assertEquals(2,complete.referenced)
        assertEquals(before,states())
    }

    @Test fun `row security hiding recovery reservations cannot yield a false unmatched candidate`() {
        stage(UUID.randomUUID()); val before = states()
        sql("ALTER TABLE photo_upload_operation ENABLE ROW LEVEL SECURITY")
        val report = inspect()
        assertEquals(InventoryCompletion.DATABASE_UNAVAILABLE,report.completion); assertEquals(0,report.checked)
        sql("ALTER TABLE photo_upload_operation DISABLE ROW LEVEL SECURITY")
        assertEquals(1,inspect().stagingProtected); assertEquals(before,states())
    }
}
