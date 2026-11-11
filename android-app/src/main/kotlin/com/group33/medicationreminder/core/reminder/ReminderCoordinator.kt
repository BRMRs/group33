package com.group33.medicationreminder.core.reminder

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.Flow

internal enum class ReminderRegistrationStatus { SCHEDULED, ALREADY_REGISTERED, TERMINAL, CONFLICT, EXPIRED }

internal data class ReminderRegistrationResult(
    val status: ReminderRegistrationStatus,
    val capability: ReminderCapabilitySnapshot,
    val accuracy: ScheduleAccuracy?,
    val groupId: String,
)

internal class ReminderCoordinator(private val context: Context) {
    private val dao = ReminderDatabase.open(context).reminders()
    private val scheduler = ReminderAlarmScheduler(context)

    fun observeActive(): Flow<List<ReminderGroupRow>> = dao.observeActive()
    suspend fun alarmEvents(groupId: String): List<ReminderAlarmEventRow> = dao.alarmEvents(groupId)

    suspend fun register(group: ReminderGroup): ReminderRegistrationResult {
        val capability = ReminderCapability.snapshot(context)
        val row = group.toRow()
        val occurrences = group.occurrences.map { it.toRow(group.groupId) }
        val inserted = dao.insertRegistration(row, occurrences)
        val stored = checkNotNull(dao.registration(group.groupId))
        if (!sameIdentity(row, occurrences, stored)) {
            return result(ReminderRegistrationStatus.CONFLICT, capability, null, group.groupId)
        }
        if (stored.group.state !in activeStates) {
            return result(ReminderRegistrationStatus.TERMINAL, capability, null, group.groupId)
        }
        val now = System.currentTimeMillis()
        if (stored.group.deadlineAtEpochMillis <= now) {
            dao.expire(now)
            return result(ReminderRegistrationStatus.EXPIRED, capability, null, group.groupId)
        }
        val accuracy = scheduler.scheduleInitial(stored.group)
        if (stored.group.state == ReminderState.SCHEDULED.name && stored.group.plannedAtEpochMillis <= now) {
            recordRecoveryEvent(group.groupId, stored.group.plannedAtEpochMillis, now)
            triggerDue(group.groupId, now, bootCount())
        }
        return result(
            if (inserted) ReminderRegistrationStatus.SCHEDULED else ReminderRegistrationStatus.ALREADY_REGISTERED,
            capability, accuracy, group.groupId,
        )
    }

    suspend fun recover() {
        val now = System.currentTimeMillis()
        val boot = bootCount()
        dao.expire(now)
        dao.active().forEach { row ->
            when (ReminderState.valueOf(row.state)) {
                ReminderState.SCHEDULED -> {
                    if (row.plannedAtEpochMillis <= now) {
                        recordRecoveryEvent(row.groupId, row.plannedAtEpochMillis, now)
                        triggerDue(row.groupId, now, boot)
                    } else scheduler.scheduleInitial(row)
                }
                ReminderState.SNOOZED -> {
                    val snoozeAt = row.snoozedUntilEpochMillis
                    if (snoozeAt != null && snoozeAt <= now) {
                        recordRecoveryEvent(row.groupId, snoozeAt, now)
                        triggerDue(row.groupId, now, boot)
                    } else if (snoozeAt != null) {
                        scheduler.scheduleInitial(row)
                        scheduler.scheduleSnooze(row, snoozeAt)
                    }
                }
                ReminderState.RINGING -> {
                    deliverNotification(row.groupId, now, boot)
                    scheduler.scheduleInitial(row)
                }
                else -> Unit
            }
        }
        if ((context.applicationContext as? com.group33.medicationreminder.Group33Application)?.reminderPhaseDispatcher != null) {
            ReminderPhaseWorker.enqueuePending(context)
        }
        if ((context.applicationContext as? com.group33.medicationreminder.Group33Application)?.reminderActionDispatcher != null) {
            ReminderActionWorker.enqueuePending(context)
        }
    }

    suspend fun triggerDue(groupId: String, now: Long = System.currentTimeMillis(), boot: Int = bootCount()): Boolean {
        val row = dao.find(groupId) ?: return false
        if (row.deadlineAtEpochMillis <= now) {
            dao.expire(now)
            ReminderNotifications.cancel(context, groupId)
            return false
        }
        if (dao.claimDue(groupId, now) != 1) return false
        return deliverNotification(groupId, now, boot)
    }

