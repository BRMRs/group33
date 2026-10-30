package com.group33.medicationreminder.elder

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderLargeFontLayoutTest {
    @Test
    fun englishAt320dpAndDoubleFontKeepsWordsAndStatusTextReadable() = checkLayout(Locale.US)

    @Test
    fun chineseAt320dpAndDoubleFontDoesNotCollapsePromptToOneCharacterPerLine() =
        checkLayout(Locale.SIMPLIFIED_CHINESE)

    private fun checkLayout(locale: Locale) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                fontScale = 2f
                densityDpi = 540
                screenWidthDp = 320
                setLocale(locale)
            }
            val context = ContextThemeWrapper(
                instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Group33,
            )
            val root = LayoutInflater.from(context).inflate(R.layout.activity_elder_home, null)
            val width = (320 * context.resources.displayMetrics.density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, 2400)
            for (id in listOf(R.id.elder_title, R.id.elder_subtitle, R.id.due_now_label)) {
                val view = root.findViewById<TextView>(id)
                val text = view.text.toString()
                val layout = view.layout
                assertTrue("Text needs usable width: $text", view.width > width / 2)
                assertTrue("No text may be ellipsized: $text", (0 until layout.lineCount).all {
                    layout.getEllipsisCount(it) == 0
                })
                if (locale == Locale.US) {
                    for (line in 0 until layout.lineCount - 1) {
                        val end = layout.getLineEnd(line)
                        assertTrue("Do not break words in $text", end == 0 || end >= text.length ||
                            text[end - 1].isWhitespace() || text[end].isWhitespace())
                    }
                } else {
                    assertTrue("Prompt must not become a vertical column: $text", layout.lineCount <= 2)
                }
            }
        }
    }
}
