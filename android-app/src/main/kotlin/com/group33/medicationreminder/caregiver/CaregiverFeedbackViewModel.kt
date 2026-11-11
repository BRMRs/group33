package com.group33.medicationreminder.caregiver

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.Cached
import com.group33.medicationreminder.core.data.contract.CaregiverDashboardRepository
import com.group33.medicationreminder.core.data.contract.ElderOverview
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.core.data.contract.SyncCompletion
import com.group33.medicationreminder.core.data.contract.SyncSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Local screen state; cache values and sync timestamps come only from the Repository observation. */
data class CaregiverFeedbackState(
    val elderId: String?,
    val range: LocalDateRange?,
    val scope: RepositoryScope?,
    val access: AccessState = AccessState.UNKNOWN,
    val overview: Cached<ElderOverview>? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val refreshResult: Outcome<SyncSummary>? = null,
    val observationFailed: Boolean = false,
    val refreshFailedUnexpectedly: Boolean = false,
) {
    val refreshError: RepositoryError?
        get() = when (val result = refreshResult) {
            is Outcome.Failure -> result.error
            is Outcome.Success -> result.value.error
            null -> null
        }

    val hasRefreshFailure: Boolean
        get() = refreshFailedUnexpectedly || when (val result = refreshResult) {
            is Outcome.Failure -> true
            is Outcome.Success -> result.value.completion != SyncCompletion.COMPLETE || result.value.error != null
            null -> false
        }

    val isRefreshComplete: Boolean
        get() = access == AccessState.AUTHORIZED && !isRefreshing && !hasRefreshFailure && !observationFailed &&
            (refreshResult as? Outcome.Success)?.value?.completion == SyncCompletion.COMPLETE

    /** Presentation freshness does not rewrite the Repository's Cached object or its timestamp. */
    val isStale: Boolean
        get() = overview?.let { it.hasLocalData && (it.isStale || hasRefreshFailure || observationFailed) } ?: false
}

/** The host supplies a session-bound Repository/scope, never a user-selected HTTP principal. */
class CaregiverFeedbackViewModel(
    repository: CaregiverDashboardRepository,
    elderId: String,
    range: LocalDateRange,
    scope: RepositoryScope,
) : ViewModel() {
    private data class Selection(
        val repository: CaregiverDashboardRepository,
        val scope: RepositoryScope,
        val elderId: String,
        val range: LocalDateRange,
    )

    private var selection: Selection? = Selection(repository, scope, elderId, range)
    private var generation = Any()
    private val revoked = mutableSetOf<Pair<RepositoryScope, String>>()
    private val mutableState = MutableStateFlow(CaregiverFeedbackState(elderId, range, scope))
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var refreshJob: Job? = null

    init {
        require(elderId.isNotBlank())
        observeOverview()
    }

    /** Selection/session replacement invalidates all work, even when the Elder ID happens to be unchanged. */
    @MainThread
    fun selectContext(
        repository: CaregiverDashboardRepository,
        scope: RepositoryScope,
        elderId: String,
        range: LocalDateRange,
    ) {
        require(elderId.isNotBlank())
        generation = Any()
        selection = Selection(repository, scope, elderId, range)
        observation?.cancel()
        refreshJob?.cancel()
        observation = null
        refreshJob = null
        val denied = (scope to elderId) in revoked
        mutableState.value = CaregiverFeedbackState(
            elderId, range, scope,
            access = if (denied) AccessState.REVOKED else AccessState.UNKNOWN,
            isLoading = !denied,
        )
        if (!denied) observeOverview()
    }

    private fun isCurrent(token: Any) = token === generation

    /** Called by the host when signed out or when a trusted session/selection is not ready. */
    @MainThread
    fun clearContext() {
        generation = Any()
        selection = null
        observation?.cancel()
        refreshJob?.cancel()
        observation = null
        refreshJob = null
        mutableState.value = CaregiverFeedbackState(null, null, null, isLoading = false)
    }

    private fun revoke(current: Selection) {
        revoked += current.scope to current.elderId
        generation = Any()
        mutableState.value = CaregiverFeedbackState(
            current.elderId, current.range, current.scope, access = AccessState.REVOKED, isLoading = false,
        )
        observation?.cancel()
        refreshJob?.cancel()
    }

    private fun observeOverview() {
        val current = selection ?: return
        val token = generation
        mutableState.update { it.copy(isLoading = it.overview == null) }
        observation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var emitted = false
            try {
                if (!isCurrent(token)) return@launch
                current.repository.observeOverview(current.elderId, current.range).collect { cached ->
                    if (!isCurrent(token)) return@collect
                    emitted = true
                    // Access belongs to this trusted subscription, not the payload's Elder ID.
                    if (cached.access == AccessState.REVOKED) {
                        revoke(current)
                    } else if (cached.value.elder.id != current.elderId) {
                        mutableState.update { it.copy(isLoading = false, observationFailed = true) }
                    } else {
                        mutableState.update {
                            it.copy(
                                overview = cached.takeIf { it.access == AccessState.AUTHORIZED },
                                access = cached.access,
                                isLoading = false,
                                observationFailed = false,
                            )
                        }
                    }
                }
                if (!emitted && isCurrent(token)) {
                    mutableState.update { it.copy(isLoading = false, observationFailed = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No guessed OFFLINE/storage diagnosis and no raw exception text in user-facing state.
                if (isCurrent(token)) mutableState.update { it.copy(isLoading = false, observationFailed = true) }
            }
        }
        observation?.start()
    }

    @MainThread
    fun refresh() {
        val current = selection ?: return
        if (mutableState.value.isRefreshing || mutableState.value.access == AccessState.REVOKED) return
        if (observation?.isActive != true) observeOverview()
        if (selection !== current || mutableState.value.access == AccessState.REVOKED) return
        val token = generation
        mutableState.update { it.copy(isRefreshing = true) }
        refreshJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!isCurrent(token)) return@launch
                val result = current.repository.refresh(current.elderId, current.range)
                if (isCurrent(token)) {
                    if (result is Outcome.Success &&
                        (result.value.scope != current.scope || result.value.elderId != current.elderId)
                    ) {
                        mutableState.update { it.copy(refreshResult = null, refreshFailedUnexpectedly = true) }
                    } else {
                        mutableState.update { it.copy(refreshResult = result, refreshFailedUnexpectedly = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(token)) {
                    mutableState.update { it.copy(refreshResult = null, refreshFailedUnexpectedly = true) }
                }
            } finally {
                if (isCurrent(token)) mutableState.update { it.copy(isRefreshing = false) }
            }
        }
        refreshJob?.start()
    }
}
