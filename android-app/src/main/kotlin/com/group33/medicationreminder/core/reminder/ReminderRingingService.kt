package com.group33.medicationreminder.core.reminder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The only component allowed to produce reminder sound. Notifications are deliberately silent. */
internal class ReminderRingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var currentGroupId: String? = null
    private var player: MediaPlayer? = null
    private var fallbackTone: ToneGenerator? = null
    private val toneHandler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var deadlineJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(EXTRA_GROUP_ID)
        // Satisfy the five-second foreground deadline before touching Room or resolving audio.
        if (currentGroupId == null) {
            startForegroundCompat(ReminderNotifications.placeholder(this, requested.orEmpty()))
        }
        scope.launch { reconcile(requested) }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAudio()
        instance.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun reconcile(preferredGroupId: String?) {
        val now = System.currentTimeMillis()
        val dao = ReminderDatabase.open(this).reminders()
        val rows = withContext(Dispatchers.IO) { dao.ringing(now) }
        val row = rows.firstOrNull { it.groupId == preferredGroupId } ?: rows.firstOrNull()
        if (row == null) {
            stopAudio()
            removeForegroundNotification()
            stopSelf()
            return
        }
        currentGroupId = row.groupId
        startForegroundCompat(ReminderNotifications.build(this, row))
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            delay((row.deadlineAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L))
            ReminderCoordinator(this@ReminderRingingService).cancel(row.groupId)
            reconcile(null)
        }
        if (player?.isPlaying != true && fallbackTone == null) startAudio()
    }

    private fun startAudio() {
        requestAudioFocus()
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (alarm != null) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(ALARM_ATTRIBUTES)
                    setDataSource(this@ReminderRingingService, alarm)
                    isLooping = true
                    setWakeMode(this@ReminderRingingService, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                    prepare()
                    start()
                }
                return
            } catch (_: Exception) {
                player?.release()
                player = null
            }
        }
        // Defensive vendor fallback when no system alarm URI is readable.
        fallbackTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        toneHandler.post(tonePulse)
    }

    private val tonePulse = object : Runnable {
        override fun run() {
            fallbackTone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
            if (fallbackTone != null) toneHandler.postDelayed(this, 1_100L)
        }
    }

    private fun stopAudio() {
        deadlineJob?.cancel()
        deadlineJob = null
        toneHandler.removeCallbacks(tonePulse)
        fallbackTone?.release()
        fallbackTone = null
        player?.runCatching { stop() }
        player?.release()
        player = null
        abandonAudioFocus()
        currentGroupId = null
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(ALARM_ATTRIBUTES)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                ReminderNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else startForeground(ReminderNotifications.NOTIFICATION_ID, notification)
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun reconcileAfterStateChange(groupId: String) {
        scope.launch {
            if (currentGroupId == groupId) reconcile(null)
            else ReminderNotifications.cancel(this@ReminderRingingService, groupId)
        }
    }

    companion object {
        private const val EXTRA_GROUP_ID = "groupId"
        private var instance = WeakReference<ReminderRingingService>(null)
        private val ALARM_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun start(context: Context, groupId: String): Boolean = try {
            val intent = Intent(context, ReminderRingingService::class.java)
                .putExtra(EXTRA_GROUP_ID, groupId)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: RuntimeException) {
            false
        }

        fun stop(context: Context, groupId: String) {
            ReminderNotifications.cancel(context, groupId)
            instance.get()?.reconcileAfterStateChange(groupId)
        }

        fun refresh(context: Context, groupId: String) {
            instance.get()?.let { service -> service.scope.launch { service.reconcile(groupId) } }
                ?: start(context, groupId)
        }

        fun reconcile() {
            instance.get()?.let { service -> service.scope.launch { service.reconcile(null) } }
        }

        internal fun isPlayingForTest(): Boolean = instance.get()?.let {
            runCatching { it.player?.isPlaying == true }.getOrDefault(false) || it.fallbackTone != null
        } == true

        internal fun activeGroupForTest(): String? = instance.get()?.currentGroupId
    }
}
