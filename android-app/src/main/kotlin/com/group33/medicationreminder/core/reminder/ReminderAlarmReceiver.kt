package com.group33.medicationreminder.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000L) { handle(context.applicationContext, intent) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: return
        val coordinator = ReminderCoordinator(context)
        val changed = when (intent.action) {
            ACTION_ALARM -> alarm(context, coordinator, groupId, intent)
            else -> {
                val action = ReminderAction.entries.firstOrNull { it.intentAction == intent.action } ?: return
                val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
                coordinator.recordAction(groupId, actionId, action.kind)
            }
        }
        if (changed) {
            (context as? com.group33.medicationreminder.Group33Application)
                ?.reminderStateListener?.onChanged(groupId)
        }
    }

    private suspend fun alarm(
        context: Context,
        coordinator: ReminderCoordinator,
        groupId: String,
        intent: Intent,
    ): Boolean {
        val kind = intent.getStringExtra(EXTRA_KIND)?.let {
            runCatching { ReminderAlarmKind.valueOf(it) }.getOrNull()
        } ?: return false
        val dao = ReminderDatabase.open(context).reminders()
        val row = dao.find(groupId) ?: return false
        val scheduledAt = when (kind) {
            ReminderAlarmKind.PRE_PROBE -> row.plannedAtEpochMillis - InitialTiming.probeOffset.toMillis()
            ReminderAlarmKind.DUE -> row.snoozedUntilEpochMillis ?: row.plannedAtEpochMillis
            ReminderAlarmKind.POST_PROBE -> row.plannedAtEpochMillis + InitialTiming.probeOffset.toMillis()
            ReminderAlarmKind.FOLLOW_UP -> row.plannedAtEpochMillis + InitialTiming.nonresponseThreshold.toMillis()
            ReminderAlarmKind.EXPIRE -> row.deadlineAtEpochMillis
            ReminderAlarmKind.RECOVERY -> return false
        }
        val now = System.currentTimeMillis()
        val event = ReminderAlarmEventRow(
                "$groupId:${kind.name}:$scheduledAt",
                groupId,
                kind.name,
                scheduledAt,
                now,
            )
        val inserted = dao.insertAlarmEvent(event) != -1L
        if (inserted && kind in dispatchableKinds &&
            (context.applicationContext as? com.group33.medicationreminder.Group33Application)
                ?.reminderPhaseDispatcher != null) {
            ReminderPhaseWorker.enqueue(context, event.eventId)
        }
        return when (kind) {
            ReminderAlarmKind.DUE -> coordinator.triggerDue(groupId, now)
            ReminderAlarmKind.EXPIRE -> coordinator.cancel(groupId)
            else -> false // Durable phase fact; the joint S1/S2 adapter consumes it for probes/follow-up.
        }
    }

    companion object {
        const val ACTION_ALARM = "com.group33.medicationreminder.reminder.ALARM"
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_KIND = "kind"
        private val dispatchableKinds = setOf(
            ReminderAlarmKind.PRE_PROBE,
            ReminderAlarmKind.POST_PROBE,
            ReminderAlarmKind.FOLLOW_UP,
        )
    }
}

internal fun interface ReminderStateListener {
    suspend fun onChanged(groupId: String)
}

