package com.group33.medicationreminder.core.reminder

import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class FollowUpOutcome { TAKEN, NOT_TAKEN, UNABLE_TO_CONFIRM }
enum class ConclusionSource { CAREGIVER, ELDER, NONE }
data class FollowUpConclusion(val outcome: FollowUpOutcome, val source: ConclusionSource) {
    val requiresVerification: Boolean get() = outcome == FollowUpOutcome.UNABLE_TO_CONFIRM
}

/** Caller supplies the currently authorized, server-accepted Caregiver entry, not last arrival.
 * This projects a conclusion; storage must retain every original report/audit entry separately.
 */
object FollowUpPolicy {
    fun conclude(currentCaregiverOutcome: FollowUpOutcome?, hasTimelyElderTaken: Boolean): FollowUpConclusion = when {
        currentCaregiverOutcome != null -> FollowUpConclusion(currentCaregiverOutcome, ConclusionSource.CAREGIVER)
        hasTimelyElderTaken -> FollowUpConclusion(FollowUpOutcome.TAKEN, ConclusionSource.ELDER)
        else -> FollowUpConclusion(FollowUpOutcome.UNABLE_TO_CONFIRM, ConclusionSource.NONE)
    }
}

data class AlertProgress(
    val alertId: String,
    val createdAt: Instant,
    val transmissionCount: Int = 0,
    val lastTransmissionAt: Instant? = null,
    val receivedAt: Instant? = null,
    val reminderCount: Int = 0,
    val lastReminderAt: Instant? = null,
    // A resolved follow-up, not merely read/contacted; Unable to confirm is unresolved.
    val handled: Boolean = false,
    val deliveryBlocked: Boolean = false,
) {
    init {
        require(alertId.isNotBlank())
        require(transmissionCount in 0..3 && reminderCount in 0..2)
        require((transmissionCount == 0) == (lastTransmissionAt == null))
        require(lastTransmissionAt == null || lastTransmissionAt >= createdAt)
        require(receivedAt == null || receivedAt >= createdAt)
        require(reminderCount == 0 || receivedAt != null)
        require((reminderCount == 0) == (lastReminderAt == null))
        require(lastReminderAt == null || (receivedAt != null && lastReminderAt >= receivedAt))
    }
}

sealed interface AlertStep {
    data class Transmit(val alertId: String, val attempt: Int, val dueAt: Instant) : AlertStep
    data class Remind(val alertId: String, val number: Int, val dueAt: Instant) : AlertStep
    data class Wait(val until: Instant) : AlertStep
    data object AwaitingReceipt : AlertStep
    data object Unavailable : AlertStep
    data object Handled : AlertStep
    data object RemindersExhausted : AlertStep
}

enum class DeliveryAssessment { UNCONFIRMED, ON_TARGET, WITHIN_LIMIT, OVER_LIMIT }

/** Stateless planner over durable progress. The adapter must atomically claim/persist work and
 * scope it before dispatch. Polling this planner is NOT an exactly-once delivery mechanism.
 */
class AlertTimingPolicy(private val clock: Clock) {
    private val retryGaps = listOf(Duration.ofSeconds(15), Duration.ofSeconds(45))
    private val reminderOffsets = listOf(Duration.ofMinutes(2), Duration.ofMinutes(5))

    fun next(progress: AlertProgress): AlertStep {
        if (progress.handled) return AlertStep.Handled
        if (progress.deliveryBlocked) return AlertStep.Unavailable
        val receipt = progress.receivedAt
        if (receipt != null) {
            if (progress.reminderCount == reminderOffsets.size) return AlertStep.RemindersExhausted
            val nominalDue = receipt.plus(reminderOffsets[progress.reminderCount])
            // Recovery keeps the normal 2m/5m schedule but never emits both reminders back-to-back.
            val due = progress.lastReminderAt?.let {
                maxOf(nominalDue, it.plus(reminderOffsets[1].minus(reminderOffsets[0])))
            } ?: nominalDue
            return if (clock.instant() < due) AlertStep.Wait(due)
            else AlertStep.Remind(progress.alertId, progress.reminderCount + 1, due)
        }
        if (progress.transmissionCount == 3) return AlertStep.AwaitingReceipt
        // Anchor retries to actual sends: recovery must not burst through overdue slots.
        val due = progress.lastTransmissionAt?.plus(retryGaps[progress.transmissionCount - 1]) ?: progress.createdAt
        return if (clock.instant() < due) AlertStep.Wait(due)
        else AlertStep.Transmit(progress.alertId, progress.transmissionCount + 1, due)
    }

    fun assessDelivery(createdAt: Instant, visibleAt: Instant?): DeliveryAssessment {
        if (visibleAt == null) return DeliveryAssessment.UNCONFIRMED
        require(visibleAt >= createdAt)
        val elapsed = Duration.between(createdAt, visibleAt)
        return when {
            elapsed <= InitialTiming.deliveryTarget -> DeliveryAssessment.ON_TARGET
            elapsed <= InitialTiming.deliveryLimit -> DeliveryAssessment.WITHIN_LIMIT
            else -> DeliveryAssessment.OVER_LIMIT
        }
    }
}
