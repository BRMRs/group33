package com.group33.medicationreminder.core.data

import android.content.Context
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.data.contract.LanguageSettingsRepository
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-wide preference shared by both app roles. No account or medical data is stored here. */
class LocalLanguageSettingsRepository(context: Context) : LanguageSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val choice = MutableStateFlow(readChoice(preferences.getString(KEY_CHOICE, null)))

    override fun observeChoice() = choice.asStateFlow()

    override suspend fun setChoice(choice: LanguageChoice): Outcome<Unit> {
        if (!preferences.edit().putString(KEY_CHOICE, choice.name).commit()) {
            return Outcome.Failure(RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE))
        }
        this.choice.value = choice
        return Outcome.Success(Unit)
    }

    companion object {
        private const val PREFERENCES_NAME = "language_settings"
        private const val KEY_CHOICE = "choice"

        internal fun readChoice(stored: String?): LanguageChoice =
            LanguageChoice.entries.firstOrNull { it.name == stored } ?: LanguageChoice.FOLLOW_SYSTEM
    }
}
