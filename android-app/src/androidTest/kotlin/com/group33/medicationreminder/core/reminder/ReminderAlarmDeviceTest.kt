package com.group33.medicationreminder.core.reminder

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderAlarmDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun prepare() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 31) shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        notifications.cancelAll()
        ReminderDatabase.open(context).clearAllTables()
        ReminderNotifications.ensureChannel(context)
    }

    @Test fun exactAlarmPostsAudibleBackgroundNotificationAndTakenEndsIt() = runBlocking {
        val now = System.currentTimeMillis()
        val group = testGroup("device-${UUID.randomUUID()}", now + 5_000L, medicineCount = 2)
        val id = group.groupId
        val result = ReminderCoordinator(context).register(group)
        assertEquals(ReminderRegistrationStatus.SCHEDULED, result.status)
        assertTrue(result.capability.notificationsUsable)
        assertEquals(ScheduleAccuracy.EXACT, result.accuracy)

        await(20_000L) {
            ReminderDatabase.open(context).reminders().find(id)?.state == ReminderState.RINGING.name &&
                notifications.activeNotifications.any { it.tag == id }
        }
        val active = checkNotNull(notifications.activeNotifications.firstOrNull { it.tag == id })
        val channel = if (Build.VERSION.SDK_INT >= 26) notifications.getNotificationChannel(ReminderNotifications.CHANNEL_ID) else null
        if (channel != null) {
            assertNotNull(channel.sound)
            assertEquals(android.media.AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
            assertTrue(channel.importance >= NotificationManager.IMPORTANCE_HIGH)
        } else {
            assertNotNull(active.notification.sound)
        }

        assertTrue(ReminderCoordinator(context).recordAction(
            id, "device-taken-${UUID.randomUUID()}", ReminderActionKind.TAKEN,
        ))
        await(5_000L) { notifications.activeNotifications.none { it.tag == id } }
        ReminderCoordinator(context).recover()
        delay(1_000L)
        assertEquals(ReminderState.TAKEN.name, ReminderDatabase.open(context).reminders().find(id)?.state)
        assertTrue(notifications.activeNotifications.none { it.tag == id })
    }

    @Test fun recoveryShowsAtMostOncePerBootAndExpiredNeverReturns() = runBlocking {
        val now = System.currentTimeMillis()
        val planId = "recovery-${UUID.randomUUID()}"
        val registration = testRegistration(planId, now - 1_000L, now + 60_000L,
            state = ReminderState.RINGING, lastNotifiedBootCount = -1)
        val id = registration.group.groupId
        val dao = ReminderDatabase.open(context).reminders()
        dao.insertTestRegistration(registration)
        val coordinator = ReminderCoordinator(context)
        coordinator.recover()
        await(5_000L) { notifications.activeNotifications.any { it.tag == id } }
        val afterFirst = checkNotNull(dao.find(id))
        coordinator.recover()
        delay(500L)
        assertEquals(afterFirst.recoveryNotificationCount, dao.find(id)?.recoveryNotificationCount)
        assertTrue(coordinator.cancel(id))
        coordinator.recover()
        delay(500L)
        assertEquals(ReminderState.EXPIRED.name, dao.find(id)?.state)
        assertTrue(notifications.activeNotifications.none { it.tag == id })
    }

    @Test fun snoozeCreatesASecondAudibleGroupReminderWithoutMovingTheDeadline() = runBlocking {
        val now = System.currentTimeMillis()
        val group = testGroup("snooze-${UUID.randomUUID()}", now - 1_000L, medicineCount = 2)
        val coordinator = ReminderCoordinator(context)
        coordinator.register(group)
        await(5_000L) { notifications.activeNotifications.any { it.tag == group.groupId } }
        val firstDelivery = checkNotNull(ReminderDatabase.open(context).reminders().find(group.groupId))
        val originalDeadline = firstDelivery.deadlineAtEpochMillis
        val boot = checkNotNull(firstDelivery.lastNotifiedBootCount)
        assertTrue(coordinator.recordAction(
            group.groupId, "snooze-${UUID.randomUUID()}", ReminderActionKind.SNOOZE, now,
        ))
        await(5_000L) { notifications.activeNotifications.none { it.tag == group.groupId } }
        assertTrue(coordinator.triggerDue(
            group.groupId, now + InitialTiming.snooze.toMillis(), boot = boot,
        ))
        await(5_000L) { notifications.activeNotifications.any { it.tag == group.groupId } }
        assertEquals(originalDeadline,
            ReminderDatabase.open(context).reminders().find(group.groupId)?.deadlineAtEpochMillis)
        assertTrue(coordinator.recordAction(
            group.groupId, "taken-${UUID.randomUUID()}", ReminderActionKind.TAKEN,
            now + InitialTiming.snooze.toMillis(),
        ))
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
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

