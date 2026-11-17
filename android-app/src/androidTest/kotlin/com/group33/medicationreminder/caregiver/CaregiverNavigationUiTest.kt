package com.group33.medicationreminder.caregiver

import android.content.Intent
import androidx.core.os.ConfigurationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.LocalLanguageSettingsRepository
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.data.contract.Outcome
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaregiverNavigationUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as Group33Application
    private var originalChoice = LanguageChoice.FOLLOW_SYSTEM

    @Before
    fun useEnglishForEveryTest() {
        runBlocking {
            originalChoice = app.languageSettings.observeChoice().first()
            app.featureServices?.signOut?.invoke()
        }
        // Use the production context-aware path: on API33+ no Activity delegate exists yet.
        // Clearing preferences alone also leaves the Application repository's choice unchanged.
        applyLanguage(LanguageChoice.ENGLISH)
    }

    @After
    fun restoreOriginalLanguage() {
        applyLanguage(originalChoice)
    }

    @Test
    fun bottomNavigationSwitchesDestinationsAndShowsNoElderGuidance() {
        ActivityScenario.launch<CaregiverHomeActivity>(Intent(context, CaregiverHomeActivity::class.java)).use { scenario ->
            awaitCaregiverLanguage("en")
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
            awaitCaregiverLanguage("en")
            scenario.onActivity { activity ->
                assertEquals("Test must start in English", "en",
                    ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language)
            }
            onView(withId(R.id.nav_settings)).perform(click())
            onView(withId(R.id.language_button)).check(matches(withText(R.string.language_english)))
            onView(withId(R.id.language_button)).perform(click())
            assertSettingsLanguage(scenario, "zh", R.string.language_chinese, "计划")

            onView(withId(R.id.language_button)).perform(click())
            assertSettingsLanguage(scenario, "en", R.string.language_english, "Plans")
        }
    }

    @Test
    fun chineseLanguageAndSettingsSelectionSurviveRecreation() {
        ActivityScenario.launch<CaregiverHomeActivity>(Intent(context, CaregiverHomeActivity::class.java)).use { scenario ->
            awaitCaregiverLanguage("en")
            onView(withId(R.id.nav_settings)).perform(click())
            onView(withId(R.id.language_button)).perform(click())
            assertSettingsLanguage(scenario, "zh", R.string.language_chinese, "计划")
            assertEquals(LanguageChoice.SIMPLIFIED_CHINESE,
                runBlocking { LocalLanguageSettingsRepository(context).observeChoice().first() })

            scenario.recreate()
            assertSettingsLanguage(scenario, "zh", R.string.language_chinese, "计划")
        }
    }

    @Test
    fun localPreviewLabelsSampleElderWithoutCreatingARealRelationship() {
        val intent = Intent(context, CaregiverHomeActivity::class.java)
            .putExtra("com.group33.medicationreminder.LOCAL_NAV_DEMO", true)
        ActivityScenario.launch<CaregiverHomeActivity>(intent).use {
            awaitCaregiverLanguage("en")
            onView(withId(R.id.demo_banner)).check(matches(isDisplayed()))
            onView(withId(R.id.current_elder_value)).check(matches(withText("Demo elder")))
            onView(withId(R.id.current_elder_hint)).check(matches(withText(R.string.caregiver_preview_relationship)))
        }
    }

    private fun assertSelectedDestination(activity: CaregiverHomeActivity, expectedId: Int) {
        assertEquals(expectedId, activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId)
    }

    private fun applyLanguage(choice: LanguageChoice) {
        runBlocking { assertTrue(app.languageSettings.setChoice(choice) is Outcome.Success) }
        instrumentation.runOnMainSync { Group33Application.applyLanguage(choice, app) }
    }

    private fun assertSettingsLanguage(
        scenario: ActivityScenario<CaregiverHomeActivity>, language: String, buttonText: Int, plansTitle: String,
    ) {
        awaitCaregiverLanguage(language)
        onView(withId(R.id.page_title)).check(matches(withText(R.string.caregiver_settings_title)))
        onView(withId(R.id.language_button)).check(matches(withText(buttonText)))
        scenario.onActivity { activity ->
            assertSelectedDestination(activity, R.id.nav_settings)
            assertEquals(language, ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language)
            assertEquals(plansTitle, activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                .menu.findItem(R.id.nav_plans).title.toString())
        }
    }

    /** LocaleManager delivers configuration/recreation asynchronously to Espresso's click. */
    private fun awaitCaregiverLanguage(expected: String) {
        val resumed = CountDownLatch(1)
        val observed = AtomicReference("no resumed CaregiverHomeActivity")
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        fun inspect(activity: android.app.Activity) {
            if (activity is CaregiverHomeActivity) {
                val language = ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language.orEmpty()
                observed.set(language)
                if (language == expected) resumed.countDown()
            }
        }
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.RESUMED) inspect(activity)
        }
        instrumentation.runOnMainSync {
            monitor.addLifecycleCallback(callback)
            monitor.getActivitiesInStage(Stage.RESUMED).forEach(::inspect)
        }
        try {
            val completed = resumed.await(10, TimeUnit.SECONDS)
            assertTrue("Expected resumed Caregiver language $expected; last observed ${observed.get()}", completed)
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
        }
    }
}
