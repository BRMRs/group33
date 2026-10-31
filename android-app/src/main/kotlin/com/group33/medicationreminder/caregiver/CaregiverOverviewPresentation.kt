package com.group33.medicationreminder.caregiver

import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.shared.contract.ReminderCapabilityView

enum class OverviewMessage { SELECT_ELDER, LOADING, ACCESS_UNKNOWN, REVOKED, UNAVAILABLE, NO_LOCAL_DATA, EMPTY, RESULTS }
enum class OverviewRefreshMessage {
    NONE, REFRESHING, COMPLETE, FAILED, OFFLINE,
    STORAGE_UNAVAILABLE, INVALID_INPUT, RANGE_TOO_LARGE, SERVICE_UNAVAILABLE, RETRY_LATER,
}

data class OverviewContent(
    val elderName: String,
    val events: List<OverviewEventRow>,
    val capability: ReminderCapabilityView?,
    val lastSuccessfulSyncAtEpochMillis: Long?,
    val isStale: Boolean,
)

/** Display policy only: it neither changes the cache nor infers medication outcomes from connectivity. */
data class CaregiverOverviewPresentation(
    val message: OverviewMessage,
    val content: OverviewContent?,
    val refreshMessage: OverviewRefreshMessage,
    val canRefresh: Boolean,
    val canOpenHistory: Boolean,
) {
    companion object {
        fun from(state: CaregiverFeedbackState): CaregiverOverviewPresentation {
            val hasSelection = state.elderId != null && state.range != null && state.scope != null
            val inaccessible = when (val error = state.refreshError) {
                is RepositoryError.Remote -> error.error.code in setOf("RESOURCE_NOT_ACCESSIBLE", "UNAUTHENTICATED", "ROLE_NOT_ALLOWED")
                is RepositoryError.Local -> error.code == LocalFailureCode.SESSION_NOT_READY
                null -> false
            }
            val cached = state.overview
            val authorized = hasSelection && !inaccessible && state.access == AccessState.AUTHORIZED &&
                cached?.access == AccessState.AUTHORIZED && cached.value.elder.id == state.elderId
            val content = cached?.takeIf { authorized && it.hasLocalData }?.let {
                OverviewContent(
                    it.value.elder.displayName,
                    it.value.events.map { event -> event.toCaregiverEventRow() },
                    it.value.capability, it.lastSuccessfulSyncAtEpochMillis, state.isStale,
                )
            }
            val message = when {
                !hasSelection -> OverviewMessage.SELECT_ELDER
                state.access == AccessState.REVOKED -> OverviewMessage.REVOKED
                inaccessible -> OverviewMessage.UNAVAILABLE
                state.isLoading && content == null -> OverviewMessage.LOADING
                !authorized && (state.observationFailed || state.hasRefreshFailure) -> OverviewMessage.UNAVAILABLE
                !authorized -> OverviewMessage.ACCESS_UNKNOWN
                content == null -> OverviewMessage.NO_LOCAL_DATA
                content.events.isEmpty() -> OverviewMessage.EMPTY
                else -> OverviewMessage.RESULTS
            }
            val refresh = when {
                !hasSelection || state.access == AccessState.REVOKED || inaccessible -> OverviewRefreshMessage.NONE
                state.isRefreshing -> OverviewRefreshMessage.REFRESHING
                (state.refreshError as? RepositoryError.Local)?.code == LocalFailureCode.OFFLINE -> OverviewRefreshMessage.OFFLINE
                state.hasRefreshFailure || state.observationFailed -> when (val error = state.refreshError) {
                    is RepositoryError.Local -> when (error.code) {
                        LocalFailureCode.STORAGE_UNAVAILABLE -> OverviewRefreshMessage.STORAGE_UNAVAILABLE
                        LocalFailureCode.INVALID_INPUT -> OverviewRefreshMessage.INVALID_INPUT
                        else -> OverviewRefreshMessage.FAILED
                    }
                    is RepositoryError.Remote -> when (error.error.code) {
                        "SNAPSHOT_RANGE_TOO_LARGE" -> OverviewRefreshMessage.RANGE_TOO_LARGE
                        "VALIDATION_FAILED" -> OverviewRefreshMessage.INVALID_INPUT
                        "SERVICE_UNAVAILABLE" -> OverviewRefreshMessage.SERVICE_UNAVAILABLE
                        "RETRY_LATER" -> OverviewRefreshMessage.RETRY_LATER
                        else -> OverviewRefreshMessage.FAILED
                    }
                    null -> OverviewRefreshMessage.FAILED
                }
                state.isRefreshComplete -> OverviewRefreshMessage.COMPLETE
                else -> OverviewRefreshMessage.NONE
            }
            return CaregiverOverviewPresentation(
                message, content, refresh,
                canRefresh = hasSelection && state.access != AccessState.REVOKED && !state.isRefreshing,
                canOpenHistory = authorized,
            )
        }
    }
}
