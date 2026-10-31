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

/** Local fictional rehearsal only, NOT an administrator adoption command or generic catalog validator.
 * Business seeding/replay follows the prior S2 plan-index and backup-recovery fixtures.
 */
@Tag("postgres")
internal class PostgresManualSchemaConversionTest {
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
        assertEquals(10, SchemaMigrations(target, targetSchema).migrate())
    }
    @AfterEach fun cleanup() { dataSource().connection.use { it.createStatement().use { s ->
        s.execute("DROP SCHEMA IF EXISTS $originalSchema CASCADE; DROP SCHEMA IF EXISTS ${originalSchema}_old CASCADE; DROP SCHEMA IF EXISTS $targetSchema CASCADE")
    } } }

    private fun columns(c: Connection, schema: String, table: String): List<String> = c.prepareStatement("""
        SELECT attname || ':' || format_type(atttypid,atttypmod) || ':' || attnotnull::text || ':' || attidentity::text || ':' || attgenerated::text
        FROM pg_catalog.pg_attribute WHERE attrelid=CAST(? AS regclass) AND attnum>0 AND NOT attisdropped ORDER BY attnum
    """.trimIndent()).use { s ->
        s.setString(1, "$schema.$table"); s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }
    /** Only these synthetic same-database fixtures, both application writers paused; no general adoption CLI. */
    private fun copyFixture(failAfter: String? = null) {
        check(!sourceRetired)
        target.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { s ->
                    s.execute("SET LOCAL lock_timeout='500ms'; SET LOCAL statement_timeout='5000ms'")
                    s.execute("LOCK TABLE ${tables.joinToString { "$originalSchema.$it" }} IN SHARE MODE")
                    s.execute("LOCK TABLE ${tables.joinToString { "$targetSchema.$it" }} IN ACCESS EXCLUSIVE MODE")
                    val actual = s.executeQuery("SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname='$originalSchema'").use { r ->
                        buildSet { while (r.next()) add(r.getString(1)) }
                    }
                    check(actual == tables.toSet())
                    tables.forEach { table ->
                        check(columns(c, originalSchema, table) == columns(c, targetSchema, table))
                        s.executeQuery("SELECT count(*) FROM $targetSchema.$table").use { r ->
                            check(r.next() && r.getLong(1) == if (table == "pairing_issuance_guard") 1L else 0L)
                        }
                    }
                    s.execute("SET CONSTRAINTS ALL DEFERRED")
                    for (table in tables.filterNot { it == "pairing_issuance_guard" }) {
                        s.executeUpdate("INSERT INTO $targetSchema.$table SELECT * FROM $originalSchema.$table")
                        check(table != failAfter) { "Fictional injected copy interruption" }
                    }
                    s.execute("SET CONSTRAINTS ALL IMMEDIATE")
                }
                c.commit()
            } catch (failure: Throwable) {
                c.rollback(); throw failure
            }
        }
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
        assertThrows(IllegalStateException::class.java) { copyFixture("medication") }
        assertEquals(before, digest(originalSchema)); assertEquals(empty, digest(targetSchema)); assertEquals(applied, history())
        copyFixture(); assertEquals(before, digest(targetSchema))
    }
    @ParameterizedTest @ValueSource(strings = ["source-table", "source-column", "target-data"])
    fun `unexpected shape or nonempty target refuses without any overwrite`(kind: String) {
        when (kind) {
            "source-table" -> sql(originalSchema, "CREATE TABLE unknown_business (id integer PRIMARY KEY)")
            "source-column" -> sql(originalSchema, "ALTER TABLE medication ADD COLUMN unexpected text")
            else -> PostgresAnonymousIdentityStore(target).getOrCreate("fictional-unrelated-target", "Fictional", UserRole.ELDER)
        }
        val before = digest(originalSchema); val targetBefore = digest(targetSchema); val applied = history()
        assertThrows(IllegalStateException::class.java) { copyFixture() }
        assertEquals(before, digest(originalSchema)); assertEquals(targetBefore, digest(targetSchema)); assertEquals(applied, history())
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
