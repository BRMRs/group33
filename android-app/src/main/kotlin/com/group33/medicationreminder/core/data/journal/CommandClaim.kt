package com.group33.medicationreminder.core.data.journal

import com.group33.medicationreminder.core.data.contract.CommandContext
import com.group33.medicationreminder.core.data.contract.CacheState
import com.group33.medicationreminder.core.data.contract.CommandResultRef
import com.group33.medicationreminder.core.data.contract.RepositoryError

enum class ClaimMode { EXPLICIT, RESUME_UNKNOWN }

/** Host-provided monotonic time. bootSession must be stable across processes in one boot. */
data class JournalTime(val bootSession: String, val elapsedMillis: Long) {
    init {
        require(bootSession.isNotBlank())
        require(elapsedMillis >= 0)
    }
}

/** For the internal feature transport adapter only; deliberately has no payload-bearing toString. */
class CommandClaim internal constructor(
    val command: CommandContext,
    internal val token: String,
    payload: ByteArray,
) {
    private val payload = payload.copyOf()
    fun payloadBytes(): ByteArray = payload.copyOf()
}

sealed interface CommandCompletion {
    data class Confirmed(val resultRef: CommandResultRef, val cacheState: CacheState) : CommandCompletion
    data object Unknown : CommandCompletion
    data class Rejected(val error: RepositoryError.Remote) : CommandCompletion
}
