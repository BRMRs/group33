package com.group33.medicationreminder.elder

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.core.data.contract.TrustedSessionContext
import com.group33.medicationreminder.core.reminder.ReminderCapabilitySnapshot
import com.group33.shared.contract.DeliveryCondition
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.contract.ExactAlarmAccess
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ElderReminderPermissionPanelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val session = TrustedSessionContext(
        RepositoryScope("permission-test", "installation-test", "elder-test"),
        generation = 1,
    )

    @Test fun unavailableCapabilityRendersAndDispatchesNotificationRecovery() = checkPanel(
        ReminderCapabilitySnapshot(
            DeliveryCondition.UNAVAILABLE,
            notificationPermissionGranted = false,
            channelEnabled = true,
            exactAlarmAccess = ExactAlarmAccess.GRANTED,
        ),
        R.string.elder_reminder_notifications_unavailable,
        R.string.elder_enable_dose_notifications,
        ReminderPermissionPrompt.NOTIFICATIONS_UNAVAILABLE,
    )

    @Test fun degradedCapabilityRendersAndDispatchesExactAlarmRecovery() = checkPanel(
        ReminderCapabilitySnapshot(
            DeliveryCondition.DEGRADED,
            notificationPermissionGranted = true,
            channelEnabled = true,
            exactAlarmAccess = ExactAlarmAccess.DENIED,
        ),
        R.string.elder_reminder_exact_alarm_degraded,
        R.string.elder_allow_exact_alarms,
        ReminderPermissionPrompt.EXACT_ALARM_DEGRADED,
    )

    private fun checkPanel(
        capability: ReminderCapabilitySnapshot,
        message: Int,
        action: Int,
        expectedPrompt: ReminderPermissionPrompt,
    ) {
        val doses = FakeDoses(ElderDoseScreen(session = session, available = true, capability = capability))
        var recovered: ReminderPermissionPrompt? = null
        instrumentation.runOnMainSync {
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                setLocale(Locale.US)
            }
            val context = ContextThemeWrapper(
                instrumentation.targetContext.createConfigurationContext(config),
                R.style.Theme_Group33,
            )
            val host = LinearLayout(context)
            val lifecycle = TestLifecycleOwner()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                ElderDosePanel(context, host, doses, lifecycle, scope) { recovered = it }
                val feedback = host.findViewById<TextView>(R.id.elder_reminder_capability_feedback)
                val button = host.findViewById<TextView>(R.id.elder_reminder_permission_action)
                assertEquals(context.getString(message), feedback.text.toString())
                assertEquals(context.getString(action), button.text.toString())
                button.performClick()
                assertEquals(expectedPrompt, recovered)
            } finally {
                lifecycle.destroy()
                scope.cancel()
            }
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.STARTED
        }

        fun destroy() {
            lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }

    private class FakeDoses(initial: ElderDoseScreen) : LiveElderDoses {
        override val state: StateFlow<ElderDoseScreen> = MutableStateFlow(initial)
        override suspend fun refresh() = Unit
        override suspend fun record(
            session: TrustedSessionContext,
            group: ElderDoseGroup,
            kind: DoseActionKind,
        ) = false
    }
}
