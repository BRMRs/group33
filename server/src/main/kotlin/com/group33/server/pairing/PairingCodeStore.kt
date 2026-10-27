package com.group33.server.pairing

import com.group33.server.photo.*
import com.group33.server.plan.*
import com.group33.shared.contract.MedicationView
import com.group33.shared.contract.ReminderPlanView
import com.group33.shared.contract.PlanRevisionView
import com.group33.shared.contract.ConfirmReminderPlanResponse
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
internal class InMemoryPairingCodeStore(private val lockWaitMillis: Long = 1_000) : PlanMutationStore {
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

    private var deletions = mutableMapOf<CodeDeletionKey, CodeDeletionReceipt>()

    private var photoOperations = mutableMapOf<PhotoUploadKey, PhotoUploadOperation>()
    private var photos = mutableMapOf<String, ReadyMedicationPhoto>()

    override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = requestTransaction { work(it) }

    override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T = decisionTransaction { work(it) }

    override fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T = directoryTransaction { work(it) }

    override fun <T> directoryTransaction(work: (PairingDirectoryTransaction) -> T): T = photoTransaction { work(it) }

    override fun <T> photoTransaction(work: (PhotoMetadataTransaction) -> T): T = confirmationTransaction { work(it) }

    private var confirmations = mutableMapOf<PlanConfirmationKey, PlanConfirmationOperation>()
    private var medications = mutableMapOf<String, MedicationView>()
    private var plans = mutableMapOf<String, ReminderPlanView>()
    private var revisions = mutableMapOf<Pair<String, Long>, PlanRevisionView>()

    override fun <T> confirmationTransaction(work: (PlanConfirmationTransaction) -> T): T = mutationTransaction { work(it) }

