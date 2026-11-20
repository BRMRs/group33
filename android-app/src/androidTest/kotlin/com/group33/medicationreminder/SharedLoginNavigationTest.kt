package com.group33.medicationreminder

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** The former role-preview detour is replaced by the shared entry and its original forms. */
class SharedLoginNavigationTest {
    @Test fun englishLauncherUsesSharedEntryAndOriginalSmsForm() = verifyEntry(LanguageChoice.ENGLISH)
    @Test fun chineseLauncherUsesSharedEntryAndOriginalSmsForm() = verifyEntry(LanguageChoice.SIMPLIFIED_CHINESE)
    private fun verifyEntry(choice: LanguageChoice) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as Group33Application
        runBlocking {
            app.featureServices?.let { services ->
                services.signOut()
                withTimeout(5_000) { services.auth.observeSession().first { it.readiness == AuthReadiness.SIGNED_OUT } }
            }
            app.languageSettings.setChoice(choice)
        }
        instrumentation.runOnMainSync { Group33Application.applyLanguage(choice, app) }
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(SharedLoginActivity::class.java.name, launcher.component!!.className)
        try { ActivityScenario.launch<SharedLoginActivity>(launcher).use { scenario ->
            scenario.onActivity { activity -> assertEquals(
                if (choice == LanguageChoice.SIMPLIFIED_CHINESE) "zh" else "en",
                androidx.core.os.ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language)
            }
            awaitLoginLayout(R.id.one_tap_button)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            onView(withId(R.id.one_tap_button)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.other_methods_button)).perform(scrollTo(), settleLoginScroll, click())
            awaitLoginLayout(R.id.sms_button)
            onView(withId(R.id.sms_button)).perform(scrollTo(), click())
            awaitLoginLayout(R.id.phone_input)
            onView(withId(R.id.phone_input)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.back_to_methods_button)).perform(scrollTo(), click())
            awaitLoginLayout(R.id.one_tap_button)
            onView(withId(R.id.one_tap_button)).perform(scrollTo()).check(matches(isDisplayed()))
        } } finally {
            runBlocking { app.languageSettings.setChoice(LanguageChoice.FOLLOW_SYSTEM) }
            instrumentation.runOnMainSync { Group33Application.applyLanguage(LanguageChoice.FOLLOW_SYSTEM, app) }
        }
    }
}
