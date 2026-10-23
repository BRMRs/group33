package com.group33.medicationreminder.elder

import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.model.DoseStatus

data class ElderDueCardUi(
    val occurrence: OccurrenceKey,
    val plannedTimeText: String,
    val medicationName: String,
    val dosageText: String,
    val instructions: String?,
    val plannedAtEpochMillis: Long,
    val responseDeadlineEpochMillis: Long,
    val status: DoseStatus,
)

data class ElderUpcomingUi(
    val medicationName: String,
    val plannedTimeText: String,
    val dosageText: String,
)

data class ElderHomeUiState(
    val sessionDisplayName: String? = null,
    val due: ElderDueCardUi? = null,
    val upcoming: List<ElderUpcomingUi> = emptyList(),
    val actionsEnabled: Boolean = true,
    val feedback: ElderFeedback? = null,
    val loginError: Boolean = false,
)

sealed interface ElderRemindLaterOutcome {
    data class NextReminder(val timeText: String) : ElderRemindLaterOutcome
    data object WindowEndsBeforeReminder : ElderRemindLaterOutcome
}
