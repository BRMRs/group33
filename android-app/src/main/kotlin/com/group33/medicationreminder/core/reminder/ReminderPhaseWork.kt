package com.group33.medicationreminder.core.reminder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.group33.medicationreminder.Group33Application
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

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
        return dispatchReminderPhase(ReminderDatabase.open(applicationContext).reminders(), eventId,
            (applicationContext as? Group33Application)?.reminderPhaseDispatcher,
            applicationContext.reminderClock().nowEpochMillis(),
            (applicationContext as? Group33Application)?.reminderDispatchClaimTimeoutMillis
                ?: DISPATCH_CLAIM_TIMEOUT_MILLIS)
    }

    companion object {
        private const val KEY_EVENT_ID = "eventId"

        fun enqueue(context: Context, eventId: String) {
            val request = OneTimeWorkRequestBuilder<ReminderPhaseWorker>()
                .setInputData(Data.Builder().putString(KEY_EVENT_ID, eventId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag("reminder-phase")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder-phase-$eventId",
                ExistingWorkPolicy.KEEP,
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
        return dispatchReminderAction(ReminderDatabase.open(applicationContext).reminders(), actionId,
            (applicationContext as? Group33Application)?.reminderActionDispatcher,
            applicationContext.reminderClock().nowEpochMillis(),
            (applicationContext as? Group33Application)?.reminderDispatchClaimTimeoutMillis
                ?: DISPATCH_CLAIM_TIMEOUT_MILLIS)
    }

    companion object {
        private const val KEY_ACTION_ID = "actionId"

        fun enqueue(context: Context, actionId: String) {
            val request = OneTimeWorkRequestBuilder<ReminderActionWorker>()
                .setInputData(Data.Builder().putString(KEY_ACTION_ID, actionId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag("reminder-action")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder-action-$actionId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        suspend fun enqueuePending(context: Context) {
            ReminderDatabase.open(context).reminders().pendingActions().forEach { enqueue(context, it.actionId) }
        }
    }
}

private const val DISPATCH_CLAIM_TIMEOUT_MILLIS = 60_000L
private val terminalDispatchStates = setOf(
    ReminderDispatchState.ACKNOWLEDGED.name, ReminderDispatchState.PERMANENT_FAILURE.name,
)

/** The WorkManager path itself; a live claim must retain retry work after process restoration. */
internal suspend fun dispatchReminderAction(
    dao: ReminderDao,
    actionId: String,
    dispatcher: ReminderActionDispatcher?,
    now: Long,
    claimTimeoutMillis: Long = DISPATCH_CLAIM_TIMEOUT_MILLIS,
): Result {
    require(claimTimeoutMillis > 0L)
    val action = dao.action(actionId) ?: return Result.success()
    if (action.dispatchState in terminalDispatchStates || dispatcher == null) return Result.success()
    val registration = dao.registration(action.groupId) ?: return Result.failure()
    if (dao.claimActionDispatch(actionId, now, now - claimTimeoutMillis) != 1) return Result.retry()
    val reply = try { dispatcher.dispatch(checkNotNull(dao.action(actionId)), registration) }
    catch (cancelled: CancellationException) { throw cancelled } // The durable claim remains recoverable.
    catch (_: Exception) { null }
    return when (reply) {
        ReminderDispatchResult.ACKNOWLEDGED -> {
            dao.finishActionDispatch(actionId, ReminderDispatchState.ACKNOWLEDGED.name)
            Result.success()
        }
        ReminderDispatchResult.PERMANENT_FAILURE -> {
            dao.finishActionDispatch(actionId, ReminderDispatchState.PERMANENT_FAILURE.name)
            Result.failure()
        }
        else -> { dao.releaseActionDispatch(actionId); Result.retry() }
    }
}

internal suspend fun dispatchReminderPhase(
    dao: ReminderDao,
    eventId: String,
    dispatcher: ReminderPhaseDispatcher?,
    now: Long,
    claimTimeoutMillis: Long = DISPATCH_CLAIM_TIMEOUT_MILLIS,
): Result {
    require(claimTimeoutMillis > 0L)
    val event = dao.alarmEvent(eventId) ?: return Result.success()
    if (event.dispatchState in terminalDispatchStates || dispatcher == null) return Result.success()
    if (dao.claimAlarmDispatch(eventId, now, now - claimTimeoutMillis) != 1) return Result.retry()
    val reply = try { dispatcher.dispatch(checkNotNull(dao.alarmEvent(eventId))) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    return when (reply) {
        ReminderDispatchResult.ACKNOWLEDGED -> {
            dao.finishAlarmDispatch(eventId, ReminderDispatchState.ACKNOWLEDGED.name)
            Result.success()
        }
        ReminderDispatchResult.PERMANENT_FAILURE -> {
            dao.finishAlarmDispatch(eventId, ReminderDispatchState.PERMANENT_FAILURE.name)
            Result.failure()
        }
        else -> { dao.releaseAlarmDispatch(eventId); Result.retry() }
    }
}
