package com.group33.server.occurrence

import com.group33.server.auth.PostgresAnonymousIdentityStore
import com.group33.server.config.RuntimeSchemaHistory
import com.group33.server.migration.SchemaMigrations
import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.server.web.ApiException
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

@Tag("postgres")
internal class MigrationCoordinationTest : OccurrenceRegistrationContract() {
    private val version=SchemaMigrations.VERSION.toInt()
    private val schema="s2_coord_"+UUID.randomUUID().toString().replace("-","")
    private fun sql(value:String)=dataSource(schema).connection.use { c -> c.createStatement().use { it.execute(value) } }
    private fun scalar(value:String)=dataSource(schema).connection.use { c -> c.createStatement().use { s -> s.executeQuery(value).use { r ->check(r.next());r.getString(1) } } }
    private fun admin()=SchemaMigrations(dataSource(schema),schema)
    private fun createSchema()=dataSource().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
    private fun reset(){sql("DROP SCHEMA $schema CASCADE");createSchema()}
    private fun history()=scalar("SELECT string_agg(version,',' ORDER BY installed_rank) FROM group33_schema_history")
    private fun historyBeforeEleven()=scalar("SELECT string_agg(version||':'||checksum||':'||xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history WHERE version::int<=10")
    override fun prepareStore():OccurrenceRegistrationStore {
        createSchema()
        Flyway.configure().dataSource(dataSource(schema)).defaultSchema(schema).schemas(schema)
            .createSchemas(false).table("group33_schema_history").locations("classpath:db/migration")
            .target("10").baselineOnMigrate(false).cleanDisabled(true).group(true).loggers(*emptyArray<String>()).load().migrate()
        return PostgresPairingCodeStore(dataSource(schema),PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))))
    }
    @AfterEach fun cleanup(){dataSource().connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }
    private fun dataSnapshot():Map<String,String?> = listOf("anonymous_identity","pairing_issue_operation","pairing_relationship","medication_photo","medication","reminder_plan","reminder_plan_revision","registered_occurrence","occurrence_registration_operation","dose_group_operation","recorded_dose_event").associateWith { table ->
        scalar("SELECT COALESCE(jsonb_agg(v ORDER BY v::text)::text,'[]') FROM (SELECT to_jsonb(t)||jsonb_build_object('_xmin',t.xmin::text) AS v FROM $table t) rows")
    }
    private fun acceptedDose():DebugDoseGroupRequest {
        val e=evidence();accepted(register(e))
        dataSource(schema).connection.use { c -> c.prepareStatement("INSERT INTO anonymous_identity VALUES (?,?,?,?)").use { s ->
            listOf(elder.id,elder.installationId,"Fictional coordination elder","ELDER").forEachIndexed { i,v ->s.setString(i+1,v) };s.executeUpdate()
        } }
        clock.now.set(e.plannedAtEpochMillis+1_000)
        return DebugDoseGroupRequest(key().toString(),listOf(e.occurrence),DoseActionKind.TAKEN,clock.millis())
    }
    @Test fun `coord upgrade keeps original accepted dose and all business row versions`() {
        val input=acceptedDose();val groups=DebugDoseGroups(store,clock)
        val receipt=groups.submit(elder,elder.id,input);val before=dataSnapshot();val h=historyBeforeEleven()
        assertEquals(version-10,admin().migrate());assertEquals(before,dataSnapshot());assertEquals(h,historyBeforeEleven())
        assertEquals("V10__dose_action_groups.sql",scalar("SELECT script FROM group33_schema_history WHERE version='10'"))
        assertEquals("621384662",scalar("SELECT checksum FROM group33_schema_history WHERE version='10'"))
        assertEquals("-96611416",scalar("SELECT checksum FROM group33_schema_history WHERE version='11'"))
        clock.now.addAndGet(7_200_000);assertEquals(receipt,groups.submit(elder,elder.id,input))
        val date=java.time.LocalDate.parse(input.occurrences.single().localDate)
        assertEquals(receipt.events,groups.read(caregiver,elder.id,date,date))
        PairingDecisionService(store,clock).revoke(elder,relation.id,key())
        assertEquals("RESOURCE_NOT_ACCESSIBLE",assertThrows(ApiException::class.java){groups.read(caregiver,elder.id,date,date)}.code)
        admin().validate();RuntimeSchemaHistory.validate(dataSource(schema),schema)
    }
    @Test fun `coord runtime refuses V10 then validates current release with read only connection`() {
        val old=historyBeforeEleven();assertThrows(IllegalStateException::class.java){RuntimeSchemaHistory.validate(dataSource(schema),schema)}
        assertEquals(old,historyBeforeEleven());assertEquals(version-10,admin().migrate())
        val after=history();val xmin=historyBeforeEleven()
        RuntimeSchemaHistory.validate(dataSource(schema).apply {options="-c default_transaction_read_only=on"},schema)
        assertEquals(after,history());assertEquals(xmin,historyBeforeEleven());assertEquals(0,admin().migrate())
    }
    @Test fun `coord second index collision rolls back first index and original receipt survives retry`() {
        val input=acceptedDose();val groups=DebugDoseGroups(store,clock);val receipt=groups.submit(elder,elder.id,input)
        val before=dataSnapshot();val h=history()
        sql("CREATE TABLE reminder_plan_elder_collection (marker text)")
        assertThrows(IllegalStateException::class.java){admin().migrate()}
        assertEquals(h,history());assertEquals(before,dataSnapshot())
        assertNull(scalar("SELECT to_regclass('$schema.medication_elder_collection')::text"))
        assertNull(scalar("SELECT to_regclass('$schema.reminder_plan_revision_elder_collection')::text"))
        sql("DROP TABLE reminder_plan_elder_collection");assertEquals(version-10,admin().migrate())
        assertEquals(before,dataSnapshot());assertEquals(receipt,groups.submit(elder,elder.id,input))
    }
    @Test fun `coord already applied indexes V10 is refused without repair or data rewriting`() {
        reset();val location=directory.resolve("index-ten");Files.createDirectories(location)
        for(version in 1..9){
            val manifest=checkNotNull(javaClass.getResource("/db/runtime-schema-history.tsv")).readText().lines().first {it.startsWith("$version\t")}.split('\t')
            Files.write(location.resolve(manifest[1]),checkNotNull(javaClass.getResource("/db/migration/"+manifest[1])).readBytes())
        }
        Files.write(location.resolve("V10__plan_collection_indexes.sql"),checkNotNull(javaClass.getResource("/db/migration/V11__plan_collection_indexes.sql")).readBytes())
        Flyway.configure().dataSource(dataSource(schema)).defaultSchema(schema).schemas(schema).createSchemas(false)
            .table("group33_schema_history").locations("filesystem:"+location).target("10").baselineOnMigrate(false)
            .cleanDisabled(true).group(true).loggers(*emptyArray<String>()).load().migrate()
        val actor=PostgresAnonymousIdentityStore(dataSource(schema)).getOrCreate("coord-index-install","Fictional",UserRole.ELDER)
        val h=historyBeforeEleven()
        assertThrows(IllegalStateException::class.java){admin().migrate()}
        assertThrows(IllegalStateException::class.java){admin().validate()}
        assertThrows(IllegalStateException::class.java){RuntimeSchemaHistory.validate(dataSource(schema),schema)}
        assertEquals(h,historyBeforeEleven());assertEquals(actor,PostgresAnonymousIdentityStore(dataSource(schema)).findById(actor.id))
        assertNull(scalar("SELECT to_regclass('$schema.dose_group_operation')::text"))
    }
    @Test fun `coord fresh schema applies all current versions and existing features remain`() {
        reset();assertEquals(version,admin().migrate());assertEquals((1..version).joinToString(","),history())
        for(name in listOf("dose_group_operation","recorded_dose_event","medication_elder_collection","reminder_plan_elder_collection","reminder_plan_revision_elder_collection"))
            assertNotNull(scalar("SELECT to_regclass('$schema.$name')::text"))
        RuntimeSchemaHistory.validate(dataSource(schema),schema);admin().validate();assertEquals(0,admin().migrate())
    }
}
