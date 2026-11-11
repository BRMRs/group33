package com.group33.medicationreminder.core.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.group33.medicationreminder.R
import java.util.UUID

internal enum class ReminderAction(val intentAction: String, val kind: ReminderActionKind) {
    TAKEN("com.group33.medicationreminder.reminder.TAKEN", ReminderActionKind.TAKEN),
    SNOOZE("com.group33.medicationreminder.reminder.SNOOZE", ReminderActionKind.SNOOZE),
    HELP("com.group33.medicationreminder.reminder.HELP", ReminderActionKind.HELP),
}

internal object ReminderNotifications {
    const val CHANNEL_ID = "medication-reminders-v1"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val sound = defaultSound()
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            setSound(sound, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    fun show(context: Context, row: ReminderGroupRow): Boolean {
        ensureChannel(context)
        if (!ReminderCapability.snapshot(context).notificationsUsable) return false
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.group33.medicationreminder.elder.ElderHomeActivity::class.java)
                .setData(android.net.Uri.parse("group33://reminder/${android.net.Uri.encode(row.groupId)}"))
                .putExtra(ReminderAlarmReceiver.EXTRA_GROUP_ID, row.groupId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(context.getString(
                R.string.reminder_notification_text,
                row.medicationSummary,
                row.dosageSummary,
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setSound(defaultSound())
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, context.getString(R.string.reminder_action_taken), action(context, row, ReminderAction.TAKEN))
            .addAction(0, context.getString(R.string.reminder_action_snooze), action(context, row, ReminderAction.SNOOZE))
            .addAction(0, context.getString(R.string.reminder_action_help), action(context, row, ReminderAction.HELP))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(row.groupId, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
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

    fun cancel(context: Context, groupId: String) =
        NotificationManagerCompat.from(context).cancel(groupId, NOTIFICATION_ID)

    private fun defaultSound() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}

