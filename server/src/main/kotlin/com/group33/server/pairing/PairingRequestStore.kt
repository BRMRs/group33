package com.group33.server.pairing

import com.group33.shared.contract.PairingRequestView
import java.util.UUID

internal data class PairingRequestKey(val actorId: String, val operationId: UUID)
internal class PairingRequestOperation(
    val installationId: String,
    val fingerprint: String,
    val receipt: PairingRequestView? = null,
    val rejection: PairingRequestResult.Rejected? = null,
) {
    override fun toString() = "PairingRequestOperation(REDACTED)"
}

/** Code consumption, request, quotas and receipt share the issuance transaction and its lock. */
internal interface PairingRequestStore : PairingCodeStore {
    fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T
}

internal interface PairingRequestTransaction : PairingCodeTransaction {
    fun requestOperation(key: PairingRequestKey): PairingRequestOperation?
    fun putRequestOperation(key: PairingRequestKey, value: PairingRequestOperation)
    fun unconsumedCode(hash: String, now: Long): StoredPairingCode?
    fun consume(code: StoredPairingCode, request: PairingRequestView)
    fun request(id: String, now: Long): PairingRequestView?
    fun installationAttempts(id: String): List<Long>
    fun putInstallationAttempts(id: String, times: List<Long>)
    fun addressAttempts(hash: String): List<Long>
    fun putAddressAttempts(hash: String, times: List<Long>)
}
