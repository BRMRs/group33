package com.group33.medicationreminder.core.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.group33.medicationreminder.R
import java.util.UUID

internal enum class ReminderAction(val intentAction: String, val kind: ReminderActionKind) {
    TAKEN("com.group33.medicationreminder.reminder.TAKEN", ReminderActionKind.TAKEN),
    SNOOZE("com.group33.medicationreminder.reminder.SNOOZE", ReminderActionKind.SNOOZE),
    HELP("com.group33.medicationreminder.reminder.HELP", ReminderActionKind.HELP),
}

internal object ReminderNotifications {
    // v2 is intentionally silent: ReminderRingingService is the sole audible source. A new ID also
    // avoids inheriting the immutable sound configured on devices that already created v1.
    const val CHANNEL_ID = "medication-reminders-v2"
    const val NOTIFICATION_ID = 41_001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    fun show(context: Context, row: ReminderGroupRow): Boolean {
        val now = System.currentTimeMillis()
        if (row.state != ReminderState.RINGING.name || row.deadlineAtEpochMillis <= now) {
            cancel(context, row.groupId)
            return false
        }
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context, row))
        return true
    }

    fun build(context: Context, row: ReminderGroupRow): Notification {
        val now = System.currentTimeMillis()
        val canSnooze = row.deadlineAtEpochMillis - now > InitialTiming.snooze.toMillis()
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.group33.medicationreminder.elder.ElderHomeActivity::class.java)
                .setData(android.net.Uri.parse("group33://reminder/${android.net.Uri.encode(row.groupId)}"))
                .putExtra(ReminderAlarmReceiver.EXTRA_GROUP_ID, row.groupId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(if (!canSnooze) context.getString(R.string.reminder_snooze_unavailable) else context.getString(
                R.string.reminder_notification_text,
                row.medicationSummary,
                row.dosageSummary,
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setExtras(android.os.Bundle().apply { putString(EXTRA_GROUP_ID, row.groupId) })
            .addAction(0, context.getString(R.string.reminder_action_taken), action(context, row, ReminderAction.TAKEN))
        if (canSnooze) {
            builder.addAction(0, context.getString(R.string.reminder_action_snooze), action(context, row, ReminderAction.SNOOZE))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.reminder_snooze_unavailable_details, row.medicationSummary, row.dosageSummary),
            ))
        }
        return builder
            .addAction(0, context.getString(R.string.reminder_action_help), action(context, row, ReminderAction.HELP))
            .build()
    }

    fun placeholder(context: Context, groupId: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(context.getString(R.string.reminder_ringing_starting))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setExtras(android.os.Bundle().apply { putString(EXTRA_GROUP_ID, groupId) })
            .build()
    }

    private fun action(context: Context, row: ReminderGroupRow, action: ReminderAction): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderAlarmReceiver::class.java)
                .setAction(action.intentAction)
                .setData(android.net.Uri.parse(
                    "group33://reminder/${android.net.Uri.encode(row.groupId)}/${action.name.lowercase()}",
                ))
                .putExtra(ReminderAlarmReceiver.EXTRA_GROUP_ID, row.groupId)
                .putExtra(ReminderAlarmReceiver.EXTRA_ACTION_ID, stableActionId(row, action)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stableActionId(row: ReminderGroupRow, action: ReminderAction): String =
        UUID.nameUUIDFromBytes(
            "${row.groupId}:${row.lastAlarmAtEpochMillis}:${action.name}".toByteArray(Charsets.UTF_8),
        ).toString()

    fun cancel(context: Context, groupId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(groupId, 1) // Remove a notification left by pre-v2 builds.
        if (manager.activeNotifications.any {
                it.id == NOTIFICATION_ID && it.notification.extras.getString(EXTRA_GROUP_ID) == groupId
            }) manager.cancel(NOTIFICATION_ID)
    }

    fun isForGroup(notification: Notification, groupId: String): Boolean =
        notification.extras.getString(EXTRA_GROUP_ID) == groupId

    const val EXTRA_GROUP_ID = "reminderForegroundGroupId"
}

