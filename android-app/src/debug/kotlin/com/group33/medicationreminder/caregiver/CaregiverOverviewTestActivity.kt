package com.group33.medicationreminder.caregiver

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.Cached
import com.group33.medicationreminder.core.data.contract.CaregiverDashboardRepository
import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.medicationreminder.core.data.contract.ElderOverview
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.core.data.contract.SyncSummary
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.shared.contract.DeliveryCondition
import com.group33.shared.contract.ExactAlarmAccess
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.ReminderCapabilityView
import com.group33.shared.contract.TimeResolutionPolicy
import com.group33.shared.model.DoseStatus
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Debug-only host for visual review. All displayed data is fictional and refresh never uses a service. */
class CaregiverOverviewTestActivity : AppCompatActivity() {
    private val repository = PreviewRepository()
    private val scope = RepositoryScope("debug-preview", "debug-installation", "debug-caregiver")
    private val elderId = "debug-elder"
    private val range = LocalDateRange("2026-10-01", "2026-10-30")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 8.dp)
        }
        val notice = TextView(this).apply {
            text = "DEBUG PREVIEW - fictional sample data; no account or server connection"
            textSize = 14f
            contentDescription = text
        }
        root.addView(notice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val overview = CaregiverOverviewView(this)
        root.addView(overview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(16.dp + bars.left, 12.dp + bars.top, 16.dp + bars.right, 8.dp + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)

        val model = ViewModelProvider(this, PreviewViewModelFactory(repository, scope, elderId, range))
            .get(CaregiverFeedbackViewModel::class.java)
        overview.bind(this, model) { _, _ ->
            Toast.makeText(this, "History navigation is not connected in this debug preview.", Toast.LENGTH_SHORT).show()
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private class PreviewViewModelFactory(
        private val repository: CaregiverDashboardRepository,
        private val scope: RepositoryScope,
        private val elderId: String,
        private val range: LocalDateRange,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CaregiverFeedbackViewModel::class.java))
            return CaregiverFeedbackViewModel(repository, elderId, range, scope) as T
        }
    }

    private class PreviewRepository : CaregiverDashboardRepository {
        private val occurrence = OccurrenceKey("debug-plan", "2026-10-21", "08:00")
        private val event = DoseEventState(
            occurrence = occurrence,
            serverEventId = "debug-event",
            generationEvidence = OccurrenceGenerationEvidence(
                generationId = "debug-generation",
                occurrence = occurrence,
                planRevision = 1,
                generatedAtEpochMillis = 1_792_584_000_000L,
                executionTimeZoneId = "Asia/Shanghai",
                plannedAtEpochMillis = 1_792_612_800_000L,
                resolvedOffsetSeconds = 28_800,
                resolutionPolicyVersion = TimeResolutionPolicy.P0_TIME_V1,
                sourceSnapshotVersion = "debug-only",
                sourceSnapshotServerAtEpochMillis = 1_792_584_000_000L,
            ),
            medicationId = "debug-medication",
            medicationSnapshot = MedicationText("Fictional test medication", "One tablet", "Debug preview only"),
            responseDeadlineEpochMillis = 1_792_620_000_000L,
            effectiveStatus = DoseStatus.MISSED,
            lastAcceptedStatus = DoseStatus.MISSED,
            localReports = emptyList(),
        )
        private val capability = ReminderCapabilityView(
            reportId = "debug-capability",
            observationSequence = 1,
            observedAtEpochMillis = 1_792_584_000_000L,
            deliveryCondition = DeliveryCondition.DEGRADED,
            notificationPermissionGranted = true,
            channelEnabled = false,
            exactAlarmAccess = ExactAlarmAccess.DENIED,
            reasons = emptyList(),
            receivedAtEpochMillis = 1_792_584_008_000L,
        )
        private val cached = Cached(
            value = ElderOverview(
                elder = User("debug-elder", "Demo elder", UserRole.ELDER),
                medications = emptyList(),
                events = listOf(event),
                capability = capability,
            ),
            hasLocalData = true,
            lastSuccessfulSyncAtEpochMillis = 1_792_584_000_000L,
            isStale = true,
            access = AccessState.AUTHORIZED,
        )
        private val overview = MutableStateFlow(cached)

        override fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>> = overview

        override suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary> =
            Outcome.Failure(RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE))
    }
}
