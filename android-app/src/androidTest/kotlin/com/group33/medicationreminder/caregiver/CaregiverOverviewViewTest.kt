package com.group33.medicationreminder.caregiver

import android.content.res.Configuration
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.ApiError
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only view tests. Repositories/data stay in androidTest and never enter the runtime app. */
@RunWith(AndroidJUnit4::class)
class CaregiverOverviewViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun debugPreviewActivityHostsTheComponentWithClearlyLabeledFictionalData() {
        ActivityScenario.launch<CaregiverOverviewTestActivity>(
            Intent(instrumentation.targetContext, CaregiverOverviewTestActivity::class.java),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val texts = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(texts.any { it.startsWith("DEBUG PREVIEW") && it.contains("no account or server connection") })
                assertTrue(texts.any { it.contains("Demo elder") })
                assertTrue(texts.contains(activity.getString(R.string.c3_stale)))
                assertTrue(texts.any { it.contains(activity.getString(R.string.c3_capability_degraded)) })
            }
        }
    }

    @Test fun englishAndChineseAtNormalAndLargeFontAndNarrowWidthsKeepTextAndActions() {
        for (language in listOf("en", "zh")) {
          for (fontScale in listOf(1f, 2f)) {
            withScreen(language, fontScale = fontScale) {
              for (widthDp in listOf(320, 360)) {
                onMain {
                    assertEquals(fontScale, context.resources.configuration.fontScale, 0f)
                    val width = (widthDp * context.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    val all = descendants(view).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }
                    assertTrue(all.any { it.text.contains(if (language == "zh") "用药记录概览" else "Medication records overview") })
                    for (text in all) {
                        assertTrue("Unmeasured text: ${text.id}", text.width > 0)
                        val layout = checkNotNull(text.layout)
                        assertTrue("Clipped text: ${text.id}", text.height >= layout.height + text.compoundPaddingTop + text.compoundPaddingBottom)
                        for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                    }
                    for (id in listOf(R.id.refresh_button, R.id.history_button)) {
                        val button = view.findViewById<View>(id)
                        assertTrue(button.isEnabled)
                        assertTrue(button.height >= 48 * context.resources.displayMetrics.density)
                    }
                    assertEquals(context.getString(R.string.c3_outcome_taken), view.findViewById<TextView>(R.id.outcome).text)
                }
              }
            }
          }
        }
    }

    @Test fun loadingUnknownMissingLocalAndAuthorizedEmptyHaveDistinctViews() = withScreen {
        onMain {
            val waiting = Repository("test-elder").apply { source = flow { awaitCancellation() } }
            model.selectContext(waiting, scope, "test-elder", range)
        }
        onMain {
            assertEquals(context.getString(R.string.c3_loading), view.findViewById<TextView>(R.id.state_message).text)
            assertEquals(0, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
            repository.updates.value = repository.updates.value.copy(access = AccessState.UNKNOWN)
            model.selectContext(repository, scope, "test-elder", range)
        }
        onMain {
            assertEquals(context.getString(R.string.c3_access_unknown), view.findViewById<TextView>(R.id.state_message).text)
            assertEquals("", view.findViewById<TextView>(R.id.elder_name).text.toString())
            assertFalse(view.findViewById<View>(R.id.history_button).isEnabled)
            repository.updates.value = repository.updates.value.copy(access = AccessState.AUTHORIZED, hasLocalData = false)
        }
        onMain {
            assertEquals(context.getString(R.string.c3_no_local_data), view.findViewById<TextView>(R.id.state_message).text)
            assertEquals(0, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
            repository.updates.value = repository.updates.value.let { cached ->
                cached.copy(hasLocalData = true, value = cached.value.copy(events = emptyList()))
            }
        }
        onMain {
            assertEquals(context.getString(R.string.c3_empty), view.findViewById<TextView>(R.id.state_message).text)
            assertEquals(0, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
            assertTrue(view.findViewById<View>(R.id.history_button).isEnabled)
        }
    }

    @Test fun capabilityNoticeKeepsObservedAndReceivedTimesSeparateInBothLanguages() {
        for (language in listOf("en", "zh")) withScreen(language) {
            val observedAt = 1_798_747_200_000L
            val receivedAt = observedAt + 8_000L
            val capability = ReminderCapabilityView(
                "test-capability", 2L, observedAt, DeliveryCondition.DEGRADED,
                notificationPermissionGranted = true,
                channelEnabled = false,
                exactAlarmAccess = ExactAlarmAccess.DENIED,
                reasons = emptyList(),
                receivedAtEpochMillis = receivedAt,
            )
            onMain {
                repository.updates.value = repository.updates.value.let { cached ->
                    cached.copy(value = cached.value.copy(capability = capability))
                }
            }
            onMain {
                val status = view.findViewById<TextView>(R.id.capability).text.toString()
                val details = view.findViewById<TextView>(R.id.capability_details).text.toString()
                assertTrue(status.contains(context.caregiverInstant(observedAt)))
                assertTrue(status.contains(context.getString(R.string.c3_capability_degraded)))
                assertEquals(context.capabilityDetails(capability), details)
                assertTrue(details.contains(context.caregiverInstant(receivedAt)))
                assertNotEquals(context.caregiverInstant(observedAt), context.caregiverInstant(receivedAt))
            }
        }
    }

    @Test fun revokedAccessRemovesPreviouslyRenderedPersonalContentAndDisablesActions() = withScreen {
        onMain { repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED) }
        onMain {
            assertEquals(context.getString(R.string.c3_revoked), view.findViewById<TextView>(R.id.state_message).text)
            assertEquals(0, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
            assertEquals("", view.findViewById<TextView>(R.id.elder_name).text.toString())
            assertFalse(view.findViewById<View>(R.id.history_button).isEnabled)
            assertFalse(view.findViewById<View>(R.id.refresh_button).isEnabled)
        }
    }

    @Test fun historyUsesCurrentSelectionAndNoSelectionCannotDispatchOldElder() = withScreen {
        onMain { view.findViewById<View>(R.id.history_button).performClick() }
        assertEquals(listOf("test-elder" to range), history)
        onMain {
            val other = Repository("other-test-elder")
            model.selectContext(other, scope, "other-test-elder", range)
        }
        onMain { view.findViewById<View>(R.id.history_button).performClick() }
        assertEquals("other-test-elder" to range, history.last())
        onMain {
            assertEquals(context.getString(R.string.c3_elder, "Test Elder (other-test-elder)"),
                view.findViewById<TextView>(R.id.elder_name).text)
        }
        onMain { model.clearContext() }
        onMain {
            view.findViewById<View>(R.id.history_button).performClick()
            assertEquals(context.getString(R.string.c3_select_elder), view.findViewById<TextView>(R.id.state_message).text)
        }
        assertEquals(2, history.size)
    }

    @Test fun repeatedRefreshClicksCallViewModelOnceAndFailureRetainsRows() = withScreen {
        onMain {
            view.findViewById<View>(R.id.refresh_button).performClick()
            view.findViewById<View>(R.id.refresh_button).performClick()
        }
        assertEquals(1, repository.refreshes)
        onMain {
            assertFalse(view.findViewById<View>(R.id.refresh_button).isEnabled)
            repository.result.complete(Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE)))
        }
        onMain {
            assertEquals(1, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
            assertEquals(context.getString(R.string.c3_offline), view.findViewById<TextView>(R.id.refresh_message).text)
            assertEquals(context.getString(R.string.c3_stale), view.findViewById<TextView>(R.id.freshness).text)
            assertTrue(view.findViewById<View>(R.id.refresh_button).isEnabled)
        }
    }

    @Test fun lifecycleRestartRendersLatestStateInsteadOfPreviousElder() = withScreen {
        onMain { lifecycle.currentState = Lifecycle.State.CREATED }
        onMain { model.clearContext() }
        onMain { lifecycle.currentState = Lifecycle.State.STARTED }
        onMain {
            assertEquals(0, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
            assertEquals("", view.findViewById<TextView>(R.id.elder_name).text.toString())
        }
    }

    @Test fun typedRefreshErrorsUseLocalizedCopyAndKeepCachedContextAtLargeFont() {
        val cases = listOf(
            RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE) to R.string.c3_refresh_storage_unavailable,
            RepositoryError.Local(LocalFailureCode.INVALID_INPUT) to R.string.c3_refresh_invalid_input,
            RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE) to R.string.c3_refresh_failed,
            RepositoryError.Remote(ApiError("SNAPSHOT_RANGE_TOO_LARGE", "private server message", false, "test")) to R.string.c3_refresh_range_too_large,
            RepositoryError.Remote(ApiError("VALIDATION_FAILED", "private server message", false, "test")) to R.string.c3_refresh_invalid_input,
            RepositoryError.Remote(ApiError("SERVICE_UNAVAILABLE", "private server message", true, "test")) to R.string.c3_refresh_service_unavailable,
            RepositoryError.Remote(ApiError("RETRY_LATER", "private server message", true, "test")) to R.string.c3_refresh_retry_later,
            RepositoryError.Remote(ApiError("FUTURE_ERROR", "private server message", false, "test")) to R.string.c3_refresh_failed,
        )
        for (language in listOf("en", "zh")) for ((error, copy) in cases) {
            withScreen(language, fontScale = 2f) {
                var lastSync = ""
                onMain {
                    lastSync = view.findViewById<TextView>(R.id.last_sync).text.toString()
                    view.findViewById<View>(R.id.refresh_button).performClick()
                    repository.result.complete(Outcome.Failure(error))
                }
                onMain {
                    val text = view.findViewById<TextView>(R.id.refresh_message)
                    assertEquals(context.getString(copy), text.text)
                    assertTrue(text.text.any { if (language == "zh") it in '\u4e00'..'\u9fff' else it in 'A'..'Z' })
                    assertFalse(text.text.contains("private server message"))
                    assertEquals(lastSync, view.findViewById<TextView>(R.id.last_sync).text.toString())
                    assertEquals(context.getString(R.string.c3_range, range.fromDate, range.throughDate),
                        view.findViewById<TextView>(R.id.date_range).text)
                    assertEquals(1, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
                    assertTrue(view.findViewById<View>(R.id.refresh_button).isEnabled)
                    val width = (320 * context.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    val layout = checkNotNull(text.layout)
                    assertTrue(text.height >= layout.height + text.compoundPaddingTop + text.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                }
            }
        }
    }

    @Test fun failedRefreshCanRetryOnSameScreenAndOnlyObservedCacheChangesSyncTime() {
        for (language in listOf("en", "zh")) withScreen(language) {
            var lastSync = ""
            onMain {
                lastSync = view.findViewById<TextView>(R.id.last_sync).text.toString()
                view.findViewById<View>(R.id.refresh_button).performClick()
                repository.result.complete(Outcome.Failure(RepositoryError.Remote(
                    ApiError("SERVICE_UNAVAILABLE", "private", true, "test"))))
            }
            onMain {
                assertEquals(context.getString(R.string.c3_refresh_service_unavailable),
                    view.findViewById<TextView>(R.id.refresh_message).text)
                assertEquals(lastSync, view.findViewById<TextView>(R.id.last_sync).text.toString())
                repository.result = CompletableDeferred()
                view.findViewById<View>(R.id.refresh_button).performClick()
            }
            onMain {
                assertEquals(context.getString(R.string.c3_refreshing), view.findViewById<TextView>(R.id.refresh_message).text)
                assertFalse(view.findViewById<View>(R.id.refresh_button).isEnabled)
                repository.result.complete(Outcome.Success(SyncSummary(scope, "test-elder", SyncCompletion.COMPLETE, 999L, null)))
            }
            onMain {
                assertEquals(context.getString(R.string.c3_refresh_complete), view.findViewById<TextView>(R.id.refresh_message).text)
                assertEquals(lastSync, view.findViewById<TextView>(R.id.last_sync).text.toString())
                assertTrue(view.findViewById<View>(R.id.refresh_button).isEnabled)
                assertEquals(listOf("test-elder" to range, "test-elder" to range), repository.requests)
                repository.updates.value = repository.updates.value.copy(lastSuccessfulSyncAtEpochMillis = 86_400_003L, isStale = false)
            }
            onMain {
                assertNotEquals(lastSync, view.findViewById<TextView>(R.id.last_sync).text.toString())
                assertEquals(View.GONE, view.findViewById<View>(R.id.freshness).visibility)
                assertEquals(1, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
            }
        }
    }

    @Test fun emptyObservationShowsUnavailableAndRefreshCanLoadRecords() {
        for (language in listOf("en", "zh")) withScreen(language) {
            onMain {
                repository.source = emptyFlow()
                model.selectContext(repository, scope, "test-elder", range)
            }
            onMain {
                assertEquals(context.getString(R.string.c3_unavailable), view.findViewById<TextView>(R.id.state_message).text)
                assertEquals(0, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
                assertTrue(view.findViewById<View>(R.id.refresh_button).isEnabled)
                assertFalse(view.findViewById<View>(R.id.history_button).isEnabled)
                repository.source = repository.updates
                repository.result.complete(Outcome.Success(SyncSummary(scope, "test-elder", SyncCompletion.COMPLETE, 999L, null)))
                view.findViewById<View>(R.id.refresh_button).performClick()
            }
            onMain {
                assertEquals(context.getString(R.string.c3_results), view.findViewById<TextView>(R.id.state_message).text)
                assertEquals(context.getString(R.string.c3_refresh_complete), view.findViewById<TextView>(R.id.refresh_message).text)
                assertEquals(1, view.findViewById<ViewGroup>(R.id.event_rows).childCount)
                assertTrue(view.findViewById<View>(R.id.history_button).isEnabled)
            }
        }
    }

    private val range = LocalDateRange("2026-10-01", "2026-10-30")
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-caregiver")

    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
        instrumentation.waitForIdleSync()
    }

    private fun withScreen(language: String = "en", fontScale: Float = 1f, block: Screen.() -> Unit) {
        lateinit var screen: Screen
        onMain { screen = Screen(language, fontScale) }
        try { screen.block() } finally { onMain { screen.close() } }
    }

    private inner class Screen(language: String, fontScale: Float) : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
        private val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            this.fontScale = fontScale
        }
        val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Group33)
        val repository = Repository("test-elder")
        val store = ViewModelStore()
        val model = CaregiverFeedbackViewModel(repository, "test-elder", range, scope).also { store.put("feedback", it) }
        val view = CaregiverOverviewView(context)
        val history = mutableListOf<Pair<String, LocalDateRange>>()

        init {
            lifecycle.currentState = Lifecycle.State.STARTED
            view.bind(this, model) { id, dates -> history += id to dates }
        }

        fun close() {
            lifecycle.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    private class Repository(elderId: String) : CaregiverDashboardRepository {
        private val occurrence = OccurrenceKey("test-plan", "2026-10-21", "08:00")
        private val evidence = OccurrenceGenerationEvidence("test-generation", occurrence, 1, 1L, "Asia/Shanghai",
            2L, 28800, TimeResolutionPolicy.P0_TIME_V1, "opaque-test-snapshot", 1L)
        private val event = DoseEventState(occurrence, "test-event", evidence, "test-medication",
            MedicationText("Test medication with a deliberately long name ".repeat(4), "Test dosage ".repeat(4)),
            7_200_002L, DoseStatus.TAKEN, DoseStatus.TAKEN, emptyList())
        val updates = MutableStateFlow(Cached(
            ElderOverview(User(elderId, "Test Elder ($elderId)", UserRole.ELDER), emptyList(), listOf(event), null),
            true, 3L, false, AccessState.AUTHORIZED,
        ))
        var result = CompletableDeferred<Outcome<SyncSummary>>()
        val requests = mutableListOf<Pair<String, LocalDateRange>>()
        var refreshes = 0
        var source: Flow<Cached<ElderOverview>> = updates
        override fun observeOverview(elderId: String, range: LocalDateRange) = source
        override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> {
            refreshes++
            requests += elderId to range
            return result.await()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
