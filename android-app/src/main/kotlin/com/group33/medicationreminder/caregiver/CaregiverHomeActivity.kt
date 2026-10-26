package com.group33.medicationreminder.caregiver

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.launch

/** C1 navigation host. C2/C3 can replace the content destinations when their screens are ready. */
class CaregiverHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCaregiverHomeBinding
    private val localDemo by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra(EXTRA_LOCAL_NAV_DEMO, false)
    }
    private val viewModel: CaregiverNavigationViewModel by viewModels {
        CaregiverNavigationViewModel.Factory(createCaregiverNavigationContextSource(localDemo))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaregiverHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.demoBanner.visibility = if (localDemo) View.VISIBLE else View.GONE
        binding.homeButton.setOnClickListener { viewModel.open(CaregiverDestination.HOME) }
        binding.plansButton.setOnClickListener { viewModel.open(CaregiverDestination.PLANS) }
        binding.recordsButton.setOnClickListener { viewModel.open(CaregiverDestination.RECORDS) }
        binding.settingsButton.setOnClickListener { viewModel.open(CaregiverDestination.SETTINGS) }
        binding.languageButton.setOnClickListener { switchLanguage() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value.destination == CaregiverDestination.HOME) finish()
                else viewModel.open(CaregiverDestination.HOME)
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: CaregiverNavigationState) {
        val home = state.destination == CaregiverDestination.HOME
        binding.homeButton.visibility = if (home) View.GONE else View.VISIBLE
        binding.navigationChoices.visibility = if (home) View.VISIBLE else View.GONE
        binding.pageTitle.visibility = if (home) View.GONE else View.VISIBLE
        binding.pageMessage.visibility = if (home) View.GONE else View.VISIBLE
        val elder = state.context as? CaregiverNavigationContext.ActiveElder
        binding.currentElderValue.text = elder?.displayName ?: getString(R.string.caregiver_no_current_elder)
        binding.currentElderHint.setText(
            when (state.context) {
                CaregiverNavigationContext.Unavailable -> R.string.caregiver_context_unavailable
                CaregiverNavigationContext.NoActiveElder -> R.string.caregiver_no_active_relationship
                is CaregiverNavigationContext.ActiveElder ->
                    if (localDemo) R.string.caregiver_preview_relationship else R.string.caregiver_active_relationship
            },
        )
        binding.pageTitle.setText(
            when (state.destination) {
                CaregiverDestination.HOME -> R.string.caregiver_home_title
                CaregiverDestination.PLANS -> R.string.caregiver_plans_title
                CaregiverDestination.RECORDS -> R.string.caregiver_records_title
                CaregiverDestination.SETTINGS -> R.string.caregiver_settings_title
            },
        )
        binding.pageMessage.setText(
            when (state.destination) {
                CaregiverDestination.HOME -> R.string.caregiver_home_intro
                CaregiverDestination.SETTINGS -> R.string.caregiver_settings_intro
                CaregiverDestination.PLANS, CaregiverDestination.RECORDS -> when (state.context) {
                    CaregiverNavigationContext.Unavailable -> R.string.caregiver_context_unavailable
                    CaregiverNavigationContext.NoActiveElder -> R.string.caregiver_choose_or_pair_elder
                    is CaregiverNavigationContext.ActiveElder -> R.string.caregiver_feature_pending
                }
            },
        )
        binding.languageButton.visibility = if (state.destination == CaregiverDestination.SETTINGS) View.VISIBLE else View.GONE
        val language = ConfigurationCompat.getLocales(resources.configuration)[0]?.language.orEmpty()
        binding.languageButton.setText(if (language == "zh") R.string.language_chinese else R.string.language_english)
        binding.languageButton.contentDescription = getString(R.string.language_switch_action)
    }

    private fun switchLanguage() {
        val language = ConfigurationCompat.getLocales(resources.configuration)[0]?.language.orEmpty()
        val choice = nextLanguageChoice(language)
        binding.languageButton.isEnabled = false
        lifecycleScope.launch {
            val settings = (application as Group33Application).languageSettings
            when (settings.setChoice(choice)) {
                is Outcome.Success -> Group33Application.applyLanguage(choice)
                is Outcome.Failure -> {
                    binding.languageButton.isEnabled = true
                    Toast.makeText(this@CaregiverHomeActivity, R.string.language_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private companion object {
        const val EXTRA_LOCAL_NAV_DEMO = "com.group33.medicationreminder.LOCAL_NAV_DEMO"
    }
}

