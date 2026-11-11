package com.group33.medicationreminder.elder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.group33.medicationreminder.R
import com.group33.medicationreminder.databinding.ElderLiveDoseCardBinding
import com.group33.shared.contract.DoseActionKind
import kotlinx.coroutines.launch

internal class ElderDosePanel(
    private val activity: ElderHomeActivity,
    private val host: LinearLayout,
    private val doses: LiveElderDoses,
) {
    private val card = ElderLiveDoseCardBinding.inflate(activity.layoutInflater, host, true)
    private val status = TextView(activity).apply { textSize = 20f; host.addView(this) }
    private val feedback = TextView(activity).apply {
        textSize = 20f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        host.addView(this)
    }
    private val enable = MaterialButton(activity).apply {
        minHeight = (64 * resources.displayMetrics.density).toInt()
        setText(R.string.elder_enable_dose_notifications)
        host.addView(this)
    }
    private var saving = false
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        activity.lifecycleScope.launch { doses.refresh() }
    }

    init {
        card.dueTakenButton.setOnClickListener { record(DoseActionKind.TAKEN) }
        card.dueRemindLaterButton.setOnClickListener { record(DoseActionKind.REMIND_LATER) }
        enable.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)))
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
        enable.isVisible = state.session != null && !state.notificationsEnabled
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
        activity.lifecycleScope.launch {
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
}
