package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
class PostgresPairingRequestStoreTest {
    private val schema = "s2_request_it_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var source: PGSimpleDataSource
    private lateinit var cipherKey: ByteArray
    private val clock = TestClock()
    private val generated = AtomicInteger()
    private val elder = AnonymousUser("fixture-elder", "fixture-e-install", "虚构长者", UserRole.ELDER)
    private val caregiver = AnonymousUser("fixture-caregiver", "fixture-c-install", "虚构照护者", UserRole.CAREGIVER)
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))

    @BeforeEach fun prepare() {
        cipherKey = Files.readAllBytes(Path.of(PostgresPairingCodeStoreTest.required("S2_PG_RECEIPT_KEY_FILE")))
        source = PostgresPairingCodeStoreTest.dataSource(schema)
        PostgresPairingCodeStoreTest.dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        install("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql")
    }
    @AfterEach fun cleanup() {
        PostgresPairingCodeStoreTest.dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }
    private fun install(vararg names: String) {
        source.connection.use { c ->
            c.autoCommit = false
            names.forEach { name -> checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText()
                .split(';').filter { it.isNotBlank() }.forEach { sql -> c.createStatement().use { it.execute(sql) } } }
            c.commit()
        }
    }
    private fun store(wait: Long = 5_000) = PostgresPairingCodeStore(
        PostgresPairingCodeStoreTest.dataSource(schema), PairingReceiptCipher(cipherKey), wait)
    private fun issue(who: AnonymousUser = elder, operation: UUID = UUID.randomUUID()): PairingCodeView =
        assertInstanceOf(CodeIssuanceResult.Issued::class.java, PairingCodeIssuanceService(store(), clock) {
            generated.incrementAndGet().toString().padStart(8, '0')
        }.issue(who, operation)).view
    private fun submit(code: String, op: UUID = UUID.randomUUID(), who: AnonymousUser = caregiver, from: InetAddress = address) =
        PairingRequestService(store(), clock).request(who, op, RequestPairingRequest(code), from)
    private fun success(value: PairingRequestResult) = assertInstanceOf(PairingRequestResult.Success::class.java, value).view
    private fun reject(value: PairingRequestResult, reason: PairingRequestRejection) =
        assertInstanceOf(PairingRequestResult.Rejected::class.java, value).also { assertEquals(reason, it.reason) }
    private fun detail(id: String, who: AnonymousUser = elder) = PairingRequestService(store(), clock).detail(who, id)
    private fun execute(sql: String) { source.connection.use { it.createStatement().use { s -> s.execute(sql) } } }
    private fun scalar(sql: String) = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery(sql).use { r -> check(r.next()); r.getString(1) }
    } }
    private fun counts() = listOf("pairing_request", "pairing_request_operation", "pairing_request_attempt")
        .map { scalar("SELECT count(*) FROM $it").toInt() }

    @Test fun `real request consumption and original receipt survive independent adapter reconstruction`() {
        val code = issue()
        val op = UUID.randomUUID()
        val view = success(submit(code.code, op))
        assertEquals(PairingRequestStatus.PENDING, view.status)
        assertEquals(code.expiresAtEpochMillis, view.expiresAtEpochMillis)
        assertEquals(caregiver.displayName, view.caregiver.displayName)
        assertNull(view.relationshipId)
        assertEquals(view, success(submit(code.code, op, caregiver.copy(displayName = "Changed"))))
        assertEquals(view, success(detail(view.id)))
        assertEquals(listOf(1, 1, 2), counts())
        assertNull(store().requestTransaction { it.unconsumedCode(PairingCodeIssuanceService.hash(code.code), clock.millis()) })
    }

    @Test fun `SQL code lookup respects deadline minus one exactly and plus one`() {
        for (offset in listOf(-1L, 0L, 1L)) {
            val actor = elder.copy(id = "elder-$offset", installationId = "installation-$offset")
            val code = issue(actor)
            clock.set(code.expiresAtEpochMillis + offset)
            if (offset < 0) assertEquals(code.expiresAtEpochMillis, success(submit(code.code)).expiresAtEpochMillis)
            else reject(submit(code.code), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        }
    }

    @Test fun `database uniqueness rejects a second request for the same consumed code`() {
        val code = issue()
        val view = success(submit(code.code))
        val failure = assertThrows(SQLException::class.java) {
            execute("INSERT INTO pairing_request (id, code_id, elder_id, caregiver_id, caregiver_name, status, expires_at, closed_reason) SELECT 'different-request', code_id, elder_id, caregiver_id, caregiver_name, status, expires_at, closed_reason FROM pairing_request")
        }
        assertEquals("23505", failure.sqlState)
        assertEquals(view, success(detail(view.id)))
    }

    @Test fun `foreign key and terminal-state constraints reject broken request evidence`() {
        val view = success(submit(issue().code))
        val badState = assertThrows(SQLException::class.java) { execute("UPDATE pairing_request SET status = 'EXPIRED', closed_reason = NULL") }
        assertEquals("23514", badState.sqlState)
        val badReceipt = assertThrows(SQLException::class.java) { execute("UPDATE pairing_request_operation SET receipt_request_id = 'missing'") }
        assertEquals("23503", badReceipt.sqlState)
        val deletion = assertThrows(SQLException::class.java) { execute("DELETE FROM pairing_request") }
        assertEquals("23503", deletion.sqlState)
        assertEquals(view, success(detail(view.id)))
    }

    @Test fun `expired detail and replaced detail retain immutable original pending receipts`() {
        val code = issue()
        val op = UUID.randomUUID()
        val original = success(submit(code.code, op))
        clock.set(code.expiresAtEpochMillis)
        val expired = success(detail(original.id))
        assertEquals(PairingRequestStatus.EXPIRED, expired.status)
        assertEquals(PairingClosedReason.CODE_EXPIRED, expired.closedReason)
        issue()
        assertEquals(expired, success(detail(original.id)))
        assertEquals(original, success(submit(code.code, op)))
        val newer = issue()
        val secondOp = UUID.randomUUID()
        val pending = success(submit(newer.code, secondOp))
        clock.advance(60_000)
        issue()
        val closed = success(detail(pending.id))
        assertEquals(PairingClosedReason.CODE_REPLACED, closed.closedReason)
        assertEquals(PairingRequestStatus.CANCELLED, closed.status)
        assertEquals(pending, success(submit(newer.code, secondOp)))
        assertEquals(2, scalar("SELECT count(*) FROM pairing_request").toInt())
    }

    @Test fun `role and participant authorization hold across persisted reads`() {
        val code = issue()
        reject(submit(code.code, who = elder), PairingRequestRejection.ROLE_FORBIDDEN)
        assertEquals(listOf(0, 0, 0), counts())
        val view = success(submit(code.code))
        assertEquals(view, success(detail(view.id, caregiver)))
        val hidden = detail("missing", caregiver)
        assertEquals(hidden, detail(view.id, caregiver.copy(id = "unrelated")))
        assertEquals(hidden, detail(view.id, elder.copy(role = UserRole.CAREGIVER)))
    }

    @Test fun `rejected binding persists and same input can be retried after the code becomes available`() {
        val op = UUID.randomUUID()
        reject(submit("00000001", op), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        issue()
        reject(submit("00000002", op), PairingRequestRejection.IDEMPOTENCY_KEY_REUSED)
        reject(submit("00000001", op, caregiver.copy(installationId = "changed")), PairingRequestRejection.IDEMPOTENCY_KEY_REUSED)
        success(submit("00000001", op))
        assertEquals(listOf(1, 1, 4), counts())
    }

    @Test fun `adapter rejects changed binding and downgrading an accepted receipt`() {
        val code = issue()
        val op = UUID.randomUUID()
        val view = success(submit(code.code, op))
        val key = PairingRequestKey(caregiver.id, op)
        val original = checkNotNull(store().requestTransaction { it.requestOperation(key) })
        for (changed in listOf(
            PairingRequestOperation("changed", original.fingerprint, view),
            PairingRequestOperation(original.installationId, "changed", view),
            PairingRequestOperation(original.installationId, original.fingerprint,
                rejection = PairingRequestResult.Rejected(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)),
        )) assertThrows(IllegalStateException::class.java) { store().requestTransaction { it.putRequestOperation(key, changed) } }
        assertEquals(view, success(submit(code.code, op)))
    }

    private fun concurrent(count: Int = 8, work: (Int) -> PairingRequestResult): List<PairingRequestResult> {
        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        try {
            val results = (0 until count).map { i -> pool.submit<PairingRequestResult> { start.await(); work(i) } }
            start.countDown()
            return results.map { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `eight independent database connections replay one request with one quota debit`() {
        val code = issue(); val op = UUID.randomUUID()
        val results = concurrent { submit(code.code, op) }.map(::success)
        assertEquals(1, results.map { it.id }.distinct().size)
        assertEquals(listOf(1, 1, 2), counts())
    }

    @Test fun `eight distinct caregivers racing one code produce one request`() {
        val code = issue()
        val results = concurrent { i -> submit(code.code, who = caregiver.copy(id = "c$i", installationId = "i$i")) }
        assertEquals(1, results.count { it is PairingRequestResult.Success })
        results.filterIsInstance<PairingRequestResult.Rejected>().forEach { assertEquals(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE, it.reason) }
        assertEquals(listOf(1, 8, 16), counts())
    }

    @Test fun `five attempts per installation is enforced across concurrent stores and survives reconstruction`() {
        val results = concurrent { submit("99999999") }
        assertEquals(5, results.count { it == PairingRequestResult.Rejected(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE) })
        assertEquals(3, results.count { it == PairingRequestResult.Rejected(PairingRequestRejection.RATE_LIMITED, 60) })
        assertEquals(10, scalar("SELECT count(*) FROM pairing_request_attempt").toInt())
        clock.advance(59_999)
        assertEquals(1L, reject(submit("99999999"), PairingRequestRejection.RATE_LIMITED).retryAfterSeconds)
        clock.advance(1)
        reject(submit("99999999"), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        assertEquals(2, scalar("SELECT count(*) FROM pairing_request_attempt").toInt())
    }

    @Test fun `twenty attempts per IP shares a database quota across installations`() {
        val results = concurrent(24) { i -> submit("99999999", who = caregiver.copy(id = "c$i", installationId = "i$i")) }
        assertEquals(20, results.count { it is PairingRequestResult.Rejected && it.reason == PairingRequestRejection.PAIRING_CODE_UNAVAILABLE })
        assertEquals(4, results.count { it is PairingRequestResult.Rejected && it.reason == PairingRequestRejection.RATE_LIMITED })
        reject(submit("99999999", from = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2))), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
    }

    private fun receiptFailure(deferred: Boolean) {
        val code = issue(); val op = UUID.randomUUID()
        execute("""CREATE FUNCTION fail_request_receipt() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'fixture request receipt failure'; END ${'$'}${'$'}""")
        val trigger = if (deferred) "CREATE CONSTRAINT TRIGGER request_failure AFTER INSERT ON pairing_request_operation DEFERRABLE INITIALLY DEFERRED"
            else "CREATE TRIGGER request_failure BEFORE INSERT ON pairing_request_operation"
        execute("$trigger FOR EACH ROW EXECUTE FUNCTION fail_request_receipt()")
        assertThrows(SQLException::class.java) { submit(code.code, op) }
        assertEquals(listOf(0, 0, 0), counts())
        assertNotNull(store().requestTransaction { it.unconsumedCode(PairingCodeIssuanceService.hash(code.code), clock.millis()) })
        execute("DROP TRIGGER request_failure ON pairing_request_operation")
        success(submit(code.code, op))
        assertEquals(listOf(1, 1, 2), counts())
    }
    @Test fun `SQL statement failure rolls back code consumption request and quota`() = receiptFailure(false)
    @Test fun `deferred COMMIT failure rolls back request and consumption before same-operation retry`() = receiptFailure(true)

    @Test fun `failed renewal COMMIT rolls back pending cancellation and code replacement`() {
        val code = issue(); val pending = success(submit(code.code))
        execute("""CREATE FUNCTION fail_renewal() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'fixture renewal failure'; END ${'$'}${'$'}""")
        execute("CREATE CONSTRAINT TRIGGER fail_renewal AFTER INSERT ON pairing_issue_operation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_renewal()")
        assertThrows(SQLException::class.java) { issue() }
        assertEquals(pending, success(detail(pending.id)))
        assertEquals(code.id, store().transaction { it.activeCode(elder.id)?.id })
        execute("DROP TRIGGER fail_renewal ON pairing_issue_operation")
        issue()
        assertEquals(PairingClosedReason.CODE_REPLACED, success(detail(pending.id)).closedReason)
    }

    @Test fun `database lock wait crossing expiry rejects instead of consuming from stale clock`() {
        val code = issue(); clock.set(code.expiresAtEpochMillis - 1)
        val pool = Executors.newFixedThreadPool(2)
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val held = pool.submit { store().transaction { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val future = pool.submit<PairingRequestResult> { submit(code.code) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name = '$schema' AND wait_event_type = 'Lock'") == "0") {
                check(System.nanoTime() < deadline); Thread.sleep(10)
            }
            clock.set(code.expiresAtEpochMillis)
            release.countDown(); held.get(5, TimeUnit.SECONDS)
            reject(future.get(5, TimeUnit.SECONDS), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
            assertEquals(0, scalar("SELECT count(*) FROM pairing_request").toInt())
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test fun `NOWAIT request contention preserves original operation for retry`() {
        val code = issue(); val op = UUID.randomUUID()
        val pool = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val held = pool.submit { store().transaction { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val blocked = PairingRequestService(store(0), clock).request(caregiver, op, RequestPairingRequest(code.code), address)
            assertEquals(1L, reject(blocked, PairingRequestRejection.OPERATION_IN_PROGRESS).retryAfterSeconds)
        } finally { release.countDown(); held.get(5, TimeUnit.SECONDS); pool.shutdownNow() }
        assertEquals(listOf(0, 0, 0), counts())
        success(submit(code.code, op))
    }

    @Test fun `replacement and consumption on independent connections serialize without a stranded pending request`() {
        val code = issue(); val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val request = pool.submit<PairingRequestResult> { start.await(); submit(code.code) }
            val replacement = pool.submit<PairingCodeView> { start.await(); issue() }
            start.countDown()
            val result = request.get(10, TimeUnit.SECONDS)
            val next = replacement.get(10, TimeUnit.SECONDS)
            if (result is PairingRequestResult.Success) assertEquals(PairingClosedReason.CODE_REPLACED, success(detail(result.view.id)).closedReason)
            else reject(result, PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
            assertEquals(0, scalar("SELECT count(*) FROM pairing_request WHERE status = 'PENDING'").toInt())
            success(submit(next.code))
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun `additive v2 installation preserves prior v1 code and encrypted receipt rows`() {
        val op = UUID.randomUUID(); val code = issue(operation = op)
        execute("DROP TABLE pairing_decision_operation, pairing_request_attempt, pairing_request_operation, pairing_request, pairing_relationship")
        assertEquals(1, scalar("SELECT count(*) FROM pairing_issue_operation").toInt())
        install("pairing-request-store-v2.sql", "pairing-decision-store-v3.sql")
        install("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql")
        assertEquals(code, issue(operation = op))
        success(submit(code.code))
    }

    @Test fun `callback failure rolls back real request and quota mutations`() {
        assertThrows(IllegalStateException::class.java) { store().requestTransaction { tx ->
            tx.putInstallationAttempts(caregiver.installationId, listOf(clock.millis()))
            tx.putAddressAttempts("fixture-address-hash", listOf(clock.millis()))
            error("fixture callback failure")
        } }
        assertEquals(listOf(0, 0, 0), counts())
    }

    private fun child(mode: String, op: UUID): String {
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            System.getProperty("s2.postgres.test.classpath"), "com.group33.server.pairing.PairingRequestPostgresProcessProbe",
            schema, mode, op.toString(), clock.millis().toString())
        val child = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue(), "Child failed; credential-bearing output not copied")
            return child.inputStream.bufferedReader().readText().trim()
        } finally { if (child.isAlive) child.destroyForcibly() }
    }
    @Test fun `request receipt and consumption survive exit of submitting JVM and replay in another JVM`() {
        val op = UUID.randomUUID()
        val first = child("consume", op)
        clock.advance(700_000)
        assertEquals(first, child("replay", op))
        assertEquals(listOf(1, 1, 2), counts())
    }

    @Test fun `actual PostgreSQL restart preserves request receipt consumption and both attempt buckets`() {
        val code = issue(); val op = UUID.randomUUID(); val original = success(submit(code.code, op))
        repeat(4) { reject(submit("99999999"), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE) }
        val before = counts()
        fun control(action: String) {
            val child = ProcessBuilder(PostgresPairingCodeStoreTest.required("S2_PG_PYTHON"),
                PostgresPairingCodeStoreTest.required("S2_PG_MANAGER_SCRIPT"), action).redirectErrorStream(true).start()
            assertTrue(child.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, child.exitValue())
        }
        try { control("stop") } finally { control("start") }
        assertEquals(before, counts())
        assertEquals(original, success(submit(code.code, op)))
        assertEquals(original, success(detail(original.id)))
        reject(submit(code.code), PairingRequestRejection.RATE_LIMITED)
        clock.advance(60_000)
        reject(submit(code.code), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
    }

    private class TestClock : Clock() {
        private val time = AtomicLong(1_792_752_000_000)
        fun advance(delta: Long) { time.addAndGet(delta) }
        fun set(value: Long) { time.set(value) }
        override fun millis() = time.get()
        override fun instant() = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock { require(zone == ZoneOffset.UTC); return this }
    }
}
