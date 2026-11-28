package com.group33.medicationreminder.elder

import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ElderReminderPermissionNavigatorDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun permissionRecoveryEntrypointsTargetThisApplication() {
        val notification = ElderReminderPermissionNavigator.notificationSettingsIntent(context)
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notification.action)
            assertEquals(context.packageName, notification.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        } else {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, notification.action)
            assertEquals("package:${context.packageName}", notification.dataString)
        }

        val exact = ElderReminderPermissionNavigator.exactAlarmSettingsIntent(context)
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, exact.action)
            assertEquals("package:${context.packageName}", exact.dataString)
        } else {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, exact.action)
            assertEquals("package:${context.packageName}", exact.dataString)
        }
    }
}
