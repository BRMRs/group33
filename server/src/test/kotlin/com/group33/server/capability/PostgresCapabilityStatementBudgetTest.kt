package com.group33.server.capability

import com.group33.server.pairing.PairingHttpFixture
import com.group33.shared.contract.ReminderCapabilityReceipt
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real JDBC capability writes behind Boot/JWT/MVC; identity/pairing stay in memory in this fixture. */
@Tag("postgres")
@SpringBootTest(properties = ["app.capability.enabled=true"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(PostgresCapabilityStatementBudgetTest.Controls::class)
internal class PostgresCapabilityStatementBudgetTest(
    @Autowired mvc: org.springframework.test.web.servlet.MockMvc,
    @Autowired private val controlled: ControlledStore,
) {
    private val http = PairingHttpFixture(mvc)
    private val support = CapabilityPostgresTestSupport
    private val schema = "s2_capability_it_" + UUID.randomUUID().toString().replace("-", "")
    private fun store() = PostgresCapabilityStore(support.source(schema))
    private fun sql(text: String) = support.source(schema).connection.use { c -> c.createStatement().use { it.execute(text) } }
    private fun contents() = listOf("capability_observation", "capability_current", "capability_operation").map { table ->
        support.source(schema).connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text FROM $table t").use {
                assertTrue(it.next()); it.getString(1)
            }
        } }
    }
    private fun body(id: String, sequence: Long) = """{"reportId":"$id","observationSequence":$sequence,"observedAtEpochMillis":100,"deliveryCondition":"AVAILABLE","notificationPermissionGranted":true,"channelEnabled":true,"exactAlarmAccess":"GRANTED","reasons":["fictional budget fixture"]}"""
    private fun put(elder: PairingHttpFixture.Session, id: String, sequence: Long) =
        http.call(elder, "PUT", "/api/v1/elders/${elder.id}/reminder-capability", body(id, sequence), id)
    private fun receipt(response: org.springframework.mock.web.MockHttpServletResponse): ReminderCapabilityReceipt {
        assertEquals(200, response.status); http.noStore(response)
        return http.json.decodeFromString<ReminderCapabilityReceipt>(response.contentAsString)
    }
    @BeforeEach fun prepare() {
        support.admin("CREATE SCHEMA $schema")
        support.install(schema)
        controlled.backing = store()
    }
    @AfterEach fun cleanup() {
        controlled.backing = InMemoryCapabilityStore()
        support.admin("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    @ParameterizedTest @ValueSource(strings = ["insert", "update", "receipt", "deferred", "delayed"])
    fun `cancelled capability writes preserve all evidence and recover with the original operation`(kind: String) {
        val elder = http.login(UserRole.ELDER)
        val originalId = UUID.randomUUID().toString()
        val originalSequence = if (kind == "delayed") 9L else 1L
        val original = if (kind == "insert") null else receipt(put(elder, originalId, originalSequence))
        val before = contents()
        val id = UUID.randomUUID().toString()
        val sequence = if (kind in setOf("insert", "delayed")) 1L else 2L
        val table = when (kind) {
            "insert" -> "capability_observation"
            "update" -> "capability_current"
            else -> "capability_operation"
        }
        sql("CREATE FUNCTION slow_capability() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(8); RETURN NEW; END $$")
        val trigger = if (kind == "deferred")
            "CREATE CONSTRAINT TRIGGER slow_capability AFTER INSERT ON $table DEFERRABLE INITIALLY DEFERRED"
        else "CREATE TRIGGER slow_capability BEFORE ${if (kind == "update") "UPDATE" else "INSERT"} ON $table"
        sql("$trigger FOR EACH ROW EXECUTE FUNCTION slow_capability()")
        val started = System.nanoTime()
        val failed = put(elder, id, sequence)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        http.failure(failed, 503, "SERVICE_UNAVAILABLE", true)
        assertTrue(elapsed in 3_500..7_500, "Expected database statement cancellation before the eight-second fault completes")
        assertNull(failed.getHeader("WWW-Authenticate"))
        for (privateText in listOf(schema, "slow_capability", "pg_sleep", "statement timeout"))
            assertFalse(failed.contentAsString.contains(privateText))
        assertEquals(before, contents(), "Cancelled write must retain original rows without partial evidence or receipt")
        sql("DROP TRIGGER slow_capability ON $table; DROP FUNCTION slow_capability()")
        val recovered = receipt(put(elder, id, sequence))
        assertEquals(id, recovered.reportId)
        assertEquals(if (kind == "delayed") originalSequence else sequence, recovered.current.observationSequence)
        assertEquals(kind != "delayed", recovered.applied)
        if (kind == "delayed") assertEquals(original!!.current, recovered.current)
        assertEquals(recovered, receipt(put(elder, id, sequence)))
        if (original != null) assertEquals(original, receipt(put(elder, originalId, originalSequence)))
        http.failure(put(elder, id, sequence + 100), 409, "IDEMPOTENCY_KEY_REUSED")
    }

    @Test fun `slow current read is cancelled by PostgreSQL and does not damage accepted evidence`() {
        val elder = support.elder
        val input = support.report()
        val service = CapabilityService(store(), com.group33.server.auth.CareRelationshipAccess { _, _ -> false }, CapabilityDatabaseClock())
        val original = assertInstanceOf(CapabilityAcceptance.Accepted::class.java,
            service.report(elder, elder.id, UUID.fromString(input.reportId), input)).receipt
        val before = contents()
        sql("ALTER TABLE capability_current RENAME TO saved_capability_current")
        sql("CREATE VIEW capability_current AS SELECT c.* FROM saved_capability_current c CROSS JOIN (SELECT pg_sleep(30)) wait")
        try {
            val started = System.nanoTime()
            val failure = assertThrows(SQLException::class.java) { service.lastKnown(elder, elder) }
            assertEquals("57014", failure.sqlState, "Server cancellation must precede the socket timeout")
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) in 3_500..8_000)
        } finally {
            // With the old adapter a socket timeout can leave its server query running.
            // Cancel only this fixture's exact read before taking a DDL cleanup lock.
            sql("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE application_name = '$schema' AND pid <> pg_backend_pid() AND state = 'active' AND query LIKE 'SELECT view_payload FROM capability_current WHERE%'")
            sql("DROP VIEW capability_current; ALTER TABLE saved_capability_current RENAME TO capability_current")
        }
        assertEquals(before, contents())
        assertEquals(original.current, assertInstanceOf(CapabilityRead.Current::class.java, service.lastKnown(elder, elder)).view)
        assertEquals(original, assertInstanceOf(CapabilityAcceptance.Accepted::class.java,
            service.report(elder, elder.id, UUID.fromString(input.reportId), input)).receipt)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Controls { @Bean @Primary fun controlledCapabilityStore() = ControlledStore() }
    class ControlledStore : CapabilityStore {
        @Volatile var backing: CapabilityStore = InMemoryCapabilityStore()
        override fun <T> transaction(work: (CapabilityTransaction) -> T): T = backing.transaction(work)
    }
}
