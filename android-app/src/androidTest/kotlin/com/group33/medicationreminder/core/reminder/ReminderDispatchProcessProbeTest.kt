package com.group33.medicationreminder.core.reminder

import android.os.Process
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Two real app processes; host kills the first after its injected HTTP receiver commits. */
class ReminderDispatchProcessProbeTest {
    @Test fun probe() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val endpoint = checkNotNull(args.getString("s2RecoveryUrl"))
        check(endpoint.startsWith("http://10.0.2.2:"))
        val expected = File(context.noBackupFilesDir, "dispatch-recovery-probe.json")
        val db = Room.databaseBuilder(context, ReminderDatabase::class.java, "dispatch-recovery-probe.db").build()
        val dao = db.reminders()
        val dispatcher = ReminderActionDispatcher { action, registration ->
            val payload = JSONObject().put("actionId", action.actionId).put("groupId", action.groupId)
                .put("kind", action.kind).put("recordedAt", action.recordedAtEpochMillis)
                .put("plannedAt", registration.group.plannedAtEpochMillis)
                .put("deadlineAt", registration.group.deadlineAtEpochMillis)
                .put("members", JSONArray(registration.occurrences.map { it.occurrenceId }.sorted())).toString()
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.connectTimeout = 5_000; connection.readTimeout = 30_000
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                check(connection.responseCode == 200)
                check(connection.inputStream.bufferedReader().use { it.readText() } == "accepted")
                ReminderDispatchResult.ACKNOWLEDGED
            } finally { connection.disconnect() }
        }
        try {
            when (args.getString("phase")) {
                "prepare" -> {
                    check(!expected.exists())
                    val now = System.currentTimeMillis()
                    val registration = testRegistration("process-${UUID.randomUUID()}", now - 1_000,
                        state = ReminderState.RINGING, medicineCount = 2)
                    dao.insertTestRegistration(registration)
                    val action = ReminderActionRow(UUID.randomUUID().toString(), registration.group.groupId,
                        ReminderActionKind.TAKEN.name, now)
                    assertEquals(LocalActionInsertResult.INSERTED, dao.recordLocalAction(action, null))
                    expected.writeText(JSONObject().put("actionId", action.actionId).put("groupId", action.groupId)
                        .put("recordedAt", now).put("deadlineAt", registration.group.deadlineAtEpochMillis)
                        .put("pid", Process.myPid()).toString())
                    // The host kills this process while the first HTTP response is deliberately held.
                    dispatchReminderAction(dao, action.actionId, dispatcher, now)
                    fail("Host must kill the uploading process before its response is delivered")
                }
                "verify" -> {
                    val before = JSONObject(expected.readText())
                    assertNotEquals(before.getInt("pid"), Process.myPid())
                    val id = before.getString("actionId")
                    val persisted = checkNotNull(dao.action(id))
                    assertEquals(ReminderDispatchState.DISPATCHING.name, persisted.dispatchState)
                    assertEquals(listOf(id), dao.pendingActions().map { it.actionId })
                    val claimedAt = checkNotNull(persisted.lastDispatchAtEpochMillis)
                    assertEquals(Result.retry().javaClass, dispatchReminderAction(dao, id, dispatcher, claimedAt + 1).javaClass)
                    // Inject the lease boundary; no real-minute wait or deadline reset is needed.
                    assertEquals(Result.success().javaClass, dispatchReminderAction(dao, id, dispatcher, claimedAt + 60_001).javaClass)
                    val accepted = checkNotNull(dao.action(id))
                    assertEquals(ReminderDispatchState.ACKNOWLEDGED.name, accepted.dispatchState)
                    assertEquals(before.getString("groupId"), accepted.groupId)
                    assertEquals(before.getLong("recordedAt"), accepted.recordedAtEpochMillis)
                    assertEquals(2, accepted.dispatchAttempts)
                    val group = checkNotNull(dao.find(accepted.groupId))
                    assertEquals(ReminderState.TAKEN.name, group.state)
                    assertEquals(before.getLong("deadlineAt"), group.deadlineAtEpochMillis)
                    dispatchReminderAction(dao, id, dispatcher, claimedAt + 120_001)
                    assertTrue(dao.pendingActions().isEmpty())
                    File(context.filesDir, "dispatch-recovery-probe-result.json").writeText(JSONObject()
                        .put("actionId", id).put("oldPid", before.getInt("pid")).put("newPid", Process.myPid())
                        .put("attempts", accepted.dispatchAttempts).put("state", accepted.dispatchState)
                        .put("originalDeadlinePreserved", true).toString())
                }
                else -> error("Explicit prepare/verify phase required")
            }
        } finally { db.close() }
    }
}
