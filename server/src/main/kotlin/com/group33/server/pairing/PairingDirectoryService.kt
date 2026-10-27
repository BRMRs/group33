package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.time.Clock
import java.util.UUID

internal data class CodeDeletionKey(val actorId: String, val operationId: UUID)
internal class CodeDeletionReceipt(val installationId: String, val fingerprint: String, val codeId: String) {
    override fun toString() = "CodeDeletionReceipt(REDACTED)"
}
internal interface PairingDirectoryStore : PairingDecisionStore {
    fun <T> directoryTransaction(work: (PairingDirectoryTransaction) -> T): T
}
internal interface PairingDirectoryTransaction : PairingDecisionTransaction {
    fun participantRequests(actorId: String, role: UserRole, now: Long, status: PairingRequestStatus?): List<PairingRequestView>
    fun participantRelationships(actorId: String, role: UserRole): List<CareRelationshipView>
    fun codeDeletion(key: CodeDeletionKey): CodeDeletionReceipt?
    fun putCodeDeletion(key: CodeDeletionKey, value: CodeDeletionReceipt)
    fun invalidateCode(elderId: String, codeId: String, now: Long): Boolean
}
internal enum class CodeDeletionRejection { ROLE_NOT_ALLOWED, RESOURCE_NOT_ACCESSIBLE, IDEMPOTENCY_KEY_REUSED, OPERATION_IN_PROGRESS }
internal sealed interface CodeDeletionResult {
    data object Deleted : CodeDeletionResult
    data class Rejected(val reason: CodeDeletionRejection) : CodeDeletionResult
}

/** Participant summaries only. No medical access follows from a list entry or an old receipt. */
internal class PairingDirectoryService(private val store: PairingDirectoryStore, private val clock: Clock = Clock.systemUTC()) {
    fun requests(actor: AnonymousUser, status: PairingRequestStatus? = null) = store.directoryTransaction {
        ItemsResponse(it.participantRequests(actor.id, actor.role, clock.millis(), status))
    }
    fun relationships(actor: AnonymousUser) = store.directoryTransaction {
        ItemsResponse(it.participantRelationships(actor.id, actor.role))
    }
    fun deleteCode(actor: AnonymousUser, codeId: String, operationId: UUID): CodeDeletionResult {
        if (actor.role != UserRole.ELDER) return CodeDeletionResult.Rejected(CodeDeletionRejection.ROLE_NOT_ALLOWED)
        return try {
            store.directoryTransaction { tx ->
                val key = CodeDeletionKey(actor.id, operationId)
                val fingerprint = PairingCodeIssuanceService.hash("${ContractProtocol.CANONICALIZATION_VERSION}:DELETE_PAIRING_CODE:$codeId:{}")
                val previous = tx.codeDeletion(key)
                val current = tx.activeCode(actor.id)
                // A matching private receipt retains ownership evidence for its removed code.
                // A changed target must still be currently owned before a binding conflict is exposed.
                val known = current?.id == codeId || previous?.codeId == codeId
                if (!known) return@directoryTransaction CodeDeletionResult.Rejected(CodeDeletionRejection.RESOURCE_NOT_ACCESSIBLE)
                if (previous != null) {
                    if (previous.installationId != actor.installationId || previous.fingerprint != fingerprint) {
                        return@directoryTransaction CodeDeletionResult.Rejected(CodeDeletionRejection.IDEMPOTENCY_KEY_REUSED)
                    }
                    return@directoryTransaction CodeDeletionResult.Deleted
                }
                check(tx.invalidateCode(actor.id, codeId, clock.millis()))
                tx.putCodeDeletion(key, CodeDeletionReceipt(actor.installationId, fingerprint, codeId))
                CodeDeletionResult.Deleted
            }
        } catch (_: CodeStoreBusy) { CodeDeletionResult.Rejected(CodeDeletionRejection.OPERATION_IN_PROGRESS) }
    }
}
