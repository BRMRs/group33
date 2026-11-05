package com.group33.medicationreminder.caregiver

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaregiverNavigationUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun useEnglishAndClearSavedLanguage() {
        context.getSharedPreferences("language_settings", 0).edit().clear().commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
    }

    @After
    fun restoreSystemLanguage() {
        context.getSharedPreferences("language_settings", 0).edit().clear().commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun bottomNavigationSwitchesDestinationsAndShowsNoElderGuidance() {
        ActivityScenario.launch<CaregiverHomeActivity>(Intent(context, CaregiverHomeActivity::class.java)).use { scenario ->
            onView(withId(R.id.bottom_navigation)).check(matches(isDisplayed()))
            onView(withId(R.id.current_elder_value)).check(matches(withText(R.string.caregiver_no_current_elder)))
            onView(withId(R.id.page_message)).check(matches(withText(R.string.caregiver_context_unavailable)))

            onView(withId(R.id.nav_records)).perform(click())
            onView(withId(R.id.page_title)).check(matches(withText(R.string.caregiver_records_title)))
            onView(withId(R.id.page_message)).check(matches(withText(R.string.caregiver_context_unavailable)))
            scenario.onActivity { activity ->
                assertSelectedDestination(activity, R.id.nav_records)
            }

            onView(withId(R.id.nav_settings)).perform(click())
            scenario.onActivity { activity ->
                assertSelectedDestination(activity, R.id.nav_settings)
            }
            onView(withId(R.id.page_title)).check(matches(withText(R.string.caregiver_settings_title)))
            onView(withId(R.id.language_button)).check(matches(isDisplayed()))

            onView(withId(R.id.nav_plans)).perform(click())
            onView(withId(R.id.page_title)).check(matches(withText(R.string.caregiver_plans_title)))
            onView(withId(R.id.language_button)).check(matches(org.hamcrest.Matchers.not(isDisplayed())))
        }
    }

    @Test
    fun changingLanguageFromSettingsKeepsSettingsSelected() {
        ActivityScenario.launch<CaregiverHomeActivity>(Intent(context, CaregiverHomeActivity::class.java)).use { scenario ->
            onView(withId(R.id.nav_settings)).perform(click())
            onView(withId(R.id.language_button)).perform(click())
            onView(withId(R.id.page_title)).check(matches(withText(R.string.caregiver_settings_title)))
            onView(withId(R.id.language_button)).check(matches(withText(R.string.language_chinese)))
            scenario.onActivity { activity ->
                assertSelectedDestination(activity, R.id.nav_settings)
                assertEquals("计划", activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    .menu.findItem(R.id.nav_plans).title.toString())
            }
        }
    }

    @Test
    fun localPreviewLabelsSampleElderWithoutCreatingARealRelationship() {
        val intent = Intent(context, CaregiverHomeActivity::class.java)
            .putExtra("com.group33.medicationreminder.LOCAL_NAV_DEMO", true)
        ActivityScenario.launch<CaregiverHomeActivity>(intent).use {
            onView(withId(R.id.demo_banner)).check(matches(isDisplayed()))
            onView(withId(R.id.current_elder_value)).check(matches(withText("Demo elder")))
            onView(withId(R.id.current_elder_hint)).check(matches(withText(R.string.caregiver_preview_relationship)))
        }
    }

    private fun assertSelectedDestination(activity: CaregiverHomeActivity, expectedId: Int) {
        assertEquals(expectedId, activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId)
    }
}
