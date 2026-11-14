package com.group33.server.migration

import com.group33.server.auth.*
import com.group33.server.config.PersistenceRuntimeConfiguration
import com.group33.server.config.RuntimeSchemaHistory
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
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment

/** Exercise the actual loopback-only administration command against fictional real PostgreSQL.
 * Preserve the earlier business/replay cases; no cloud settings or backups are used.
 */
@Tag("postgres")
internal class PostgresManualCopyToolTest {
    @TempDir lateinit var directory: Path
    private val suffix = UUID.randomUUID().toString().replace("-", "")
    private val originalSchema = "group33_manual_it_$suffix"
    private val targetSchema = "group33_managed_it_$suffix"
    private var sourceRetired = false
    private val source get() = dataSource(originalSchema)
    private val target get() = dataSource(targetSchema)
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val receiptKey get() = Path.of(required("S2_PG_RECEIPT_KEY_FILE"))
    private val originalObjects get() = LocalFilePhotoObjectStore(directory.resolve("source-objects"))
    private val managedObjects get() = LocalFilePhotoObjectStore(directory.resolve("managed-objects"))
    private val codeId = UUID.randomUUID()
    private val photoId = UUID.randomUUID()
    private val confirmId = UUID.randomUUID()
    private val editId = UUID.randomUUID()
    private lateinit var elder: AnonymousUser
    private lateinit var caregiver: AnonymousUser
    private lateinit var relation: CareRelationshipView
    private lateinit var code: PairingCodeView
    private lateinit var photo: MedicationPhotoView
    private lateinit var original: ConfirmReminderPlanResponse
    private lateinit var edited: UpdateReminderPlanResponse
    private lateinit var input: ConfirmReminderPlanRequest
    private lateinit var edit: UpdateReminderPlanRequest
    private lateinit var objectKey: PhotoObjectKey
    private lateinit var image: ByteArray
    private val tables = listOf("pairing_issuance_guard", "anonymous_identity", "pairing_active_code",
        "pairing_issue_operation", "pairing_issuance_time", "pairing_relationship", "pairing_request",
        "pairing_request_operation", "pairing_request_attempt", "pairing_decision_operation", "pairing_code_deletion",
        "medication_photo", "photo_upload_operation", "medication", "reminder_plan", "reminder_plan_revision",
        "plan_confirmation_operation", "plan_mutation_operation")
    private fun store(schema: String) = PostgresPairingCodeStore(dataSource(schema), PairingReceiptCipher(Files.readAllBytes(receiptKey)))
    private fun sql(schema: String, text: String) = dataSource(schema).connection.use { it.createStatement().use { s -> s.execute(text) } }
    private fun scalar(schema: String, text: String): String? = dataSource(schema).connection.use { c -> c.createStatement().use { s ->
        s.executeQuery(text).use { r -> check(r.next()); r.getString(1) } } }
    private fun digest(schema: String) = tables.associateWith { table ->
        scalar(schema, "SELECT md5(COALESCE(string_agg(row_to_json(t)::text, ',' ORDER BY row_to_json(t)::text), '')) FROM $table t")
    }
    private fun history() = scalar(targetSchema, "SELECT md5(string_agg(h::text, ',' ORDER BY installed_rank)) FROM group33_schema_history h")
    private fun runtime(key: Path = receiptKey) = PersistenceRuntimeConfiguration().create(MockEnvironment()
        .withProperty("app.persistence.mode", "postgres-local").withProperty("app.persistence.require-versioned-schema", "true")
        .withProperty("app.persistence.jdbc-url", required("S2_PG_INTEGRATION_URL"))
        .withProperty("app.persistence.username", required("S2_PG_INTEGRATION_USER"))
        .withProperty("app.persistence.password", required("S2_PG_INTEGRATION_PASSWORD"))
        .withProperty("app.persistence.schema", targetSchema).withProperty("app.persistence.receipt-key-file", key.toString())
        .withProperty("app.jwt.secret", "FICTIONAL_MANUAL_CONVERSION_STABLE_SIGNING_SECRET_2026")
        .withProperty("app.photo.enabled", "true").withProperty("app.plan.enabled", "true")
        .withProperty("app.occurrence.enabled", "true"))

