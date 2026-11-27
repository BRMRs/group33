package com.group33.server.capability

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ReminderCapabilityReceipt
import com.group33.shared.contract.ReminderCapabilityReport
import com.group33.shared.contract.ReminderCapabilityView
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal data class CapabilityOwner(val elderId: String, val installationId: String) {
    override fun toString() = "CapabilityOwner(REDACTED)"
}
internal data class CapabilityOperationKey(val actorId: String, val operationId: UUID) {
    override fun toString() = "CapabilityOperationKey(REDACTED)"
}
internal data class CapabilityBinding(
    val owner: CapabilityOwner,
    val report: ReminderCapabilityReport,
    val canonicalizationVersion: String = ContractProtocol.CANONICALIZATION_VERSION,
) {
    override fun toString() = "CapabilityBinding(REDACTED)"
}
internal class CapabilityOperation(
    val binding: CapabilityBinding,
    val receipt: ReminderCapabilityReceipt? = null,
    val rejection: CapabilityRejection? = null,
) {
    override fun toString() = "CapabilityOperation(REDACTED)"
}

internal fun ReminderCapabilityReport.detached() = copy(reasons = ArrayList(reasons))
internal fun ReminderCapabilityView.detached() = copy(reasons = ArrayList(reasons))
internal fun ReminderCapabilityReceipt.detached() = copy(current = current.detached())
internal fun CapabilityBinding.detached() = copy(report = report.detached())
internal fun CapabilityOperation.detached() = CapabilityOperation(
    binding = binding.detached(),
    receipt = receipt?.detached(),
    rejection = rejection,
)

/** One deployment supplies one shared instance; durable adapters must implement the same transaction. */
internal interface CapabilityStore {
    fun <T> transaction(work: (CapabilityTransaction) -> T): T
}
internal interface CapabilityTransaction {
    fun operation(key: CapabilityOperationKey): CapabilityOperation?
    fun putOperation(key: CapabilityOperationKey, value: CapabilityOperation)
    fun current(owner: CapabilityOwner): ReminderCapabilityView?
    fun putCurrent(owner: CapabilityOwner, value: ReminderCapabilityView)
    fun observation(owner: CapabilityOwner, sequence: Long): ReminderCapabilityReport?
    fun putObservation(owner: CapabilityOwner, report: ReminderCapabilityReport)
}
internal class CapabilityStoreBusy : RuntimeException("Capability store is busy")

/** Process-local preparation only. Copy-on-write gives rollback, not restart or multi-process durability. */
internal class InMemoryCapabilityStore(private val lockWaitMillis: Long = 1_000) : CapabilityStore {
    init { require(lockWaitMillis >= 0) }
    private val lock = ReentrantLock()
    private var operations = mutableMapOf<CapabilityOperationKey, CapabilityOperation>()
    private var currents = mutableMapOf<CapabilityOwner, ReminderCapabilityView>()
    private var observations = mutableMapOf<Pair<CapabilityOwner, Long>, ReminderCapabilityReport>()

    override fun <T> transaction(work: (CapabilityTransaction) -> T): T {
        if (!lock.tryLock(lockWaitMillis, TimeUnit.MILLISECONDS)) throw CapabilityStoreBusy()
        try {
            val nextOperations = operations.mapValuesTo(mutableMapOf()) { it.value.detached() }
            val nextCurrents = currents.mapValuesTo(mutableMapOf()) { it.value.detached() }
            val nextObservations = observations.mapValuesTo(mutableMapOf()) { it.value.detached() }
            val result = work(object : CapabilityTransaction {
                override fun operation(key: CapabilityOperationKey) = nextOperations[key]?.detached()
                override fun putOperation(key: CapabilityOperationKey, value: CapabilityOperation) {
                    nextOperations[key] = value.detached()
                }
                override fun current(owner: CapabilityOwner) = nextCurrents[owner]?.detached()
                override fun putCurrent(owner: CapabilityOwner, value: ReminderCapabilityView) {
                    nextCurrents[owner] = value.detached()
                }
                override fun observation(owner: CapabilityOwner, sequence: Long) =
                    nextObservations[owner to sequence]?.detached()
                override fun putObservation(owner: CapabilityOwner, report: ReminderCapabilityReport) {
                    nextObservations[owner to report.observationSequence] = report.detached()
                }
            })
            operations = nextOperations
            currents = nextCurrents
            observations = nextObservations
            return result
        } finally { lock.unlock() }
    }
}
