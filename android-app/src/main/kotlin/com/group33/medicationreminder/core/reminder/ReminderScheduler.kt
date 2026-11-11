package com.group33.medicationreminder.core.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.group33.shared.contract.DeliveryCondition
import com.group33.shared.contract.ExactAlarmAccess

internal enum class ScheduleAccuracy { EXACT, INEXACT }

internal data class ReminderCapabilitySnapshot(
    val deliveryCondition: DeliveryCondition,
    val notificationPermissionGranted: Boolean,
    val channelEnabled: Boolean,
    val exactAlarmAccess: ExactAlarmAccess,
) {
    val notificationsUsable get() = notificationPermissionGranted && channelEnabled
}

internal object ReminderCapability {
    fun snapshot(context: Context): ReminderCapabilitySnapshot {
        ReminderNotifications.ensureChannel(context)
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channel = Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ReminderNotifications.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        val exact = when {
            Build.VERSION.SDK_INT < 31 -> ExactAlarmAccess.NOT_REQUIRED
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() -> ExactAlarmAccess.GRANTED
            else -> ExactAlarmAccess.DENIED
        }
        val condition = when {
            !notifications || !channel -> DeliveryCondition.UNAVAILABLE
            exact == ExactAlarmAccess.DENIED -> DeliveryCondition.DEGRADED
            else -> DeliveryCondition.AVAILABLE
        }
        return ReminderCapabilitySnapshot(condition, notifications, channel, exact)
    }

    fun openNotificationSettings(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun openExactAlarmSettings(context: Context): Intent = if (Build.VERSION.SDK_INT >= 31) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
    }
}

internal class ReminderAlarmScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun scheduleInitial(row: ReminderGroupRow): ScheduleAccuracy {
        schedule(row.groupId, ReminderAlarmKind.PRE_PROBE,
            row.plannedAtEpochMillis - InitialTiming.probeOffset.toMillis())
        schedule(row.groupId, ReminderAlarmKind.DUE, row.plannedAtEpochMillis)
        schedule(row.groupId, ReminderAlarmKind.POST_PROBE,
            row.plannedAtEpochMillis + InitialTiming.probeOffset.toMillis())
        schedule(row.groupId, ReminderAlarmKind.FOLLOW_UP,
            row.plannedAtEpochMillis + InitialTiming.nonresponseThreshold.toMillis())
        schedule(row.groupId, ReminderAlarmKind.EXPIRE, row.deadlineAtEpochMillis)
        return accuracy()
    }

    fun scheduleSnooze(row: ReminderGroupRow, atEpochMillis: Long): ScheduleAccuracy {
        cancel(row.groupId, ReminderAlarmKind.DUE)
        if (atEpochMillis < row.deadlineAtEpochMillis) {
            schedule(row.groupId, ReminderAlarmKind.DUE, atEpochMillis)
        }
        return accuracy()
    }

    fun cancel(groupId: String) = ReminderAlarmKind.entries.forEach { cancel(groupId, it) }

    private fun cancel(groupId: String, kind: ReminderAlarmKind) {
        alarms.cancel(pending(groupId, kind))
    }

    private fun schedule(groupId: String, kind: ReminderAlarmKind, atEpochMillis: Long) {
        if (atEpochMillis <= System.currentTimeMillis()) return
        val operation = pending(groupId, kind)
        try {
            if (accuracy() == ScheduleAccuracy.EXACT) {
                if (Build.VERSION.SDK_INT >= 23) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, operation)
                } else {
                    alarms.setExact(AlarmManager.RTC_WAKEUP, atEpochMillis, operation)
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, operation)
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, atEpochMillis, operation)
            }
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, operation)
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, atEpochMillis, operation)
            }
        }
    }

    private fun accuracy(): ScheduleAccuracy =
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) ScheduleAccuracy.EXACT
        else ScheduleAccuracy.INEXACT

    private fun pending(groupId: String, kind: ReminderAlarmKind): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderAlarmReceiver::class.java)
                .setAction(ReminderAlarmReceiver.ACTION_ALARM)
                .setData(android.net.Uri.parse(
                    "group33://reminder/${android.net.Uri.encode(groupId)}/alarm/${kind.name.lowercase()}",
                ))
                .putExtra(ReminderAlarmReceiver.EXTRA_GROUP_ID, groupId)
                .putExtra(ReminderAlarmReceiver.EXTRA_KIND, kind.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

