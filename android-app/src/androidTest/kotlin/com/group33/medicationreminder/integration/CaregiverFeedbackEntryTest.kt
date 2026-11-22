package com.group33.medicationreminder.integration

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.*
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import com.group33.shared.model.DoseStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real shared renderer/layout and C1 context resolver; strict test-only service fixtures. */
class CaregiverFeedbackEntryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dates = LocalDateRange("2026-10-01", "2026-10-30")

    @Test fun explicitDatesRouteFullPagesAndPreservePairingAndPlanSections() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act {
            assertTrue(h.providerCalls >= 1)
            assertNotNull(findText(h.root, it.getString(R.string.chain_no_plans)))
            h.root.findViewById<View>(R.id.refresh_button).performClick()
        }
        h.await { h.fixture.refreshes.size == 1 }
        h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
        h.await { find(h.root, CaregiverHistoryHost::class.java) != null }
        h.act {
            assertEquals(CaregiverDestination.RECORDS, h.lastDestination)
            assertEquals(listOf("elder" to dates), h.fixture.historyQueries)
            assertTrue(h.providerCalls >= 1)
            h.content.show(CaregiverDestination.SETTINGS)
        }
        h.await { find(h.root, CaregiverHistoryHost::class.java) == null }
        h.act { activity ->
            assertTrue(allViews(h.root).filterIsInstance<EditText>().any {
                it.hint?.toString() == activity.getString(R.string.chain_enter_code) && it.isShown
            })
            assertEquals(listOf("elder" to dates, "elder" to dates), h.fixture.refreshes)
        }
    }

    @Test fun recordsUseAvailableHeightAndContextLossRestoresTheSharedShell() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java)?.parent is FrameLayout }
        h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
        var oldHeight = 0
        h.await {
            val slot = find(h.root, CaregiverHistoryHost::class.java)?.parent as? FrameLayout
            val viewport = h.root.findViewById<View>(R.id.caregiver_page_content)
            slot != null && slot.height > 0 && slot.height == viewport.height
        }
        h.act { activity ->
            oldHeight = (find(h.root, CaregiverHistoryHost::class.java)!!.parent as FrameLayout).height
            val density = activity.resources.displayMetrics.density
            h.root.layoutParams = FrameLayout.LayoutParams((320 * density).toInt(), (480 * density).toInt())
        }
        h.await {
            val slot = find(h.root, CaregiverHistoryHost::class.java)?.parent as? FrameLayout
            val viewport = h.root.findViewById<View>(R.id.caregiver_page_content)
            slot != null && slot.height > 0 && slot.height != oldHeight &&
                slot.height == viewport.height
        }
        h.act { h.fixture.trusted.value = null }
        h.await { find(h.root, CaregiverHistoryHost::class.java) == null }
        h.act {
            assertEquals(View.VISIBLE, h.root.findViewById<View>(R.id.caregiver_scroll).visibility)
            assertEquals(View.GONE, h.root.findViewById<View>(R.id.caregiver_records_content).visibility)
            h.content.close()
            h.fixture.trusted.value = TrustedSessionContext(h.fixture.scope, 2)
        }
        instrumentation.waitForIdleSync()
        h.act { assertNull(find(h.root, CaregiverHistoryHost::class.java)) }
    }

    @Test fun callerWithoutDateProviderDoesNotQueryGuessedDates() = withEntry(false) { h ->
        h.await { findText(h.root, h.root.context.getString(R.string.c1_feedback_date_unavailable)) != null }
        captureDateEvidence("unavailable")
        h.act {
            assertNull(find(h.root, CaregiverOverviewHost::class.java))
            assertNull(find(h.root, CaregiverHistoryHost::class.java))
            assertEquals(0, h.providerCalls)
            assertTrue(h.fixture.historyQueries.isEmpty())
            assertTrue(h.fixture.refreshes.isEmpty())
        }
    }

    private fun scrollingEvents(count: Int = 24) = (1..count).map { number ->
            val key = OccurrenceKey("scroll-plan-$number", "2026-10-20", "08:00")
            val planned = java.time.Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()
            val generation = OccurrenceGenerationEvidence("scroll-generation-$number", key, 1,
                planned - 86_400_000, "Asia/Shanghai", planned, 28800,
                TimeResolutionPolicy.P0_TIME_V1, "scroll-snapshot-$number", planned - 86_400_000)
            DoseEventState(key, "scroll-event-$number", generation, "scroll-medication-$number",
                MedicationText("Scroll medication $number", "Test dosage", "Fictional scroll fixture"),
                planned + 7_200_000, DoseStatus.MISSED, DoseStatus.MISSED, emptyList())
        }

    @Test fun overviewContentUsesOnlyTheSharedPageScroll() = withEntry(eventRows = scrollingEvents()) { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        lateinit var outer: androidx.core.widget.NestedScrollView
        h.act {
            outer = h.root.findViewById(R.id.caregiver_scroll)
            assertTrue(allViews(find(h.root, CaregiverOverviewHost::class.java)!!)
                .none { it is androidx.core.widget.NestedScrollView })
            outer.scrollTo(0, 0)
        }
        h.await { outer.height > 0 }
        awaitStableScroll(h, outer)
        swipeVisible(h, h.root, up = true, pageContent = true)
        awaitStableScroll(h, outer)
        h.act {
            assertTrue("The shared page owns movement across header and overview content", outer.scrollY > 0)
        }
        repeat(4) {
            var before = 0
            h.act { before = outer.scrollY }
            if (before > 0) {
                swipeVisible(h, h.root, up = false, pageContent = true)
                awaitStableScroll(h, outer)
                h.act {
                    assertTrue("A reverse drag from the same region must move toward the shared header: " +
                        "before=$before, after=${outer.scrollY}", outer.scrollY < before)
                }
            }
        }
        h.act { assertEquals("Pulling down at the content top must reveal the shared header", 0, outer.scrollY) }
    }

    @Test fun overviewAndPlanFooterRemainReachableAlongThePageEdge() = withEntry(eventRows = scrollingEvents()) { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.await { h.root.height > 0 }
        var reached = false
        repeat(36) {
            if (!reached) {
                h.act { activity ->
                    val footer = findText(h.root, activity.getString(R.string.chain_no_plans))
                    val bounds = android.graphics.Rect()
                    reached = footer != null && footer.getGlobalVisibleRect(bounds) && bounds.height() == footer.height
                }
                if (!reached) swipeVisible(h, h.root, up = true, horizontalFraction = .98f, pageContent = true)
            }
        }
        assertTrue("Scrolling past C3 records must leave the existing plan section reachable", reached)
        var atTop = false
        repeat(36) {
            h.act { atTop = h.root.findViewById<View>(R.id.caregiver_scroll).scrollY == 0 }
            if (!atTop) swipeVisible(h, h.root, up = false, horizontalFraction = .98f, pageContent = true)
        }
        assertTrue("The same edge must allow scrolling back from the plan area", atTop)
    }

    @Test fun leavingRecordsRestoresTheSharedShellAndClearsOldActions() = withEntry(eventRows = scrollingEvents()) { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
        h.await { find(h.root, CaregiverHistoryHost::class.java) != null }
        h.act {
            val oldApply = h.root.findViewById<View>(R.id.history_apply)
            val reads = h.fixture.historyQueries.size
            h.content.show(CaregiverDestination.SETTINGS)
            assertEquals(View.VISIBLE, h.root.findViewById<View>(R.id.caregiver_scroll).visibility)
            assertEquals(View.GONE, h.root.findViewById<View>(R.id.caregiver_records_content).visibility)
            assertNull(find(h.root, CaregiverHistoryHost::class.java))
            oldApply.performClick()
            assertEquals(reads, h.fixture.historyQueries.size)
            h.content.close()
        }
    }

    @Test fun recordsPageEdgesReachTheLastRecordAndReturnToTheTop() {
        for (edge in listOf(.02f, .98f)) withEntry(eventRows = scrollingEvents()) { h ->
            h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
            h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
            h.await { h.root.findViewById<android.widget.ListView>(R.id.history_list)?.count == 25 }
            var atEnd = false
            repeat(32) {
                h.act {
                    val list = h.root.findViewById<android.widget.ListView>(R.id.history_list)
                    atEnd = list.lastVisiblePosition == list.count - 1 &&
                        (list.getChildAt(list.childCount - 1)?.bottom ?: Int.MAX_VALUE) <= list.height - list.paddingBottom
                }
                if (!atEnd) swipeVisible(h, h.root, up = true, horizontalFraction = edge, pageContent = true)
            }
            assertTrue("Page-edge drag must reach the final history row without changing its start region: $edge", atEnd)
            var atTop = false
            repeat(32) {
                h.act {
                    val list = h.root.findViewById<android.widget.ListView>(R.id.history_list)
                    atTop = list.firstVisiblePosition == 0 &&
                        (list.getChildAt(0)?.top ?: Int.MIN_VALUE) >= list.paddingTop
                }
                if (!atTop) swipeVisible(h, h.root, up = false, horizontalFraction = edge, pageContent = true)
            }
            assertTrue("The same page edge must allow returning to the date header: $edge", atTop)
        }
    }

    @Test fun bilingualLargeTextHistoryHasOneScrollOwnerAndRecyclesLongData() {
        for (language in listOf("zh", "en")) withEntry(
            eventRows = scrollingEvents(200), language = language, fontScale = 2f, widthDp = 320,
        ) { h ->
            h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
            h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
            h.await { h.root.findViewById<android.widget.ListView>(R.id.history_list)?.count == 201 }
            lateinit var list: android.widget.ListView
            var before = 0
            h.act { activity ->
                list = h.root.findViewById(R.id.history_list)
                var parent = list.parent
                while (parent is View) {
                    assertFalse("Records must not be inside another vertical scroller", parent is androidx.core.widget.NestedScrollView)
                    parent = parent.parent
                }
                assertEquals(activity.getString(R.string.c3_history), h.root.findViewById<TextView>(R.id.history_title).text)
                assertEquals(2f, list.resources.configuration.fontScale, 0f)
                before = list.getChildAt(0)?.top ?: 0
                assertTrue(list.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            }
            h.await { list.firstVisiblePosition > 0 || (list.getChildAt(0)?.top ?: before) < before }
            h.act { assertTrue(list.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)) }
            h.await { list.firstVisiblePosition == 0 && (list.getChildAt(0)?.top ?: -1) >= list.paddingTop }
            // This jump checks row reuse only; the 24-row edge test separately proves touch reachability.
            h.act { list.setSelection(list.count - 1) }
            h.await { findText(list, "Scroll medication 200") != null }
            h.act {
                assertTrue("History must retain row reuse instead of expanding every event", list.childCount < 201)
                h.fixture.trusted.value = null
            }
            h.await { find(h.root, CaregiverHistoryHost::class.java) == null }
        }
    }

    @Test fun keyboardLeavesDateFieldsAndReadActionReachable() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
        h.await { find(h.root, CaregiverHistoryHost::class.java) != null }
        lateinit var editor: EditText
        h.act { activity ->
            editor = h.root.findViewById(R.id.history_through)
            editor.requestFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        try {
            h.await { androidx.core.view.ViewCompat.getRootWindowInsets(h.root)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
            var reached = false
            repeat(12) {
                h.act {
                    val action = h.root.findViewById<View>(R.id.history_apply)
                    val bounds = android.graphics.Rect()
                    reached = action.getGlobalVisibleRect(bounds) && bounds.height() == action.height
                }
                if (!reached) swipeVisible(h, h.root, up = true, horizontalFraction = .98f, pageContent = true)
            }
            assertTrue("Read must be scroll-reachable while the keyboard is visible", reached)
            h.act { assertEquals("2026-10-30", editor.text.toString()) }
        } finally {
            h.act { activity ->
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
            }
        }
        h.await { androidx.core.view.ViewCompat.getRootWindowInsets(h.root)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
    }

    @Test fun dateFieldSideDragsScrollWithoutChangingTheStartRegion() = withEntry(eventRows = scrollingEvents()) { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
        h.await { h.root.findViewById<android.widget.ListView>(R.id.history_list)?.count == 25 }
        for (field in listOf(R.id.history_from, R.id.history_through)) {
            lateinit var list: android.widget.ListView
            h.act {
                list = h.root.findViewById(R.id.history_list)
                list.setSelectionFromTop(0, 0)
            }
            h.await { list.firstVisiblePosition == 0 && (list.getChildAt(0)?.top ?: -1) >= list.paddingTop }
            val area = android.graphics.Rect()
            val input = android.graphics.Rect()
            var before = 0
            h.act {
                assertTrue(h.root.getGlobalVisibleRect(area))
                val gestures = androidx.core.view.ViewCompat.getRootWindowInsets(h.root)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemGestures())
                area.right -= gestures?.right ?: 0
                assertTrue(h.root.findViewById<View>(field).getGlobalVisibleRect(input))
                before = list.getChildAt(0).top
            }
            val x = area.right - 16f
            sendDrag(x, input.exactCenterY(), maxOf(area.top + 16f, input.exactCenterY() - area.height() * .3f))
            h.await { list.firstVisiblePosition > 0 || (list.getChildAt(0)?.top ?: before) < before }
        }
    }

    @Test fun fastReverseAndLastRowDetailsRemainReachable() = withEntry(eventRows = scrollingEvents()) { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
        h.await { h.root.findViewById<android.widget.ListView>(R.id.history_list)?.count == 25 }
        lateinit var list: android.widget.ListView
        h.act { list = h.root.findViewById(R.id.history_list) }
        swipeVisible(h, h.root, up = true, pageContent = true, holdBeforeRelease = false)
        var position = 0
        var offset = 0
        h.act { position = list.firstVisiblePosition; offset = list.getChildAt(0).top }
        swipeVisible(h, h.root, up = false, pageContent = true, holdBeforeRelease = false)
        h.await("Reverse drag must move back from first=$position, top=$offset") { list.firstVisiblePosition < position ||
            list.firstVisiblePosition == position && (list.getChildAt(0)?.top ?: offset) > offset }
        var atEnd = false
        repeat(32) {
            h.act { atEnd = list.lastVisiblePosition == list.count - 1 &&
                (list.getChildAt(list.childCount - 1)?.bottom ?: Int.MAX_VALUE) <= list.height - list.paddingBottom }
            if (!atEnd) swipeVisible(h, h.root, up = true, pageContent = true)
        }
        assertTrue(atEnd)
        val row = android.graphics.Rect()
        h.act { assertTrue(findText(list, "Scroll medication 24")!!.getGlobalVisibleRect(row)) }
        tapPoint(row.exactCenterX(), row.exactCenterY())
        h.await("Tapping the final row must open inline details") { list.count == 26 }
        val close = android.graphics.Rect()
        var closeVisible = false
        repeat(8) {
            h.act {
                val button = h.root.findViewById<View>(R.id.history_close_details)
                closeVisible = button != null && button.getGlobalVisibleRect(close) && close.height() == button.height
            }
            if (!closeVisible) swipeVisible(h, h.root, up = true, pageContent = true)
        }
        assertTrue("Inline detail close must be reachable at the last record", closeVisible)
        tapPoint(close.exactCenterX(), close.exactCenterY())
        h.await("Closing inline details must retain the final record") { list.count == 25 && findText(list, "Scroll medication 24") != null }
    }

    private fun awaitStableScroll(h: Harness, scroll: androidx.core.widget.NestedScrollView) {
        var previousPosition: Pair<Int, Int>? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        h.await("Shared scroll must finish layout and movement") {
            val position = scroll.scrollY to scroll.height
            val now = android.os.SystemClock.uptimeMillis()
            if (scroll.isLayoutRequested || h.root.isLayoutRequested || position != previousPosition) {
                previousPosition = position
                stableSince = now
            }
            scroll.height > 0 && !scroll.isLayoutRequested && !h.root.isLayoutRequested &&
                now - stableSince >= 150
        }
    }

    private fun tapPoint(x: Float, y: Float) {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .perform(object : androidx.test.espresso.ViewAction {
                override fun getDescription() = "Send one verified tap at the captured visible coordinates"
                override fun getConstraints(): org.hamcrest.Matcher<View> =
                    androidx.test.espresso.matcher.ViewMatchers.isRoot()
                override fun perform(controller: androidx.test.espresso.UiController, view: View) {
                    // Tap.SINGLE synchronizes input delivery; call it directly to avoid click retries.
                    assertEquals(androidx.test.espresso.action.Tapper.Status.SUCCESS,
                        androidx.test.espresso.action.Tap.SINGLE.sendTap(controller, floatArrayOf(x, y),
                            androidx.test.espresso.action.Press.FINGER.describePrecision()))
                }
            })
    }

    private fun swipeVisible(h: Harness, target: View, up: Boolean, horizontalFraction: Float = .5f,
        pageContent: Boolean = false, holdBeforeRelease: Boolean = true) {
        val bounds = android.graphics.Rect()
        h.act {
            assertTrue(target.getGlobalVisibleRect(bounds))
            if (pageContent) {
                val navigation = android.graphics.Rect()
                assertTrue(h.root.findViewById<View>(R.id.bottom_navigation).getGlobalVisibleRect(navigation))
                bounds.bottom = minOf(bounds.bottom, navigation.top)
                val gestures = androidx.core.view.ViewCompat.getRootWindowInsets(h.root)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemGestures())
                bounds.left += gestures?.left ?: 0
                bounds.right -= gestures?.right ?: 0
            }
        }
        val from = bounds.top + bounds.height() * if (up) .8f else .2f
        val to = bounds.top + bounds.height() * if (up) .2f else .8f
        sendDrag(bounds.left + bounds.width() * horizontalFraction, from, to, holdBeforeRelease)
    }

    private fun sendDrag(x: Float, from: Float, to: Float, holdBeforeRelease: Boolean = true) {
        val down = android.os.SystemClock.uptimeMillis()
        for (step in 0..12) {
            // Holding before release isolates drag handoff from fling velocity.
            if (step == 12 && holdBeforeRelease) android.os.SystemClock.sleep(220)
            val action = when (step) {
                0 -> android.view.MotionEvent.ACTION_DOWN
                12 -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(),
                action, x, from + (to - from) * step / 12, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            // An edge gesture can be routed through a system-owned window on API27/MIUI.
            // Use the automation input channel, retaining the exact start region and assertions.
            assertTrue("The automation channel must deliver each drag event",
                instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            android.os.SystemClock.sleep(20)
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun historyLastRecordRemainsReachableByTouchInsideSharedScroll() = withEntry(eventRows = scrollingEvents()) { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.root.findViewById<View>(R.id.history_button).performClick() }
        h.await { find(h.root, CaregiverHistoryHost::class.java) != null }
        var reached = false
        repeat(28) {
            if (!reached) {
                val visible = android.graphics.Rect()
                h.act {
                    reached = allViews(h.root).filterIsInstance<TextView>().any { view ->
                        val bounds = android.graphics.Rect()
                        view.text.toString() == "Scroll medication 24" &&
                            view.getGlobalVisibleRect(bounds) && bounds.height() == view.height
                    }
                    assertTrue(h.root.findViewById<View>(R.id.history_list).getGlobalVisibleRect(visible))
                }
                if (!reached) {
                    val x = visible.exactCenterX()
                    val from = visible.top + visible.height() * .85f
                    val to = visible.top + visible.height() * .2f
                    val down = android.os.SystemClock.uptimeMillis()
                    for (step in 0..12) {
                        val action = when (step) {
                            0 -> android.view.MotionEvent.ACTION_DOWN
                            12 -> android.view.MotionEvent.ACTION_UP
                            else -> android.view.MotionEvent.ACTION_MOVE
                        }
                        val event = android.view.MotionEvent.obtain(down,
                            android.os.SystemClock.uptimeMillis(), action, x, from + (to - from) * step / 12, 0)
                        instrumentation.sendPointerSync(event)
                        event.recycle()
                        android.os.SystemClock.sleep(20)
                    }
                    instrumentation.waitForIdleSync()
                }
            }
        }
        assertTrue("Touch scrolling must reach the last history record through the shared parent", reached)
    }


    @Test fun dateUnavailableDoesNotQueryUntilExplicitHostRebind() = withEntry(initialDateAvailable = false) { h ->
        var fallbackQueries = emptyList<Pair<String, LocalDateRange>>()
        h.await { findText(h.root, h.root.context.getString(R.string.c1_feedback_date_unavailable)) != null }
        h.act {
            assertNull(find(h.root, CaregiverOverviewHost::class.java))
            h.content.show(CaregiverDestination.RECORDS)
        }
        h.act {
            assertNotNull(find(h.root, CaregiverHistoryHost::class.java))
            assertEquals("", h.root.findViewById<EditText>(R.id.history_from).text.toString())
            assertEquals(View.GONE, h.root.findViewById<View>(R.id.caregiver_scroll).visibility)
            assertEquals(View.VISIBLE, h.root.findViewById<View>(R.id.caregiver_records_content).visibility)
            fallbackQueries = h.fixture.historyQueries.toList()
            assertTrue(fallbackQueries.isEmpty())
            h.suppliedToday = java.time.LocalDate.parse("2026-10-30")
            h.content.show(CaregiverDestination.RECORDS)
        }
        h.await { find(h.root, CaregiverHistoryHost::class.java) != null }
        h.await { h.fixture.historyQueries.size == fallbackQueries.size + 1 }
        h.act { assertEquals(fallbackQueries + ("elder" to dates), h.fixture.historyQueries) }
    }

    @Test fun restoredSessionRejectsOldDateBeforeMountingHistory() = withEntry { h ->
        var fallbackQueries = emptyList<Pair<String, LocalDateRange>>()
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act {
            h.content.show(CaregiverDestination.SETTINGS)
            h.dateContextOverride = CaregiverElderContext(h.fixture.scope, 1, "relationship", "elder", "Fixture elder")
            h.fixture.trusted.value = TrustedSessionContext(h.fixture.scope, 2)
        }
        h.await { (h.model.state.value.context as? CaregiverContextState.Active)?.context?.sessionGeneration == 2L }
        h.act { h.content.show(CaregiverDestination.RECORDS) }
        h.act {
            assertNotNull(find(h.root, CaregiverHistoryHost::class.java))
            assertEquals("", h.root.findViewById<EditText>(R.id.history_from).text.toString())
            fallbackQueries = h.fixture.historyQueries.toList()
            assertTrue(fallbackQueries.isEmpty())
            h.dateContextOverride = null
            h.content.show(CaregiverDestination.RECORDS)
        }
        h.await { find(h.root, CaregiverHistoryHost::class.java) != null }
        h.await { h.fixture.historyQueries.size == fallbackQueries.size + 1 }
        h.act { assertEquals(fallbackQueries + ("elder" to dates), h.fixture.historyQueries) }
    }

    @Test fun reentryWithoutDateDoesNotReuseThePreviousRange() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act {
            h.content.show(CaregiverDestination.SETTINGS)
            h.suppliedToday = null
            h.content.show(CaregiverDestination.RECORDS)
        }
        h.act {
            assertNull(find(h.root, CaregiverOverviewHost::class.java))
            assertNotNull(find(h.root, CaregiverHistoryHost::class.java))
            assertEquals("", h.root.findViewById<EditText>(R.id.history_from).text.toString())
            assertTrue(h.fixture.historyQueries.isEmpty())
            assertEquals(View.VISIBLE, h.root.findViewById<View>(R.id.caregiver_records_content).visibility)
        }
    }

    @Test fun sameContextDateUpdateRebindsTheDefaultRange() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.content.show(CaregiverDestination.RECORDS) }
        h.await { h.fixture.historyQueries == listOf("elder" to dates) }
        val next = LocalDateRange("2026-10-02", "2026-10-31")
        h.act {
            h.suppliedToday = java.time.LocalDate.parse("2026-10-31")
            h.content.show(CaregiverDestination.RECORDS)
        }
        h.await { h.fixture.historyQueries == listOf("elder" to dates, "elder" to next) }
    }

    @Test fun sameContextDateWithdrawalClearsHistoryAndDoesNotQueryFallback() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.content.show(CaregiverDestination.RECORDS) }
        h.await { h.fixture.historyQueries == listOf("elder" to dates) }
        h.act {
            h.suppliedToday = null
            h.content.show(CaregiverDestination.RECORDS)
        }
        h.await { h.root.findViewById<EditText>(R.id.history_from).text.toString().isEmpty() }
        h.act {
            assertNull(find(h.root, CaregiverOverviewHost::class.java))
            assertNotNull(find(h.root, CaregiverHistoryHost::class.java))
            assertEquals(listOf("elder" to dates), h.fixture.historyQueries)
            h.suppliedToday = java.time.LocalDate.parse("2026-10-30")
            h.content.show(CaregiverDestination.RECORDS)
        }
        h.await { h.fixture.historyQueries == listOf("elder" to dates, "elder" to dates) }
    }

    @Test fun unchangedDefaultDoesNotOverwriteExplicitHistorySelection() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.content.show(CaregiverDestination.RECORDS) }
        h.await { h.fixture.historyQueries == listOf("elder" to dates) }
        val explicit = LocalDateRange("2026-10-10", "2026-10-11")
        h.act {
            h.root.findViewById<EditText>(R.id.history_from).setText(explicit.fromDate)
            h.root.findViewById<EditText>(R.id.history_through).setText(explicit.throughDate)
            h.root.findViewById<View>(R.id.history_apply).performClick()
        }
        h.await { h.fixture.historyQueries == listOf("elder" to dates, "elder" to explicit) }
        h.act { h.content.show(CaregiverDestination.RECORDS) }
        h.act {
            assertEquals(explicit.fromDate, h.root.findViewById<EditText>(R.id.history_from).text.toString())
            assertEquals(listOf("elder" to dates, "elder" to explicit), h.fixture.historyQueries)
        }
    }

    @Test fun missingDefaultAllowsCalendarDraftAndExplicitRead() = withEntry(false) { h ->
        h.await { findText(h.root, h.root.context.getString(R.string.c1_feedback_date_unavailable)) != null }
        h.act { h.content.show(CaregiverDestination.RECORDS) }
        h.await { find(h.root, CaregiverHistoryHost::class.java) != null }
        var selected: LocalDateRange? = null
        h.act { activity ->
            assertTrue(h.fixture.historyQueries.isEmpty())
            h.root.findViewById<View>(R.id.history_pick_range).performClick()
            activity.supportFragmentManager.executePendingTransactions()
            val dialog = activity.supportFragmentManager.fragments
                .filterIsInstance<CaregiverHistoryDateRangeDialog>().single().requireDialog()
            val grid = dialog.findViewById<ViewGroup>(R.id.history_calendar_grid)
            val days = (0 until grid.childCount).map(grid::getChildAt).filter { it.tag is java.time.LocalDate }
            selected = LocalDateRange(days[0].tag.toString(), days[1].tag.toString())
            days[0].performClick(); days[1].performClick()
            dialog.findViewById<View>(R.id.history_dialog_confirm).performClick()
            assertTrue("Calendar confirmation only fills a draft", h.fixture.historyQueries.isEmpty())
            assertTrue("Calendar confirmation must not sync", h.fixture.refreshes.isEmpty())
            assertEquals(selected!!.fromDate, h.root.findViewById<EditText>(R.id.history_from).text.toString())
        }
        h.act { h.root.findViewById<View>(R.id.history_apply).performClick() }
        h.await { h.fixture.historyQueries == listOf("elder" to selected!!) }
        h.await { h.fixture.refreshes == listOf("elder" to selected!!) }
        h.await {
            h.root.findViewById<TextView>(R.id.history_applied_range).text.toString().contains(selected!!.fromDate) &&
                h.root.findViewById<TextView>(R.id.history_elder).text.isNotBlank()
        }
        captureDateEvidence("manual-history")
    }

    @Test fun changedAndWithdrawnDefaultsKeepManualReadAndPendingDraftUntilSessionChanges() = withEntry { h ->
        h.await { find(h.root, CaregiverOverviewHost::class.java) != null }
        h.act { h.content.show(CaregiverDestination.RECORDS) }
        h.await { h.fixture.historyQueries == listOf("elder" to dates) }
        val manual = LocalDateRange("2026-10-10", "2026-10-11")
        lateinit var original: CaregiverHistoryHost
        h.act {
            original = checkNotNull(find(h.root, CaregiverHistoryHost::class.java))
            h.root.findViewById<EditText>(R.id.history_from).setText(manual.fromDate)
            h.root.findViewById<EditText>(R.id.history_through).setText(manual.throughDate)
            h.root.findViewById<View>(R.id.history_apply).performClick()
        }
        h.await { h.fixture.historyQueries == listOf("elder" to dates, "elder" to manual) }
        h.act { h.root.findViewById<EditText>(R.id.history_from).setText("2026-10-09") }
        for (source in listOf(java.time.LocalDate.parse("2026-10-31"), null)) {
            h.act { h.suppliedToday = source; h.content.show(CaregiverDestination.RECORDS) }
            h.act {
                assertSame(original, find(h.root, CaregiverHistoryHost::class.java))
                assertEquals("2026-10-09", h.root.findViewById<EditText>(R.id.history_from).text.toString())
                assertEquals(manual.throughDate, h.root.findViewById<EditText>(R.id.history_through).text.toString())
                assertEquals(listOf("elder" to dates, "elder" to manual), h.fixture.historyQueries)
            }
        }
        captureDateEvidence("preserved-draft")
        h.act { h.fixture.trusted.value = TrustedSessionContext(h.fixture.scope, 2) }
        h.await { (h.model.state.value.context as? CaregiverContextState.Active)?.context?.sessionGeneration == 2L }
        h.act {
            assertEquals("", h.root.findViewById<EditText>(R.id.history_from).text.toString())
            assertEquals("", h.root.findViewById<EditText>(R.id.history_through).text.toString())
            assertEquals(listOf("elder" to dates, "elder" to manual), h.fixture.historyQueries)
        }
    }

    private fun captureDateEvidence(name: String) {
        instrumentation.waitForIdleSync()
        // Material floating labels finish after text changes; do not capture an intermediate frame.
        android.os.SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
        val language = InstrumentationRegistry.getArguments().getString("c1DateLanguage", "system")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.File(instrumentation.targetContext.filesDir, "date-$name-$language.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun withEntry(explicitDates: Boolean = true, eventRows: List<DoseEventState> = emptyList(),
        language: String? = InstrumentationRegistry.getArguments().getString("c1DateLanguage"), fontScale: Float? = null, widthDp: Int? = null,
        initialDateAvailable: Boolean = true, block: (Harness) -> Unit) {
        val appContext = instrumentation.targetContext.applicationContext
        val previousLocales = if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.os.LocaleListCompat.wrap(
                appContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales)
        } else androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        fun applyFixtureLocales(locales: androidx.core.os.LocaleListCompat) {
            instrumentation.runOnMainSync {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    appContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                        android.os.LocaleList.forLanguageTags(locales.toLanguageTags())
                }
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
            }
            instrumentation.waitForIdleSync()
        }
        if (language != null) applyFixtureLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(language))
        try {
            ActivityScenario.launch<CaregiverHistoryTestActivity>(
                Intent(instrumentation.targetContext, CaregiverHistoryTestActivity::class.java),
            ).use { scenario ->
                if (language != null) {
                    val expected = java.util.Locale.forLanguageTag(language).language
                    val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                    var observed = ""
                    do {
                        scenario.onActivity { activity ->
                            observed = androidx.core.os.ConfigurationCompat.getLocales(
                                activity.resources.configuration)[0]?.language.orEmpty()
                        }
                        if (observed == expected) break
                        instrumentation.waitForIdleSync()
                        android.os.SystemClock.sleep(16)
                    } while (android.os.SystemClock.uptimeMillis() < deadline)
                    assertEquals("Activity must receive the fixture language before page creation",
                        expected, observed)
                }
                val h = Harness(scenario, eventRows)
                if (!initialDateAvailable) h.suppliedToday = null
                var original: android.content.res.Configuration? = null
                h.act { activity ->
                    if (fontScale != null) {
                        val config = android.content.res.Configuration(activity.resources.configuration)
                        original = android.content.res.Configuration(config)
                        fontScale?.let { config.fontScale = it }
                        @Suppress("DEPRECATION")
                        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                    }
                    language?.let {
                        assertEquals(java.util.Locale.forLanguageTag(it).language,
                            androidx.core.os.ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language)
                    }
                    // Mirror C1's production IME/system-bar avoidance within this controlled test window.
                    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(activity.container) { view, insets ->
                        val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                            androidx.core.view.WindowInsetsCompat.Type.ime())
                        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                        androidx.core.view.WindowInsetsCompat.CONSUMED
                    }
                    h.root = activity.layoutInflater.inflate(R.layout.activity_caregiver_home, activity.container, false) as ViewGroup
                    val density = activity.resources.displayMetrics.density
                    activity.container.addView(h.root, FrameLayout.LayoutParams(
                        widthDp?.let { (it * density).toInt() } ?: -1, if (widthDp == null) -1 else (480 * density).toInt()))
                    androidx.core.view.ViewCompat.requestApplyInsets(activity.container)
                    val model = ChainViewModel(h.fixture.services)
                    h.model = model
                    h.store.put("entry", model)
                    h.content = OnlineFeatureContent(activity, h.root.findViewById(R.id.feature_content),
                        UserRole.CAREGIVER, model, h.fixture.services,
                        navigate = { destination -> h.lastDestination = destination; h.content.show(destination) },
                        feedbackRange = if (explicitDates) ({ requested ->
                            h.providerCalls++
                            CaregiverFeedbackRangeInput(
                                { (model.state.value.context as? CaregiverContextState.Active)?.context },
                                { h.suppliedToday?.let { CaregiverFeedbackDate(h.dateContextOverride ?: requested, it) } },
                            ).resolve(requested)
                        }) else null)
                }
                try { block(h) } finally { h.act { activity ->
                    h.content.close(); h.store.clear()
                    original?.let {
                        @Suppress("DEPRECATION")
                        activity.resources.updateConfiguration(it, activity.resources.displayMetrics)
                    }
                } }
            }
        } finally {
            if (language != null) applyFixtureLocales(previousLocales)
        }
    }

    private class Harness(val scenario: ActivityScenario<CaregiverHistoryTestActivity>, eventRows: List<DoseEventState>) {
        val fixture = Services(eventRows)
        val store = ViewModelStore()
        lateinit var model: ChainViewModel
        lateinit var root: ViewGroup
        lateinit var content: OnlineFeatureContent
        var providerCalls = 0
        var suppliedToday: java.time.LocalDate? = java.time.LocalDate.parse("2026-10-30")
        var dateContextOverride: CaregiverElderContext? = null
        var lastDestination: CaregiverDestination? = null
        fun act(action: (CaregiverHistoryTestActivity) -> Unit) = scenario.onActivity { action(it) }
        fun await(description: String = "Expected entry state", condition: () -> Boolean) = runBlocking {
            try {
                withTimeout(5_000) {
                    while (true) {
                        var result = false
                        act { result = condition() }
                        if (result) break
                        delay(25)
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                var actual = ""
                act {
                    val list = root.findViewById<android.widget.ListView>(R.id.history_list)
                    actual = "count=${list?.count}, first=${list?.firstVisiblePosition}, " +
                        "last=${list?.lastVisiblePosition}, top=${list?.getChildAt(0)?.top}"
                }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val image = instrumentation.uiAutomation.takeScreenshot()
                if (image != null) try {
                    val language = InstrumentationRegistry.getArguments().getString("c1DateLanguage", "system")
                    java.io.File(instrumentation.targetContext.filesDir, "date-timeout-$language.png")
                        .outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } finally { image.recycle() }
                throw AssertionError("$description; $actual", timeout)
            }
        }
    }

    private class Services(private val eventRows: List<DoseEventState>) {
        val scope = RepositoryScope("test", "installation", "caregiver")
        val trusted = MutableStateFlow<TrustedSessionContext?>(TrustedSessionContext(scope, 1))
        val caregiver = UserView("caregiver", "Fixture caregiver", UserRole.CAREGIVER)
        val elder = UserView("elder", "Fixture elder", UserRole.ELDER)
        val refreshes = mutableListOf<Pair<String, LocalDateRange>>()
        val historyQueries = mutableListOf<Pair<String, LocalDateRange>>()
        private fun <T> cached(value: T) = Cached(value, true, 1L, false, AccessState.AUTHORIZED)
        private val summary = SyncSummary(scope, "elder", SyncCompletion.COMPLETE, 1L, null)
        private val dashboard = object : CaregiverDashboardRepository {
            override fun observeOverview(elderId: String, range: LocalDateRange) = flowOf(cached(
                ElderOverview(User("elder", "Fixture elder", UserRole.ELDER), emptyList(), eventRows, null)))
            override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
                refreshes += elderId to range
                return Outcome.Success(summary)
            }
        }
        private val events = strict<DoseEventRepository> { method, args -> when (method) {
            "observeEvents" -> {
                historyQueries += (args[0] as String) to (args[1] as LocalDateRange)
                flowOf(cached(eventRows))
            }
            else -> error("Unexpected history operation: $method")
        } }
        val services = ChainServices(
            auth = strict { method, _ -> when (method) {
                "observeSession" -> flowOf(SessionView(AuthReadiness.READY, caregiver))
                else -> error("Unexpected auth operation: $method")
            } },
            pairing = strict { method, _ -> when (method) {
                "observeRequests" -> flowOf(cached(emptyList<PairingRequestView>()))
                "observeRelationships" -> flowOf(cached(listOf(CareRelationshipView(
                    "relationship", elder.id, caregiver.id, elder, caregiver, CareRelationshipStatus.ACTIVE, 1L))))
                "refresh" -> Outcome.Success(Unit)
                else -> error("Unexpected pairing write: $method")
            } },
            photos = strict { method, _ -> when (method) {
                "observePhotos" -> flowOf(cached(emptyList<MedicationPhotoView>()))
                "refresh" -> Outcome.Success(summary)
                else -> error("Unexpected photo operation: $method")
            } },
            plans = strict { method, _ -> when (method) {
                "observePlans" -> flowOf(cached(emptyList<ReminderPlanView>()))
                "observeMedications" -> flowOf(cached(emptyList<MedicationView>()))
                else -> error("Unexpected plan operation: $method")
            } },
            commands = strict { method, _ -> when (method) {
                "observeCommands" -> flowOf(emptyList<OnlineCommandRecord>())
                else -> error("Unexpected command write: $method")
            } },
            scope = MutableStateFlow(scope), trusted = trusted,
            resumePairingCode = { error("No recovery command in this test") },
            doseEvents = events, caregiverDashboard = dashboard,
            signOut = { error("No sign-out command in this test") },
        )
    }

    companion object {
        /** No silent success for unrelated methods; suspend calls return immediate fixture values. */
        private inline fun <reified T> strict(crossinline answer: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "Strict ${T::class.java.simpleName} fixture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> answer(method.name, args ?: emptyArray())
                }
            } as T

        private fun allViews(root: View): List<View> = listOf(root) +
            if (root is ViewGroup) (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) } else emptyList()
        private fun <T : View> find(root: View, type: Class<T>): T? = allViews(root).firstOrNull(type::isInstance)?.let(type::cast)
        private fun findText(root: View, text: String) = allViews(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
    }
}
