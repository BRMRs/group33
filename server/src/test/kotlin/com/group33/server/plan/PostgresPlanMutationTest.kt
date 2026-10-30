package com.group33.server.plan

import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.shared.contract.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
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

    private fun readStore(captured: AtomicReference<Connection>? = null, wait: Long = 1_000): PostgresPairingCodeStore {
        val actual = dataSource(schema)
        val observed = object : DataSource by actual {
            override fun getConnection(): Connection = actual.connection.also { captured?.set(it) }
        }
        return PostgresPairingCodeStore(observed,
            PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))), wait)
    }
    private fun reader(value: PlanConfirmationStore = readStore()) =
        ReminderPlanConfirmationService(value, LocalFilePhotoObjectStore(directory.resolve("objects")), clock)

    @Test fun `plan snapshot is read only repeatable read on the actual server and closes its connection`() {
        val captured = AtomicReference<Connection>()
        readStore(captured).planReadTransaction { tx ->
            val connection = captured.get()
            assertTrue(connection.isReadOnly)
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.transactionIsolation)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_setting('transaction_read_only'), current_setting('transaction_isolation')").use {
                    assertTrue(it.next()); assertEquals("on", it.getString(1)); assertEquals("repeatable read", it.getString(2))
                }
            }
            assertEquals(relation, tx.activeRelationship(elder.id))
            assertEquals(listOf(original.medication), tx.medications(elder.id))
        }
        assertTrue(captured.get().isClosed)
    }

    @Test fun `database rejects accidental writes from the read snapshot and the connection is rolled back and closed`() {
        val captured = AtomicReference<Connection>()
        val failure = assertThrows(SQLException::class.java) {
            readStore(captured).planReadTransaction {
                captured.get().createStatement().use { it.executeUpdate("UPDATE pairing_issuance_guard SET id = id") }
            }
        }
        assertEquals("25006", failure.sqlState)
        assertTrue(captured.get().isClosed)
        assertEquals(listOf(original.plan), assertInstanceOf(PlanConfirmationResult.Plans::class.java,
            reader().plans(caregiver, elder.id)).value.items)
    }

    @ParameterizedTest @ValueSource(strings = ["plans", "medications"])
    fun `actual GET service keeps its authorization and resources in one snapshot while an edit and revoke commit`(kind: String) {
        val authorized = CountDownLatch(1); val continueRead = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val actual = readStore()
        val paused = object : PlanConfirmationStore by actual {
            override fun <T> planReadTransaction(work: (PlanReadTransaction) -> T): T = actual.planReadTransaction { tx ->
                work(object : PlanReadTransaction by tx {
                    override fun activeRelationship(elderId: String): CareRelationshipView? {
                        val result = tx.activeRelationship(elderId)
                        authorized.countDown()
                        check(continueRead.await(10, TimeUnit.SECONDS))
                        return result
                    }
                })
            }
        }
        try {
            val read = executor.submit(Callable {
                if (kind == "plans") reader(paused).plans(caregiver, elder.id) else reader(paused).medications(caregiver, elder.id)
            })
            assertTrue(authorized.await(5, TimeUnit.SECONDS))
            val write = executor.submit(Callable {
                accepted(update())
                assertInstanceOf(PairingDecisionResult.Relationship::class.java,
                    PairingDecisionService(store, clock).revoke(elder, relation.id, key()))
            })
            // Proves there is no read-held issuance guard, rather than merely reading after the writer.
            write.get(3, TimeUnit.SECONDS)
            continueRead.countDown()
            val result = read.get(5, TimeUnit.SECONDS)
            if (kind == "plans") {
                val collection = assertInstanceOf(PlanConfirmationResult.Plans::class.java, result).value
                assertEquals(listOf(original.plan), collection.items)
                assertEquals(listOf(PlanConfirmationCopies.initialRevision(original)), collection.planRevisions)
            } else assertEquals(listOf(original.medication),
                assertInstanceOf(PlanConfirmationResult.Medications::class.java, result).items)
            assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE),
                reader().plans(caregiver, elder.id))
            assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE),
                reader().medications(caregiver, elder.id))
            val latest = assertInstanceOf(PlanConfirmationResult.Plans::class.java, reader().plans(elder, elder.id)).value
            assertEquals(2L, latest.items.single().revision)
            assertEquals(listOf(1L, 2L), latest.planRevisions.map { it.revision })
            assertEquals(original, store.confirmationTransaction {
                it.confirmationOperation(PlanConfirmationKey(caregiver.id, confirmKey))!!.receipt
            })
        } finally {
            continueRead.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun `authorized collection reads do not wait behind an unrelated write guard`() {
        dataSource(schema).connection.use { blocking ->
            blocking.autoCommit = false
            blocking.createStatement().use { it.executeQuery("SELECT id FROM pairing_issuance_guard WHERE id = 1 FOR UPDATE").close() }
            try {
                val service = reader(readStore(wait = 100))
                assertEquals(listOf(original.medication), assertInstanceOf(PlanConfirmationResult.Medications::class.java,
                    service.medications(caregiver, elder.id)).items)
                assertEquals(listOf(original.plan), assertInstanceOf(PlanConfirmationResult.Plans::class.java,
                    service.plans(caregiver, elder.id)).value.items)
            } finally { blocking.rollback() }
        }
    }

    @Test fun `actual table lock is bounded and a later read recovers without changing medical records`() {
        dataSource(schema).connection.use { blocking ->
            blocking.autoCommit = false
            blocking.createStatement().use { it.execute("LOCK TABLE reminder_plan IN ACCESS EXCLUSIVE MODE") }
            try {
                assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.OPERATION_IN_PROGRESS),
                    reader(readStore(wait = 100)).plans(caregiver, elder.id))
            } finally { blocking.rollback() }
        }
        assertEquals(listOf(original.plan), assertInstanceOf(PlanConfirmationResult.Plans::class.java,
            reader().plans(caregiver, elder.id)).value.items)
        assertEquals(listOf(original.medication), state().first)
        assertEquals(1, state().third.size)
    }

    @Test fun `read callback failure closes the transaction and does not break the next authorized read`() {
        val captured = AtomicReference<Connection>()
        assertThrows(IllegalStateException::class.java) {
            readStore(captured).planReadTransaction { tx -> tx.plans(elder.id); error("fictional read failure") }
        }
        assertTrue(captured.get().isClosed)
        assertEquals(listOf(original.plan), assertInstanceOf(PlanConfirmationResult.Plans::class.java,
            reader().plans(caregiver, elder.id)).value.items)
    }

    @Test fun `actual long database statement is cancelled with a controlled unavailable result and later reads recover`() {
        val captured = AtomicReference<Connection>()
        val actual = readStore(captured)
        val stalled = object : PlanConfirmationStore by actual {
            override fun <T> planReadTransaction(work: (PlanReadTransaction) -> T): T = actual.planReadTransaction { tx ->
                captured.get().createStatement().use { it.execute("SELECT pg_sleep(30)") }
                work(tx)
            }
        }
        val started = System.nanoTime()
        assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.SERVICE_UNAVAILABLE),
            reader(stalled).plans(caregiver, elder.id))
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsed in 3_500..12_000, "Statement must be cancelled within the read budget")
        assertTrue(captured.get().isClosed)
        assertEquals(listOf(original.plan), assertInstanceOf(PlanConfirmationResult.Plans::class.java,
            reader().plans(caregiver, elder.id)).value.items)
    }

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
