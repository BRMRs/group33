package com.group33.server.migration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.auth.AnonymousUser
import com.group33.server.config.RuntimeSchemaHistory
import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.server.photo.MedicationPhotoService
import com.group33.server.photo.PhotoBusinessResult
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.server.plan.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.imageio.ImageIO

/** One genuinely confirmed/edited plan plus SQL-only planner fillers, all constraints enabled. */
internal class PlanIndexFixture(private val directory: Path) : AutoCloseable {
    val schema = "s2_plan_index_it_" + UUID.randomUUID().toString().replace("-", "")
    val source get() = dataSource(schema)
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC)
    val elder = AnonymousUser("fixture-index-elder", "fixture-index-e-installation", "Fictional E", UserRole.ELDER)
    val caregiver = AnonymousUser("fixture-index-caregiver", "fixture-index-c-installation", "Fictional C", UserRole.CAREGIVER)
    val objects = LocalFilePhotoObjectStore(directory.resolve("objects"))
    val store get() = PostgresPairingCodeStore(source, PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))))
    val reader get() = ReminderPlanConfirmationService(store, objects, clock)
    val confirmationKey = UUID.randomUUID()
    val editKey = UUID.randomUUID()
    lateinit var input: ConfirmReminderPlanRequest
    lateinit var editInput: UpdateReminderPlanRequest
    lateinit var original: ConfirmReminderPlanResponse
    lateinit var edited: UpdateReminderPlanResponse
    lateinit var relation: CareRelationshipView

    fun sql(text: String) = source.connection.use { it.createStatement().use { s -> s.execute(text) } }
    fun scalar(text: String): String = source.connection.use { it.createStatement().use { s -> s.executeQuery(text).use { r -> check(r.next()); r.getString(1) } } }
    fun history() = scalar("SELECT string_agg(version, ',' ORDER BY installed_rank) FROM group33_schema_history")
    fun migrate() = SchemaMigrations(source, schema).migrate()
    fun indexes() = scalar("SELECT count(*) FROM pg_indexes WHERE schemaname='$schema' AND indexname IN ('medication_elder_collection','reminder_plan_elder_collection','reminder_plan_revision_elder_collection')").toInt()
    fun contents() = listOf("pairing_relationship", "medication_photo", "photo_upload_operation", "medication", "reminder_plan", "reminder_plan_revision", "plan_confirmation_operation", "plan_mutation_operation").associateWith {
        scalar("SELECT md5(COALESCE(string_agg(row_to_json(t)::text || t.xmin::text, ',' ORDER BY row_to_json(t)::text), '')) FROM $it t")
    }
    fun prepare() {
        dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).createSchemas(false)
            .table("group33_schema_history").locations("classpath:db/migration").target("9")
            .baselineOnMigrate(false).cleanDisabled(true).group(true).loggers(*emptyArray<String>()).load().migrate()
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store, clock) { "12345678" }.issue(elder, UUID.randomUUID())).view.code
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java, PairingRequestService(store, clock)
            .request(caregiver, UUID.randomUUID(), RequestPairingRequest(code), InetAddress.getByName("192.0.2.30"))).view
        relation = assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(store, clock).accept(elder, pending.id, UUID.randomUUID())).view
        val bytes = ByteArrayOutputStream().use { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it); it.toByteArray() }
        val photo = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            MedicationPhotoService(store, objects, clock).upload(elder, elder.id, UUID.randomUUID(), bytes, "image/png")).view
        input = ConfirmReminderPlanRequest(photo.id, MedicationText("Fictional medicine", "Fictional dosage"),
            ReminderPlanDraft(listOf("08:00"), "2026-10-31", "Asia/Shanghai"),
            TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai"))
        original = assertInstanceOf(PlanConfirmationResult.Confirmed::class.java,
            reader.confirm(caregiver, elder.id, confirmationKey, input)).view
        editInput = UpdateReminderPlanRequest(1, input.plan.copy(localTimes = listOf("09:00")), input.timeZoneAcknowledgement)
        edited = assertInstanceOf(PlanMutationResult.Accepted::class.java,
            ReminderPlanMutationService(store, clock).update(caregiver, original.plan.id, editKey, editInput)).view
    }

    fun seedPlannerRows() {
        // These fabricated rows are only a planner workload, not simulated production uploads.
        // Keep all FK/CHECK/deferred constraints enabled and commit a complete reference graph.
        source.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                s.execute("CREATE TEMP TABLE index_seed ON COMMIT DROP AS SELECT i, md5('photo-'||i)::uuid photo, md5('med-'||i)::uuid med, md5('plan-'||i)::uuid plan, md5('receipt-'||i)::uuid receipt, md5('operation-'||i)::uuid operation, 'fixture-planner-'||i elder FROM generate_series(1,6000) i")
                s.execute("""INSERT INTO medication_photo SELECT g.photo,g.elder,p.content_type,p.created_at,g.photo,p.byte_count,p.sha256 FROM index_seed g CROSS JOIN medication_photo p WHERE p.elder_id='${elder.id}'""")
                s.execute("""INSERT INTO medication SELECT g.med,g.elder,m.display_name,m.dosage_text,m.instructions,g.photo,m.active,g.receipt FROM index_seed g CROSS JOIN medication m WHERE m.elder_id='${elder.id}'""")
                s.execute("""INSERT INTO reminder_plan SELECT g.plan,g.elder,g.med,1,p.local_times,p.start_date,p.end_date,p.execution_time_zone_id,p.active,p.effective_from,p.confirmed_at,g.receipt FROM index_seed g CROSS JOIN reminder_plan_revision p WHERE p.elder_id='${elder.id}' AND p.revision=1""")
                s.execute("""INSERT INTO reminder_plan_revision SELECT g.plan,1,g.elder,g.med,r.local_times,r.start_date,r.end_date,r.execution_time_zone_id,r.active,r.effective_from,r.confirmed_at,r.display_name,r.dosage_text,r.instructions FROM index_seed g CROSS JOIN reminder_plan_revision r WHERE r.elder_id='${elder.id}' AND r.revision=1""")
                s.execute("""INSERT INTO plan_confirmation_operation
                    SELECT 'fixture-planner-caregiver-'||g.i,g.operation,'fixture-planner-install-'||g.i,g.elder,o.fingerprint,g.receipt,g.med,g.plan,1,
                        jsonb_build_object('medication',o.receipt_payload->'medication'||jsonb_build_object('id',g.med::text,'elderId',g.elder,'photoId',g.photo::text),
                                           'plan',o.receipt_payload->'plan'||jsonb_build_object('id',g.plan::text,'elderId',g.elder,'medicationId',g.med::text)),
                        NULL,'[]'::jsonb
                    FROM index_seed g CROSS JOIN plan_confirmation_operation o WHERE o.actor_id='${caregiver.id}'
                """.trimIndent())
            }
            c.commit()
        }
        sql("ANALYZE medication; ANALYZE reminder_plan; ANALYZE reminder_plan_revision")
    }

    fun explain(table: String): JsonNode {
        val order = if (table == "reminder_plan_revision") "plan_id, revision" else "id"
        return source.connection.use { c -> c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON, TIMING OFF) SELECT * FROM $table WHERE elder_id = ? ORDER BY $order").use { s ->
            s.setString(1, elder.id)
            s.executeQuery().use { r -> check(r.next()); ObjectMapper().readTree(r.getString(1))[0]["Plan"] }
        } }
    }
    override fun close() { dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }
}

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PostgresPlanCollectionIndexTest {
    private lateinit var fixture: PlanIndexFixture
    private lateinit var before: Map<String, JsonNode>
    @BeforeAll fun prepare(@TempDir directory: Path) {
        fixture = PlanIndexFixture(directory)
        fixture.prepare(); fixture.seedPlannerRows()
        before = listOf("medication", "reminder_plan", "reminder_plan_revision").associateWith(fixture::explain)
    }
    @AfterAll fun cleanup() { if (::fixture.isInitialized) fixture.close() }
    private fun nodes(root: JsonNode): List<JsonNode> = listOf(root) + root.path("Plans").flatMap(::nodes)
    private fun blocks(node: JsonNode) = node.path("Shared Hit Blocks").asLong() + node.path("Shared Read Blocks").asLong()

    @ParameterizedTest @ValueSource(strings = ["medication", "reminder_plan", "reminder_plan_revision"])
    fun `normal planner bounds elder collection work using the new access path`(table: String) {
        fixture.migrate()
        val after = fixture.explain(table)
        println("INDEX_PLAN $table before=${before.getValue(table)} after=$after")
        assertTrue(nodes(after).any { it.path("Index Name").asText() == "${table}_elder_collection" && it.path("Index Cond").asText().contains("elder_id") }, "Planner must use the elder-leading condition with default scan settings")
        assertFalse(nodes(after).any { it.path("Node Type").asText() == "Seq Scan" && it.path("Relation Name").asText() == table })
        assertEquals(before.getValue(table).path("Actual Rows").asLong(), after.path("Actual Rows").asLong())
        assertTrue(blocks(after) * 10 < blocks(before.getValue(table)), "Selective fixture should touch fewer data/index pages, independently of wall-clock timing")
    }
}

