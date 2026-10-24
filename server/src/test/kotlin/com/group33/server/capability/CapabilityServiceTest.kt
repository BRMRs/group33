package com.group33.server.capability

import com.group33.server.auth.AnonymousUser
import com.group33.server.auth.CareRelationshipAccess
import com.group33.shared.contract.*
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

class CapabilityServiceTest {
    private val elder = AnonymousUser("fictional-elder", "fictional-install", "Fictional Elder", UserRole.ELDER)
    private val other = elder.copy(id = "other-elder", installationId = "other-install")
    private val caregiver = elder.copy(id = "caregiver", installationId = "care-install", role = UserRole.CAREGIVER)
    private var active = false
    private val accessChecks = AtomicInteger()
    private val access = CareRelationshipAccess { actor, target ->
        accessChecks.incrementAndGet()
        active && actor == caregiver.id && target == elder.id
    }
    private val clock = TestClock()
    private val store = InMemoryCapabilityStore()
    private val service = CapabilityService(store, access, clock)
    private fun report(sequence: Long = 1, id: UUID = UUID.randomUUID()) = ReminderCapabilityReport(
        id.toString(), sequence, 100, DeliveryCondition.AVAILABLE, true, true, ExactAlarmAccess.GRANTED, emptyList(),
    )
    private fun submit(value: ReminderCapabilityReport, actor: AnonymousUser = elder, target: String = actor.id,
        using: CapabilityService = service, key: UUID = UUID.fromString(value.reportId)) = using.report(actor, target, key, value)
    private fun accepted(value: CapabilityAcceptance) = assertInstanceOf(CapabilityAcceptance.Accepted::class.java, value).receipt
    private fun rejected(value: CapabilityAcceptance, reason: CapabilityRejection) {
        assertEquals(reason, assertInstanceOf(CapabilityAcceptance.Rejected::class.java, value).reason)
    }
    private fun current(actor: AnonymousUser = elder, target: AnonymousUser = elder) =
        assertInstanceOf(CapabilityRead.Current::class.java, service.lastKnown(actor, target)).view
    private fun owner(actor: AnonymousUser = elder) = CapabilityOwner(actor.id, actor.installationId)

    @Test fun `first report is stored with observed and server receipt times kept separate`() {
        val input = report().copy(reasons = listOf("fictional reason"))
        val receipt = accepted(submit(input))
        assertEquals(input.reportId, receipt.reportId)
        assertTrue(receipt.applied)
        assertEquals(clock.millis(), receipt.receivedAtEpochMillis)
        assertEquals(100, receipt.current.observedAtEpochMillis)
        assertEquals(input.reasons, current()!!.reasons)
        assertEquals(receipt.current, current())
    }

    @Test fun `replay returns original receipt and does not renew its timestamps`() {
        val input = report()
        val first = accepted(submit(input))
        clock.advance(20_000)
        assertEquals(first, accepted(submit(input)))
        assertEquals(first.current, current())
    }

    @Test fun `delayed lower sequence is acknowledged without replacing current`() {
        val newer = accepted(submit(report(2)))
        clock.advance(10)
        val delayed = accepted(submit(report(1).copy(deliveryCondition = DeliveryCondition.UNAVAILABLE)))
        assertFalse(delayed.applied)
        assertEquals(newer.current, delayed.current)
        assertEquals(newer.current, current())
        assertTrue(delayed.receivedAtEpochMillis > delayed.current.receivedAtEpochMillis)
    }

    @Test fun `sequence rather than observed or received wall time controls the current report`() {
        accepted(submit(report(1).copy(observedAtEpochMillis = 900_000)))
        clock.advance(-100)
        val higher = accepted(submit(report(2).copy(observedAtEpochMillis = 1)))
        assertTrue(higher.applied)
        assertEquals(2, current()!!.observationSequence)
        assertEquals(1, current()!!.observedAtEpochMillis)
    }

    @Test fun `historical replay never overwrites a newer current report`() {
        val old = report(1)
        val oldReceipt = accepted(submit(old))
        val latest = accepted(submit(report(2)))
        assertEquals(oldReceipt, accepted(submit(old)))
        assertEquals(latest.current, current())
    }

