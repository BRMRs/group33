package com.group33.medicationreminder.core.reminder

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

/** Real policy code with controlled time; no fake Room, HTTP or push acceptance claims. */
class S1PolicyTest {
    private fun at(value: String) = Instant.parse("2026-10-22T${value}Z")
    private fun clock(value: String) = Clock.fixed(at(value), ZoneOffset.UTC)
    // 10:00 UTC = 18:00 Asia/Shanghai. Expected values are worked examples of the approved rules.
    private val window = ReminderWindow(at("10:00:00"))
    private fun reminder(now: String, ended: Boolean = false) = ReminderTimingPolicy(clock(now)).evaluate(window, ended)
    private val sentFirst = ProbeAttempt(at("09:55:00"))
    private val sentSecond = ProbeAttempt(at("10:05:00"))
    private fun round(first: ProbeAttempt = sentFirst, second: ProbeAttempt = sentSecond) =
        ProbeRoundFacts(window, first, second)
    private fun probe(now: String, facts: ProbeRoundFacts) = ProbeRoundPolicy(clock(now)).evaluate(facts)
    private val alert = AlertProgress("opaque-alert-1", at("10:06:00"))
    private fun alertStep(now: String, progress: AlertProgress = alert) = AlertTimingPolicy(clock(now)).next(progress)

    @Test fun `V201 pre-Due cannot respond and exact planned time opens`() {
        assertEquals(ReminderPhase.NOT_DUE, reminder("09:59:59.999").phase)
        assertFalse(reminder("09:59:59.999").canRespond)
        assertTrue(reminder("10:00:00").canRespond)
    }

    @Test fun `V201 thirty minutes opens follow-up timing without withdrawing Elder response`() {
        assertFalse(reminder("10:29:59.999").nonresponseThresholdReached)
        for (time in listOf("10:30:00", "10:31:00", "11:59:00")) {
            val result = reminder(time)
            assertTrue(result.nonresponseThresholdReached)
            assertTrue(result.canRespond)
            assertEquals(ReminderPhase.OPEN, result.phase)
        }
    }

    @Test fun `V201 exact two-hour cutoff wins all later responses`() {
        assertTrue(reminder("11:59:59.999").canRespond)
        for (time in listOf("12:00:00", "12:00:00.001", "13:00:00")) {
            assertEquals(ReminderPhase.EXPIRED, reminder(time).phase)
            assertFalse(reminder(time).canRespond)
            assertNull(reminder(time).nextSnoozeAt)
        }
    }

    @Test fun `V201 snooze is ten minutes but never equal to or beyond original deadline`() {
        assertEquals(at("11:59:59.999"), reminder("11:49:59.999").nextSnoozeAt)
        assertNull(reminder("11:50:00").nextSnoozeAt)
        assertTrue(reminder("11:50:00").canRespond)
        assertNull(reminder("11:50:00.001").nextSnoozeAt)
        assertEquals(at("10:41:00"), reminder("10:31:00").nextSnoozeAt)
        assertEquals(at("12:00:00"), window.deadline)
        assertEquals(at("10:30:00"), window.followUpAt)
    }

    @Test fun `V201 Help Taken or acknowledged takeover stops action and snooze`() {
        val result = reminder("10:10:00", ended = true)
        assertFalse(result.canRespond)
        assertNull(result.nextSnoozeAt)
    }

    @Test fun `V201 reconstructed policy after recovery does not re-open expired reminder`() {
        val reconstructed = ReminderTimingPolicy(clock("12:05:00"))
        assertFalse(reconstructed.evaluate(ReminderWindow(at("10:00:00"))).canRespond)
    }

    @Test fun `V201 decision samples its clock once so a cutoff cannot split the result`() {
        val clock = object : Clock() {
            var reads = 0
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = if (reads++ == 0) at("11:59:59.999") else at("12:00:00")
        }
        val result = ReminderTimingPolicy(clock).evaluate(window)
        assertTrue(result.canRespond)
        assertEquals(ReminderPhase.OPEN, result.phase)
        assertEquals(1, clock.reads)
    }

