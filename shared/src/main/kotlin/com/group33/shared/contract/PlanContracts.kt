package com.group33.shared.contract

import kotlinx.serialization.Serializable

@Serializable
data class MedicationPhotoView(
    val id: String,
    val elderId: String,
    val contentType: String,
    val createdAtEpochMillis: Long,
)

@Serializable
data class MedicationText(val displayName: String, val dosageText: String, val instructions: String? = null)

@Serializable
data class MedicationView(
    val id: String,
    val elderId: String,
    val displayName: String,
    val dosageText: String,
    val active: Boolean,
    val instructions: String? = null,
    val photoId: String? = null,
)

@Serializable
data class ReminderPlanDraft(
    val localTimes: List<String>,
    val startDate: String,
    val executionTimeZoneId: String,
    val endDate: String? = null,
)

@Serializable
data class TimeZoneAcknowledgement(
    val executionTimeZoneId: String,
    val mismatchAcknowledged: Boolean,
    val caregiverTimeZoneId: String? = null,
    val observedElderTimeZoneId: String? = null,
)

@Serializable
data class ConfirmReminderPlanRequest(
    val photoId: String,
    val medication: MedicationText,
    val plan: ReminderPlanDraft,
    val timeZoneAcknowledgement: TimeZoneAcknowledgement,
)

@Serializable
data class ReminderPlanView(
    val id: String,
    val elderId: String,
    val medicationId: String,
    val revision: Long,
    val localTimes: List<String>,
    val startDate: String,
    val executionTimeZoneId: String,
    val active: Boolean,
    val effectiveFromEpochMillis: Long,
    val confirmedAtEpochMillis: Long,
    val endDate: String? = null,
)

/** Flat wire shape, not an embedded current plan: a revision retains its own text. */
@Serializable
data class PlanRevisionView(
    val id: String,
    val elderId: String,
    val medicationId: String,
    val revision: Long,
    val localTimes: List<String>,
    val startDate: String,
    val executionTimeZoneId: String,
    val active: Boolean,
    val effectiveFromEpochMillis: Long,
    val confirmedAtEpochMillis: Long,
    val medicationSnapshot: MedicationText,
    val endDate: String? = null,
)

@Serializable
data class ConfirmReminderPlanResponse(val medication: MedicationView, val plan: ReminderPlanView)

@Serializable
data class UpdateReminderPlanRequest(
    val expectedRevision: Long,
    val plan: ReminderPlanDraft,
    val timeZoneAcknowledgement: TimeZoneAcknowledgement,
    val medication: MedicationText? = null,
)

@Serializable
data class UpdateReminderPlanResponse(val medication: MedicationView, val plan: ReminderPlanView)

@Serializable
data class DeactivateReminderPlanRequest(val expectedRevision: Long)

@Serializable
data class ReminderPlanCollection(val items: List<ReminderPlanView>, val planRevisions: List<PlanRevisionView>)
