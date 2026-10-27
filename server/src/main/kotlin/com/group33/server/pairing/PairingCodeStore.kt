package com.group33.server.pairing

import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.PairingCodeView
import com.group33.shared.contract.PairingClosedReason
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.PairingRequestView
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal data class CodeIssueKey(val actorId: String, val operationId: UUID)
internal data class CodeIssueBinding(val installationId: String, val targetElderId: String, val fingerprint: String)

/** Replay material is private to the store/service seam, never a public query or log value. */
internal class CodeIssueReceipt(private val view: PairingCodeView) {
    fun replay(): PairingCodeView = view
    override fun toString() = "CodeIssueReceipt(REDACTED)"
}

internal data class CodeIssueOperation(
    val binding: CodeIssueBinding,
    val receipt: CodeIssueReceipt? = null,
    val rejection: CodeIssuanceResult.Rejected? = null,
)

internal data class StoredPairingCode(
    val id: String,
    val elderId: String,
    val hash: String,
    val expiresAtEpochMillis: Long,
) {
    fun isEffectiveAt(now: Long) = now < expiresAtEpochMillis
}

/** All mutations, including code replacement, quota and operation evidence, commit or roll back together. */
internal interface PairingCodeStore {
    fun <T> transaction(work: (PairingCodeTransaction) -> T): T
}

internal interface PairingCodeTransaction {
    fun operation(key: CodeIssueKey): CodeIssueOperation?
    fun putOperation(key: CodeIssueKey, value: CodeIssueOperation)
    fun activeCode(elderId: String): StoredPairingCode?
    fun replaceActiveCode(value: StoredPairingCode, now: Long)
    fun hasEffectiveHash(hash: String, now: Long): Boolean
    fun issuanceTimes(elderId: String): List<Long>
    fun putIssuanceTimes(elderId: String, times: List<Long>)
}

internal class CodeStoreBusy : RuntimeException("Pairing code store is busy")

/** Process-local prototype. A bounded global lock and copy-on-write staging provide real rollback. */
internal class InMemoryPairingCodeStore(private val lockWaitMillis: Long = 1_000) : PairingDecisionStore {
    init { require(lockWaitMillis >= 0) }
    private val lock = ReentrantLock()
    private var operations = mutableMapOf<CodeIssueKey, CodeIssueOperation>()
    private var codes = mutableMapOf<String, StoredPairingCode>()
    private var times = mutableMapOf<String, List<Long>>()
    private var requests = mutableMapOf<String, PairingRequestView>()
    private var consumed = mutableMapOf<String, String>()
    private var requestOperations = mutableMapOf<PairingRequestKey, PairingRequestOperation>()
    private var installationAttempts = mutableMapOf<String, List<Long>>()
    private var addressAttempts = mutableMapOf<String, List<Long>>()

    private var relationships = mutableMapOf<String, CareRelationshipView>()
    private var decisions = mutableMapOf<PairingDecisionKey, PairingDecisionOperation>()

    override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = requestTransaction { work(it) }

    override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T = decisionTransaction { work(it) }

