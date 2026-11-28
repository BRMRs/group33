package com.group33.medicationreminder.elder

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.group33.medicationreminder.R
import com.group33.medicationreminder.databinding.ElderLiveDoseCardBinding
import com.group33.shared.contract.DoseActionKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ElderDosePanel(
    context: Context,
    private val host: LinearLayout,
    private val doses: LiveElderDoses,
    lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val recoverPermission: (ReminderPermissionPrompt) -> Unit,
) {
    constructor(
        activity: AppCompatActivity,
        host: LinearLayout,
        doses: LiveElderDoses,
    ) : this(
        activity,
        host,
        doses,
        activity,
        activity.lifecycleScope,
        createPermissionRecovery(activity, doses),
    )

    private val card = ElderLiveDoseCardBinding.inflate(LayoutInflater.from(context), host, true)
    private val status = TextView(context).apply { textSize = 20f; host.addView(this) }
    private val feedback = TextView(context).apply {
        textSize = 20f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        host.addView(this)
    }
    private val permissionBanner = LayoutInflater.from(context).inflate(
        R.layout.elder_reminder_permission_banner,
        host,
        true,
    )
    private val capabilityFeedback: TextView = permissionBanner.findViewById(
        R.id.elder_reminder_capability_feedback,
    )
    private val permissionAction: MaterialButton = permissionBanner.findViewById(
        R.id.elder_reminder_permission_action,
    )
    private var saving = false
    private var permissionPrompt = ReminderPermissionPrompt.NONE

    init {
        card.dueTakenButton.setOnClickListener { record(DoseActionKind.TAKEN) }
        card.dueRemindLaterButton.setOnClickListener { record(DoseActionKind.REMIND_LATER) }
        permissionAction.setOnClickListener { recoverPermission(permissionPrompt) }
        scope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                doses.state.collect(::render)
            }
        }
    }

    private fun render(state: ElderDoseScreen) {
        val due = state.due
        host.isVisible = state.session != null
        if (state.session == null) feedback.text = ""
        card.root.isVisible = due != null
        status.isVisible = due == null
        status.setText(if (state.available) R.string.elder_no_dose_due else R.string.elder_dose_unavailable)
        permissionPrompt = state.permissionPrompt()
        val permissionPresentation = permissionPrompt.presentation
        capabilityFeedback.isVisible = state.session != null && permissionPresentation.message != null
        permissionPresentation.message?.let(capabilityFeedback::setText)
        permissionAction.isVisible = state.session != null && permissionPresentation.action != null
        permissionPresentation.action?.let(permissionAction::setText)
        if (due != null) {
            card.dueTime.text = due.occurrences.first().key.localTime
            card.dueMedicationName.text = due.occurrences.joinToString("\n\n") { occurrence ->
                listOfNotNull(occurrence.medication.displayName, occurrence.medication.dosageText,
                    occurrence.medication.instructions?.takeIf { it.isNotBlank() }).joinToString("\n")
            }
            card.dueTakenButton.isEnabled = !saving
            card.dueRemindLaterButton.isEnabled = !saving && due.canSnooze
        }
    }

    private fun record(kind: DoseActionKind) {
        if (saving) return
        val state = doses.state.value
        val session = state.session ?: return
        val due = state.due ?: return
        saving = true
        card.dueTakenButton.isEnabled = false
        card.dueRemindLaterButton.isEnabled = false
        scope.launch {
            val saved = doses.record(session, due, kind)
            saving = false
            val latest = doses.state.value
            if (latest.session == session) {
                feedback.setText(when {
                    !saved -> R.string.elder_action_not_recorded
                    kind == DoseActionKind.TAKEN -> R.string.elder_action_saved_on_device
                    else -> R.string.elder_snoozed_on_device
                })
            }
            render(latest)
        }
    }

    companion object {
        private fun createPermissionRecovery(
            activity: AppCompatActivity,
            doses: LiveElderDoses,
        ): (ReminderPermissionPrompt) -> Unit {
            val permission = activity.registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { activity.lifecycleScope.launch { doses.refresh() } }
            val navigator = ElderReminderPermissionNavigator(activity) {
                permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            return navigator::recover
        }
    }
}
