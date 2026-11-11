package com.group33.medicationreminder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.group33.medicationreminder.core.data.LocalLanguageSettingsRepository
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.reminder.ReminderNotifications
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class Group33Application : Application() {
    private val infrastructureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal open val featureServices: com.group33.medicationreminder.integration.ChainServices? get() = null
    internal open val elderDoses: com.group33.medicationreminder.elder.LiveElderDoses? get() = null
    internal open val reminderPhaseDispatcher: com.group33.medicationreminder.core.reminder.ReminderPhaseDispatcher? get() = null
    internal open val reminderActionDispatcher: com.group33.medicationreminder.core.reminder.ReminderActionDispatcher? get() = null
    lateinit var languageSettings: LocalLanguageSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        languageSettings = LocalLanguageSettingsRepository(this)
        ReminderNotifications.ensureChannel(this)
        infrastructureScope.launch {
            com.group33.medicationreminder.core.reminder.ReminderCoordinator(this@Group33Application).recover()
            if (reminderPhaseDispatcher != null) {
                com.group33.medicationreminder.core.reminder.ReminderPhaseWorker.enqueuePending(this@Group33Application)
            }
            if (reminderActionDispatcher != null) {
                com.group33.medicationreminder.core.reminder.ReminderActionWorker.enqueuePending(this@Group33Application)
            }
        }
        val choice = runBlocking { languageSettings.observeChoice().first() }
        applyLanguage(choice, this)
    }

    companion object {
        fun applyLanguage(choice: LanguageChoice, context: android.content.Context? = null) {
            val tags = when (choice) {
                LanguageChoice.FOLLOW_SYSTEM -> ""
                LanguageChoice.ENGLISH -> "en"
                LanguageChoice.SIMPLIFIED_CHINESE -> "zh-CN"
            }
            // On API33+ AppCompat cannot find LocaleManager before any Activity delegate exists.
            // A persisted choice must also work on a real cold start, not only from a live screen.
            if (android.os.Build.VERSION.SDK_INT >= 33 && context != null) {
                context.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                    android.os.LocaleList.forLanguageTags(tags)
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }
    }
}
