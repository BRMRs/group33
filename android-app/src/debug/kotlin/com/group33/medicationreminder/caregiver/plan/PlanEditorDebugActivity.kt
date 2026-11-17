package com.group33.medicationreminder.caregiver.plan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.CacheState
import com.group33.medicationreminder.core.data.contract.OnlineCommandState
import com.group33.shared.contract.MedicationPhotoView
import java.time.LocalDate

/** Explicit sandbox: all repositories and the drawn photo fixture are debug-only. */
class PlanEditorDebugActivity : AppCompatActivity() {
    private lateinit var editor: PlanEditorView
    private lateinit var bitmap: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        root.addView(TextView(this).apply { setText(R.string.c2_debug_notice); textSize = 16f })
        val scenarios = Spinner(this).apply {
            adapter = ArrayAdapter(this@PlanEditorDebugActivity, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.c2_debug_scenarios))
            contentDescription = getString(R.string.c2_debug_scenario)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
        }
        root.addView(scenarios)
        val reset = Button(this).apply { setText(R.string.c2_debug_reset) }
        root.addView(reset)
        editor = PlanEditorView(this)
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        bitmap = Bitmap.createBitmap(800, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 38f }
            drawRect(20f, 20f, 780f, 220f, Paint().apply { color = Color.LTGRAY })
            drawText("TEST IMAGE - NOT MEDICATION", 35f, 95f, paint)
            drawText("C2 DEBUG FIXTURE", 35f, 160f, paint)
        }
        fun resetEditor() {
            val fixture = PlanEditorFixture().apply {
                resultState = when (scenarios.selectedItemPosition) {
                    0, 1 -> OnlineCommandState.CONFIRMED
                    2 -> OnlineCommandState.AWAITING_CONFIRMATION
                    3 -> OnlineCommandState.NOT_SUBMITTED
                    else -> OnlineCommandState.REJECTED
                }
                cache = if (scenarios.selectedItemPosition == 1) CacheState.REFRESH_REQUIRED else CacheState.APPLIED
                recoveredState = OnlineCommandState.CONFIRMED
            }
            val form = PlanFormViewModel().apply {
                updateInput(PlanFormInput(getString(R.string.c2_debug_name), getString(R.string.c2_debug_dose),
                    localTimes = listOf("08:00", "20:00"), startDate = LocalDate.now().toString(),
                    executionTimeZoneId = "Asia/Shanghai"))
            }
            // Resume simulates completed cache refresh. The initial confirm still uses the selected scenario.
            editor.bind(this, getString(R.string.c2_debug_elder),
                MedicationPhotoView("debug-photo", "elder", "image/png", 1), bitmap,
                "Asia/Shanghai", "Asia/Shanghai", form, fixture.model)
        }
        reset.setOnClickListener { resetEditor() }
        resetEditor()
    }

    override fun onDestroy() {
        editor.clear()
        bitmap.recycle()
        super.onDestroy()
    }
}
