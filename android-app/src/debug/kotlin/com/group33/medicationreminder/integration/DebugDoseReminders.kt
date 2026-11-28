package com.group33.medicationreminder.integration

import android.content.Context
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.TrustedSessionContext
import com.group33.medicationreminder.core.reminder.*
import com.group33.medicationreminder.elder.*
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LocalDoseUpdate(
    val occurrences: List<LocalElderOccurrence>,
    val hasPlanSnapshot: Boolean,
    val actionRecorded: Boolean,
)

/** Debug host adapter: real cached plans feed the durable S1 runtime; there is no second alarm path. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DebugDoseReminders(
    private val app: Context,
    private val repo: DebugChainRepository,
    private val clock: Clock = Clock.systemUTC(),
) : LiveElderDoses {
    private val mutableState = MutableStateFlow(ElderDoseScreen())
    override val state = mutableState.asStateFlow()
    private val gate = Mutex()
    private val coordinator = ReminderCoordinator(app)
    private var lastExecutionScopeId: String? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(repo.services.auth.observeSession(), repo.services.trusted) { session, trusted ->
                trusted?.takeIf { session.readiness == AuthReadiness.READY &&
                    session.user?.role == UserRole.ELDER && session.user.id == trusted.scope.userId }
            }.distinctUntilChanged().flatMapLatest { trusted ->
                if (trusted == null) flowOf(Unit)
                else repo.observePlans(trusted.scope.userId).map { Unit }.onStart { emit(Unit) }
            }.collect { refresh() }
        }
        scope.launch {
            coordinator.observeActive().distinctUntilChanged().drop(1).collect { refresh() }
        }
    }

    override suspend fun refresh() {
        gate.withLock { update() }
    }

    override suspend fun record(
        session: TrustedSessionContext,
        group: ElderDoseGroup,
        kind: DoseActionKind,
    ): Boolean = gate.withLock {
        if (session != repo.services.trusted.value) return@withLock false
        val runtime = runtimeGroup(session, group.occurrences) ?: return@withLock false
        val actionId = UUID.randomUUID().toString()
        val recordedAt = clock.millis()
        val runtimeKind = when (kind) {
            DoseActionKind.TAKEN -> ReminderActionKind.TAKEN
            DoseActionKind.REMIND_LATER -> ReminderActionKind.SNOOZE
        }
        if (!coordinator.recordAction(runtime.groupId, actionId, runtimeKind, recordedAt)) return@withLock false
        persistRuntimeAction(session, group, kind, actionId, recordedAt)
        true // The S1 Room action/outbox commit is the local success boundary.
    }

    suspend fun dispatchRuntimeAction(
        action: ReminderActionRow,
        registration: ReminderGroupWithOccurrences,
    ): ReminderDispatchResult = gate.withLock {
        val kind = when (ReminderActionKind.valueOf(action.kind)) {
            ReminderActionKind.TAKEN -> DoseActionKind.TAKEN
            ReminderActionKind.SNOOZE -> DoseActionKind.REMIND_LATER
            ReminderActionKind.HELP -> return@withLock ReminderDispatchResult.PERMANENT_FAILURE
        }
        val trusted = repo.services.trusted.value ?: return@withLock ReminderDispatchResult.RETRY
        // A different active account/environment must wait, never discard or rebind old work.
        if (trusted.scope.userId != registration.group.elderId ||
            repo.executionScopeId(trusted) != registration.group.executionScopeId) {
            return@withLock ReminderDispatchResult.RETRY
        }
        val atAction = Clock.fixed(Instant.ofEpochMilli(action.recordedAtEpochMillis), ZoneOffset.UTC)
        val snapshot = (repo.localElderDoses(trusted, atAction) as? Outcome.Success)?.value
            ?: return@withLock ReminderDispatchResult.RETRY
        val keys = registration.occurrences.map { OccurrenceKey(it.planId, it.localDate, it.localTime) }
        val members = snapshot.occurrences.filter { it.key in keys }
        if (members.map { it.key }.toSet() != keys.toSet()) return@withLock ReminderDispatchResult.PERMANENT_FAILURE
        val window = ReminderTimingPolicy(atAction).evaluate(
            ReminderWindow(Instant.ofEpochMilli(registration.group.plannedAtEpochMillis)),
        )
        val group = ElderDoseGroup(registration.group.plannedAtEpochMillis, members, window.nextSnoozeAt != null)
        val persisted = persistRuntimeAction(
            trusted, group, kind, action.actionId, action.recordedAtEpochMillis,
        )
        if (!persisted) return@withLock ReminderDispatchResult.RETRY
        if (repo.syncLocalElderActions(trusted) !is Outcome.Success) return@withLock ReminderDispatchResult.RETRY
        // Draining a batch can succeed after moving rejected rows to needs-attention.
        // Only this original action's accepted receipt permits its runtime ACK.
        val result = (repo.localElderActionDispatchResult(trusted, action.actionId) as? Outcome.Success)?.value
            ?: return@withLock ReminderDispatchResult.RETRY
        update()
        result
    }

    private suspend fun update() {
        val trusted = repo.services.trusted.value
        val user = repo.services.auth.observeSession().first().user
        if (trusted == null || user?.role != UserRole.ELDER || user.id != trusted.scope.userId) {
            lastExecutionScopeId?.let { coordinator.cancelScope(it) }
            lastExecutionScopeId = null
            mutableState.value = ElderDoseScreen()
            return
        }
        val scopeId = repo.executionScopeId(trusted) ?: return
        if (lastExecutionScopeId != null && lastExecutionScopeId != scopeId) {
            coordinator.cancelScope(checkNotNull(lastExecutionScopeId))
        }
        lastExecutionScopeId = scopeId
        val result = repo.localElderDoses(trusted, clock)
        if (trusted != repo.services.trusted.value) return
        val update = (result as? Outcome.Success)?.value
        update?.occurrences.orEmpty().groupBy { it.plannedAt }.values.forEach { members ->
            val runtime = runtimeGroup(trusted, members) ?: return@forEach
            if (members.any { it.takenAt != null }) {
                coordinator.cancel(runtime.groupId)
            } else {
                coordinator.register(runtime)
                members.mapNotNull { it.snoozedUntil }.distinct().singleOrNull()?.let { until ->
                    coordinator.restoreSnooze(runtime.groupId, until, clock.millis())
                }
            }
        }
        val due = update?.let { dueElderDose(it.occurrences, clock) }
        val capability = ReminderCapability.snapshot(app)
        mutableState.value = ElderDoseScreen(
            trusted,
            due,
            capability.notificationsUsable,
            update != null && (update.hasPlanSnapshot || update.occurrences.isNotEmpty()),
        )
    }

    private suspend fun persistRuntimeAction(
        trusted: TrustedSessionContext,
        group: ElderDoseGroup,
        kind: DoseActionKind,
        actionId: String,
        recordedAt: Long,
    ): Boolean {
        val result = repo.localElderDoses(
            trusted,
            Clock.fixed(Instant.ofEpochMilli(recordedAt), ZoneOffset.UTC),
            group to kind,
            actionId,
            recordedAt,
        )
        val update = (result as? Outcome.Success)?.value ?: return false
        val due = dueElderDose(update.occurrences, clock)
        val capability = ReminderCapability.snapshot(app)
        mutableState.value = ElderDoseScreen(
            trusted, due, capability.notificationsUsable,
            update.hasPlanSnapshot || update.occurrences.isNotEmpty(),
        )
        return update.actionRecorded
    }

    private fun runtimeGroup(
        trusted: TrustedSessionContext,
        occurrences: List<LocalElderOccurrence>,
    ): ReminderGroup? {
        if (occurrences.isEmpty()) return null
        val scopeId = repo.executionScopeId(trusted) ?: return null
        return ReminderGroup(
            scopeId,
            trusted.scope.userId,
            occurrences.map { occurrence ->
                ReminderOccurrence(
                    trusted.scope.userId,
                    occurrence.medication.displayName,
                    occurrence.medication.dosageText,
                    repo.generationEvidence(trusted, occurrence) ?: return null,
                )
            },
        )
    }

}
