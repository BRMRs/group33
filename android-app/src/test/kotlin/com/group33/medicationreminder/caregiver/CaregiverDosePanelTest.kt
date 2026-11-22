package com.group33.medicationreminder.caregiver

import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.TimeResolutionPolicy
import com.group33.shared.model.DoseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverDosePanelTest {
    private val occurrence = OccurrenceKey("plan", "2026-11-11", "08:00")
    private val evidence = OccurrenceGenerationEvidence(
        "generation", occurrence, 1, 1, "Asia/Shanghai", 1, 28_800,
        TimeResolutionPolicy.P0_TIME_V1, "snapshot", 1,
    )

    private fun event(status: DoseStatus, acceptedAt: Long? = null) = DoseEventState(
        occurrence, "event-$status", evidence, "medication", MedicationText("Medicine", "1", null),
        10, status, status, emptyList(), serverAcceptedTakenAtEpochMillis = acceptedAt,
    )

    @Test fun onlyMissedRecordsProduceNoConfirmedDose() {
        assertTrue(confirmedTakenRecords(listOf(event(DoseStatus.MISSED))).isEmpty())
    }

    @Test fun newerMissedRecordDoesNotReplaceLatestConfirmedTakenDose() {
        val confirmed = event(DoseStatus.TAKEN, acceptedAt = 100)
        assertEquals(listOf(confirmed), confirmedTakenRecords(listOf(event(DoseStatus.MISSED), confirmed)))
    }
}
