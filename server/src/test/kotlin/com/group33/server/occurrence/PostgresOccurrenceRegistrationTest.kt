package com.group33.server.occurrence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.shared.contract.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("postgres")
internal class PostgresOccurrenceRegistrationTest : OccurrenceRegistrationContract() {
    private val schema="s2_occurrence_it_"+UUID.randomUUID().toString().replace("-", "")
    private fun sql(text:String) = dataSource(schema).connection.use { c -> c.createStatement().use { it.execute(text) } }
    private fun adapter(wait:Long=10_000)=PostgresPairingCodeStore(dataSource(schema),PairingReceiptCipher(
        Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))),wait)
    override fun prepareStore():OccurrenceRegistrationStore {
        dataSource().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val names=listOf("pairing-code-store-v1.sql","pairing-request-store-v2.sql","pairing-decision-store-v3.sql",
            "pairing-directory-store-v4.sql","anonymous-identity-store-v5.sql","medication-photo-store-v6.sql",
            "plan-confirmation-store-v7.sql","plan-mutation-store-v8.sql","occurrence-registration-store-v9.sql")
        dataSource(schema).connection.use { c ->c.autoCommit=false;c.createStatement().use { s ->
            names.forEach { s.execute(checkNotNull(javaClass.getResource("/db/prototypes/$it")).readText()) }
        };c.commit() }
        return adapter()
    }
    @AfterEach fun cleanup() { dataSource().connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }
    private fun counts()=dataSource(schema).connection.use { c -> c.createStatement().use { s ->
        listOf("registered_occurrence","occurrence_registration_operation").map { name ->
            s.executeQuery("SELECT count(*) FROM $name").use { r ->check(r.next());r.getInt(1) }
        }
    } }
    @ParameterizedTest @ValueSource(strings=["first-event","second-event","operation","commit"])
    fun `actual SQL faults including second event and commit roll back complete batch`(point:String) {
        val a=evidence();val b=evidence(time="20:00");val op=key()
        val table=if(point in listOf("operation","commit")) "occurrence_registration_operation" else "registered_occurrence"
        val condition=if(point=="second-event") "IF NEW.local_time <> '20:00' THEN RETURN NEW; END IF;" else ""
        sql("CREATE FUNCTION fail_registration_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN $condition RAISE EXCEPTION 'PRIVATE fictional failure'; END $$")
        val trigger=if(point=="commit") "CREATE CONSTRAINT TRIGGER fail_registration AFTER INSERT ON $table DEFERRABLE INITIALLY DEFERRED"
            else "CREATE TRIGGER fail_registration BEFORE INSERT ON $table"
        sql("$trigger FOR EACH ROW EXECUTE FUNCTION fail_registration_write()")
        rejected(register(a,b,operation=op),RegistrationRejection.SERVICE_UNAVAILABLE);assertEquals(listOf(0,0),counts())
        sql("DROP TRIGGER fail_registration ON $table;DROP FUNCTION fail_registration_write()")
        val first=accepted(register(a,b,operation=op));assertEquals(listOf(2,1),counts())
        assertEquals(first,accepted(register(a,b,operation=op)))
    }
    @Test fun `database rejects receipt or event rewrite deletion and partial batch response`() {
        val a=evidence();val first=accepted(register(a));val before=counts()
        for(text in listOf("UPDATE registered_occurrence SET local_time='20:00'", "DELETE FROM registered_occurrence",
            "UPDATE occurrence_registration_operation SET response_payload=jsonb_set(response_payload,'{items}','[]')",
            "UPDATE occurrence_registration_operation SET request_payload=jsonb_set(request_payload,'{items}','[]')",
            "DELETE FROM occurrence_registration_operation")) assertThrows(SQLException::class.java) { sql(text) }
        assertEquals(before,counts());assertEquals(first,accepted(register(a)))
    }
    @Test fun `independent adapters share canonical identity and bounded database row lock`() {
        val e=evidence();val op=key();val first=accepted(register(e,operation=op))
        assertEquals(first,accepted(service(adapter()).register(elder,elder.id,key(),OccurrenceRegistrationRequest(listOf(e)))))
        dataSource(schema).connection.use { blocker ->
            blocker.autoCommit=false;blocker.createStatement().use { it.executeQuery("SELECT id FROM pairing_issuance_guard WHERE id=1 FOR UPDATE").close() }
            try { rejected(service(adapter(0)).register(elder,elder.id,op,OccurrenceRegistrationRequest(listOf(e))),RegistrationRejection.OPERATION_IN_PROGRESS) }
            finally { blocker.rollback() }
        }
        assertEquals(first,accepted(register(e,operation=op)))
    }
    private fun control(action:String) {
        val child=ProcessBuilder(required("S2_PG_PYTHON"),required("S2_PG_MANAGER_SCRIPT"),action).redirectErrorStream(true).start()
        try { assertTrue(child.waitFor(40,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),"Private database control failed") }
        finally { if(child.isAlive) child.destroyForcibly() }
    }
    @Test fun `actual database stop start preserves original IDs receipts and rejected bindings`() {
        val e=evidence();val op=key();val original=accepted(register(e,operation=op));val rejectedKey=key()
        val invalid=e.copy(generationId=key().toString(),planRevision=99);rejected(register(invalid,operation=rejectedKey))
        try { control("stop");rejected(register(e,operation=op),RegistrationRejection.SERVICE_UNAVAILABLE) }
        finally { control("start") }
        clock.now.addAndGet(2*86_400_000)
        assertEquals(original,accepted(service(adapter()).register(elder,elder.id,op,OccurrenceRegistrationRequest(listOf(e)))))
        rejected(register(e,operation=rejectedKey),RegistrationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(listOf(1,2),counts())
    }
    private fun process(evidence:OccurrenceGenerationEvidence,op:UUID,now:Long):String {
        val payload=Base64.getEncoder().encodeToString(jacksonObjectMapper().writeValueAsBytes(evidence))
        val child=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("s2.postgres.test.classpath"),
            "com.group33.server.occurrence.OccurrenceRegistrationProcessProbe",schema,op.toString(),now.toString(),payload).redirectErrorStream(true).start()
        try { assertTrue(child.waitFor(20,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),"Separate JVM failed; private output omitted")
            return child.inputStream.bufferedReader().readText().trim() }
        finally { if(child.isAlive) child.destroyForcibly() }
    }
    @Test fun `issuing and replay JVM exit preserves original result after another registration`() {
        val e=evidence();val op=key();val first=process(e,op,clock.millis())
        process(evidence(time="20:00"),key(),clock.millis()+1234)
        assertEquals(first,process(e,op,clock.millis()+86_400_000));assertEquals(listOf(2,2),counts())
    }
    @Test fun `schema reapplication retains evidence and accepted operation`() {
        val e=evidence();val op=key();val first=accepted(register(e,operation=op))
        sql(checkNotNull(javaClass.getResource("/db/prototypes/occurrence-registration-store-v9.sql")).readText())
        assertEquals(first,accepted(register(e,operation=op)));assertEquals(listOf(1,1),counts())
    }
}
