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
        val evidencePrefix = InstrumentationRegistry.getArguments().getString("c1KeyboardEvidenceRun")?.let {
            require(it.matches(Regex("[a-z0-9-]+")))
            "$it-"
        }.orEmpty()
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
                // Limit orientation changes to this Activity; leave the device settings untouched.
                val orientation = when (InstrumentationRegistry.getArguments().getString("c1KeyboardOrientation")) {
                    "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> null
                }
                orientation?.let { requested ->
                    scenario.onActivity { it.requestedOrientation = requested }
                    val expected = if (requested == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                    val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                    var matched = false
                    while (!matched && android.os.SystemClock.uptimeMillis() < deadline) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { matched = it.resources.configuration.orientation == expected }
                        if (!matched) android.os.SystemClock.sleep(16)
                    }
                    assertTrue("Requested Activity orientation must be applied", matched)
                }
                try {
                    onView(withId(R.id.other_methods_button)).perform(closeSoftKeyboard(), scrollTo(),
                        settleKeyboardLayout, awaitLayout, click())
                    onView(withId(if (mode == "sms") R.id.sms_button else R.id.password_button))
                        .perform(scrollTo(), click())
                    if (mode == "reset") onView(withId(R.id.forgot_password_button)).perform(scrollTo(), click())
                    val lastInput = when (mode) {
                        "sms" -> R.id.code_input
                        "password" -> R.id.password_input
                        else -> R.id.new_password_input
                    }
                    onView(withId(R.id.phone_input)).perform(scrollTo(), click())
                    assertFocusedInputAboveKeyboard(scenario, R.id.phone_input)
                    val followingInputs = if (mode == "reset") listOf(R.id.code_input, lastInput) else listOf(lastInput)
                    followingInputs.forEach { inputId ->
                        // Focus moves while the IME is already open; do not scroll on the test's behalf.
                        scenario.onActivity { assertTrue(it.findViewById<View>(inputId).requestFocus()) }
                        assertFocusedInputAboveKeyboard(scenario, inputId)
                    }
                    // Focus and real IME visibility were already asserted without test scrolling.
                    // A redundant scroll-and-tap here races password IME relayout on API27.
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
                    // Let the IME finish switching from numeric to password before computing scroll bounds.
                    onView(withId(R.id.form_submit_button)).perform(settleKeyboardLayout, scrollTo(),
                        settleKeyboardLayout, awaitLayout)
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
                        FileOutputStream(File(context.filesDir, "keyboard-$evidencePrefix${choice.name}-$mode.png")).use {
                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        screenshot.recycle()
                    }
                    // A physical Espresso tap must reach the button and reveal its inline status.
                    onView(withId(R.id.form_submit_button)).perform(click())
                    scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.login_status).visibility) }
                    androidx.test.espresso.Espresso.pressBack()
                    // A clamped NestedScrollView can keep animating after its position stops changing.
                    // Its first DOWN then stops the scroll instead of reaching the button.
                    onView(withId(R.id.back_to_methods_button)).perform(awaitKeyboardHidden, scrollTo(),
                        settleKeyboardLayout, awaitLayout, click())
                    onView(withId(R.id.one_tap_button)).perform(awaitLayout, scrollTo())
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                } catch (failure: Throwable) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                        FileOutputStream(File(context.filesDir, "keyboard-failure-$evidencePrefix${choice.name}-$mode.png")).use {
                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        screenshot.recycle()
                    }
                    throw failure
                }
            }
        } finally {
            runBlocking { app.languageSettings.setChoice(LanguageChoice.FOLLOW_SYSTEM) }
            instrumentation.runOnMainSync { Group33Application.applyLanguage(LanguageChoice.FOLLOW_SYSTEM, app) }
        }
    }

    private fun assertFocusedInputAboveKeyboard(scenario: ActivityScenario<SharedLoginActivity>, inputId: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var ready = false
        var observed = "no layout observed"
        while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val field = activity.findViewById<View>(inputId)
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                val screen = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.getRealMetrics(screen)
                val topOfKeyboard = screen.heightPixels - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val position = IntArray(2).also(field::getLocationOnScreen)
                val visible = Rect()
                val visibleField = field.getGlobalVisibleRect(visible)
                val fieldBounds = Rect(position[0], position[1], position[0] + field.width, position[1] + field.height)
                ready = field.hasFocus() && insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    visibleField && visible.contains(fieldBounds) && field.height > 0 &&
                    position[1] >= 0 && position[1] + field.height <= topOfKeyboard
                observed = "input=$inputId focus=${field.hasFocus()} y=${position[1]} " +
                    "height=${field.height} visible=${visible.height()} keyboardTop=$topOfKeyboard"
            }
            if (!ready) android.os.SystemClock.sleep(16)
        }
        assertTrue("Focused input must become fully visible without a test scroll: $observed", ready)
    }

    private val awaitLayout = object : ViewAction {
        override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(View::class.java)
        override fun getDescription() = "wait for the returned login choice to finish layout"
        override fun perform(uiController: UiController, view: View) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            var previousPosition: List<Int>? = null
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val position = IntArray(2).also(view::getLocationOnScreen).toList()
                val ready = view.isShown && view.width > 0 && view.height > 0 &&
                    !view.isLayoutRequested && !view.rootView.isLayoutRequested && view.hasWindowFocus()
                if (ready && previousPosition == position) return
                previousPosition = if (ready) position else null
                uiController.loopMainThreadForAtLeast(16)
            }
            fail("Login control ${view.id} did not settle: shown=${view.isShown}, focus=${view.hasWindowFocus()}")
        }
    }

    private val awaitKeyboardHidden = object : ViewAction {
        override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(View::class.java)
        override fun getDescription() = "wait for keyboard dismissal before clicking the return control"
        override fun perform(uiController: UiController, view: View) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val insets = ViewCompat.getRootWindowInsets(view)
                if (insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())) return
                uiController.loopMainThreadForAtLeast(16)
            }
            fail("Keyboard did not dismiss before return navigation")
        }
    }

    private val settleKeyboardLayout = object : ViewAction {
        override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(View::class.java)
        override fun getDescription() = "allow keyboard animation and scroll frames to finish"
        override fun perform(uiController: UiController, view: View) { uiController.loopMainThreadForAtLeast(500) }
    }
}
