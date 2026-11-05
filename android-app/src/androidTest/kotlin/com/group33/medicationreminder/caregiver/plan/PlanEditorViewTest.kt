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

    @Test fun unknownAndCachePendingOnlyReconcileOriginalOperation() {
        for (pendingCache in listOf(false, true)) withBoundEditor { host, editor, fixture ->
            fixture.resultState = if (pendingCache) com.group33.medicationreminder.core.data.contract.OnlineCommandState.CONFIRMED
                else com.group33.medicationreminder.core.data.contract.OnlineCommandState.AWAITING_CONFIRMATION
            fixture.cache = com.group33.medicationreminder.core.data.contract.CacheState.REFRESH_REQUIRED
            onUi { checks(editor).first().isChecked = true; action(editor).performClick() }
            onUi {
                assertEquals(host.getString(if (pendingCache) R.string.c2_plan_refresh_cache else R.string.c2_plan_check_result), action(editor).text.toString())
                assertTrue(edits(editor).none { it.isEnabled })
                action(editor).performClick()
            }
            onUi {
                assertEquals(1, fixture.begins)
                assertEquals(1, fixture.calls.size)
                assertEquals(listOf(fixture.calls.single().operationId), fixture.resumes)
                assertFalse(action(editor).isEnabled)
            }
        }
    }

    @Test fun unsentRetryRetainsIntentAndRejectedRequiresChangedInput() {
        for (rejected in listOf(false, true)) withBoundEditor { _, editor, fixture ->
            fixture.resultState = if (rejected) com.group33.medicationreminder.core.data.contract.OnlineCommandState.REJECTED
                else com.group33.medicationreminder.core.data.contract.OnlineCommandState.NOT_SUBMITTED
            onUi { checks(editor).first().isChecked = true; action(editor).performClick() }
            onUi {
                assertTrue(edits(editor).all { it.isEnabled })
                assertEquals("Test name", edits(editor).first().text.toString())
                action(editor).performClick()
            }
            if (rejected) {
                onUi { action(editor).performClick() }
                onUi {
                    assertEquals(1, fixture.calls.size)
                    edits(editor).first().setText("Corrected name")
                    action(editor).performClick()
                }
                onUi { assertEquals(2, fixture.begins); assertEquals(2, fixture.calls.size) }
            } else onUi {
                assertEquals(1, fixture.begins)
                assertEquals(2, fixture.calls.size)
                assertEquals(fixture.calls[0].operationId, fixture.calls[1].operationId)
            }
        }
    }

    @Test fun invalidFieldsAndUnacknowledgedZoneDoNotSubmit() = withBoundEditor { _, editor, fixture ->
        onUi {
            checks(editor).first().isChecked = true
            edits(editor)[0].setText("")
            edits(editor)[3].setText("25:00")
            edits(editor)[5].setText("2026-01-01")
            action(editor).performClick()
        }
        onUi {
            assertEquals(0, fixture.begins)
            for (index in listOf(0, 3, 5)) assertNotNull(edits(editor)[index].error)
            assertEquals("25:00", edits(editor)[3].text.toString())
            edits(editor)[0].setText("Test name")
            edits(editor)[3].setText("08:00")
            edits(editor)[5].setText("")
            edits(editor)[6].setText("Europe/London")
            action(editor).performClick()
        }
        onUi {
            assertEquals(0, fixture.begins)
            assertEquals(View.VISIBLE, checks(editor).last().visibility)
            checks(editor).last().isChecked = true
            action(editor).performClick()
        }
        onUi { assertEquals(1, fixture.begins) }
    }

    @Test fun rebindingCancelsOldSaveAndClearsPhotoConfirmation() = withBoundEditor { host, editor, old ->
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        old.gate = gate
        onUi { checks(editor).first().isChecked = true; action(editor).performClick() }
        onUi { assertEquals(1, old.calls.size) }
        val replacement = PlanEditorFixture()
        onUi {
            val form = PlanFormViewModel().apply { updateInput(PlanFormInput("New context", "New dose",
                localTimes = listOf("09:00"), startDate = "2026-10-30", executionTimeZoneId = "Asia/Shanghai")) }
            editor.bind(host, "Another context", null, null, "Asia/Shanghai", "Asia/Shanghai", form, replacement.model)
            gate.complete(Unit)
        }
        onUi {
            assertEquals("New context", edits(editor).first().text.toString())
            assertTrue(edits(editor).all { it.isEnabled })
            assertFalse(checks(editor).first().isChecked)
            assertFalse(checks(editor).first().isEnabled)
            assertNull(descendants(editor).filterIsInstance<ImageView>().single().drawable)
            action(editor).performClick()
        }
        onUi { assertEquals(0, replacement.begins); assertEquals(1, old.calls.size) }
    }

    @Test fun destroyingHostClearsDraftAndDisablesActions() = withBoundEditor { host, editor, fixture ->
        onUi { edits(editor).first().setText("Private draft"); host.finish() }
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var destroyed = false
        while (!destroyed && android.os.SystemClock.uptimeMillis() < deadline) {
            onUi { destroyed = host.isDestroyed }
            if (!destroyed) android.os.SystemClock.sleep(50)
        }
        assertTrue("Host destruction must finish before checking cleanup", destroyed)
        onUi {
            assertTrue(edits(editor).all { it.text.isEmpty() && !it.isEnabled })
            assertNull(descendants(editor).filterIsInstance<ImageView>().single().drawable)
            assertFalse(action(editor).isEnabled)
            action(editor).performClick()
            assertEquals(0, fixture.begins)
        }
    }

    private fun onUi(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync(block)
        instrumentation.waitForIdleSync()
    }
    private fun edits(editor: PlanEditorView) = descendants(editor).filterIsInstance<EditText>()
    private fun checks(editor: PlanEditorView) = descendants(editor).filterIsInstance<CheckBox>()
    private fun action(editor: PlanEditorView) = descendants(editor).filterIsInstance<Button>().filterNot { it is CheckBox }.single()

    private fun withBoundEditor(test: (PlanEditorDebugActivity, PlanEditorView, PlanEditorFixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            PlanEditorDebugActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as PlanEditorDebugActivity
        val fixture = PlanEditorFixture()
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        lateinit var editor: PlanEditorView
        try {
            onUi {
                editor = descendants(host.window.decorView).filterIsInstance<PlanEditorView>().single()
                val form = PlanFormViewModel().apply { updateInput(PlanFormInput("Test name", "Test dose",
                    localTimes = listOf("08:00"), startDate = "2026-10-29", executionTimeZoneId = "Asia/Shanghai")) }
                editor.bind(host, "Test Elder", com.group33.shared.contract.MedicationPhotoView("test-photo", "elder", "image/png", 1),
                    bitmap, "Asia/Shanghai", "Asia/Shanghai", form, fixture.model)
            }
            test(host, editor, fixture)
        } finally { onUi { editor.clear(); host.finish() }; bitmap.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
