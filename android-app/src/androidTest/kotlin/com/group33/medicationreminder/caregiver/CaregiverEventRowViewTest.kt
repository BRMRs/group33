package com.group33.medicationreminder.caregiver

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.*
import com.group33.medicationreminder.databinding.ItemCaregiverOverviewEventBinding
import com.group33.shared.contract.*
import com.group33.shared.model.DoseStatus
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fictional component evidence only: no production navigation, Repository or synchronization. */
@RunWith(AndroidJUnit4::class)
class CaregiverEventRowViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val planned = Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()
    private val occurrence = OccurrenceKey("test-plan", "2026-10-20", "08:00")
    private val generation = OccurrenceGenerationEvidence("test-generation", occurrence, 1, planned,
        "Asia/Shanghai", planned, 28800, TimeResolutionPolicy.P0_TIME_V1, "test-snapshot", planned)
    private val event = DoseEventState(occurrence, "test-event", generation, "test-medication",
        MedicationText("Test medication / 测试药品", "Test dosage / 测试剂量"), planned + 7_200_000,
        DoseStatus.TAKEN, DoseStatus.MISSED, emptyList())
    private val report = LocalActionReceipt("test-report", occurrence, DoseActionKind.TAKEN,
        LocalActionClassification.LATE_TAKEN, planned + 7_201_000, SyncState.SYNCED)

    @Test fun confirmedReportsKeepClassificationRecordedTimeAndAcceptedOutcomeSeparate() {
        for (language in listOf("en", "zh")) withRow(language) { row ->
            for (classification in LocalActionClassification.entries) {
                val kind = if (classification == LocalActionClassification.REMIND_LATER)
                    DoseActionKind.REMIND_LATER else DoseActionKind.TAKEN
                row.bindEvent(event.copy(localReports = listOf(report.copy(kind = kind,
                    classification = classification))).toCaregiverEventRow())
                assertTrue(row.outcome.text.contains(if (language == "en") "no on-time confirmation" else "未及时确认"))
                val expected = when (classification) {
                    LocalActionClassification.TIMELY_TAKEN -> if (language == "en") "within the response window" else "响应期内"
                    LocalActionClassification.LATE_TAKEN -> if (language == "en") "after the response window" else "响应期结束后"
                    LocalActionClassification.REMIND_LATER -> if (language == "en") "does not promise" else "不保证"
                }
                val text = row.syncedReports.text.toString()
                assertTrue(text, text.contains(expected))
                // Fixed UTC, independent expected clock value; never use the production formatter as oracle.
                assertTrue(text, Regex("0?2:00:01").containsMatchIn(text))
                assertFalse(text, text.contains("03:00:02"))
                assertTrue(text, text.contains(if (language == "en") "Service receipt time: unknown" else "服务接收时间：未知"))
                val plannedText = if (language == "en") "08:00 (Asia/Shanghai)" else "08:00（Asia/Shanghai）"
                assertTrue(row.plannedTime.text.toString(), row.plannedTime.text.contains(plannedText))
            }
        }
    }

    @Test fun unconfirmedAndForeignReportsNeverExposeDetails() {
        for (language in listOf("en", "zh")) withRow(language) { row ->
            for (sync in listOf(SyncState.PENDING_SYNC, SyncState.NEEDS_ATTENTION)) {
                row.bindEvent(event.copy(localReports = listOf(report.copy(syncState = sync))).toCaregiverEventRow())
                assertCleared(row.syncedReports)
                assertEquals(View.VISIBLE, row.localSync.visibility)
                val expected = if (language == "en") {
                    if (sync == SyncState.PENDING_SYNC) "pending sync" else "needs attention"
                } else if (sync == SyncState.PENDING_SYNC) "待同步" else "需要处理"
                assertTrue(row.localSync.text.toString(), row.localSync.text.contains(expected))
            }
            val foreign = report.copy(occurrence = occurrence.copy(planId = "other-plan"))
            row.bindEvent(event.copy(localReports = listOf(foreign)).toCaregiverEventRow())
            assertCleared(row.syncedReports)
            assertCleared(row.localSync)
            assertEquals(View.VISIBLE, row.reportLinkError.visibility)
            assertTrue(row.reportLinkError.text.contains(if (language == "en") "could not be associated" else "无法与本事件关联"))
        }
    }

    @Test fun rebindingClearsReportWarningAndAccessibilityText() = withRow { row ->
        row.bindEvent(event.copy(localReports = listOf(report)).toCaregiverEventRow())
        assertTrue(row.syncedReports.text.isNotEmpty())
        row.bindEvent(event.copy(localReports = listOf(report.copy(syncState = SyncState.PENDING_SYNC))).toCaregiverEventRow())
        assertCleared(row.syncedReports)
        row.bindEvent(event.copy(localReports = listOf(report.copy(occurrence = occurrence.copy(planId = "other")))).toCaregiverEventRow())
        assertTrue(row.reportLinkError.text.isNotEmpty())
        row.bindEvent(event.toCaregiverEventRow())
        listOf(row.syncedReports, row.reportLinkError, row.localSync).forEach(::assertCleared)
    }

    @Test fun everyAcceptedOutcomeHasTextIncludingUnknown() = withRow { row ->
        val expected = listOf("scheduled", "awaiting a report", "reminder postponed",
            "Elder reported taking medication", "no on-time confirmation")
        DoseStatus.entries.zip(expected).forEach { (status, text) ->
            row.bindEvent(event.copy(lastAcceptedStatus = status).toCaregiverEventRow())
            assertTrue(row.outcome.text.toString(), row.outcome.text.contains(text))
        }
        row.bindEvent(event.copy(lastAcceptedStatus = null).toCaregiverEventRow())
        assertEquals("Accepted medication result: unknown", row.outcome.text.toString())
    }

    @Test fun multipleReportsRetainRepositoryOrderAndIndividualClockValues() = withRow { row ->
        val second = report.copy(actionId = "test-second", recordedAtEpochMillis = planned + 3_602_000)
        row.bindEvent(event.copy(localReports = listOf(report, second)).toCaregiverEventRow())
        val text = row.syncedReports.text.toString()
        val first = Regex("0?2:00:01").find(text)
        val next = Regex("0?1:00:02").find(text)
        assertNotNull(first)
        assertNotNull(next)
        assertTrue(checkNotNull(first).range.first < checkNotNull(next).range.first)
    }

    @Test fun attachedBilingualRowsWrapAtNarrowWidthsAndLargeConfiguredFont() {
        for (language in listOf("en", "zh")) for (scale in listOf(1f, 2f)) for (width in listOf(320, 360)) {
            withRow(language, scale, width, afterLayout = { activity, row ->
                assertTrue(row.root.isAttachedToWindow)
                assertTrue(ViewCompat.isAccessibilityHeading(row.medication))
                assertTrue(row.root.height > 0)
                val background = row.root.cardBackgroundColor.defaultColor
                texts(row).filter { it.visibility == View.VISIBLE }.forEach { text ->
                    val layout = checkNotNull(text.layout)
                    assertTrue(text.resources.getResourceEntryName(text.id), layout.lineCount > 0)
                    assertTrue(layout.getLineBottom(layout.lineCount - 1) <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
                    for (line in 0 until layout.lineCount) {
                        assertEquals(0, layout.getEllipsisCount(line))
                        // Plain, unspanned text: exclude wrap-ending whitespace from visible ink extent.
                        val lineText = layout.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line))
                            .toString().trimEnd()
                        val visibleWidth = text.paint.measureText(lineText)
                        assertTrue("$language/$scale/$width line=$line visible=$visibleWidth limit=${layout.width}",
                            visibleWidth <= layout.width + 1f)
                    }
                    assertTrue(Color.alpha(text.currentTextColor) == 255)
                    assertTrue(ColorUtils.calculateContrast(text.currentTextColor, background) >= 4.5)
                }
                // A real window capture, not proof of actual system font settings or spoken TalkBack output.
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val file = File(activity.cacheDir, "c3-row-$language-$scale-$width.png")
                    file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { screenshot.recycle() }
            }) { row ->
                row.bindEvent(event.copy(medicationSnapshot = MedicationText(
                    "Test medication / 测试药品 ".repeat(5), "Test dosage / 测试剂量 ".repeat(5)),
                    localReports = listOf(report, report.copy(occurrence = occurrence.copy(planId = "other"))))
                    .toCaregiverEventRow())
            }
        }
    }

    @Test fun debugHostKeepsScrolledReportAboveSystemBars() = withRow(scale = 2f, widthDp = 320,
        viewportHeightDp = 600,
        afterLayout = { host, row ->
            val scroll = host.container
            val bars = checkNotNull(ViewCompat.getRootWindowInsets(scroll)).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertTrue("bottom padding=${scroll.paddingBottom}, system inset=${bars.bottom}",
                scroll.paddingBottom >= bars.bottom)
            assertTrue("top padding must avoid system UI", scroll.paddingTop >= bars.top)
            assertTrue("fixture must exceed viewport", row.root.height > scroll.height)
            row.syncedReports.requestRectangleOnScreen(Rect(0, 0,
                row.syncedReports.width, row.syncedReports.height), true)
            val requestedPosition = IntArray(2).also(row.syncedReports::getLocationOnScreen)
            val requestedScrollPosition = IntArray(2).also(scroll::getLocationOnScreen)
            val safeBottom = requestedScrollPosition[1] + scroll.height - bars.bottom
            val overflow = requestedPosition[1] + row.syncedReports.height - safeBottom
            if (overflow > 0) scroll.scrollBy(0, overflow)
            val scrolledPosition = IntArray(2).also(scroll::getLocationOnScreen)
            val visibleReportPosition = IntArray(2).also(row.syncedReports::getLocationOnScreen)
            val finalSafeBottom = scrolledPosition[1] + scroll.height - bars.bottom
            assertTrue("last report must be above system bar: reportBottom=${visibleReportPosition[1] + row.syncedReports.height}, safeBottom=$finalSafeBottom",
                visibleReportPosition[1] + row.syncedReports.height <= finalSafeBottom)
            assertTrue("last report must be fully inside the visible viewport: reportTop=${visibleReportPosition[1]}, safeTop=${scrolledPosition[1] + scroll.paddingTop}, scrollY=${scroll.scrollY}, childHeight=${scroll.getChildAt(0).height}",
                visibleReportPosition[1] >= scrolledPosition[1] + scroll.paddingTop)
        }) { row ->
        row.bindEvent(event.copy(medicationSnapshot = MedicationText("Fictional medication ".repeat(40),
            "Fictional dosage ".repeat(40)), localReports = listOf(report)).toCaregiverEventRow())
    }

    private fun texts(row: ItemCaregiverOverviewEventBinding) = listOf(row.medication, row.dosage,
        row.plannedTime, row.outcome, row.localSync, row.syncedReports, row.reportLinkError)

    private fun assertCleared(view: TextView) {
        assertEquals(View.GONE, view.visibility)
        assertEquals("", view.text.toString())
        val node = view.createAccessibilityNodeInfo()
        try { assertTrue(node.text.isNullOrEmpty()) } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun withRow(language: String = "en", scale: Float = 1f, widthDp: Int = 360,
        viewportHeightDp: Int? = null,
        afterLayout: ((CaregiverEventRowTestActivity, ItemCaregiverOverviewEventBinding) -> Unit)? = null,
        block: (ItemCaregiverOverviewEventBinding) -> Unit,
    ) {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        var activity: CaregiverEventRowTestActivity? = null
        try {
            val intent = Intent(instrumentation.targetContext, CaregiverEventRowTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity = instrumentation.startActivitySync(intent) as CaregiverEventRowTestActivity
            val host = activity
            lateinit var row: ItemCaregiverOverviewEventBinding
            instrumentation.runOnMainSync {
                val configuration = Configuration(host.resources.configuration).apply {
                    setLocale(if (language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.US)
                    fontScale = scale
                }
                val context = ContextThemeWrapper(host.createConfigurationContext(configuration), R.style.Theme_Group33)
                row = ItemCaregiverOverviewEventBinding.inflate(LayoutInflater.from(context))
                viewportHeightDp?.let { heightDp ->
                    val density = context.resources.displayMetrics.density
                    host.container.layoutParams = host.container.layoutParams.apply {
                        width = (widthDp * density).toInt()
                        height = (heightDp * density).toInt()
                    }
                }
                host.container.addView(row.root, ViewGroup.LayoutParams(
                    (widthDp * context.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                block(row)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { afterLayout?.invoke(host, row) }
        } finally {
            try { activity?.let { host -> instrumentation.runOnMainSync { host.finish() } } }
            finally { TimeZone.setDefault(previousZone) }
        }
    }
}
