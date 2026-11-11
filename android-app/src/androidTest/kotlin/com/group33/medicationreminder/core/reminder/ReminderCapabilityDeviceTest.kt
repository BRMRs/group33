package com.group33.medicationreminder.core.reminder

import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.shared.contract.DeliveryCondition
import com.group33.shared.contract.ExactAlarmAccess
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Permission changes kill the target process on current Android versions.
 * These phases are intentionally driven by the host acceptance script. */
@RunWith(AndroidJUnit4::class)
class ReminderCapabilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val phase get() = InstrumentationRegistry.getArguments().getString("capabilityPhase")

    @Test fun notificationDeniedLeavesTheSameOccurrenceRecoverable() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33 && phase == "notification-denied")
        notifications.cancelAll()
        ReminderDatabase.open(context).clearAllTables()
        val denied = ReminderCapability.snapshot(context)
        assertFalse(denied.notificationPermissionGranted)
        assertEquals(DeliveryCondition.UNAVAILABLE, denied.deliveryCondition)

        val group = testGroup("permission-${UUID.randomUUID()}", System.currentTimeMillis() - 1_000L)
        val result = ReminderCoordinator(context).register(group)
        assertEquals(ReminderRegistrationStatus.SCHEDULED, result.status)
        assertEquals(ReminderState.RINGING.name,
            ReminderDatabase.open(context).reminders().find(group.groupId)?.state)
        assertTrue(notifications.activeNotifications.none { it.tag == group.groupId })
    }

    @Test fun notificationGrantRecoversTheSameOccurrence() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33 && phase == "notification-granted")
        assertTrue(ReminderCapability.snapshot(context).notificationPermissionGranted)
        val row = ReminderDatabase.open(context).reminders().active().single()
        ReminderCoordinator(context).recover()
        await(5_000L) { notifications.activeNotifications.any { it.tag == row.groupId } }
        assertTrue(ReminderCoordinator(context).recordAction(
            row.groupId, "permission-taken-${UUID.randomUUID()}", ReminderActionKind.TAKEN,
        ))
    }

    @Test fun exactAlarmDenialIsDegradedAndUsesInexactFallback() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 31 && phase == "exact-denied")
        ReminderDatabase.open(context).clearAllTables()
        val capability = ReminderCapability.snapshot(context)
        assertEquals(ExactAlarmAccess.DENIED, capability.exactAlarmAccess)
        assertEquals(DeliveryCondition.DEGRADED, capability.deliveryCondition)
        val group = testGroup("degraded-${UUID.randomUUID()}", System.currentTimeMillis() + 60_000L)
        val result = ReminderCoordinator(context).register(group)
        assertEquals(ScheduleAccuracy.INEXACT, result.accuracy)
        assertTrue(shell("dumpsys alarm").contains(context.packageName))
        assertTrue(ReminderCoordinator(context).cancel(group.groupId))
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
    }

    private suspend fun await(timeoutMillis: Long, condition: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            delay(200L)
        }
        fail("Condition did not become true within ${timeoutMillis}ms")
    }
}
