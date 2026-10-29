package com.group33.medicationreminder.core.data.journal

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No wall-clock/per-process UUID fallback: an unavailable boot identity stops recovery. */
internal class AndroidJournalClock(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun now(): JournalTime = withContext(Dispatchers.IO) {
        val boot = if (Build.VERSION.SDK_INT >= 24) {
            val count = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT)
            check(count >= 0)
            "android-boot:$count"
        } else {
            // API23 has no public BOOT_COUNT. Kernel boot_id is optional on vendor devices;
            // denied/unavailable/malformed reads fail closed instead of fabricating a new boot.
            val id = File("/proc/sys/kernel/random/boot_id").bufferedReader().use { it.readLine() }
            check(UUID.fromString(id).toString() == id)
            "kernel-boot:$id"
        }
        JournalTime(boot, SystemClock.elapsedRealtime())
    }
}
