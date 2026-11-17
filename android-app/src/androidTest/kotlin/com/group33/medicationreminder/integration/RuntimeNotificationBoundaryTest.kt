package com.group33.medicationreminder.integration

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.reminder.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real SystemUI notification clicks and saved PendingIntent replays -> receiver -> Room. */
class RuntimeNotificationBoundaryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val dao get() = ReminderDatabase.open(context).reminders()

    @Before fun permission() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
    }

    private suspend fun ringing(remaining: Long): ReminderGroupRow {
        val now = System.currentTimeMillis()
        val registration = testRegistration("notification-${UUID.randomUUID()}", now + remaining - 7_200_000,
            state = ReminderState.RINGING)
        dao.insertTestRegistration(registration)
        assertTrue(ReminderNotifications.show(context, registration.group))
        await { manager.activeNotifications.any { ReminderNotifications.isForGroup(it.notification, registration.group.groupId) } }
        return registration.group
    }

    private fun posted(id: String) = manager.activeNotifications.single {
        ReminderNotifications.isForGroup(it.notification, id)
    }.notification
    private fun Notification.action(label: Int) = actions.single { it.title.toString() == context.getString(label) }.actionIntent
    private fun Notification.hasAction(label: Int) = actions.any { it.title.toString() == context.getString(label) }

    private suspend fun click(action: PendingIntent) {
        val finished = CompletableDeferred<Unit>()
        action.send(context, 0, null, { _, _, _, _, _ -> finished.complete(Unit) }, Handler(Looper.getMainLooper()))
        withTimeout(10_000) { finished.await() }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private suspend fun clickInNotificationShade(label: Int) {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("cmd statusbar expand-notifications")
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        fun tap(node: AccessibilityNodeInfo): Boolean {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.isEmpty || !node.isVisibleToUser) return false
            val now = SystemClock.uptimeMillis()
            fun event(action: Int, time: Long) = MotionEvent.obtain(now, time, action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val down = event(MotionEvent.ACTION_DOWN, now)
            val up = event(MotionEvent.ACTION_UP, now + 50)
            return try { automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true) }
            finally { down.recycle(); up.recycle() }
        }
        var expanded = false
        try { await {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() != "com.android.systemui") return@await false
            // RemoteViews action labels are exposed in the tree even when text search misses them.
            val button = findNode(root) { it.isVisibleToUser && it.text?.toString()?.equals(context.getString(label), ignoreCase = true) == true }
            if (button != null) {
                tap(button)
            } else {
                if (!expanded) {
                    findNode(root) { it.viewIdResourceName == "android:id/expand_button" }
                        ?.let { expanded = tap(it) }
                }
                false
            }
        } } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val nodes = mutableListOf<String>()
            fun describe(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null || nodes.size >= 100 || depth > 15) return
                nodes += "${node.packageName} ${node.viewIdResourceName} text=${node.text} description=${node.contentDescription} clickable=${node.isClickable}"
                for (index in 0 until node.childCount) describe(node.getChild(index), depth + 1)
            }
            describe(automation.rootInActiveWindow)
            throw AssertionError("Notification shade action ${context.getString(label)} was not clicked:\n${nodes.joinToString("\n")}", error)
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(7_000) {
        while (!condition()) delay(50)
    }

    @Test fun newlyPostedLastWindowNotificationExplainsUnavailableSnoozeAndKeepsTaken() = runBlocking {
        val row = ringing(599_000)
        try {
            val notification = posted(row.groupId)
            assertEquals(context.getString(R.string.reminder_snooze_unavailable),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertFalse(notification.hasAction(R.string.reminder_action_snooze))
            assertTrue(notification.hasAction(R.string.reminder_action_taken))
            assertEquals(row.deadlineAtEpochMillis, dao.find(row.groupId)?.deadlineAtEpochMillis)
        } finally { ReminderCoordinator(context).cancel(row.groupId) }
    }

    @Test fun clickingOldSnoozeAcrossBoundaryShowsFeedbackAndOldTakenStillCommitsOnce() = runBlocking {
        val row = ringing(602_000)
        try {
            val original = posted(row.groupId)
            val snooze = original.action(R.string.reminder_action_snooze)
            val taken = original.action(R.string.reminder_action_taken)
            // Cross only the final ten-minute boundary (about2s), keeping the original deadline unchanged.
            await { System.currentTimeMillis() >= row.deadlineAtEpochMillis - 600_000 }
            clickInNotificationShade(R.string.reminder_action_snooze)
            await { posted(row.groupId).extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                context.getString(R.string.reminder_snooze_unavailable) }
            assertEquals(ReminderState.RINGING.name, dao.find(row.groupId)?.state)
            assertNull(dao.find(row.groupId)?.snoozedUntilEpochMillis)
            assertTrue(dao.pendingActions().none { it.groupId == row.groupId })
            assertFalse(posted(row.groupId).hasAction(R.string.reminder_action_snooze))
            assertTrue(posted(row.groupId).hasAction(R.string.reminder_action_taken))
            assertEquals(row.deadlineAtEpochMillis, dao.find(row.groupId)?.deadlineAtEpochMillis)
            await {
                findNode(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.isVisibleToUser && it.text?.toString()?.contains(context.getString(R.string.reminder_snooze_unavailable)) == true
                } != null
            }
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.filesDir, "runtime-notification-feedback.png").outputStream().use {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
            clickInNotificationShade(R.string.reminder_action_taken)
            await { dao.find(row.groupId)?.state == ReminderState.TAKEN.name &&
                manager.activeNotifications.none { ReminderNotifications.isForGroup(it.notification, row.groupId) } }
            click(taken) // The original captured Taken retains the same idempotent identity.
            click(snooze) // Late delivery must not resurrect a terminal prompt or action.
            assertEquals(ReminderState.TAKEN.name, dao.find(row.groupId)?.state)
            assertTrue(manager.activeNotifications.none { ReminderNotifications.isForGroup(it.notification, row.groupId) })
            assertTrue(dao.pendingActions().none { it.groupId == row.groupId && it.kind == ReminderActionKind.SNOOZE.name })
        } finally {
            ReminderCoordinator(context).cancel(row.groupId)
            shell("cmd statusbar collapse")
        }
    }

    @Test fun clickingValidNotificationSnoozeKeepsOriginalDeadlineAndDuplicateDoesNotRepost() = runBlocking {
        val row = ringing(1_200_000)
        try {
            val snooze = posted(row.groupId).action(R.string.reminder_action_snooze)
            click(snooze)
            await { dao.find(row.groupId)?.state == ReminderState.SNOOZED.name &&
                manager.activeNotifications.none { ReminderNotifications.isForGroup(it.notification, row.groupId) } }
            val until = dao.find(row.groupId)?.snoozedUntilEpochMillis
            assertNotNull(until)
            assertEquals(row.deadlineAtEpochMillis, dao.find(row.groupId)?.deadlineAtEpochMillis)
            click(snooze)
            assertEquals(until, dao.find(row.groupId)?.snoozedUntilEpochMillis)
            assertEquals(ReminderState.SNOOZED.name, dao.find(row.groupId)?.state)
            assertTrue(manager.activeNotifications.none { ReminderNotifications.isForGroup(it.notification, row.groupId) })
        } finally { ReminderCoordinator(context).cancel(row.groupId) }
    }
}
