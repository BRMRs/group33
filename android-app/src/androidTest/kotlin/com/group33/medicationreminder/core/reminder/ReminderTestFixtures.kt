package com.group33.medicationreminder.core.reminder

import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.TimeResolutionPolicy

internal fun testReminder(
    planId: String,
    plannedAt: Long,
    elderId: String = "fictional-elder",
    medicationName: String = "Fictional medicine",
    dosageText: String = "1 tablet",
) = ReminderOccurrence(
    elderId, medicationName, dosageText,
    OccurrenceGenerationEvidence(
        "generation-$planId", OccurrenceKey(planId, "2026-11-11", "08:00"), 1L,
        plannedAt - 60_000L, "Asia/Shanghai", plannedAt, 28_800,
        TimeResolutionPolicy.P0_TIME_V1, "snapshot-$planId", plannedAt - 60_000L,
    ),
)

internal fun testGroup(
    planId: String,
    plannedAt: Long,
    elderId: String = "fictional-elder",
    medicineCount: Int = 1,
    executionScopeId: String = "test-execution-scope",
) = ReminderGroup(
    executionScopeId,
    elderId,
    (1..medicineCount).map { index ->
        testReminder(
            if (medicineCount == 1) planId else "$planId-$index",
            plannedAt, elderId, "Fictional medicine $index", "$index tablet",
        )
    },
)

internal data class TestRegistration(
    val group: ReminderGroupRow,
    val occurrences: List<ReminderOccurrenceRow>,
)

internal fun testRegistration(
    planId: String,
    plannedAt: Long,
    deadlineAt: Long = plannedAt + InitialTiming.responseWindow.toMillis(),
    elderId: String = "fictional-elder",
    state: ReminderState = ReminderState.SCHEDULED,
    lastNotifiedBootCount: Int? = null,
    medicineCount: Int = 1,
    executionScopeId: String = "test-execution-scope",
): TestRegistration {
    val model = testGroup(planId, plannedAt, elderId, medicineCount, executionScopeId)
    return TestRegistration(
        ReminderGroupRow(
            model.groupId, model.executionScopeId, elderId, plannedAt, deadlineAt,
            model.occurrences.joinToString(" · ") { it.medicationName },
            model.occurrences.joinToString(" · ") { it.dosageText },
            state = state.name,
            lastNotifiedBootCount = lastNotifiedBootCount,
        ),
        model.occurrences.map { occurrence ->
            val evidence = occurrence.generationEvidence
            ReminderOccurrenceRow(
                model.groupId, model.executionScopeId, occurrence.occurrenceId, evidence.occurrence.planId,
                evidence.occurrence.localDate, evidence.occurrence.localTime,
                evidence.planRevision, evidence.generationId, evidence.executionTimeZoneId,
                occurrence.medicationName, occurrence.dosageText,
            )
        },
    )
}

internal suspend fun ReminderDao.insertTestRegistration(registration: TestRegistration): Boolean =
    insertRegistration(registration.group, registration.occurrences)
