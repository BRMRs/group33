package com.group33.medicationreminder.core.reminder

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real Room regressions for facts left claimed when a process exits during dispatch. */
class ReminderDispatchRecoveryTest {
    private lateinit var db: ReminderDatabase
    private val dao get() = db.reminders()
    private val now = 1_000_000L
    private val registration by lazy { testRegistration("dispatch-recovery", now - 1_000, state = ReminderState.RINGING) }

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReminderDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking { assertTrue(dao.insertTestRegistration(registration)) }
    }
    @After fun close() = db.close()

    @Test fun restartScanIncludesClaimedActionsButNeverAcknowledgedOrRejectedFacts() = runBlocking {
        for ((index, state) in ReminderDispatchState.entries.withIndex()) {
            dao.insertAction(ReminderActionRow("action-$state", registration.group.groupId,
                ReminderActionKind.TAKEN.name, now + index, state.name,
                if (state == ReminderDispatchState.PENDING) 0 else 1, now))
        }
        assertEquals(listOf("action-PENDING", "action-DISPATCHING"), dao.pendingActions().map { it.actionId })
        assertEquals(4, ReminderDispatchState.entries.count { dao.action("action-$it") != null })
    }

    @Test fun restartScanIncludesClaimedPhaseFactsButNotTerminalFacts() = runBlocking {
        for ((index, state) in ReminderDispatchState.entries.withIndex()) {
            dao.insertAlarmEvent(ReminderAlarmEventRow("event-$state", registration.group.groupId,
                ReminderAlarmKind.FOLLOW_UP.name, now, now + index, state.name,
                if (state == ReminderDispatchState.PENDING) 0 else 1, now))
        }
        assertEquals(listOf("event-PENDING", "event-DISPATCHING"), dao.pendingAlarmEvents().map { it.eventId })
    }

    @Test fun staleReclaimPreservesOriginalActionAndPayloadWhileFreshClaimCannotBeStolen() = runBlocking {
        val original = ReminderActionRow("original-action", registration.group.groupId, ReminderActionKind.TAKEN.name, now)
        dao.insertAction(original)
        assertEquals(1, dao.claimActionDispatch(original.actionId, now, now - 60_000))
        assertEquals(0, dao.claimActionDispatch(original.actionId, now + 1, now - 59_999))
        assertEquals(1, dao.claimActionDispatch(original.actionId, now + 60_001, now + 1))
        val reclaimed = checkNotNull(dao.action(original.actionId))
        assertEquals(original.actionId, reclaimed.actionId)
        assertEquals(original.groupId, reclaimed.groupId)
        assertEquals(original.kind, reclaimed.kind)
        assertEquals(original.recordedAtEpochMillis, reclaimed.recordedAtEpochMillis)
        assertEquals(2, reclaimed.dispatchAttempts)
        assertEquals(registration.group.deadlineAtEpochMillis, dao.find(registration.group.groupId)?.deadlineAtEpochMillis)
    }

    @Test fun liveActionClaimReturnsRetryWithoutSendingOrChangingItsIdentity() = runBlocking {
        val action = ReminderActionRow("busy-action", registration.group.groupId, ReminderActionKind.TAKEN.name, now)
        dao.insertAction(action)
        assertEquals(1, dao.claimActionDispatch(action.actionId, now, now - 60_000))
        val result = dispatchReminderAction(dao, action.actionId, ReminderActionDispatcher { _, _ ->
            fail("A fresh claim must not be sent again"); ReminderDispatchResult.ACKNOWLEDGED
        }, now + 1)
        assertEquals(Result.retry().javaClass, result.javaClass)
        assertEquals(1, dao.action(action.actionId)?.dispatchAttempts)
        assertEquals(listOf(action.actionId), dao.pendingActions().map { it.actionId })
    }

    @Test fun recoveredActionAcknowledgesOnlyAfterAcceptanceAndTerminalReplayDoesNotResend() = runBlocking {
        val action = ReminderActionRow("replayed-action", registration.group.groupId, ReminderActionKind.TAKEN.name, now)
        dao.insertAction(action)
        dao.claimActionDispatch(action.actionId, now, now - 60_000)
        var sends = 0
        val dispatcher = ReminderActionDispatcher { row, group ->
            sends++
            assertEquals(action.actionId, row.actionId); assertEquals(action.recordedAtEpochMillis, row.recordedAtEpochMillis)
            assertEquals(registration.group.groupId, group.group.groupId)
            assertEquals(ReminderDispatchState.DISPATCHING.name, dao.action(action.actionId)?.dispatchState)
            ReminderDispatchResult.ACKNOWLEDGED
        }
        assertEquals(Result.success().javaClass, dispatchReminderAction(dao, action.actionId, dispatcher, now + 60_001).javaClass)
        assertEquals(ReminderDispatchState.ACKNOWLEDGED.name, dao.action(action.actionId)?.dispatchState)
        dispatchReminderAction(dao, action.actionId, dispatcher, now + 120_001)
        assertEquals(1, sends); assertTrue(dao.pendingActions().isEmpty())
    }

    @Test fun phaseRecoveryRetriesLiveClaimThenResendsTheSameFactOnce() = runBlocking {
        val event = ReminderAlarmEventRow("replayed-phase", registration.group.groupId, ReminderAlarmKind.FOLLOW_UP.name, now, now)
        dao.insertAlarmEvent(event); dao.claimAlarmDispatch(event.eventId, now, now - 60_000)
        var sends = 0
        val dispatcher = ReminderPhaseDispatcher { row ->
            sends++; assertEquals(event.eventId, row.eventId); assertEquals(event.scheduledAtEpochMillis, row.scheduledAtEpochMillis)
            ReminderDispatchResult.ACKNOWLEDGED
        }
        assertEquals(Result.retry().javaClass, dispatchReminderPhase(dao, event.eventId, dispatcher, now + 1).javaClass)
        assertEquals(0, sends)
        assertEquals(Result.success().javaClass, dispatchReminderPhase(dao, event.eventId, dispatcher, now + 60_001).javaClass)
        dispatchReminderPhase(dao, event.eventId, dispatcher, now + 120_001)
        assertEquals(1, sends); assertTrue(dao.pendingAlarmEvents().isEmpty())
    }

    @Test fun cancellationKeepsClaimRecoverableAndDoesNotFabricateAcknowledgement() = runBlocking {
        val action = ReminderActionRow("cancelled-action", registration.group.groupId, ReminderActionKind.TAKEN.name, now)
        dao.insertAction(action)
        try {
            dispatchReminderAction(dao, action.actionId, ReminderActionDispatcher { _, _ -> throw CancellationException("test interruption") }, now)
            fail("Cancellation must propagate to WorkManager")
        } catch (_: CancellationException) { }
        assertEquals(ReminderDispatchState.DISPATCHING.name, dao.action(action.actionId)?.dispatchState)
        assertEquals(listOf(action.actionId), dao.pendingActions().map { it.actionId })
    }

    @Test fun repeatedRecoveryScanKeepsExistingUniqueWorkInsteadOfReplacingItsWorker() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val realDao = ReminderDatabase.open(context).reminders()
        val at = System.currentTimeMillis()
        val group = testRegistration("unique-${UUID.randomUUID()}", at - 1_000, state = ReminderState.RINGING)
        realDao.insertTestRegistration(group)
        val action = ReminderActionRow(UUID.randomUUID().toString(), group.group.groupId, ReminderActionKind.TAKEN.name, at)
        realDao.recordLocalAction(action, null); realDao.claimActionDispatch(action.actionId, at, at - 60_000)
        val work = WorkManager.getInstance(context); val name = "reminder-action-${action.actionId}"
        try {
            ReminderActionWorker.enqueue(context, action.actionId)
            val first = work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).single().id
            ReminderActionWorker.enqueuePending(context)
            val again = work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
            assertEquals(listOf(first), again.map { it.id })
            assertFalse(again.single().state.isFinished)
        } finally {
            work.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
            realDao.finishActionDispatch(action.actionId, ReminderDispatchState.PERMANENT_FAILURE.name)
        }
    }
}
