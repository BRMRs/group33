package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.net.InetAddress
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PairingRequestServiceTest {
    private val elder = AnonymousUser("elder-a", "install-e", "Fictional Elder", UserRole.ELDER)
    private val caregiver = AnonymousUser("caregiver-a", "install-c", "Fictional Caregiver", UserRole.CAREGIVER)
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
    private val clock = TestClock()
    private val store = InMemoryPairingCodeStore()
    private val generated = AtomicInteger()
    private val issuer = PairingCodeIssuanceService(store, clock) { generated.incrementAndGet().toString().padStart(8, '0') }
    private val service = PairingRequestService(store, clock)
    private fun key() = UUID.randomUUID()
    private fun issue(actor: AnonymousUser = elder, operation: UUID = key()) =
        assertInstanceOf(CodeIssuanceResult.Issued::class.java, issuer.issue(actor, operation)).view
    private fun submit(code: String, actor: AnonymousUser = caregiver, operation: UUID = key(), from: InetAddress = address) =
        service.request(actor, operation, RequestPairingRequest(code), from)
    private fun success(result: PairingRequestResult) = assertInstanceOf(PairingRequestResult.Success::class.java, result).view
    private fun reject(result: PairingRequestResult, reason: PairingRequestRejection) =
        assertInstanceOf(PairingRequestResult.Rejected::class.java, result).also { assertEquals(reason, it.reason) }

    @Test fun `consumes a leading zero code into one pending request inheriting expiry and trusted identity`() {
        val code = issue()
        clock.advance(123_456)
        val view = success(submit(code.code))
        assertEquals("00000001", code.code)
        assertEquals(elder.id, view.elderId)
        assertEquals(caregiver.id, view.caregiver.id)
        assertEquals(caregiver.displayName, view.caregiver.displayName)
        assertEquals(UserRole.CAREGIVER, view.caregiver.role)
        assertEquals(PairingRequestStatus.PENDING, view.status)
        assertEquals(code.expiresAtEpochMillis, view.expiresAtEpochMillis)
        assertNull(view.relationshipId)
        assertNull(view.closedReason)
        assertNull(store.requestTransaction { it.unconsumedCode(PairingCodeIssuanceService.hash(code.code), clock.millis()) })
        assertEquals(view, success(service.detail(elder, view.id)))
        assertEquals(view, success(service.detail(caregiver, view.id)))
    }

    @Test fun `role is checked before consuming or replaying`() {
        val code = issue()
        val operation = key()
        reject(submit(code.code, elder, operation), PairingRequestRejection.ROLE_FORBIDDEN)
        val view = success(submit(code.code, operation = operation))
        reject(submit(code.code, caregiver.copy(role = UserRole.ELDER), operation), PairingRequestRejection.ROLE_FORBIDDEN)
        assertEquals(view, success(submit(code.code, operation = operation)))
    }

    @Test fun `accepted replay bypasses new attempts and does not refresh request or display name`() {
        val code = issue()
        val operation = key()
        val view = success(submit(code.code, operation = operation))
        repeat(4) { reject(submit("99999999"), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE) }
        clock.advance(30_000)
        val otherAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 9))
        repeat(8) { assertEquals(view, success(submit(code.code, caregiver.copy(displayName = "Renamed"), operation, otherAddress))) }
        assertEquals(5, store.requestTransaction { it.installationAttempts(caregiver.installationId).size })
    }

    @Test fun `changed payload or installation cannot reuse accepted operation`() {
        val code = issue()
        val operation = key()
        val view = success(submit(code.code, operation = operation))
        reject(submit("00000002", operation = operation), PairingRequestRejection.IDEMPOTENCY_KEY_REUSED)
        reject(submit(code.code, caregiver.copy(installationId = "changed"), operation), PairingRequestRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(view, success(submit(code.code, operation = operation)))
    }

    @Test fun `same UUID from another actor cannot retrieve the first actor receipt`() {
        val code = issue()
        val operation = key()
        val view = success(submit(code.code, operation = operation))
        val other = caregiver.copy(id = "other", installationId = "other-install")
        reject(submit(code.code, other, operation), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        reject(service.detail(other, view.id), PairingRequestRejection.RESOURCE_NOT_ACCESSIBLE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "123456789", " 00000001", "00000001 ", "０００００００１", "abcdefgh", "", "00000001\n"])
    fun `invalid code formats do not consume a valid code and are counted`(raw: String) {
        val code = issue()
        reject(submit(raw), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        success(submit(code.code))
        assertEquals(2, store.requestTransaction { it.installationAttempts(caregiver.installationId).size })
    }

    @Test fun `new consumption accepts deadline minus one and rejects exactly deadline and plus one`() {
        for (offset in listOf(-1L, 0L, 1L)) {
            val e = elder.copy(id = "e$offset", installationId = "e-install$offset")
            val code = issue(e)
            clock.set(code.expiresAtEpochMillis + offset)
            val result = submit(code.code)
            if (offset < 0) assertEquals(code.expiresAtEpochMillis, success(result).expiresAtEpochMillis)
            else reject(result, PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        }
    }

    @Test fun `expiry detail remains terminal while accepted replay preserves original pending receipt`() {
        val code = issue()
        val operation = key()
        val original = success(submit(code.code, operation = operation))
        clock.set(code.expiresAtEpochMillis - 1)
        assertEquals(PairingRequestStatus.PENDING, success(service.detail(elder, original.id)).status)
        for (offset in listOf(0L, 1L, 30L * 86_400_000)) {
            clock.set(code.expiresAtEpochMillis + offset)
            val terminal = success(service.detail(caregiver, original.id))
            assertEquals(PairingRequestStatus.EXPIRED, terminal.status)
            assertEquals(PairingClosedReason.CODE_EXPIRED, terminal.closedReason)
            assertNull(terminal.relationshipId)
            assertEquals(original, success(submit(code.code, operation = operation)))
            reject(submit(code.code), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        }
    }

    @Test fun `renewal cancels only its pending request and neither issuance nor request replay reopens it`() {
        val issueOperation = key()
        val code = issue(operation = issueOperation)
        val unrelatedCode = issue(elder.copy(id = "other-elder", installationId = "other-e-install"))
        val requestOperation = key()
        val pending = success(submit(code.code, operation = requestOperation))
        val otherPending = success(submit(unrelatedCode.code))
        val replacement = issue()
        val closed = success(service.detail(elder, pending.id))
        assertEquals(PairingRequestStatus.CANCELLED, closed.status)
        assertEquals(PairingClosedReason.CODE_REPLACED, closed.closedReason)
        assertEquals(otherPending, success(service.detail(caregiver, otherPending.id)))
        assertEquals(code, issue(operation = issueOperation))
        assertEquals(pending, success(submit(code.code, operation = requestOperation)))
        assertEquals(closed, success(service.detail(elder, pending.id)))
        reject(submit(code.code), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        success(submit(replacement.code))
    }

    @Test fun `renewal at expired request deadline retains expired instead of inventing a later replacement reason`() {
        val code = issue()
        val pending = success(submit(code.code))
        clock.set(code.expiresAtEpochMillis)
        issue()
        val closed = success(service.detail(elder, pending.id))
        assertEquals(PairingRequestStatus.EXPIRED, closed.status)
        assertEquals(PairingClosedReason.CODE_EXPIRED, closed.closedReason)
    }

    @Test fun `consumed but unexpired code stays reserved against generator collisions`() {
        val code = issue()
        success(submit(code.code))
        val stuck = PairingCodeIssuanceService(store, clock) { code.code }
        val other = elder.copy(id = "other", installationId = "other-install")
        val failure = assertInstanceOf(CodeIssuanceResult.Rejected::class.java, stuck.issue(other, key()))
        assertEquals(CodeIssueRejection.CODE_GENERATION_UNAVAILABLE, failure.reason)
    }

    @Test fun `unrelated and wrong role detail matches missing request and exposes no participant fields`() {
        val pending = success(submit(issue().code))
        val unavailable = reject(service.detail(caregiver, "missing"), PairingRequestRejection.RESOURCE_NOT_ACCESSIBLE)
        for (actor in listOf(elder.copy(id = "outsider"), caregiver.copy(id = "outsider"),
            elder.copy(role = UserRole.CAREGIVER), caregiver.copy(role = UserRole.ELDER))) {
            assertEquals(unavailable, service.detail(actor, pending.id))
        }
    }

    @Test fun `installation rate limit counts failed guesses rounds retry and recovers exactly at one minute`() {
        val initial = clock.millis()
        repeat(5) { reject(submit("99999999"), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE) }
        val operation = key()
        assertEquals(60L, reject(submit("99999999", operation = operation), PairingRequestRejection.RATE_LIMITED).retryAfterSeconds)
        clock.set(initial + 59_999)
        assertEquals(1L, reject(submit("99999999", operation = operation), PairingRequestRejection.RATE_LIMITED).retryAfterSeconds)
        clock.set(initial + 60_000)
        reject(submit("99999999", operation = operation), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        assertEquals(1, store.requestTransaction { it.installationAttempts(caregiver.installationId).size })
    }

    @Test fun `IP rate limit spans installations but a different address is independent`() {
        repeat(20) { i ->
            reject(submit("99999999", caregiver.copy(id = "c$i", installationId = "i$i")), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        }
        val operation = key()
        assertEquals(60L, reject(submit("99999999", operation = operation), PairingRequestRejection.RATE_LIMITED).retryAfterSeconds)
        val otherAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2))
        reject(submit("99999999", operation = operation, from = otherAddress), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        clock.advance(60_000)
        reject(submit("99999999", operation = operation), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
    }

    @Test fun `installation limit persists across IP changes while other installations remain independent`() {
        repeat(5) { reject(submit("99999999"), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE) }
        val otherAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2))
        reject(submit("99999999", from = otherAddress), PairingRequestRejection.RATE_LIMITED)
        reject(submit("99999999", caregiver.copy(id = "other", installationId = "other-install")), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
    }

    @Test fun `rejected operation remains bound but same payload may succeed after a precondition is fixed`() {
        val operation = key()
        reject(submit("00000001", operation = operation), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        issue()
        reject(submit("00000002", operation = operation), PairingRequestRejection.IDEMPOTENCY_KEY_REUSED)
        reject(submit("00000001", caregiver.copy(installationId = "changed"), operation), PairingRequestRejection.IDEMPOTENCY_KEY_REUSED)
        success(submit("00000001", operation = operation))
    }

    @Test fun `eight concurrent same operations create one request and one quota effect`() {
        val code = issue()
        val operation = key()
        val results = concurrently { submit(code.code, operation = operation) }.map(::success)
        assertEquals(1, results.map { it.id }.distinct().size)
        assertEquals(1, store.requestTransaction { it.installationAttempts(caregiver.installationId).size })
    }

    @Test fun `eight different caregivers racing the same code cannot create a second request`() {
        val code = issue()
        val results = concurrently { i -> submit(code.code, caregiver.copy(id = "c$i", installationId = "i$i")) }
        assertEquals(1, results.count { it is PairingRequestResult.Success })
        results.filterIsInstance<PairingRequestResult.Rejected>().forEach { assertEquals(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE, it.reason) }
    }

    @Test fun `concurrent renewal and consumption leave no pending request on the replaced code`() {
        val code = issue()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val consume = pool.submit<PairingRequestResult> { start.await(); submit(code.code) }
            val renew = pool.submit<PairingCodeView> { start.await(); issue() }
            start.countDown()
            val result = consume.get(5, TimeUnit.SECONDS)
            val replacement = renew.get(5, TimeUnit.SECONDS)
            when (result) {
                is PairingRequestResult.Success -> {
                    val terminal = success(service.detail(elder, result.view.id))
                    assertEquals(PairingRequestStatus.CANCELLED, terminal.status)
                    assertEquals(PairingClosedReason.CODE_REPLACED, terminal.closedReason)
                }
                is PairingRequestResult.Rejected -> assertEquals(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE, result.reason)
            }
            reject(submit(code.code), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
            success(submit(replacement.code))
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun `waiting request checks expiry after acquiring transaction not before waiting`() {
        val code = issue()
        clock.set(code.expiresAtEpochMillis - 1)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val delayed = object : PairingRequestStore by store {
            override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                return store.requestTransaction(work)
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        val pending = pool.submit<PairingRequestResult> {
            PairingRequestService(delayed, clock).request(caregiver, key(), RequestPairingRequest(code.code), address)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            clock.set(code.expiresAtEpochMillis)
            proceed.countDown()
            reject(pending.get(5, TimeUnit.SECONDS), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        } finally { proceed.countDown(); pool.shutdownNow() }
    }

    @Test fun `when both rate buckets are full retry after covers the slower bucket`() {
        val otherAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2))
        repeat(5) { reject(submit("99999999", from = otherAddress), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE) }
        clock.advance(30_000)
        repeat(20) { i ->
            reject(submit("99999999", caregiver.copy(id = "c$i", installationId = "i$i")), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
        }
        assertEquals(60L, reject(submit("99999999"), PairingRequestRejection.RATE_LIMITED).retryAfterSeconds)
        clock.advance(30_000)
        assertEquals(30L, reject(submit("99999999"), PairingRequestRejection.RATE_LIMITED).retryAfterSeconds)
        clock.advance(30_000)
        reject(submit("99999999"), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
    }

    @Test fun `failed receipt write rolls back consumption request and both quotas then original retry succeeds`() {
        val code = issue()
        val operation = key()
        var stagedId: String? = null
        val broken = object : PairingRequestStore by store {
            override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T = store.requestTransaction { tx ->
                work(object : PairingRequestTransaction by tx {
                    override fun putRequestOperation(key: PairingRequestKey, value: PairingRequestOperation) {
                        tx.putRequestOperation(key, value)
                        stagedId = value.receipt?.id
                        throw IllegalStateException("injected receipt failure")
                    }
                })
            }
        }
        assertThrows(IllegalStateException::class.java) { PairingRequestService(broken, clock).request(caregiver, operation, RequestPairingRequest(code.code), address) }
        assertNotNull(stagedId)
        assertNull(store.requestTransaction { it.request(checkNotNull(stagedId), clock.millis()) })
        assertNull(store.requestTransaction { it.requestOperation(PairingRequestKey(caregiver.id, operation)) })
        assertTrue(store.requestTransaction { it.installationAttempts(caregiver.installationId).isEmpty() })
        assertTrue(store.requestTransaction { it.addressAttempts(addressHash()).isEmpty() })
        success(submit(code.code, operation = operation))
    }

    @Test fun `failed renewal receipt leaves old pending request and code opportunity unchanged`() {
        val code = issue()
        val pending = success(submit(code.code))
        val broken = object : PairingCodeStore {
            override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = store.transaction { tx ->
                work(object : PairingCodeTransaction by tx {
                    override fun putOperation(key: CodeIssueKey, value: CodeIssueOperation) { error("injected renewal failure") }
                })
            }
        }
        assertThrows(IllegalStateException::class.java) { PairingCodeIssuanceService(broken, clock) { "87654321" }.issue(elder, key()) }
        assertEquals(pending, success(service.detail(elder, pending.id)))
        assertEquals(code.id, store.transaction { it.activeCode(elder.id)?.id })
        reject(submit(code.code), PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
    }

    @Test fun `bounded store contention returns retryable in progress and does not consume`() {
        val bounded = InMemoryPairingCodeStore(0)
        val localIssuer = PairingCodeIssuanceService(bounded, clock) { "00123456" }
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java, localIssuer.issue(elder, key())).view
        val local = PairingRequestService(bounded, clock)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val held = pool.submit { bounded.transaction { locked.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val result = local.request(caregiver, key(), RequestPairingRequest(code.code), address)
            assertEquals(1L, reject(result, PairingRequestRejection.OPERATION_IN_PROGRESS).retryAfterSeconds)
        } finally {
            release.countDown(); held.get(5, TimeUnit.SECONDS); pool.shutdownNow()
        }
        success(local.request(caregiver, key(), RequestPairingRequest(code.code), address))
    }

    @Test fun `diagnostics exclude raw code and display identity`() {
        val code = issue()
        val operation = key()
        val result = submit(code.code, operation = operation)
        val stored = store.requestTransaction { it.requestOperation(PairingRequestKey(caregiver.id, operation)) }
        for (text in listOf(result.toString(), stored.toString())) {
            assertFalse(text.contains(code.code))
            assertFalse(text.contains(caregiver.displayName))
            assertFalse(text.contains(caregiver.installationId))
        }
    }

    private fun addressHash() = PairingCodeIssuanceService.hash(java.util.HexFormat.of().formatHex(address.address))

    private fun concurrently(block: (Int) -> PairingRequestResult): List<PairingRequestResult> {
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { i -> pool.submit<PairingRequestResult> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); block(i)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            return futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally { start.countDown(); pool.shutdownNow() }
    }

    private class TestClock : Clock() {
        private val now = AtomicLong(1_000_000)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock { require(zone == ZoneOffset.UTC); return this }
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun millis() = now.get()
        fun advance(delta: Long) { now.addAndGet(delta) }
        fun set(value: Long) { now.set(value) }
    }
}
