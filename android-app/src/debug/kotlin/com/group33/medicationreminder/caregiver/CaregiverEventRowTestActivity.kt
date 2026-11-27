package com.group33.medicationreminder.caregiver

import android.os.Bundle
import android.graphics.drawable.ColorDrawable
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.group33.medicationreminder.R

/** Debug-only empty window. Fixtures and bindings are supplied by instrumentation, not production. */
class CaregiverEventRowTestActivity : AppCompatActivity() {
    lateinit var container: ScrollView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.group33_background)))
        container = ScrollView(this).apply {
            keepScreenOn = true
            setBackgroundColor(ContextCompat.getColor(this@CaregiverEventRowTestActivity, R.color.group33_background))
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(container)
        ViewCompat.requestApplyInsets(container)
    }
}