    @Test fun `accepted key cannot change any bound report fields`() {
        val input = report().copy(reasons = listOf(" a ", "b"))
        val first = accepted(submit(input))
        val changed = listOf(
            input.copy(reportId = UUID.randomUUID().toString()), input.copy(observationSequence = 3),
            input.copy(observedAtEpochMillis = 101), input.copy(deliveryCondition = DeliveryCondition.DEGRADED),
            input.copy(notificationPermissionGranted = false), input.copy(channelEnabled = false),
            input.copy(exactAlarmAccess = ExactAlarmAccess.DENIED), input.copy(reasons = listOf("a", "b")),
            input.copy(reasons = input.reasons.reversed()),
        )
        changed.forEach { rejected(submit(it, key = UUID.fromString(input.reportId)), CapabilityRejection.IDEMPOTENCY_KEY_REUSED) }
        assertEquals(first, accepted(submit(input)))
        assertEquals(first.current, current())
    }

    @Test fun `same sequence cannot acquire another report id or different payload`() {
        val first = accepted(submit(report(1)))
        rejected(submit(report(1).copy(channelEnabled = false)), CapabilityRejection.CAPABILITY_SEQUENCE_CONFLICT)
        assertEquals(first.current, current())
    }

    @Test fun `lower sequence payload evidence is retained after it has been acknowledged`() {
        val higher = accepted(submit(report(3)))
        accepted(submit(report(1)))
        rejected(submit(report(1).copy(reasons = listOf("different"))), CapabilityRejection.CAPABILITY_SEQUENCE_CONFLICT)
        assertEquals(higher.current, current())
    }

    @Test fun `invalid reports have no effect and their rejected bindings cannot be rewritten`() {
        val invalid = listOf(report(0), report(-1), report().copy(observedAtEpochMillis = -1),
            report().copy(reportId = "not-a-uuid"))
        invalid.forEach { value ->
            val key = runCatching { UUID.fromString(value.reportId) }.getOrElse { UUID.randomUUID() }
            rejected(submit(value, key = key), CapabilityRejection.INVALID_REPORT)
            assertNull(current())
            val corrected = value.copy(reportId = key.toString(), observationSequence = 1, observedAtEpochMillis = 0)
            rejected(submit(corrected, key = key), CapabilityRejection.IDEMPOTENCY_KEY_REUSED)
        }
        accepted(submit(report()))
    }

    @Test fun `report id must be the operation key`() {
        rejected(submit(report(), key = UUID.randomUUID()), CapabilityRejection.INVALID_REPORT)
        assertNull(current())
    }

    @Test fun `role and self target are checked before accepted receipt replay`() {
        val value = report()
        accepted(submit(value))
        rejected(submit(value, actor = elder.copy(role = UserRole.CAREGIVER)), CapabilityRejection.ROLE_NOT_ALLOWED)
        rejected(submit(value, actor = other, target = elder.id), CapabilityRejection.RESOURCE_NOT_ACCESSIBLE)
        rejected(submit(value, target = other.id), CapabilityRejection.RESOURCE_NOT_ACCESSIBLE)
    }

