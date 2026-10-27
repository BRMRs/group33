package com.group33.server.capability

import com.group33.server.config.PersistenceRuntimeConfiguration
import com.group33.server.auth.PostgresAnonymousIdentityStore
import com.group33.server.pairing.PostgresPairingCodeStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import java.util.UUID

@Tag("postgres")
internal class CapabilityRuntimePostgresTest {
    private val schema = "s2_capability_it_" + UUID.randomUUID().toString().replace("-", "")
    private val support = CapabilityPostgresTestSupport
    @BeforeEach fun prepare() {
        support.admin("CREATE SCHEMA $schema")
        support.source(schema).connection.use { c ->
            c.autoCommit = false
            listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql",
                "pairing-directory-store-v4.sql", "anonymous-identity-store-v5.sql", "capability-store-v1.sql").forEach { file ->
                c.createStatement().use { it.execute(checkNotNull(javaClass.getResource("/db/prototypes/$file")).readText()) }
            }
            c.commit()
        }
    }
    @AfterEach fun cleanup() { support.admin("DROP SCHEMA IF EXISTS $schema CASCADE") }
    private fun sql(text: String) = support.source(schema).connection.use { c -> c.createStatement().use { it.execute(text) } }
    private fun environment(enabled: Boolean = true) = MockEnvironment()
        .withProperty("app.persistence.mode", "postgres-local").withProperty("app.capability.enabled", enabled.toString())
        .withProperty("app.persistence.jdbc-url", support.required("S2_PG_INTEGRATION_URL"))
        .withProperty("app.persistence.username", support.required("S2_PG_INTEGRATION_USER"))
        .withProperty("app.persistence.password", support.required("S2_PG_INTEGRATION_PASSWORD"))
        .withProperty("app.persistence.schema", schema)
        .withProperty("app.persistence.receipt-key-file", support.required("S2_PG_RECEIPT_KEY_FILE"))
        .withProperty("app.jwt.secret", "FICTIONAL_TEST_ONLY_SIGNING_CONFIGURATION_2026")
    @Test fun `opt in selects durable identities pairing and capability together without photo dependency`() {
        val runtime = PersistenceRuntimeConfiguration().create(environment())
        assertInstanceOf(PostgresAnonymousIdentityStore::class.java, runtime.identities)
        assertInstanceOf(PostgresPairingCodeStore::class.java, runtime.pairing)
        assertInstanceOf(PostgresCapabilityStore::class.java, runtime.capability)
        assertEquals("PersistenceRuntime(REDACTED)", runtime.toString())
    }
    @ParameterizedTest @ValueSource(strings = ["capability_guard", "capability_observation", "capability_current", "capability_operation", "guard-row", "column"])
    fun `missing required capability schema fails closed without fallback or sensitive cause`(missing: String) {
        when (missing) {
            "guard-row" -> sql("DELETE FROM capability_guard")
            "column" -> sql("ALTER TABLE capability_operation RENAME COLUMN receipt_payload TO missing_payload")
            else -> sql("ALTER TABLE $missing RENAME TO temporarily_missing_capability")
        }
        val failure = assertThrows(IllegalStateException::class.java) { PersistenceRuntimeConfiguration().create(environment()) }
        assertNull(failure.cause); assertFalse(failure.toString().contains(schema))
        assertFalse(failure.toString().contains(support.required("S2_PG_INTEGRATION_PASSWORD")))
    }
    @Test fun `disabled feature does not require or create capability tables`() {
        sql("DROP TABLE capability_operation, capability_current, capability_observation, capability_guard CASCADE")
        assertNull(PersistenceRuntimeConfiguration().create(environment(false)).capability)
        support.source(schema).connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT to_regclass('capability_guard')").use { rows -> assertTrue(rows.next()); assertNull(rows.getString(1)) }
        } }
    }
}