    @Test fun `V204 probes stay anchored to original reminder not ten minutes after first actual send`() {
        assertEquals(at("09:55:00"), window.firstProbeAt)
        assertEquals(at("10:05:00"), window.secondProbeAt)
        assertEquals(ProbeDecision.WAITING, probe("10:05:59.999", round()))
        assertEquals(ProbeDecision.CREATE_ALERT, probe("10:06:00", round()))
    }

    @Test fun `V204 either valid attempt prevents two-failure alert`() {
        val firstOk = sentFirst.copy(responseAt = at("09:55:30"))
        val secondOk = sentSecond.copy(responseAt = at("10:05:30"))
        for (facts in listOf(round(firstOk, secondOk), round(firstOk), round(second = secondOk))) {
            assertEquals(ProbeDecision.RESPONDED, probe("10:07:00", facts))
        }
    }

    @Test fun `V204 unexecuted probes are incomplete not device failure`() {
        assertEquals(ProbeDecision.WAITING, probe("09:56:00", ProbeRoundFacts(window)))
        for (facts in listOf(round(first = ProbeAttempt()), round(second = ProbeAttempt()), ProbeRoundFacts(window))) {
            assertEquals(ProbeDecision.CHECK_INCOMPLETE, probe("10:07:00", facts))
        }
    }

    @Test fun `V204 late dispatch gets its response wait without hiding the scheduled time`() {
        val facts = round(second = ProbeAttempt(at("10:05:30")))
        assertEquals(ProbeDecision.WAITING, probe("10:06:29.999", facts))
        assertEquals(ProbeDecision.CREATE_ALERT, probe("10:06:30", facts))
        assertEquals(at("10:05:00"), facts.window.secondProbeAt)
    }

    @Test fun `V204 late matched response fact before alert decision suppresses it including boundary`() {
        val facts = round(first = sentFirst.copy(responseAt = at("10:06:00")))
        assertEquals(ProbeDecision.RESPONDED, probe("10:06:00", facts))
    }

    @Test fun `V204 previously created alert fact prevents another alert decision`() {
        val restored = round().copy(alertCreatedAt = at("10:06:00"))
        assertEquals(ProbeDecision.ALERT_ALREADY_CREATED, probe("10:08:00", restored))
        assertEquals(ProbeDecision.CONNECTION_RESTORED,
            probe("10:08:00", restored.copy(second = sentSecond.copy(responseAt = at("10:07:00")))))
    }

    @Test fun `V204 clock rollback does not erase a previously received verified response`() {
        val receivedBeforeClockCorrection = round(first = sentFirst.copy(responseAt = at("10:09:00")))
        assertEquals(ProbeDecision.RESPONDED, probe("10:07:00", receivedBeforeClockCorrection))
    }

    @Test fun `V204 clock rollback does not create a second already persisted alert`() {
        val createdBeforeClockCorrection = round().copy(alertCreatedAt = at("10:10:00"))
        assertEquals(ProbeDecision.ALERT_ALREADY_CREATED, probe("10:07:00", createdBeforeClockCorrection))
        val received = createdBeforeClockCorrection.copy(second = sentSecond.copy(responseAt = at("10:11:00")))
        assertEquals(ProbeDecision.CONNECTION_RESTORED, probe("10:07:00", received))
    }

    @Test fun `V204 a verified response after a clock rollback remains a valid occurrence fact`() {
        // The second attempt was sent at 10:05, then the wall clock moved back before reception.
        val repliedAfterClockCorrection = round(second = ProbeAttempt(at("10:05:00"), at("10:04:10")))
        assertEquals(ProbeDecision.RESPONDED, probe("10:04:15", repliedAfterClockCorrection))
    }

