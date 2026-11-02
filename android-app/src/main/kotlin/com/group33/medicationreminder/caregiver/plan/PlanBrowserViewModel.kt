package com.group33.medicationreminder.caregiver.plan

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.MedicationView
import com.group33.shared.contract.ReminderPlanView
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

enum class PlanBrowserMessage { SELECT_ELDER, LOADING, ACCESS_UNKNOWN, REVOKED, UNAVAILABLE, NO_LOCAL_DATA, EMPTY, RESULTS }
enum class PlanPreviewMessage { SELECT_ELDER, LOADING, ACCESS_UNKNOWN, REVOKED, UNAVAILABLE, NO_LOCAL_DATA, WAITING_FOR_MATCHING_PLANS, EMPTY, RESULTS }

/** Confirmed records only; photoId is a reference, not permission to load photo bytes. */
data class PlanDetails(val plan: ReminderPlanView, val medication: MedicationView?)

data class PlanBrowserState(
    val scope: RepositoryScope? = null,
    val elderId: String? = null,
    val range: LocalDateRange? = null,
    val plans: Cached<List<ReminderPlanView>>? = null,
    val medications: Cached<List<MedicationView>>? = null,
    val preview: Cached<List<PlanPreview>>? = null,
    val access: AccessState = AccessState.UNKNOWN,
    val loading: Boolean = false,
    val observationFailed: Boolean = false,
    val selectedPlanId: String? = null,
) {
    val items: List<ReminderPlanView> get() = plans?.takeIf { it.hasLocalData }?.value.orEmpty()
    val details: PlanDetails? get() = items.find { it.id == selectedPlanId }?.let { plan ->
        PlanDetails(plan, medications?.takeIf { it.hasLocalData }?.value?.find { it.id == plan.medicationId })
    }
    // Separate collections can emit at different times. Do not attach old-revision candidates to a new plan.
    val candidates: List<PlanPreview> get() = preview?.takeIf { it.hasLocalData }?.value.orEmpty().filter { candidate ->
        items.any { it.id == candidate.occurrence.planId && it.revision == candidate.planRevision && it.active }
    }
    val isStale: Boolean get() = observationFailed || loading ||
        listOfNotNull(plans, medications, preview).any { it.isStale }
    /** A missing preview cache or temporarily unmatched revision is not an empty schedule. */
    val previewMessage: PlanPreviewMessage get() = when {
        elderId == null -> PlanPreviewMessage.SELECT_ELDER
        access == AccessState.REVOKED -> PlanPreviewMessage.REVOKED
        loading && preview == null -> PlanPreviewMessage.LOADING
        observationFailed && preview == null -> PlanPreviewMessage.UNAVAILABLE
        access != AccessState.AUTHORIZED -> PlanPreviewMessage.ACCESS_UNKNOWN
        preview?.hasLocalData != true || plans?.hasLocalData != true -> PlanPreviewMessage.NO_LOCAL_DATA
        candidates.size != preview.value.size -> PlanPreviewMessage.WAITING_FOR_MATCHING_PLANS
        candidates.isEmpty() -> PlanPreviewMessage.EMPTY
        else -> PlanPreviewMessage.RESULTS
    }
    val message: PlanBrowserMessage get() = when {
        elderId == null -> PlanBrowserMessage.SELECT_ELDER
        access == AccessState.REVOKED -> PlanBrowserMessage.REVOKED
        loading && plans == null -> PlanBrowserMessage.LOADING
        observationFailed && plans == null -> PlanBrowserMessage.UNAVAILABLE
        access != AccessState.AUTHORIZED -> PlanBrowserMessage.ACCESS_UNKNOWN
        plans?.hasLocalData != true -> PlanBrowserMessage.NO_LOCAL_DATA
        items.isEmpty() -> PlanBrowserMessage.EMPTY
        else -> PlanBrowserMessage.RESULTS
    }
}

/** Read-only consumer. Trusted host supplies the scoped repository and today's execution-zone date.
 * Time conversion and exclusion of frozen occurrences belong to observePreview, not this UI state.
 */
