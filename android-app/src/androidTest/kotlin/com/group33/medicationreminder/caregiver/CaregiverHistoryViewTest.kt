package com.group33.medicationreminder.caregiver

import android.content.Intent
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import android.widget.GridLayout
import java.time.LocalDate
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.allOf
import java.util.TimeZone
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Contract-matching fictional data, device framework Views; no fake in the runtime application. */
@RunWith(AndroidJUnit4::class)
class CaregiverHistoryViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var calendarRoot: View? = null
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-caregiver")
    private val elder = User("test-elder", "Test Elder", UserRole.ELDER)
    private val range = LocalDateRange("2026-10-01", "2026-10-30")

    @Test fun everyFontChoosesDatesByTappingACalendar() = withScreen(fontScale = 2f) {
        onMain {
            header.findViewById<View>(R.id.history_pick_range).performClick()
            host.supportFragmentManager.executePendingTransactions()
            val popup = host.supportFragmentManager.findFragmentByTag("c3.history.date-range") as androidx.fragment.app.DialogFragment
            val root = checkNotNull(popup.dialog?.window?.decorView)
            assertTrue("Large font must still choose by tapping dates, never switch to date input fields",
                visibleViews(root).filterIsInstance<EditText>().isEmpty())
        }
    }

    @Test fun weekdayHeadersIdentifyAllSevenDaysInBothLanguages() {
        for (language in listOf("zh", "en")) withScreen(language, 2f) {
        onMain {
            val grid = checkNotNull(openPicker().dialog).findViewById<GridLayout>(R.id.history_calendar_grid)
            val labels = (0..6).map { (grid.getChildAt(it) as TextView).text.toString() }
            assertEquals("Weekday headers must identify different days: $labels", 7, labels.distinct().size)
        }
        }
    }

    @Test fun dateRangePickerEntryOpensWithoutQuerying() = withScreen {
        onMain {
            val entries = arrayListOf<View>()
            header.findViewsWithText(entries, "Choose dates", View.FIND_VIEWS_WITH_TEXT)
            assertEquals("History must expose a date-range dialog entry", 1, entries.size)
            val before = repository.queries.size
            entries.single().performClick()
            host.supportFragmentManager.executePendingTransactions()
            assertNotNull(host.supportFragmentManager.findFragmentByTag("c3.history.date-range"))
            assertEquals(before, repository.queries.size)
        }
    }

    @Test fun calendarConfirmUsesUtcDatesAndCancelPreservesDraftsWithoutImplicitReads() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            withScreen {
                val reads = repository.queries.size
                onMain {
                    text(R.id.history_from).text = "2026-10-20"
                    text(R.id.history_through).text = "2026-10-21"
                    openPicker()
                    // Rapid taps must not create two dialogs.
                    header.findViewById<View>(R.id.history_pick_range).performClick()
                    host.supportFragmentManager.executePendingTransactions()
                    assertEquals(1, host.supportFragmentManager.fragments.filterIsInstance<CaregiverHistoryDateRangeDialog>().size)
                }
                chooseDay("22"); chooseDay("23")
                onView(withId(R.id.history_dialog_confirm)).inRoot(withDecorView(equalTo(checkNotNull(calendarRoot)))).perform(scrollTo(), click())
                onMain {
                    assertEquals("2026-10-22", text(R.id.history_from).text.toString())
                    assertEquals("2026-10-23", text(R.id.history_through).text.toString())
                    assertEquals(range, model.state.value.range)
                    assertEquals(reads, repository.queries.size)
                    assertEquals(2, model.state.value.events.size)
                    header.findViewById<View>(R.id.history_apply).performClick()
                }
                onMain {
                    assertEquals(LocalDateRange("2026-10-22", "2026-10-23"), model.state.value.range)
                    assertTrue(model.state.value.events.isEmpty())
                    openPicker()
                }
                chooseDay("24"); chooseDay("25")
                onView(withId(R.id.history_dialog_cancel)).inRoot(withDecorView(equalTo(checkNotNull(calendarRoot)))).perform(scrollTo(), click())
                onMain {
                    assertEquals("2026-10-22", text(R.id.history_from).text.toString())
                    assertEquals("2026-10-23", text(R.id.history_through).text.toString())
                    assertEquals(reads + 1, repository.queries.size)
                }
            }
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun contextClearAndRevocationDismissTheDialogAndIgnoreItsOldConfirmControl() = withScreen {
        var oldConfirm: View? = null
        var picker: DialogFragment? = null
        onMain {
            picker = openPicker()
            oldConfirm = checkNotNull(picker?.dialog).findViewById(R.id.history_dialog_confirm)
            model.clearContext()
        }
        onMain {
            host.supportFragmentManager.executePendingTransactions()
            assertTrue(picker?.dialog?.isShowing != true)
            checkNotNull(oldConfirm).performClick()
            assertEquals("", text(R.id.history_from).text.toString())
            model.selectContext(repository, scope, elder, range)
        }
        onMain {
            assertEquals(range.fromDate, text(R.id.history_from).text.toString())
            picker = openPicker()
            oldConfirm = checkNotNull(picker?.dialog).findViewById(R.id.history_dialog_confirm)
            repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED)
        }
        onMain {
            host.supportFragmentManager.executePendingTransactions()
            assertTrue(picker?.dialog?.isShowing != true)
            val from = text(R.id.history_from).text.toString()
            val reads = repository.queries.size
            checkNotNull(oldConfirm).performClick()
            assertEquals(from, text(R.id.history_from).text.toString())
            assertEquals(reads, repository.queries.size)
            assertFalse(header.findViewById<View>(R.id.history_pick_range).isEnabled)
        }
    }

    private fun chooseDay(day: String) = chooseDate(LocalDate.of(2026, 10, day.toInt()))
    private fun chooseDate(date: LocalDate) {
        val interaction = onView(allOf(withTagValue(equalTo(date)), isDescendantOfA(withId(R.id.history_calendar_grid))))
            .inRoot(withDecorView(equalTo(checkNotNull(calendarRoot))))
        interaction.perform(scrollTo())
        var target: View? = null
        interaction.check { view, error -> if (error != null) throw error else target = view }
        awaitLayoutFrame(checkNotNull(target))
        // Native window move/resize transactions are not main-looper idling resources.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        interaction.perform(click())
        onMain { assertTrue("Tapped date must be selected: $date", (checkNotNull(target) as MaterialButton).isChecked) }
    }

    @Test fun calendarGridAndButtonsRemainReadableAtBothFontsAndAllWindowWidths() {
        for (language in listOf("en", "zh")) for (font in listOf(1f, 2f)) withScreen(language, font) {
            var picker: DialogFragment? = null
            onMain { picker = openPicker() }
            val root = checkNotNull(picker?.dialog?.window?.decorView)
            for (width in listOf(320, 360, 600, 320)) {
                onMain { checkNotNull(picker?.dialog?.window).setLayout((width * host.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT) }
                awaitLayoutFrame(root)
                onMain {
                    assertTrue(visibleViews(root).filterIsInstance<EditText>().isEmpty())
                    assertReadable(root)
                    val grid = root.findViewById<GridLayout>(R.id.history_calendar_grid)
                    val day = grid.findViewWithTag<MaterialButton>(LocalDate.of(2026, 10, 22))
                    assertEquals("22", day.text.toString())
                    assertEquals(1, day.layout.lineCount)
                    assertTrue(day.paint.measureText(day.text.toString()) <= day.width - day.compoundPaddingLeft - day.compoundPaddingRight)
                    val minimum = 48 * day.resources.displayMetrics.density
                    assertTrue(day.width >= minimum && day.height >= minimum)
                    assertTrue(grid.width >= minimum * 7)
                    for (id in listOf(R.id.history_calendar_from, R.id.history_calendar_through, R.id.history_calendar_scroll_hint,
                        R.id.history_calendar_month, R.id.history_dialog_confirm, R.id.history_dialog_cancel)) {
                        assertTrue("Non-calendar controls must fit the window horizontally", root.findViewById<View>(id).width <= root.width)
                    }
                    if (width == 320) assertTrue(grid.width > root.findViewById<View>(R.id.history_calendar_scroll).width)
                    assertTrue(root.findViewById<View>(R.id.history_dialog_confirm).height >= minimum)
                    assertTrue(root.findViewById<View>(R.id.history_dialog_cancel).height >= minimum)
                }
            }
            chooseDay("22"); chooseDay("23")
            onView(withId(R.id.history_dialog_confirm)).inRoot(withDecorView(equalTo(checkNotNull(calendarRoot)))).perform(scrollTo(), click())
            onMain {
                assertEquals("$language font=$font", "2026-10-22", text(R.id.history_from).text.toString())
                assertEquals("$language font=$font", "2026-10-23", text(R.id.history_through).text.toString())
                assertEquals(range, model.state.value.range)
            }
        }
    }

    @Test fun calendarSupportsLeapDayCrossMonthAndSameDayWithoutImplicitReads() = withScreen {
        onMain {
            text(R.id.history_from).text = "2028-02-29"
            text(R.id.history_through).text = "2028-02-29"
            openPicker()
        }
        val reads = repository.queries.size
        chooseDate(LocalDate.of(2028, 2, 29))
        onMain {
            val popup = host.supportFragmentManager.findFragmentByTag("c3.history.date-range") as DialogFragment
            assertFalse("First click starts a new range", checkNotNull(popup.dialog).findViewById<View>(R.id.history_dialog_confirm).isEnabled)
        }
        onView(withId(R.id.history_calendar_next)).inRoot(withDecorView(equalTo(checkNotNull(calendarRoot)))).perform(scrollTo(), click())
        chooseDate(LocalDate.of(2028, 3, 1))
        onMain {
            val popup = host.supportFragmentManager.findFragmentByTag("c3.history.date-range") as DialogFragment
            val root = checkNotNull(popup.dialog?.window?.decorView)
            assertEquals(context.getString(R.string.c3_history_calendar_through, "2028-03-01"), root.findViewById<TextView>(R.id.history_calendar_through).text.toString())
            assertTrue(root.findViewById<View>(R.id.history_dialog_confirm).isEnabled)
        }
        onView(withId(R.id.history_dialog_confirm)).inRoot(withDecorView(equalTo(checkNotNull(calendarRoot)))).perform(scrollTo(), click())
        onMain {
            assertEquals("2028-02-29", text(R.id.history_from).text.toString())
            assertEquals("2028-03-01", text(R.id.history_through).text.toString())
            assertEquals(reads, repository.queries.size)
            openPicker()
        }
        chooseDate(LocalDate.of(2028, 2, 29)); chooseDate(LocalDate.of(2028, 2, 29))
        onView(withId(R.id.history_dialog_confirm)).inRoot(withDecorView(equalTo(checkNotNull(calendarRoot)))).perform(scrollTo(), click())
        onMain {
            assertEquals("2028-02-29", text(R.id.history_through).text.toString())
            assertEquals(reads, repository.queries.size)
        }
    }

    @Test fun partialCalendarRangeCannotConfirmAndOldConfirmationCannotSurviveRevocation() = withScreen(fontScale = 2f) {
        var confirm: View? = null
        onMain {
            val popup = checkNotNull(openPicker().dialog?.window?.decorView)
            confirm = popup.findViewById(R.id.history_dialog_confirm)
            popup.findViewWithTag<View>(LocalDate.of(2026, 10, 21)).performClick()
            assertFalse(checkNotNull(confirm).isEnabled)
            popup.findViewWithTag<View>(LocalDate.of(2026, 10, 20)).performClick()
            assertFalse(checkNotNull(confirm).isEnabled)
            popup.findViewWithTag<View>(LocalDate.of(2026, 10, 20)).performClick()
            assertTrue(checkNotNull(confirm).isEnabled)
            repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED)
        }
        onMain {
            val reads = repository.queries.size
            checkNotNull(confirm).performClick()
            assertEquals(range.fromDate, text(R.id.history_from).text.toString())
            assertEquals(reads, repository.queries.size)
        }
    }

    @Test fun twoDateFiltersAndClickingDetailsUseFrozenEventTextWithoutDeletingOldRecords() = withScreen {
        var closed: View? = null
        onMain { filter("2026-10-20", "2026-10-20") }
        onMain {
            measure()
            assertEquals(2, list.adapter.count)
            list.performItemClick(list.adapter.getView(1, null, list), 1, 0)
        }
        onMain { showDetails() }
        onMain {
            assertEquals("Frozen medicine 20", checkNotNull(model.state.value.selectedEvent).medicationSnapshot.displayName)
            assertEquals(context.getString(R.string.c3_history_instructions, "Frozen instructions 20"), text(R.id.history_instructions).text)
            closed = details()
            assertTrue(checkNotNull(closed).isAttachedToWindow)
            checkNotNull(closed).findViewById<View>(R.id.history_close_details).performClick()
        }
        onMain {
            assertTrue(view.findViewById<View>(R.id.history_details)?.visibility != View.VISIBLE)
            assertEquals("", checkNotNull(closed).findViewById<TextView>(R.id.history_instructions).text.toString())
            filter("2026-10-21", "2026-10-21")
        }
        onMain {
            measure()
            list.performItemClick(list.adapter.getView(1, null, list), 1, 0)
        }
        onMain { showDetails() }
        onMain {
            assertEquals("Frozen medicine 21", model.state.value.selectedEvent?.medicationSnapshot?.displayName)
            assertTrue(text(R.id.history_instructions).text.contains("Frozen instructions 21"))
        }
        assertEquals(listOf("2026-10-20", "2026-10-21"), repository.queries.takeLast(2).map { it.second.fromDate })
        assertEquals(2, repository.updates.value.value.size)
    }

    @Test fun selectedDetailsStayAfterTheirRecordWithoutJumpingToFilters() = withScreen {
        var anchor = -1
        var closed: View? = null
        onMain { repository.updates.value = repository.updates.value.copy(value = (10..29).map { event(it) }) }
        onMain { list.setSelectionFromTop(10, 0) } // Day20, with enough rows below to keep this anchor.
        onMain {
            anchor = list.firstVisiblePosition
            assertTrue("Fixture must start below the filters", anchor > 0)
            list.performItemClick(list.getChildAt(10 - anchor), 10, 0)
        }
        onMain {
            assertEquals("Frozen medicine 20", model.state.value.selectedEvent?.medicationSnapshot?.displayName)
            assertNull("Details must belong to the selected row, not the filter header", header.findViewById<View>(R.id.history_details))
            assertEquals(22, list.adapter.count) // header, twenty events and one inline detail
            val details = list.adapter.getView(11, null, list)
            assertEquals(context.getString(R.string.c3_history_instructions, "Frozen instructions 20"),
                details.findViewById<TextView>(R.id.history_instructions).text)
            assertEquals("Opening details must not jump to the filter header", anchor, list.firstVisiblePosition)
        }
        onMain { list.getChildAt(9 - list.firstVisiblePosition).performClick() } // Select day21 using the actual card.
        onMain {
            assertEquals("Frozen medicine 21", model.state.value.selectedEvent?.medicationSnapshot?.displayName)
            assertEquals(22, list.adapter.count) // Switching replaces, rather than duplicates, details.
            closed = details()
            assertTrue(checkNotNull(closed).findViewById<TextView>(R.id.history_instructions).text.contains("Frozen instructions 21"))
            assertFalse(checkNotNull(closed).findViewById<TextView>(R.id.history_instructions).text.contains("Frozen instructions 20"))
            checkNotNull(closed).findViewById<View>(R.id.history_close_details).performClick()
        }
        onMain {
            assertNull(model.state.value.selectedEvent)
            assertEquals(21, list.adapter.count)
            assertTrue("Closing details must retain the list position", list.firstVisiblePosition > 0)
            assertEquals("", checkNotNull(closed).findViewById<TextView>(R.id.history_instructions).text.toString())
        }
    }

    @Test fun invalidAndOversizedDatesNeverQueryAndValidEmptyDateIsNotAnError() = withScreen {
        val count = repository.queries.size
        onMain { filter("2026-02-30", "2026-10-30") }
        onMain { assertEquals(context.getString(R.string.c3_history_invalid_date), text(R.id.history_range_error).text) }
        onMain { filter("2026-01-01", "2026-04-01") }
        onMain {
            assertEquals(context.getString(R.string.c3_history_too_long), text(R.id.history_range_error).text)
            assertEquals(context.getString(R.string.c3_results), text(R.id.history_state).text)
            assertEquals(2, model.state.value.events.size)
        }
        assertEquals(count, repository.queries.size)
        onMain { filter("2026-10-22", "2026-10-22") }
        onMain {
            assertEquals(context.getString(R.string.c3_empty), text(R.id.history_state).text)
            assertEquals("", text(R.id.history_range_error).text.toString())
            assertTrue(model.state.value.events.isEmpty())
        }
        assertEquals(2, repository.updates.value.value.size)
    }

    @Test fun rangeValidationIsExposedOnBothDateInputsWithoutLosingLocalizedHints() {
        val cases = listOf(
            Triple("2026-02-30", "2026-10-30", R.string.c3_history_invalid_date),
            Triple("2026-10-30", "2026-10-01", R.string.c3_history_reversed),
            Triple("2026-01-01", "2026-04-01", R.string.c3_history_too_long),
        )
        for (language in listOf("en", "zh")) withScreen(language) {
            for ((from, through, errorId) in cases) {
                onMain { filter(from, through) }
                onMain { assertDateInputErrors(context.getString(errorId)) }
            }
        }
    }

    @Test fun correctingRangeOrClearingContextRemovesOldInputAccessibilityErrors() {
        for (language in listOf("en", "zh")) withScreen(language) {
            onMain { filter("2026-02-30", "2026-10-30") }
            onMain { assertDateInputErrors(context.getString(R.string.c3_history_invalid_date)) }
            onMain { filter("2026-10-01", "2026-10-30") }
            onMain { assertDateInputErrors(null) }
            onMain { filter("2026-02-30", "2026-10-30") }
            onMain {
                assertDateInputErrors(context.getString(R.string.c3_history_invalid_date))
                model.clearContext()
            }
            onMain {
                assertDateInputErrors(null)
                assertFalse(header.findViewById<View>(R.id.history_from).isEnabled)
                assertFalse(header.findViewById<View>(R.id.history_through).isEnabled)
            }
        }
    }

    @Test fun revocationClearsRowsAndOpenDetailsAndDisablesFiltering() = withScreen {
        var removed: View? = null
        onMain { model.selectEvent(model.state.value.events.first().occurrence) }
        onMain { showDetails() }
        onMain {
            removed = details()
            repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED)
        }
        onMain {
            assertEquals(context.getString(R.string.c3_revoked), text(R.id.history_state).text)
            assertEquals("", text(R.id.history_elder).text.toString())
            assertEquals("", checkNotNull(removed).findViewById<TextView>(R.id.history_instructions).text.toString())
            assertEquals("", checkNotNull(removed).findViewById<TextView>(R.id.medication).text.toString())
            assertTrue(view.findViewById<View>(R.id.history_details)?.visibility != View.VISIBLE)
            assertFalse(header.findViewById<View>(R.id.history_apply).isEnabled)
            assertEquals(1, list.adapter.count)
        }
    }

    @Test fun readFailureRetainsDataAndLocalReadRetryClearsTheError() = withScreen {
        onMain { repository.failRead = true; model.retryObservation() }
        onMain {
            assertEquals(context.getString(R.string.c3_history_read_error), text(R.id.history_read_error).text)
            assertEquals(View.VISIBLE, text(R.id.history_read_error).visibility)
            assertEquals(2, model.state.value.events.size)
            assertTrue(model.state.value.isStale)
            repository.failRead = false
            header.findViewById<View>(R.id.history_retry).performClick()
        }
        onMain {
            assertEquals(View.GONE, text(R.id.history_read_error).visibility)
            assertFalse(model.state.value.observationFailed)
        }
        assertEquals(3, repository.queries.size)
    }

    @Test fun bothLanguagesFontScalesAndWidthsKeepFilterAndDetailTextReadable() {
        for (language in listOf("en", "zh")) {
            for (fontScale in listOf(1f, 2f)) {
                withScreen(language, fontScale) {
                    onMain { model.selectEvent(model.state.value.events.first().occurrence) }
                    // null uses all space allocated by the host, then returns to narrow mode.
                    for (width in listOf(320, 360, null, 320)) {
                        onMain { measure(width) }
                        awaitLayoutFrame(view)
                        onMain { showFilters() }
                        awaitLayoutFrame(view)
                        onMain {
                            assertEquals(fontScale, context.resources.configuration.fontScale, 0f)
                            assertTrue("Header not attached for $language/font=$fontScale/width=$width touchMode=${list.isInTouchMode} first=${list.firstVisiblePosition} selected=${list.selectedItemPosition}", header.isAttachedToWindow)
                            assertEquals(context.getString(R.string.c3_history), text(R.id.history_title).text)
                            assertReadable(header)
                            for (id in listOf(R.id.history_pick_range, R.id.history_apply, R.id.history_from, R.id.history_through)) {
                                assertTrue(header.findViewById<View>(id).height >= 48 * context.resources.displayMetrics.density)
                            }
                            assertTrue(header.findViewById<EditText>(R.id.history_from).requestFocus())
                            assertTrue(header.findViewById<EditText>(R.id.history_through).requestFocus())
                            showDetails()
                        }
                        awaitLayoutFrame(view)
                        onMain {
                            val detail = details()
                            assertTrue(detail.isAttachedToWindow)
                            assertReadable(detail)
                            assertTrue(detail.findViewById<View>(R.id.history_close_details).height >= 48 * context.resources.displayMetrics.density)
                            assertEquals("Frozen medicine 20", model.state.value.selectedEvent?.medicationSnapshot?.displayName)
                            if (width == null) {
                                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.width)
                                assertEquals(host.container.width - host.container.paddingLeft - host.container.paddingRight, view.width)
                                assertEquals(view.width, list.width)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun awaitLayoutFrame(view: View) {
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { view.postOnAnimation { view.postOnAnimation { frame.countDown() } } }
        assertTrue("Attached window did not deliver a layout frame", frame.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
        instrumentation.waitForIdleSync()
    }

    @Test fun debugHostAppliesSystemInsetsWithoutNestedScrolling() = withScreen {
        onMain {
            assertTrue(view.isAttachedToWindow)
            assertSame(host.container, view.parent)
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(host.container))
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertEquals(insets.left, host.container.paddingLeft)
            assertEquals(insets.top, host.container.paddingTop)
            assertEquals(insets.right, host.container.paddingRight)
            assertEquals(insets.bottom, host.container.paddingBottom)
        }
    }

    private fun withScreen(language: String = "en", fontScale: Float = 1f, block: Screen.() -> Unit) {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CaregiverHistoryTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaregiverHistoryTestActivity
        instrumentation.waitForIdleSync()
        var screen: Screen? = null
        try {
            onMain { screen = Screen(host, language, fontScale) }
            checkNotNull(screen).block()
        } finally {
            onMain { screen?.close(); host.finish() }
        }
    }

    @Suppress("DEPRECATION")
    private inner class Screen(val host: CaregiverHistoryTestActivity, language: String, fontScale: Float) {
        private val originalHostConfig = Configuration(host.resources.configuration)
        private val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language)); this.fontScale = fontScale
        }
        val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Group33)
        val repository = Repository()
        private val store = ViewModelStore()
        val model = CaregiverHistoryViewModel(repository, scope, elder, range).also { store.put("history", it) }
        val view = CaregiverHistoryView(context)
        val list: ListView get() = view.findViewById(R.id.history_list)
        val header: View get() = list.adapter.getView(0, null, list)
        init {
            // Dialog fragments inflate from their Activity, so match its fixture language/font too.
            host.resources.updateConfiguration(config, host.resources.displayMetrics)
            host.container.addView(view)
            view.bind(host, model, host.supportFragmentManager)
            measure()
        }
        fun openPicker(): DialogFragment {
            header.findViewById<View>(R.id.history_pick_range).performClick()
            host.supportFragmentManager.executePendingTransactions()
            val picker = host.supportFragmentManager.findFragmentByTag("c3.history.date-range") as DialogFragment
            calendarRoot = checkNotNull(picker.dialog?.window?.decorView)
            check(!picker.isRemoving && picker.dialog?.isShowing == true) { "Expected a live calendar, removing=${picker.isRemoving}, showing=${picker.dialog?.isShowing}" }
            return picker
        }
        fun text(id: Int): TextView = checkNotNull(view.findViewById(id) ?: header.findViewById(id))
        fun details(): View = checkNotNull(view.findViewById(R.id.history_details))
        fun showFilters() {
            // Resizing preserves the current content anchor. Scroll back as a touch user would,
            // rather than mixing a selection request with ListView's resize layout.
            repeat(20) {
                if (!list.canScrollVertically(-1)) return
                list.scrollListBy(-list.height)
            }
            error("History filters were not reachable by scrolling")
        }
        fun showDetails() {
            val position = (list.headerViewsCount until list.adapter.count).first { list.adapter.getItemViewType(it) == 1 }
            list.setSelectionFromTop(position, 0)
        }
        fun assertReadable(root: View) {
            for (text in visibleViews(root).filterIsInstance<TextView>()) {
                if (text.text.isEmpty()) continue
                assertTrue("Unmeasured text: ${text.id}", text.width > 0)
                val layout = checkNotNull(text.layout)
                assertTrue("Clipped text: ${text.id}", text.height >= layout.height + text.compoundPaddingTop + text.compoundPaddingBottom)
                for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
            }
        }
        fun filter(from: String, through: String) {
            header.findViewById<EditText>(R.id.history_from).setText(from)
            header.findViewById<EditText>(R.id.history_through).setText(through)
            header.findViewById<View>(R.id.history_apply).performClick()
        }
        @Suppress("DEPRECATION")
        fun assertDateInputErrors(expected: String?) {
            for ((id, hintId) in listOf(
                R.id.history_from to R.string.c3_history_from,
                R.id.history_through to R.string.c3_history_through,
            )) {
                val node = AccessibilityNodeInfoCompat.wrap(header.findViewById<EditText>(id).createAccessibilityNodeInfo())
                try {
                    assertEquals("Date input must expose whether its range is invalid", expected != null, node.isContentInvalid)
                    assertEquals(expected, node.error?.toString())
                    assertEquals(context.getString(hintId), node.hintText?.toString())
                } finally {
                    node.recycle()
                }
            }
        }
        fun measure(widthDp: Int? = 360) {
            val density = context.resources.displayMetrics.density
            val width = widthDp?.let { (it * density).toInt() } ?: (host.container.width - host.container.paddingLeft - host.container.paddingRight)
            val height = host.container.height - host.container.paddingTop - host.container.paddingBottom
            check(height > 0) { "History test requires a laid-out real window" }
            view.layoutParams = FrameLayout.LayoutParams(if (widthDp == null) ViewGroup.LayoutParams.MATCH_PARENT else width, ViewGroup.LayoutParams.MATCH_PARENT)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        fun close() {
            host.container.removeAllViews()
            store.clear()
            host.resources.updateConfiguration(originalHostConfig, host.resources.displayMetrics)
        }
    }

    private inner class Repository : DoseEventRepository {
        val updates = MutableStateFlow(Cached(listOf(event(20), event(21)), true, 123L, false, AccessState.AUTHORIZED))
        val queries = mutableListOf<Pair<String, LocalDateRange>>()
        var failRead = false
        override fun observeEvents(elderId: String, range: LocalDateRange): kotlinx.coroutines.flow.Flow<Cached<List<DoseEventState>>> {
            queries += elderId to range
            fun filtered(cached: Cached<List<DoseEventState>>) = cached.copy(value = cached.value.filter {
                it.occurrence.localDate >= range.fromDate && it.occurrence.localDate <= range.throughDate
            })
            return if (failRead) flow { emit(filtered(updates.value)); error("test read failure") }
                else updates.map { filtered(it) }
        }
        override fun observeLocalAction(actionId: String): kotlinx.coroutines.flow.Flow<LocalActionReceipt?> = error("Read-only history")
        override suspend fun recordAction(occurrence: OccurrenceKey, kind: DoseActionKind): Outcome<LocalActionReceipt> = error("Read-only history")
        override suspend fun retryAction(actionId: String): Outcome<RetryQueued> = error("Read-only history")
    }

    private fun event(day: Int): DoseEventState {
        val key = OccurrenceKey("test-plan", "2026-10-$day", "08:00")
        val evidence = OccurrenceGenerationEvidence("test-generation-$day", key, 1, day.toLong(), "Asia/Shanghai",
            day.toLong(), 28800, TimeResolutionPolicy.P0_TIME_V1, "test-opaque", 1L)
        return DoseEventState(key, "test-event-$day", evidence, "test-medication",
            MedicationText("Frozen medicine $day", "Frozen dosage $day", "Frozen instructions $day"),
            7_200_000L + day, DoseStatus.TAKEN, DoseStatus.TAKEN, emptyList())
    }

    private fun visibleViews(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { visibleViews(view.getChildAt(it)) } else emptyList()
}
