package com.group33.server.recovery

import com.group33.server.auth.*
import com.group33.server.config.PersistenceRuntime
import com.group33.server.config.PersistenceRuntimeConfiguration
import com.group33.server.migration.SchemaMigrations
import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.server.photo.*
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.server.photo.storage.PhotoObjectKey
import com.group33.server.plan.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.mock.env.MockEnvironment

/** Explicit local-only recovery evidence. No cloud credentials or production restore command. */
@Tag("postgres-recovery")
internal class PostgresBusinessRecoveryTest {
    @TempDir lateinit var directory: Path
    private val suffix = UUID.randomUUID().toString().replace("-", "")
    private val schema = "s2_recovery_it_$suffix"
    private val target = "s2_restore_it_$suffix"
    private var sourceCreated = false
    private var sourceRetired = false
    private var targetCreated = false
    private val clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC)
    private val codeOperation = UUID.randomUUID()
    private val photoOperation = UUID.randomUUID()
    private val confirmationOperation = UUID.randomUUID()
    private val editOperation = UUID.randomUUID()
    private lateinit var elder: AnonymousUser
    private lateinit var caregiver: AnonymousUser
    private lateinit var relation: CareRelationshipView
    private lateinit var issued: PairingCodeView
    private lateinit var photo: MedicationPhotoView
    private lateinit var original: ConfirmReminderPlanResponse
    private lateinit var edited: UpdateReminderPlanResponse
    private lateinit var request: ConfirmReminderPlanRequest
    private lateinit var edit: UpdateReminderPlanRequest
    private lateinit var objectKey: PhotoObjectKey
    private lateinit var backup: Path
    private lateinit var archive: Path
    private lateinit var objectBackup: Path
    private lateinit var originalCounts: List<Long>
    private lateinit var originalCiphertext: String
    private val sourceObjects get() = directory.resolve("source-objects")
    private val restoredObjects get() = directory.resolve("restored-objects")
    private val receiptKey get() = Path.of(required("S2_PG_RECEIPT_KEY_FILE"))

    private fun source() = dataSource(schema)
    private fun targetSource() = dataSource().apply {
        check(target.matches(Regex("s2_restore_it_[a-f0-9]{32}")))
        databaseName = target; currentSchema = schema
    }
    private fun admin() = PGSimpleDataSource().apply {
        check(required("S2_PG_INTEGRATION_URL") == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local")
        check(required("S2_PG_INTEGRATION_USER") == "group33_local_app")
        check(required("S2_PG_RECOVERY_ENABLED") == "local-fixtures-only")
        setURL(required("S2_PG_INTEGRATION_URL")); user = "s2_local_admin"
        password = required("S2_PG_RECOVERY_ADMIN_PASSWORD"); connectTimeout = 5; socketTimeout = 15
    }
    private fun store(source: PGSimpleDataSource = source()) =
        PostgresPairingCodeStore(source, PairingReceiptCipher(Files.readAllBytes(receiptKey)))
    private fun objects(root: Path = sourceObjects) = LocalFilePhotoObjectStore(root)
    private fun scalar(source: PGSimpleDataSource, sql: String) = source.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) } }
    }
    private fun counts(source: PGSimpleDataSource) = TABLES.map { scalar(source, "SELECT count(*) FROM $it").toLong() }
    private fun runtime(key: Path = receiptKey): PersistenceRuntime = PersistenceRuntimeConfiguration().create(MockEnvironment()
        .withProperty("app.persistence.mode", "postgres-local")
        .withProperty("app.persistence.jdbc-url", "jdbc:postgresql://127.0.0.1:55432/$target")
        .withProperty("app.persistence.username", required("S2_PG_INTEGRATION_USER"))
        .withProperty("app.persistence.password", required("S2_PG_INTEGRATION_PASSWORD"))
        .withProperty("app.persistence.schema", schema)
        .withProperty("app.persistence.receipt-key-file", key.toString())
        .withProperty("app.persistence.require-versioned-schema", "true")
        .withProperty("app.jwt.secret", "FICTIONAL_RECOVERY_ONLY_SIGNING_SECRET_2026")
        .withProperty("app.photo.enabled", "true").withProperty("app.plan.enabled", "true")
        .withProperty("app.occurrence.enabled", "true"))

    @BeforeEach fun prepare() {
        admin() // Validate local-only settings before creating any fixture resource.
        source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        sourceCreated = true
        assertEquals(9, SchemaMigrations(source(), schema).migrate())
        val identities = PostgresAnonymousIdentityStore(source())
        elder = identities.getOrCreate("fictional-recovery-elder-installation", "虚构长者", UserRole.ELDER)
        caregiver = identities.getOrCreate("fictional-recovery-caregiver-installation", "虚构照护者", UserRole.CAREGIVER)
        val store = store()
        issued = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store, clock).issue(elder, codeOperation)).view
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java,
            PairingRequestService(store, clock).request(caregiver, UUID.randomUUID(), RequestPairingRequest(issued.code),
                InetAddress.getByName("192.0.2.60"))).view
        relation = assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(store, clock).accept(elder, pending.id, UUID.randomUUID())).view
        val image = ByteArrayOutputStream().use { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it); it.toByteArray() }
        photo = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            MedicationPhotoService(store, objects(), clock).upload(elder, elder.id, photoOperation, image, "image/png")).view
        objectKey = store.photoTransaction { it.readyPhoto(photo.id)!!.objectKey }
        request = ConfirmReminderPlanRequest(photo.id, MedicationText("虚构原药品", "虚构原用量", " 原说明 "),
            ReminderPlanDraft(listOf("08:00"), "2026-10-31", "Asia/Shanghai"),
            TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai"))
        original = assertInstanceOf(PlanConfirmationResult.Confirmed::class.java,
            ReminderPlanConfirmationService(store, objects(), clock).confirm(caregiver, elder.id, confirmationOperation, request)).view
        edit = UpdateReminderPlanRequest(1, request.plan.copy(localTimes = listOf("09:00")), request.timeZoneAcknowledgement,
            MedicationText("虚构编辑药品", "虚构编辑用量", null))
        edited = assertInstanceOf(PlanMutationResult.Accepted::class.java,
            ReminderPlanMutationService(store, clock).update(caregiver, original.plan.id, editOperation, edit)).view
        originalCounts = counts(source())
        originalCiphertext = scalar(source(), "SELECT encode(receipt_cipher, 'hex') FROM pairing_issue_operation")
        backup = Files.createDirectory(directory.resolve("private-backup"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        archive = Files.createFile(backup.resolve("business.dump"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        objectBackup = Files.createFile(backup.resolve("photo.blob"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(objectBackup, objects().read(objectKey))
        // The fixture is quiescent: all source writes finished before logical DB/object capture.
        tool("pg_dump", "--format=custom", "--no-owner", "--no-acl", "--schema=$schema", "--file=$archive", "--dbname=group33_s2_local")
        assertTrue(Files.size(archive) > 0)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(archive))
        // Make accidental reads from the original data impossible for the recovery assertions.
        source().connection.use { c -> c.createStatement().use { it.execute("ALTER SCHEMA $schema RENAME TO ${schema}_retired") } }
        sourceRetired = true
        Files.move(sourceObjects, directory.resolve("retired-source-objects"))
        assertThrows(IdentityStoreUnavailable::class.java) { identities.findById(elder.id) }
        admin().connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $target OWNER group33_local_app") } }
        targetCreated = true
        tool("pg_restore", "--single-transaction", "--exit-on-error", "--no-owner", "--no-acl", "--dbname=$target", archive.toString())
        assertEquals(target, scalar(targetSource(), "SELECT current_database()"))
        assertEquals(originalCounts, counts(targetSource()))
        SchemaMigrations(targetSource(), schema).validate()
        assertEquals(originalCiphertext, scalar(targetSource(), "SELECT encode(receipt_cipher, 'hex') FROM pairing_issue_operation"))
    }

    private fun tool(name: String, vararg args: String) {
        check(name in setOf("pg_dump", "pg_restore"))
        val binary = Path.of(required("S2_PG_RECOVERY_BIN"), name)
        check(Files.isExecutable(binary))
        val log = Files.createTempFile(backup, "tool-", ".log", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        val command = listOf(binary.toString(), "--no-password", "--host=127.0.0.1", "--port=55432", "--username=group33_local_app") + args
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
        builder.environment().keys.removeIf { it.startsWith("PG") }
        builder.environment()["PGPASSWORD"] = required("S2_PG_INTEGRATION_PASSWORD")
        builder.environment()["PGCONNECT_TIMEOUT"] = "5"
        val process = builder.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Local backup/restore process did not finish")
            assertEquals(0, process.exitValue(), "Local backup/restore failed; credential-bearing output is not copied")
        } finally { if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
    }

    private fun restoreObject() = objects(restoredObjects).also { storage ->
        Files.newInputStream(objectBackup).use { storage.putIfAbsent(objectKey, it) }
    }

    @Test fun `fresh database and object root recover current business original receipts and later revocation`() {
        val storage = restoreObject(); val restored = runtime(); val store = restored.pairing
        assertEquals(elder, restored.identities.findById(elder.id)); assertEquals(caregiver, restored.identities.findById(caregiver.id))
        assertEquals(issued, assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store, clock, PairingCodeGenerator { error("Must replay original receipt") }).issue(elder, codeOperation)).view)
        val photos = MedicationPhotoService(store, storage, clock)
        assertArrayEquals(Files.readAllBytes(objectBackup), assertInstanceOf(PhotoBusinessResult.Content::class.java,
            photos.content(caregiver, photo.id)).bytes)
        assertEquals(photo, assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            photos.upload(elder, elder.id, photoOperation, Files.readAllBytes(objectBackup), "image/png")).view)
        val plans = ReminderPlanConfirmationService(store, storage, clock)
        assertEquals(original, assertInstanceOf(PlanConfirmationResult.Confirmed::class.java,
            plans.confirm(caregiver, elder.id, confirmationOperation, request)).view)
        assertEquals(edited, assertInstanceOf(PlanMutationResult.Accepted::class.java,
            ReminderPlanMutationService(store, clock).update(caregiver, original.plan.id, editOperation, edit)).view)
        val current = assertInstanceOf(PlanConfirmationResult.Plans::class.java, plans.plans(caregiver, elder.id)).value
        assertEquals(listOf(edited.plan), current.items); assertEquals(listOf(1L, 2L), current.planRevisions.map { it.revision })
        assertEquals(original.medication.displayName, current.planRevisions.first().medicationSnapshot.displayName)
        assertEquals(originalCounts, counts(targetSource())) // Replays have no new effects.
        assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(store, clock).revoke(elder, relation.id, UUID.randomUUID()))
        val afterReload = runtime().pairing
        assertEquals(PhotoBusinessResult.Rejected(PhotoBusinessRejection.RESOURCE_NOT_ACCESSIBLE),
            MedicationPhotoService(afterReload, storage, clock).content(caregiver, photo.id))
        assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE),
            ReminderPlanConfirmationService(afterReload, storage, clock).plans(caregiver, elder.id))
        assertEquals(2, assertInstanceOf(PlanConfirmationResult.Plans::class.java,
            ReminderPlanConfirmationService(afterReload, storage, clock).plans(elder, elder.id)).value.planRevisions.size)
    }

    @Test fun `wrong external receipt key rejects restored startup without changing original data`() {
        val wrong = Files.createFile(directory.resolve("wrong.key"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(wrong, ByteArray(32) { 42 })
        val failure = assertThrows(IllegalStateException::class.java) { runtime(wrong) }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(schema)); assertFalse(failure.toString().contains(wrong.toString()))
        assertEquals(originalCounts, counts(targetSource()))
        assertEquals(elder, runtime().identities.findById(elder.id))
    }

    @Test fun `restored metadata cannot serve a missing object and original backup repairs the same identity`() {
        val restored = runtime(); val storage = objects(restoredObjects)
        val photos = MedicationPhotoService(restored.pairing, storage, clock)
        assertEquals(PhotoBusinessResult.Rejected(PhotoBusinessRejection.SERVICE_UNAVAILABLE), photos.content(caregiver, photo.id))
        assertEquals(listOf(photo), assertInstanceOf(PhotoBusinessResult.Listed::class.java, photos.list(caregiver, elder.id)).items)
        Files.newInputStream(objectBackup).use { storage.putIfAbsent(objectKey, it) }
        assertArrayEquals(Files.readAllBytes(objectBackup), assertInstanceOf(PhotoBusinessResult.Content::class.java,
            photos.content(caregiver, photo.id)).bytes)
        assertEquals(originalCounts, counts(targetSource()))
    }

    @AfterEach fun cleanup() {
        if (targetCreated) admin().connection.use { c -> c.createStatement().use { it.execute("DROP DATABASE $target WITH (FORCE)") } }
        if (sourceCreated) dataSource().connection.use { c -> c.createStatement().use {
            it.execute("DROP SCHEMA IF EXISTS ${schema}${if (sourceRetired) "_retired" else ""} CASCADE")
        } }
    }

    private companion object {
        val TABLES = listOf("anonymous_identity", "pairing_active_code", "pairing_issue_operation", "pairing_request",
            "pairing_request_operation", "pairing_decision_operation", "pairing_relationship", "photo_upload_operation",
            "medication_photo", "medication", "reminder_plan", "reminder_plan_revision", "plan_confirmation_operation",
            "plan_mutation_operation", "registered_occurrence", "occurrence_registration_operation", "group33_schema_history")
    }
}
