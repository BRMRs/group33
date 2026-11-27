package com.group33.medicationreminder.elder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

internal class ElderReminderPermissionNavigator(
    private val activity: Activity,
    private val requestNotifications: () -> Unit,
) {
    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun recover(prompt: ReminderPermissionPrompt) {
        val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val recovery = prompt.recoveryAction(NotificationPermissionState(
            apiLevel = Build.VERSION.SDK_INT,
            granted = notificationPermissionGranted,
            requestedBefore = preferences.getBoolean(
                NOTIFICATION_PERMISSION_REQUESTED,
                false,
            ),
            shouldShowRequestRationale = Build.VERSION.SDK_INT >= 33 &&
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        ))
        when (recovery) {
            ReminderPermissionRecoveryAction.NONE -> Unit
            ReminderPermissionRecoveryAction.REQUEST_NOTIFICATIONS -> {
                preferences.edit().putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true).apply()
                requestNotifications()
            }
            ReminderPermissionRecoveryAction.OPEN_NOTIFICATION_SETTINGS ->
                activity.startActivity(notificationSettingsIntent(activity))
            ReminderPermissionRecoveryAction.OPEN_EXACT_ALARM_SETTINGS ->
                activity.startActivity(exactAlarmSettingsIntent(activity))
        }
    }

    companion object {
        private const val PREFERENCES = "elder-reminder-permission-recovery"
        private const val NOTIFICATION_PERMISSION_REQUESTED = "notification-permission-requested"

        fun notificationSettingsIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }

        fun exactAlarmSettingsIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
    }
}