    private var mutations = mutableMapOf<PlanMutationKey, PlanMutationOperation>()
    override fun <T> mutationTransaction(work: (PlanMutationTransaction) -> T): T {
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
            val stagedDeletions = deletions.toMutableMap()
            val stagedPhotoOperations = photoOperations.toMutableMap()
            val stagedPhotos = photos.toMutableMap()
            val stagedMutations = mutations.toMutableMap()
            val stagedConfirmations = confirmations.toMutableMap()
            val stagedMedications = medications.toMutableMap()
            val stagedPlans = plans.toMutableMap()
            val stagedRevisions = revisions.toMutableMap()
            val result = work(object : PlanMutationTransaction {
                override fun plan(id: String) = stagedPlans[id]?.let(PlanConfirmationCopies::plan)
                override fun medication(id: String) = stagedMedications[id]
                override fun mutationOperation(key: PlanMutationKey) = stagedMutations[key]?.detached()
                override fun putMutationOperation(key: PlanMutationKey, value: PlanMutationOperation) {
                    stagedMutations[key]?.let { old ->
                        check(old.installationId == value.installationId && old.elderId == value.elderId && old.planId == value.planId && old.fingerprint == value.fingerprint)
                        if (old.receipt != null) check(old.receipt == value.receipt)
                    }
                    value.receipt?.let {
                        check(it.plan.id == value.planId && it.plan.elderId == value.elderId && it.plan.medicationId == it.medication.id)
                        check(stagedPlans[it.plan.id] == it.plan && stagedMedications[it.medication.id] == it.medication)
                        check(stagedRevisions[it.plan.id to it.plan.revision] == PlanConfirmationCopies.initialRevision(ConfirmReminderPlanResponse(it.medication, it.plan)))
                    }
                    stagedMutations[key] = value.detached()
                }
                override fun updateMedication(value: MedicationView) {
                    val old = checkNotNull(stagedMedications[value.id])
                    check(old.elderId == value.elderId && old.photoId == value.photoId && old.active == value.active)
                    stagedMedications[value.id] = value
                }
                override fun updatePlan(value: ReminderPlanView, expectedRevision: Long) {
                    val old = checkNotNull(stagedPlans[value.id])
                    check(old.revision == expectedRevision && expectedRevision < Long.MAX_VALUE && value.revision == expectedRevision + 1)
                    check(old.elderId == value.elderId && old.medicationId == value.medicationId && (!value.active || old.active))
                    stagedPlans[value.id] = PlanConfirmationCopies.plan(value)
                }
                override fun confirmationOperation(key: PlanConfirmationKey) = stagedConfirmations[key]?.let(PlanConfirmationCopies::operation)
                override fun putConfirmationOperation(key: PlanConfirmationKey, value: PlanConfirmationOperation) {
                    stagedConfirmations[key]?.let { old ->
                        check(old.installationId == value.installationId && old.elderId == value.elderId && old.fingerprint == value.fingerprint)
                        if (old.receipt != null) check(old.receipt == value.receipt)
                    }
                    value.receipt?.let { receipt ->
                        check(receipt.medication.elderId == value.elderId && receipt.plan.elderId == value.elderId)
                        check(receipt.plan.medicationId == receipt.medication.id)
                        check(stagedMedications[receipt.medication.id] == receipt.medication && stagedPlans[receipt.plan.id] == receipt.plan)
                        check(stagedRevisions[receipt.plan.id to receipt.plan.revision] == PlanConfirmationCopies.initialRevision(receipt))
                    }
                    stagedConfirmations[key] = PlanConfirmationCopies.operation(value)
                }
                override fun insertMedication(value: MedicationView) {
                    check(value.id !in stagedMedications && value.active)
                    check(stagedPhotos[value.photoId]?.view?.elderId == value.elderId)
                    stagedMedications[value.id] = value
                }
                override fun insertPlan(value: ReminderPlanView) {
                    check(value.id !in stagedPlans && value.revision == 1L && value.active)
                    check(stagedMedications[value.medicationId]?.elderId == value.elderId)
                    stagedPlans[value.id] = PlanConfirmationCopies.plan(value)
                }
                override fun insertRevision(value: PlanRevisionView) {
                    check((value.id to value.revision) !in stagedRevisions)
                    val plan = checkNotNull(stagedPlans[value.id]); val medication = checkNotNull(stagedMedications[value.medicationId])
                    check(value == PlanConfirmationCopies.initialRevision(ConfirmReminderPlanResponse(medication, plan)))
                    stagedRevisions[value.id to value.revision] = PlanConfirmationCopies.revision(value)
                }
                override fun medications(elderId: String) = stagedMedications.values.filter { it.elderId == elderId }.sortedBy { it.id }
                override fun plans(elderId: String) = stagedPlans.values.filter { it.elderId == elderId }.sortedBy { it.id }.map(PlanConfirmationCopies::plan)
                override fun revisions(elderId: String) = stagedRevisions.values.filter { it.elderId == elderId }.sortedBy { it.id }.map(PlanConfirmationCopies::revision)

                override fun photoOperation(key: PhotoUploadKey) = stagedPhotoOperations[key]
                override fun putPhotoOperation(key: PhotoUploadKey, value: PhotoUploadOperation) {
                    stagedPhotoOperations[key]?.let { old ->
                        check(old.installationId == value.installationId && old.elderId == value.elderId && old.fingerprint == value.fingerprint && old.photoId == value.photoId)
                        if (old.state == PhotoUploadState.READY) check(value.state == PhotoUploadState.READY && old.receipt == value.receipt)
                    }
                    if (value.state == PhotoUploadState.READY) check(stagedPhotos[value.photoId.toString()]?.view == value.receipt)
                    stagedPhotoOperations[key] = value
                }
                override fun readyPhoto(id: String) = stagedPhotos[id]
                override fun readyPhotos(elderId: String) = stagedPhotos.values.filter { it.view.elderId == elderId }.map { it.view }.sortedBy { it.id }
                override fun publishPhoto(value: ReadyMedicationPhoto) {
                    check(value.view.id == value.objectKey.objectId.toString())
                    stagedPhotos[value.view.id]?.let { check(it.view == value.view && it.objectKey == value.objectKey) }
                    stagedPhotos[value.view.id] = value
                }
                override fun participantRequests(actorId: String, role: com.group33.shared.model.UserRole, now: Long, status: PairingRequestStatus?) =
                    stagedRequests.values.filter {
                        if (role == com.group33.shared.model.UserRole.ELDER) it.elderId == actorId else it.caregiver.id == actorId
                    }.map { checkNotNull(request(it.id, now)) }.filter { status == null || it.status == status }.sortedBy { it.id }
                override fun participantRelationships(actorId: String, role: com.group33.shared.model.UserRole) =
                    stagedRelationships.values.filter {
                        if (role == com.group33.shared.model.UserRole.ELDER) it.elderId == actorId else it.caregiverId == actorId
                    }.sortedBy { it.id }
                override fun codeDeletion(key: CodeDeletionKey) = stagedDeletions[key]
                override fun putCodeDeletion(key: CodeDeletionKey, value: CodeDeletionReceipt) {
                    stagedDeletions[key]?.let {
                        check(it.installationId == value.installationId && it.fingerprint == value.fingerprint && it.codeId == value.codeId)
                    }
                    stagedDeletions[key] = value
                }
                override fun invalidateCode(elderId: String, codeId: String, now: Long): Boolean {
                    if (stagedCodes[elderId]?.id != codeId) return false
                    stagedConsumed[codeId]?.let { id ->
                        val old = checkNotNull(request(id, now))
                        if (old.status == PairingRequestStatus.PENDING) stagedRequests[id] = old.copy(
                            status = PairingRequestStatus.CANCELLED, closedReason = PairingClosedReason.ELDER_CANCELLED)
                    }
                    stagedCodes.remove(elderId)
                    return true
                }
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
            // This first-confirmation seam cannot commit a new orphan Medication/plan or omit its receipt.
            for (id in stagedMedications.keys - medications.keys) {
                check(stagedConfirmations.values.any { it.receipt?.medication?.id == id })
            }
            for (id in stagedPlans.keys - plans.keys) {
                check(stagedConfirmations.values.any { it.receipt?.plan?.id == id })
            }
            for ((id, old) in plans) if (stagedPlans[id] != old) {
                val plan = checkNotNull(stagedPlans[id])
                check(stagedRevisions[id to plan.revision] == PlanConfirmationCopies.initialRevision(ConfirmReminderPlanResponse(checkNotNull(stagedMedications[plan.medicationId]), plan)))
                check(stagedMutations.values.any { it.receipt?.plan == plan })
            }
            for ((id, old) in medications) if (stagedMedications[id] != old)
                check(stagedMutations.values.any { operation -> operation.receipt?.let { it.medication == stagedMedications[id] && it.plan == stagedPlans[it.plan.id] } == true })
            mutations = stagedMutations
            confirmations = stagedConfirmations
            medications = stagedMedications
            plans = stagedPlans
            revisions = stagedRevisions
            photoOperations = stagedPhotoOperations
            photos = stagedPhotos
            deletions = stagedDeletions
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