    @BeforeEach fun prepare() {
        dataSource().connection.use { it.createStatement().use { s ->
            s.execute("CREATE SCHEMA $originalSchema; CREATE SCHEMA $targetSchema")
        } }
        // Create actual unmanaged v1-v8 tables, never delete/fabricate Flyway history to simulate them.
        for (name in listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql",
            "pairing-directory-store-v4.sql", "anonymous-identity-store-v5.sql", "medication-photo-store-v6.sql",
            "plan-confirmation-store-v7.sql", "plan-mutation-store-v8.sql"))
            sql(originalSchema, checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText())
        assertNull(scalar(originalSchema, "SELECT to_regclass('$originalSchema.group33_schema_history')::text"))
        val identities = PostgresAnonymousIdentityStore(source)
        elder = identities.getOrCreate("fictional-conversion-e", "虚构长者", UserRole.ELDER)
        caregiver = identities.getOrCreate("fictional-conversion-c", "虚构照护者", UserRole.CAREGIVER)
        val old = store(originalSchema)
        code = assertInstanceOf(CodeIssuanceResult.Issued::class.java, PairingCodeIssuanceService(old, clock).issue(elder, codeId)).view
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java, PairingRequestService(old, clock)
            .request(caregiver, UUID.randomUUID(), RequestPairingRequest(code.code), InetAddress.getByName("192.0.2.88"))).view
        relation = assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(old, clock).accept(elder, pending.id, UUID.randomUUID())).view
        image = ByteArrayOutputStream().use { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it); it.toByteArray() }
        photo = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            MedicationPhotoService(old, originalObjects, clock).upload(elder, elder.id, photoId, image, "image/png")).view
        objectKey = old.photoTransaction { it.readyPhoto(photo.id)!!.objectKey }
        input = ConfirmReminderPlanRequest(photo.id, MedicationText("虚构原药品", "虚构用量", "原说明"),
            ReminderPlanDraft(listOf("08:00"), "2026-11-01", "Asia/Shanghai"),
            TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai"))
        original = assertInstanceOf(PlanConfirmationResult.Confirmed::class.java,
            ReminderPlanConfirmationService(old, originalObjects, clock).confirm(caregiver, elder.id, confirmId, input)).view
        edit = UpdateReminderPlanRequest(1, input.plan.copy(localTimes = listOf("09:00")), input.timeZoneAcknowledgement,
            MedicationText("虚构编辑药品", "虚构新用量", null))
        edited = assertInstanceOf(PlanMutationResult.Accepted::class.java,
            ReminderPlanMutationService(old, clock).update(caregiver, original.plan.id, editId, edit)).view
        assertEquals(9, SchemaMigrations(target, targetSchema).migrate())
        sourceReference = ManualSchemaCopy.inspect(target, originalSchema, false)
        targetReference = ManualSchemaCopy.inspect(target, targetSchema, true)
    }
    @AfterEach fun cleanup() { dataSource().connection.use { it.createStatement().use { s ->
        s.execute("DROP SCHEMA IF EXISTS $originalSchema CASCADE; DROP SCHEMA IF EXISTS ${originalSchema}_old CASCADE; DROP SCHEMA IF EXISTS $targetSchema CASCADE")
    } } }

    private lateinit var sourceReference: ManualSchemaCopy.Reference
    private lateinit var targetReference: ManualSchemaCopy.Reference
    private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (wrapped: java.lang.reflect.InvocationTargetException) { throw wrapped.targetException }
    private fun copyFixture(failAfter: String? = null) {
        val real = target
        val input = if (failAfter == null) real else object : org.postgresql.ds.PGSimpleDataSource() {
            init {
                setURL(required("S2_PG_INTEGRATION_URL")); user = required("S2_PG_INTEGRATION_USER")
                password = required("S2_PG_INTEGRATION_PASSWORD"); currentSchema = targetSchema
            }
            override fun getConnection(): java.sql.Connection {
                val connection = real.connection
                return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                    arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                    val value = invoke(connection, method, args)
                    if (method.name != "createStatement") value else java.lang.reflect.Proxy.newProxyInstance(
                        javaClass.classLoader, arrayOf(java.sql.Statement::class.java)) { _, call, values ->
                        val result = invoke(value!!, call, values)
                        if (call.name == "executeUpdate" && values?.firstOrNull().toString()
                                .contains("\"$targetSchema\".\"$failAfter\"")) {
                            throw SQLException("Fictional injected statement failure", "40001")
                        }
                        result
                    }
                } as java.sql.Connection
            }
        }
        ManualSchemaCopy.copy(input, sourceReference, targetReference)
    }
    private fun restoreObject() { image.inputStream().use { managedObjects.putIfAbsent(objectKey, it) } }

    @Test fun `manual source remains untouched and managed target preserves business receipts after source is retired`() {
        val before = digest(originalSchema); val applied = history()
        assertThrows(IllegalStateException::class.java) { SchemaMigrations(source, originalSchema).migrate() }
        copyFixture(); restoreObject()
        assertEquals(before, digest(originalSchema)); assertEquals(before, digest(targetSchema)); assertEquals(applied, history())
        assertNull(scalar(originalSchema, "SELECT to_regclass('$originalSchema.group33_schema_history')::text"))
        sql(originalSchema, "ALTER SCHEMA $originalSchema RENAME TO ${originalSchema}_old"); sourceRetired = true
        Files.move(directory.resolve("source-objects"), directory.resolve("retired-source-objects"))
        assertThrows(IdentityStoreUnavailable::class.java) { PostgresAnonymousIdentityStore(source).findById(elder.id) }
        RuntimeSchemaHistory.validate(target, targetSchema)
        val current = runtime(); val store = current.pairing
        assertEquals(elder, current.identities.findById(elder.id)); assertEquals(caregiver, current.identities.findById(caregiver.id))
        assertEquals(code, assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store, clock, PairingCodeGenerator { error("Must replay original code") }).issue(elder, codeId)).view)
        val photos = MedicationPhotoService(store, managedObjects, clock)
        assertArrayEquals(image, assertInstanceOf(PhotoBusinessResult.Content::class.java, photos.content(caregiver, photo.id)).bytes)
        assertEquals(photo, assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            photos.upload(elder, elder.id, photoId, image, "image/png")).view)
        val plans = ReminderPlanConfirmationService(store, managedObjects, clock)
        assertEquals(original, assertInstanceOf(PlanConfirmationResult.Confirmed::class.java, plans.confirm(caregiver, elder.id, confirmId, input)).view)
        assertEquals(edited, assertInstanceOf(PlanMutationResult.Accepted::class.java,
            ReminderPlanMutationService(store, clock).update(caregiver, original.plan.id, editId, edit)).view)
        val view = assertInstanceOf(PlanConfirmationResult.Plans::class.java, plans.plans(caregiver, elder.id)).value
        assertEquals(listOf(edited.plan), view.items); assertEquals(listOf(1L, 2L), view.planRevisions.map { it.revision })
        assertEquals(before, digest(targetSchema)) // Every original replay has no new effects.
        assertEquals("0", scalar(targetSchema, "SELECT count(*) FROM registered_occurrence")) // Never invent event history.
        PairingDecisionService(store, clock).revoke(elder, relation.id, UUID.randomUUID())
        assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE), plans.plans(caregiver, elder.id))
        assertEquals(PhotoBusinessResult.Rejected(PhotoBusinessRejection.RESOURCE_NOT_ACCESSIBLE), photos.content(caregiver, photo.id))
        assertEquals(2, assertInstanceOf(PlanConfirmationResult.Plans::class.java, plans.plans(elder, elder.id)).value.planRevisions.size)
    }
    @Test fun `middle copy interruption rolls back all new rows and leaves immutable history and source unchanged`() {
        val before = digest(originalSchema); val empty = digest(targetSchema); val applied = history()
        assertEquals("40001", assertThrows(SQLException::class.java) { copyFixture("medication") }.sqlState)
        assertEquals(before, digest(originalSchema)); assertEquals(empty, digest(targetSchema)); assertEquals(applied, history())
        copyFixture(); assertEquals(before, digest(targetSchema))
    }
    @ParameterizedTest @ValueSource(strings = ["source-table", "source-column", "target-data", "new-target-data"])
    fun `unexpected shape or nonempty target refuses without any overwrite`(kind: String) {
        when (kind) {
            "source-table" -> sql(originalSchema, "CREATE TABLE unknown_business (id integer PRIMARY KEY)")
            "source-column" -> sql(originalSchema, "ALTER TABLE medication ADD COLUMN unexpected text")
            "new-target-data" -> sql(targetSchema, "INSERT INTO occurrence_registration_operation(actor_id,operation_id,installation_id,elder_id,canonicalization_version,request_payload,rejection_payload) VALUES ('fictional-elder','${UUID.randomUUID()}','fictional-installation','fictional-elder','P0_CANONICAL_V1','{\"items\":[]}','{\"reason\":\"VALIDATION_FAILED\"}')")
            else -> PostgresAnonymousIdentityStore(target).getOrCreate("fictional-unrelated-target", "Fictional", UserRole.ELDER)
        }
        val before = digest(originalSchema); val targetBefore = digest(targetSchema); val applied = history()
        assertThrows(IllegalStateException::class.java) { copyFixture() }
        assertEquals(before, digest(originalSchema)); assertEquals(targetBefore, digest(targetSchema)); assertEquals(applied, history())
    }
    @ParameterizedTest @ValueSource(strings = ["source-index", "source-trigger", "source-check", "target-index", "history"])
    fun `catalog or history drift rejects before copying even when columns still match`(kind: String) {
        when (kind) {
            "source-index" -> sql(originalSchema, "CREATE INDEX unexpected_copy_index ON medication(elder_id)")
            "source-trigger" -> sql(originalSchema, "ALTER TABLE reminder_plan DISABLE TRIGGER USER")
            "source-check" -> sql(originalSchema, "ALTER TABLE medication ADD CONSTRAINT unexpected_copy_check CHECK (length(id::text)>0)")
            "target-index" -> sql(targetSchema, "CREATE INDEX unexpected_target_index ON medication(elder_id)")
            else -> sql(targetSchema, "UPDATE group33_schema_history SET installed_by='fictional-modified-installer'")
        }
        val before = digest(originalSchema); val empty = digest(targetSchema); val applied = history()
        assertThrows(Exception::class.java) { copyFixture() }
        assertEquals(before, digest(originalSchema)); assertEquals(empty, digest(targetSchema)); assertEquals(applied, history())
    }

    @Test fun `copied target is never overwritten by a retry`() {
        copyFixture()
        val before = digest(originalSchema); val accepted = digest(targetSchema); val applied = history()
        assertThrows(Exception::class.java) { copyFixture() }
        assertEquals(before, digest(originalSchema)); assertEquals(accepted, digest(targetSchema)); assertEquals(applied, history())
    }

    @Test fun `source row budget is enforced before any target row is copied`() {
        sql(originalSchema, "INSERT INTO anonymous_identity SELECT 'fictional-budget-'||n, 'fictional-budget-install-'||n, 'Fixture', 'ELDER' FROM generate_series(1,10001) n")
        val before = digest(originalSchema); val empty = digest(targetSchema); val applied = history()
        assertThrows(Exception::class.java) { copyFixture() }
        assertEquals(before, digest(originalSchema)); assertEquals(empty, digest(targetSchema)); assertEquals(applied, history())
    }

    @Test fun `packaged launcher creates private references and copies only after explicit acknowledgement`() {
        val archive = Path.of("build/distributions/group33-schema-migration.zip").toAbsolutePath()
        assertTrue(Files.isRegularFile(archive), "postgresIntegrationTest must build the real distribution")
        val root = Files.createDirectory(directory.resolve("operator"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        java.util.zip.ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val destination = root.resolve(entry.name).normalize()
                check(destination.startsWith(root))
                if (entry.isDirectory) Files.createDirectories(destination)
                else { Files.createDirectories(destination.parent); zip.getInputStream(entry).use { Files.copy(it, destination) } }
            }
        }
        val launcher = root.resolve("bin/manual-schema-copy")
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))
        val secret = root.resolve("password")
        Files.createFile(secret, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(secret, required("S2_PG_INTEGRATION_PASSWORD"))
        val sourceFile = root.resolve("source.properties"); val targetFile = root.resolve("target.properties")
        val env = mapOf("GROUP33_MIGRATION_URL" to required("S2_PG_INTEGRATION_URL"),
            "GROUP33_MIGRATION_SCHEMA" to targetSchema, "GROUP33_MIGRATION_USER" to required("S2_PG_INTEGRATION_USER"),
            "GROUP33_MIGRATION_PASSWORD_FILE" to secret.toString(), "GROUP33_COPY_SOURCE_SCHEMA" to originalSchema,
            "GROUP33_COPY_SOURCE_CATALOG_FILE" to sourceFile.toString(), "GROUP33_COPY_TARGET_CATALOG_FILE" to targetFile.toString(),
            "JAVA_HOME" to System.getProperty("java.home"))
        fun run(command: String, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
            val builder = ProcessBuilder(launcher.toString(), command).redirectErrorStream(true)
            builder.environment().keys.removeIf { it.startsWith("GROUP33_") || it.startsWith("SUPABASE_") }
            builder.environment().putAll(env + extra)
            val process = builder.start()
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
            val text = process.inputStream.bufferedReader().readText()
            assertFalse(text.contains(required("S2_PG_INTEGRATION_PASSWORD")))
            assertFalse(text.contains(originalSchema)); assertFalse(text.contains(targetSchema))
            return process.exitValue() to text
        }
        assertEquals(0, run("inspect-source").first); assertEquals(0, run("inspect-target").first)
        val referenceBytes = Files.readAllBytes(sourceFile)
        assertEquals(1, run("inspect-source").first)
        assertArrayEquals(referenceBytes, Files.readAllBytes(sourceFile))
        assertEquals(sourceReference, ManualSchemaCopy.readReference(sourceFile.toString()))
        assertEquals(targetReference, ManualSchemaCopy.readReference(targetFile.toString()))
        val before = digest(originalSchema); val empty = digest(targetSchema); val applied = history()
        assertEquals(1, run("copy").first)
        assertEquals(before, digest(originalSchema)); assertEquals(empty, digest(targetSchema))
        assertEquals(1, run("copy", mapOf("GROUP33_MIGRATION_URL" to "jdbc:postgresql://cloud.invalid:5432/postgres")).first)
        assertEquals(0, run("copy", mapOf("GROUP33_COPY_ACK" to "LOCAL_RESTORE_CATALOGS_REVIEWED_WRITERS_AND_DDL_PAUSED")).first)
        assertEquals(before, digest(originalSchema)); assertEquals(before, digest(targetSchema)); assertEquals(applied, history())
        assertEquals(1, run("copy", mapOf("GROUP33_COPY_ACK" to "LOCAL_RESTORE_CATALOGS_REVIEWED_WRITERS_AND_DDL_PAUSED")).first)
        assertEquals(before, digest(targetSchema)); assertEquals(applied, history())
    }

    @Test fun `source writer is blocked by copy lock and lock failure leaves no partial target`() {
        val before = digest(originalSchema); val empty = digest(targetSchema)
        source.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE medication IN ROW EXCLUSIVE MODE") }
            try { assertEquals("55P03", assertThrows(SQLException::class.java) { copyFixture() }.sqlState) }
            finally { blocker.rollback() }
        }
        assertEquals(before, digest(originalSchema)); assertEquals(empty, digest(targetSchema))
        copyFixture(); assertEquals(before, digest(targetSchema))
    }

    @Test fun `actual target lock conflict refuses within budget and original copy remains retryable`() {
        val before = digest(originalSchema); val empty = digest(targetSchema)
        target.connection.use { blocker ->
            blocker.autoCommit = false; blocker.createStatement().use { it.execute("LOCK TABLE medication IN ACCESS EXCLUSIVE MODE") }
            try { assertEquals("55P03", assertThrows(SQLException::class.java) { copyFixture() }.sqlState) }
            finally { blocker.rollback() }
        }
        assertEquals(empty, digest(targetSchema)); assertEquals(before, digest(originalSchema))
        copyFixture(); assertEquals(before, digest(targetSchema))
    }
    @Test fun `wrong external receipt key blocks startup without repairing or rewriting copied receipts`() {
        copyFixture(); val before = digest(targetSchema); val applied = history()
        val wrong = Files.createFile(directory.resolve("wrong.key"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(wrong, ByteArray(32) { 71 })
        val failure = assertThrows(IllegalStateException::class.java) { runtime(wrong) }
        assertNull(failure.cause); assertEquals(before, digest(targetSchema)); assertEquals(applied, history())
        assertNotNull(runtime().pairing)
    }
    @Test fun `missing immutable object is detected by content read and original bytes restore access without rewriting metadata`() {
        copyFixture(); val before = digest(targetSchema)
        val photos = MedicationPhotoService(runtime().pairing, managedObjects, clock)
        assertEquals(PhotoBusinessResult.Rejected(PhotoBusinessRejection.SERVICE_UNAVAILABLE), photos.content(caregiver, photo.id))
        assertEquals(before, digest(targetSchema)); restoreObject()
        assertArrayEquals(image, assertInstanceOf(PhotoBusinessResult.Content::class.java, photos.content(caregiver, photo.id)).bytes)
        assertEquals(before, digest(targetSchema))
    }
}
