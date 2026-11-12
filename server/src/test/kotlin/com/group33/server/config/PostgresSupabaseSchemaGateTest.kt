package com.group33.server.config

import com.group33.server.migration.SchemaMigrations
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.mock.env.MockEnvironment

/** Actual local PG for the cloud runtime path, via internal constructor seam, never a TLS bypass flag. */
@Tag("postgres")
internal class PostgresSupabaseSchemaGateTest {
    private val schema = "group33_gate_it_" + UUID.randomUUID().toString().replace("-", "")
    private val source get() = dataSource(schema)
    private fun sql(text: String) = source.connection.use { it.createStatement().use { s -> s.execute(text) } }
    private fun scalar(text: String) = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery(text).use { r -> check(r.next()); r.getString(1) } } }
    private fun environment(gate: Boolean = true) = MockEnvironment()
        .withProperty("app.persistence.mode", "postgres-supabase")
        .withProperty("app.persistence.require-versioned-schema", gate.toString())
        .withProperty("app.persistence.schema", schema)
        .withProperty("app.persistence.receipt-key-file", required("S2_PG_RECEIPT_KEY_FILE"))
        .withProperty("app.jwt.secret", "FICTIONAL_CLOUD_SCHEMA_GATE_SIGNING_SECRET_2026")
        .withProperty("app.photo.enabled", "true").withProperty("app.plan.enabled", "true")
        .withProperty("app.occurrence.enabled", "true")
    private fun runtime(gate: Boolean = true, readonly: Boolean = false): PersistenceRuntime =
        PersistenceRuntimeConfiguration().create(environment(gate)) { _, mode ->
            assertEquals("postgres-supabase", mode)
            source.apply { if (readonly) options = "-c default_transaction_read_only=on" }
        }
    private fun digest() = scalar("SELECT md5(string_agg(h::text, ',' ORDER BY installed_rank)) FROM group33_schema_history h")
    @BeforeEach fun prepare() { dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } } }
    @AfterEach fun cleanup() { dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }

    @Test fun `cloud path accepts exact managed history using database enforced read only startup`() {
        assertEquals(10, SchemaMigrations(source, schema).migrate())
        val before = digest()
        assertNotNull(runtime(readonly = true).pairing)
        assertEquals(before, digest())
    }
    @Test fun `cloud path rejects manual tables and never creates or adopts history`() {
        for (name in listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql",
            "pairing-directory-store-v4.sql", "anonymous-identity-store-v5.sql", "medication-photo-store-v6.sql",
            "plan-confirmation-store-v7.sql", "plan-mutation-store-v8.sql", "occurrence-registration-store-v9.sql"))
            sql(checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText())
        assertNotNull(runtime(gate = false).pairing)
        val failure = assertThrows(IllegalStateException::class.java) { runtime() }
        assertNull(failure.cause); assertFalse(failure.toString().contains(schema))
        assertNull(scalar("SELECT to_regclass('$schema.group33_schema_history')::text"))
    }
    @Test fun `cloud bad history fails without repair while explicit old opt out remains compatible`() {
        SchemaMigrations(source, schema).migrate()
        sql("UPDATE group33_schema_history SET checksum=checksum+1 WHERE version='5'")
        val before = digest()
        val failure = assertThrows(IllegalStateException::class.java) { runtime() }
        assertNull(failure.cause); assertFalse(failure.toString().contains(required("S2_PG_INTEGRATION_PASSWORD")))
        assertEquals(before, digest())
        assertNotNull(runtime(gate = false).pairing)
    }
    @Test fun `production default factory still refuses loopback disguised as cloud`() {
        SchemaMigrations(source, schema).migrate()
        val env = environment().withProperty("app.persistence.jdbc-url", required("S2_PG_INTEGRATION_URL"))
            .withProperty("app.persistence.username", required("S2_PG_INTEGRATION_USER"))
            .withProperty("app.persistence.password", required("S2_PG_INTEGRATION_PASSWORD"))
            .withProperty("app.supabase.project-ref", "abcdefghijklmnopqrst")
        assertThrows(IllegalStateException::class.java) { PersistenceRuntimeConfiguration().create(env) }
    }
}
