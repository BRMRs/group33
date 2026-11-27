package com.group33.medicationreminder.core.data.contract

sealed interface CommandRecoveryStep {
    data class ResumeOriginal(val operationId: String) : CommandRecoveryStep
    data class RefreshAcceptedCache(val operationId: String) : CommandRecoveryStep
    data object Complete : CommandRecoveryStep
    data object ExplicitActionRequired : CommandRecoveryStep
    data object ScopeMismatch : CommandRecoveryStep
}

/** Recovery decision for the existing public command journal contract. Never submits a write.
 * The real adapter must authenticate, bind the original payload and recheck current access.
 */
object CommandRecoveryPolicy {
    fun decide(currentScope: RepositoryScope, record: OnlineCommandRecord): CommandRecoveryStep {
        if (currentScope != record.scope) return CommandRecoveryStep.ScopeMismatch
        return when (record.state) {
            OnlineCommandState.NOT_SUBMITTED, OnlineCommandState.REJECTED -> CommandRecoveryStep.ExplicitActionRequired
            OnlineCommandState.AWAITING_CONFIRMATION -> CommandRecoveryStep.ResumeOriginal(record.operationId)
            OnlineCommandState.CONFIRMED -> if (record.cacheState == CacheState.APPLIED) CommandRecoveryStep.Complete
                else CommandRecoveryStep.RefreshAcceptedCache(record.operationId)
        }
    }
}
