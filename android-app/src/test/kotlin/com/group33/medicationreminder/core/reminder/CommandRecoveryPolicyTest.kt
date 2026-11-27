package com.group33.medicationreminder.core.reminder

import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.model.ApiError
import org.junit.Assert.*
import org.junit.Test

class CommandRecoveryPolicyTest {
    private val scope = RepositoryScope("fictional-cloud", "fictional-install", "fictional-user")
    private val record = OnlineCommandRecord(
        operationId = "00000000-0000-4000-8000-000000000012", scope = scope,
        commandKind = OnlineCommandKind.CONFIRM_PLAN, targetId = "fictional-plan",
        state = OnlineCommandState.AWAITING_CONFIRMATION, resultRef = null, cacheState = null, error = null,
    )

    @Test fun `R04 unknown completion resumes original identity rather than creating another intent`() {
        val decision = CommandRecoveryPolicy.decide(scope, record)
        assertEquals(CommandRecoveryStep.ResumeOriginal("00000000-0000-4000-8000-000000000012"), decision)
    }

    @Test fun `R04 unsent and definitively rejected operations never automatically replay`() {
        val rejection = RepositoryError.Remote(ApiError("PHOTO_NOT_READY", "fictional", true, "fictional-request"))
        for (state in listOf(OnlineCommandState.NOT_SUBMITTED, OnlineCommandState.REJECTED)) {
            assertEquals(CommandRecoveryStep.ExplicitActionRequired,
                CommandRecoveryPolicy.decide(scope, record.copy(state = state, error = rejection)))
        }
    }

    @Test fun `R04 confirmed remote success with unknown or pending cache only refreshes cache`() {
        for (cache in listOf(null, CacheState.REFRESH_REQUIRED)) {
            assertEquals(CommandRecoveryStep.RefreshAcceptedCache("00000000-0000-4000-8000-000000000012"),
                CommandRecoveryPolicy.decide(scope, record.copy(state = OnlineCommandState.CONFIRMED, cacheState = cache)))
        }
    }

    @Test fun `R04 confirmed result already applied requires no work`() {
        assertEquals(CommandRecoveryStep.Complete, CommandRecoveryPolicy.decide(scope,
            record.copy(state = OnlineCommandState.CONFIRMED, cacheState = CacheState.APPLIED)))
    }

    @Test fun `R01 R04 environment installation and user changes reject old recovery at every stage`() {
        for (newScope in listOf(scope.copy(environmentId = "fictional-lan"),
            scope.copy(installationId = "other-install"), scope.copy(userId = "other-user"))) {
            for (state in OnlineCommandState.entries) {
                assertEquals(CommandRecoveryStep.ScopeMismatch,
                    CommandRecoveryPolicy.decide(newScope, record.copy(state = state)))
            }
        }
    }
}
