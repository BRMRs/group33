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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
class PostgresPairingDecisionStoreTest {
    private val schema = "s2_decision_it_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var source: PGSimpleDataSource
    private lateinit var cipherKey: ByteArray
    private val clock = TestClock()
    private val generated = AtomicInteger()
    private val elder = AnonymousUser("fixture-elder", "fixture-e-install", "虚构长者", UserRole.ELDER)
    private val caregiver = AnonymousUser("fixture-caregiver", "fixture-c-install", "虚构照护者", UserRole.CAREGIVER)
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
    private fun key() = UUID.randomUUID()

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
            for (name in names) checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText()
                .split(';').filter { it.isNotBlank() }.forEach { sql -> c.createStatement().use { it.execute(sql) } }
            c.commit()
        }
    }
    private fun store(wait: Long = 5_000) = PostgresPairingCodeStore(PostgresPairingCodeStoreTest.dataSource(schema), PairingReceiptCipher(cipherKey), wait)
    private fun service() = PairingDecisionService(store(), clock)
    private fun issue(e: AnonymousUser = elder, op: UUID = key()) = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
        PairingCodeIssuanceService(store(), clock) { generated.incrementAndGet().toString().padStart(8, '0') }.issue(e, op)).view
    private fun submit(code: String, op: UUID = key(), c: AnonymousUser = caregiver) = PairingRequestService(store(), clock)
        .request(c, op, RequestPairingRequest(code), address)
    private fun pending(e: AnonymousUser = elder, c: AnonymousUser = caregiver) = request(submit(issue(e).code, c = c))
    private fun request(result: PairingRequestResult) = assertInstanceOf(PairingRequestResult.Success::class.java, result).view
    private fun detail(id: String) = request(PairingRequestService(store(), clock).detail(elder, id))
    private fun relation(result: PairingDecisionResult) = assertInstanceOf(PairingDecisionResult.Relationship::class.java, result).view
    private fun reject(result: PairingDecisionResult, reason: PairingDecisionRejection) =
        assertInstanceOf(PairingDecisionResult.Rejected::class.java, result).also { assertEquals(reason, it.reason) }
    private fun active(c: AnonymousUser = caregiver, e: AnonymousUser = elder) = StoredCareRelationshipAccess(store()).isActive(c.id, e.id)
    private fun execute(sql: String) { source.connection.use { it.createStatement().use { s -> s.execute(sql) } } }
    private fun scalar(sql: String) = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery(sql).use { r -> check(r.next()); r.getString(1) }
    } }

    @Test fun `acceptance and immutable receipt survive new connections expiry and revocation`() {
        val code = issue(); val requestOp = key(); val p = request(submit(code.code, requestOp)); val op = key()
        assertFalse(active())
        val original = relation(service().accept(elder, p.id, op))
        assertTrue(active()); assertEquals(original.id, detail(p.id).relationshipId)
        assertEquals(elder.displayName, original.elder.displayName)
        clock.set(p.expiresAtEpochMillis + 1)
        val revoked = relation(service().revoke(caregiver, original.id, key()))
        assertFalse(active()); assertEquals(CareRelationshipStatus.REVOKED, revoked.status)
        assertEquals(original, relation(service().accept(elder.copy(displayName = "changed"), p.id, op)))
        assertEquals(revoked, relation(service().relationship(elder, original.id)))
        assertEquals(p, request(submit(code.code, requestOp))) // relationshipId must not leak into original PENDING receipt
        assertEquals("1", scalar("SELECT count(*) FROM pairing_relationship"))
    }

    @ParameterizedTest @ValueSource(strings = ["REJECT", "CANCEL_ELDER", "CANCEL_CAREGIVER"])
    fun `persisted negative decisions replay terminal result without reopening code`(mode: String) {
        val code = issue(); val p = request(submit(code.code)); val op = key()
        fun act() = if (mode == "REJECT") service().reject(elder, p.id, op)
            else service().cancel(if (mode == "CANCEL_ELDER") elder else caregiver, p.id, op)
        val original = act()
        clock.set(p.expiresAtEpochMillis + 1)
        if (mode == "REJECT") assertEquals((original as PairingDecisionResult.Request).view,
            assertInstanceOf(PairingDecisionResult.Request::class.java, act()).view)
        else assertEquals(PairingDecisionResult.Cancelled, act())
        assertEquals(when (mode) {
            "REJECT" -> PairingClosedReason.ELDER_REJECTED
            "CANCEL_ELDER" -> PairingClosedReason.ELDER_CANCELLED
            else -> PairingClosedReason.CAREGIVER_CANCELLED
        }, detail(p.id).closedReason)
        reject(service().accept(elder, p.id, key()), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
        assertEquals(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE,
            assertInstanceOf(PairingRequestResult.Rejected::class.java, submit(code.code)).reason)
        assertFalse(active()); assertEquals("0", scalar("SELECT count(*) FROM pairing_relationship"))
    }

    @Test fun `persistent relationship access and decision receipts remain participant scoped`() {
        val p = pending(); val op = key(); val r = relation(service().accept(elder, p.id, op))
        reject(service().accept(caregiver, p.id, op), PairingDecisionRejection.ROLE_NOT_ALLOWED)
        for (actor in listOf(elder.copy(id = "outsider"), caregiver.copy(id = "outsider"), elder.copy(role = UserRole.CAREGIVER))) {
            val missing = service().accept(actor, "missing", op)
            assertEquals(missing, service().accept(actor, p.id, op))
            reject(service().relationship(actor, r.id), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
            reject(service().revoke(actor, r.id, key()), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
        }
        assertTrue(active())
    }

    @Test fun `second caregiver rejection persists binding and same intent succeeds only after revocation`() {
        val r = relation(service().accept(elder, pending().id, key()))
        val other = caregiver.copy(id = "other-caregiver", installationId = "other-install")
        val p = pending(c = other); val op = key()
        reject(service().accept(elder, p.id, op), PairingDecisionRejection.ELDER_ALREADY_PAIRED)
        reject(service().accept(elder.copy(installationId = "changed"), p.id, op), PairingDecisionRejection.IDEMPOTENCY_KEY_REUSED)
        service().revoke(caregiver, r.id, key())
        relation(service().accept(elder, p.id, op))
        assertFalse(active()); assertTrue(active(other))
        assertEquals("1", scalar("SELECT count(*) FROM pairing_relationship WHERE status = 'ACTIVE'"))
        assertEquals("1", scalar("SELECT count(*) FROM pairing_relationship WHERE status = 'REVOKED'"))
    }

    @Test fun `same caregiver retains other elder access after one relationship is revoked`() {
        val r = relation(service().accept(elder, pending().id, key()))
        val other = elder.copy(id = "other-elder", installationId = "other-install")
        relation(service().accept(other, pending(e = other).id, key()))
        service().revoke(elder, r.id, key())
        assertFalse(active()); assertTrue(active(e = other))
    }

    @Test fun `new and replayed revoke retain the first revoked time and metadata`() {
        val r = relation(service().accept(elder, pending().id, key())); val op = key()
        val original = relation(service().revoke(elder, r.id, op))
        clock.set(clock.millis() + 999)
        assertEquals(original, relation(service().revoke(elder, r.id, op)))
        assertEquals(original, relation(service().revoke(caregiver, r.id, key())))
        assertEquals(original, relation(service().relationship(caregiver, r.id)))
        assertFalse(active())
    }

    @Test fun `decision binding and successful payload cannot be replaced or downgraded`() {
        val p = pending(); val op = key(); val accepted = relation(service().accept(elder, p.id, op))
        val k = PairingDecisionKey(elder.id, PairingDecision.ACCEPT, op)
        val original = checkNotNull(store().decisionTransaction { it.decisionOperation(k) })
        for (changed in listOf(
            PairingDecisionOperation("changed", original.fingerprint, original.result),
            PairingDecisionOperation(original.installationId, "changed", original.result),
            PairingDecisionOperation(original.installationId, original.fingerprint, PairingDecisionResult.Rejected(PairingDecisionRejection.PAIRING_REQUEST_CLOSED)),
            PairingDecisionOperation(original.installationId, original.fingerprint, PairingDecisionResult.Relationship(accepted.copy(createdAtEpochMillis = 7))),
        )) assertThrows(IllegalStateException::class.java) { store().decisionTransaction { it.putDecisionOperation(k, changed) } }
        store().decisionTransaction { it.putDecisionOperation(k, original) }
        assertEquals(accepted, relation(service().accept(elder, p.id, op)))
        val p2 = pending()
        reject(service().accept(elder, p2.id, op), PairingDecisionRejection.IDEMPOTENCY_KEY_REUSED)
    }

    @Test fun `SQL constraints enforce active uniqueness participant link and nonnull terminal meaning`() {
        val p = pending(); val r = relation(service().accept(elder, p.id, key()))
        fun sqlState(expected: String, sql: String) { assertEquals(expected, assertThrows(SQLException::class.java) { execute(sql) }.sqlState) }
        sqlState("23505", "INSERT INTO pairing_relationship SELECT 'duplicate', elder_id, caregiver_id, elder_name, caregiver_name, status, created_at, revoked_at FROM pairing_relationship")
        sqlState("23503", "UPDATE pairing_request SET caregiver_id = 'outsider' WHERE id = '${p.id}'")
        sqlState("23514", "UPDATE pairing_request SET status = 'REJECTED', relationship_id = NULL, closed_reason = NULL")
        sqlState("23514", "UPDATE pairing_relationship SET status = 'REVOKED', revoked_at = NULL")
        sqlState("23514", "UPDATE pairing_decision_operation SET result_payload = NULL")
        sqlState("23503", "DELETE FROM pairing_relationship WHERE id = '${r.id}'")
        assertTrue(active())
    }

    private fun <T> concurrent(count: Int = 8, work: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count); val ready = CountDownLatch(count); val start = CountDownLatch(1)
        try {
            val results = (0 until count).map { i -> pool.submit<T> { ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); work(i) } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            return results.map { it.get(20, TimeUnit.SECONDS) }
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun `eight independent connections replay one accept and create one receipt`() {
        val p = pending(); val op = key()
        val results = concurrent { service().accept(elder, p.id, op) }.map(::relation)
        assertEquals(1, results.map { it.id }.distinct().size)
        assertEquals("1", scalar("SELECT count(*) FROM pairing_relationship"))
        assertEquals("1", scalar("SELECT count(*) FROM pairing_decision_operation"))
    }
    @Test fun `eight distinct accepts produce one success and seven closed rejections`() {
        val p = pending(); val results = concurrent { service().accept(elder, p.id, key()) }
        assertEquals(1, results.count { it is PairingDecisionResult.Relationship })
        results.filterIsInstance<PairingDecisionResult.Rejected>().forEach { assertEquals(PairingDecisionRejection.PAIRING_REQUEST_CLOSED, it.reason) }
        assertEquals("1", scalar("SELECT count(*) FROM pairing_relationship"))
    }
    @Test fun `independent accept and cancel transactions yield one terminal winner`() {
        val p = pending()
        val results = concurrent(2) { if (it == 0) service().accept(elder, p.id, key()) else service().cancel(caregiver, p.id, key()) }
        assertEquals(1, results.count { it is PairingDecisionResult.Rejected })
        assertEquals(detail(p.id).status == PairingRequestStatus.ACCEPTED, active())
    }
    @Test fun `concurrent revoke and original accept replay cannot reactivate relation`() {
        val p = pending(); val op = key(); val r = relation(service().accept(elder, p.id, op))
        concurrent { if (it % 2 == 0) service().accept(elder, p.id, op) else service().revoke(caregiver, r.id, key()) }
        assertFalse(active()); assertEquals(CareRelationshipStatus.REVOKED, relation(service().relationship(elder, r.id)).status)
    }
    @Test fun `concurrent code renewal and acceptance leave no pending request on replaced opportunity`() {
        val p = pending()
        concurrent(2) { if (it == 0) service().accept(elder, p.id, key()) else issue() }
        val closed = detail(p.id)
        assertNotEquals(PairingRequestStatus.PENDING, closed.status)
        assertEquals(closed.status == PairingRequestStatus.ACCEPTED, active())
    }

    @ParameterizedTest @ValueSource(strings = ["ACCEPT", "REJECT", "CANCEL", "REVOKE"])
    fun `actual deferred commit failure rolls back every decision and original intent can retry`(action: String) {
        val p = pending(); val existing = if (action == "REVOKE") relation(service().accept(elder, p.id, key())) else null
        val op = key(); val before = detail(p.id)
        val receipts = scalar("SELECT count(*) FROM pairing_decision_operation")
        execute("""CREATE FUNCTION fail_decision() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'fixture decision commit failure'; END ${'$'}${'$'}""")
        execute("CREATE CONSTRAINT TRIGGER fail_decision AFTER INSERT ON pairing_decision_operation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_decision()")
        fun act() = when (action) {
            "ACCEPT" -> service().accept(elder, p.id, op)
            "REJECT" -> service().reject(elder, p.id, op)
            "CANCEL" -> service().cancel(caregiver, p.id, op)
            else -> service().revoke(elder, checkNotNull(existing).id, op)
        }
        assertThrows(SQLException::class.java) { act() }
        assertEquals(before, detail(p.id)); assertEquals(existing != null, active())
        assertEquals(if (existing == null) "0" else "1", scalar("SELECT count(*) FROM pairing_relationship"))
        assertEquals(receipts, scalar("SELECT count(*) FROM pairing_decision_operation"))
        execute("DROP TRIGGER fail_decision ON pairing_decision_operation")
        assertFalse(act() is PairingDecisionResult.Rejected)
        assertEquals(action == "ACCEPT", active())
    }
    @Test fun `SQL statement failure after relationship insert rolls back the partial relationship`() {
        val p = pending(); val op = key()
        execute("""CREATE FUNCTION fail_close() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'fixture request update failure'; END ${'$'}${'$'}""")
        execute("CREATE TRIGGER fail_close BEFORE UPDATE ON pairing_request FOR EACH ROW EXECUTE FUNCTION fail_close()")
        assertThrows(SQLException::class.java) { service().accept(elder, p.id, op) }
        assertEquals("0", scalar("SELECT count(*) FROM pairing_relationship"))
        assertEquals("0", scalar("SELECT count(*) FROM pairing_decision_operation"))
        execute("DROP TRIGGER fail_close ON pairing_request")
        assertEquals(p, detail(p.id)); relation(service().accept(elder, p.id, op))
    }

    @Test fun `real database lock wait crossing original expiry prevents acceptance`() {
        val p = pending(); clock.set(p.expiresAtEpochMillis - 1)
        val pool = Executors.newFixedThreadPool(2); val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val held = pool.submit { store().transaction { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val future = pool.submit<PairingDecisionResult> { service().accept(elder, p.id, key()) }
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name = '$schema' AND wait_event_type = 'Lock'") == "0") {
                check(System.nanoTime() < limit); Thread.sleep(10)
            }
            clock.set(p.expiresAtEpochMillis); release.countDown(); held.get(5, TimeUnit.SECONDS)
            reject(future.get(5, TimeUnit.SECONDS), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
            assertEquals(PairingClosedReason.CODE_EXPIRED, detail(p.id).closedReason); assertFalse(active())
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS) }
    }
    @Test fun `NOWAIT decision returns retry metadata and leaves no effect before retry`() {
        val p = pending(); val op = key(); val pool = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val held = pool.submit { store().transaction { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val result = PairingDecisionService(store(0), clock).accept(elder, p.id, op)
            assertEquals(1L, reject(result, PairingDecisionRejection.OPERATION_IN_PROGRESS).retryAfterSeconds)
        } finally { release.countDown(); held.get(5, TimeUnit.SECONDS); pool.shutdownNow() }
        assertEquals(p, detail(p.id)); assertFalse(active())
        relation(service().accept(elder, p.id, op))
    }

    @Test fun `forward v3 upgrade retains v2 pending terminal and encrypted code receipt data`() {
        execute("DROP TABLE pairing_decision_operation, pairing_request_attempt, pairing_request_operation, pairing_request, pairing_relationship")
        install("pairing-request-store-v2.sql")
        val issueOp = key(); val code = issue(op = issueOp); val requestOp = key()
        source.connection.use { c ->
            c.prepareStatement("INSERT INTO pairing_request VALUES (?, ?, ?, ?, ?, 'PENDING', ?, NULL)").use {
                it.setString(1, "old-pending"); it.setString(2, code.id); it.setString(3, elder.id)
                it.setString(4, caregiver.id); it.setString(5, caregiver.displayName); it.setLong(6, code.expiresAtEpochMillis); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO pairing_request_operation VALUES (?, ?, ?, ?, 'old-pending', NULL, NULL)").use {
                it.setString(1, caregiver.id); it.setObject(2, requestOp); it.setString(3, caregiver.installationId)
                it.setString(4, PairingCodeIssuanceService.hash("${ContractProtocol.CANONICALIZATION_VERSION}:REQUEST_PAIRING:${code.code}")); it.executeUpdate()
            }
        }
        execute("INSERT INTO pairing_request VALUES ('old-expired', 'old-code', 'old-elder', 'old-caregiver', 'Fixture', 'EXPIRED', 1, 'CODE_EXPIRED')")
        install("pairing-decision-store-v3.sql")
        assertEquals(code, issue(op = issueOp))
        val original = request(submit(code.code, requestOp)); assertNull(original.relationshipId)
        val r = relation(service().accept(elder, original.id, key()))
        install("pairing-decision-store-v3.sql")
        assertEquals(r.id, detail(original.id).relationshipId)
        assertEquals("EXPIRED|CODE_EXPIRED", scalar("SELECT status || '|' || closed_reason FROM pairing_request WHERE id = 'old-expired'"))
        assertEquals(original, request(submit(code.code, requestOp)))
    }

    private fun child(mode: String, requestId: String, op: UUID): String {
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            System.getProperty("s2.postgres.test.classpath"), "com.group33.server.pairing.PairingDecisionPostgresProcessProbe",
            schema, mode, requestId, op.toString(), clock.millis().toString())
        val child = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), "Child failed; private output not copied")
            return child.inputStream.bufferedReader().readText().trim()
        } finally { if (child.isAlive) child.destroyForcibly() }
    }
    @Test fun `acceptance survives JVM exit and another JVM replays original result after revocation`() {
        val p = pending(); val op = key(); val first = child("accept", p.id, op)
        val r = relation(service().accept(elder, p.id, op)); service().revoke(caregiver, r.id, key())
        clock.set(p.expiresAtEpochMillis + 1)
        assertEquals(first, child("replay", p.id, op)); assertFalse(active())
        assertEquals("1", scalar("SELECT count(*) FROM pairing_relationship"))
    }
    @Test fun `actual PostgreSQL restart retains revoked relationship and original decision receipt`() {
        val p = pending(); val op = key(); val original = relation(service().accept(elder, p.id, op))
        val revokeOp = key(); val revoked = relation(service().revoke(caregiver, original.id, revokeOp))
        val p2 = pending(); val rejectionOp = key()
        val rejectedView = assertInstanceOf(PairingDecisionResult.Request::class.java, service().reject(elder, p2.id, rejectionOp)).view
        val otherElder = elder.copy(id = "second-elder", installationId = "second-install")
        val activeRelation = relation(service().accept(otherElder, pending(e = otherElder).id, key()))
        val thirdElder = elder.copy(id = "third-elder", installationId = "third-install")
        val p3 = pending(e = thirdElder); val cancelOp = key()
        assertEquals(PairingDecisionResult.Cancelled, service().cancel(caregiver, p3.id, cancelOp))
        fun control(action: String) {
            val child = ProcessBuilder(PostgresPairingCodeStoreTest.required("S2_PG_PYTHON"),
                PostgresPairingCodeStoreTest.required("S2_PG_MANAGER_SCRIPT"), action).redirectErrorStream(true).start()
            try { assertTrue(child.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, child.exitValue()) }
            finally { if (child.isAlive) child.destroyForcibly() }
        }
        try { control("stop") } finally { control("start") }
        assertFalse(active()); assertEquals(revoked, relation(service().relationship(elder, original.id)))
        assertEquals(original, relation(service().accept(elder, p.id, op)))
        assertEquals(revoked, relation(service().revoke(caregiver, original.id, revokeOp)))
        assertEquals(original.id, detail(p.id).relationshipId)
        assertTrue(active(e = otherElder))
        assertEquals(activeRelation, relation(service().relationship(otherElder, activeRelation.id)))
        assertEquals(rejectedView, assertInstanceOf(PairingDecisionResult.Request::class.java, service().reject(elder, p2.id, rejectionOp)).view)
        assertEquals(PairingDecisionResult.Cancelled, service().cancel(caregiver, p3.id, cancelOp))
    }

    private class TestClock : Clock() {
        private val time = AtomicLong(1_792_752_000_000)
        fun set(value: Long) { time.set(value) }
        override fun millis() = time.get()
        override fun instant() = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock { require(zone == ZoneOffset.UTC); return this }
    }
}
