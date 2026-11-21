package com.group33.medicationreminder.integration

import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.google.android.material.button.MaterialButton
import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.CaregiverDestination
import com.group33.medicationreminder.caregiver.plan.*
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Reusable business content in the real role Activities, independent of the login implementation. */
internal class OnlineFeatureContent private constructor(
    private val activity: AppCompatActivity,
    host: FrameLayout,
    private val role: UserRole,
    private val model: ChainViewModel,
    private val services: ChainServices,
    private val navigate: (CaregiverDestination) -> Unit,
) {
    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; host.addView(this) }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun section() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; column.addView(this) }
    private fun text(parent: LinearLayout, resource: Int? = null) = TextView(activity).apply {
        textSize = if (role == UserRole.ELDER) 24f else 18f
        setPadding(0, dp(8), 0, dp(8)); resource?.let(::setText); parent.addView(this)
    }
    private fun button(parent: LinearLayout, resource: Int, action: () -> Unit) = MaterialButton(activity).apply {
        setText(resource); minHeight = dp(if (role == UserRole.ELDER) 64 else 56)
        setOnClickListener { action() }; parent.addView(this)
    }
    private val identity = text(column)
    private val status = text(column).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val controls = section()
    private val pairing = section()
    private val requests = section()
    private val relationships = section()
    private val dosePanel = if (role == UserRole.CAREGIVER && services.caregiverDashboard != null && services.doseEvents != null)
        com.group33.medicationreminder.caregiver.CaregiverDosePanel(activity, section(), services.caregiverDashboard, services.doseEvents)
        else null
    private val data = section()
    private val editor = PlanEditorView(activity).apply { visibility = View.GONE; column.addView(this) }
    private val code: TextView
    private val signOutPanel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        host.addView(this)
    }
    private val signOutStatus = text(signOutPanel).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val retrySignOut = button(signOutPanel, R.string.chain_retry_sign_out) { model.logout() }
    private val checkElderSubmission = if (role == UserRole.ELDER)
        button(controls, R.string.chain_recover) { model.recover() } else null
    private var editorKey: String? = null
    private var destination = CaregiverDestination.PLANS
    private var signedIn = false

    init {
        if (role == UserRole.CAREGIVER) {
            button(controls, R.string.chain_refresh) { model.refresh() }
            button(controls, R.string.chain_recover) { model.recover() }
        }
        if (role == UserRole.ELDER) button(pairing, R.string.chain_issue) { model.issue() }
        code = text(pairing)
        if (role == UserRole.CAREGIVER) {
            val input = EditText(activity).apply {
                setHint(R.string.chain_enter_code); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(8)); minHeight = dp(56); pairing.addView(this)
            }
            button(pairing, R.string.chain_pair) { model.request(input.text.toString()) }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
    }

    fun show(destination: CaregiverDestination) {
        this.destination = destination
        render(model.state.value)
    }

    private fun render(state: ChainUi) {
        val user = state.session.user
        if (state.signOut != SignOutStatus.IDLE || user == null || user.role != role) {
            dosePanel?.clear()
            editor.clear(); editorKey = null; column.visibility = View.GONE
            val clearing = state.signOut == SignOutStatus.CLEARING
            val failed = state.signOut == SignOutStatus.FAILED
            signOutPanel.visibility = if (clearing || failed) View.VISIBLE else View.GONE
            if (clearing || failed) {
                signOutStatus.setText(if (clearing) R.string.chain_working else R.string.chain_sign_out_failed)
                retrySignOut.visibility = if (failed) View.VISIBLE else View.GONE
            } else if (state.signOut == SignOutStatus.COMPLETE ||
                signedIn && state.session.readiness == AuthReadiness.SIGNED_OUT) openSharedLogin(activity)
            return
        }
        signOutPanel.visibility = View.GONE
        signedIn = true
        val settings = role == UserRole.ELDER || destination == CaregiverDestination.SETTINGS
        val plans = role == UserRole.ELDER || destination == CaregiverDestination.PLANS
        val records = role == UserRole.CAREGIVER && destination == CaregiverDestination.RECORDS
        column.visibility = if (settings || plans || records) View.VISIBLE else View.GONE
        controls.visibility = if (records) View.GONE else View.VISIBLE
        val active = (state.context as? CaregiverContextState.Active)?.context
        val elder = state.relationships.firstOrNull { it.id == active?.relationshipId }?.elder
        dosePanel?.show(active.takeIf { !settings }, elder, records)
        identity.text = activity.getString(if (role == UserRole.ELDER) R.string.chain_elder_identity else R.string.chain_caregiver_identity, user.displayName)
        status.setText(if (state.busy) R.string.chain_working else state.message)
        // Session/cache refresh is automatic; Elders do not manage synchronization.
        if (role == UserRole.ELDER) {
            val message = when (state.message) {
                R.string.chain_code_ready, R.string.chain_code_expired -> state.message
                R.string.chain_failed -> R.string.chain_elder_failed
                R.string.chain_pending -> R.string.chain_elder_pending
                R.string.chain_confirmed_refresh_required, R.string.chain_rejected, R.string.chain_not_submitted,
                R.string.chain_pairing_accepted, R.string.chain_pairing_declined, R.string.chain_revoked -> state.message
                else -> null
            }
            status.visibility = if (message == null) View.GONE else View.VISIBLE
            message?.let(status::setText)
            checkElderSubmission?.visibility = if (state.hasPendingPairing) View.VISIBLE else View.GONE
        }
        pairing.visibility = if (settings) View.VISIBLE else View.GONE
        requests.visibility = if (settings) View.VISIBLE else View.GONE
        relationships.visibility = if (settings) View.VISIBLE else View.GONE
        data.visibility = if (plans) View.VISIBLE else View.GONE
        code.text = state.code?.let { activity.getString(R.string.chain_code, it.code) }.orEmpty()
        requests.removeAllViews(); relationships.removeAllViews(); data.removeAllViews()
        if (settings && role == UserRole.ELDER) state.requests.filter { it.status == PairingRequestStatus.PENDING }.forEach { request ->
            text(requests).text = activity.getString(R.string.chain_request_from, request.caregiver.displayName)
            button(requests, R.string.chain_accept) { model.respond(request.id, true) }
            button(requests, R.string.chain_reject) { model.respond(request.id, false) }
        }
        if (settings) {
            state.relationships.filter { it.status == CareRelationshipStatus.ACTIVE }.forEach { relationship ->
                if (role == UserRole.CAREGIVER) button(relationships, R.string.chain_choose_elder) { model.select(relationship.id) }
                    .text = activity.getString(R.string.chain_select_name, relationship.elder.displayName)
                button(relationships, R.string.chain_revoke) { editor.clear(); model.revoke(relationship.id) }
            }
            button(relationships, R.string.chain_logout) { editor.clear(); model.logout() }
        }
        if (plans) {
            if (role == UserRole.CAREGIVER && state.context !is CaregiverContextState.Active) {
                button(data, R.string.chain_manage_pairing) { navigate(CaregiverDestination.SETTINGS) }
            } else {
                state.photos.forEachIndexed { i, photo ->
                    if (role == UserRole.CAREGIVER) button(data, R.string.chain_inspect) { model.viewPhoto(photo.id) }
                        .text = activity.getString(R.string.chain_inspect_number, i + 1)
                }
                state.failedPhotoId?.let { id ->
                    text(data, R.string.chain_photo_read_failed).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    button(data, R.string.chain_photo_retry) { model.viewPhoto(id) }
                }
                if (state.plans.isEmpty()) text(data,
                    if (state.plansKnown) R.string.chain_no_plans else R.string.chain_plans_unavailable)
                state.plans.forEach { plan ->
                    val medication = state.medications.firstOrNull { it.id == plan.medicationId } ?: return@forEach
                    text(data).text = activity.getString(R.string.chain_plan, medication.displayName, medication.dosageText,
                        plan.localTimes.joinToString(", "), plan.executionTimeZoneId, plan.startDate,
                        plan.endDate ?: activity.getString(R.string.chain_no_end),
                        activity.getString(if (plan.active) R.string.chain_active else R.string.chain_inactive))
                    medication.instructions?.let { text(data).text = it }
                }
                if (state.stale && role == UserRole.CAREGIVER) text(data, R.string.chain_stale)
            }
        }
        val viewed = state.viewed.takeIf { plans }
        val key = viewed?.let { "${it.scope}:${it.generation}:${it.elderId}:${it.metadata.id}" }
        if (key != editorKey) {
            editorKey = key; editor.clear()
            editor.visibility = if (viewed == null) View.GONE else View.VISIBLE
            if (viewed != null) {
                fun <T : ViewModel> retained(type: Class<T>, name: String, create: () -> T): T =
                    ViewModelProvider(activity, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>) = create() as V
                    })[name, type]
                val form = retained(PlanFormViewModel::class.java, "form:$key") {
                    PlanFormViewModel().apply { updateInput(PlanFormInput(localTimes = listOf("08:00"),
                        startDate = LocalDate.now().toString(), executionTimeZoneId = "Asia/Shanghai")) }
                }
                val save = retained(PlanSaveViewModel::class.java, "save:$key") {
                    PlanSaveViewModel(viewed.elderId, viewed.scope, services.plans, services.commands)
                }
                val active = (state.context as? CaregiverContextState.Active)?.context
                if (active != null) editor.bind(activity, active.elderDisplayName, viewed.metadata, viewed.bitmap,
                    java.time.ZoneId.systemDefault().id, null, form, save)
            }
        }
        listOf(controls, pairing, requests, relationships).forEach { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is Button && child.text != activity.getString(R.string.chain_logout)) child.isEnabled = !state.busy
            }
        }
    }

    fun close() { dosePanel?.clear(); editor.clear() }
    companion object {
        fun install(activity: AppCompatActivity, host: FrameLayout, role: UserRole,
                    navigate: (CaregiverDestination) -> Unit = {}): OnlineFeatureContent? {
            val services = (activity.application as Group33Application).featureServices ?: return null
            return OnlineFeatureContent(activity, host, role, checkNotNull(featureModel(activity)), services, navigate)
        }
    }
}
