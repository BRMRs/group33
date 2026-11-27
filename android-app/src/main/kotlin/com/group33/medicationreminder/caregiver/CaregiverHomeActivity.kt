package com.group33.medicationreminder.caregiver

import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.databinding.ActivityCaregiverHomeBinding
import com.group33.medicationreminder.nextLanguageChoice
import com.group33.medicationreminder.integration.OnlineFeatureContent
import com.group33.medicationreminder.integration.ChainUi
import com.group33.medicationreminder.integration.SignOutStatus
import com.group33.medicationreminder.integration.featureModel
import com.group33.medicationreminder.core.data.contract.CaregiverContextState
import com.group33.medicationreminder.core.data.contract.TrustedSessionContext
import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.CareRelationshipView
import com.google.android.material.snackbar.Snackbar
import com.group33.shared.model.UserRole
import kotlinx.coroutines.launch

/** C1 navigation host. C2/C3 can replace the content destinations when their screens are ready. */
class CaregiverHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCaregiverHomeBinding
    private val localDemo by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra(EXTRA_LOCAL_NAV_DEMO, false)
    }
    private val viewModel: CaregiverNavigationViewModel by viewModels {
        CaregiverNavigationViewModel.Factory(createCaregiverNavigationContextSource(this, localDemo))
    }
    private var content: OnlineFeatureContent? = null
    private var selectorState: ChainUi? = null
    private var elderOptions: List<CareRelationshipView> = emptyList()
    private var selectorLease: TrustedSessionContext? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaregiverHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.demoBanner.visibility = if (localDemo) View.VISIBLE else View.GONE
        binding.bottomNavigation.itemActiveIndicatorColor = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.group33_navigation_selected),
        )
        // The content scrolls independently; destinations remain available at the bottom.
        ViewCompat.setAccessibilityHeading(binding.pageTitle, true)
        savedInstanceState?.getString(DESTINATION_KEY)?.let { name ->
            CaregiverDestination.entries.firstOrNull { it.name == name }?.let(viewModel::open)
        }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val destination = when (item.itemId) {
                R.id.nav_plans -> CaregiverDestination.PLANS
                R.id.nav_records -> CaregiverDestination.RECORDS
                R.id.nav_settings -> CaregiverDestination.SETTINGS
                else -> return@setOnItemSelectedListener false
            }
            viewModel.open(destination)
            binding.caregiverScroll.scrollTo(0, 0)
            true
        }
        if (!localDemo) content = OnlineFeatureContent.install(this, binding.featureContent, UserRole.CAREGIVER) { target ->
            binding.bottomNavigation.selectedItemId = when (target) {
                CaregiverDestination.PLANS -> R.id.nav_plans
                CaregiverDestination.RECORDS -> R.id.nav_records
                CaregiverDestination.SETTINGS -> R.id.nav_settings
            }
        }
        binding.languageButton.setOnClickListener { switchLanguage() }
        binding.currentElderValue.keyListener = null
        if (!localDemo) featureModel(this)?.let { model ->
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.state.collect { state ->
                        selectorState = state
                        renderElderSelector(viewModel.state.value.context)
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value.destination == CaregiverDestination.PLANS) finish()
                else viewModel.open(CaregiverDestination.PLANS)
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: CaregiverNavigationState) {
        val selectedItem = when (state.destination) {
            CaregiverDestination.PLANS -> R.id.nav_plans
            CaregiverDestination.RECORDS -> R.id.nav_records
            CaregiverDestination.SETTINGS -> R.id.nav_settings
        }
        binding.bottomNavigation.menu.findItem(selectedItem).isChecked = true
        val elder = state.context as? CaregiverNavigationContext.ActiveElder
        renderElderSelector(state.context)
        binding.pageTitle.setText(
            when (state.destination) {
                CaregiverDestination.PLANS -> R.string.caregiver_plans_title
                CaregiverDestination.RECORDS -> R.string.caregiver_records_title
                CaregiverDestination.SETTINGS -> R.string.caregiver_settings_title
            },
        )
        binding.pageMessage.setText(
            when (state.destination) {
                CaregiverDestination.SETTINGS -> R.string.caregiver_settings_intro
                CaregiverDestination.PLANS, CaregiverDestination.RECORDS -> when (state.context) {
                    CaregiverNavigationContext.Unavailable -> R.string.caregiver_context_unavailable
                    CaregiverNavigationContext.NoActiveElder -> R.string.caregiver_choose_or_pair_elder
                    is CaregiverNavigationContext.ActiveElder -> R.string.caregiver_feature_pending
                }
            },
        )
        binding.pageMessage.visibility = if (content != null &&
            (state.destination == CaregiverDestination.SETTINGS ||
                (state.destination == CaregiverDestination.PLANS || state.destination == CaregiverDestination.RECORDS) && elder != null)) View.GONE else View.VISIBLE
        content?.show(state.destination)
        binding.languageButton.visibility = if (state.destination == CaregiverDestination.SETTINGS) View.VISIBLE else View.GONE
        val language = ConfigurationCompat.getLocales(resources.configuration)[0]?.language.orEmpty()
        binding.languageButton.setText(if (language == "zh") R.string.language_chinese else R.string.language_english)
        binding.languageButton.contentDescription = getString(R.string.language_switch_action)
    }

    private fun renderElderSelector(navigation: CaregiverNavigationContext) {
        val state = selectorState
        val services = (application as Group33Application).featureServices
        val lease = services?.trusted?.value
        val options = state?.relationships.orEmpty().filter {
            state?.signOut == SignOutStatus.IDLE && lease != null &&
                it.status == CareRelationshipStatus.ACTIVE && it.caregiverId == lease.scope.userId
        }.sortedByDescending { it.createdAtEpochMillis }
        if (options != elderOptions || lease != selectorLease) {
            binding.currentElderValue.dismissDropDown()
            elderOptions = options
            selectorLease = lease
            binding.currentElderValue.setAdapter(ArrayAdapter(this,
                R.layout.caregiver_elder_dropdown_item, options.map { it.elder.displayName }))
            binding.currentElderValue.setOnItemClickListener { _, _, position, _ ->
                val relationship = options.getOrNull(position) ?: return@setOnItemClickListener
                val current = featureModel(this)?.state?.value ?: return@setOnItemClickListener
                if (lease == null || services?.trusted?.value != lease || current.busy ||
                    current.signOut != SignOutStatus.IDLE || relationship !in current.relationships) return@setOnItemClickListener
                featureModel(this)?.select(relationship.id)
                Snackbar.make(binding.root, getString(R.string.chain_selected_name,
                    relationship.elder.displayName), Snackbar.LENGTH_SHORT).show()
            }
        }
        val active = (state?.context as? CaregiverContextState.Active)?.context
        val label = if (localDemo) (navigation as? CaregiverNavigationContext.ActiveElder)?.displayName
            else active?.elderDisplayName
        binding.currentElderValue.setText(label ?: getString(R.string.caregiver_no_current_elder), false)
        binding.currentElderInput.isEnabled = options.isNotEmpty() && state?.busy != true
        binding.currentElderHint.visibility = if (localDemo && label != null) View.GONE else View.VISIBLE
        binding.currentElderHint.setText(when {
            localDemo && label != null -> R.string.caregiver_preview_relationship
            active != null -> R.string.caregiver_active_relationship
            state?.context is CaregiverContextState.SelectElder -> R.string.caregiver_choose_elder_above
            state?.context is CaregiverContextState.NoActiveElder -> R.string.caregiver_no_active_relationship
            else -> R.string.caregiver_context_unavailable
        })
    }

    override fun onStop() { binding.currentElderValue.dismissDropDown(); super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(DESTINATION_KEY, viewModel.state.value.destination.name)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (!localDemo) com.group33.medicationreminder.integration.featureModel(this)?.let {
            if (it.state.value.session.readiness == com.group33.medicationreminder.core.data.contract.AuthReadiness.READY) it.refresh(reportStatus = false)
        }
    }
    override fun onDestroy() { content?.close(); super.onDestroy() }

    private fun switchLanguage() {
        val language = ConfigurationCompat.getLocales(resources.configuration)[0]?.language.orEmpty()
        val choice = nextLanguageChoice(language)
        binding.languageButton.isEnabled = false
        lifecycleScope.launch {
            val settings = (application as Group33Application).languageSettings
            when (settings.setChoice(choice)) {
                is Outcome.Success -> Group33Application.applyLanguage(choice, this@CaregiverHomeActivity)
                is Outcome.Failure -> {
                    binding.languageButton.isEnabled = true
                    Toast.makeText(this@CaregiverHomeActivity, R.string.language_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private companion object {
        const val DESTINATION_KEY = "caregiver_destination"
        const val EXTRA_LOCAL_NAV_DEMO = "com.group33.medicationreminder.LOCAL_NAV_DEMO"
    }
}