    @Test fun `V204 malformed or premature attempt facts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ProbeAttempt(responseAt = at("10:00:00")) }
        assertThrows(IllegalArgumentException::class.java) { round(second = ProbeAttempt(at("10:04:59"))) }
    }

    @Test fun `V205 retries keep one alert identity at zero fifteen and sixty seconds`() {
        assertEquals(AlertStep.Transmit("opaque-alert-1", 1, at("10:06:00")), alertStep("10:06:00"))
        val sentOnce = alert.copy(transmissionCount = 1, lastTransmissionAt = at("10:06:00"))
        assertEquals(AlertStep.Wait(at("10:06:15")), alertStep("10:06:14.999", sentOnce))
        assertEquals(AlertStep.Transmit("opaque-alert-1", 2, at("10:06:15")), alertStep("10:06:15", sentOnce))
        val sentTwice = alert.copy(transmissionCount = 2, lastTransmissionAt = at("10:06:15"))
        assertEquals(AlertStep.Wait(at("10:07:00")), alertStep("10:06:59.999", sentTwice))
        assertEquals(AlertStep.Transmit("opaque-alert-1", 3, at("10:07:00")), alertStep("10:07:00", sentTwice))
        assertEquals(AlertStep.AwaitingReceipt, alertStep("11:00:00", alert.copy(transmissionCount = 3, lastTransmissionAt = at("10:07:00"))))
    }

    @Test fun `V205 delivered alerts stop transport retries and remind two and five minutes after receipt`() {
        val received = alert.copy(transmissionCount = 1, lastTransmissionAt = at("10:06:00"), receivedAt = at("10:06:10"))
        assertEquals(AlertStep.Wait(at("10:08:10")), alertStep("10:08:09.999", received))
        assertEquals(AlertStep.Remind("opaque-alert-1", 1, at("10:08:10")), alertStep("10:08:10", received))
        assertEquals(AlertStep.Wait(at("10:11:10")), alertStep("10:11:09", received.copy(reminderCount = 1, lastReminderAt = at("10:08:10"))))
        assertEquals(AlertStep.Remind("opaque-alert-1", 2, at("10:11:10")),
            alertStep("10:11:10", received.copy(reminderCount = 1, lastReminderAt = at("10:08:10"))))
        assertEquals(AlertStep.RemindersExhausted, alertStep("11:00:00", received.copy(reminderCount = 2, lastReminderAt = at("10:11:10"))))
    }

    @Test fun `V205 handled and explicitly blocked alerts do not trigger more notifications`() {
        assertEquals(AlertStep.Handled, alertStep("10:06:00", alert.copy(handled = true)))
        assertEquals(AlertStep.Unavailable, alertStep("10:06:00", alert.copy(deliveryBlocked = true)))
    }

    @Test fun `V205 reconstructed progress retains the same retry request until adapter claims it`() {
        val restored = AlertProgress("opaque-alert-1", at("10:06:00"), transmissionCount = 1, lastTransmissionAt = at("10:06:00"))
        assertEquals(AlertStep.Transmit("opaque-alert-1", 2, at("10:06:15")), alertStep("10:10:00", restored))
        assertEquals(AlertStep.Transmit("opaque-alert-1", 2, at("10:06:15")), alertStep("10:10:00", restored))
        // Repeating a decision doesn't claim dispatch or consume work. A durable adapter must do that.
    }

    @Test fun `V205 resuming late work does not burst remaining retries or close unresolved follow-up`() {
        val sentLate = alert.copy(transmissionCount = 1, lastTransmissionAt = at("10:10:00"))
        assertEquals(AlertStep.Wait(at("10:10:15")), alertStep("10:10:00", sentLate))
        val retriedLate = sentLate.copy(transmissionCount = 2, lastTransmissionAt = at("10:12:00"))
        assertEquals(AlertStep.Wait(at("10:12:45")), alertStep("10:12:00", retriedLate))
        val exhausted = alert.copy(receivedAt = at("10:06:10"), reminderCount = 2, lastReminderAt = at("10:11:10"))
        assertEquals(AlertStep.RemindersExhausted, alertStep("11:00:00", exhausted))
        assertFalse(exhausted.handled)
    }

    @Test fun `V205 delayed first reminder does not burst a second stale reminder after recovery`() {
        val firstRemindedLate = alert.copy(receivedAt = at("10:06:10"), reminderCount = 1,
            lastReminderAt = at("10:20:00"))
        assertEquals(AlertStep.Wait(at("10:23:00")), alertStep("10:20:00", firstRemindedLate))
        assertEquals(AlertStep.Wait(at("10:23:00")), alertStep("10:22:59.999", firstRemindedLate))
        assertEquals(AlertStep.Remind("opaque-alert-1", 2, at("10:23:00")),
            alertStep("10:23:00", firstRemindedLate))
    }

    @Test fun `V205 no receipt remains unknown even beyond a delivery limit`() {
        assertEquals(DeliveryAssessment.UNCONFIRMED,
            AlertTimingPolicy(clock("11:00:00")).assessDelivery(at("10:06:00"), null))
    }

    @Test fun `V205 visibility metrics use actual device visibility with inclusive limits`() {
        val policy = AlertTimingPolicy(clock("11:00:00"))
        assertEquals(DeliveryAssessment.ON_TARGET, policy.assessDelivery(at("10:06:00"), at("10:06:15")))
        assertEquals(DeliveryAssessment.WITHIN_LIMIT, policy.assessDelivery(at("10:06:00"), at("10:06:15.001")))
        assertEquals(DeliveryAssessment.WITHIN_LIMIT, policy.assessDelivery(at("10:06:00"), at("10:07:00")))
        assertEquals(DeliveryAssessment.OVER_LIMIT, policy.assessDelivery(at("10:06:00"), at("10:07:00.001")))
        assertThrows(IllegalArgumentException::class.java) { policy.assessDelivery(at("10:06:00"), at("10:05:59")) }
    }

    @Test fun `V205 invalid durable progress cannot skip to a fabricated successful stage`() {
        assertThrows(IllegalArgumentException::class.java) { alert.copy(transmissionCount = 4) }
        assertThrows(IllegalArgumentException::class.java) { alert.copy(transmissionCount = 1) }
        assertThrows(IllegalArgumentException::class.java) { alert.copy(reminderCount = 1) }
        assertThrows(IllegalArgumentException::class.java) {
            alert.copy(receivedAt = at("10:06:10"), reminderCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            alert.copy(receivedAt = at("10:06:10"), reminderCount = 1, lastReminderAt = at("10:06:09"))
        }
        assertThrows(IllegalArgumentException::class.java) { alert.copy(receivedAt = at("10:05:59")) }
    }

    @Test fun `V203 current Caregiver entry wins over late Elder Taken for all three outcomes`() {
        for (outcome in FollowUpOutcome.entries) {
            val result = FollowUpPolicy.conclude(outcome, hasTimelyElderTaken = true)
            assertEquals(outcome, result.outcome)
            assertEquals(ConclusionSource.CAREGIVER, result.source)
        }
    }

    @Test fun `V203 Caregiver unknown remains unresolved and absence of confirmation is not Not taken`() {
        assertTrue(FollowUpPolicy.conclude(FollowUpOutcome.UNABLE_TO_CONFIRM, true).requiresVerification)
        val unknown = FollowUpPolicy.conclude(null, false)
        assertEquals(FollowUpOutcome.UNABLE_TO_CONFIRM, unknown.outcome)
        assertEquals(ConclusionSource.NONE, unknown.source)
        assertTrue(unknown.requiresVerification)
    }

    @Test fun `V203 timely Elder Taken still counts without an accepted Caregiver entry`() {
        val result = FollowUpPolicy.conclude(null, true)
        assertEquals(FollowUpOutcome.TAKEN, result.outcome)
        assertEquals(ConclusionSource.ELDER, result.source)
        assertFalse(result.requiresVerification)
    }
}
