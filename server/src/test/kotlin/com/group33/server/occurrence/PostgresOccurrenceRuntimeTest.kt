package com.group33.server.occurrence

import com.group33.server.config.PersistenceRuntimeConfiguration
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import java.util.UUID

@Tag("postgres")
internal class PostgresOccurrenceRuntimeTest {
    private val schema = "s2_occurrence_runtime_it_" + UUID.randomUUID().toString().replace("-", "")
    private fun sql(text: String) = dataSource(schema).connection.use { c -> c.createStatement().use { it.execute(text) } }
    @BeforeEach fun prepare() {
        dataSource().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val names = listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql",
            "pairing-directory-store-v4.sql", "anonymous-identity-store-v5.sql", "medication-photo-store-v6.sql",
            "plan-confirmation-store-v7.sql", "plan-mutation-store-v8.sql", "occurrence-registration-store-v9.sql")
        dataSource(schema).connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s -> names.forEach { s.execute(checkNotNull(javaClass.getResource("/db/prototypes/$it")).readText()) } }
            c.commit()
        }
    }
    @AfterEach fun cleanup() {
        dataSource().connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }
    private fun environment(enabled: Boolean = true) = MockEnvironment()
        .withProperty("app.persistence.mode", "postgres-local")
        .withProperty("app.persistence.jdbc-url", required("S2_PG_INTEGRATION_URL"))
        .withProperty("app.persistence.username", required("S2_PG_INTEGRATION_USER"))
        .withProperty("app.persistence.password", required("S2_PG_INTEGRATION_PASSWORD"))
        .withProperty("app.persistence.schema", schema)
        .withProperty("app.persistence.receipt-key-file", required("S2_PG_RECEIPT_KEY_FILE"))
        .withProperty("app.jwt.secret", "FICTIONAL_TEST_ONLY_OCCURRENCE_RUNTIME_SIGNING_2026")
        .withProperty("app.plan.enabled", "true").withProperty("app.photo.enabled", "true")
        .withProperty("app.occurrence.enabled", enabled.toString())
    @ParameterizedTest
    @ValueSource(strings = ["operation-table", "event-table", "request-column", "receipt-column"])
    fun `enabled runtime fails closed on missing registration persistence without exposing private configuration`(part: String) {
        when (part) {
            "operation-table" -> sql("DROP TABLE occurrence_registration_operation CASCADE")
            "event-table" -> sql("DROP TABLE registered_occurrence CASCADE")
            "request-column" -> sql("ALTER TABLE occurrence_registration_operation DROP COLUMN request_payload CASCADE")
            else -> sql("ALTER TABLE registered_occurrence DROP COLUMN receipt_payload CASCADE")
        }
        val failure = assertThrows(IllegalStateException::class.java) { PersistenceRuntimeConfiguration().create(environment()) }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(required("S2_PG_INTEGRATION_PASSWORD")))
        assertFalse(failure.toString().contains(schema))
    }
    @Test fun `disabled registration can use existing plan schema without occurrence tables`() {
        sql("DROP TABLE registered_occurrence CASCADE; DROP TABLE occurrence_registration_operation CASCADE")
        assertNotNull(PersistenceRuntimeConfiguration().create(environment(false)).pairing)
    }
    @Test fun `complete explicit schema supplies the same persistent registration and plan transaction`() {
        val runtime = PersistenceRuntimeConfiguration().create(environment())
        val registered = assertInstanceOf(OccurrenceRegistrationStore::class.java, runtime.pairing)
        assertNotNull(OccurrenceRegistrationHttpConfiguration().occurrenceRegistrationService(runtime, java.time.Clock.systemUTC()))
        assertNull(registered.registrationTransaction { it.occurrence(com.group33.shared.contract.OccurrenceKey(
            UUID.randomUUID().toString(), "2026-10-29", "08:00")) })
    }
}
