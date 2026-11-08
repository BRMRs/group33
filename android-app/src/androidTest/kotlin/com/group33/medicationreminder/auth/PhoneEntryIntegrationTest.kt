package com.group33.medicationreminder.auth

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.*
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Never agrees to the provider scene or sends SMS. Exercises the actual shared entry. */
class PhoneEntryIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun signedOut() = runBlocking {
        val app = context.applicationContext as Group33Application
        app.phoneAuth?.signOut()
        app.featureServices?.let {
            it.signOut()
            withTimeout(5_000) { it.auth.observeSession().first { s -> s.readiness == AuthReadiness.SIGNED_OUT } }
        }
    }
    @Test fun forged_demo_extra_never_creates_a_phone_or_anonymous_session() {
        assumeTrue(BuildConfig.PHONE_AUTH_BASE_URL.isBlank())
        signedOut()
        ActivityScenario.launch<SharedLoginActivity>(Intent(context, SharedLoginActivity::class.java)
            .putExtra("com.group33.medicationreminder.LOCAL_AUTH_DEMO", true)).use {
            awaitLoginLayout(R.id.one_tap_button)
            onView(withId(R.id.one_tap_button)).perform(scrollTo(), click())
            onView(withId(R.id.choice_feedback_card)).check(matches(isDisplayed()))
            assertNull((context.applicationContext as Group33Application).featureServices?.scope?.value)
        }
    }
    @Test fun phone_profile_shows_consent_without_anonymous_login_controls() {
        assumeTrue(BuildConfig.PHONE_AUTH_BASE_URL.isNotBlank() && BuildConfig.PHONE_AUTH_LOCAL_TRIAL)
        signedOut()
        ActivityScenario.launch<SharedLoginActivity>(Intent(context, SharedLoginActivity::class.java)).use {
            awaitLoginLayout(R.id.one_tap_button)
            onView(withText(R.string.chain_login_elder)).check(doesNotExist())
            onView(withText(R.string.chain_login_caregiver)).check(doesNotExist())
            onView(withId(R.id.one_tap_button)).perform(scrollTo(), click())
            onView(withText(R.string.login_trial_privacy_title)).check(matches(isDisplayed()))
            onView(withText(android.R.string.cancel)).perform(click())
            assertNull((context.applicationContext as Group33Application).featureServices?.scope?.value)
        }
    }
    @Test fun sms_reentry_does_not_reuse_a_previous_typed_number() {
        signedOut()
        ActivityScenario.launch<SharedLoginActivity>(Intent(context, SharedLoginActivity::class.java)).use {
            awaitLoginLayout(R.id.one_tap_button)
            closeSoftKeyboard()
            onView(withId(R.id.other_methods_button)).perform(scrollTo(), settleLoginScroll, click())
            awaitLoginLayout(R.id.sms_button)
            onView(withId(R.id.sms_button)).perform(scrollTo(), click())
            awaitLoginLayout(R.id.phone_input)
            onView(withId(R.id.phone_input)).perform(scrollTo(), replaceText("13800000000"), closeSoftKeyboard())
            onView(withId(R.id.back_to_methods_button)).perform(scrollTo(), click())
            awaitLoginLayout(R.id.sms_button)
            onView(withId(R.id.sms_button)).perform(scrollTo(), click())
            awaitLoginLayout(R.id.phone_input)
            onView(withId(R.id.phone_input)).check(matches(withText("")))
            assertNull((context.applicationContext as Group33Application).featureServices?.scope?.value)
        }
    }
}
