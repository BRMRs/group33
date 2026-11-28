package com.group33.medicationreminder.elder

import com.group33.medicationreminder.R
import com.group33.shared.contract.DeliveryCondition

internal data class ReminderPermissionPresentation(val message: Int?, val action: Int?)

internal enum class ReminderPermissionPrompt(val presentation: ReminderPermissionPresentation) {
    NONE(ReminderPermissionPresentation(null, null)),
    NOTIFICATIONS_UNAVAILABLE(ReminderPermissionPresentation(
        R.string.elder_reminder_notifications_unavailable,
        R.string.elder_enable_dose_notifications,
    )),
    EXACT_ALARM_DEGRADED(ReminderPermissionPresentation(
        R.string.elder_reminder_exact_alarm_degraded,
        R.string.elder_allow_exact_alarms,
    )),
}

internal enum class ReminderPermissionRecoveryAction {
    NONE,
    REQUEST_NOTIFICATIONS,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_EXACT_ALARM_SETTINGS,
}

internal data class NotificationPermissionState(
    val apiLevel: Int,
    val granted: Boolean,
    val requestedBefore: Boolean,
    val shouldShowRequestRationale: Boolean,
)

internal fun ElderDoseScreen.permissionPrompt(): ReminderPermissionPrompt = when (
    capability?.deliveryCondition
) {
    DeliveryCondition.UNAVAILABLE -> ReminderPermissionPrompt.NOTIFICATIONS_UNAVAILABLE
    DeliveryCondition.DEGRADED -> ReminderPermissionPrompt.EXACT_ALARM_DEGRADED
    else -> ReminderPermissionPrompt.NONE
}

internal fun ReminderPermissionPrompt.recoveryAction(
    notification: NotificationPermissionState,
): ReminderPermissionRecoveryAction = when (this) {
    ReminderPermissionPrompt.NONE -> ReminderPermissionRecoveryAction.NONE
    ReminderPermissionPrompt.EXACT_ALARM_DEGRADED ->
        ReminderPermissionRecoveryAction.OPEN_EXACT_ALARM_SETTINGS
    ReminderPermissionPrompt.NOTIFICATIONS_UNAVAILABLE -> if (
        notification.apiLevel >= 33 &&
        !notification.granted &&
        (!notification.requestedBefore || notification.shouldShowRequestRationale)
    ) {
        ReminderPermissionRecoveryAction.REQUEST_NOTIFICATIONS
    } else {
        ReminderPermissionRecoveryAction.OPEN_NOTIFICATION_SETTINGS
    }
}
