package com.group33.medicationreminder.integration

import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.CaregiverDestination
import com.group33.medicationreminder.caregiver.CaregiverFeedbackContent
import com.group33.medicationreminder.caregiver.CaregiverFeedbackPage
import com.group33.medicationreminder.caregiver.CaregiverHistoryHost
import com.group33.medicationreminder.caregiver.PairingRequestScreen
import com.group33.medicationreminder.caregiver.PairingWorkflowViewModel
import com.group33.medicationreminder.caregiver.plan.*
import com.group33.medicationreminder.caregiver.photo.*
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Reusable business content in the real role Activities, independent of the login implementation. */
internal class OnlineFeatureContent internal constructor(
    private val activity: AppCompatActivity,
    host: FrameLayout,
    private val role: UserRole,
    private val model: ChainViewModel,
    private val services: ChainServices,
    private val navigate: (CaregiverDestination) -> Unit,
    // Explicit caller-supplied dates only. No default time-zone policy is selected here.
    private val feedbackRange: ((CaregiverElderContext) -> LocalDateRange?)? = null,
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
    private var renderedRelationships: List<CareRelationshipView>? = null
    private var revokeFeedback: Snackbar? = null
    private val dosePanel = if (role == UserRole.CAREGIVER && services.caregiverDashboard != null && services.doseEvents != null)
        com.group33.medicationreminder.caregiver.CaregiverDosePanel(activity, section(), services.caregiverDashboard, services.doseEvents)
        else null
    private val recordsContainer = if (role == UserRole.CAREGIVER &&
        services.caregiverDashboard != null && services.doseEvents != null
    ) requireNotNull(activity.findViewById<FrameLayout>(R.id.caregiver_records_content)) {
        "Full caregiver feedback requires the shared records content container"
    } else null
    private val sharedScroll = activity.findViewById<View>(R.id.caregiver_scroll)
    private val feedbackSlot = recordsContainer?.let { FrameLayout(activity).apply {
        visibility = View.GONE
        column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    } }
    private var feedbackContext: CaregiverElderContext? = null
    private var feedbackDates: LocalDateRange? = null
    private var feedbackDefaultDates: LocalDateRange? = null
    private val history = recordsContainer?.let { CaregiverHistoryHost(activity) }
    private var historySync: CaregiverHistorySyncRepository? = null
    private var historySyncContext: CaregiverElderContext? = null
    private val feedback = feedbackSlot?.let { slot ->
        CaregiverFeedbackContent(activity, slot, checkNotNull(services.caregiverDashboard), checkNotNull(services.doseEvents),
            parentScrollsOverview = true) { elder, dates ->
            val active = (model.state.value.context as? CaregiverContextState.Active)?.context
            if (active != null && feedbackContext?.isCurrentFor(active) == true && active.elderId == elder) {
                feedbackDates = dates
                navigate(CaregiverDestination.RECORDS)
            }
        }
    }
    private val data = section()
    private val selectedPhoto = section()
    private val photoPreview = ImageView(activity).apply {
        adjustViewBounds = true
        selectedPhoto.addView(this, LinearLayout.LayoutParams(-1, dp(220)))
    }
    private val createPlan = button(selectedPhoto, R.string.browse_create_plan) { model.createPlanForViewedPhoto() }
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
    private val editorModels = ViewModelProvider(activity)[CaregiverEditorModels::class.java]
    private var destination = CaregiverDestination.PLANS
    private var signedIn = false
    private var closed = false
    private var stateCollection: Job? = null

    init {
        if (role == UserRole.CAREGIVER) {
            column.removeView(relationships)
            column.addView(relationships, column.indexOfChild(controls) + 1)
            button(controls, R.string.chain_refresh) { model.refresh() }
            button(controls, R.string.chain_recover) { model.recover() }
        }
        if (role == UserRole.ELDER) button(pairing, R.string.chain_issue) { model.issue() }
        code = text(pairing)
        if (role == UserRole.CAREGIVER) {
            val form = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PairingWorkflowViewModel(services.pairing, services.commands, services.auth, services.trusted) as T
            })[PairingWorkflowViewModel::class.java]
            pairing.addView(PairingRequestScreen(activity, form).view)
        }
        stateCollection = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
    }

    fun show(destination: CaregiverDestination) {
        if (closed) return
        this.destination = destination
        render(model.state.value)
    }

    private fun render(state: ChainUi) {
        val user = state.session.user
        if (state.signOut != SignOutStatus.IDLE || user == null || user.role != role) {
            editorModels.select(null)
            dosePanel?.clear()
            clearFeedback()
            editor.clear(); editorKey = null; photoPreview.setImageDrawable(null); column.visibility = View.GONE
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
        if (editorModels.select(active)) {
            editor.clear()
            editorKey = null
            editor.visibility = View.GONE
        }
        val elder = state.relationships.firstOrNull { it.id == active?.relationshipId }?.elder
        // Missing default input never authorizes the old UTC-based panel query.
        // The future date owner rebinds this host when its scoped input changes.
        if (feedback != null && active != null && !settings) {
            val supplied = feedbackRange?.invoke(active)
            if (feedbackContext?.isCurrentFor(active) != true || feedbackDefaultDates != supplied) {
                feedbackDefaultDates = supplied
                feedbackDates = supplied
                feedbackContext = active
            }
            val dates = feedbackDates
            val latest = (model.state.value.context as? CaregiverContextState.Active)?.context
            if (latest?.let(active::isCurrentFor) != true) clearFeedback()
            else if (records) {
                feedback.clear()
                val current = checkNotNull(history)
                if (current.parent == null) checkNotNull(recordsContainer).addView(current,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (historySyncContext?.isCurrentFor(active) != true) {
                    historySyncContext = active
                    historySync = CaregiverHistorySyncRepository(checkNotNull(services.doseEvents),
                        checkNotNull(services.caregiverDashboard), active.elderId) {
                        val stateNow = model.state.value
                        val contextNow = (stateNow.context as? CaregiverContextState.Active)?.context
                        !closed && destination == CaregiverDestination.RECORDS &&
                            stateNow.signOut == SignOutStatus.IDLE &&
                            contextNow?.let(active::isCurrentFor) == true
                    }
                }
                current.bind(activity, checkNotNull(historySync), active, dates, activity.supportFragmentManager)
                showRecordsContainer(true)
            } else {
                clearHistory()
                showRecordsContainer(false)
                if (dates != null) feedback.show(CaregiverFeedbackPage.OVERVIEW, active, dates)
                else feedback.clear()
            }
        } else clearFeedback()
        dosePanel?.show(null, null, records)
        identity.text = activity.getString(if (role == UserRole.ELDER) R.string.chain_elder_identity else R.string.chain_caregiver_identity, user.displayName)
        status.setText(if (state.busy) R.string.chain_working else state.message)
        if (role == UserRole.CAREGIVER && active != null && !settings && feedbackDates == null) {
            status.setText(R.string.c1_feedback_date_unavailable)
        }
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
        requests.removeAllViews(); data.removeAllViews()
        if (settings && role == UserRole.ELDER) state.requests.filter { it.status == PairingRequestStatus.PENDING }.forEach { request ->
            text(requests).text = activity.getString(R.string.chain_request_from, request.caregiver.displayName)
            button(requests, R.string.chain_accept) { model.respond(request.id, true) }
            button(requests, R.string.chain_reject) { model.respond(request.id, false) }
        }
        if (settings) {
            val available = state.relationships.filter { it.status == CareRelationshipStatus.ACTIVE }
            val managed = if (role == UserRole.CAREGIVER)
                available.filter { it.id == active?.relationshipId } else available
            // Settings manages the selected relationship; selection has one fixed dropdown.
            if (managed != renderedRelationships) {
                renderedRelationships = managed
                relationships.removeAllViews()
                if (role == UserRole.CAREGIVER && managed.isEmpty())
                    text(relationships, R.string.caregiver_choose_elder_above)
                managed.forEach { relationship ->
                    if (role == UserRole.CAREGIVER)
                        text(relationships).text = activity.getString(R.string.chain_selected_name,
                            relationship.elder.displayName)
                    button(relationships, R.string.chain_revoke) {
                        editor.clear()
                        revokeFeedback?.dismiss()
                        revokeFeedback = Snackbar.make(column, R.string.chain_working, Snackbar.LENGTH_INDEFINITE)
                            .also { it.show() }
                        model.revoke(relationship.id)
                    }
                }
                button(relationships, R.string.chain_logout) { editor.clear(); model.logout() }
            }
        }
        if (!state.busy) revokeFeedback?.let { feedback ->
            feedback.setText(state.message).setDuration(Snackbar.LENGTH_LONG).show()
            revokeFeedback = null
        }
        if (plans) {
            if (role == UserRole.CAREGIVER && state.context !is CaregiverContextState.Active) {
                button(data, R.string.chain_manage_pairing) { navigate(CaregiverDestination.SETTINGS) }
            } else {
                if (role == UserRole.CAREGIVER) renderPhotos(state)
                renderPlans(state)
            }
        }
        val visiblePhoto = state.viewed.takeIf { plans && role == UserRole.CAREGIVER }
        val editing = visiblePhoto != null && state.editorPhotoId == visiblePhoto.metadata.id
        selectedPhoto.visibility = if (visiblePhoto != null && !editing) View.VISIBLE else View.GONE
        photoPreview.setImageBitmap(visiblePhoto?.bitmap.takeUnless { editing })
        photoPreview.contentDescription = visiblePhoto?.let { activity.getString(R.string.c2_editor_photo_description,
            (state.context as? CaregiverContextState.Active)?.context?.elderDisplayName.orEmpty()) }
        val viewed = visiblePhoto.takeIf { editing }
        val key = viewed?.let { "${it.scope}:${it.generation}:${(state.context as? CaregiverContextState.Active)?.context?.relationshipId}:${it.elderId}:${it.metadata.id}" }
        if (key != editorKey) {
            editorKey = key; editor.clear()
            editor.visibility = if (viewed == null) View.GONE else View.VISIBLE
            if (viewed != null) {
                fun <T : ViewModel> retained(type: Class<T>, name: String, create: () -> T): T =
                    ViewModelProvider(editorModels, object : ViewModelProvider.Factory {
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

    private fun showRecordsContainer(show: Boolean) {
        val records = recordsContainer ?: return
        records.visibility = if (show) View.VISIBLE else View.GONE
        sharedScroll?.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun clearFeedback() {
        clearHistory()
        showRecordsContainer(false)
        feedbackContext = null
        feedbackDates = null
        feedbackDefaultDates = null
        feedback?.clear()
    }

    private fun clearHistory() {
        historySync = null
        historySyncContext = null
        history?.let { current ->
            current.bind(activity, checkNotNull(services.doseEvents), null, null, activity.supportFragmentManager)
            (current.parent as? ViewGroup)?.removeView(current)
        }
    }

    fun close() {
        closed = true
        stateCollection?.cancel()
        stateCollection = null
        clearFeedback()
        dosePanel?.clear()
        editor.clear()
        revokeFeedback?.dismiss(); revokeFeedback = null
        photoPreview.setImageDrawable(null)
    }

    private fun renderPhotos(state: ChainUi) {
        val photo = state.photo
        text(data, R.string.browse_photos_title).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
        val message = when {
            photo.observationError != null -> R.string.browse_photos_unavailable
            photo.loading -> R.string.browse_loading
            photo.access == AccessState.REVOKED -> R.string.browse_access_revoked
            photo.access != AccessState.AUTHORIZED -> R.string.browse_access_unknown
            !photo.hasLocalData -> R.string.browse_no_local_data
            photo.photos.isEmpty() -> R.string.browse_photos_empty
            else -> null
        }
        message?.let { text(data, it).id = R.id.photo_browser_status }
        if (photo.observationError != null) button(data, R.string.browse_retry_observation) { model.retryPhotoObservation() }
        photo.photos.forEachIndexed { i, metadata ->
            button(data, R.string.chain_inspect) { model.viewPhoto(metadata.id) }
                .text = activity.getString(R.string.chain_view_photo_number, i + 1)
        }
        when {
            state.photoDecodeFailed -> {
                text(data, R.string.browse_photo_decode_failed)
                button(data, R.string.browse_retry_photo) { model.retryPhotoDecode() }
            }
            photo.contentStatus == PhotoContentStatus.LOADING ||
                photo.contentStatus == PhotoContentStatus.AVAILABLE && state.viewed == null -> text(data, R.string.browse_photo_loading)
            photo.contentStatus == PhotoContentStatus.FAILED -> {
                text(data, R.string.browse_photo_failed)
                button(data, R.string.browse_retry_photo) { model.retryPhotoContent() }
            }
        }
        if (photo.isStale && photo.hasLocalData) text(data, R.string.chain_stale)
    }

    private fun renderPlans(state: ChainUi) {
        val browser = state.browser
        val browserMessage = if (browser.message == PlanBrowserMessage.EMPTY && browser.plans?.isStale == true)
            PlanBrowserMessage.UNAVAILABLE else browser.message
        if (role == UserRole.CAREGIVER) text(data, R.string.browse_plans_title)
            .apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
        val message = when (browserMessage) {
            PlanBrowserMessage.SELECT_ELDER -> R.string.chain_manage_pairing
            PlanBrowserMessage.LOADING -> R.string.browse_loading
            PlanBrowserMessage.ACCESS_UNKNOWN -> R.string.browse_access_unknown
            PlanBrowserMessage.REVOKED -> R.string.browse_access_revoked
            PlanBrowserMessage.UNAVAILABLE -> R.string.browse_plans_unavailable
            PlanBrowserMessage.NO_LOCAL_DATA -> R.string.browse_no_local_data
            PlanBrowserMessage.EMPTY -> R.string.browse_plans_empty
            PlanBrowserMessage.RESULTS -> null
        }
        if (role == UserRole.CAREGIVER) {
            message?.let { text(data, it).id = R.id.plan_browser_status }
            if (browser.observationFailed) button(data, R.string.browse_retry_observation) { model.retryPlanObservation() }
        }
        val medications = browser.medications?.value.orEmpty()
        browser.items.forEach { plan ->
            val medication = medications.firstOrNull { it.id == plan.medicationId }
            if (role == UserRole.ELDER) renderPlan(plan, medication)
            else button(data, R.string.browse_plan_details) { model.selectPlan(plan.id) }.apply {
                text = activity.getString(R.string.browse_plan_row, medication?.displayName ?: activity.getString(R.string.browse_medication_unknown),
                    medication?.dosageText ?: activity.getString(R.string.browse_medication_unknown), plan.localTimes.joinToString(", "))
                isSelected = browser.selectedPlanId == plan.id
            }
        }
        if (role != UserRole.CAREGIVER) {
            val elderMessage = when (browserMessage) {
                PlanBrowserMessage.RESULTS -> null
                PlanBrowserMessage.EMPTY -> R.string.browse_elder_empty
                PlanBrowserMessage.LOADING -> R.string.browse_elder_loading
                else -> R.string.browse_elder_unavailable
            }
            elderMessage?.let { text(data, it).id = R.id.plan_browser_status }
            return
        }
        browser.details?.let { details ->
            text(data, R.string.browse_plan_details).id = R.id.plan_details_heading
            renderPlan(details.plan, details.medication)
            val photoId = details.medication?.photoId
            if (photoId != null && state.photo.photos.any { it.id == photoId })
                button(data, R.string.browse_plan_photo) { model.viewPhoto(photoId) }
            button(data, R.string.browse_close_details) { model.selectPlan(null) }
        }
        if (listOfNotNull(browser.plans, browser.medications).any { it.isStale && it.hasLocalData })
            text(data, R.string.chain_stale)
        val previewMessage = when (browser.previewMessage) {
            PlanPreviewMessage.RESULTS -> R.string.browse_preview_results
            PlanPreviewMessage.EMPTY -> R.string.browse_preview_empty
            PlanPreviewMessage.WAITING_FOR_MATCHING_PLANS -> R.string.browse_preview_waiting
            PlanPreviewMessage.LOADING -> R.string.browse_loading
            else -> R.string.browse_preview_unavailable
        }
        text(data, previewMessage).id = R.id.plan_preview_status
        browser.candidates.forEach { candidate -> text(data).text = activity.getString(R.string.browse_preview_candidate,
            candidate.occurrence.localDate, candidate.occurrence.localTime, candidate.executionTimeZoneId) }
    }

    private fun renderPlan(plan: ReminderPlanView, medication: MedicationView?) {
        if (medication == null) { text(data, R.string.browse_medication_unknown); return }
        text(data).text = activity.getString(R.string.chain_plan, medication.displayName, medication.dosageText,
            plan.localTimes.joinToString(", "), plan.executionTimeZoneId, plan.startDate,
            plan.endDate ?: activity.getString(R.string.chain_no_end),
            activity.getString(if (plan.active) R.string.chain_active else R.string.chain_inactive))
        medication.instructions?.let { text(data).text = it }
    }

    companion object {
        fun install(activity: AppCompatActivity, host: FrameLayout, role: UserRole,
                    feedbackRange: ((CaregiverElderContext) -> LocalDateRange?)? = null,
                    navigate: (CaregiverDestination) -> Unit = {}): OnlineFeatureContent? {
            val services = (activity.application as Group33Application).featureServices ?: return null
            return OnlineFeatureContent(activity, host, role, checkNotNull(featureModel(activity)), services, navigate, feedbackRange)
        }
    }
}
