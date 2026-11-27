package com.group33.medicationreminder.core.reminder

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.group33.medicationreminder.Group33Application
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Opt-in test application: real production bootstrap/Workers, injected loopback transport only. */
class AutomaticRecoveryRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(AutomaticRecoveryApplication::class.java.classLoader,
            AutomaticRecoveryApplication::class.java.name, context)
}

class AutomaticRecoveryApplication : Group33Application() {
    internal override val reminderDispatchClaimTimeoutMillis: Long get() = 5_000L

    internal override val reminderActionDispatcher: ReminderActionDispatcher?
        get() {
            val endpoint = getSharedPreferences("automatic-recovery", Context.MODE_PRIVATE)
                .getString("endpoint", null) ?: return null
            check(endpoint.startsWith("http://10.0.2.2:"))
            return ReminderActionDispatcher { action, registration ->
                val payload = JSONObject().put("actionId", action.actionId).put("groupId", action.groupId)
                    .put("kind", action.kind).put("recordedAt", action.recordedAtEpochMillis)
                    .put("deadlineAt", registration.group.deadlineAtEpochMillis)
                    .put("members", JSONArray(registration.occurrences.map { it.occurrenceId }.sorted())).toString()
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.connectTimeout = 5_000; connection.readTimeout = 120_000
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    check(connection.responseCode == 200)
                    check(connection.inputStream.bufferedReader().use { it.readText() } == "accepted")
                    ReminderDispatchResult.ACKNOWLEDGED
                } finally { connection.disconnect() }
            }
        }
}

/** No manual enqueue, claim, dispatch helper or injected clock is used after the process restarts. */
class AutomaticRecoveryProbeTest {
    @Test fun realStartupAndWorkManagerRecovery() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val expected = File(context.noBackupFilesDir, "automatic-recovery.json")
        val dao = ReminderDatabase.open(context).reminders()
        val work = WorkManager.getInstance(context)
        when (args.getString("phase")) {
            "prepare" -> {
                val endpoint = checkNotNull(args.getString("s2RecoveryUrl"))
                check(endpoint.startsWith("http://10.0.2.2:")); check(!expected.exists())
                check(context.getSharedPreferences("automatic-recovery", Context.MODE_PRIVATE)
                    .edit().putString("endpoint", endpoint).commit())
                val now = System.currentTimeMillis()
                val registration = testRegistration("automatic-${UUID.randomUUID()}", now - 1_000,
                    state = ReminderState.RINGING, medicineCount = 2)
                dao.insertTestRegistration(registration)
                val id = UUID.randomUUID().toString()
                expected.writeText(JSONObject().put("actionId", id).put("groupId", registration.group.groupId)
                    .put("recordedAt", now).put("deadlineAt", registration.group.deadlineAtEpochMillis)
                    .put("pid", Process.myPid()).toString())
                // The actual local-action transaction schedules the actual Worker.
                assertTrue(ReminderCoordinator(context).recordAction(registration.group.groupId, id, ReminderActionKind.TAKEN, now))
                withTimeout(10_000) {
                    while (true) {
                        val infos = work.getWorkInfosForUniqueWork("reminder-action-$id").get(10, TimeUnit.SECONDS)
                        if (infos.isNotEmpty() && dao.action(id)?.dispatchState == ReminderDispatchState.DISPATCHING.name) {
                            assertEquals(1, infos.size)
                            expected.writeText(JSONObject(expected.readText()).put("workId", infos.single().id)
                                .put("claimedRunAttemptCount", infos.single().runAttemptCount).toString())
                            break
                        }
                        delay(25)
                    }
                }
                // Host kills this process only after the receiver has committed the first upload.
                withTimeout(30_000) { while (true) delay(100) }
            }
            "verify" -> {
                val before = JSONObject(expected.readText()); val id = before.getString("actionId")
                val claimTimeoutMillis = (context.applicationContext as AutomaticRecoveryApplication)
                    .reminderDispatchClaimTimeoutMillis
                assertNotEquals(before.getInt("pid"), Process.myPid())
                val initial = checkNotNull(dao.action(id)); val claimedAt = checkNotNull(initial.lastDispatchAtEpochMillis)
                assertEquals(ReminderDispatchState.DISPATCHING.name, initial.dispatchState)
                assertTrue(System.currentTimeMillis() - claimedAt < claimTimeoutMillis * 4)
                var observedRetry = false
                val originalWorkId = UUID.fromString(before.getString("workId"))
                val originalRunCount = before.getInt("claimedRunAttemptCount")
                val observations = JSONArray()
                var lastObservation = ""
                withTimeout(60_000) {
                    while (true) {
                        val infos = work.getWorkInfosForUniqueWork("reminder-action-$id").get(10, TimeUnit.SECONDS)
                        if (infos.isNotEmpty()) {
                            assertEquals(1, infos.size)
                            val info = infos.single()
                            assertEquals(originalWorkId, info.id)
                            val actionState = dao.action(id)?.dispatchState
                            val observation = "${info.state}:${info.runAttemptCount}:$actionState"
                            if (observation != lastObservation) {
                                observations.put(JSONObject().put("state", info.state.name)
                                    .put("runAttemptCount", info.runAttemptCount).put("actionState", actionState))
                                lastObservation = observation
                            }
                            if (info.runAttemptCount > originalRunCount && info.state == WorkInfo.State.ENQUEUED &&
                                actionState == ReminderDispatchState.DISPATCHING.name)
                                observedRetry = true
                            if (dao.action(id)?.dispatchState == ReminderDispatchState.ACKNOWLEDGED.name && info.state == WorkInfo.State.SUCCEEDED) break
                        }
                        delay(100)
                    }
                }
                val accepted = checkNotNull(dao.action(id)); val group = checkNotNull(dao.find(accepted.groupId))
                assertTrue("Actual busy-claim retry must be observed", observedRetry)
                assertTrue(checkNotNull(accepted.lastDispatchAtEpochMillis) - claimedAt > claimTimeoutMillis)
                assertEquals(2, accepted.dispatchAttempts)
                assertEquals(before.getString("groupId"), accepted.groupId)
                assertEquals(before.getLong("recordedAt"), accepted.recordedAtEpochMillis)
                assertEquals(before.getLong("deadlineAt"), group.deadlineAtEpochMillis)
                assertEquals(ReminderState.TAKEN.name, group.state)
                File(context.filesDir, "automatic-recovery-result.json").writeText(JSONObject()
                    .put("actionId", id).put("oldPid", before.getInt("pid")).put("newPid", Process.myPid())
                    .put("workId", originalWorkId).put("observedRetry", observedRetry)
                    .put("originalRunAttemptCount", originalRunCount).put("workObservations", observations)
                    .put("elapsedClaimMillis", checkNotNull(accepted.lastDispatchAtEpochMillis) - claimedAt)
                    .put("claimTimeoutMillis", claimTimeoutMillis)
                    .put("attempts", accepted.dispatchAttempts).put("state", accepted.dispatchState)
                    .put("originalDeadlinePreserved", true).put("manualRecoveryCalls", 0).toString())
            }
            else -> error("Explicit prepare/verify phase required")
        }
    }
}
