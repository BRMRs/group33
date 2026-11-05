package com.group33.medicationreminder.caregiver

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.Cached
import com.group33.medicationreminder.core.data.contract.DoseEventRepository
import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.UserView
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryRangeError { INVALID_DATE, REVERSED, TOO_MANY_DATES }
enum class HistoryMessage { SELECT_ELDER, LOADING, ACCESS_UNKNOWN, REVOKED, UNAVAILABLE, NO_LOCAL_DATA, EMPTY, RESULTS }

data class CaregiverHistoryState(
    val elder: UserView?,
    val scope: RepositoryScope?,
    val range: LocalDateRange?,
    val cached: Cached<List<DoseEventState>>? = null,
    val access: AccessState = AccessState.UNKNOWN,
    val isLoading: Boolean = true,
    val observationFailed: Boolean = false,
    val rangeError: HistoryRangeError? = null,
    val selectedOccurrence: OccurrenceKey? = null,
) {
    val authorized: Boolean get() = elder != null && scope != null && range != null &&
        access == AccessState.AUTHORIZED && cached?.access == AccessState.AUTHORIZED
    val events: List<DoseEventState> get() = cached?.takeIf { authorized && it.hasLocalData }?.value.orEmpty()
    val selectedEvent: DoseEventState? get() = events.find { it.occurrence == selectedOccurrence }
    val isStale: Boolean get() = authorized && cached?.hasLocalData == true && (cached.isStale || observationFailed)
    val message: HistoryMessage get() = when {
        elder == null || scope == null -> HistoryMessage.SELECT_ELDER
        access == AccessState.REVOKED -> HistoryMessage.REVOKED
        isLoading -> HistoryMessage.LOADING
        observationFailed && !authorized -> HistoryMessage.UNAVAILABLE
        !authorized -> HistoryMessage.ACCESS_UNKNOWN
        cached?.hasLocalData != true -> HistoryMessage.NO_LOCAL_DATA
        events.isEmpty() -> HistoryMessage.EMPTY
        else -> HistoryMessage.RESULTS
    }
}

