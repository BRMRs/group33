package com.group33.medicationreminder

import android.content.Intent
import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.hamcrest.Matcher
import java.io.File
import java.io.FileOutputStream

/** Run with a real IME, including API35 at 320dp/200% font scale. */
class SharedLoginKeyboardTest {
    @Test fun englishSms() = verify(LanguageChoice.ENGLISH, "sms")
    @Test fun englishPassword() = verify(LanguageChoice.ENGLISH, "password")
    @Test fun englishReset() = verify(LanguageChoice.ENGLISH, "reset")
    @Test fun chineseSms() = verify(LanguageChoice.SIMPLIFIED_CHINESE, "sms")
    @Test fun chinesePassword() = verify(LanguageChoice.SIMPLIFIED_CHINESE, "password")
    @Test fun chineseReset() = verify(LanguageChoice.SIMPLIFIED_CHINESE, "reset")

    private fun verify(choice: LanguageChoice, mode: String) {
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
        try {
            ActivityScenario.launch<SharedLoginActivity>(Intent(context, SharedLoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                awaitLoginLayout(R.id.one_tap_button)
                onView(withId(R.id.other_methods_button)).perform(scrollTo(), click())
                awaitLoginLayout(if (mode == "sms") R.id.sms_button else R.id.password_button)
                onView(withId(if (mode == "sms") R.id.sms_button else R.id.password_button))
                    .perform(scrollTo(), click())
                awaitLoginLayout(R.id.phone_input)
                if (mode == "reset") onView(withId(R.id.forgot_password_button)).perform(scrollTo(), click())
                val lastInput = when (mode) {
                    "sms" -> R.id.phone_input // Codes are entered in the provider's scene.
                    "password" -> R.id.password_input
                    else -> R.id.new_password_input
                }
                awaitLoginLayout(lastInput)
                onView(withId(lastInput)).perform(scrollTo(), click())
                // Wait for the actual keyboard and its final layout, rather than assuming focus shows it.
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                var keyboardVisible = false
                while (!keyboardVisible && android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { keyboardVisible = ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    if (!keyboardVisible) android.os.SystemClock.sleep(50)
                }
                assertTrue("A real keyboard must be visible", keyboardVisible)
                onView(withId(R.id.form_submit_button)).perform(scrollTo(), settleKeyboardLayout)
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                    assertTrue("Scrolling must keep the keyboard open", insets.isVisible(WindowInsetsCompat.Type.ime()))
                    val button = activity.findViewById<View>(R.id.form_submit_button)
                    val location = IntArray(2).also(button::getLocationOnScreen)
                    val visible = Rect()
                    assertTrue(button.getGlobalVisibleRect(visible))
                    assertEquals("Full button height must be reachable", button.height, visible.height())
                    // The legacy decor may already be resized: subtracting IME from decor.height
                    // would count the keyboard twice. These tests use a full-screen portrait window.
                    val screen = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    activity.windowManager.defaultDisplay.getRealMetrics(screen)
                    val keyboardTop = screen.heightPixels - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    assertTrue("Submit bottom ${location[1] + button.height} must be above keyboard $keyboardTop",
                        location[1] + button.height <= keyboardTop)
                }
                checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { screenshot ->
                    FileOutputStream(File(context.filesDir, "keyboard-${choice.name}-$mode.png")).use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                // A physical Espresso tap must reach the button and reveal its inline status.
                onView(withId(R.id.form_submit_button)).perform(click())
                if (BuildConfig.PHONE_AUTH_LOCAL_TRIAL && mode != "password") {
                    onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.login_trial_privacy_title))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                    onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.cancel)).perform(click())
                } else {
                    awaitLoginLayout(R.id.form_feedback_card)
                    onView(withId(R.id.form_feedback_card)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                }
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                awaitLoginLayout(R.id.back_to_methods_button)
                onView(withId(R.id.back_to_methods_button)).perform(scrollTo(), settleKeyboardLayout, click())
                awaitLoginLayout(R.id.one_tap_button)
                onView(withId(R.id.one_tap_button)).perform(scrollTo())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            }
        } finally {
            runBlocking { app.languageSettings.setChoice(LanguageChoice.FOLLOW_SYSTEM) }
            instrumentation.runOnMainSync { Group33Application.applyLanguage(LanguageChoice.FOLLOW_SYSTEM, app) }
        }
    }

    private val settleKeyboardLayout = object : ViewAction {
        override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(View::class.java)
        override fun getDescription() = "allow keyboard animation and scroll frames to finish"
        override fun perform(uiController: UiController, view: View) { uiController.loopMainThreadForAtLeast(500) }
    }
}
