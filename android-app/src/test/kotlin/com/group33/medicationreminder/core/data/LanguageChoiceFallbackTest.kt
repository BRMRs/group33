package com.group33.medicationreminder.core.data

import com.group33.medicationreminder.core.data.contract.LanguageChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageChoiceFallbackTest {
    @Test fun `missing or unknown saved choice follows system`() {
        assertEquals(LanguageChoice.FOLLOW_SYSTEM, LocalLanguageSettingsRepository.readChoice(null))
        assertEquals(LanguageChoice.FOLLOW_SYSTEM, LocalLanguageSettingsRepository.readChoice("unknown"))
    }
}
