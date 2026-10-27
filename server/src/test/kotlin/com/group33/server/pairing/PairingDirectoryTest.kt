package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
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

internal class PairingDirectoryTest {
    private val e = AnonymousUser("elder", "ei", "Fictional Elder", UserRole.ELDER)
    private val c = AnonymousUser("carer", "ci", "Fictional Caregiver", UserRole.CAREGIVER)
    private val store = InMemoryPairingCodeStore()
    private val clock = TestClock()
    private val counter = AtomicInteger()
    private val issuer = PairingCodeIssuanceService(store, clock) { counter.incrementAndGet().toString().padStart(8, '0') }
    private val requests = PairingRequestService(store, clock)
    private val decisions = PairingDecisionService(store, clock)
    private val directory = PairingDirectoryService(store, clock)
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
    private fun key() = UUID.randomUUID()
    private fun issue(actor: AnonymousUser = e, op: UUID = key()) = assertInstanceOf(CodeIssuanceResult.Issued::class.java, issuer.issue(actor, op)).view
    private fun consume(code: String, actor: AnonymousUser = c) = requests.request(actor, key(), RequestPairingRequest(code), address)
    private fun pending(actor: AnonymousUser = e) = assertInstanceOf(PairingRequestResult.Success::class.java, consume(issue(actor).code)).view

