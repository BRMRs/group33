package com.group33.medicationreminder.core.reminder

import java.time.Clock
import java.time.Instant

/** Facts for ONE scoped round, supplied only after adapter authentication/round matching.
 * Timestamps describe actual recorded events, never future scheduled events.
 * Null sentAt means not executed, not a failed device response. No wire fields are defined here.
 */
data class ProbeAttempt(val sentAt: Instant? = null, val responseAt: Instant? = null) {
    init {
        // Authentication/round matching establishes causality, not wall-clock ordering.
        require(responseAt == null || sentAt != null)
    }
}

data class ProbeRoundFacts(
    val window: ReminderWindow,
    val first: ProbeAttempt = ProbeAttempt(),
    val second: ProbeAttempt = ProbeAttempt(),
    val alertCreatedAt: Instant? = null,
) {
    init {
        require(first.sentAt == null || first.sentAt >= window.firstProbeAt)
        require(second.sentAt == null || second.sentAt >= window.secondProbeAt)
    }
}

enum class ProbeDecision {
    WAITING, CHECK_INCOMPLETE, RESPONDED, CREATE_ALERT, ALERT_ALREADY_CREATED, CONNECTION_RESTORED,
}

/** Local projection/reference logic. S2 still owns durable, atomic server-side alert arbitration. */
class ProbeRoundPolicy(private val clock: Clock) {
    fun evaluate(round: ProbeRoundFacts): ProbeDecision {
        val now = clock.instant()
        // A wall-clock correction cannot undo a verified response or an existing alert.
        val responded = round.first.responseAt != null || round.second.responseAt != null
        val alerted = round.alertCreatedAt != null
        if (alerted) return if (responded) ProbeDecision.CONNECTION_RESTORED else ProbeDecision.ALERT_ALREADY_CREATED
        if (responded) return ProbeDecision.RESPONDED
        if (now < round.window.secondProbeAt) return ProbeDecision.WAITING
        val firstSent = round.first.sentAt ?: return ProbeDecision.CHECK_INCOMPLETE
        val secondSent = round.second.sentAt ?: return ProbeDecision.CHECK_INCOMPLETE
        val waitsEnd = maxOf(firstSent, secondSent).plus(InitialTiming.probeWait)
        return if (now >= waitsEnd) ProbeDecision.CREATE_ALERT else ProbeDecision.WAITING
    }
}
