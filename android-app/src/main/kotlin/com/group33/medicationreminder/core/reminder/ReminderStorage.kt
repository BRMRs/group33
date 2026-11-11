package com.group33.medicationreminder.core.reminder

import android.content.Context
import androidx.room.*
import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.OccurrenceKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

internal enum class ReminderState {
    SCHEDULED, RINGING, SNOOZED, TAKEN, HELP, CAREGIVER_TAKEN_OVER, EXPIRED
}

internal enum class ReminderAlarmKind { PRE_PROBE, DUE, POST_PROBE, FOLLOW_UP, EXPIRE, RECOVERY }
internal enum class ReminderActionKind { TAKEN, SNOOZE, HELP }
internal enum class ReminderDispatchState { PENDING, DISPATCHING, ACKNOWLEDGED, PERMANENT_FAILURE }
internal enum class LocalActionInsertResult { INSERTED, DUPLICATE, CONFLICT }

internal data class ReminderOccurrence(
    val elderId: String,
    val medicationName: String,
    val dosageText: String,
    val generationEvidence: OccurrenceGenerationEvidence,
) {
    init {
        require(elderId.isNotBlank() && medicationName.isNotBlank() && dosageText.isNotBlank())
        require(generationEvidence.generationId.isNotBlank())
        require(generationEvidence.occurrence.planId.isNotBlank())
    }
    val occurrenceId: String get() = generationEvidence.occurrence.stableReminderId()
    val plannedAtEpochMillis: Long get() = generationEvidence.plannedAtEpochMillis
}

internal data class ReminderGroup(
    val executionScopeId: String,
    val elderId: String,
    val occurrences: List<ReminderOccurrence>,
) {
    init {
        require(executionScopeId.isNotBlank() && elderId.isNotBlank() && occurrences.isNotEmpty())
        require(occurrences.all { it.elderId == elderId })
        require(occurrences.map { it.occurrenceId }.distinct().size == occurrences.size)
        require(occurrences.map { it.plannedAtEpochMillis }.distinct().size == 1)
    }
    val plannedAtEpochMillis = occurrences.first().plannedAtEpochMillis
    val deadlineAtEpochMillis = plannedAtEpochMillis + InitialTiming.responseWindow.toMillis()
    val groupId: String = stableGroupId(
        executionScopeId, elderId, plannedAtEpochMillis, occurrences.map { it.occurrenceId },
    )
}

internal fun OccurrenceKey.stableReminderId(): String = "$planId\u001f$localDate\u001f$localTime"

