package com.group33.medicationreminder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.group33.medicationreminder.core.data.LocalLanguageSettingsRepository
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

open class Group33Application : Application() {
    internal open val featureServices: com.group33.medicationreminder.integration.ChainServices? get() = null
    lateinit var languageSettings: LocalLanguageSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        languageSettings = LocalLanguageSettingsRepository(this)
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