    @Test fun `empty and participant scoped lists contain terminal states and no outsiders`() {
        assertTrue(directory.requests(e).items.isEmpty()); assertTrue(directory.relationships(c).items.isEmpty())
        val first = pending(); decisions.reject(e, first.id, key())
        val second = pending(); val r = assertInstanceOf(PairingDecisionResult.Relationship::class.java, decisions.accept(e, second.id, key())).view
        decisions.revoke(c, r.id, key())
        val other = e.copy(id = "other", installationId = "other-i"); val third = pending(other)
        val r2 = assertInstanceOf(PairingDecisionResult.Relationship::class.java, decisions.accept(other, third.id, key())).view
        assertEquals(setOf(first.id, second.id), directory.requests(e).items.map { it.id }.toSet())
        assertEquals(setOf(first.id, second.id, third.id), directory.requests(c).items.map { it.id }.toSet())
        assertEquals(setOf(r.id, r2.id), directory.relationships(c).items.map { it.id }.toSet())
        assertEquals(CareRelationshipStatus.REVOKED, directory.relationships(e).items.single().status)
        assertTrue(directory.requests(e.copy(id = "outsider")).items.isEmpty())
        assertTrue(directory.relationships(e.copy(role = UserRole.CAREGIVER)).items.isEmpty())
    }
    @Test fun `status filter is applied after expiry at original deadline and keeps terminal metadata`() {
        val p = pending(); clock.time.set(p.expiresAtEpochMillis)
        assertTrue(directory.requests(e, PairingRequestStatus.PENDING).items.isEmpty())
        assertEquals(PairingClosedReason.CODE_EXPIRED, directory.requests(c, PairingRequestStatus.EXPIRED).items.single().closedReason)
        clock.time.addAndGet(31L * 24 * 60 * 60 * 1000)
        assertEquals(p.id, directory.requests(e).items.single().id)
    }
    @Test fun `unconsumed deletion and old issuance replay never restore code`() {
        val issuance = key(); val code = issue(op = issuance); val op = key()
        assertEquals(CodeDeletionResult.Deleted, directory.deleteCode(e, code.id, op))
        assertEquals(code, issue(op = issuance))
        assertEquals(CodeDeletionResult.Deleted, directory.deleteCode(e, code.id, op))
        assertEquals(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE, assertInstanceOf(PairingRequestResult.Rejected::class.java, consume(code.code)).reason)
    }
    @ParameterizedTest @ValueSource(longs = [-1, 0, 1])
    fun `code deletion closes pending with expiry winning at deadline`(offset: Long) {
        val code = issue(); val p = assertInstanceOf(PairingRequestResult.Success::class.java, consume(code.code)).view
        clock.time.set(p.expiresAtEpochMillis + offset)
        directory.deleteCode(e, code.id, key())
        val closed = directory.requests(c).items.single()
        assertEquals(if (offset < 0) PairingClosedReason.ELDER_CANCELLED else PairingClosedReason.CODE_EXPIRED, closed.closedReason)
        assertEquals(null, store.transaction { it.activeCode(e.id) })
    }
    @Test fun `deleting consumed code does not revoke accepted relationship`() {
        val code = issue(); val p = assertInstanceOf(PairingRequestResult.Success::class.java, consume(code.code)).view
        decisions.accept(e, p.id, key()); directory.deleteCode(e, code.id, key())
        assertTrue(StoredCareRelationshipAccess(store).isActive(c.id, e.id))
        assertEquals(PairingRequestStatus.ACCEPTED, directory.requests(e).items.single().status)
    }
    @Test fun `foreign target role and changed binding cannot delete a current code`() {
        val code = issue(); val op = key(); val outsider = e.copy(id = "other", installationId = "other-i")
        assertEquals(CodeDeletionResult.Rejected(CodeDeletionRejection.RESOURCE_NOT_ACCESSIBLE), directory.deleteCode(outsider, code.id, op))
        assertEquals(CodeDeletionResult.Rejected(CodeDeletionRejection.ROLE_NOT_ALLOWED), directory.deleteCode(c, code.id, op))
        directory.deleteCode(e, code.id, op); val next = issue()
        assertEquals(CodeDeletionResult.Rejected(CodeDeletionRejection.IDEMPOTENCY_KEY_REUSED), directory.deleteCode(e, next.id, op))
        assertEquals(CodeDeletionResult.Rejected(CodeDeletionRejection.IDEMPOTENCY_KEY_REUSED), directory.deleteCode(e.copy(installationId = "changed"), code.id, op))
        assertEquals(CodeDeletionResult.Deleted, directory.deleteCode(e, code.id, op))
        assertEquals(next.id, store.transaction { it.activeCode(e.id)?.id })
    }
    @Test fun `receipt failure rolls back deletion and pending cancellation before same key retry`() {
        val code = issue(); val p = assertInstanceOf(PairingRequestResult.Success::class.java, consume(code.code)).view; val op = key()
        val broken = object : PairingDirectoryStore by store {
            override fun <T> directoryTransaction(work: (PairingDirectoryTransaction) -> T): T = store.directoryTransaction { tx ->
                work(object : PairingDirectoryTransaction by tx {
                    override fun putCodeDeletion(key: CodeDeletionKey, value: CodeDeletionReceipt) { tx.putCodeDeletion(key, value); error("Injected failure") }
                })
            }
        }
        assertThrows(IllegalStateException::class.java) { PairingDirectoryService(broken, clock).deleteCode(e, code.id, op) }
        assertEquals(code.id, store.transaction { it.activeCode(e.id)?.id }); assertEquals(p, directory.requests(e).items.single())
        assertNull(store.directoryTransaction { it.codeDeletion(CodeDeletionKey(e.id, op)) })
        assertEquals(CodeDeletionResult.Deleted, directory.deleteCode(e, code.id, op))
    }
    @Test fun `delete versus consumption leaves no open pending opportunity`() {
        val code = issue(); val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val consumed = pool.submit<PairingRequestResult> { start.await(); consume(code.code) }
            val deleted = pool.submit<CodeDeletionResult> { start.await(); directory.deleteCode(e, code.id, key()) }
            start.countDown(); assertEquals(CodeDeletionResult.Deleted, deleted.get(5, TimeUnit.SECONDS))
            val result = consumed.get(5, TimeUnit.SECONDS)
            if (result is PairingRequestResult.Success) assertEquals(PairingClosedReason.ELDER_CANCELLED, directory.requests(c).items.single().closedReason)
            else assertEquals(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE, (result as PairingRequestResult.Rejected).reason)
            assertTrue(directory.requests(e, PairingRequestStatus.PENDING).items.isEmpty())
        } finally { start.countDown(); pool.shutdownNow() }
    }
    @Test fun `eight duplicate deletes produce one receipt and preserve replacement on replay`() {
        val code = issue(); val op = key(); val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        try {
            val results = (1..8).map { pool.submit<CodeDeletionResult> { start.await(); directory.deleteCode(e, code.id, op) } }
            start.countDown(); results.forEach { assertEquals(CodeDeletionResult.Deleted, it.get(5, TimeUnit.SECONDS)) }
            val replacement = issue(); directory.deleteCode(e, code.id, op)
            assertEquals(replacement.id, store.transaction { it.activeCode(e.id)?.id })
        } finally { start.countDown(); pool.shutdownNow() }
    }
    private class TestClock : Clock() {
        val time = AtomicLong(1_800_000_000_000)
        override fun instant() = Instant.ofEpochMilli(time.get())
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }
}
