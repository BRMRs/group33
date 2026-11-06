package com.group33.server.pairing

import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.PairingRequestView
import java.util.UUID

internal enum class PairingDecision { ACCEPT, REJECT, CANCEL, REVOKE }
internal data class PairingDecisionKey(val actorId: String, val action: PairingDecision, val operationId: UUID)
internal class PairingDecisionOperation(
    val installationId: String,
    val fingerprint: String,
    val result: PairingDecisionResult,
) {
    override fun toString() = "PairingDecisionOperation(REDACTED)"
}

/** Separate extension: existing request-only PostgreSQL adapter does not claim relationship support. */
internal interface PairingDecisionStore : PairingRequestStore {
    fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T
}

/** All decisions, code consumption, current relationship and immutable success receipts share one transaction. */
internal interface PairingDecisionTransaction : PairingRequestTransaction {
    fun decisionOperation(key: PairingDecisionKey): PairingDecisionOperation?
    fun putDecisionOperation(key: PairingDecisionKey, value: PairingDecisionOperation)
    fun closeRequest(value: PairingRequestView)
    fun relationship(id: String): CareRelationshipView?
    fun activeRelationship(elderId: String): CareRelationshipView?
    fun insertRelationship(value: CareRelationshipView)
    fun revokeRelationship(id: String, now: Long): CareRelationshipView
}
