package com.group33.medicationreminder.caregiver.plan

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import org.junit.Assert.*
import org.junit.Test

class PlanEditorViewTest {
    @Test fun debugHomeEntryOpensBoundFormAndCanSaveFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(PlanEditorDebugActivity::class.java.name, null, false)
        val home = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            com.group33.medicationreminder.caregiver.CaregiverHomeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                descendants(home.window.decorView).filterIsInstance<Button>()
                    .single { it.text.toString() == home.getString(R.string.c2_debug_open) }.performClick()
            }
            val host = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull("Debug entry must open its test Activity", host)
            try {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val editor = descendants(host.window.decorView).filterIsInstance<PlanEditorView>().single()
                    val controls = descendants(editor)
                    assertEquals(7, controls.filterIsInstance<EditText>().size)
                    assertTrue(controls.filterIsInstance<EditText>().all { it.isEnabled })
                    controls.filterIsInstance<CheckBox>().single { it.visibility == View.VISIBLE }.isChecked = true
                    controls.filterIsInstance<Button>().filterNot { it is CheckBox }.single().performClick()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val editor = descendants(host.window.decorView).filterIsInstance<PlanEditorView>().single()
                    assertTrue(descendants(editor).filterIsInstance<EditText>().none { it.isEnabled })
                    assertFalse(descendants(editor).filterIsInstance<Button>().filterNot { it is CheckBox }.single().isEnabled)
                }
            } finally { host?.finish() }
        } finally {
            home.finish()
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun unboundEditorCannotSubmitAndClearRemovesSensitiveInputAndImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = PlanEditorView(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Group33))
            val controls = descendants(view)
            assertEquals(7, controls.filterIsInstance<EditText>().size)
            assertFalse(controls.filterIsInstance<Button>().filterNot { it is CheckBox }.single().isEnabled)
            controls.filterIsInstance<EditText>().forEach { it.setText("private draft"); it.error = "error" }
            controls.filterIsInstance<ImageView>().single().setImageResource(android.R.drawable.ic_menu_camera)
            view.clear()
            controls.filterIsInstance<EditText>().forEach {
                assertEquals("", it.text.toString()); assertNull(it.error); assertFalse(it.isEnabled)
            }
            assertNull(controls.filterIsInstance<ImageView>().single().drawable)
            assertTrue(controls.filterIsInstance<CheckBox>().none { it.isChecked })
        }
    }

    @Test fun eachEditorHasAPersistentAssociatedLabel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = PlanEditorView(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Group33))
            val controls = descendants(view)
            controls.filterIsInstance<EditText>().forEach { edit ->
                assertTrue(controls.any { it !== edit && it.labelFor == edit.id })
                assertTrue(edit.minimumHeight >= (48 * view.resources.displayMetrics.density).toInt())
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
