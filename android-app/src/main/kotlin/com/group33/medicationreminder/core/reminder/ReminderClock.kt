package com.group33.medicationreminder.core.reminder

import android.content.Context
import com.group33.medicationreminder.Group33Application

internal fun interface ReminderClock {
    fun nowEpochMillis(): Long
}

internal object SystemReminderClock : ReminderClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

internal fun Context.reminderClock(): ReminderClock =
    (applicationContext as? Group33Application)?.reminderClock ?: SystemReminderClock
