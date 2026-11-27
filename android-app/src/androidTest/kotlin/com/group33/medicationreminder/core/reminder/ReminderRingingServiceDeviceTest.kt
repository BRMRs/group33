package com.group33.medicationreminder.core.reminder

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRingingServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val dao get() = ReminderDatabase.open(context).reminders()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Before fun prepare() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        notifications.cancelAll()
        context.stopService(Intent(context, ReminderRingingService::class.java))
        delay(250L)
        ReminderDatabase.open(context).clearAllTables()
    }

    @After fun cleanup() = runBlocking {
        dao.ringing(System.currentTimeMillis()).forEach { ReminderCoordinator(context).cancel(it.groupId) }
        context.stopService(Intent(context, ReminderRingingService::class.java))
        await { !ReminderRingingService.isPlayingForTest() }
    }

    @Test fun foregroundServiceIsSingleAlarmUsageLoopAndEveryTerminalTransitionStopsIt() = runBlocking {
        val coordinator = ReminderCoordinator(context)

        suspend fun ringing(label: String): ReminderGroupRow {
            val now = System.currentTimeMillis()
            val registration = testRegistration(
                "$label-${UUID.randomUUID()}", now - 1_000L, now + 1_200_000L,
                state = ReminderState.RINGING,
            )
            dao.insertTestRegistration(registration)
            assertTrue(ReminderRingingService.start(context, registration.group.groupId))
            await {
                ReminderRingingService.isPlayingForTest() &&
                    ReminderRingingService.activeGroupForTest() == registration.group.groupId
            }
            assertEquals(registration.group.groupId, ReminderRingingService.activeGroupForTest())
            assertEquals(1, notifications.activeNotifications.count {
                ReminderNotifications.isForGroup(it.notification, registration.group.groupId)
            })
            if (Build.VERSION.SDK_INT >= 26) {
                val alarmPlayers = context.getSystemService(AudioManager::class.java)
                    .activePlaybackConfigurations.count {
                        it.audioAttributes.usage == AudioAttributes.USAGE_ALARM
                    }
                assertEquals(1, alarmPlayers)
            }
            // Re-delivery updates the same foreground notification and never creates a second player.
            assertTrue(ReminderRingingService.start(context, registration.group.groupId))
            delay(250L)
            assertEquals(1, notifications.activeNotifications.count {
                ReminderNotifications.isForGroup(it.notification, registration.group.groupId)
            })
            return registration.group
        }

        val taken = ringing("taken")
        assertTrue(coordinator.recordAction(taken.groupId, UUID.randomUUID().toString(), ReminderActionKind.TAKEN))
        awaitStopped(taken.groupId)

        val help = ringing("help")
        assertTrue(coordinator.recordAction(help.groupId, UUID.randomUUID().toString(), ReminderActionKind.HELP))
        awaitStopped(help.groupId)

        val snooze = ringing("snooze")
        assertTrue(coordinator.recordAction(snooze.groupId, UUID.randomUUID().toString(), ReminderActionKind.SNOOZE))
        awaitStopped(snooze.groupId)

        val takeover = ringing("takeover")
        assertTrue(coordinator.caregiverTakeover(takeover.groupId))
        awaitStopped(takeover.groupId)

        val expiryNow = System.currentTimeMillis()
        val expiring = testRegistration(
            "expired-${UUID.randomUUID()}", expiryNow - 1_000L, expiryNow + 1_500L,
            state = ReminderState.RINGING,
        )
        dao.insertTestRegistration(expiring)
        assertTrue(ReminderRingingService.start(context, expiring.group.groupId))
        await { ReminderRingingService.activeGroupForTest() == expiring.group.groupId }
        awaitStopped(expiring.group.groupId)
        assertEquals(ReminderState.EXPIRED.name, dao.find(expiring.group.groupId)?.state)
    }

    @Test fun stickyRecoverySelectsOnlyDurableRingingRows() = runBlocking {
        val now = System.currentTimeMillis()
        val staleScheduled = testRegistration(
            "scheduled-${UUID.randomUUID()}", now - 2_000L, now + 120_000L,
            state = ReminderState.SCHEDULED,
        )
        dao.insertTestRegistration(staleScheduled)
        assertTrue(ReminderRingingService.start(context, staleScheduled.group.groupId))
        delay(750L)
        assertFalse(ReminderRingingService.isPlayingForTest())
        assertEquals(ReminderState.SCHEDULED.name, dao.find(staleScheduled.group.groupId)?.state)

        val ringing = testRegistration(
            "ringing-${UUID.randomUUID()}", now - 1_000L, now + 120_000L,
            state = ReminderState.RINGING,
        )
        dao.insertTestRegistration(ringing)
        assertTrue(ReminderRingingService.start(context, staleScheduled.group.groupId))
        await {
            ReminderRingingService.activeGroupForTest() == ringing.group.groupId &&
                ReminderRingingService.isPlayingForTest()
        }
        assertEquals(ReminderState.SCHEDULED.name, dao.find(staleScheduled.group.groupId)?.state)
        assertTrue(ReminderCoordinator(context).cancel(ringing.group.groupId))
    }

    private suspend fun awaitStopped(groupId: String) {
        await {
            !ReminderRingingService.isPlayingForTest() &&
                notifications.activeNotifications.none {
                    ReminderNotifications.isForGroup(it.notification, groupId)
                }
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(7_000L) {
        while (!condition()) delay(50L)
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}
