package com.group33.medicationreminder

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
import com.group33.medicationreminder.auth.LoginFlowState
import com.group33.medicationreminder.auth.LoginFlowViewModel
import com.group33.medicationreminder.auth.LoginMethod
import com.group33.medicationreminder.auth.LoginNotice
import com.group33.medicationreminder.auth.LoginStep
import com.group33.medicationreminder.auth.PhoneAuthError
import com.group33.medicationreminder.auth.createPhoneAuthRepository
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.databinding.ActivitySharedLoginBinding
import com.group33.medicationreminder.elder.ElderHomeActivity
import com.group33.shared.model.UserRole
import kotlinx.coroutines.launch

internal fun nextLanguageChoice(displayedLanguage: String): LanguageChoice =
    if (displayedLanguage == "zh") LanguageChoice.ENGLISH else LanguageChoice.SIMPLIFIED_CHINESE

/** Shared entry. Real phone verification awaits the coordinated #10 authentication contract. */
class SharedLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySharedLoginBinding
    private val localDemo by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra(EXTRA_LOCAL_AUTH_DEMO, false)
    }
    private val viewModel: LoginFlowViewModel by viewModels {
        LoginFlowViewModel.Factory(createPhoneAuthRepository(localDemo))
    }
    private var announcedNotice: LoginNotice? = null

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

        val displayedLanguage = ConfigurationCompat.getLocales(resources.configuration)[0]?.language.orEmpty()
        binding.languageButton.setText(
            if (displayedLanguage == "zh") R.string.language_chinese else R.string.language_english,
        )
        binding.languageButton.contentDescription = getString(R.string.language_switch_action)
        binding.languageButton.setOnClickListener { switchLanguage(displayedLanguage) }
        binding.localDemoNotice.visibility = if (localDemo) View.VISIBLE else View.GONE

        binding.oneTapButton.setOnClickListener { viewModel.oneTap() }
        binding.otherMethodsButton.setOnClickListener { viewModel.toggleAlternatives() }
        binding.smsButton.setOnClickListener { viewModel.showMethod(LoginMethod.SMS) }
        binding.passwordButton.setOnClickListener { viewModel.showMethod(LoginMethod.PASSWORD) }
        binding.backToMethodsButton.setOnClickListener { viewModel.showMethod(LoginMethod.CHOICE) }
        binding.forgotPasswordButton.setOnClickListener { viewModel.showMethod(LoginMethod.RESET) }
        binding.requestCodeButton.setOnClickListener { viewModel.requestSms(binding.phoneInput.text.toString()) }
        binding.formSubmitButton.setOnClickListener { submitForm() }
        binding.registerElderButton.setOnClickListener { viewModel.chooseRole(UserRole.ELDER) }
        binding.registerCaregiverButton.setOnClickListener { viewModel.chooseRole(UserRole.CAREGIVER) }
        binding.cancelRegistrationButton.setOnClickListener { viewModel.cancelRegistration() }
        binding.backToRoleButton.setOnClickListener { viewModel.backToRole() }
        binding.completeRegistrationButton.setOnClickListener {
            viewModel.completeCaregiver(
                binding.displayNameInput.text.toString(),
                binding.registrationPasswordInput.text.toString(),
            )
        }
        binding.openHomeButton.setOnClickListener { openDemoHome() }
        binding.signOutButton.setOnClickListener { viewModel.signOut() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun submitForm() {
        val phone = binding.phoneInput.text.toString()
        when (viewModel.state.value.method) {
            LoginMethod.SMS -> viewModel.verifySms(phone, binding.codeInput.text.toString())
            LoginMethod.PASSWORD -> viewModel.passwordSignIn(phone, binding.passwordInput.text.toString())
            LoginMethod.RESET -> viewModel.resetPassword(
                phone, binding.codeInput.text.toString(), binding.newPasswordInput.text.toString(),
            )
            LoginMethod.CHOICE -> Unit
        }
    }

    private fun render(state: LoginFlowState) {
        val signingIn = state.step == LoginStep.SIGN_IN
        binding.loginHeader.visibility = if (signingIn) View.VISIBLE else View.GONE
        binding.loginDescription.visibility = if (signingIn) View.VISIBLE else View.GONE
        binding.loginChoices.visibility = if (signingIn && state.method == LoginMethod.CHOICE) View.VISIBLE else View.GONE
        binding.loginForm.visibility = if (signingIn && state.method != LoginMethod.CHOICE) View.VISIBLE else View.GONE
        binding.roleSelection.visibility = if (state.step == LoginStep.CHOOSE_ROLE) View.VISIBLE else View.GONE
        binding.caregiverRegistration.visibility = if (state.step == LoginStep.CAREGIVER_DETAILS) View.VISIBLE else View.GONE
        binding.signedInSummary.visibility = if (state.step == LoginStep.SIGNED_IN) View.VISIBLE else View.GONE
        binding.loginProgress.visibility = if (state.busy) View.VISIBLE else View.GONE
        binding.alternativeMethods.visibility = if (state.alternativesExpanded) View.VISIBLE else View.GONE
        binding.otherMethodsButton.setText(
            if (state.alternativesExpanded) R.string.login_hide_methods else R.string.login_other_methods,
        )
        renderForm(state.method)

        listOf(
            binding.oneTapButton, binding.otherMethodsButton, binding.smsButton, binding.passwordButton,
            binding.requestCodeButton, binding.formSubmitButton, binding.registerElderButton,
            binding.registerCaregiverButton, binding.completeRegistrationButton, binding.signOutButton,
        ).forEach { it.isEnabled = !state.busy }

        state.account?.let { account ->
            val role = getString(if (account.role == UserRole.ELDER) R.string.elder_role else R.string.caregiver_role)
            binding.signedInTitle.text = getString(R.string.login_signed_in_as, account.displayName, role)
        }
        binding.openHomeButton.visibility = if (localDemo) View.VISIBLE else View.GONE
        binding.signedInDescription.setText(
            if (localDemo) R.string.login_demo_no_real_session else R.string.login_real_session_pending,
        )

        val notice = state.notice
        binding.loginStatus.visibility = if (notice == null) View.GONE else View.VISIBLE
        if (notice == null) {
            announcedNotice = null
        } else {
            val message = getString(noticeResource(notice))
            binding.loginStatus.text = message
            if (notice != announcedNotice) {
                binding.loginStatus.announceForAccessibility(message)
                binding.loginScroll.post { binding.loginScroll.smoothScrollTo(0, binding.loginStatus.bottom) }
                announcedNotice = notice
            }
        }
    }

    private fun renderForm(method: LoginMethod) {
        val usesCode = method == LoginMethod.SMS || method == LoginMethod.RESET
        binding.codeField.visibility = if (usesCode) View.VISIBLE else View.GONE
        binding.requestCodeButton.visibility = if (usesCode) View.VISIBLE else View.GONE
        binding.passwordField.visibility = if (method == LoginMethod.PASSWORD) View.VISIBLE else View.GONE
        binding.forgotPasswordButton.visibility = if (method == LoginMethod.PASSWORD) View.VISIBLE else View.GONE
        binding.newPasswordField.visibility = if (method == LoginMethod.RESET) View.VISIBLE else View.GONE
        val title = when (method) {
            LoginMethod.SMS -> R.string.login_sms
            LoginMethod.PASSWORD -> R.string.login_password
            LoginMethod.RESET -> R.string.login_reset_password
            LoginMethod.CHOICE -> return
        }
        binding.formTitle.setText(title)
        binding.formSubmitButton.setText(
            if (method == LoginMethod.RESET) R.string.login_reset_password_action else title,
        )
    }

    private fun noticeResource(notice: LoginNotice): Int = when (notice) {
        LoginNotice.InvalidPhone -> R.string.login_invalid_phone
        LoginNotice.InvalidCode -> R.string.login_invalid_code
        LoginNotice.MissingPassword -> R.string.login_missing_password
        LoginNotice.InvalidName -> R.string.login_invalid_name
        LoginNotice.CodeSent -> if (localDemo) R.string.login_demo_code_sent else R.string.login_code_sent
        LoginNotice.PasswordReset -> R.string.login_password_reset_done
        is LoginNotice.AuthFailure -> when (notice.error) {
            PhoneAuthError.NOT_CONFIGURED -> R.string.login_auth_unavailable
            PhoneAuthError.INVALID_PHONE -> R.string.login_invalid_phone
            PhoneAuthError.INVALID_CODE -> R.string.login_invalid_code
            PhoneAuthError.CODE_EXPIRED -> R.string.login_code_expired
            PhoneAuthError.WRONG_PASSWORD -> R.string.login_wrong_password
            PhoneAuthError.PASSWORD_NOT_SET -> R.string.login_password_not_set
            PhoneAuthError.ACCOUNT_NOT_FOUND -> R.string.login_account_not_found
            PhoneAuthError.NETWORK -> R.string.login_network_error
            PhoneAuthError.TOO_MANY_REQUESTS -> R.string.login_too_many_requests
            PhoneAuthError.UNKNOWN -> R.string.error_unknown
        }
    }

    private fun openDemoHome() {
        if (!localDemo) return
        val role = viewModel.state.value.account?.role ?: return
        val destination = if (role == UserRole.ELDER) ElderHomeActivity::class.java else CaregiverHomeActivity::class.java
        startActivity(Intent(this, destination))
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
        const val EXTRA_LOCAL_AUTH_DEMO = "com.group33.medicationreminder.LOCAL_AUTH_DEMO"
    }
}
