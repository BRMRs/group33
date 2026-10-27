package com.group33.server.plan

import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.shared.contract.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("postgres")
internal class PostgresPlanMutationTest : PlanMutationStoreContract() {
    private val schema="s2_mutation_it_"+UUID.randomUUID().toString().replace("-","")
    private fun sql(text:String)=dataSource(schema).connection.use { c -> c.createStatement().use { it.execute(text) } }
    override fun prepareStore(): PlanMutationStore {
        dataSource().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val names=listOf("pairing-code-store-v1.sql","pairing-request-store-v2.sql","pairing-decision-store-v3.sql",
            "pairing-directory-store-v4.sql","anonymous-identity-store-v5.sql","medication-photo-store-v6.sql",
            "plan-confirmation-store-v7.sql","plan-mutation-store-v8.sql")
        dataSource(schema).connection.use { c ->
            c.autoCommit=false
            c.createStatement().use { s -> names.forEach { s.execute(checkNotNull(javaClass.getResource("/db/prototypes/$it")).readText()) } }
            c.commit()
        }
        return PostgresPairingCodeStore(dataSource(schema),PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))) ,10_000)
    }
    @AfterEach fun cleanup() { dataSource().connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }
    @ParameterizedTest @ValueSource(strings=["medication","reminder_plan","reminder_plan_revision","plan_mutation_operation","commit"])
    fun `actual SQL and deferred commit faults leave no partial edit and original intent recovers`(point:String) {
        val saved=state(); val id=key()
        val table=if(point=="commit") "plan_mutation_operation" else point
        sql("CREATE FUNCTION fail_mutation_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fictional SQL fault'; RETURN NEW; END $$;")
        val trigger=if(point=="commit") "CREATE CONSTRAINT TRIGGER fail_mutation AFTER INSERT ON $table DEFERRABLE INITIALLY DEFERRED"
            else "CREATE TRIGGER fail_mutation BEFORE ${if(point in listOf("medication","reminder_plan")) "UPDATE" else "INSERT"} ON $table"
        sql("$trigger FOR EACH ROW EXECUTE FUNCTION fail_mutation_write();")
        rejected(update(id=id),PlanMutationRejection.SERVICE_UNAVAILABLE); assertEquals(saved,state())
        assertNull(store.mutationTransaction { it.mutationOperation(PlanMutationKey(caregiver.id,PlanMutationAction.UPDATE,id)) })
        sql("DROP TRIGGER fail_mutation ON $table; DROP FUNCTION fail_mutation_write();")
        accepted(update(id=id)); assertEquals(2,state().third.size)
    }
    @Test fun `database forbids current version without mutation receipt and retained history rewrite`() {
        val saved=state()
        assertThrows(SQLException::class.java) { sql("UPDATE reminder_plan SET revision=2 WHERE id='${original.plan.id}'") }
        assertThrows(SQLException::class.java) { sql("UPDATE reminder_plan_revision SET display_name='changed fixture' WHERE plan_id='${original.plan.id}'") }
        assertEquals(saved,state())
    }
}
