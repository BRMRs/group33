package com.group33.medicationreminder.caregiver.plan

import android.content.Intent
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import org.junit.Assert.*
import org.junit.Test

class PhotoTextSuggestionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun buttons(view: View): List<Button> = (if (view is Button) listOf(view) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList()
    private fun fixture(): Bitmap = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
            drawText("EXAMPLE MEDICINE", 40f, 120f, paint)
            drawText("TEST ONLY 500mg", 40f, 240f, paint)
            drawText("示例药品", 40f, 340f, paint)
        }
    }
    @Test fun blankImageAllowsRetryAndClearDuringRecognitionDropsResults() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PlanEditorDebugActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        lateinit var view: PhotoTextSuggestions
        var applied = false
        try {
            ui {
                view = PhotoTextSuggestions(host, { applied = true }, { applied = true })
                host.setContentView(view); view.bind(bitmap); view.setEditable(true)
                buttons(view).single().performClick()
            }
            var ready = false
            val deadline = System.nanoTime() + 60_000_000_000L
            while (!ready && System.nanoTime() < deadline) {
                Thread.sleep(200); ui { ready = buttons(view).first().isEnabled }
            }
            assertTrue("Blank OCR completes and retry remains available", ready)
            ui {
                assertEquals(1, buttons(view).size)
                assertEquals(host.getString(R.string.ocr_empty), (view.getChildAt(1) as android.widget.TextView).text.toString())
                buttons(view).single().performClick(); view.clear()
            }
            Thread.sleep(1500)
            ui { assertEquals(View.GONE, view.visibility); assertFalse(buttons(view).single().isEnabled); assertFalse(applied) }
        } finally { ui { host.finish() }; bitmap.recycle() }
    }
    @Test fun realRecognitionFillsBlankNameButPreservesUserInput() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PlanEditorDebugActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup)
            (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        var candidate: Button? = null
        lateinit var name: android.widget.EditText
        var original = ""
        try {
            instrumentation.waitForIdleSync()
            ui {
                name = all(host.window.decorView).filterIsInstance<android.widget.EditText>().first()
                original = name.text.toString()
                buttons(host.window.decorView).single { it.text == host.getString(R.string.ocr_scan) }.performClick()
            }
            val deadline = System.nanoTime() + 60_000_000_000L
            while (candidate == null && System.nanoTime() < deadline) {
                Thread.sleep(200)
                ui { candidate = buttons(host.window.decorView).firstOrNull { it.text.contains("C2 DEBUG FIXTURE") } }
            }
            assertNotNull(candidate)
            ui {
                candidate!!.performClick(); assertEquals(original, name.text.toString())
                name.setText(""); candidate!!.performClick(); assertEquals("C2 DEBUG FIXTURE", name.text.toString())
            }
        } finally { ui { host.finish() } }
    }
    @Test fun realBundledRecognitionProducesSelectableLiteralText() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PlanEditorDebugActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val bitmap = fixture()
        lateinit var view: PhotoTextSuggestions
        var applied = ""
        try {
            ui {
                view = PhotoTextSuggestions(host, { applied = it }, {})
                host.setContentView(view); view.bind(bitmap); view.setEditable(true)
                buttons(view).single().performClick()
            }
            val deadline = System.nanoTime() + 60_000_000_000L
            var candidate: Button? = null
            while (candidate == null && System.nanoTime() < deadline) {
                Thread.sleep(200)
                ui { candidate = buttons(view).firstOrNull { it.text.contains("EXAMPLE MEDICINE") } }
            }
            assertNotNull("Actual OCR must find fictional English label", candidate)
            ui {
                assertTrue(buttons(view).any { it.text.contains("示例药品") })
                candidate!!.performClick()
                assertEquals("EXAMPLE MEDICINE", applied)
                view.setEditable(false); candidate!!.performClick()
                view.clear(); applied = ""
                candidate!!.performClick()
                assertEquals("", applied)
                assertEquals(View.GONE, view.visibility)
            }
        } finally { ui { host.finish() }; bitmap.recycle() }
    }
}