    @Test fun `elder and installation scopes cannot retrieve one another's accepted operation`() {
        val value = report()
        val first = accepted(submit(value))
        val unrelated = accepted(submit(value, actor = other))
        assertEquals(first.current, current())
        assertEquals(unrelated.current, current(other, other))
        rejected(submit(value, actor = elder.copy(installationId = "another-install")), CapabilityRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(CapabilityRead.NotAccessible, service.lastKnown(elder.copy(installationId = "another-install"), elder))
    }

    @Test fun `authorized absence differs from generic denial without leaking another elder`() {
        assertNull(current())
        assertEquals(CapabilityRead.NotAccessible, service.lastKnown(other, elder))
        accepted(submit(report()))
        assertEquals(CapabilityRead.NotAccessible, service.lastKnown(other, elder))
        assertEquals(CapabilityRead.NotAccessible, service.lastKnown(caregiver, elder))
    }

    @Test fun `caregiver reads recheck relationship and revocation on every call`() {
        accepted(submit(report()))
        active = true
        assertEquals(current(), current(caregiver, elder))
        active = false
        assertEquals(CapabilityRead.NotAccessible, service.lastKnown(caregiver, elder))
        assertEquals(2, accessChecks.get())
        assertEquals(CapabilityRead.NotAccessible, service.lastKnown(elder, caregiver))
        active = true
        rejected(submit(report(), actor = caregiver), CapabilityRejection.ROLE_NOT_ALLOWED)
    }

    @Test fun `input and output list mutation cannot alter a receipt or current report`() {
        val reasons = mutableListOf(" keep spaces ", "fictional")
        val input = report().copy(reasons = reasons)
        val receipt = accepted(submit(input))
        reasons.clear()
        (receipt.current.reasons as MutableList<String>).clear()
        val read = current()!!
        assertEquals(listOf(" keep spaces ", "fictional"), read.reasons)
        (read.reasons as MutableList<String>).clear()
        val replay = accepted(submit(input.copy(reasons = listOf(" keep spaces ", "fictional"))))
        assertEquals(listOf(" keep spaces ", "fictional"), replay.current.reasons)
        assertEquals(replay.current, current())
    }

    @Test fun `receipt failure rolls back current sequence and operation evidence together`() {
        val before = accepted(submit(report(1)))
        val input = report(2)
        val fault = object : CapabilityStore {
            override fun <T> transaction(work: (CapabilityTransaction) -> T) = store.transaction { tx ->
                work(object : CapabilityTransaction by tx {
                    override fun putOperation(key: CapabilityOperationKey, value: CapabilityOperation) {
                        if (value.receipt != null) error("fictional receipt write failure")
                        tx.putOperation(key, value)
                    }
                })
            }
        }
        assertThrows(IllegalStateException::class.java) { submit(input, using = CapabilityService(fault, access, clock)) }
        assertEquals(before.current, current())
        store.transaction {
            assertNull(it.observation(owner(), 2))
            assertNull(it.operation(CapabilityOperationKey(elder.id, UUID.fromString(input.reportId))))
        }
        assertTrue(accepted(submit(input)).applied)
    }

    @Test fun `concurrent retries of one operation return one accepted effect`() {
        val input = report()
        val results = parallel(8) { accepted(submit(input)) }
        assertEquals(1, results.distinct().size)
        assertEquals(1, clock.reads.get())
        assertEquals(results.first().current, current())
    }

    @Test fun `concurrent distinct sequences retain the highest observation`() {
        val results = parallel(8) { index -> accepted(submit(report(index + 1L))) }
        assertEquals(8, results.size)
        assertEquals(8, current()!!.observationSequence)
    }

    @Test fun `concurrent conflicting reports for one sequence have one winner`() {
        val results = parallel(2) { submit(report(1)) }
        assertEquals(1, results.count { it is CapabilityAcceptance.Accepted })
        assertEquals(1, results.count { it == CapabilityAcceptance.Rejected(CapabilityRejection.CAPABILITY_SEQUENCE_CONFLICT) })
    }

    @Test fun `bounded contention leaves completion unknown and allows the original operation later`() {
        val bounded = InMemoryCapabilityStore(0)
        val busy = CapabilityService(bounded, access, clock)
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = pool.submit { bounded.transaction { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val input = report()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(CapabilityAcceptance.InProgress, submit(input, using = busy))
            assertEquals(CapabilityRead.InProgress, busy.lastKnown(elder, elder))
            assertEquals(0, clock.reads.get())
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            assertTrue(accepted(submit(input, using = busy)).applied)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `service and stored operation diagnostics do not expose identities or report contents`() {
        val value = report().copy(reasons = listOf("private fictional text"))
        val result = submit(value)
        val operation = store.transaction { it.operation(CapabilityOperationKey(elder.id, UUID.fromString(value.reportId))) }!!
        val diagnostics = listOf(result.toString(), service.lastKnown(elder, elder).toString(), operation.toString(),
            operation.binding.toString(), operation.binding.owner.toString())
        for (text in diagnostics) for (secret in listOf(elder.id, elder.installationId, value.reportId, value.reasons.single())) {
            assertFalse(text.contains(secret))
        }
        assertEquals(ContractProtocol.CANONICALIZATION_VERSION, operation.binding.canonicalizationVersion)
    }

    private fun <T> parallel(count: Int, work: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until count).map { index -> pool.submit<T> { start.await(); work(index) } }
            start.countDown()
            return jobs.map { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
    private class TestClock : Clock() {
        private val time = AtomicLong(1_792_900_000_000)
        val reads = AtomicInteger()
        fun advance(delta: Long) { time.addAndGet(delta) }
        override fun millis(): Long { reads.incrementAndGet(); return time.get() }
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock { require(zone == ZoneOffset.UTC); return this }
    }
}
