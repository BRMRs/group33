package com.group33.medicationreminder

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.hamcrest.Matcher

/** The former role-preview detour is replaced by the shared entry and its original forms. */
class SharedLoginNavigationTest {
    @Test fun englishLauncherUsesSharedEntryAndOriginalSmsForm() = verifyEntry(LanguageChoice.ENGLISH)
    @Test fun chineseLauncherUsesSharedEntryAndOriginalSmsForm() = verifyEntry(LanguageChoice.SIMPLIFIED_CHINESE)
    private fun verifyEntry(choice: LanguageChoice) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as Group33Application
        val originalChoice = runBlocking { app.languageSettings.observeChoice().first() }
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
            val language = if (choice == LanguageChoice.SIMPLIFIED_CHINESE) "zh" else "en"
            onView(withId(R.id.one_tap_button)).perform(awaitLayout(language), scrollTo())
                .check(matches(isDisplayed()))
            scenario.onActivity { activity -> assertEquals(
                language,
                androidx.core.os.ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language)
            }
            onView(withId(R.id.other_methods_button)).perform(awaitLayout(language), scrollTo(), awaitLayout(language), click())
            onView(withId(R.id.sms_button)).perform(awaitLayout(language), scrollTo(), awaitLayout(language), click())
            onView(withId(R.id.phone_input)).perform(awaitLayout(language), scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.back_to_methods_button)).perform(awaitLayout(language), scrollTo(), awaitLayout(language), click())
            onView(withId(R.id.one_tap_button)).perform(awaitLayout(language), scrollTo()).check(matches(isDisplayed()))
        } } finally {
            runBlocking { app.languageSettings.setChoice(originalChoice) }
            instrumentation.runOnMainSync { Group33Application.applyLanguage(originalChoice, app) }
        }
    }

    /** A resumed Activity can still be laying out a newly visible form or gaining window focus. */
    private fun awaitLayout(language: String) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
        override fun getDescription() = "wait for focused, laid-out $language login content"
        override fun perform(uiController: UiController, view: View) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var previousPosition: List<Int>? = null
            do {
                val position = IntArray(2).also(view::getLocationOnScreen).toList()
                val actualLanguage = androidx.core.os.ConfigurationCompat.getLocales(view.resources.configuration)[0]?.language
                val ready = view.isShown && view.width > 0 && view.height > 0 &&
                    !view.isLayoutRequested && !view.rootView.isLayoutRequested &&
                    view.hasWindowFocus() && actualLanguage == language
                if (ready && previousPosition == position) return
                previousPosition = if (ready) position else null
                uiController.loopMainThreadForAtLeast(16)
            } while (SystemClock.uptimeMillis() < deadline)
            assertTrue("Login view ${view.id} did not settle: size=${view.width}x${view.height}, " +
                "shown=${view.isShown}, focus=${view.hasWindowFocus()}, layout=${view.isLayoutRequested}", false)
        }
    }
}
