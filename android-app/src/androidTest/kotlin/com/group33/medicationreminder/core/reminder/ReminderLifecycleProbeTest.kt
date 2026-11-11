package com.group33.medicationreminder.core.reminder

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderLifecycleProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val record = File(context.noBackupFilesDir, "reminder-lifecycle-probe.txt")

    @Test fun probe() = runBlocking {
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "prepare-process" -> prepare(15_000L, false)
            "prepare-reboot" -> prepare(90_000L, false)
            "prepare-reboot-snoozed" -> prepare(0L, true)
            "verify" -> verify()
            else -> fail("Pass phase=prepare-process, prepare-reboot, prepare-reboot-snoozed, or verify")
        }
    }

    private suspend fun prepare(delayMillis: Long, snoozeBeforeReboot: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 31) shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        context.getSystemService(NotificationManager::class.java).cancelAll()
        ReminderDatabase.open(context).clearAllTables()
        val now = System.currentTimeMillis()
        val planId = "lifecycle-${UUID.randomUUID()}"
        val planned = if (snoozeBeforeReboot) now - 1_000L else now + delayMillis
        val group = testGroup(planId, planned, medicineCount = 2)
        val id = group.groupId
        val deadline = group.deadlineAtEpochMillis
        val result = ReminderCoordinator(context).register(group)
        assertEquals(ReminderRegistrationStatus.SCHEDULED, result.status)
        assertEquals(ScheduleAccuracy.EXACT, result.accuracy)
        val resumeAt = if (snoozeBeforeReboot) {
            await(10_000L) {
                ReminderDatabase.open(context).reminders().find(id)?.state == ReminderState.RINGING.name &&
                    context.getSystemService(NotificationManager::class.java)
                        .activeNotifications.any { it.tag == id }
            }
            assertTrue(ReminderCoordinator(context).recordAction(
                id, "lifecycle-snooze-${UUID.randomUUID()}", ReminderActionKind.SNOOZE,
            ))
            checkNotNull(ReminderDatabase.open(context).reminders().find(id)?.snoozedUntilEpochMillis)
        } else planned

        var takenId = "-"
        var expiredId = "-"
        if (delayMillis >= 90_000L || snoozeBeforeReboot) {
            val taken = testGroup("lifecycle-taken-${UUID.randomUUID()}", now - 1_000L)
            ReminderCoordinator(context).register(taken)
            assertTrue(ReminderCoordinator(context).recordAction(
                taken.groupId, "lifecycle-terminal-${UUID.randomUUID()}", ReminderActionKind.TAKEN,
            ))
            takenId = taken.groupId

            val expired = testGroup(
                "lifecycle-expired-${UUID.randomUUID()}",
                now - InitialTiming.responseWindow.toMillis() - 1_000L,
            )
            assertEquals(ReminderRegistrationStatus.EXPIRED, ReminderCoordinator(context).register(expired).status)
            expiredId = expired.groupId
        }
        record.parentFile?.mkdirs()
        record.writeText(listOf(
            if (snoozeBeforeReboot) "snoozed" else "scheduled",
            id, planned.toString(), deadline.toString(), resumeAt.toString(), takenId, expiredId,
        ).joinToString("\n", postfix = "\n"))
    }

    private suspend fun verify() {
        val values = record.readLines()
        assertEquals(7, values.size)
        val mode = values[0]
        val id = values[1]
        val planned = values[2].toLong()
        val deadline = values[3].toLong()
        val resumeAt = values[4].toLong()
        val takenId = values[5]
        val expiredId = values[6]
        val waitUntil = minOf(deadline, System.currentTimeMillis() + 120_000L)
        while (System.currentTimeMillis() < waitUntil) {
            val row = ReminderDatabase.open(context).reminders().find(id)
            val visible = context.getSystemService(NotificationManager::class.java)
                .activeNotifications.any { it.tag == id }
            if (row?.state == ReminderState.RINGING.name && visible) {
                assertTrue(row.lastAlarmAtEpochMillis != null && row.lastAlarmAtEpochMillis >= resumeAt)
                assertEquals(deadline, row.deadlineAtEpochMillis)
                if (mode == "snoozed") assertTrue(resumeAt > planned)
                assertTerminalDidNotReplay(takenId, ReminderState.TAKEN)
                assertTerminalDidNotReplay(expiredId, ReminderState.EXPIRED)
                assertTrue(ReminderCoordinator(context).recordAction(
                    id, "lifecycle-taken-${UUID.randomUUID()}", ReminderActionKind.TAKEN,
                ))
                record.delete()
                return
            }
            delay(500L)
        }
        val row = ReminderDatabase.open(context).reminders().find(id)
        fail("Reminder did not recover: id=$id state=${row?.state} now=${System.currentTimeMillis()} deadline=$deadline")
    }

    private suspend fun assertTerminalDidNotReplay(id: String, expected: ReminderState) {
        if (id == "-") return
        assertEquals(expected.name, ReminderDatabase.open(context).reminders().find(id)?.state)
        assertTrue(context.getSystemService(NotificationManager::class.java)
            .activeNotifications.none { it.tag == id })
    }

    private suspend fun await(timeoutMillis: Long, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(200L)
        }
        fail("Condition did not become true within ${timeoutMillis}ms")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}

