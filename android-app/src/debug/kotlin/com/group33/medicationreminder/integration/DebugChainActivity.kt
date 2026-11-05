package com.group33.medicationreminder.integration

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.group33.medicationreminder.BuildConfig
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.plan.*
import com.group33.medicationreminder.core.data.contract.*
import com.group33.medicationreminder.elder.photo.ElderPhotoActivity
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.model.UserRole
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Actual Debug launcher, shared by the two installations; no credentials or arbitrary actor inputs. */
class DebugChainActivity : AppCompatActivity() {
    private val services get() = (application as DebugChainApplication).repository.services
    private val model: ChainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>) = ChainViewModel(services) as T
        }
    }
    private lateinit var column: LinearLayout
    private lateinit var identity: TextView
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var login: LinearLayout
    private lateinit var elder: LinearLayout
    private lateinit var caregiver: LinearLayout
    private lateinit var requests: LinearLayout
    private lateinit var relationships: LinearLayout
    private lateinit var data: LinearLayout
    private lateinit var editor: PlanEditorView
    private var editorKey: String? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun section() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; column.addView(this) }
    private fun text(parent: LinearLayout, resource: Int? = null) = TextView(this).apply {
        textSize = 18f; setPadding(0, dp(8), 0, dp(8)); resource?.let(::setText); parent.addView(this)
    }
    private fun button(parent: LinearLayout, resource: Int, action: () -> Unit) = Button(this).apply {
        setText(resource); minHeight = dp(56); setOnClickListener { action() }; parent.addView(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column) }
        setContentView(scroll)
        androidx.core.view.WindowInsetsControllerCompat(window, scroll).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        text(column, R.string.chain_title)
        text(column).text = getString(R.string.chain_server, BuildConfig.DEBUG_SERVER_URL)
        text(column, R.string.chain_boundary)
        identity = text(column)
        status = text(column).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        login = section()
        val name = EditText(this).apply { setHint(R.string.chain_name); minHeight = dp(56); login.addView(this) }
        button(login, R.string.chain_login_elder) { model.login(UserRole.ELDER, name.text.toString().ifBlank { getString(R.string.chain_demo_elder) }) }
        button(login, R.string.chain_login_caregiver) { model.login(UserRole.CAREGIVER, name.text.toString().ifBlank { getString(R.string.chain_demo_caregiver) }) }
        button(column, R.string.chain_logout) { editor.clear(); model.logout() }
        button(column, R.string.chain_refresh) { model.refresh() }
        button(column, R.string.chain_recover) { model.recover() }
        elder = section()
        button(elder, R.string.chain_issue) { model.issue() }
        code = text(elder)
        button(elder, R.string.chain_capture) { startActivity(Intent(this, ElderPhotoActivity::class.java)) }
        caregiver = section()
        val input = EditText(this).apply {
            setHint(R.string.chain_enter_code); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(8)); minHeight = dp(56); caregiver.addView(this)
        }
        button(caregiver, R.string.chain_pair) { model.request(input.text.toString()) }
        relationships = section(); requests = section(); data = section()
        editor = PlanEditorView(this).apply { visibility = View.GONE; column.addView(this) }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }

    override fun onResume() {
        super.onResume()
        if (model.state.value.session.readiness == AuthReadiness.READY) model.refresh()
    }

    private fun render(state: ChainUi) {
        val user = state.session.user
        identity.text = user?.let { getString(if (it.role == UserRole.ELDER) R.string.chain_elder_identity else R.string.chain_caregiver_identity, it.displayName) }
            ?: getString(R.string.chain_signed_out)
        status.setText(if (state.busy) R.string.chain_working else state.message)
        login.visibility = if (user == null) View.VISIBLE else View.GONE
        elder.visibility = if (user?.role == UserRole.ELDER) View.VISIBLE else View.GONE
        caregiver.visibility = if (user?.role == UserRole.CAREGIVER) View.VISIBLE else View.GONE
        code.text = state.code?.let { getString(R.string.chain_code, it.code) }.orEmpty()
        requests.removeAllViews(); relationships.removeAllViews(); data.removeAllViews()
        if (user?.role == UserRole.ELDER) state.requests.filter { it.status == PairingRequestStatus.PENDING }.forEach { request ->
            text(requests).text = getString(R.string.chain_request_from, request.caregiver.displayName)
            button(requests, R.string.chain_accept) { model.respond(request.id, true) }
            button(requests, R.string.chain_reject) { model.respond(request.id, false) }
        }
        state.relationships.filter { it.status == CareRelationshipStatus.ACTIVE }.forEach { relationship ->
            if (user?.role == UserRole.CAREGIVER) {
                button(relationships, R.string.chain_choose_elder) { model.select(relationship.id) }
                    .text = getString(R.string.chain_select_name, relationship.elder.displayName)
            }
            button(relationships, R.string.chain_revoke) { editor.clear(); model.revoke(relationship.id) }
        }
        if (user?.role == UserRole.CAREGIVER) text(data).text = when (val c = state.context) {
            is CaregiverContextState.Active -> getString(R.string.chain_current_elder, c.context.elderDisplayName)
            is CaregiverContextState.SelectElder -> getString(R.string.chain_choose_elder)
            else -> getString(R.string.chain_no_elder)
        }
        state.photos.forEachIndexed { i, photo ->
            if (user?.role == UserRole.CAREGIVER) button(data, R.string.chain_inspect) { model.viewPhoto(photo.id) }
                .text = getString(R.string.chain_inspect_number, i + 1)
        }
        if (state.plans.isEmpty()) text(data, R.string.chain_no_plans)
        state.plans.forEach { plan ->
            val medication = state.medications.firstOrNull { it.id == plan.medicationId } ?: return@forEach
            text(data).text = getString(R.string.chain_plan, medication.displayName, medication.dosageText,
                plan.localTimes.joinToString(", "), plan.executionTimeZoneId, plan.startDate,
                plan.endDate ?: getString(R.string.chain_no_end),
                getString(if (plan.active) R.string.chain_active else R.string.chain_inactive))
            medication.instructions?.let { text(data).text = it }
        }
        if (state.stale && user != null) text(data, R.string.chain_stale)
        val viewed = state.viewed
        val key = viewed?.let { "${it.scope}:${it.generation}:${it.elderId}:${it.metadata.id}" }
        if (key != editorKey) {
            editorKey = key; editor.clear()
            editor.visibility = if (viewed == null) View.GONE else View.VISIBLE
            if (viewed != null) {
                fun <T : ViewModel> retained(type: Class<T>, name: String, create: () -> T): T =
                    ViewModelProvider(this, object : ViewModelProvider.Factory {
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
                if (active != null) editor.bind(this, active.elderDisplayName, viewed.metadata, viewed.bitmap,
                    java.time.ZoneId.systemDefault().id, null, form, save)
            }
        }
    }
}
