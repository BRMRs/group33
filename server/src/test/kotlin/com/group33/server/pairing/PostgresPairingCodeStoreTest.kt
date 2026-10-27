package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.PairingCodeView
import com.group33.shared.model.UserRole
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
class PostgresPairingCodeStoreTest {
    private val schema = "s2_pairing_it_" + UUID.randomUUID().toString().replace("-", "")
    private val clock = MutableClock()
    private val calls = AtomicInteger()
    private val generator = PairingCodeGenerator { calls.incrementAndGet().toString().padStart(8, '0') }
    private val actor = AnonymousUser("fictional-elder", "fictional-installation", "Fixture", UserRole.ELDER)
    private lateinit var source: PGSimpleDataSource
    private lateinit var cipherKey: ByteArray

    @BeforeEach fun prepare() {
        cipherKey = Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))
        source = dataSource(schema)
        dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        installSchema()
    }

    @AfterEach fun cleanup() {
        dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    private fun installSchema() {
        val ddl = checkNotNull(javaClass.getResource("/db/prototypes/pairing-code-store-v1.sql")).readText()
        source.connection.use { c ->
            c.autoCommit = false
            ddl.split(';').filter { it.isNotBlank() }.forEach { sql -> c.createStatement().use { it.execute(sql) } }
            c.commit()
        }
    }

    private fun store(wait: Long = 5_000, key: ByteArray = cipherKey) =
        PostgresPairingCodeStore(dataSource(schema), PairingReceiptCipher(key), wait)
    private fun service(store: PairingCodeStore = store(), gen: PairingCodeGenerator = generator) =
        PairingCodeIssuanceService(store, clock, gen)
    private fun issue(s: PairingCodeIssuanceService = service(), key: UUID = UUID.randomUUID(),
                      who: AnonymousUser = actor) = assertInstanceOf(CodeIssuanceResult.Issued::class.java, s.issue(who, key)).view
    private fun reject(result: CodeIssuanceResult, reason: CodeIssueRejection): CodeIssuanceResult.Rejected =
        assertInstanceOf(CodeIssuanceResult.Rejected::class.java, result).also { assertEquals(reason, it.reason) }
    private fun same(expected: PairingCodeView, actual: PairingCodeView) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.expiresAtEpochMillis, actual.expiresAtEpochMillis)
        assertEquals(PairingCodeIssuanceService.hash(expected.code), PairingCodeIssuanceService.hash(actual.code))
    }
    private fun execute(sql: String) { source.connection.use { it.createStatement().use { s -> s.execute(sql) } } }
    private fun scalar(sql: String): String = source.connection.use {
        it.createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getString(1) } }
    }
    private fun counts() = listOf("pairing_active_code", "pairing_issue_operation", "pairing_issuance_time")
        .map { scalar("SELECT count(*) FROM $it").toInt() }

    @Test fun `leading zero receipt replays after new service construction and expiry without extending deadline`() {
        val operation = UUID.randomUUID()
        val original = issue(key = operation)
        assertEquals('0', original.code.first())
        clock.advance(600_000)
        same(original, issue(service(gen = PairingCodeGenerator { error("Replay must not generate") }), operation))
        assertFalse(store().transaction { it.activeCode(actor.id)!!.isEffectiveAt(clock.millis()) })
        assertEquals(listOf(1, 1, 1), counts())
    }

    @Test fun `old successful replay does not replace a newer explicit code`() {
        val operation = UUID.randomUUID()
        val first = issue(key = operation)
        val second = issue()
        same(first, issue(key = operation))
        assertEquals(second.id, store().transaction { it.activeCode(actor.id)!!.id })
        assertEquals(listOf(1, 2, 2), counts())
    }

    @Test fun `rejection binding and rolling quota survive store reconstruction and can be reevaluated`() {
        repeat(3) { issue() }
        val operation = UUID.randomUUID()
        assertEquals(60, reject(service().issue(actor, operation), CodeIssueRejection.RATE_LIMITED).retryAfterSeconds)
        clock.advance(59_999)
        assertEquals(1, reject(service().issue(actor, operation), CodeIssueRejection.RATE_LIMITED).retryAfterSeconds)
        clock.advance(1)
        reject(service().issue(actor.copy(installationId = "changed"), operation), CodeIssueRejection.IDEMPOTENCY_KEY_REUSED)
        issue(key = operation)
        assertEquals(listOf(1, 4, 1), counts())
    }

    @Test fun `caregiver is rejected before any database mutation`() {
        reject(service().issue(actor.copy(role = UserRole.CAREGIVER), UUID.randomUUID()), CodeIssueRejection.ROLE_FORBIDDEN)
        assertEquals(listOf(0, 0, 0), counts())
    }

    private fun concurrent(block: () -> CodeIssuanceResult): List<CodeIssuanceResult> {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map { pool.submit<CodeIssuanceResult> { start.await(); block() } }
            start.countDown()
            return results.map { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `independent database connections serialize eight identical operations to one effect`() {
        val operation = UUID.randomUUID()
        val results = concurrent { service().issue(actor, operation) }
            .map { assertInstanceOf(CodeIssuanceResult.Issued::class.java, it).view }
        results.forEach { same(results.first(), it) }
        assertEquals(1, calls.get())
        assertEquals(listOf(1, 1, 1), counts())
    }

    @Test fun `independent stores cannot exceed three new issuances in a shared rolling window`() {
        val results = concurrent { service().issue(actor, UUID.randomUUID()) }
        assertEquals(3, results.count { it is CodeIssuanceResult.Issued })
        results.filterIsInstance<CodeIssuanceResult.Rejected>().forEach { assertEquals(CodeIssueRejection.RATE_LIMITED, it.reason) }
        assertEquals(3, calls.get())
        assertEquals(listOf(1, 8, 3), counts())
    }

    @Test fun `collision across elders regenerates while expired hash can be reused at exact boundary`() {
        val first = issue(service(gen = PairingCodeGenerator { "00001234" }))
        val values = ArrayDeque(listOf("00001234", "00005678"))
        issue(service(gen = PairingCodeGenerator { values.removeFirst() }), who = actor.copy(id = "other-elder"))
        assertTrue(values.isEmpty())
        assertEquals(2, scalar("SELECT count(DISTINCT code_hash) FROM pairing_active_code").toInt())
        clock.advance(600_000)
        val replacement = issue(service(gen = PairingCodeGenerator { "00001234" }), who = actor.copy(id = "third-elder"))
        assertNotEquals(first.id, replacement.id)
        assertEquals(1, scalar("SELECT count(*) FROM pairing_active_code WHERE expires_at > ${clock.millis()} AND code_hash = '${PairingCodeIssuanceService.hash(first.code)}'").toInt())
    }

    private fun failureTrigger(operation: UUID, deferred: Boolean) {
        execute("""CREATE FUNCTION fail_fixture_receipt() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN IF NEW.operation_id = '$operation'::uuid THEN
              RAISE EXCEPTION 'fictional receipt failure'; END IF; RETURN NEW; END ${'$'}${'$'}""")
        val prefix = if (deferred) "CREATE CONSTRAINT TRIGGER fixture_failure AFTER INSERT" else "CREATE TRIGGER fixture_failure BEFORE INSERT"
        val timing = if (deferred) "DEFERRABLE INITIALLY DEFERRED" else ""
        execute("$prefix ON pairing_issue_operation $timing FOR EACH ROW EXECUTE FUNCTION fail_fixture_receipt()")
    }

    private fun checkSqlRollback(deferred: Boolean) {
        val previous = issue()
        val operation = UUID.randomUUID()
        failureTrigger(operation, deferred)
        assertThrows(SQLException::class.java) { service().issue(actor, operation) }
        assertEquals(previous.id, store().transaction { it.activeCode(actor.id)!!.id })
        assertNull(store().transaction { it.operation(CodeIssueKey(actor.id, operation)) })
        assertEquals(listOf(1, 1, 1), counts())
        execute("DROP TRIGGER fixture_failure ON pairing_issue_operation")
        issue(key = operation)
        assertEquals(listOf(1, 2, 2), counts())
    }

    @Test fun `actual SQL receipt failure rolls back code quota and operation`() = checkSqlRollback(false)
    @Test fun `actual deferred commit failure rolls back code quota and receipt`() = checkSqlRollback(true)

    @Test fun `callback exception rolls back earlier real writes`() {
        assertThrows(IllegalArgumentException::class.java) {
            store().transaction { tx -> tx.putIssuanceTimes(actor.id, listOf(clock.millis())); errorForRollback() }
        }
        assertEquals(listOf(0, 0, 0), counts())
    }
    private fun errorForRollback(): Nothing = throw IllegalArgumentException("fictional callback failure")

    private fun checkContention(wait: Long) {
        val pool = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = pool.submit { store().transaction { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val operation = UUID.randomUUID()
            assertEquals(1, reject(service(store(wait)).issue(actor, operation), CodeIssueRejection.OPERATION_IN_PROGRESS).retryAfterSeconds)
            assertEquals(0, calls.get())
            release.countDown(); held.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(0, 0, 0), counts())
            issue(key = operation)
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS) }
    }
    @Test fun `NOWAIT contention maps to original operation unknown completion guidance`() = checkContention(0)
    @Test fun `finite database lock timeout rolls back and allows the same operation later`() = checkContention(50)

    @Test fun `service samples time after the actual database lock wait`() {
        val pool = Executors.newFixedThreadPool(2)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = pool.submit { store().transaction { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val next = pool.submit<PairingCodeView> { issue() }
            val timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name = '$schema' AND wait_event_type = 'Lock'") == "0") {
                check(System.nanoTime() < timeout) { "No actual database lock waiter observed" }
                Thread.sleep(10)
            }
            clock.advance(45_000)
            release.countDown(); held.get(5, TimeUnit.SECONDS)
            assertEquals(clock.millis() + 600_000, next.get(5, TimeUnit.SECONDS).expiresAtEpochMillis)
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test fun `schema script can be applied twice without losing accepted evidence`() {
        val operation = UUID.randomUUID()
        val first = issue(key = operation)
        installSchema()
        same(first, issue(key = operation))
        assertEquals(listOf(1, 1, 1), counts())
    }

    @Test fun `same UUID for different actors remains separately scoped`() {
        val operation = UUID.randomUUID()
        val first = issue(key = operation)
        val second = issue(key = operation, who = actor.copy(id = "second-elder"))
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(2, 2, 2), counts())
    }

    @Test fun `database key constraint rejects duplicate operation rows`() {
        issue()
        val failure = assertThrows(SQLException::class.java) {
            execute("INSERT INTO pairing_issue_operation SELECT * FROM pairing_issue_operation")
        }
        assertEquals("23505", failure.sqlState)
        assertEquals(listOf(1, 1, 1), counts())
    }

    @Test fun `stored rejection binding cannot be overwritten inside a transaction`() {
        repeat(3) { issue() }
        val operation = UUID.randomUUID()
        reject(service().issue(actor, operation), CodeIssueRejection.RATE_LIMITED)
        val key = CodeIssueKey(actor.id, operation)
        assertThrows(IllegalStateException::class.java) {
            store().transaction { tx -> val old = tx.operation(key)!!
                tx.putOperation(key, old.copy(binding = old.binding.copy(installationId = "changed"))) }
        }
        assertEquals(actor.installationId, store().transaction { it.operation(key)!!.binding.installationId })
    }

    @Test fun `receipt bytes are encrypted and tampering cannot replay or issue a new code`() {
        val operation = UUID.randomUUID()
        val first = issue(key = operation)
        source.connection.use { c ->
            val payload = c.createStatement().use { s -> s.executeQuery("SELECT receipt_cipher FROM pairing_issue_operation").use { r -> r.next(); r.getBytes(1) } }
            assertEquals(37, payload.size)
            assertFalse(payload.toString(Charsets.US_ASCII).contains(first.code))
            payload[20] = (payload[20].toInt() xor 1).toByte()
            c.prepareStatement("UPDATE pairing_issue_operation SET receipt_cipher = ?").use { it.setBytes(1, payload); it.executeUpdate() }
        }
        assertThrows(IllegalStateException::class.java) { service().issue(actor, operation) }
        assertEquals(1, calls.get())
        assertEquals(listOf(1, 1, 1), counts())
    }

    @Test fun `receipt metadata is authenticated and a different key cannot restore it`() {
        val operation = UUID.randomUUID()
        val first = issue(key = operation)
        assertThrows(IllegalStateException::class.java) { service(store(key = ByteArray(32) { 8 })).issue(actor, operation) }
        same(first, issue(key = operation))
        execute("UPDATE pairing_issue_operation SET receipt_expiry = receipt_expiry + 1")
        assertThrows(IllegalStateException::class.java) { service().issue(actor, operation) }
        assertEquals(1, calls.get())
    }

    @Test fun `missing short or mutable external key has no insecure fallback`() {
        assertThrows(IllegalArgumentException::class.java) { PairingReceiptCipher(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { PairingReceiptCipher(ByteArray(31)) }
        val key = cipherKey.copyOf()
        val adapter = store(key = key)
        key.fill(0)
        val operation = UUID.randomUUID()
        val first = issue(service(adapter), operation)
        same(first, issue(key = operation))
        assertEquals("PairingReceiptCipher(REDACTED)", PairingReceiptCipher(cipherKey).toString())
    }

    private fun process(vararg args: String): String {
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("s2.postgres.test.classpath"),
            "com.group33.server.pairing.PairingPostgresProcessProbe", schema) + args
        val child = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Child JVM did not terminate")
            assertEquals(0, child.exitValue(), "Child JVM failed; no credential-bearing output copied")
            return child.inputStream.bufferedReader().readText().trim()
        } finally { if (child.isAlive) child.destroyForcibly() }
    }

    @Test fun `accepted operation survives issuing JVM exit and a separate replay JVM`() {
        val operation = UUID.randomUUID().toString()
        val first = process("issue", operation, clock.millis().toString())
        val second = process("replay", operation, (clock.millis() + 700_000).toString())
        assertEquals(first, second)
        assertEquals(listOf(1, 1, 1), counts())
    }

    @Test fun `accepted receipt active code and quota survive an actual PostgreSQL restart`() {
        val operation = UUID.randomUUID()
        val first = issue(key = operation)
        val manager = required("S2_PG_MANAGER_SCRIPT")
        fun control(action: String) {
            val child = ProcessBuilder(required("S2_PG_PYTHON"), manager, action).redirectErrorStream(true).start()
            assertTrue(child.waitFor(40, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
        }
        try { control("stop") } finally { control("start") }
        clock.advance(120_000)
        same(first, issue(service(gen = PairingCodeGenerator { error("Replay after restart must not generate") }), operation))
        assertEquals(first.id, store().transaction { it.activeCode(actor.id)!!.id })
        assertEquals(listOf(1, 1, 1), counts())
    }

    private class MutableClock : Clock() {
        private val time = AtomicLong(1_792_752_000_000)
        fun advance(millis: Long) { time.addAndGet(millis) }
        override fun millis() = time.get()
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    companion object {
        internal fun required(name: String) = requireNotNull(System.getenv(name)) { "Missing explicit integration setting $name" }
        internal fun dataSource(schema: String? = null) = PGSimpleDataSource().apply {
            val url = required("S2_PG_INTEGRATION_URL")
            require(url == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local") { "Only the dedicated local practice database is allowed" }
            setURL(url)
            user = required("S2_PG_INTEGRATION_USER")
            password = required("S2_PG_INTEGRATION_PASSWORD")
            connectTimeout = 5
            socketTimeout = 10
            schema?.let { currentSchema = it; applicationName = it }
        }
    }
}
