package com.group33.medicationreminder.core.reminder

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderDatabaseTest {
    private lateinit var db: ReminderDatabase
    private lateinit var dao: ReminderDao
    private val planned = 1_000_000L
    private val deadline = planned + InitialTiming.responseWindow.toMillis()
    private val registration by lazy { testRegistration("dose", planned, deadline, medicineCount = 2) }
    private val id get() = registration.group.groupId

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ReminderDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.reminders()
    }

    @After fun close() = db.close()

    @Test fun groupedRegistrationIsAtomicAndCannotReplaceTerminalStateOrIdentity() = runBlocking {
        assertTrue(dao.insertTestRegistration(registration))
        assertEquals(2, dao.registration(id)?.occurrences?.size)
        assertEquals(1, dao.finish(id, ReminderState.TAKEN.name, planned))
        assertFalse(dao.insertTestRegistration(registration.copy(group = registration.group.copy(elderId = "other"))))
        val stored = checkNotNull(dao.registration(id))
        assertEquals("fictional-elder", stored.group.elderId)
        assertEquals(ReminderState.TAKEN.name, stored.group.state)
        assertEquals(2, stored.occurrences.size)
    }

    @Test fun dueClaimIsAtomicAndHonorsOriginalDeadline() = runBlocking {
        dao.insertTestRegistration(registration)
        assertEquals(0, dao.claimDue(id, planned - 1))
        assertEquals(1, dao.claimDue(id, planned))
        assertEquals(0, dao.claimDue(id, planned))
        assertEquals(1, dao.cancel(id))
        assertEquals(0, dao.claimDue(id, planned + 1))
    }

    @Test fun notificationClaimRetriesFailureAndAllowsOnlyOneCatchUpPerBoot() = runBlocking {
        dao.insertTestRegistration(registration)
        dao.claimDue(id, planned)
        assertEquals(1, dao.claimNotification(id, planned, 7, planned - 30_000))
        assertEquals(0, dao.claimNotification(id, planned + 1, 7, planned - 29_999))
        assertEquals(1, dao.releaseNotificationClaim(id, 7))
        assertEquals(1, dao.claimNotification(id, planned + 2, 7, planned - 29_998))
        assertEquals(1, dao.markNotificationDelivered(id, 7))
        assertEquals(0, dao.claimNotification(id, planned + 3, 7, planned - 29_997))
        assertEquals(1, dao.claimNotification(id, planned + 4, 8, planned - 29_996))
        assertEquals(1, dao.markNotificationDelivered(id, 8))
        assertEquals(1, dao.find(id)?.recoveryNotificationCount)
    }

    @Test fun snoozeActionIsAtomicGroupWideAndDoesNotShiftDeadline() = runBlocking {
        dao.insertTestRegistration(registration)
        dao.claimDue(id, planned)
        val snoozedUntil = planned + InitialTiming.snooze.toMillis()
        val action = ReminderActionRow("action-snooze", id, ReminderActionKind.SNOOZE.name, planned + 1)
        assertEquals(LocalActionInsertResult.INSERTED, dao.recordLocalAction(action, snoozedUntil))
        assertEquals(LocalActionInsertResult.DUPLICATE, dao.recordLocalAction(action, snoozedUntil))
        assertEquals(ReminderState.SNOOZED.name, dao.find(id)?.state)
        assertEquals(1, dao.claimDue(id, snoozedUntil))
        assertEquals(1, dao.claimNotification(id, snoozedUntil, 7, snoozedUntil - 30_000))
        assertEquals(deadline, dao.find(id)?.deadlineAtEpochMillis)
        assertEquals(0, dao.claimDue(id, deadline))
        assertEquals(1, dao.expire(deadline))
        assertEquals(ReminderState.EXPIRED.name, dao.find(id)?.state)
    }

    @Test fun takenActionAtomicallyFinishesWholeGroupAndCreatesOneOutboxFact() = runBlocking {
        dao.insertTestRegistration(registration)
        dao.claimDue(id, planned)
        val action = ReminderActionRow("action-taken", id, ReminderActionKind.TAKEN.name, planned + 1)
        assertEquals(LocalActionInsertResult.INSERTED, dao.recordLocalAction(action, null))
        assertEquals(ReminderState.TAKEN.name, dao.registration(id)?.group?.state)
        assertEquals(2, dao.registration(id)?.occurrences?.size)
        assertEquals(listOf(action), dao.pendingActions())
        assertEquals(LocalActionInsertResult.DUPLICATE, dao.recordLocalAction(action, null))
    }

    @Test fun alarmFactsAreDeduplicatedByOriginalPhaseAndTime() = runBlocking {
        dao.insertTestRegistration(registration)
        val event = ReminderAlarmEventRow("$id:DUE:$planned", id, ReminderAlarmKind.DUE.name, planned, planned + 5)
        assertNotEquals(-1L, dao.insertAlarmEvent(event))
        assertEquals(-1L, dao.insertAlarmEvent(event.copy(firedAtEpochMillis = planned + 10)))
        assertEquals(listOf(event), dao.alarmEvents(id))
    }
}