/** Read-only history. The host supplies the trusted Elder/session and an already bound Repository. */
class CaregiverHistoryViewModel(
    repository: DoseEventRepository,
    scope: RepositoryScope,
    elder: UserView,
    range: LocalDateRange,
) : ViewModel() {
    private data class Selection(val repository: DoseEventRepository, val scope: RepositoryScope, val elder: UserView, val range: LocalDateRange)
    private var selection: Selection? = null
    private var generation = Any()
    private var observation: Job? = null
    private val revoked = mutableSetOf<Pair<RepositoryScope, String>>()
    private val mutableState = MutableStateFlow(CaregiverHistoryState(null, null, null, isLoading = false))
    val state = mutableState.asStateFlow()

    init { selectContext(repository, scope, elder, range) }

    companion object {
        // REPOSITORY_INTERFACES / API_CONTRACT: dates are inclusive, not retention or purge limits.
        const val MAX_DATES = 90L

        /** Caller computes today in the Elder's execution time zone; no device-zone guess here. */
        fun defaultRange(today: LocalDate) = LocalDateRange(today.minusDays(29).toString(), today.toString())

        private fun tooLong(range: LocalDateRange) =
            ChronoUnit.DAYS.between(LocalDate.parse(range.fromDate), LocalDate.parse(range.throughDate)) + 1 > MAX_DATES
    }

    @MainThread
    fun selectContext(repository: DoseEventRepository, scope: RepositoryScope, elder: UserView, range: LocalDateRange) {
        require(elder.id.isNotBlank())
        generation = Any()
        observation?.cancel()
        selection = Selection(repository, scope, elder, range)
        val denied = (scope to elder.id) in revoked
        val invalid = tooLong(range)
        mutableState.value = CaregiverHistoryState(
            elder, scope, range,
            access = if (denied) AccessState.REVOKED else AccessState.UNKNOWN,
            isLoading = !denied && !invalid,
            rangeError = if (invalid) HistoryRangeError.TOO_MANY_DATES else null,
        )
        if (!denied && !invalid) observe()
    }

    /** Invalid drafts keep the applied range/cache; they neither query nor delete history. */
    @MainThread
    fun applyRange(fromText: String, throughText: String): Boolean {
        val current = selection ?: return false
        val from: LocalDate
        val through: LocalDate
        try {
            from = LocalDate.parse(fromText.trim())
            through = LocalDate.parse(throughText.trim())
        } catch (_: DateTimeParseException) {
            mutableState.update { it.copy(rangeError = HistoryRangeError.INVALID_DATE) }
            return false
        }
        val error = when {
            through.isBefore(from) -> HistoryRangeError.REVERSED
            ChronoUnit.DAYS.between(from, through) + 1 > MAX_DATES -> HistoryRangeError.TOO_MANY_DATES
            else -> null
        }
        if (error != null) {
            mutableState.update { it.copy(rangeError = error) }
            return false
        }
        selectContext(current.repository, current.scope, current.elder, LocalDateRange(from.toString(), through.toString()))
        return true
    }

    @MainThread
    fun clearContext() {
        generation = Any()
        selection = null
        observation?.cancel()
        mutableState.value = CaregiverHistoryState(null, null, null, isLoading = false)
    }

    /** Reopens the local observation only; this is not a server refresh or an action retry. */
    @MainThread
    fun retryObservation() {
        val current = selection ?: return
        if (mutableState.value.access == AccessState.REVOKED || tooLong(current.range)) return
        generation = Any()
        observation?.cancel()
        observe()
    }

    @MainThread
    fun selectEvent(occurrence: OccurrenceKey) {
        if (mutableState.value.events.any { it.occurrence == occurrence }) {
            mutableState.update { it.copy(selectedOccurrence = occurrence) }
        }
    }

    @MainThread
    fun closeDetails() { mutableState.update { it.copy(selectedOccurrence = null) } }

    private fun observe() {
        val current = selection ?: return
        val token = generation
        val from = LocalDate.parse(current.range.fromDate)
        val through = LocalDate.parse(current.range.throughDate)
        mutableState.update { it.copy(isLoading = it.cached == null) }
        observation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var emitted = false
            try {
                if (token !== generation) return@launch
                current.repository.observeEvents(current.elder.id, current.range).collect { cached ->
                    if (token !== generation) return@collect
                    emitted = true
                    if (cached.access == AccessState.REVOKED) {
                        revoked += current.scope to current.elder.id
                        generation = Any()
                        mutableState.value = CaregiverHistoryState(current.elder, current.scope, current.range,
                            access = AccessState.REVOKED, isLoading = false)
                        observation?.cancel()
                    } else if (cached.access != AccessState.AUTHORIZED) {
                        // Access withdrawal wins even when the accompanying old payload is malformed.
                        mutableState.update { it.copy(cached = null, access = cached.access,
                            isLoading = false, observationFailed = false, selectedOccurrence = null) }
                    } else {
                        // A date filter never silently truncates an oversized or mis-scoped adapter result.
                        require(!cached.hasLocalData || cached.value.all {
                            val date = LocalDate.parse(it.occurrence.localDate)
                            !date.isBefore(from) && !date.isAfter(through)
                        })
                        mutableState.update {
                            it.copy(
                                cached = cached.takeIf { it.access == AccessState.AUTHORIZED },
                                access = cached.access, isLoading = false, observationFailed = false,
                                selectedOccurrence = it.selectedOccurrence.takeIf { key -> cached.access == AccessState.AUTHORIZED &&
                                    cached.hasLocalData && cached.value.any { event -> event.occurrence == key } },
                            )
                        }
                    }
                }
                if (!emitted && token === generation) mutableState.update { it.copy(isLoading = false, observationFailed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token === generation) mutableState.update { it.copy(isLoading = false, observationFailed = true) }
            }
        }
        observation?.start()
    }
}
