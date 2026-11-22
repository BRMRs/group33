package com.group33.medicationreminder.integration

import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.CareRelationshipStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in local anonymous Debug acceptance; uses the actual HTTP/Room/application path.
 * Prepare a fictional active relationship first. No caller-provided token or fake authorized cache.
 */
class CaregiverHistoryOnlineEntryTest {
    @Test fun selectedRangeReadsThroughHttpRoomAndActualActivity(): Unit = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("c1HistoryOnline") == "true")
        val app = instrumentation.targetContext.applicationContext as DebugChainApplication
        val repository = app.repository
        val session = withTimeout(15_000) {
            repository.observeSession().first { it.readiness != AuthReadiness.RESTORING }
        }
        assertEquals(AuthReadiness.READY, session.readiness)
        // Process restoration invalidates cached authority. Confirm the relationship through
        // real HTTP as normal entry does; do not preload or grant event-range authority.
        assertTrue(repository.refresh() is Outcome.Success)
        val relationships = withTimeout(15_000) {
            repository.observeRelationships().first { it.access == AccessState.AUTHORIZED }
        }
        val elder = relationships.value.single { it.status == CareRelationshipStatus.ACTIVE }.elderId
        // Fresh, explicit range not used by the manual attempt; proves no preseeded authorization.
        val args = InstrumentationRegistry.getArguments()
        val range = LocalDateRange(args.getString("c1HistoryFrom") ?: "2026-11-03",
            args.getString("c1HistoryThrough") ?: "2026-11-04")
        val initial = repository.observeEvents(elder, range).first()
        assertEquals(AccessState.UNKNOWN, initial.access)
        assertFalse("Use a range with no preseeded local document", initial.hasLocalData)
        ActivityScenario.launch(CaregiverHomeActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_records
            }
            withTimeout(15_000) {
                var ready = false
                while (!ready) {
                    scenario.onActivity { ready = it.findViewById<EditText>(R.id.history_from) != null }
                    if (!ready) delay(50)
                }
            }
            scenario.onActivity {
                it.findViewById<EditText>(R.id.history_from).setText(range.fromDate)
                it.findViewById<EditText>(R.id.history_through).setText(range.throughDate)
            }
            // Editing alone does not start sync.
            assertEquals(AccessState.UNKNOWN, repository.observeEvents(elder, range).first().access)
            scenario.onActivity { it.findViewById<android.view.View>(R.id.history_apply).performClick() }
            val cache = withTimeout(20_000) {
                repository.observeEvents(elder, range).first { it.access == AccessState.AUTHORIZED }
            }
            assertTrue(cache.hasLocalData)
            assertTrue(cache.value.isEmpty()) // Local fixture has no events, not a general product assumption.
            withTimeout(10_000) {
                var rendered = false
                while (!rendered) {
                    scenario.onActivity {
                        val applied = it.findViewById<TextView>(R.id.history_applied_range).text.toString()
                        val status = it.findViewById<TextView>(R.id.history_state).text.toString()
                        rendered = applied.contains(range.fromDate) && applied.contains(range.throughDate) &&
                            status == it.getString(R.string.c3_empty)
                    }
                    if (!rendered) delay(50)
                }
            }
            instrumentation.waitForIdleSync()
            val language = args.getString("c1HistoryLanguage") ?: "fixture"
            require(language in setOf("en", "zh-CN", "fixture"))
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    java.io.File(app.filesDir, "history-online-$language.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
}