    suspend fun recordAction(
        groupId: String,
        actionId: String,
        kind: ReminderActionKind,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(actionId.isNotBlank())
        dao.action(actionId)?.let { existing ->
            return existing.groupId == groupId && existing.kind == kind.name
        }
        val row = dao.find(groupId) ?: return false
        if (row.deadlineAtEpochMillis <= now || row.state != ReminderState.RINGING.name) return false
        val action = ReminderActionRow(actionId, groupId, kind.name, now)
        val snoozedUntil = if (kind == ReminderActionKind.SNOOZE) {
            minOf(now + InitialTiming.snooze.toMillis(), row.deadlineAtEpochMillis)
        } else null
        return when (dao.recordLocalAction(action, snoozedUntil)) {
            LocalActionInsertResult.CONFLICT -> false
            LocalActionInsertResult.DUPLICATE -> true
            LocalActionInsertResult.INSERTED -> {
                ReminderNotifications.cancel(context, groupId)
                if (kind == ReminderActionKind.SNOOZE) {
                    scheduler.scheduleSnooze(row, checkNotNull(snoozedUntil))
                } else {
                    scheduler.cancel(groupId)
                }
                if ((context.applicationContext as? com.group33.medicationreminder.Group33Application)
                        ?.reminderActionDispatcher != null) {
                    ReminderActionWorker.enqueue(context, actionId)
                }
                true
            }
        }
    }

    suspend fun caregiverTakeover(groupId: String, now: Long = System.currentTimeMillis()): Boolean =
        finish(groupId, ReminderState.CAREGIVER_TAKEN_OVER, now)

    suspend fun finish(groupId: String, terminal: ReminderState, now: Long = System.currentTimeMillis()): Boolean {
        require(terminal in setOf(ReminderState.TAKEN, ReminderState.HELP, ReminderState.CAREGIVER_TAKEN_OVER))
        if (dao.finish(groupId, terminal.name, now) != 1) return false
        scheduler.cancel(groupId)
        ReminderNotifications.cancel(context, groupId)
        return true
    }

    suspend fun cancel(groupId: String): Boolean {
        if (dao.cancel(groupId) != 1) return false
        scheduler.cancel(groupId)
        ReminderNotifications.cancel(context, groupId)
        return true
    }

    private suspend fun recordRecoveryEvent(groupId: String, scheduledAt: Long, now: Long) {
        dao.insertAlarmEvent(ReminderAlarmEventRow(
            "$groupId:${ReminderAlarmKind.RECOVERY}:$scheduledAt:${bootCount()}",
            groupId, ReminderAlarmKind.RECOVERY.name, scheduledAt, now,
        ))
    }

    private suspend fun deliverNotification(groupId: String, now: Long, boot: Int): Boolean {
        if (dao.claimNotification(groupId, now, boot, now - NOTIFICATION_CLAIM_TIMEOUT_MILLIS) != 1) return false
        val shown = dao.find(groupId)?.let { ReminderNotifications.show(context, it) } == true
        if (shown) dao.markNotificationDelivered(groupId, boot) else dao.releaseNotificationClaim(groupId, boot)
        return shown
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Settings.SettingNotFoundException) {
        0
    }

    private fun sameIdentity(
        expectedGroup: ReminderGroupRow,
        expectedOccurrences: List<ReminderOccurrenceRow>,
        stored: ReminderGroupWithOccurrences,
    ): Boolean = expectedGroup.groupId == stored.group.groupId && expectedGroup.elderId == stored.group.elderId &&
        expectedGroup.plannedAtEpochMillis == stored.group.plannedAtEpochMillis &&
        expectedGroup.deadlineAtEpochMillis == stored.group.deadlineAtEpochMillis &&
        expectedOccurrences.sortedBy { it.occurrenceId } == stored.occurrences.sortedBy { it.occurrenceId }

    private fun ReminderGroup.toRow() = ReminderGroupRow(
        groupId, elderId, plannedAtEpochMillis, deadlineAtEpochMillis,
        occurrences.joinToString(" · ") { it.medicationName },
        occurrences.joinToString(" · ") { it.dosageText },
    )

    private fun ReminderOccurrence.toRow(groupId: String): ReminderOccurrenceRow {
        val evidence = generationEvidence
        return ReminderOccurrenceRow(
            groupId, occurrenceId, evidence.occurrence.planId, evidence.occurrence.localDate,
            evidence.occurrence.localTime, evidence.planRevision, evidence.generationId,
            evidence.executionTimeZoneId, medicationName, dosageText,
        )
    }

    private fun result(
        status: ReminderRegistrationStatus,
        capability: ReminderCapabilitySnapshot,
        accuracy: ScheduleAccuracy?,
        groupId: String,
    ) = ReminderRegistrationResult(status, capability, accuracy, groupId)

    private companion object {
        const val NOTIFICATION_CLAIM_TIMEOUT_MILLIS = 30_000L
        val activeStates = setOf(ReminderState.SCHEDULED.name, ReminderState.RINGING.name, ReminderState.SNOOZED.name)
    }
}

