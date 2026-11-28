package com.group33.medicationreminder.elder

import com.group33.medicationreminder.core.reminder.ReminderCapabilitySnapshot
import com.group33.medicationreminder.R
import com.group33.shared.contract.DeliveryCondition
import com.group33.shared.contract.ExactAlarmAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class ElderDoseCapabilityPresentationTest {
    @Test fun exactAlarmDenialIsPresentedAsDegraded() {
        val screen = ElderDoseScreen(capability = ReminderCapabilitySnapshot(
            DeliveryCondition.DEGRADED,
            notificationPermissionGranted = true,
            channelEnabled = true,
            exactAlarmAccess = ExactAlarmAccess.DENIED,
        ))

        assertEquals(ReminderPermissionPrompt.EXACT_ALARM_DEGRADED, screen.permissionPrompt())
    }

    @Test fun notificationDenialIsPresentedAsUnavailable() {
        val screen = ElderDoseScreen(capability = ReminderCapabilitySnapshot(
            DeliveryCondition.UNAVAILABLE,
            notificationPermissionGranted = false,
            channelEnabled = true,
            exactAlarmAccess = ExactAlarmAccess.GRANTED,
        ))

        assertEquals(ReminderPermissionPrompt.NOTIFICATIONS_UNAVAILABLE, screen.permissionPrompt())
        assertEquals(R.string.elder_reminder_notifications_unavailable,
            screen.permissionPrompt().presentation.message)
        assertEquals(R.string.elder_enable_dose_notifications,
            screen.permissionPrompt().presentation.action)
    }

    @Test fun availableCapabilityNeedsNoPermissionPrompt() {
        val screen = ElderDoseScreen(capability = ReminderCapabilitySnapshot(
            DeliveryCondition.AVAILABLE,
            notificationPermissionGranted = true,
            channelEnabled = true,
            exactAlarmAccess = ExactAlarmAccess.GRANTED,
        ))

        assertEquals(ReminderPermissionPrompt.NONE, screen.permissionPrompt())
        assertEquals(null, screen.permissionPrompt().presentation.message)
        assertEquals(null, screen.permissionPrompt().presentation.action)
    }

    @Test fun degradedPromptUsesQualifiedTimingGuidanceAndSettingsAction() {
        val presentation = ReminderPermissionPrompt.EXACT_ALARM_DEGRADED.presentation

        assertEquals(R.string.elder_reminder_exact_alarm_degraded, presentation.message)
        assertEquals(R.string.elder_allow_exact_alarms, presentation.action)
    }

    @Test fun firstNotificationDenialRequestsTheRuntimePermission() {
        val recovery = ReminderPermissionPrompt.NOTIFICATIONS_UNAVAILABLE.recoveryAction(NotificationPermissionState(
            apiLevel = 33,
            granted = false,
            requestedBefore = false,
            shouldShowRequestRationale = false,
        ))

        assertEquals(ReminderPermissionRecoveryAction.REQUEST_NOTIFICATIONS, recovery)
    }

    @Test fun permanentNotificationDenialOpensApplicationSettings() {
        val recovery = ReminderPermissionPrompt.NOTIFICATIONS_UNAVAILABLE.recoveryAction(NotificationPermissionState(
            apiLevel = 33,
            granted = false,
            requestedBefore = true,
            shouldShowRequestRationale = false,
        ))

        assertEquals(ReminderPermissionRecoveryAction.OPEN_NOTIFICATION_SETTINGS, recovery)
    }

    @Test fun explainableNotificationDenialCanRequestAgain() {
        val recovery = ReminderPermissionPrompt.NOTIFICATIONS_UNAVAILABLE.recoveryAction(NotificationPermissionState(
            apiLevel = 33,
            granted = false,
            requestedBefore = true,
            shouldShowRequestRationale = true,
        ))

        assertEquals(ReminderPermissionRecoveryAction.REQUEST_NOTIFICATIONS, recovery)
    }

    @Test fun exactAlarmDenialAlwaysUsesItsSettingsRecovery() {
        val recovery = ReminderPermissionPrompt.EXACT_ALARM_DEGRADED.recoveryAction(NotificationPermissionState(
            apiLevel = 35,
            granted = true,
            requestedBefore = false,
            shouldShowRequestRationale = false,
        ))

        assertEquals(ReminderPermissionRecoveryAction.OPEN_EXACT_ALARM_SETTINGS, recovery)
    }
}
