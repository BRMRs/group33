package com.group33.server.config

import com.group33.server.migration.SchemaMigrations
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment
import java.util.UUID

@Tag("postgres")
internal class PostgresRuntimeSchemaHistoryTest {
    private val schema = "s2_startup_it_" + UUID.randomUUID().toString().replace("-", "")
    private fun sql(text: String) = dataSource(schema).connection.use { c -> c.createStatement().use { it.execute(text) } }
    private fun scalar(text: String): String? = dataSource(schema).connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(text).use { r -> check(r.next()); r.getString(1) } }
    }
    private fun history() = scalar("SELECT md5(string_agg(h::text, ',' ORDER BY installed_rank)) FROM group33_schema_history h")
    private fun settings(requiredHistory: Boolean = true) = linkedMapOf(
        "app.persistence.mode" to "postgres-local", "app.persistence.require-versioned-schema" to requiredHistory.toString(),
        "app.persistence.jdbc-url" to required("S2_PG_INTEGRATION_URL"),
        "app.persistence.username" to required("S2_PG_INTEGRATION_USER"),
        "app.persistence.password" to required("S2_PG_INTEGRATION_PASSWORD"),
        "app.persistence.schema" to schema, "app.persistence.receipt-key-file" to required("S2_PG_RECEIPT_KEY_FILE"),
        "app.jwt.secret" to "FICTIONAL_STABLE_SCHEMA_GATE_TEST_SECRET_2026",
        "app.photo.enabled" to "true", "app.plan.enabled" to "true", "app.occurrence.enabled" to "true",
    )
    private fun environment(requiredHistory: Boolean = true) = MockEnvironment().apply {
        settings(requiredHistory).forEach { (key, value) -> setProperty(key, value) }
    }
    private fun migrate() = SchemaMigrations(dataSource(schema), schema).migrate()
    private fun rejects() {
        val failure = assertThrows(IllegalStateException::class.java) { PersistenceRuntimeConfiguration().create(environment()) }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(required("S2_PG_INTEGRATION_PASSWORD")))
        assertFalse(failure.toString().contains(schema))
    }
    @BeforeEach fun prepare() { dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } } }
    @AfterEach fun cleanup() { dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }

    @Test fun `actual Flyway history matches release manifest and database enforced read only gate does not mutate it`() {
        assertEquals(9, migrate())
        val before = history()
        RuntimeSchemaHistory.validate(dataSource(schema).apply { options = "-c default_transaction_read_only=on" }, schema)
        assertNotNull(PersistenceRuntimeConfiguration().create(environment()).pairing)
        assertEquals(before, history())
        assertEquals("9", scalar("SELECT count(*) FROM group33_schema_history"))
    }

    @Test fun `empty schema fails without installing tables or history`() {
        rejects()
        assertEquals("0", scalar("SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace='$schema'::regnamespace"))
    }

    @Test fun `manually initialized business schema needs explicit adoption while old opt out stays compatible`() {
        val names = listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql",
            "pairing-directory-store-v4.sql", "anonymous-identity-store-v5.sql", "medication-photo-store-v6.sql",
            "plan-confirmation-store-v7.sql", "plan-mutation-store-v8.sql", "occurrence-registration-store-v9.sql")
        names.forEach { sql(checkNotNull(javaClass.getResource("/db/prototypes/$it")).readText()) }
        rejects()
        assertNull(scalar("SELECT to_regclass('$schema.group33_schema_history')::text"))
        assertNotNull(PersistenceRuntimeConfiguration().create(environment(false)).pairing)
        assertNull(scalar("SELECT to_regclass('$schema.group33_schema_history')::text"))
    }

    @Test fun `managed partial sequence is rejected unchanged until explicit migration`() {
        Flyway.configure().dataSource(dataSource(schema)).defaultSchema(schema).schemas(schema).createSchemas(false)
            .table("group33_schema_history").locations("classpath:db/migration").target("5")
            .baselineOnMigrate(false).cleanDisabled(true).group(true).loggers(*emptyArray<String>()).load().migrate()
        val before = history()
        rejects()
        assertEquals(before, history())
        assertNull(scalar("SELECT to_regclass('$schema.medication_photo')::text"))
        assertEquals(4, migrate())
        assertNotNull(PersistenceRuntimeConfiguration().create(environment()).pairing)
    }

    @ParameterizedTest
    @ValueSource(strings = ["checksum", "missing", "future", "failed", "script", "type", "null-checksum", "rank"])
    fun `incompatible history blocks startup without changing or repairing it`(kind: String) {
        migrate()
        sql(when (kind) {
            "checksum" -> "UPDATE group33_schema_history SET checksum=checksum+1 WHERE version='3'"
            "missing" -> "DELETE FROM group33_schema_history WHERE version='3'"
            "future" -> "INSERT INTO group33_schema_history VALUES (10,'10','future','SQL','V10__future.sql',1,current_user,current_timestamp,0,true)"
            "failed" -> "UPDATE group33_schema_history SET success=false WHERE version='3'"
            "script" -> "UPDATE group33_schema_history SET script='V3__other.sql' WHERE version='3'"
            "type" -> "UPDATE group33_schema_history SET type='BASELINE' WHERE version='3'"
            "null-checksum" -> "UPDATE group33_schema_history SET checksum=null WHERE version='3'"
            else -> "UPDATE group33_schema_history SET installed_rank=11 WHERE version='3'"
        })
        val before = history()
        rejects()
        assertEquals(before, history())
    }

    @Test fun `validation uses explicitly named schema rather than accepting a valid search path neighbour`() {
        migrate()
        assertThrows(IllegalStateException::class.java) { RuntimeSchemaHistory.validate(dataSource(schema), schema + "_absent") }
    }

    @Test fun `valid history does not bypass existing missing business column startup checks`() {
        migrate()
        sql("ALTER TABLE registered_occurrence DROP COLUMN receipt_payload CASCADE")
        val before = history()
        rejects()
        assertEquals(before, history())
    }

    @Test fun `actual Spring runtime bean creation is blocked before service construction and succeeds after explicit migration`() {
        val runner = ApplicationContextRunner().withUserConfiguration(PersistenceRuntimeConfiguration::class.java)
            .withPropertyValues(*settings().map { (key, value) -> "$key=$value" }.toTypedArray())
        runner.run { assertNotNull(it.startupFailure) }
        migrate()
        runner.run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(PersistenceRuntime::class.java))
        }
    }
}
