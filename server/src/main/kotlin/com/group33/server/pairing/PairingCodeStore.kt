package com.group33.server.pairing

import com.group33.shared.contract.PairingCodeView
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
    fun replaceActiveCode(value: StoredPairingCode)
    fun hasEffectiveHash(hash: String, now: Long): Boolean
    fun issuanceTimes(elderId: String): List<Long>
    fun putIssuanceTimes(elderId: String, times: List<Long>)
}

internal class CodeStoreBusy : RuntimeException("Pairing code store is busy")

/** Process-local prototype. A bounded global lock and copy-on-write staging provide real rollback. */
internal class InMemoryPairingCodeStore(private val lockWaitMillis: Long = 1_000) : PairingCodeStore {
    init { require(lockWaitMillis >= 0) }
    private val lock = ReentrantLock()
    private var operations = mutableMapOf<CodeIssueKey, CodeIssueOperation>()
    private var codes = mutableMapOf<String, StoredPairingCode>()
    private var times = mutableMapOf<String, List<Long>>()

    override fun <T> transaction(work: (PairingCodeTransaction) -> T): T {
        if (!lock.tryLock(lockWaitMillis, TimeUnit.MILLISECONDS)) throw CodeStoreBusy()
        try {
            val stagedOperations = operations.toMutableMap()
            val stagedCodes = codes.toMutableMap()
            val stagedTimes = times.toMutableMap()
            val result = work(object : PairingCodeTransaction {
                override fun operation(key: CodeIssueKey) = stagedOperations[key]
                override fun putOperation(key: CodeIssueKey, value: CodeIssueOperation) { stagedOperations[key] = value }
                override fun activeCode(elderId: String) = stagedCodes[elderId]
                override fun replaceActiveCode(value: StoredPairingCode) { stagedCodes[value.elderId] = value }
                override fun hasEffectiveHash(hash: String, now: Long) =
                    stagedCodes.values.any { it.hash == hash && it.isEffectiveAt(now) }
                override fun issuanceTimes(elderId: String) = stagedTimes[elderId]?.toList().orEmpty()
                override fun putIssuanceTimes(elderId: String, times: List<Long>) { stagedTimes[elderId] = times.toList() }
            })
            operations = stagedOperations
            codes = stagedCodes
            times = stagedTimes
            return result
        } finally {
            lock.unlock()
        }
    }
}
