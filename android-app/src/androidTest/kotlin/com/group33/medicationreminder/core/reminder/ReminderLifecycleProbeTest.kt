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
            "prepare-process" -> prepare(15_000L)
            "prepare-reboot" -> prepare(90_000L)
            "verify" -> verify()
            else -> fail("Pass phase=prepare-process, prepare-reboot, or verify")
        }
    }

    private suspend fun prepare(delayMillis: Long) {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 31) shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        context.getSystemService(NotificationManager::class.java).cancelAll()
        ReminderDatabase.open(context).clearAllTables()
        val now = System.currentTimeMillis()
        val planId = "lifecycle-${UUID.randomUUID()}"
        val planned = now + delayMillis
        val group = testGroup(planId, planned, medicineCount = 2)
        val id = group.groupId
        val deadline = group.deadlineAtEpochMillis
        val result = ReminderCoordinator(context).register(group)
        assertEquals(ReminderRegistrationStatus.SCHEDULED, result.status)
        assertEquals(ScheduleAccuracy.EXACT, result.accuracy)
        record.parentFile?.mkdirs()
        record.writeText("$id\n$planned\n$deadline\n")
    }

    private suspend fun verify() {
        val values = record.readLines()
        val id = values[0]
        val planned = values[1].toLong()
        val deadline = values[2].toLong()
        val waitUntil = minOf(deadline, System.currentTimeMillis() + 120_000L)
        while (System.currentTimeMillis() < waitUntil) {
            val row = ReminderDatabase.open(context).reminders().find(id)
            val visible = context.getSystemService(NotificationManager::class.java)
                .activeNotifications.any { it.tag == id }
            if (row?.state == ReminderState.RINGING.name && visible) {
                assertTrue(row.lastAlarmAtEpochMillis != null && row.lastAlarmAtEpochMillis >= planned)
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

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}

