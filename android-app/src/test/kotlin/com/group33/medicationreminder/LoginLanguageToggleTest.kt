package com.group33.medicationreminder

import com.group33.medicationreminder.core.data.contract.LanguageChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginLanguageToggleTest {
    @Test fun `Chinese display switches to English`() {
        assertEquals(LanguageChoice.ENGLISH, nextLanguageChoice("zh"))
    }

    @Test fun `English or another system language switches to Chinese`() {
        assertEquals(LanguageChoice.SIMPLIFIED_CHINESE, nextLanguageChoice("en"))
        assertEquals(LanguageChoice.SIMPLIFIED_CHINESE, nextLanguageChoice("fr"))
    }
}