private fun stableGroupId(
    executionScopeId: String,
    elderId: String,
    plannedAt: Long,
    occurrenceIds: List<String>,
): String {
    val canonical = buildString {
        append(executionScopeId).append('\n').append(elderId).append('\n').append(plannedAt)
        occurrenceIds.sorted().forEach { append('\n').append(it) }
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Entity(
    tableName = "reminder_groups",
    indices = [
        Index(value = ["state", "plannedAtEpochMillis"]),
        Index(value = ["executionScopeId"]),
        Index(value = ["elderId"]),
    ]
)
internal data class ReminderGroupRow(
    @PrimaryKey val groupId: String,
    val executionScopeId: String,
    val elderId: String,
    val plannedAtEpochMillis: Long,
    val deadlineAtEpochMillis: Long,
    val medicationSummary: String,
    val dosageSummary: String,
    val state: String = ReminderState.SCHEDULED.name,
    val snoozedUntilEpochMillis: Long? = null,
    val lastAlarmAtEpochMillis: Long? = null,
    val lastNotifiedBootCount: Int? = null,
    val notificationClaimBootCount: Int? = null,
    val notificationClaimedAtEpochMillis: Long? = null,
    val recoveryNotificationCount: Int = 0,
)

@Entity(
    tableName = "reminder_group_occurrences",
    primaryKeys = ["groupId", "occurrenceId"],
    foreignKeys = [ForeignKey(
        entity = ReminderGroupRow::class,
        parentColumns = ["groupId"], childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["executionScopeId", "occurrenceId"], unique = true),
    ],
)
internal data class ReminderOccurrenceRow(
    val groupId: String,
    val executionScopeId: String,
    val occurrenceId: String,
    val planId: String,
    val localDate: String,
    val localTime: String,
    val planRevision: Long,
    val generationId: String,
    val executionTimeZoneId: String,
    val medicationName: String,
    val dosageText: String,
)

internal data class ReminderGroupWithOccurrences(
    @Embedded val group: ReminderGroupRow,
    @Relation(parentColumn = "groupId", entityColumn = "groupId")
    val occurrences: List<ReminderOccurrenceRow>,
)

@Entity(
    tableName = "reminder_alarm_events",
    foreignKeys = [ForeignKey(
        entity = ReminderGroupRow::class,
        parentColumns = ["groupId"], childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["groupId"])]
)
internal data class ReminderAlarmEventRow(
    @PrimaryKey val eventId: String,
    val groupId: String,
    val kind: String,
    val scheduledAtEpochMillis: Long,
    val firedAtEpochMillis: Long,
    val dispatchState: String = ReminderDispatchState.PENDING.name,
    val dispatchAttempts: Int = 0,
    val lastDispatchAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "reminder_actions",
    foreignKeys = [ForeignKey(
        entity = ReminderGroupRow::class,
        parentColumns = ["groupId"], childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["groupId"])]
)
internal data class ReminderActionRow(
    @PrimaryKey val actionId: String,
    val groupId: String,
    val kind: String,
    val recordedAtEpochMillis: Long,
    val dispatchState: String = ReminderDispatchState.PENDING.name,
    val dispatchAttempts: Int = 0,
    val lastDispatchAtEpochMillis: Long? = null,
)

@Dao
internal interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGroup(row: ReminderGroupRow): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOccurrences(rows: List<ReminderOccurrenceRow>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAction(row: ReminderActionRow): Long

    @Transaction
    suspend fun recordLocalAction(action: ReminderActionRow, snoozedUntil: Long?): LocalActionInsertResult {
        val existing = action(action.actionId)
        if (existing != null) {
            return if (existing.groupId == action.groupId && existing.kind == action.kind) {
                LocalActionInsertResult.DUPLICATE
            } else LocalActionInsertResult.CONFLICT
        }
        check(insertAction(action) != -1L)
        val changed = if (action.kind == ReminderActionKind.SNOOZE.name) {
            snooze(action.groupId, checkNotNull(snoozedUntil), action.recordedAtEpochMillis)
        } else {
            val terminal = if (action.kind == ReminderActionKind.TAKEN.name) ReminderState.TAKEN else ReminderState.HELP
            finish(action.groupId, terminal.name, action.recordedAtEpochMillis)
        }
        check(changed == 1) { "Reminder group changed before the local action transaction" }
        return LocalActionInsertResult.INSERTED
    }

    @Transaction
    suspend fun insertRegistration(group: ReminderGroupRow, occurrences: List<ReminderOccurrenceRow>): Boolean {
        if (insertGroup(group) == -1L) return false
        insertOccurrences(occurrences)
        return true
    }

    @Query("SELECT * FROM reminder_groups WHERE groupId = :id") suspend fun find(id: String): ReminderGroupRow?
    @Transaction @Query("SELECT * FROM reminder_groups WHERE groupId = :id")
    suspend fun registration(id: String): ReminderGroupWithOccurrences?

    @Query("SELECT * FROM reminder_groups WHERE state IN ('SCHEDULED','RINGING','SNOOZED') ORDER BY plannedAtEpochMillis")
    suspend fun active(): List<ReminderGroupRow>

    @Query("SELECT * FROM reminder_groups WHERE executionScopeId = :scopeId AND state IN ('SCHEDULED','RINGING','SNOOZED') ORDER BY plannedAtEpochMillis")
    suspend fun activeForScope(scopeId: String): List<ReminderGroupRow>

    @Query("SELECT * FROM reminder_groups WHERE state IN ('SCHEDULED','RINGING','SNOOZED') ORDER BY plannedAtEpochMillis")
    fun observeActive(): Flow<List<ReminderGroupRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAlarmEvent(row: ReminderAlarmEventRow): Long
    @Query("SELECT * FROM reminder_alarm_events WHERE groupId = :id ORDER BY firedAtEpochMillis")
    suspend fun alarmEvents(id: String): List<ReminderAlarmEventRow>
    @Query("SELECT * FROM reminder_alarm_events WHERE eventId = :eventId")
    suspend fun alarmEvent(eventId: String): ReminderAlarmEventRow?
    @Query("SELECT * FROM reminder_alarm_events WHERE dispatchState = 'PENDING' ORDER BY firedAtEpochMillis")
    suspend fun pendingAlarmEvents(): List<ReminderAlarmEventRow>
    @Query("SELECT * FROM reminder_actions WHERE actionId = :actionId") suspend fun action(actionId: String): ReminderActionRow?
    @Query("SELECT * FROM reminder_actions WHERE dispatchState = 'PENDING' ORDER BY recordedAtEpochMillis")
    suspend fun pendingActions(): List<ReminderActionRow>

    @Query("""
        UPDATE reminder_groups SET state = 'RINGING', snoozedUntilEpochMillis = NULL,
            lastAlarmAtEpochMillis = :now
        WHERE groupId = :id AND state IN ('SCHEDULED','SNOOZED') AND deadlineAtEpochMillis > :now
          AND ((state = 'SCHEDULED' AND plannedAtEpochMillis <= :now)
            OR (state = 'SNOOZED' AND snoozedUntilEpochMillis <= :now))
    """) suspend fun claimDue(id: String, now: Long): Int

    @Query("""
        UPDATE reminder_groups SET notificationClaimBootCount = :bootCount, notificationClaimedAtEpochMillis = :now
        WHERE groupId = :id AND state = 'RINGING' AND deadlineAtEpochMillis > :now
          AND (lastNotifiedBootCount IS NULL OR lastNotifiedBootCount != :bootCount)
          AND (notificationClaimBootCount IS NULL OR notificationClaimBootCount != :bootCount
            OR notificationClaimedAtEpochMillis < :staleBefore)
    """) suspend fun claimNotification(id: String, now: Long, bootCount: Int, staleBefore: Long): Int

    @Query("""
        UPDATE reminder_groups
        SET recoveryNotificationCount = recoveryNotificationCount +
                CASE WHEN lastNotifiedBootCount IS NOT NULL AND lastNotifiedBootCount != :bootCount THEN 1 ELSE 0 END,
            lastNotifiedBootCount = :bootCount,
            notificationClaimBootCount = NULL, notificationClaimedAtEpochMillis = NULL
        WHERE groupId = :id AND state = 'RINGING' AND notificationClaimBootCount = :bootCount
    """) suspend fun markNotificationDelivered(id: String, bootCount: Int): Int

    @Query("UPDATE reminder_groups SET notificationClaimBootCount = NULL, notificationClaimedAtEpochMillis = NULL WHERE groupId = :id AND notificationClaimBootCount = :bootCount")
    suspend fun releaseNotificationClaim(id: String, bootCount: Int): Int

    @Query("""
        UPDATE reminder_groups SET state = 'SNOOZED', snoozedUntilEpochMillis = :until,
            lastNotifiedBootCount = NULL, notificationClaimBootCount = NULL,
            notificationClaimedAtEpochMillis = NULL
        WHERE groupId = :id AND state = 'RINGING' AND deadlineAtEpochMillis > :now
    """) suspend fun snooze(id: String, until: Long, now: Long): Int

    @Query("""
        UPDATE reminder_groups SET state = :terminal, snoozedUntilEpochMillis = NULL,
            notificationClaimBootCount = NULL, notificationClaimedAtEpochMillis = NULL
        WHERE groupId = :id AND state IN ('SCHEDULED','RINGING','SNOOZED') AND deadlineAtEpochMillis > :now
    """) suspend fun finish(id: String, terminal: String, now: Long): Int

    @Query("UPDATE reminder_groups SET state = 'EXPIRED', snoozedUntilEpochMillis = NULL, notificationClaimBootCount = NULL, notificationClaimedAtEpochMillis = NULL WHERE groupId = :id AND state IN ('SCHEDULED','RINGING','SNOOZED')")
    suspend fun cancel(id: String): Int

    @Query("UPDATE reminder_groups SET state = 'EXPIRED', snoozedUntilEpochMillis = NULL, notificationClaimBootCount = NULL, notificationClaimedAtEpochMillis = NULL WHERE state IN ('SCHEDULED','RINGING','SNOOZED') AND deadlineAtEpochMillis <= :now")
    suspend fun expire(now: Long): Int

    @Query("""
        UPDATE reminder_alarm_events SET dispatchState = 'DISPATCHING', dispatchAttempts = dispatchAttempts + 1,
            lastDispatchAtEpochMillis = :now WHERE eventId = :id AND (dispatchState = 'PENDING'
              OR (dispatchState = 'DISPATCHING' AND lastDispatchAtEpochMillis < :staleBefore))
    """) suspend fun claimAlarmDispatch(id: String, now: Long, staleBefore: Long): Int
    @Query("UPDATE reminder_alarm_events SET dispatchState = :state WHERE eventId = :id AND dispatchState = 'DISPATCHING'")
    suspend fun finishAlarmDispatch(id: String, state: String): Int
    @Query("UPDATE reminder_alarm_events SET dispatchState = 'PENDING' WHERE eventId = :id AND dispatchState = 'DISPATCHING'")
    suspend fun releaseAlarmDispatch(id: String): Int

    @Query("""
        UPDATE reminder_actions SET dispatchState = 'DISPATCHING', dispatchAttempts = dispatchAttempts + 1,
            lastDispatchAtEpochMillis = :now WHERE actionId = :id AND (dispatchState = 'PENDING'
              OR (dispatchState = 'DISPATCHING' AND lastDispatchAtEpochMillis < :staleBefore))
    """) suspend fun claimActionDispatch(id: String, now: Long, staleBefore: Long): Int
    @Query("UPDATE reminder_actions SET dispatchState = :state WHERE actionId = :id AND dispatchState = 'DISPATCHING'")
    suspend fun finishActionDispatch(id: String, state: String): Int
    @Query("UPDATE reminder_actions SET dispatchState = 'PENDING' WHERE actionId = :id AND dispatchState = 'DISPATCHING'")
    suspend fun releaseActionDispatch(id: String): Int
}

@Database(
    entities = [ReminderGroupRow::class, ReminderOccurrenceRow::class, ReminderAlarmEventRow::class, ReminderActionRow::class],
    version = 1,
    exportSchema = true,
)
internal abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminders(): ReminderDao
    companion object {
        @Volatile private var instance: ReminderDatabase? = null
        fun open(context: Context): ReminderDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ReminderDatabase::class.java, "reminders.db")
                .build().also { instance = it }
        }
    }
}

