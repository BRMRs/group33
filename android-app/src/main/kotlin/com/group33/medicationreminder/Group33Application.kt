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
        applyLanguage(choice)
    }

    companion object {
        fun applyLanguage(choice: LanguageChoice) {
            val tags = when (choice) {
                LanguageChoice.FOLLOW_SYSTEM -> ""
                LanguageChoice.ENGLISH -> "en"
                LanguageChoice.SIMPLIFIED_CHINESE -> "zh-CN"
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }
    }
}