class PlanBrowserViewModel : ViewModel() {
    private data class Context(val repository: ReminderPlanRepository, val scope: RepositoryScope,
        val elderId: String, val range: LocalDateRange)
    private var context: Context? = null
    private var generation = Any()
    private var observation: Job? = null
    private val revoked = mutableSetOf<Pair<RepositoryScope, String>>()
    private val mutableState = MutableStateFlow(PlanBrowserState())
    val state = mutableState.asStateFlow()

    companion object {
        fun sevenDayRange(today: LocalDate) = LocalDateRange(today.toString(), today.plusDays(6).toString())
    }

    @MainThread
    fun bind(repository: ReminderPlanRepository, scope: RepositoryScope, elderId: String, today: LocalDate) {
        require(elderId.isNotBlank())
        val next = Context(repository, scope, elderId, sevenDayRange(today))
        clearContext()
        context = next
        val denied = (scope to elderId) in revoked
        mutableState.value = PlanBrowserState(scope, elderId, next.range,
            access = if (denied) AccessState.REVOKED else AccessState.UNKNOWN)
        if (!denied) retryObservation()
    }

    @MainThread
    fun clearContext() {
        generation = Any()
        observation?.cancel()
        context = null
        mutableState.value = PlanBrowserState()
    }

    @MainThread
    fun selectPlan(id: String?) {
        mutableState.value = state.value.copy(selectedPlanId = id?.takeIf { key -> state.value.items.any { it.id == key } })
    }

    /** Reopen observations, not a network refresh or a retry of a save operation. */
    @MainThread
    fun retryObservation() {
        val current = context ?: return
        if ((current.scope to current.elderId) in revoked) return
        generation = Any()
        val token = generation
        observation?.cancel()
        mutableState.value = state.value.copy(loading = true)
        // Access withdrawal must not wait for the other collections' first emissions.
        fun <T> Flow<Cached<T>>.guardAccess() = onEach { cached ->
            if (token === generation && cached.access != AccessState.AUTHORIZED) {
                mutableState.value = PlanBrowserState(current.scope, current.elderId, current.range, access = cached.access)
                if (cached.access == AccessState.REVOKED) {
                    revoked += current.scope to current.elderId
                    generation = Any()
                    observation?.cancel()
                }
            }
        }
        observation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var emitted = false
            try {
                combine(current.repository.observePlans(current.elderId).guardAccess(),
                    current.repository.observeMedications(current.elderId).guardAccess(),
                    current.repository.observePreview(current.elderId, current.range).guardAccess()) { plans, medications, preview ->
                    Triple(plans, medications, preview)
                }.collect { (plans, medications, preview) ->
                    if (token !== generation) return@collect
                    emitted = true
                    val accesses = listOf(plans.access, medications.access, preview.access)
                    if (accesses.any { it != AccessState.AUTHORIZED }) {
                        val access = if (AccessState.REVOKED in accesses) AccessState.REVOKED else AccessState.UNKNOWN
                        mutableState.value = PlanBrowserState(current.scope, current.elderId, current.range, access = access)
                        if (access == AccessState.REVOKED) {
                            revoked += current.scope to current.elderId
                            generation = Any()
                            observation?.cancel()
                        }
                    } else {
                        require(!plans.hasLocalData || plans.value.all { it.elderId == current.elderId })
                        require(!medications.hasLocalData || medications.value.all { it.elderId == current.elderId })
                        require(!preview.hasLocalData || preview.value.all {
                            val date = LocalDate.parse(it.occurrence.localDate)
                            !date.isBefore(LocalDate.parse(current.range.fromDate)) &&
                                !date.isAfter(LocalDate.parse(current.range.throughDate))
                        })
                        mutableState.value = PlanBrowserState(current.scope, current.elderId, current.range,
                            plans, medications, preview, AccessState.AUTHORIZED,
                            selectedPlanId = state.value.selectedPlanId?.takeIf { id ->
                                plans.hasLocalData && plans.value.any { it.id == id }
                            })
                    }
                }
                if (!emitted && token === generation) failObservation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token === generation) failObservation()
            }
        }
        observation?.start()
    }

    private fun failObservation() {
        mutableState.value = state.value.copy(loading = false, observationFailed = true)
    }

    override fun onCleared() { clearContext() }
}
