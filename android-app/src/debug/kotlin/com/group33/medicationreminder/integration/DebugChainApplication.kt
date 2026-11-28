package com.group33.medicationreminder.integration

import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.BuildConfig
import com.group33.medicationreminder.core.reminder.ReminderActionDispatcher
import com.group33.medicationreminder.core.reminder.ReminderClock
import com.group33.medicationreminder.core.reminder.ReminderStateListener
import com.group33.medicationreminder.elder.photo.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DebugChainApplication : Group33Application(), ElderPhotoDependenciesOwner {
    @Volatile private var testReminderNow: Long? = null
    internal override val reminderClock = ReminderClock {
        testReminderNow ?: System.currentTimeMillis()
    }
    internal val repository by lazy { DebugChainRepository(this, BuildConfig.DEBUG_SERVER_URL) }
    internal override val elderDoses by lazy { DebugDoseReminders(this, repository) }
    internal override val reminderActionDispatcher by lazy {
        ReminderActionDispatcher(elderDoses::dispatchRuntimeAction)
    }
    internal override val reminderStateListener by lazy {
        ReminderStateListener { elderDoses.refresh() }
    }

    override fun onCreate() {
        super.onCreate()
        elderDoses.start(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    internal override val featureServices get() = repository.services
    internal fun setReminderTimeForTest(epochMillis: Long) {
        check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        testReminderNow = epochMillis
    }
    override val elderPhotoDependencies: ElderPhotoDependencies
        get() = repository.services.let { ElderPhotoDependencies(it.photos, it.commands, it.scope) }
}
