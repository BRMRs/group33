package com.group33.medicationreminder.elder

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
import com.group33.medicationreminder.MainActivity
import com.group33.medicationreminder.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleEntryNavigationTest {
    @Test fun englishLauncherKeepsBothRolesReachable() = verifyNavigation("en")
    @Test fun chineseLauncherKeepsBothRolesReachable() = verifyNavigation("zh-CN")

    private fun verifyNavigation(language: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
        }
        val context = instrumentation.targetContext
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try { ActivityScenario.launch<MainActivity>(launcher).use {
            onView(withId(R.id.elder_button)).perform(scrollTo(), click())
            onView(withId(R.id.elder_title)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.caregiver_button)).perform(scrollTo(), click())
            onView(withId(R.id.caregiver_title)).check(matches(isDisplayed()))
            pressBack()
        } } finally {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
        }
    }
}