@Tag("postgres")
internal class PostgresPlanIndexMigrationTest {
    @TempDir lateinit var directory: Path
    private lateinit var fixture: PlanIndexFixture
    @BeforeEach fun prepare() { fixture = PlanIndexFixture(directory); fixture.prepare() }
    @AfterEach fun cleanup() { if (::fixture.isInitialized) fixture.close() }

    @Test fun `version nine upgrades without rewriting medical history or receipts and current revocation remains authoritative`() {
        val saved = fixture.contents()
        val history = fixture.history()
        assertThrows(IllegalStateException::class.java) { RuntimeSchemaHistory.validate(fixture.source, fixture.schema) }
        assertEquals(history, fixture.history())
        assertEquals(1, fixture.migrate())
        assertEquals(3, fixture.indexes())
        assertEquals(saved, fixture.contents())
        RuntimeSchemaHistory.validate(fixture.source.apply { options = "-c default_transaction_read_only=on" }, fixture.schema)
        assertEquals(0, fixture.migrate())
        assertEquals(fixture.original, assertInstanceOf(PlanConfirmationResult.Confirmed::class.java,
            fixture.reader.confirm(fixture.caregiver, fixture.elder.id, fixture.confirmationKey, fixture.input)).view)
        assertEquals(fixture.edited, assertInstanceOf(PlanMutationResult.Accepted::class.java,
            ReminderPlanMutationService(fixture.store, fixture.clock).update(fixture.caregiver, fixture.original.plan.id, fixture.editKey, fixture.editInput)).view)
        assertEquals(listOf(1L, 2L), assertInstanceOf(PlanConfirmationResult.Plans::class.java,
            fixture.reader.plans(fixture.caregiver, fixture.elder.id)).value.planRevisions.map { it.revision })
        assertEquals(listOf(fixture.edited.medication), assertInstanceOf(PlanConfirmationResult.Medications::class.java,
            fixture.reader.medications(fixture.caregiver, fixture.elder.id)).items)
        assertEquals(saved, fixture.contents())
        PairingDecisionService(fixture.store, fixture.clock).revoke(fixture.elder, fixture.relation.id, UUID.randomUUID())
        assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE), fixture.reader.plans(fixture.caregiver, fixture.elder.id))
        assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE), fixture.reader.medications(fixture.caregiver, fixture.elder.id))
        assertEquals(2, assertInstanceOf(PlanConfirmationResult.Plans::class.java, fixture.reader.plans(fixture.elder, fixture.elder.id)).value.planRevisions.size)
    }

    @Test fun `conflicting final index name rolls back preceding indexes and preserves the existing object`() {
        fixture.sql("CREATE INDEX reminder_plan_revision_elder_collection ON pairing_issuance_guard (id)")
        val saved = fixture.contents(); val history = fixture.history()
        val failure = assertThrows(IllegalStateException::class.java) { fixture.migrate() }
        assertNull(failure.cause)
        assertEquals(history, fixture.history()); assertEquals(saved, fixture.contents())
        assertEquals(1, fixture.indexes())
        assertEquals("pairing_issuance_guard", fixture.scalar("SELECT tablename FROM pg_indexes WHERE schemaname='${fixture.schema}' AND indexname='reminder_plan_revision_elder_collection'"))
        fixture.sql("DROP INDEX reminder_plan_revision_elder_collection")
        assertEquals(1, fixture.migrate()); assertEquals(3, fixture.indexes()); assertEquals(saved, fixture.contents())
    }

    @Test fun `blocked middle table fails within lock budget and rolls back earlier index before retry`() {
        val saved = fixture.contents(); val history = fixture.history()
        fixture.source.connection.use { blocking ->
            blocking.autoCommit = false
            blocking.createStatement().use { it.execute("LOCK TABLE reminder_plan IN ACCESS EXCLUSIVE MODE") }
            try {
                val failure = assertThrows(IllegalStateException::class.java) { fixture.migrate() }
                assertNull(failure.cause)
                assertEquals(history, fixture.history()); assertEquals(0, fixture.indexes())
            } finally { blocking.rollback() }
        }
        assertEquals(saved, fixture.contents())
        assertEquals(1, fixture.migrate()); assertEquals(3, fixture.indexes()); assertEquals(saved, fixture.contents())
    }
}
