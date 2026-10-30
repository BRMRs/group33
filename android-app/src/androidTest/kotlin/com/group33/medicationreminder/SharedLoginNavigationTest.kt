package com.group33.medicationreminder

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedLoginNavigationTest {
    @Test
    fun englishLauncherKeepsBothRolePreviewsReachable() = verifyRoleNavigation("en")

    @Test
    fun chineseLauncherKeepsBothRolePreviewsReachable() = verifyRoleNavigation("zh-CN")

    private fun verifyRoleNavigation(language: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try { ActivityScenario.launch<SharedLoginActivity>(launcher).use {
            onView(withId(R.id.role_preview_button)).perform(scrollTo(), click())
            onView(withId(R.id.elder_button)).perform(scrollTo(), click())
            onView(withId(R.id.elder_title)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.caregiver_button)).perform(scrollTo(), click())
            onView(withId(R.id.caregiver_header)).check(matches(isDisplayed()))
            pressBack()
            pressBack()
            onView(withId(R.id.one_tap_button)).perform(scrollTo()).check(matches(isDisplayed()))
        } } finally {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
        }
    }
}
