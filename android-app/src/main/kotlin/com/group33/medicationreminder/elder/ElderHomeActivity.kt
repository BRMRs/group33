package com.group33.medicationreminder.elder

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.session.InstallationAnonymousAuthRepository
import com.group33.medicationreminder.databinding.ActivityElderHomeBinding
import com.group33.medicationreminder.databinding.ItemElderUpcomingDoseBinding
import com.group33.shared.model.DoseStatus
import kotlinx.coroutines.launch

class ElderHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityElderHomeBinding
    private val previewClock = ElderDuePreviewCatalog.PREVIEW_CLOCK
    private val doseRepository = PreviewElderDoseRepository(previewClock)
    private val viewModel: ElderHomeViewModel by viewModels {
        ElderHomeViewModel.Factory(
            authRepository = InstallationAnonymousAuthRepository(applicationContext),
            doseRepository = doseRepository,
            clock = previewClock,
            anonymousDisplayName = getString(R.string.elder_anonymous_display_name),
            dueMedicationName = getString(R.string.elder_demo_medication_a),
            upcomingMedicationName = getString(R.string.elder_demo_medication_b),
            dosageText = getString(R.string.elder_demo_dosage_one_tablet),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityElderHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dueTakenButton.setOnClickListener { viewModel.onTaken() }
        binding.dueRemindLaterButton.setOnClickListener { viewModel.onRemindLater() }
        binding.elderExitButton.setOnClickListener { finish() }
        binding.elderSettingsButton.setOnClickListener { showSessionInfo() }
        binding.elderBottomNav.selectedItemId = R.id.elder_nav_home
        binding.elderBottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.elder_nav_home -> true
                R.id.elder_nav_history -> {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.elder_nav_history)
                        .setMessage(R.string.elder_nav_history_pending)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    false
                }
                R.id.elder_nav_profile -> {
                    showSessionInfo()
                    false
                }
                else -> false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> bindState(state) }
            }
        }
    }

    private fun bindState(state: ElderHomeUiState) {
        val due = state.due
        binding.dueCard.isVisible = due != null
        binding.dueEmpty.isVisible = due == null
        if (due != null) {
            binding.dueTime.text = due.plannedTimeText
            binding.dueMedicationName.text = due.medicationName
            binding.dueDosage.text = getString(R.string.elder_dosage_format, due.dosageText)
            val instructions = due.instructions?.takeIf { it.isNotBlank() }
                ?: getString(R.string.elder_instructions_default)
            binding.dueInstructions.text = getString(R.string.elder_instructions_format, instructions)
            binding.dueStatusChip.text = statusLabel(due.status)
            val showActions = state.actionsEnabled &&
                (due.status == DoseStatus.DUE || due.status == DoseStatus.SNOOZED)
            binding.dueTakenButton.isEnabled = showActions
            binding.dueRemindLaterButton.isEnabled = showActions
            binding.dueTakenButton.isVisible = due.status == DoseStatus.DUE || due.status == DoseStatus.SNOOZED
            binding.dueRemindLaterButton.isVisible = due.status == DoseStatus.DUE || due.status == DoseStatus.SNOOZED
            binding.dueNowLabel.setText(
                if (due.status == DoseStatus.MISSED) R.string.elder_window_closed else R.string.elder_due_now_label,
            )
        }

        binding.upcomingList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        state.upcoming.forEach { item ->
            val row = ItemElderUpcomingDoseBinding.inflate(inflater, binding.upcomingList, false)
            row.upcomingName.text = item.medicationName
            row.upcomingMeta.text = getString(
                R.string.elder_upcoming_meta_format,
                item.plannedTimeText,
                item.dosageText,
            )
            row.upcomingStatus.text = getString(R.string.elder_status_not_due_yet)
            binding.upcomingList.addView(row.root)
        }

        val feedback = state.feedback
        binding.actionFeedback.isVisible = feedback != null
        binding.actionFeedback.text = feedback?.let { feedbackText(it) }.orEmpty()
        if (state.loginError && feedback is ElderFeedback.LoginFailed) {
            Toast.makeText(this, R.string.elder_login_failed, Toast.LENGTH_LONG).show()
            viewModel.clearFeedback()
        }
    }

    private fun statusLabel(status: DoseStatus): String = when (status) {
        DoseStatus.DUE -> getString(R.string.elder_status_pending)
        DoseStatus.TAKEN -> getString(R.string.elder_status_taken)
        DoseStatus.SNOOZED -> getString(R.string.elder_status_snoozed)
        DoseStatus.SCHEDULED -> getString(R.string.elder_status_not_due_yet)
        DoseStatus.MISSED -> getString(R.string.elder_status_missed)
    }

    private fun feedbackText(feedback: ElderFeedback): String = when (feedback) {
        ElderFeedback.ActionCompleted -> getString(R.string.elder_action_completed)
        is ElderFeedback.RemindLaterNext ->
            getString(R.string.elder_remind_later_next_format, feedback.timeText)
        ElderFeedback.RemindLaterWindowEnding -> getString(R.string.elder_remind_later_window_ending)
        ElderFeedback.NotRecorded -> getString(R.string.elder_action_not_recorded)
        ElderFeedback.LoginFailed -> getString(R.string.elder_login_failed)
        ElderFeedback.WindowClosed -> getString(R.string.elder_window_closed)
    }

    private fun showSessionInfo() {
        val name = viewModel.uiState.value.sessionDisplayName
            ?: getString(R.string.elder_anonymous_display_name)
        AlertDialog.Builder(this)
            .setTitle(R.string.elder_nav_profile)
            .setMessage(getString(R.string.elder_nav_profile_format, name))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
