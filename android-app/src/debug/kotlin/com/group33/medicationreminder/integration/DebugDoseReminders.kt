package com.group33.medicationreminder.integration

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.group33.medicationreminder.R
import com.group33.medicationreminder.SharedLoginActivity
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.TrustedSessionContext
import com.group33.medicationreminder.core.reminder.InitialTiming
import com.group33.medicationreminder.elder.ElderDoseGroup
import com.group33.medicationreminder.elder.ElderDoseScreen
import com.group33.medicationreminder.elder.LiveElderDoses
import com.group33.medicationreminder.elder.LocalElderOccurrence
import com.group33.medicationreminder.elder.dueElderDose
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LocalDoseUpdate(
    val occurrences: List<LocalElderOccurrence>,
    val hasPlanSnapshot: Boolean,
    val actionRecorded: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class DebugDoseReminders(
    private val app: Context,
    private val repo: DebugChainRepository,
    private val clock: Clock = Clock.systemUTC(),
) : LiveElderDoses {
    private val mutableState = MutableStateFlow(ElderDoseScreen())
    override val state = mutableState.asStateFlow()
    private val gate = Mutex()
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val alarmIntent = PendingIntent.getBroadcast(app, 0, Intent(app, DebugDoseAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private var actionSync: Job? = null
    private var anotherSyncRequested = false
    private lateinit var appScope: CoroutineScope
    private var notifiedGroup: Long? = null

    fun start(scope: CoroutineScope) {
        appScope = scope
        scope.launch {
            combine(repo.services.auth.observeSession(), repo.services.trusted) { session, trusted ->
                trusted?.takeIf { session.readiness == AuthReadiness.READY &&
                    session.user?.role == UserRole.ELDER && session.user.id == trusted.scope.userId }
            }.distinctUntilChanged().flatMapLatest { trusted ->
                if (trusted == null) flowOf(Unit)
                else repo.observePlans(trusted.scope.userId).map { Unit }.onStart { emit(Unit) }
            }.collect { refresh() }
        }
    }

    override suspend fun refresh() {
        gate.withLock { update(null) }
    }

    override suspend fun record(session: TrustedSessionContext, group: ElderDoseGroup, kind: DoseActionKind): Boolean =
        gate.withLock {
            if (session != repo.services.trusted.value) false
            else update(group to kind, session)
        }

    private suspend fun update(action: Pair<ElderDoseGroup, DoseActionKind>?,
                               expected: TrustedSessionContext? = null): Boolean {
        val trusted = repo.services.trusted.value
        if (expected != null && trusted != expected) return false
        val user = repo.services.auth.observeSession().first().user
        if (trusted == null || user?.role != UserRole.ELDER || user.id != trusted.scope.userId) {
            actionSync?.cancel()
            alarms.cancel(alarmIntent)
            notifications.cancel(NOTIFICATION_ID)
            notifiedGroup = null
            mutableState.value = ElderDoseScreen()
            return false
        }
        val result = repo.localElderDoses(trusted, clock, action)
        if (trusted != repo.services.trusted.value) return false
        val update = (result as? Outcome.Success)?.value
        val due = update?.let { dueElderDose(it.occurrences, clock) }
        val enabled = NotificationManagerCompat.from(app).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 26 || notifications.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
        mutableState.value = ElderDoseScreen(trusted, due, enabled,
            update != null && (update.hasPlanSnapshot || update.occurrences.isNotEmpty()))
        updateNotification(due, enabled)
        scheduleNext(update?.occurrences.orEmpty())
        if (::appScope.isInitialized) {
            if (actionSync?.isActive == true) {
                if (update?.actionRecorded == true) anotherSyncRequested = true
            } else {
                actionSync = appScope.launch {
                    do {
                        anotherSyncRequested = false
                        for (attempt in 0..2) {
                            if (trusted != repo.services.trusted.value) return@launch
                            if (repo.syncLocalElderActions(trusted) is Outcome.Success) break
                            if (attempt < 2) delay(5_000L * (attempt + 1))
                        }
                    } while (anotherSyncRequested)
                }
            }
        }
        return update?.actionRecorded == true
    }

    private fun scheduleNext(occurrences: List<LocalElderOccurrence>) {
        val now = clock.millis()
        val nextChange = occurrences.asSequence().filter { it.takenAt == null }
            .flatMap { sequenceOf(it.plannedAt, it.snoozedUntil ?: it.plannedAt,
                it.plannedAt + InitialTiming.responseWindow.toMillis()) }
            .filter { it > now }.minOrNull()
        val maintenance = now + Duration.ofHours(12).toMillis()
        // Android may defer an inexact alarm in Doze. The persisted response deadline stays fixed.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, minOf(nextChange ?: maintenance, maintenance), alarmIntent)
    }

    private fun updateNotification(due: ElderDoseGroup?, enabled: Boolean) {
        if (due == null || !enabled) {
            notifiedGroup = null
            notifications.cancel(NOTIFICATION_ID)
            return
        }
        if (notifiedGroup == due.plannedAt) return
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                app.getString(R.string.elder_dose_notification_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(app, 0, Intent(app, SharedLoginActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID) else Notification.Builder(app)
        try {
            notifications.notify(NOTIFICATION_ID, builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.elder_due_now_label))
                .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH).build())
            notifiedGroup = due.plannedAt
        } catch (_: SecurityException) {
            // Notification permission can be revoked between the check and delivery.
        }
    }

    private companion object {
        const val CHANNEL_ID = "elder-dose-reminders"
        const val NOTIFICATION_ID = 14001
    }
}

class DebugDoseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? DebugChainApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withTimeoutOrNull(8_000) {
                    app.repository.services.auth.observeSession().first { it.readiness != AuthReadiness.RESTORING }
                    app.elderDoses.refresh()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
