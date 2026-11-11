package com.group33.medicationreminder.core.reminder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.group33.medicationreminder.Group33Application
import java.util.concurrent.TimeUnit

internal enum class ReminderDispatchResult { ACKNOWLEDGED, RETRY, PERMANENT_FAILURE }

internal fun interface ReminderPhaseDispatcher {
    suspend fun dispatch(event: ReminderAlarmEventRow): ReminderDispatchResult
}

internal class ReminderPhaseWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()
        val dao = ReminderDatabase.open(applicationContext).reminders()
        val event = dao.alarmEvent(eventId) ?: return Result.success()
        if (event.dispatchState in terminalStates) return Result.success()
        val dispatcher = (applicationContext as? Group33Application)?.reminderPhaseDispatcher
            ?: return Result.success() // Fact stays pending until a real authenticated host is installed.
        val now = System.currentTimeMillis()
        if (dao.claimAlarmDispatch(eventId, now, now - CLAIM_TIMEOUT_MILLIS) != 1) return Result.success()
        return when (runCatching { dispatcher.dispatch(checkNotNull(dao.alarmEvent(eventId))) }.getOrNull()) {
            ReminderDispatchResult.ACKNOWLEDGED -> {
                dao.finishAlarmDispatch(eventId, ReminderDispatchState.ACKNOWLEDGED.name)
                Result.success()
            }
            ReminderDispatchResult.PERMANENT_FAILURE -> {
                dao.finishAlarmDispatch(eventId, ReminderDispatchState.PERMANENT_FAILURE.name)
                Result.failure()
            }
            else -> {
                dao.releaseAlarmDispatch(eventId)
                Result.retry()
            }
        }
    }

    companion object {
        private const val KEY_EVENT_ID = "eventId"
        private const val CLAIM_TIMEOUT_MILLIS = 60_000L
        private val terminalStates = setOf(
            ReminderDispatchState.ACKNOWLEDGED.name,
            ReminderDispatchState.PERMANENT_FAILURE.name,
        )

        fun enqueue(context: Context, eventId: String) {
            val request = OneTimeWorkRequestBuilder<ReminderPhaseWorker>()
                .setInputData(Data.Builder().putString(KEY_EVENT_ID, eventId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag("reminder-phase")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder-phase-$eventId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        suspend fun enqueuePending(context: Context) {
            ReminderDatabase.open(context).reminders().pendingAlarmEvents().forEach { enqueue(context, it.eventId) }
        }
    }
}

internal fun interface ReminderActionDispatcher {
    suspend fun dispatch(
        action: ReminderActionRow,
        registration: ReminderGroupWithOccurrences,
    ): ReminderDispatchResult
}

internal class ReminderActionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val actionId = inputData.getString(KEY_ACTION_ID) ?: return Result.failure()
        val dao = ReminderDatabase.open(applicationContext).reminders()
        val action = dao.action(actionId) ?: return Result.success()
        if (action.dispatchState in terminalStates) return Result.success()
        val dispatcher = (applicationContext as? Group33Application)?.reminderActionDispatcher
            ?: return Result.success()
        val registration = dao.registration(action.groupId) ?: return Result.failure()
        val now = System.currentTimeMillis()
        if (dao.claimActionDispatch(actionId, now, now - CLAIM_TIMEOUT_MILLIS) != 1) return Result.success()
        val claimed = checkNotNull(dao.action(actionId))
        return when (runCatching { dispatcher.dispatch(claimed, registration) }.getOrNull()) {
            ReminderDispatchResult.ACKNOWLEDGED -> {
                dao.finishActionDispatch(actionId, ReminderDispatchState.ACKNOWLEDGED.name)
                Result.success()
            }
            ReminderDispatchResult.PERMANENT_FAILURE -> {
                dao.finishActionDispatch(actionId, ReminderDispatchState.PERMANENT_FAILURE.name)
                Result.failure()
            }
            else -> {
                dao.releaseActionDispatch(actionId)
                Result.retry()
            }
        }
    }

    companion object {
        private const val KEY_ACTION_ID = "actionId"
        private const val CLAIM_TIMEOUT_MILLIS = 60_000L
        private val terminalStates = setOf(
            ReminderDispatchState.ACKNOWLEDGED.name,
            ReminderDispatchState.PERMANENT_FAILURE.name,
        )

        fun enqueue(context: Context, actionId: String) {
            val request = OneTimeWorkRequestBuilder<ReminderActionWorker>()
                .setInputData(Data.Builder().putString(KEY_ACTION_ID, actionId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag("reminder-action")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder-action-$actionId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        suspend fun enqueuePending(context: Context) {
            ReminderDatabase.open(context).reminders().pendingActions().forEach { enqueue(context, it.actionId) }
        }
    }
}

