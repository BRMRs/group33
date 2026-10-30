package com.group33.medicationreminder.elder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.AuthRepository
import com.group33.medicationreminder.core.data.contract.DoseEventRepository
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.UserRole
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ElderFeedback {
    data object ActionCompleted : ElderFeedback
    data class RemindLaterNext(val timeText: String) : ElderFeedback
    data object RemindLaterWindowEnding : ElderFeedback
    data object NotRecorded : ElderFeedback
    data object LoginFailed : ElderFeedback
    data object WindowClosed : ElderFeedback
}

class ElderHomeViewModel(
    private val authRepository: AuthRepository,
    private val doseRepository: DoseEventRepository,
    private val clock: Clock,
    private val anonymousDisplayName: String,
    private val dueMedicationName: String,
    private val upcomingMedicationName: String,
    private val dosageText: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialPreviewState())
    val uiState: StateFlow<ElderHomeUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { ensureAnonymousElderSession() }
    }

    fun onTaken() = record(DoseActionKind.TAKEN)

    fun onRemindLater() = record(DoseActionKind.REMIND_LATER)

    fun clearFeedback() {
        mutableState.update { it.copy(feedback = null, loginError = false) }
    }

    private suspend fun ensureAnonymousElderSession() {
        val session = authRepository.observeSession().first()
        if (session.readiness == AuthReadiness.READY && session.user?.role == UserRole.ELDER) {
            mutableState.update {
                it.copy(sessionDisplayName = session.user.displayName, loginError = false)
            }
            return
        }
        when (val result = authRepository.login(UserRole.ELDER, anonymousDisplayName)) {
            is Outcome.Success -> mutableState.update {
                it.copy(sessionDisplayName = result.value.displayName, loginError = false)
            }
            is Outcome.Failure -> mutableState.update {
                it.copy(loginError = true, feedback = ElderFeedback.LoginFailed)
            }
        }
    }

    private fun record(kind: DoseActionKind) {
        val due = mutableState.value.due ?: return
        val now = clock.millis()
        val withinWindow = ElderDuePreviewCatalog.canAcceptElderAction(
            status = due.status,
            nowEpochMillis = now,
            plannedAtEpochMillis = due.plannedAtEpochMillis,
            responseDeadlineEpochMillis = due.responseDeadlineEpochMillis,
        )
        if (!withinWindow) {
            mutableState.update {
                it.copy(
                    due = due.copy(
                        status = if (now >= due.responseDeadlineEpochMillis) DoseStatus.MISSED else due.status,
                    ),
                    actionsEnabled = false,
                    feedback = ElderFeedback.WindowClosed,
                )
            }
            return
        }
        if (!mutableState.value.actionsEnabled) return

        viewModelScope.launch {
            mutableState.update { it.copy(actionsEnabled = false, feedback = null) }
            when (val result = doseRepository.recordAction(due.occurrence, kind)) {
                is Outcome.Failure -> mutableState.update {
                    val expired = clock.millis() >= due.responseDeadlineEpochMillis
                    it.copy(
                        due = if (expired) due.copy(status = DoseStatus.MISSED) else it.due,
                        actionsEnabled = !expired,
                        feedback = if (expired) ElderFeedback.WindowClosed else ElderFeedback.NotRecorded,
                    )
                }
                is Outcome.Success -> {
                    val nextStatus = when (kind) {
                        DoseActionKind.TAKEN -> DoseStatus.TAKEN
                        DoseActionKind.REMIND_LATER -> DoseStatus.SNOOZED
                    }
                    val feedback = when (kind) {
                        DoseActionKind.TAKEN -> ElderFeedback.ActionCompleted
                        DoseActionKind.REMIND_LATER -> when (
                            val snooze = ElderDuePreviewCatalog.remindLaterOutcome(
                                recordedAtEpochMillis = result.value.recordedAtEpochMillis,
                                responseDeadlineEpochMillis = due.responseDeadlineEpochMillis,
                                zone = clock.zone,
                            )
                        ) {
                            is ElderRemindLaterOutcome.NextReminder ->
                                ElderFeedback.RemindLaterNext(snooze.timeText)
                            ElderRemindLaterOutcome.WindowEndsBeforeReminder ->
                                ElderFeedback.RemindLaterWindowEnding
                        }
                    }
                    val stillOpen = nextStatus == DoseStatus.SNOOZED &&
                        ElderDuePreviewCatalog.canAcceptElderAction(
                            status = nextStatus,
                            nowEpochMillis = clock.millis(),
                            plannedAtEpochMillis = due.plannedAtEpochMillis,
                            responseDeadlineEpochMillis = due.responseDeadlineEpochMillis,
                        )
                    mutableState.update {
                        it.copy(
                            due = due.copy(status = nextStatus),
                            actionsEnabled = stillOpen,
                            feedback = feedback,
                        )
                    }
                }
            }
        }
    }

    private fun initialPreviewState(): ElderHomeUiState {
        val (due, upcoming) = ElderDuePreviewCatalog.dayPlan(
            clock = clock,
            dueMedicationName = dueMedicationName,
            upcomingMedicationName = upcomingMedicationName,
            dosageText = dosageText,
        )
        val actionsEnabled = ElderDuePreviewCatalog.canAcceptElderAction(
            status = due.status,
            nowEpochMillis = clock.millis(),
            plannedAtEpochMillis = due.plannedAtEpochMillis,
            responseDeadlineEpochMillis = due.responseDeadlineEpochMillis,
        )
        return ElderHomeUiState(due = due, upcoming = upcoming, actionsEnabled = actionsEnabled)
    }

    class Factory(
        private val authRepository: AuthRepository,
        private val doseRepository: DoseEventRepository,
        private val clock: Clock,
        private val anonymousDisplayName: String,
        private val dueMedicationName: String,
        private val upcomingMedicationName: String,
        private val dosageText: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ElderHomeViewModel::class.java))
            return ElderHomeViewModel(
                authRepository,
                doseRepository,
                clock,
                anonymousDisplayName,
                dueMedicationName,
                upcomingMedicationName,
                dosageText,
            ) as T
        }
    }
}
