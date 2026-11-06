package com.group33.medicationreminder.caregiver

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.group33.medicationreminder.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingRequestPreviewUiTest {
    @Test fun leadingZeroCodeShowsPendingThenConfirmedTerminalStatus() {
        ActivityScenario.launch(PairingRequestPreviewActivity::class.java).use {
            onView(withId(R.id.pairing_code_input)).perform(typeText("00000001"), closeSoftKeyboard())
            onView(withId(R.id.pairing_submit_button)).perform(scrollTo(), click())
            onView(withText(R.string.pairing_status_pending)).check(matches(isDisplayed()))
            onView(withId(R.id.pairing_refresh_button)).perform(scrollTo(), click())
            onView(withText(R.string.pairing_status_accepted)).check(matches(isDisplayed()))
        }
    }

    @Test fun rateLimitAndUnknownResultHaveDistinctRecoveryMessages() {
        ActivityScenario.launch(PairingRequestPreviewActivity::class.java).use {
            onView(withId(R.id.pairing_code_input)).perform(typeText("00000004"), closeSoftKeyboard())
            onView(withId(R.id.pairing_submit_button)).perform(scrollTo(), click())
            onView(withText(R.string.pairing_notice_rate_title)).check(matches(isDisplayed()))
            onView(withId(R.id.pairing_code_input)).perform(scrollTo(), androidx.test.espresso.action.ViewActions.replaceText("00000005"))
            onView(withId(R.id.pairing_submit_button)).perform(scrollTo(), click())
            onView(withText(R.string.pairing_notice_unknown_title)).check(matches(isDisplayed()))
            onView(withId(R.id.pairing_refresh_button)).perform(scrollTo(), click())
            onView(withText(R.string.pairing_notice_unknown_title)).check(matches(isDisplayed()))
        }
    }
}
