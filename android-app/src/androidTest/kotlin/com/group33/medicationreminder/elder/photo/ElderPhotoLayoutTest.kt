package com.group33.medicationreminder.elder.photo

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ElderPhotoLayoutTest {
    @Test fun englishAt320dpAnd200Percent() = check(Locale.US)
    @Test fun chineseAt320dpAnd200Percent() = check(Locale.SIMPLIFIED_CHINESE)
    private fun check(locale: Locale) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                setLocale(locale); fontScale = 2f; densityDpi = 540; screenWidthDp = 320
            }
            val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Group33)
            val root = LayoutInflater.from(context).inflate(R.layout.activity_elder_photo, null)
            root.findViewById<View>(R.id.photo_use).visibility = View.VISIBLE
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2400)
            for (id in listOf(R.id.photo_camera, R.id.photo_choose, R.id.photo_use, R.id.photo_replace)) {
                val button = root.findViewById<TextView>(id)
                assertTrue(button.height >= (64 * context.resources.displayMetrics.density).toInt())
                assertTrue((0 until button.layout.lineCount).all { button.layout.getEllipsisCount(it) == 0 })
            }
            val status = root.findViewById<TextView>(R.id.photo_status)
            val minimum = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 24f, context.resources.displayMetrics)
            assertTrue("Status must be at least 24sp", status.textSize + 0.1f >= minimum)
        }
    }
}
