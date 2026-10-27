package com.group33.server.pairing

import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.postgresql.ds.PGSimpleDataSource

/** Real Boot filters/JWT/controller/services and JDBC transactions. Only DB selection is test configuration. */
@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(PostgresPairingLifecycleHttpTest.DatabaseSelection::class)
internal class PostgresPairingLifecycleHttpTest(@Autowired mvc: MockMvc, @Autowired private val holder: SelectedDatabase) {
    private val http = PairingHttpFixture(mvc)
    private val schema = "s2_http_it_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var source: PGSimpleDataSource
    private val path = PairingHttpFixture.REQUESTS

    @BeforeEach fun prepare() {
        source = PostgresPairingCodeStoreTest.dataSource(schema)
        PostgresPairingCodeStoreTest.dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        source.connection.use { c ->
            c.autoCommit = false
            listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql").forEach { name ->
                checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText().split(';').filter(String::isNotBlank)
                    .forEach { sql -> c.createStatement().use { it.execute(sql) } }
            }
            c.commit()
        }
        holder.backing = PostgresPairingCodeStore(source, PairingReceiptCipher(Files.readAllBytes(Path.of(PostgresPairingCodeStoreTest.required("S2_PG_RECEIPT_KEY_FILE")))))
    }
    @AfterEach fun cleanup() {
        PostgresPairingCodeStoreTest.dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }
    private fun sql(value: String) { source.connection.use { it.createStatement().use { s -> s.execute(value) } } }
    private fun scalar(value: String) = source.connection.use { c -> c.createStatement().use { s -> s.executeQuery(value).use { r -> check(r.next()); r.getString(1) } } }

    @Test fun `real JWT HTTP and PostgreSQL share one pairing lifecycle and immutable original receipts`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER)
        val code = http.issue(e); val requestOp = UUID.randomUUID().toString()
        val p = http.request(http.submit(c, code.code, requestOp), 201); val op = UUID.randomUUID().toString()
        val r = http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op))
        assertEquals(r.id, scalar("SELECT relationship_id FROM pairing_request WHERE id = '${p.id}'"))
        assertEquals("ACTIVE", scalar("SELECT status FROM pairing_relationship WHERE id = '${r.id}'"))
        val revoked = http.relationship(http.call(c, "POST", "/api/v1/care-relationships/${r.id}/revoke"))
        assertEquals(CareRelationshipStatus.REVOKED, revoked.status)
        assertFalse(StoredCareRelationshipAccess(holder).isActive(c.id, e.id))
        assertEquals(r, http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op)))
        assertEquals(p, http.request(http.submit(c, code.code, requestOp), 201))
        assertEquals("REVOKED", scalar("SELECT status FROM pairing_relationship WHERE id = '${r.id}'"))
    }
    @Test fun `persisted reject and cancellation are visible to participants and code cannot reopen`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER)
        val p = http.pending(e, c)
        assertEquals(PairingRequestStatus.REJECTED, http.request(http.call(e, "POST", "$path/${p.id}/reject")).status)
        val second = http.pending(e, c); val op = UUID.randomUUID().toString()
        repeat(2) { assertEquals(204, http.call(c, "DELETE", "$path/${second.id}", null, op).status) }
        assertEquals(PairingClosedReason.CAREGIVER_CANCELLED, http.request(http.call(e, "GET", "$path/${second.id}", null)).closedReason)
        http.failure(http.call(e, "POST", "$path/${second.id}/accept"), 409, "PAIRING_REQUEST_CLOSED")
        assertEquals("0", scalar("SELECT count(*) FROM pairing_relationship"))
    }
    @ParameterizedTest @ValueSource(strings = ["ISSUE", "REQUEST", "ACCEPT", "REVOKE"])
    fun `real deferred SQL commit failure maps to safe 503 and original HTTP key retries atomically`(action: String) {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER)
        val code = if (action == "ISSUE") null else http.issue(e)
        val p = if (action in setOf("ISSUE", "REQUEST")) null else http.request(http.submit(c, code!!.code), 201)
        val r = if (action == "REVOKE") http.relationship(http.call(e, "POST", "$path/${p!!.id}/accept")) else null
        val op = UUID.randomUUID().toString()
        val table = when (action) { "ISSUE" -> "pairing_issue_operation"; "REQUEST" -> "pairing_request_operation"; else -> "pairing_decision_operation" }
        sql("""CREATE FUNCTION fail_http_receipt() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'PRIVATE injected HTTP commit failure'; END ${'$'}${'$'}""")
        sql("CREATE CONSTRAINT TRIGGER fail_http_receipt AFTER INSERT ON $table DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_http_receipt()")
        fun act() = when (action) {
            "ISSUE" -> http.call(e, "POST", "/api/v1/pairing-codes", key = op)
            "REQUEST" -> http.submit(c, code!!.code, op)
            "ACCEPT" -> http.call(e, "POST", "$path/${p!!.id}/accept", key = op)
            else -> http.call(c, "POST", "/api/v1/care-relationships/${r!!.id}/revoke", key = op)
        }
        val response = act(); http.failure(response, 503, "SERVICE_UNAVAILABLE", true)
        assertFalse(response.contentAsString.contains("PRIVATE")); assertFalse(response.contentAsString.contains(schema))
        assertEquals(if (action in setOf("ISSUE", "REQUEST")) "0" else "1", scalar("SELECT count(*) FROM pairing_request"))
        assertEquals(if (action == "REVOKE") "1" else "0", scalar("SELECT count(*) FROM pairing_relationship"))
        if (action == "REVOKE") assertEquals("ACTIVE", scalar("SELECT status FROM pairing_relationship"))
        sql("DROP TRIGGER fail_http_receipt ON $table")
        val success = act()
        when (action) {
            "ISSUE" -> { assertEquals(201, success.status); assertEquals("1", scalar("SELECT count(*) FROM pairing_active_code")) }
            "REQUEST" -> http.request(success, 201)
            else -> http.relationship(success)
        }
        assertEquals(success.contentAsString, act().contentAsString)
    }
    @Test fun `valid foreign JWT never reads or changes another persisted request`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val outsider = http.login(UserRole.ELDER)
        val p = http.pending(e, c)
        http.failure(http.call(outsider, "GET", "$path/${p.id}", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(outsider, "POST", "$path/${p.id}/accept"), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(c, "POST", "$path/${p.id}/accept"), 403, "ROLE_NOT_ALLOWED")
        assertEquals("PENDING", scalar("SELECT status FROM pairing_request"))
        assertEquals("0", scalar("SELECT count(*) FROM pairing_relationship"))
    }

    @TestConfiguration(proxyBeanMethods = false) class DatabaseSelection { @Bean @Primary fun selectedDatabase() = SelectedDatabase() }
    class SelectedDatabase : PairingDecisionStore {
        lateinit var backing: PostgresPairingCodeStore
        override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = backing.transaction(work)
        override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T = backing.requestTransaction(work)
        override fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T = backing.decisionTransaction(work)
    }
}
