package com.group33.server.pairing

import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
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

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(PostgresPairingDirectoryTest.Selection::class)
internal class PostgresPairingDirectoryTest(@Autowired mvc: MockMvc, @Autowired private val selected: Selected) {
    private val http = PairingHttpFixture(mvc)
    private val schema = "s2_directory_it_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var source: PGSimpleDataSource
    @BeforeEach fun prepare() {
        source = PostgresPairingCodeStoreTest.dataSource(schema)
        PostgresPairingCodeStoreTest.dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        install("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql", "pairing-directory-store-v4.sql")
        selected.backing = PostgresPairingCodeStore(source, PairingReceiptCipher(Files.readAllBytes(Path.of(PostgresPairingCodeStoreTest.required("S2_PG_RECEIPT_KEY_FILE")))))
    }
    @AfterEach fun cleanup() { PostgresPairingCodeStoreTest.dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }
    private fun install(vararg names: String) { source.connection.use { c -> c.autoCommit = false
        names.forEach { name -> checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText().split(';').filter(String::isNotBlank).forEach { sql -> c.createStatement().use { it.execute(sql) } } }; c.commit()
    } }
    private fun sql(value: String) { source.connection.use { it.createStatement().use { s -> s.execute(value) } } }
    private fun scalar(value: String) = source.connection.use { it.createStatement().use { s -> s.executeQuery(value).use { r -> check(r.next()); r.getString(1) } } }
    private fun requests(s: PairingHttpFixture.Session, suffix: String = ""): List<PairingRequestView> {
        val response = http.call(s, "GET", "/api/v1/pairing-requests$suffix", null); assertEquals(200, response.status); http.noStore(response)
        return http.json.decodeFromString<ItemsResponse<PairingRequestView>>(response.contentAsString).items
    }
    private fun relationships(s: PairingHttpFixture.Session): List<CareRelationshipView> {
        val response = http.call(s, "GET", "/api/v1/care-relationships", null); assertEquals(200, response.status)
        return http.json.decodeFromString<ItemsResponse<CareRelationshipView>>(response.contentAsString).items
    }
    private fun key() = UUID.randomUUID().toString()

