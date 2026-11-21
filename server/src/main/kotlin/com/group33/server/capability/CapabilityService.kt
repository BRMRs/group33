package com.group33.server.capability

import com.group33.server.auth.AnonymousUser
import com.group33.server.auth.CareRelationshipAccess
import com.group33.shared.contract.ReminderCapabilityReceipt
import com.group33.shared.contract.ReminderCapabilityReport
import com.group33.shared.contract.ReminderCapabilityView
import com.group33.shared.model.UserRole
import java.time.Clock
import java.util.UUID

/** Private service results; HTTP status/header/unknown-completion mapping is a separate adapter. */
internal enum class CapabilityRejection {
    ROLE_NOT_ALLOWED, RESOURCE_NOT_ACCESSIBLE, INVALID_REPORT, IDEMPOTENCY_KEY_REUSED, CAPABILITY_SEQUENCE_CONFLICT,
}
internal sealed interface CapabilityAcceptance {
    class Accepted(val receipt: ReminderCapabilityReceipt) : CapabilityAcceptance {
        override fun toString() = "CapabilityAccepted(REDACTED)"
    }
    data class Rejected(val reason: CapabilityRejection) : CapabilityAcceptance
    data object InProgress : CapabilityAcceptance
}
internal sealed interface CapabilityRead {
    class Current(val view: ReminderCapabilityView?) : CapabilityRead {
        override fun toString() = "CapabilityCurrent(REDACTED)"
    }
    data object NotAccessible : CapabilityRead
    data object InProgress : CapabilityRead
}

/** Plain application service. Both actors and read targets must come from trusted identity resolution. */
internal class CapabilityService(
    private val store: CapabilityStore,
    private val relationships: CareRelationshipAccess,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun report(actor: AnonymousUser, elderId: String, operationId: UUID, input: ReminderCapabilityReport): CapabilityAcceptance {
        if (actor.role != UserRole.ELDER) return CapabilityAcceptance.Rejected(CapabilityRejection.ROLE_NOT_ALLOWED)
        if (actor.id != elderId) return CapabilityAcceptance.Rejected(CapabilityRejection.RESOURCE_NOT_ACCESSIBLE)
        val owner = CapabilityOwner(actor.id, actor.installationId)
        val payload = input.copy(reasons = input.reasons.toList())
        val binding = CapabilityBinding(owner, payload)
        val key = CapabilityOperationKey(actor.id, operationId)
        return try {
            store.transaction { tx ->
                val previous = tx.operation(key)
                if (previous != null && previous.binding != binding) {
                    return@transaction CapabilityAcceptance.Rejected(CapabilityRejection.IDEMPOTENCY_KEY_REUSED)
                }
                previous?.receipt?.let { return@transaction CapabilityAcceptance.Accepted(it.detached()) }
                fun reject(reason: CapabilityRejection): CapabilityAcceptance.Rejected {
                    tx.putOperation(key, CapabilityOperation(binding, rejection = reason))
                    return CapabilityAcceptance.Rejected(reason)
                }
                if (payload.reportId != operationId.toString() || payload.observationSequence <= 0 || payload.observedAtEpochMillis < 0) {
                    return@transaction reject(CapabilityRejection.INVALID_REPORT)
                }
                val observation = tx.observation(owner, payload.observationSequence)
                if (observation != null && observation != payload) {
                    return@transaction reject(CapabilityRejection.CAPABILITY_SEQUENCE_CONFLICT)
                }
                val current = tx.current(owner)
                val receivedAt = clock.millis()
                val applied = current == null || payload.observationSequence > current.observationSequence
                val next = if (applied) ReminderCapabilityView(
                    payload.reportId, payload.observationSequence, payload.observedAtEpochMillis,
                    payload.deliveryCondition, payload.notificationPermissionGranted, payload.channelEnabled,
                    payload.exactAlarmAccess, payload.reasons.toList(), receivedAt,
                ) else checkNotNull(current)
                val receipt = ReminderCapabilityReceipt(payload.reportId, receivedAt, applied, next.detached())
                tx.putObservation(owner, payload)
                if (applied) tx.putCurrent(owner, next)
                tx.putOperation(key, CapabilityOperation(binding, receipt = receipt))
                CapabilityAcceptance.Accepted(receipt.detached())
            }
        } catch (_: CapabilityStoreBusy) { CapabilityAcceptance.InProgress }
    }

    /** Authorized snapshot building seam, not a new public API or a caller-selected installation. */
    fun lastKnown(actor: AnonymousUser, target: AnonymousUser): CapabilityRead {
        val allowed = target.role == UserRole.ELDER && when (actor.role) {
            UserRole.ELDER -> actor.id == target.id && actor.installationId == target.installationId
            UserRole.CAREGIVER -> relationships.isActive(actor.id, target.id)
        }
        if (!allowed) return CapabilityRead.NotAccessible
        return try {
            store.transaction { tx ->
                CapabilityRead.Current(tx.current(CapabilityOwner(target.id, target.installationId))?.detached())
            }
        } catch (_: CapabilityStoreBusy) { CapabilityRead.InProgress }
    }
}
