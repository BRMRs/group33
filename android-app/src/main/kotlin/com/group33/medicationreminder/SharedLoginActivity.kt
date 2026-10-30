package com.group33.medicationreminder

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.databinding.ActivitySharedLoginBinding
import kotlinx.coroutines.launch

internal fun nextLanguageChoice(displayedLanguage: String): LanguageChoice =
    if (displayedLanguage == "zh") LanguageChoice.ENGLISH else LanguageChoice.SIMPLIFIED_CHINESE

/** Shared visual entry. Phone authentication is wired in the next #10 delivery. */
class SharedLoginActivity : AppCompatActivity() {
    private enum class LoginMode { CHOICE, SMS, PASSWORD, RESET }

    private lateinit var binding: ActivitySharedLoginBinding
    private var mode = LoginMode.CHOICE
    private var alternativesExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharedLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        mode = savedInstanceState?.getString(MODE_KEY)?.let {
            LoginMode.entries.firstOrNull { mode -> mode.name == it }
        } ?: LoginMode.CHOICE
        alternativesExpanded = savedInstanceState?.getBoolean(ALTERNATIVES_KEY) ?: false

        val displayedLanguage = ConfigurationCompat.getLocales(resources.configuration)[0]?.language.orEmpty()
        binding.languageButton.setText(
            if (displayedLanguage == "zh") R.string.language_chinese else R.string.language_english,
        )
        binding.languageButton.contentDescription = getString(R.string.language_switch_action)
        binding.languageButton.setOnClickListener { switchLanguage(displayedLanguage) }
        binding.oneTapButton.setOnClickListener {
            alternativesExpanded = true
            render()
            showStatus(R.string.login_preview_only)
        }
        binding.otherMethodsButton.setOnClickListener {
            alternativesExpanded = !alternativesExpanded
            hideStatus()
            render()
        }
        binding.smsButton.setOnClickListener { showForm(LoginMode.SMS) }
        binding.passwordButton.setOnClickListener { showForm(LoginMode.PASSWORD) }
        binding.backToMethodsButton.setOnClickListener {
            mode = LoginMode.CHOICE
            alternativesExpanded = true
            hideStatus()
            render()
        }
        binding.forgotPasswordButton.setOnClickListener { showForm(LoginMode.RESET) }
        binding.requestCodeButton.setOnClickListener { showStatus(R.string.login_code_preview_only) }
        binding.formSubmitButton.setOnClickListener { showStatus(R.string.login_preview_only) }
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            binding.caregiverNavigationPreviewButton.visibility = View.VISIBLE
            binding.caregiverNavigationPreviewButton.setOnClickListener {
                startActivity(Intent(this, CaregiverHomeActivity::class.java).apply {
                    putExtra("com.group33.medicationreminder.LOCAL_NAV_DEMO", true)
                })
            }
        }
        binding.rolePreviewButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(MODE_KEY, mode.name)
        outState.putBoolean(ALTERNATIVES_KEY, alternativesExpanded)
        super.onSaveInstanceState(outState)
    }

    private fun showForm(nextMode: LoginMode) {
        mode = nextMode
        hideStatus()
        render()
    }

    private fun render() {
        binding.loginChoices.visibility = if (mode == LoginMode.CHOICE) View.VISIBLE else View.GONE
        binding.loginForm.visibility = if (mode == LoginMode.CHOICE) View.GONE else View.VISIBLE
        binding.alternativeMethods.visibility =
            if (alternativesExpanded) View.VISIBLE else View.GONE
        binding.otherMethodsButton.setText(
            if (alternativesExpanded) R.string.login_hide_methods else R.string.login_other_methods,
        )
        if (mode == LoginMode.CHOICE) return

        val usesCode = mode == LoginMode.SMS || mode == LoginMode.RESET
        binding.codeField.visibility = if (usesCode) View.VISIBLE else View.GONE
        binding.requestCodeButton.visibility = if (usesCode) View.VISIBLE else View.GONE
        binding.passwordField.visibility = if (mode == LoginMode.PASSWORD) View.VISIBLE else View.GONE
        binding.forgotPasswordButton.visibility =
            if (mode == LoginMode.PASSWORD) View.VISIBLE else View.GONE
        binding.newPasswordField.visibility = if (mode == LoginMode.RESET) View.VISIBLE else View.GONE
        val title = when (mode) {
            LoginMode.SMS -> R.string.login_sms
            LoginMode.PASSWORD -> R.string.login_password
            LoginMode.RESET -> R.string.login_reset_password
            LoginMode.CHOICE -> error("Choice mode has no form")
        }
        binding.formTitle.setText(title)
        binding.formSubmitButton.setText(
            if (mode == LoginMode.RESET) R.string.login_reset_password_action else title,
        )
    }

    private fun hideStatus() {
        binding.loginStatus.visibility = View.GONE
    }

    private fun showStatus(message: Int) {
        binding.loginStatus.setText(message)
        binding.loginStatus.visibility = View.VISIBLE
        binding.loginStatus.announceForAccessibility(getString(message))
        binding.loginScroll.post { binding.loginScroll.smoothScrollTo(0, binding.loginStatus.bottom) }
    }

    private fun switchLanguage(displayedLanguage: String) {
        val target = nextLanguageChoice(displayedLanguage)
        binding.languageButton.isEnabled = false
        lifecycleScope.launch {
            val settings = (application as Group33Application).languageSettings
            when (settings.setChoice(target)) {
                is Outcome.Success -> Group33Application.applyLanguage(target)
                is Outcome.Failure -> {
                    binding.languageButton.isEnabled = true
                    Toast.makeText(this@SharedLoginActivity, R.string.language_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private companion object {
        const val MODE_KEY = "login_mode"
        const val ALTERNATIVES_KEY = "alternatives_expanded"
    }
}