    @Test fun `real JWT collection queries return only participant histories and active or revoked relationships`() {
        val e = http.login(UserRole.ELDER); val other = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val outsider = http.login(UserRole.CAREGIVER)
        val p = http.pending(e, c); val r = http.relationship(http.call(e, "POST", "/api/v1/pairing-requests/${p.id}/accept"))
        http.relationship(http.call(c, "POST", "/api/v1/care-relationships/${r.id}/revoke"))
        val p2 = http.pending(other, c); http.relationship(http.call(other, "POST", "/api/v1/pairing-requests/${p2.id}/accept"))
        assertEquals(setOf(p.id, p2.id), requests(c).map { it.id }.toSet())
        assertEquals(listOf(p.id), requests(e, "?status=ACCEPTED").map { it.id })
        assertEquals(setOf(CareRelationshipStatus.ACTIVE, CareRelationshipStatus.REVOKED), relationships(c).map { it.status }.toSet())
        assertTrue(requests(outsider).isEmpty()); assertTrue(relationships(outsider).isEmpty())
    }
    @Test fun `expiry resolution occurs before SQL status filtering and touches only participant rows`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val outsider = http.login(UserRole.ELDER); val p = http.pending(e, c)
        sql("UPDATE pairing_request SET expires_at = 1 WHERE id = '${p.id}'")
        assertTrue(requests(outsider).isEmpty()); assertEquals("PENDING", scalar("SELECT status FROM pairing_request"))
        assertTrue(requests(e, "?status=PENDING").isEmpty())
        assertEquals(PairingClosedReason.CODE_EXPIRED, requests(c, "?status=EXPIRED").single().closedReason)
    }
    @Test fun `deletion closes pending and persistent original receipt cannot remove replacement`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val code = http.issue(e); http.request(http.submit(c, code.code), 201); val op = key()
        assertEquals(204, http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status)
        assertEquals(PairingClosedReason.ELDER_CANCELLED, requests(c).single().closedReason)
        val next = http.issue(e)
        repeat(2) { assertEquals(204, http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status) }
        assertEquals(next.id, scalar("SELECT code_id FROM pairing_active_code"))
        assertEquals("1", scalar("SELECT count(*) FROM pairing_code_deletion"))
        http.request(http.submit(c, next.code), 201)
    }
    @Test fun `deferred receipt commit failure yields 503 and rolls back code removal and pending closure`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val code = http.issue(e); val p = http.request(http.submit(c, code.code), 201); val op = key()
        sql("""CREATE FUNCTION fail_deletion() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'PRIVATE deletion commit failure'; END ${'$'}${'$'}""")
        sql("CREATE CONSTRAINT TRIGGER fail_deletion AFTER INSERT ON pairing_code_deletion DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_deletion()")
        val response = http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op)
        http.failure(response, 503, "SERVICE_UNAVAILABLE", true); assertFalse(response.contentAsString.contains("PRIVATE"))
        assertEquals(code.id, scalar("SELECT code_id FROM pairing_active_code")); assertEquals(p, requests(e).single())
        assertEquals("0", scalar("SELECT count(*) FROM pairing_code_deletion"))
        sql("DROP TRIGGER fail_deletion ON pairing_code_deletion")
        assertEquals(204, http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status)
    }
    @Test fun `SQL receipt binding is immutable and wrong caller cannot delete code`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val outsider = http.login(UserRole.ELDER); val code = http.issue(e); val op = key()
        http.failure(http.call(outsider, "DELETE", "/api/v1/pairing-codes/${code.id}", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        assertEquals(204, http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status)
        val k = CodeDeletionKey(e.id, UUID.fromString(op)); val old = checkNotNull(selected.directoryTransaction { it.codeDeletion(k) })
        for (changed in listOf(CodeDeletionReceipt("changed", old.fingerprint, old.codeId), CodeDeletionReceipt(old.installationId, "changed", old.codeId), CodeDeletionReceipt(old.installationId, old.fingerprint, "changed")))
            assertThrows(IllegalStateException::class.java) { selected.directoryTransaction { it.putCodeDeletion(k, changed) } }
        val next = http.issue(e)
        http.failure(http.call(e, "DELETE", "/api/v1/pairing-codes/${next.id}", null, op), 409, "IDEMPOTENCY_KEY_REUSED")
        http.request(http.submit(c, next.code), 201)
    }
    @Test fun `eight database connections serialize duplicate deletions and consume race`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val code = http.issue(e); val op = key()
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { pool.submit<Int> { start.await(); http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status } }
            start.countDown(); futures.forEach { assertEquals(204, it.get(15, TimeUnit.SECONDS)) }
            assertEquals("1", scalar("SELECT count(*) FROM pairing_code_deletion"))
            val next = http.issue(e); val gate = CountDownLatch(1)
            val consume = pool.submit<Int> { gate.await(); http.submit(c, next.code).status }
            val deleted = pool.submit<Int> { gate.await(); http.call(e, "DELETE", "/api/v1/pairing-codes/${next.id}", null).status }
            gate.countDown(); assertEquals(204, deleted.get(10, TimeUnit.SECONDS)); assertTrue(consume.get(10, TimeUnit.SECONDS) in listOf(201, 422))
            assertTrue(requests(e, "?status=PENDING").isEmpty())
        } finally { start.countDown(); pool.shutdownNow() }
    }
    @Test fun `code deletion racing acceptance either cancels pending or preserves accepted relation`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val code = http.issue(e)
        val p = http.request(http.submit(c, code.code), 201); val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val accept = pool.submit<Int> { start.await(); http.call(e, "POST", "/api/v1/pairing-requests/${p.id}/accept").status }
            val deletion = pool.submit<Int> { start.await(); http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null).status }
            start.countDown(); assertEquals(204, deletion.get(10, TimeUnit.SECONDS))
            val status = accept.get(10, TimeUnit.SECONDS); assertTrue(status in listOf(200, 409))
            assertEquals(if (status == 200) PairingRequestStatus.ACCEPTED else PairingRequestStatus.CANCELLED, requests(e).single().status)
            assertEquals(if (status == 200) "1" else "0", scalar("SELECT count(*) FROM pairing_relationship WHERE status = 'ACTIVE'"))
        } finally { start.countDown(); pool.shutdownNow() }
    }
    @Test fun `v4 is additive and database restart retains terminal lists and deletion receipt`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val code = http.issue(e); val p = http.request(http.submit(c, code.code), 201); val op = key()
        sql("DROP TABLE pairing_code_deletion"); install("pairing-directory-store-v4.sql")
        assertEquals(p, requests(e).single())
        assertEquals(204, http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status)
        install("pairing-directory-store-v4.sql")
        fun control(action: String) {
            val child = ProcessBuilder(PostgresPairingCodeStoreTest.required("S2_PG_PYTHON"), PostgresPairingCodeStoreTest.required("S2_PG_MANAGER_SCRIPT"), action).redirectErrorStream(true).start()
            try { assertTrue(child.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, child.exitValue()) } finally { if (child.isAlive) child.destroyForcibly() }
        }
        try { control("stop") } finally { control("start") }
        assertEquals(PairingClosedReason.ELDER_CANCELLED, requests(c).single().closedReason)
        assertEquals(204, http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status)
        assertEquals("0", scalar("SELECT count(*) FROM pairing_active_code"))
    }
    @Test fun `busy collection maps to retryable 409 and disconnected adapter fails closed with safe 503`() {
        val e = http.login(UserRole.ELDER); val pool = Executors.newSingleThreadExecutor(); val held = CountDownLatch(1); val release = CountDownLatch(1)
        val future = pool.submit { selected.directoryTransaction { held.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val response = http.call(e, "GET", "/api/v1/pairing-requests", null)
            http.failure(response, 409, "OPERATION_IN_PROGRESS", true); assertEquals("1", response.getHeader("Retry-After"))
        } finally { release.countDown(); future.get(5, TimeUnit.SECONDS); pool.shutdownNow() }
        sql("ALTER TABLE pairing_request RENAME TO pairing_request_offline")
        val failed = http.call(e, "GET", "/api/v1/pairing-requests", null); http.failure(failed, 503, "SERVICE_UNAVAILABLE", true)
        assertFalse(failed.contentAsString.contains(schema)); assertFalse(failed.contentAsString.contains("offline"))
    }
    @TestConfiguration(proxyBeanMethods = false) class Selection { @Bean @Primary fun directoryDatabase() = Selected() }
    class Selected : PairingDirectoryStore {
        lateinit var backing: PostgresPairingCodeStore
        override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = backing.transaction(work)
        override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T = backing.requestTransaction(work)
        override fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T = backing.decisionTransaction(work)
        override fun <T> directoryTransaction(work: (PairingDirectoryTransaction) -> T): T = backing.directoryTransaction(work)
    }
}
