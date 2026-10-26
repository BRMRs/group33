package com.group33.medicationreminder.elder

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderCompletedStateTest {
    @Test
    fun takingDoseRemovesPromptAndActionsButKeepsCompletedStatus() {
        ActivityScenario.launch(ElderHomeActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.due_now_label).visibility)
                activity.findViewById<View>(R.id.due_taken_button).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.elder_status_taken),
                    activity.findViewById<TextView>(R.id.due_status_chip).text.toString(),
                )
                for (id in listOf(R.id.due_now_label, R.id.due_taken_button, R.id.due_remind_later_button)) {
                    assertEquals(View.GONE, activity.findViewById<View>(id).visibility)
                }
            }
        }
    }
}
