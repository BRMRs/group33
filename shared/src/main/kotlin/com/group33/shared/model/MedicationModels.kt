package com.group33.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class Medication(
    val id: String,
    val elderId: String,
    val displayName: String,
    val dosageText: String,
    val instructions: String? = null,
    val photoUrl: String? = null,
    val active: Boolean = true,
)

@Serializable
data class MedicationSchedule(
    val id: String,
    val medicationId: String,
    val elderId: String,
    val caregiverId: String,
    val times: List<String>,
    val startDate: String,
    val endDate: String? = null,
    val enabled: Boolean = true,
    val updatedAtEpochMillis: Long,
)

@Serializable
enum class DoseStatus {
    SCHEDULED,
    DUE,
    SNOOZED,
    TAKEN,
    MISSED,
}

@Serializable
data class DoseEvent(
    val id: String,
    val scheduleId: String,
    val plannedAtEpochMillis: Long,
    val status: DoseStatus,
    val confirmedAtEpochMillis: Long? = null,
)

