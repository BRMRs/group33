package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.server.auth.CareRelationshipAccess
import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.PairingClosedReason
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.PairingRequestView
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.time.Clock
import java.util.UUID

internal enum class PairingDecisionRejection {
    RESOURCE_NOT_ACCESSIBLE, ROLE_NOT_ALLOWED, IDEMPOTENCY_KEY_REUSED,
    PAIRING_REQUEST_CLOSED, ELDER_ALREADY_PAIRED, OPERATION_IN_PROGRESS,
}

/** Private service results, not new wire DTOs or automatic Spring registrations. */
internal sealed interface PairingDecisionResult {
    class Relationship(val view: CareRelationshipView) : PairingDecisionResult {
        override fun toString() = "PairingRelationship(REDACTED)"
    }
    class Request(val view: PairingRequestView) : PairingDecisionResult {
        override fun toString() = "PairingRequestDecision(REDACTED)"
    }
    data object Cancelled : PairingDecisionResult
    data class Rejected(val reason: PairingDecisionRejection, val retryAfterSeconds: Long? = null) : PairingDecisionResult
}

/** Actor comes from verified authentication. Request IDs/keys never grant participant rights. */
internal class PairingDecisionService(private val store: PairingDecisionStore, private val clock: Clock = Clock.systemUTC()) {
    fun accept(actor: AnonymousUser, requestId: String, operationId: UUID) =
        decide(actor, requestId, operationId, PairingDecision.ACCEPT)
    fun reject(actor: AnonymousUser, requestId: String, operationId: UUID) =
        decide(actor, requestId, operationId, PairingDecision.REJECT)
    fun cancel(actor: AnonymousUser, requestId: String, operationId: UUID) =
        decide(actor, requestId, operationId, PairingDecision.CANCEL)
    fun revoke(actor: AnonymousUser, relationshipId: String, operationId: UUID) =
        decide(actor, relationshipId, operationId, PairingDecision.REVOKE)

    fun relationship(actor: AnonymousUser, id: String): PairingDecisionResult = transaction { tx ->
        val view = tx.relationship(id)
        if (view == null || !participant(actor, view.elderId, view.caregiverId)) inaccessible()
        else PairingDecisionResult.Relationship(view)
    }

    private fun decide(actor: AnonymousUser, target: String, operation: UUID, action: PairingDecision): PairingDecisionResult = transaction { tx ->
        val now = clock.millis() // expiry must be checked after lock acquisition
        val request = if (action == PairingDecision.REVOKE) null else tx.request(target, now)
        val relation = if (action == PairingDecision.REVOKE) tx.relationship(target) else null
        val elderId = request?.elderId ?: relation?.elderId ?: return@transaction inaccessible()
        val caregiverId = request?.caregiver?.id ?: checkNotNull(relation).caregiverId
        // Participants retain terminal metadata access after revocation; medical-data access does not follow a receipt.
        if (!participant(actor, elderId, caregiverId)) return@transaction inaccessible()
        if ((action == PairingDecision.ACCEPT || action == PairingDecision.REJECT) && actor.role != UserRole.ELDER) {
            return@transaction rejected(PairingDecisionRejection.ROLE_NOT_ALLOWED)
        }
        val key = PairingDecisionKey(actor.id, action, operation)
        val fingerprint = PairingCodeIssuanceService.hash("${ContractProtocol.CANONICALIZATION_VERSION}:$action:$target:{}")
        val previous = tx.decisionOperation(key)
        if (previous != null && (previous.installationId != actor.installationId || previous.fingerprint != fingerprint)) {
            return@transaction rejected(PairingDecisionRejection.IDEMPOTENCY_KEY_REUSED)
        }
        previous?.result?.let { if (it !is PairingDecisionResult.Rejected) return@transaction it }
        val result = when {
            action == PairingDecision.REVOKE -> PairingDecisionResult.Relationship(tx.revokeRelationship(target, now))
            checkNotNull(request).status != PairingRequestStatus.PENDING -> rejected(PairingDecisionRejection.PAIRING_REQUEST_CLOSED)
            action == PairingDecision.ACCEPT && tx.activeRelationship(elderId) != null -> rejected(PairingDecisionRejection.ELDER_ALREADY_PAIRED)
            action == PairingDecision.ACCEPT -> {
                val accepted = CareRelationshipView(UUID.randomUUID().toString(), elderId, caregiverId,
                    User(actor.id, actor.displayName, actor.role), request.caregiver, CareRelationshipStatus.ACTIVE, now)
                tx.insertRelationship(accepted)
                tx.closeRequest(request.copy(status = PairingRequestStatus.ACCEPTED, relationshipId = accepted.id))
                PairingDecisionResult.Relationship(accepted)
            }
            else -> {
                val cancelled = action == PairingDecision.CANCEL
                val reason = if (!cancelled) PairingClosedReason.ELDER_REJECTED
                    else if (actor.role == UserRole.ELDER) PairingClosedReason.ELDER_CANCELLED
                    else PairingClosedReason.CAREGIVER_CANCELLED
                val closed = request.copy(status = if (cancelled) PairingRequestStatus.CANCELLED else PairingRequestStatus.REJECTED,
                    closedReason = reason)
                tx.closeRequest(closed)
                if (cancelled) PairingDecisionResult.Cancelled else PairingDecisionResult.Request(closed)
            }
        }
        tx.putDecisionOperation(key, PairingDecisionOperation(actor.installationId, fingerprint, result))
        result
    }

    private fun participant(actor: AnonymousUser, elderId: String, caregiverId: String) =
        (actor.role == UserRole.ELDER && actor.id == elderId) || (actor.role == UserRole.CAREGIVER && actor.id == caregiverId)
    private fun inaccessible() = rejected(PairingDecisionRejection.RESOURCE_NOT_ACCESSIBLE)
    private fun rejected(reason: PairingDecisionRejection) = PairingDecisionResult.Rejected(reason)
    private fun transaction(work: (PairingDecisionTransaction) -> PairingDecisionResult) = try {
        store.decisionTransaction(work)
    } catch (_: CodeStoreBusy) {
        PairingDecisionResult.Rejected(PairingDecisionRejection.OPERATION_IN_PROGRESS, 1)
    }
}

/** Explicit local adapter, NOT a Spring bean. A protected write must check access inside its own business transaction. */
internal class StoredCareRelationshipAccess(private val store: PairingDecisionStore) : CareRelationshipAccess {
    override fun isActive(caregiverId: String, elderId: String): Boolean = store.decisionTransaction {
        it.activeRelationship(elderId)?.caregiverId == caregiverId
    }
}
