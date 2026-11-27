package com.group33.medicationreminder.elder

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ElderReminderPermissionBannerLayoutTest {
    @Test fun englishAt320dpAnd200Percent() = check(Locale.US, 320)

    @Test fun chineseAt320dpAnd200Percent() = check(Locale.SIMPLIFIED_CHINESE, 320)

    @Test fun englishAt360dpAnd200Percent() = check(Locale.US, 360)

    @Test fun chineseAt360dpAnd200Percent() = check(Locale.SIMPLIFIED_CHINESE, 360)

    private fun check(locale: Locale, widthDp: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                setLocale(locale)
                fontScale = 2f
                screenWidthDp = widthDp
            }
            val context = ContextThemeWrapper(
                instrumentation.targetContext.createConfigurationContext(config),
                R.style.Theme_Group33,
            )
            val width = (widthDp * context.resources.displayMetrics.density).toInt()

            listOf(
                Triple(
                    "unavailable",
                    R.string.elder_reminder_notifications_unavailable,
                    R.string.elder_enable_dose_notifications,
                ),
                Triple(
                    "degraded",
                    R.string.elder_reminder_exact_alarm_degraded,
                    R.string.elder_allow_exact_alarms,
                ),
            ).forEach { (state, message, action) ->
                val root = LayoutInflater.from(context)
                    .inflate(R.layout.elder_reminder_permission_banner, null)
                root.setBackgroundColor(Color.WHITE)
                val feedback = root.findViewById<TextView>(R.id.elder_reminder_capability_feedback)
                val button = root.findViewById<TextView>(R.id.elder_reminder_permission_action)
                feedback.setText(message)
                button.setText(action)
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                root.layout(0, 0, width, root.measuredHeight)

                assertTrue((0 until feedback.layout.lineCount).all {
                    feedback.layout.getEllipsisCount(it) == 0
                })
                assertTrue((0 until button.layout.lineCount).all {
                    button.layout.getEllipsisCount(it) == 0
                })
                assertTrue(button.height >= (64 * context.resources.displayMetrics.density).toInt())
                assertEquals(feedback.text.toString(), feedback.createAccessibilityNodeInfo().text.toString())
                assertEquals(button.text.toString(), button.createAccessibilityNodeInfo().text.toString())
                if (widthDp == 320 &&
                    InstrumentationRegistry.getArguments().getString("captureEvidence") == "true") {
                    capture(
                        root,
                        File(
                            checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                            "permission-${locale.language}-$state.png",
                        ),
                    )
                }
            }
        }
    }

    private fun capture(view: View, destination: File) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(destination).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
