package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.PairingCodeView
import com.group33.shared.model.UserRole
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PairingCodeIssuanceServiceTest {
    private val actor = AnonymousUser("fictional-elder", "fictional-install", "Fictional Elder", UserRole.ELDER)
    private val other = actor.copy(id = "other-elder", installationId = "other-install")
    private val clock = MutableClock()
    private val store = InMemoryPairingCodeStore()
    private val calls = AtomicInteger()
    private val generator = PairingCodeGenerator { calls.incrementAndGet().toString().padStart(8, '0') }
    private val service = PairingCodeIssuanceService(store, clock, generator)
    private fun key() = UUID.randomUUID()
    private fun issued(value: CodeIssuanceResult) = assertInstanceOf(CodeIssuanceResult.Issued::class.java, value).view
    private fun rejected(value: CodeIssuanceResult, reason: CodeIssueRejection): CodeIssuanceResult.Rejected =
        assertInstanceOf(CodeIssuanceResult.Rejected::class.java, value).also { assertEquals(reason, it.reason) }
    private fun effective(view: PairingCodeView) = store.transaction {
        it.hasEffectiveHash(PairingCodeIssuanceService.hash(view.code), clock.millis())
    }

    @Test fun `only elder can issue and authorization is rechecked before replay`() {
        val operation = key()
        rejected(service.issue(actor.copy(role = UserRole.CAREGIVER), operation), CodeIssueRejection.ROLE_FORBIDDEN)
        assertEquals(0, calls.get())
        issued(service.issue(actor, operation))
        rejected(service.issue(actor.copy(role = UserRole.CAREGIVER), operation), CodeIssueRejection.ROLE_FORBIDDEN)
        assertEquals(1, calls.get())
    }

    @Test fun `leading zeros and exact original server expiry survive replay`() {
        val operation = key()
        val first = issued(service.issue(actor, operation))
        assertEquals("00000001", first.code)
        assertEquals(clock.millis() + 600_000, first.expiresAtEpochMillis)
        clock.advance(12_345)
        assertEquals(first, issued(service.issue(actor.copy(displayName = "Renamed"), operation)))
        assertEquals(1, calls.get())
    }

    @Test fun `expiry is exclusive at ten minutes and replay never renews`() {
        val operation = key()
        val first = issued(service.issue(actor, operation))
        for ((offset, expected) in listOf(-1L to true, 0L to false, 1L to false)) {
            clock.set(first.expiresAtEpochMillis + offset)
            assertEquals(expected, effective(first))
            assertEquals(first, issued(service.issue(actor, operation)))
            assertEquals(expected, effective(first))
        }
        assertEquals(1, calls.get())
    }

    @Test fun `new intent replaces only its owner's old opportunity and old replay cannot reactivate it`() {
        val originalKey = key()
        val original = issued(service.issue(actor, originalKey))
        val unrelated = issued(service.issue(other, key()))
        val replacement = issued(service.issue(actor, key()))
        assertNotEquals(original.id, replacement.id)
        assertFalse(effective(original))
        assertTrue(effective(replacement))
        assertTrue(effective(unrelated))
        assertEquals(original, issued(service.issue(actor, originalKey)))
        assertFalse(effective(original))
        assertTrue(effective(replacement))
    }

    @Test fun `same operation number cannot retrieve another elder's result`() {
        val operation = key()
        val first = issued(service.issue(actor, operation))
        val second = issued(service.issue(other, operation))
        assertNotEquals(first, second)
        assertEquals(first, issued(service.issue(actor, operation)))
        assertEquals(second, issued(service.issue(other, operation)))
    }

    @Test fun `changed binding is rejected while exact request is retained`() {
        val operation = key()
        val first = issued(service.issue(actor, operation))
        rejected(service.issue(actor.copy(installationId = "different-install"), operation),
            CodeIssueRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(first, issued(service.issue(actor, operation)))
        assertEquals(1, calls.get())
    }

    @Test fun `stored target or canonical request mismatch cannot replay an accepted receipt`() {
        val operation = key()
        val first = issued(service.issue(actor, operation))
        val receiptKey = CodeIssueKey(actor.id, operation)
        val original = checkNotNull(store.transaction { it.operation(receiptKey) })
        for (binding in listOf(original.binding.copy(targetElderId = other.id),
            original.binding.copy(fingerprint = "different-canonical-request"))) {
            store.transaction { it.putOperation(receiptKey, original.copy(binding = binding)) }
            rejected(service.issue(actor, operation), CodeIssueRejection.IDEMPOTENCY_KEY_REUSED)
            assertTrue(effective(first))
            assertEquals(1, calls.get())
        }
        store.transaction { it.putOperation(receiptKey, original) }
        assertEquals(first, issued(service.issue(actor, operation)))
    }

    @Test fun `effective hash collision is regenerated including the replaced owner's current code`() {
        val values = ArrayDeque(listOf("00123456", "00123456", "00234567", "00234567", "00345678"))
        val collisionService = PairingCodeIssuanceService(store, clock) { values.removeFirst() }
        val first = issued(collisionService.issue(actor, key()))
        val second = issued(collisionService.issue(other, key()))
        val third = issued(collisionService.issue(other, key()))
        assertEquals("00123456", first.code)
        assertEquals("00234567", second.code)
        assertEquals("00345678", third.code)
        assertTrue(effective(first))
        assertFalse(effective(second))
        assertTrue(effective(third))
        assertTrue(values.isEmpty())
    }

    @Test fun `generation exhaustion does not replace old code or debit quota`() {
        val original = issued(service.issue(actor, key()))
        val repeating = PairingCodeIssuanceService(store, clock) { original.code }
        val operation = key()
        rejected(repeating.issue(actor, operation), CodeIssueRejection.CODE_GENERATION_UNAVAILABLE)
        assertTrue(effective(original))
        val afterFailure = issued(service.issue(actor, operation))
        assertTrue(effective(afterFailure))
        issued(service.issue(actor, key()))
        rejected(service.issue(actor, key()), CodeIssueRejection.RATE_LIMITED)
    }

    @Test fun `malformed generator output is never issued`() {
        val malformed = PairingCodeIssuanceService(store, clock) { "1234567" }
        rejected(malformed.issue(actor, key()), CodeIssueRejection.CODE_GENERATION_UNAVAILABLE)
        assertNull(store.transaction { it.activeCode(actor.id) })
    }

    @Test fun `rate limit uses three new issuances per rolling minute with rounded retry after`() {
        val firstKey = key()
        val first = issued(service.issue(actor, firstKey))
        issued(service.issue(actor, key()))
        val third = issued(service.issue(actor, key()))
        val blockedKey = key()
        assertEquals(60, rejected(service.issue(actor, blockedKey), CodeIssueRejection.RATE_LIMITED).retryAfterSeconds)
        assertEquals(first, issued(service.issue(actor, firstKey)))
        assertTrue(effective(third))
        issued(service.issue(other, key()))
        clock.advance(59_999)
        assertEquals(1, rejected(service.issue(actor, blockedKey), CodeIssueRejection.RATE_LIMITED).retryAfterSeconds)
        clock.advance(1)
        issued(service.issue(actor, blockedKey))
        assertEquals(5, calls.get())
    }

    @Test fun `rejection binding remains checked when retrying after quota recovers`() {
        repeat(3) { issued(service.issue(actor, key())) }
        val rejectedKey = key()
        rejected(service.issue(actor, rejectedKey), CodeIssueRejection.RATE_LIMITED)
        clock.advance(60_000)
        rejected(service.issue(actor.copy(installationId = "changed"), rejectedKey), CodeIssueRejection.IDEMPOTENCY_KEY_REUSED)
        issued(service.issue(actor, rejectedKey))
    }

    @Test fun `concurrent same operation has one code receipt and quota effect`() {
        val operation = key()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { pool.submit<PairingCodeView> { start.await(); issued(service.issue(actor, operation)) } }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.distinct().size)
            assertEquals(1, calls.get())
            issued(service.issue(actor, key()))
            issued(service.issue(actor, key()))
            rejected(service.issue(actor, key()), CodeIssueRejection.RATE_LIMITED)
        } finally { pool.shutdownNow() }
    }

    @Test fun `concurrent distinct intents cannot exceed the shared elder quota`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { pool.submit<CodeIssuanceResult> { start.await(); service.issue(actor, key()) } }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(3, results.count { it is CodeIssuanceResult.Issued })
            results.filterIsInstance<CodeIssuanceResult.Rejected>().forEach {
                assertEquals(CodeIssueRejection.RATE_LIMITED, it.reason)
            }
            assertEquals(3, calls.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun `failure writing receipt rolls back replacement quota and operation evidence`() {
        val original = issued(service.issue(actor, key()))
        val operation = key()
        val failingStore = object : PairingCodeStore {
            override fun <T> transaction(work: (PairingCodeTransaction) -> T) = store.transaction { tx ->
                work(object : PairingCodeTransaction by tx {
                    override fun putOperation(key: CodeIssueKey, value: CodeIssueOperation) {
                        if (value.receipt != null) throw IllegalStateException("fictional receipt failure")
                        tx.putOperation(key, value)
                    }
                })
            }
        }
        val failing = PairingCodeIssuanceService(failingStore, clock, generator)
        assertThrows(IllegalStateException::class.java) { failing.issue(actor, operation) }
        assertTrue(effective(original))
        assertNull(store.transaction { it.operation(CodeIssueKey(actor.id, operation)) })
        assertEquals(1, store.transaction { it.issuanceTimes(actor.id).size })
        issued(service.issue(actor, operation))
        issued(service.issue(actor, key()))
        rejected(service.issue(actor, key()), CodeIssueRejection.RATE_LIMITED)
    }

    @Test fun `bounded store contention preserves unknown completion retry guidance`() {
        val bounded = InMemoryPairingCodeStore(lockWaitMillis = 0)
        val pool = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = pool.submit { bounded.transaction { locked.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val operation = key()
            val busyService = PairingCodeIssuanceService(bounded, clock, generator)
            assertEquals(1, rejected(busyService.issue(actor, operation), CodeIssueRejection.OPERATION_IN_PROGRESS).retryAfterSeconds)
            assertEquals(0, calls.get())
            release.countDown()
            held.get(5, TimeUnit.SECONDS)
            issued(busyService.issue(actor, operation))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `production generator returns eight digit strings and diagnostic values redact codes`() {
        val secure = SecurePairingCodeGenerator()
        repeat(16) { assertTrue(secure.nextCode().matches(Regex("[0-9]{8}"))) }
        val operation = key()
        val result = service.issue(actor, operation)
        val view = issued(result)
        assertFalse(result.toString().contains(view.code))
        assertFalse(store.transaction { it.operation(CodeIssueKey(actor.id, operation)) }.toString().contains(view.code))
    }

    private class MutableClock(
        private val time: AtomicLong = AtomicLong(1_792_752_000_000),
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        fun set(value: Long) { time.set(value) }
        fun advance(millis: Long) { time.addAndGet(millis) }
        override fun millis() = time.get()
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(time, zone)
    }
}
