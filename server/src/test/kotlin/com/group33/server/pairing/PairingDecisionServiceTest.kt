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

class PairingDecisionServiceTest {
    private val elder = AnonymousUser("elder-a", "install-e", "虚构长者", UserRole.ELDER)
    private val caregiver = AnonymousUser("caregiver-a", "install-c", "虚构照护者", UserRole.CAREGIVER)
    private val clock = TestClock()
    private val store = InMemoryPairingCodeStore()
    private val counter = AtomicInteger()
    private val issuer = PairingCodeIssuanceService(store, clock) { counter.incrementAndGet().toString().padStart(8, '0') }
    private val requester = PairingRequestService(store, clock)
    private val service = PairingDecisionService(store, clock)
    private val access = StoredCareRelationshipAccess(store)
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
    private fun key() = UUID.randomUUID()
    private fun pending(e: AnonymousUser = elder, c: AnonymousUser = caregiver): PairingRequestView {
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java, issuer.issue(e, key())).view
        return assertInstanceOf(PairingRequestResult.Success::class.java,
            requester.request(c, key(), RequestPairingRequest(code.code), address)).view
    }
    private fun relation(result: PairingDecisionResult) = assertInstanceOf(PairingDecisionResult.Relationship::class.java, result).view
    private fun rejected(result: PairingDecisionResult, reason: PairingDecisionRejection) =
        assertInstanceOf(PairingDecisionResult.Rejected::class.java, result).also { assertEquals(reason, it.reason) }
    private fun detail(p: PairingRequestView) = assertInstanceOf(PairingRequestResult.Success::class.java, requester.detail(elder, p.id)).view

    @Test fun `pending grants no access and only explicit elder acceptance creates identifiable relationship`() {
        val p = pending()
        assertFalse(access.isActive(caregiver.id, elder.id))
        val r = relation(service.accept(elder, p.id, key()))
        assertEquals(CareRelationshipStatus.ACTIVE, r.status)
        assertEquals(elder.id, r.elder.id); assertEquals(elder.displayName, r.elder.displayName)
        assertEquals(caregiver.id, r.caregiver.id); assertEquals(caregiver.displayName, r.caregiver.displayName)
        assertEquals(clock.millis(), r.createdAtEpochMillis); assertNull(r.revokedAtEpochMillis)
        assertEquals(PairingRequestStatus.ACCEPTED, detail(p).status)
        assertEquals(r.id, detail(p).relationshipId)
        assertTrue(access.isActive(caregiver.id, elder.id))
        assertFalse(access.isActive("other", elder.id)); assertFalse(access.isActive(caregiver.id, "other"))
    }

    @Test fun `caregiver cannot accept or reject even using elders operation key`() {
        val p = pending(); val op = key()
        service.accept(elder, p.id, op)
        rejected(service.accept(caregiver, p.id, op), PairingDecisionRejection.ROLE_NOT_ALLOWED)
        rejected(service.reject(caregiver, p.id, op), PairingDecisionRejection.ROLE_NOT_ALLOWED)
    }

    @Test fun `outsiders and forged roles receive identical missing resource result for all actions`() {
        val p = pending(); val r = relation(service.accept(elder, p.id, key()))
        for (actor in listOf(elder.copy(id = "outsider"), caregiver.copy(id = "outsider"),
            elder.copy(role = UserRole.CAREGIVER), caregiver.copy(role = UserRole.ELDER))) {
            for (id in listOf(p.id, "missing")) {
                rejected(service.accept(actor, id, key()), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
                rejected(service.reject(actor, id, key()), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
                rejected(service.cancel(actor, id, key()), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
            }
            rejected(service.revoke(actor, r.id, key()), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
            rejected(service.revoke(actor, "missing", key()), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
            rejected(service.relationship(actor, r.id), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
        }
    }

    @Test fun `reject is terminal and never creates a relationship or reopens the code`() {
        val p = pending(); val code = "00000001"; val op = key()
        val result = assertInstanceOf(PairingDecisionResult.Request::class.java, service.reject(elder, p.id, op)).view
        assertEquals(PairingRequestStatus.REJECTED, result.status)
        assertEquals(PairingClosedReason.ELDER_REJECTED, result.closedReason)
        assertFalse(access.isActive(caregiver.id, elder.id))
        assertEquals(result, assertInstanceOf(PairingDecisionResult.Request::class.java, service.reject(elder, p.id, op)).view)
        rejected(service.accept(elder, p.id, key()), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
        val reused = assertInstanceOf(PairingRequestResult.Rejected::class.java,
            requester.request(caregiver, key(), RequestPairingRequest(code), address))
        assertEquals(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE, reused.reason)
    }

    @ParameterizedTest @ValueSource(booleans = [true, false])
    fun `either participant can cancel with stable reason and original no content success`(byElder: Boolean) {
        val p = pending(); val op = key(); val actor = if (byElder) elder else caregiver
        assertEquals(PairingDecisionResult.Cancelled, service.cancel(actor, p.id, op))
        clock.set(p.expiresAtEpochMillis + 1)
        assertEquals(PairingDecisionResult.Cancelled, service.cancel(actor, p.id, op))
        assertEquals(PairingRequestStatus.CANCELLED, detail(p).status)
        assertEquals(if (byElder) PairingClosedReason.ELDER_CANCELLED else PairingClosedReason.CAREGIVER_CANCELLED, detail(p).closedReason)
        rejected(service.cancel(actor, p.id, key()), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
        rejected(service.accept(elder, p.id, key()), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
        assertFalse(access.isActive(caregiver.id, elder.id))
    }

    @ParameterizedTest @ValueSource(longs = [-1, 0, 1])
    fun `acceptance obeys exclusive original deadline`(offset: Long) {
        val p = pending(); clock.set(p.expiresAtEpochMillis + offset)
        val result = service.accept(elder, p.id, key())
        if (offset < 0) assertEquals(CareRelationshipStatus.ACTIVE, relation(result).status)
        else {
            rejected(result, PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
            assertEquals(PairingClosedReason.CODE_EXPIRED, detail(p).closedReason)
            assertFalse(access.isActive(caregiver.id, elder.id))
        }
    }

    @Test fun `accepted receipt survives expiry but never reactivates revoked access`() {
        val p = pending(); val op = key(); val original = relation(service.accept(elder, p.id, op))
        clock.set(p.expiresAtEpochMillis + 1)
        service.revoke(caregiver, original.id, key())
        assertEquals(original, relation(service.accept(elder, p.id, op)))
        assertEquals(CareRelationshipStatus.REVOKED, relation(service.relationship(elder, original.id)).status)
        assertFalse(access.isActive(caregiver.id, elder.id))
        rejected(service.accept(elder, p.id, key()), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
    }

    @Test fun `original request receipt remains pending after acceptance while detail carries relationship`() {
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java, issuer.issue(elder, key())).view
        val op = key()
        val original = assertInstanceOf(PairingRequestResult.Success::class.java,
            requester.request(caregiver, op, RequestPairingRequest(code.code), address)).view
        service.accept(elder, original.id, key())
        val replay = assertInstanceOf(PairingRequestResult.Success::class.java,
            requester.request(caregiver, op, RequestPairingRequest(code.code), address)).view
        assertEquals(original, replay); assertNull(replay.relationshipId)
        assertNotNull(detail(original).relationshipId)
    }

    @Test fun `target and installation remain bound on successful decision retries`() {
        val p = pending(); val op = key(); service.accept(elder, p.id, op)
        val second = pending()
        rejected(service.accept(elder, second.id, op), PairingDecisionRejection.IDEMPOTENCY_KEY_REUSED)
        rejected(service.accept(elder.copy(installationId = "new-install"), p.id, op), PairingDecisionRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(PairingRequestStatus.PENDING, detail(second).status)
    }

    @Test fun `same caregiver may serve multiple elders and revoking one isolates the other`() {
        val r = relation(service.accept(elder, pending().id, key()))
        val e2 = elder.copy(id = "elder-b", installationId = "install-b")
        val r2 = relation(service.accept(e2, pending(e2).id, key()))
        service.revoke(caregiver, r.id, key())
        assertFalse(access.isActive(caregiver.id, elder.id))
        assertTrue(access.isActive(caregiver.id, e2.id))
        assertEquals(CareRelationshipStatus.ACTIVE, relation(service.relationship(e2, r2.id)).status)
    }

    @Test fun `second caregiver conflicts then explicit same key retry succeeds only after revocation`() {
        val r = relation(service.accept(elder, pending().id, key()))
        val other = caregiver.copy(id = "caregiver-b", installationId = "install-b")
        val p2 = pending(c = other); val op = key()
        rejected(service.accept(elder, p2.id, op), PairingDecisionRejection.ELDER_ALREADY_PAIRED)
        assertEquals(PairingRequestStatus.PENDING, detail(p2).status)
        service.revoke(elder, r.id, key())
        rejected(service.accept(elder, r.id, op), PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
        val next = relation(service.accept(elder, p2.id, op))
        assertNotEquals(r.id, next.id)
        assertFalse(access.isActive(caregiver.id, elder.id)); assertTrue(access.isActive(other.id, elder.id))
    }

    @Test fun `rejected operation cannot retarget another accessible request`() {
        val r = relation(service.accept(elder, pending().id, key()))
        val p2 = pending(); val op = key()
        rejected(service.accept(elder, p2.id, op), PairingDecisionRejection.ELDER_ALREADY_PAIRED)
        val p3 = pending()
        service.revoke(elder, r.id, key())
        rejected(service.accept(elder, p3.id, op), PairingDecisionRejection.IDEMPOTENCY_KEY_REUSED)
        rejected(service.accept(elder, p2.id, op), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
    }

    @ParameterizedTest @ValueSource(booleans = [true, false])
    fun `revocation is participant authorized and never changes first revoked time`(byElder: Boolean) {
        val r = relation(service.accept(elder, pending().id, key())); val op = key()
        val actor = if (byElder) elder else caregiver
        val revoked = relation(service.revoke(actor, r.id, op))
        clock.set(clock.millis() + 1000)
        assertEquals(revoked, relation(service.revoke(actor, r.id, op)))
        assertEquals(revoked, relation(service.revoke(actor, r.id, key())))
        assertEquals(revoked, relation(service.relationship(caregiver, r.id)))
        assertEquals(revoked, relation(service.relationship(elder, r.id)))
        assertFalse(access.isActive(caregiver.id, elder.id))
    }

    @Test fun `replacement closes pending request but does not revoke existing relationship`() {
        val r = relation(service.accept(elder, pending().id, key()))
        val p = pending(); pending()
        rejected(service.accept(elder, p.id, key()), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
        assertEquals(PairingClosedReason.CODE_REPLACED, detail(p).closedReason)
        assertTrue(access.isActive(caregiver.id, elder.id))
        assertEquals(r, relation(service.relationship(elder, r.id)))
    }

    @Test fun `eight identical accept commands establish exactly one relationship`() {
        val p = pending(); val op = key()
        val results = parallel(8) { service.accept(elder, p.id, op) }.map(::relation)
        assertEquals(1, results.map { it.id }.distinct().size)
        assertEquals(results.first().id, detail(p).relationshipId)
    }

    @Test fun `eight distinct accept commands have one winner and seven closed results`() {
        val p = pending()
        val results = parallel(8) { service.accept(elder, p.id, key()) }
        assertEquals(1, results.count { it is PairingDecisionResult.Relationship })
        results.filterIsInstance<PairingDecisionResult.Rejected>().forEach { assertEquals(PairingDecisionRejection.PAIRING_REQUEST_CLOSED, it.reason) }
    }

    @Test fun `accept and cancel serialize to exactly one terminal decision`() {
        val p = pending()
        val results = parallel(2) { if (it == 0) service.accept(elder, p.id, key()) else service.cancel(caregiver, p.id, key()) }
        assertEquals(1, results.count { it is PairingDecisionResult.Rejected })
        val accepted = detail(p).status == PairingRequestStatus.ACCEPTED
        assertEquals(accepted, access.isActive(caregiver.id, elder.id))
        if (!accepted) assertEquals(PairingClosedReason.CAREGIVER_CANCELLED, detail(p).closedReason)
        else {
            val r = relation(results[0]); service.revoke(caregiver, r.id, key())
            assertFalse(access.isActive(caregiver.id, elder.id))
        }
    }

    @Test fun `accept and reject race cannot both succeed`() {
        val p = pending()
        val results = parallel(2) { if (it == 0) service.accept(elder, p.id, key()) else service.reject(elder, p.id, key()) }
        assertEquals(1, results.count { it is PairingDecisionResult.Rejected })
        assertEquals(detail(p).status == PairingRequestStatus.ACCEPTED, access.isActive(caregiver.id, elder.id))
    }

    @Test fun `revocation racing old accepted replay cannot restore access`() {
        val p = pending(); val op = key(); val r = relation(service.accept(elder, p.id, op))
        parallel(8) { if (it % 2 == 0) service.accept(elder, p.id, op) else service.revoke(caregiver, r.id, key()) }
        assertFalse(access.isActive(caregiver.id, elder.id))
        assertEquals(CareRelationshipStatus.REVOKED, relation(service.relationship(elder, r.id)).status)
    }

    @Test fun `receipt failure rolls back accepted relationship and terminal request`() {
        val p = pending(); val op = key()
        assertThrows(IllegalStateException::class.java) { brokenService().accept(elder, p.id, op) }
        assertEquals(p, detail(p)); assertFalse(access.isActive(caregiver.id, elder.id))
        assertNull(store.decisionTransaction { it.decisionOperation(PairingDecisionKey(elder.id, PairingDecision.ACCEPT, op)) })
        relation(service.accept(elder, p.id, op))
    }

    @Test fun `receipt failure rolls back reject and cancel`() {
        val p = pending()
        assertThrows(IllegalStateException::class.java) { brokenService().reject(elder, p.id, key()) }
        assertEquals(p, detail(p))
        assertThrows(IllegalStateException::class.java) { brokenService().cancel(caregiver, p.id, key()) }
        assertEquals(p, detail(p))
    }

    @Test fun `receipt failure rolls back revoked status and access`() {
        val r = relation(service.accept(elder, pending().id, key())); val op = key()
        assertThrows(IllegalStateException::class.java) { brokenService().revoke(elder, r.id, op) }
        assertTrue(access.isActive(caregiver.id, elder.id))
        assertEquals(r, relation(service.relationship(elder, r.id)))
        assertEquals(CareRelationshipStatus.REVOKED, relation(service.revoke(elder, r.id, op)).status)
    }

    @Test fun `lock timeout is retryable and leaves request unchanged`() {
        val bounded = InMemoryPairingCodeStore(0)
        val local = PairingDecisionService(bounded, clock)
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(bounded, clock) { "00123456" }.issue(elder, key())).view
        val requests = PairingRequestService(bounded, clock)
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java,
            requests.request(caregiver, key(), RequestPairingRequest(code.code), address)).view
        val operation = key()
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val held = pool.submit { bounded.transaction { locked.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            assertEquals(1L, rejected(local.accept(elder, pending.id, operation), PairingDecisionRejection.OPERATION_IN_PROGRESS).retryAfterSeconds)
            assertThrows(CodeStoreBusy::class.java) { StoredCareRelationshipAccess(bounded).isActive(caregiver.id, elder.id) }
        } finally { release.countDown(); held.get(5, TimeUnit.SECONDS); pool.shutdownNow() }
        assertEquals(pending, assertInstanceOf(PairingRequestResult.Success::class.java, requests.detail(elder, pending.id)).view)
        relation(local.accept(elder, pending.id, operation))
    }

    @Test fun `clock after transaction entry prevents accepting while waiting across expiry`() {
        val p = pending(); clock.set(p.expiresAtEpochMillis - 1)
        val delayed = object : PairingDecisionStore by store {
            override fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T = store.decisionTransaction {
                clock.set(p.expiresAtEpochMillis); work(it)
            }
        }
        rejected(PairingDecisionService(delayed, clock).accept(elder, p.id, key()), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
        assertFalse(access.isActive(caregiver.id, elder.id))
    }

    @Test fun `decisions remain queryable after thirty days within same process`() {
        val p = pending(); val op = key(); val r = relation(service.accept(elder, p.id, op))
        clock.set(clock.millis() + 31L * 24 * 60 * 60 * 1000)
        assertEquals(r, relation(service.accept(elder, p.id, op)))
        assertEquals(PairingRequestStatus.ACCEPTED, detail(p).status)
    }

    @Test fun `store refuses a second active relationship even if service is bypassed`() {
        val r = relation(service.accept(elder, pending().id, key()))
        assertThrows(IllegalStateException::class.java) { store.decisionTransaction { it.insertRelationship(r.copy(id = "duplicate")) } }
        assertEquals(r, relation(service.relationship(elder, r.id)))
    }

    @Test fun `diagnostics do not expose names installations or relationship IDs`() {
        val op = key(); val result = service.accept(elder, pending().id, op)
        val stored = store.decisionTransaction { it.decisionOperation(PairingDecisionKey(elder.id, PairingDecision.ACCEPT, op)) }
        for (text in listOf(result.toString(), stored.toString())) {
            assertFalse(text.contains(elder.displayName)); assertFalse(text.contains(caregiver.displayName))
            assertFalse(text.contains(elder.installationId)); assertFalse(text.contains(relation(result).id))
        }
    }

    @ParameterizedTest @ValueSource(booleans = [true, false])
    fun `reject and cancel at expiry preserve expired reason`(cancel: Boolean) {
        val p = pending(); clock.set(p.expiresAtEpochMillis)
        val result = if (cancel) service.cancel(caregiver, p.id, key()) else service.reject(elder, p.id, key())
        rejected(result, PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
        assertEquals(PairingClosedReason.CODE_EXPIRED, detail(p).closedReason)
    }

    @Test fun `new cancel after acceptance cannot silently revoke a relationship`() {
        val p = pending(); service.accept(elder, p.id, key())
        rejected(service.cancel(caregiver, p.id, key()), PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
        assertTrue(access.isActive(caregiver.id, elder.id))
    }

    @Test fun `cancellation plus replayed issuance receipt never reopens consumed code`() {
        val issuance = key()
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java, issuer.issue(elder, issuance)).view
        val request = assertInstanceOf(PairingRequestResult.Success::class.java,
            requester.request(caregiver, key(), RequestPairingRequest(code.code), address)).view
        service.cancel(elder, request.id, key())
        assertEquals(code, assertInstanceOf(CodeIssuanceResult.Issued::class.java, issuer.issue(elder, issuance)).view)
        val refused = assertInstanceOf(PairingRequestResult.Rejected::class.java,
            requester.request(caregiver, key(), RequestPairingRequest(code.code), address))
        assertEquals(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE, refused.reason)
        assertFalse(access.isActive(caregiver.id, elder.id))
    }

    private fun brokenService(): PairingDecisionService {
        val failing = object : PairingDecisionStore by store {
            override fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T = store.decisionTransaction { tx ->
                work(object : PairingDecisionTransaction by tx {
                    override fun putDecisionOperation(key: PairingDecisionKey, value: PairingDecisionOperation) {
                        tx.putDecisionOperation(key, value); error("Injected receipt failure")
                    }
                })
            }
        }
        return PairingDecisionService(failing, clock)
    }

    private fun <T> parallel(count: Int, work: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count); val ready = CountDownLatch(count); val start = CountDownLatch(1)
        try {
            val futures = (0 until count).map { i -> pool.submit<T> { ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); work(i) } }
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
        fun set(value: Long) { now.set(value) }
    }
}
