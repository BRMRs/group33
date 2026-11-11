package com.group33.medicationreminder.caregiver.data

import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.medicationreminder.core.data.contract.ServerAcceptedLateTakenReport
import com.group33.shared.contract.DoseEventView

/** Maps service-confirmed read data without inventing device-local queue state. */
internal fun DoseEventView.toDoseEventState(): DoseEventState = DoseEventState(
    occurrence = occurrence,
    serverEventId = id,
    generationEvidence = generationEvidence,
    medicationId = medicationId,
    medicationSnapshot = medicationSnapshot,
    responseDeadlineEpochMillis = responseDeadlineEpochMillis,
    effectiveStatus = status,
    lastAcceptedStatus = status,
    localReports = emptyList(),
    serverAcceptedLateTakenReports = lateTakenReports.map {
        ServerAcceptedLateTakenReport(
            actionId = it.actionId,
            recordedAtEpochMillis = it.recordedAtEpochMillis,
            receivedAtEpochMillis = it.receivedAtEpochMillis,
        )
    },
)