    override fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T {
        if (!lock.tryLock(lockWaitMillis, TimeUnit.MILLISECONDS)) throw CodeStoreBusy()
        try {
            val stagedOperations = operations.toMutableMap()
            val stagedCodes = codes.toMutableMap()
            val stagedTimes = times.toMutableMap()
            val stagedRequests = requests.toMutableMap()
            val stagedConsumed = consumed.toMutableMap()
            val stagedRequestOperations = requestOperations.toMutableMap()
            val stagedInstallationAttempts = installationAttempts.toMutableMap()
            val stagedAddressAttempts = addressAttempts.toMutableMap()
            val stagedRelationships = relationships.toMutableMap()
            val stagedDecisions = decisions.toMutableMap()
            val result = work(object : PairingDecisionTransaction {
                override fun decisionOperation(key: PairingDecisionKey) = stagedDecisions[key]
                override fun putDecisionOperation(key: PairingDecisionKey, value: PairingDecisionOperation) {
                    stagedDecisions[key]?.let { old ->
                        check(old.installationId == value.installationId && old.fingerprint == value.fingerprint)
                        check(old.result is PairingDecisionResult.Rejected || old.result === value.result)
                    }
                    stagedDecisions[key] = value
                }
                override fun relationship(id: String) = stagedRelationships[id]
                override fun activeRelationship(elderId: String) = stagedRelationships.values.singleOrNull {
                    it.elderId == elderId && it.status == CareRelationshipStatus.ACTIVE
                }
                override fun insertRelationship(value: CareRelationshipView) {
                    check(value.id !in stagedRelationships && activeRelationship(value.elderId) == null)
                    check(value.status == CareRelationshipStatus.ACTIVE && value.revokedAtEpochMillis == null)
                    check(value.elder.id == value.elderId && value.caregiver.id == value.caregiverId)
                    check(value.elder.role == com.group33.shared.model.UserRole.ELDER)
                    check(value.caregiver.role == com.group33.shared.model.UserRole.CAREGIVER)
                    stagedRelationships[value.id] = value
                }
                override fun revokeRelationship(id: String, now: Long): CareRelationshipView {
                    val old = checkNotNull(stagedRelationships[id])
                    val revoked = if (old.status == CareRelationshipStatus.REVOKED) old
                        else old.copy(status = CareRelationshipStatus.REVOKED, revokedAtEpochMillis = now)
                    stagedRelationships[id] = revoked
                    return revoked
                }
                override fun closeRequest(value: PairingRequestView) {
                    val old = checkNotNull(stagedRequests[value.id])
                    check(old.status == PairingRequestStatus.PENDING)
                    check(old == value.copy(status = old.status, closedReason = old.closedReason, relationshipId = old.relationshipId))
                    when (value.status) {
                        PairingRequestStatus.ACCEPTED -> {
                            val relation = checkNotNull(stagedRelationships[value.relationshipId])
                            check(relation.status == CareRelationshipStatus.ACTIVE && relation.elderId == old.elderId)
                            check(relation.caregiverId == old.caregiver.id && value.closedReason == null)
                        }
                        PairingRequestStatus.REJECTED -> check(value.closedReason == PairingClosedReason.ELDER_REJECTED && value.relationshipId == null)
                        PairingRequestStatus.CANCELLED -> check(value.closedReason in setOf(PairingClosedReason.ELDER_CANCELLED,
                            PairingClosedReason.CAREGIVER_CANCELLED) && value.relationshipId == null)
                        else -> error("Unsupported explicit pairing decision")
                    }
                    stagedRequests[value.id] = value
                }
                override fun operation(key: CodeIssueKey) = stagedOperations[key]
                override fun putOperation(key: CodeIssueKey, value: CodeIssueOperation) { stagedOperations[key] = value }
                override fun activeCode(elderId: String) = stagedCodes[elderId]
                override fun replaceActiveCode(value: StoredPairingCode, now: Long) {
                    stagedCodes[value.elderId]?.let { old ->
                        stagedConsumed[old.id]?.let { id ->
                            val request = request(id, now)
                            if (request?.status == PairingRequestStatus.PENDING) {
                                stagedRequests[id] = request.copy(status = PairingRequestStatus.CANCELLED,
                                    closedReason = PairingClosedReason.CODE_REPLACED)
                            }
                        }
                    }
                    stagedCodes[value.elderId] = value
                }
                override fun hasEffectiveHash(hash: String, now: Long) =
                    stagedCodes.values.any { it.hash == hash && it.isEffectiveAt(now) }
                override fun issuanceTimes(elderId: String) = stagedTimes[elderId]?.toList().orEmpty()
                override fun putIssuanceTimes(elderId: String, times: List<Long>) { stagedTimes[elderId] = times.toList() }
                override fun requestOperation(key: PairingRequestKey) = stagedRequestOperations[key]
                override fun putRequestOperation(key: PairingRequestKey, value: PairingRequestOperation) {
                    stagedRequestOperations[key] = value
                }
                override fun unconsumedCode(hash: String, now: Long) = stagedCodes.values.singleOrNull {
                    it.hash == hash && it.isEffectiveAt(now) && it.id !in stagedConsumed
                }
                override fun consume(code: StoredPairingCode, request: PairingRequestView) {
                    check(stagedCodes[code.elderId] == code && code.id !in stagedConsumed)
                    check(request.id !in stagedRequests && request.elderId == code.elderId)
                    check(request.status == PairingRequestStatus.PENDING && request.expiresAtEpochMillis == code.expiresAtEpochMillis)
                    check(request.relationshipId == null && request.closedReason == null)
                    stagedRequests[request.id] = request
                    stagedConsumed[code.id] = request.id
                }
                override fun request(id: String, now: Long): PairingRequestView? {
                    val current = stagedRequests[id] ?: return null
                    val resolved = if (current.status == PairingRequestStatus.PENDING && now >= current.expiresAtEpochMillis) {
                        current.copy(status = PairingRequestStatus.EXPIRED, closedReason = PairingClosedReason.CODE_EXPIRED)
                    } else current
                    stagedRequests[id] = resolved
                    return resolved
                }
                override fun installationAttempts(id: String) = stagedInstallationAttempts[id].orEmpty().toList()
                override fun putInstallationAttempts(id: String, times: List<Long>) { stagedInstallationAttempts[id] = times.toList() }
                override fun addressAttempts(hash: String) = stagedAddressAttempts[hash].orEmpty().toList()
                override fun putAddressAttempts(hash: String, times: List<Long>) { stagedAddressAttempts[hash] = times.toList() }
            })
            relationships = stagedRelationships
            decisions = stagedDecisions
            operations = stagedOperations
            codes = stagedCodes
            times = stagedTimes
            requests = stagedRequests
            consumed = stagedConsumed
            requestOperations = stagedRequestOperations
            installationAttempts = stagedInstallationAttempts
            addressAttempts = stagedAddressAttempts
            return result
        } finally {
            lock.unlock()
        }
    }
}
