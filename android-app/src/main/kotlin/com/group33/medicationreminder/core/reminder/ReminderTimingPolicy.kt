package com.group33.medicationreminder.core.reminder

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Initial parameters authorized by the lead; no timers, network or platform guarantees. */
object InitialTiming {
    val responseWindow: Duration = Duration.ofHours(2)
    val nonresponseThreshold: Duration = Duration.ofMinutes(30)
    val snooze: Duration = Duration.ofMinutes(10)
    val probeOffset: Duration = Duration.ofMinutes(5)
    val probeWait: Duration = Duration.ofSeconds(60)
    val deliveryTarget: Duration = Duration.ofSeconds(15)
    val deliveryLimit: Duration = Duration.ofSeconds(60)
}

data class ReminderWindow(val plannedAt: Instant) {
    val deadline: Instant = plannedAt.plus(InitialTiming.responseWindow)
    val followUpAt: Instant = plannedAt.plus(InitialTiming.nonresponseThreshold)
    val firstProbeAt: Instant = plannedAt.minus(InitialTiming.probeOffset)
    val secondProbeAt: Instant = plannedAt.plus(InitialTiming.probeOffset)
}

enum class ReminderPhase { NOT_DUE, OPEN, EXPIRED }

data class ReminderDecision(
    val phase: ReminderPhase,
    val canRespond: Boolean,
    val nextSnoozeAt: Instant?,
    // Reached is a time fact, not proof of non-intake or permission to create an alert.
    val nonresponseThresholdReached: Boolean,
)

/** E's adapter must use the same clock when validating a real action, not just drawing UI. */
class ReminderTimingPolicy(private val clock: Clock) {
    fun evaluate(window: ReminderWindow, promptingEnded: Boolean = false): ReminderDecision {
        val now = clock.instant()
        val phase = when {
            now < window.plannedAt -> ReminderPhase.NOT_DUE
            now >= window.deadline -> ReminderPhase.EXPIRED
            else -> ReminderPhase.OPEN
        }
        val canRespond = phase == ReminderPhase.OPEN && !promptingEnded
        val snooze = if (canRespond) now.plus(InitialTiming.snooze).takeIf { it < window.deadline } else null
        return ReminderDecision(phase, canRespond, snooze, now >= window.followUpAt)
    }
}
