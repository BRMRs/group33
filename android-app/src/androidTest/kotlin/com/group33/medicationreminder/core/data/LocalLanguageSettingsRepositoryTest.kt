package com.group33.medicationreminder.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.data.contract.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLanguageSettingsRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun clearPreference() {
        context.getSharedPreferences("language_settings", 0).edit().clear().commit()
    }

    @Test fun choiceSurvivesNewRepositoryInstance() = runBlocking {
        clearPreference()
        val firstInstance = LocalLanguageSettingsRepository(context)
        assertEquals(LanguageChoice.FOLLOW_SYSTEM, firstInstance.observeChoice().first())

        assertTrue(firstInstance.setChoice(LanguageChoice.SIMPLIFIED_CHINESE) is Outcome.Success)
        assertEquals(
            LanguageChoice.SIMPLIFIED_CHINESE,
            LocalLanguageSettingsRepository(context).observeChoice().first(),
        )

        assertTrue(firstInstance.setChoice(LanguageChoice.ENGLISH) is Outcome.Success)
        assertEquals(
            LanguageChoice.ENGLISH,
            LocalLanguageSettingsRepository(context).observeChoice().first(),
        )

        assertTrue(firstInstance.setChoice(LanguageChoice.FOLLOW_SYSTEM) is Outcome.Success)
        assertEquals(
            LanguageChoice.FOLLOW_SYSTEM,
            LocalLanguageSettingsRepository(context).observeChoice().first(),
        )
    }
}
