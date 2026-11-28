package com.group33.medicationreminder.caregiver

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat

/** Empty debug window; instrumentation supplies the history component and fictional Repository. */
class CaregiverHistoryTestActivity : AppCompatActivity() {
    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep one inset owner on both older Android and target-35 edge-to-edge windows.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        container = FrameLayout(this).apply { keepScreenOn = true }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(container)
        ViewCompat.requestApplyInsets(container)
    }
}
