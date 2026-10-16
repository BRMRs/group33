package com.group33.shared.contract

import kotlinx.serialization.Serializable

@Serializable
enum class DeliveryCondition { AVAILABLE, DEGRADED, UNAVAILABLE }

@Serializable
enum class ExactAlarmAccess { NOT_REQUIRED, GRANTED, DENIED }

@Serializable
data class ReminderCapabilityReport(
    val reportId: String,
    val observationSequence: Long,
    val observedAtEpochMillis: Long,
    val deliveryCondition: DeliveryCondition,
    val notificationPermissionGranted: Boolean,
    val channelEnabled: Boolean,
    val exactAlarmAccess: ExactAlarmAccess,
    val reasons: List<String>,
)

@Serializable
data class ReminderCapabilityView(
    val reportId: String,
    val observationSequence: Long,
    val observedAtEpochMillis: Long,
    val deliveryCondition: DeliveryCondition,
    val notificationPermissionGranted: Boolean,
    val channelEnabled: Boolean,
    val exactAlarmAccess: ExactAlarmAccess,
    val reasons: List<String>,
    val receivedAtEpochMillis: Long,
)

@Serializable
data class ReminderCapabilityReceipt(
    val reportId: String,
    val receivedAtEpochMillis: Long,
    val applied: Boolean,
    val current: ReminderCapabilityView,
)

@Serializable
data class ElderSnapshot(
    val elder: UserView,
    val photos: List<MedicationPhotoView>,
    val medications: List<MedicationView>,
    val reminderPlans: List<ReminderPlanView>,
    val planRevisions: List<PlanRevisionView>,
    val doseEvents: List<DoseEventView>,
    val fromDate: String,
    val throughDate: String,
    val snapshotVersion: String,
    val serverAtEpochMillis: Long,
    val careRelationship: CareRelationshipView? = null,
    val capability: ReminderCapabilityView? = null,
)
