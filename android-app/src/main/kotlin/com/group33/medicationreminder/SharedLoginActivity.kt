package com.group33.medicationreminder

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
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

/** Shared phone-auth entry; the server verifies Fusion results before account or session creation. */
class SharedLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySharedLoginBinding
    private val localDemo by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra(EXTRA_LOCAL_AUTH_DEMO, false)
    }
    private val viewModel: LoginFlowViewModel by viewModels {
        LoginFlowViewModel.Factory(createPhoneAuthRepository(localDemo, applicationContext))
    }
    private var announcedNotice: LoginNotice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharedLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureKeyboardDismissal()
        viewModel.attachActivity(this)
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }
        binding.root.addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            val availableWidth = root.width - root.paddingLeft - root.paddingRight
            if (availableWidth > 0) {
                val maxWidth = resources.getDimensionPixelSize(R.dimen.group33_content_max_width)
                val targetWidth = minOf(availableWidth, maxWidth)
                val params = binding.loginContentContainer.layoutParams as FrameLayout.LayoutParams
                if (params.width != targetWidth || params.gravity != Gravity.CENTER_HORIZONTAL) {
                    params.width = targetWidth
                    params.gravity = Gravity.CENTER_HORIZONTAL
                    binding.loginContentContainer.layoutParams = params
                }
            }
        }

        val displayedLanguage = ConfigurationCompat.getLocales(resources.configuration)[0]?.language.orEmpty()
        binding.languageButton.setText(
            if (displayedLanguage == "zh") R.string.language_english else R.string.language_chinese,
        )
        binding.languageButton.contentDescription = getString(R.string.language_switch_action)
        binding.languageButton.setOnClickListener { switchLanguage(displayedLanguage) }
        binding.otherMethodsButton.visibility = View.VISIBLE
        // Keep the #59 password route available. The real provider repository still
        // reports unavailable until the password-auth work-plan step is implemented.
        binding.passwordButton.visibility = View.VISIBLE
        binding.registrationPasswordField.visibility = View.VISIBLE

        // Debug builds can enter caregiver navigation without phone authentication.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            binding.caregiverNavigationPreviewButton.visibility = View.VISIBLE
            binding.caregiverNavigationPreviewButton.setOnClickListener {
                startActivity(Intent(this, CaregiverHomeActivity::class.java).apply {
                    putExtra("com.group33.medicationreminder.LOCAL_NAV_DEMO", true)
                })
            }
        }

        binding.oneTapButton.setOnClickListener { startPhoneSignIn { viewModel.oneTap() } }
        binding.otherMethodsButton.setOnClickListener { viewModel.toggleAlternatives() }
        binding.smsButton.setOnClickListener {
            binding.phoneInput.text?.clear()
            binding.codeInput.text?.clear()
            viewModel.showMethod(LoginMethod.SMS)
        }
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
            val password = binding.registrationPasswordInput.text.toString()
            if (!localDemo && password.isNotBlank()) viewModel.showPasswordSetupPending()
            else viewModel.completeCaregiver(binding.displayNameInput.text.toString(), password)
        }
        binding.skipPasswordButton.setOnClickListener {
            binding.registrationPasswordInput.text?.clear()
            viewModel.completeCaregiver(binding.displayNameInput.text.toString(), null)
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
            LoginMethod.SMS -> if (localDemo) viewModel.verifySms(phone, binding.codeInput.text.toString())
                else {
                    hideKeyboard(binding.phoneInput)
                    startPhoneSignIn { viewModel.smsSignIn(phone) }
                }
            LoginMethod.PASSWORD -> if (localDemo) viewModel.passwordSignIn(phone, binding.passwordInput.text.toString())
                else viewModel.showPasswordUnavailable()
            LoginMethod.RESET -> if (localDemo) viewModel.resetPassword(
                phone, binding.codeInput.text.toString(), binding.newPasswordInput.text.toString(),
            ) else startPhoneSignIn { viewModel.resetPasswordWithSms(phone, binding.newPasswordInput.text.toString()) }
            LoginMethod.CHOICE -> Unit
        }
    }

    private fun configureKeyboardDismissal() {
        listOf(
            binding.phoneInput,
            binding.codeInput,
            binding.passwordInput,
            binding.newPasswordInput,
            binding.registrationPasswordInput,
        ).forEach { input ->
            input.setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                    hideKeyboard(view)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun hideKeyboard(view: View) {
        view.clearFocus()
        WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime())
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && ::binding.isInitialized) {
            val focused = currentFocus
            if (focused is EditText) {
                val location = IntArray(2)
                focused.getLocationOnScreen(location)
                val fieldBounds = Rect(
                    location[0], location[1], location[0] + focused.width, location[1] + focused.height,
                )
                if (!fieldBounds.contains(event.rawX.toInt(), event.rawY.toInt())) hideKeyboard(focused)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun render(state: LoginFlowState) {
        val signingIn = state.step == LoginStep.SIGN_IN
        binding.loginHeader.visibility = if (signingIn) View.VISIBLE else View.GONE
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
            binding.skipPasswordButton,
        ).forEach { it.isEnabled = !state.busy }

        state.account?.let { account ->
            val role = getString(if (account.role == UserRole.ELDER) R.string.elder_role else R.string.caregiver_role)
            binding.signedInTitle.text = getString(R.string.login_signed_in_as, account.displayName, role)
        }
        binding.openHomeButton.visibility = View.VISIBLE
        binding.signedInDescription.setText(
            if (localDemo) R.string.login_demo_no_real_session else R.string.login_real_session_pending,
        )

        val notice = state.notice
        if (notice == null) {
            announcedNotice = null
            binding.choiceFeedbackCard.visibility = View.GONE
            binding.formFeedbackCard.visibility = View.GONE
            binding.loginStatus.visibility = View.GONE
        } else {
            val message = getString(noticeResource(notice, state.method, state.lastAuthEntry))
            val isChoice = state.step == LoginStep.SIGN_IN && state.method == LoginMethod.CHOICE
            val card = if (isChoice) binding.choiceFeedbackCard else binding.formFeedbackCard
            val title = noticeTitle(notice, state.method, state.lastAuthEntry, isChoice)
            val titleView = if (isChoice) binding.choiceFeedbackTitle else binding.formFeedbackTitle
            val messageView = if (isChoice) binding.choiceFeedbackMessage else binding.formFeedbackMessage
            titleView.setText(title)
            messageView.text = message
            val isSuccess = notice == LoginNotice.CodeSent || notice == LoginNotice.PasswordReset
            val containerColor = getColor(
                if (isSuccess) R.color.group33_success_container else R.color.group33_error_container,
            )
            val outlineColor = getColor(
                if (isSuccess) R.color.group33_success_outline else R.color.group33_error_outline,
            )
            card.setCardBackgroundColor(containerColor)
            card.strokeColor = outlineColor
            titleView.setTextAppearance(
                if (isSuccess) R.style.TextAppearance_Group33_SuccessTitle else R.style.TextAppearance_Group33_ErrorTitle,
            )
            messageView.setTextAppearance(
                if (isSuccess) R.style.TextAppearance_Group33_SuccessBody else R.style.TextAppearance_Group33_ErrorBody,
            )
            card.visibility = View.VISIBLE
            binding.loginStatus.visibility = if (state.step == LoginStep.CHOOSE_ROLE ||
                state.step == LoginStep.CAREGIVER_DETAILS || state.step == LoginStep.SIGNED_IN
            ) View.VISIBLE else View.GONE
            binding.loginStatus.text = message
            if (notice != announcedNotice) {
                card.announceForAccessibility("${getString(title)}. $message")
                binding.loginScroll.post {
                    val target = if (binding.loginStatus.visibility == View.VISIBLE) binding.loginStatus else card
                    val visibleArea = Rect()
                    target.getDrawingRect(visibleArea)
                    binding.loginScroll.offsetDescendantRectToMyCoords(target, visibleArea)
                    binding.loginScroll.smoothScrollTo(0, visibleArea.top)
                }
                announcedNotice = notice
            }
        }
    }

    private fun renderForm(method: LoginMethod) {
        val usesCode = localDemo && (method == LoginMethod.SMS || method == LoginMethod.RESET)
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
            if (method == LoginMethod.RESET) {
                if (localDemo) R.string.login_reset_password_action else R.string.login_sms_reset_password_action
            } else if (method == LoginMethod.SMS && !localDemo) R.string.login_continue_to_verification
            else R.string.login_sign_in_action,
        )
    }

    private fun noticeTitle(notice: LoginNotice, method: LoginMethod, lastAuthEntry: LoginMethod, isChoice: Boolean): Int = when (notice) {
        LoginNotice.InvalidPhone -> R.string.login_phone_error_title
        LoginNotice.InvalidCode -> R.string.login_code_error_title
        LoginNotice.MissingPassword -> R.string.login_password_error_title
        LoginNotice.InvalidPassword -> R.string.login_password_error_title
        LoginNotice.PasswordSetupPending -> R.string.login_password_setup_pending_title
        LoginNotice.InvalidName -> R.string.login_name_error_title
        LoginNotice.CodeSent -> R.string.login_code_sent_title
        LoginNotice.PasswordReset -> R.string.login_password_reset_title
        is LoginNotice.AuthFailure -> when {
            isChoice && lastAuthEntry == LoginMethod.SMS -> R.string.login_code_unavailable_title
            isChoice -> R.string.login_unavailable_title
            method == LoginMethod.SMS -> R.string.login_code_unavailable_title
            else -> R.string.login_form_unavailable_title
        }
    }

    private fun noticeResource(notice: LoginNotice, method: LoginMethod, lastAuthEntry: LoginMethod): Int = when (notice) {
        LoginNotice.InvalidPhone -> R.string.login_invalid_phone
        LoginNotice.InvalidCode -> R.string.login_invalid_code
        LoginNotice.MissingPassword -> R.string.login_missing_password
        LoginNotice.InvalidPassword -> R.string.login_invalid_new_password
        LoginNotice.PasswordSetupPending -> R.string.login_password_setup_pending
        LoginNotice.InvalidName -> R.string.login_invalid_name
        LoginNotice.CodeSent -> if (localDemo) R.string.login_demo_code_sent else R.string.login_code_sent
        LoginNotice.PasswordReset -> R.string.login_password_reset_done
        is LoginNotice.AuthFailure -> when (notice.error) {
            PhoneAuthError.NOT_CONFIGURED -> when (method) {
                LoginMethod.CHOICE -> if (lastAuthEntry == LoginMethod.SMS) R.string.login_sms_unavailable else R.string.login_auth_unavailable
                LoginMethod.SMS -> R.string.login_sms_unavailable
                LoginMethod.PASSWORD -> R.string.login_password_unavailable
                LoginMethod.RESET -> R.string.login_reset_unavailable
            }
            PhoneAuthError.INVALID_PHONE -> R.string.login_invalid_phone
            PhoneAuthError.PHONE_MISMATCH -> R.string.login_phone_mismatch
            PhoneAuthError.INVALID_CODE -> R.string.login_invalid_code
            PhoneAuthError.CODE_EXPIRED -> R.string.login_code_expired
            PhoneAuthError.WRONG_PASSWORD -> R.string.login_wrong_password
            PhoneAuthError.PASSWORD_NOT_SET -> R.string.login_password_not_set
            PhoneAuthError.ACCOUNT_NOT_FOUND -> R.string.login_account_not_found
            PhoneAuthError.NETWORK -> R.string.login_network_error
            PhoneAuthError.NO_SIM -> R.string.login_no_sim
            PhoneAuthError.CELLULAR_UNAVAILABLE -> R.string.login_cellular_unavailable
            PhoneAuthError.ONE_TAP_UNAVAILABLE -> R.string.login_one_tap_unavailable
            PhoneAuthError.PASSWORD_FEATURE_PENDING -> R.string.login_reset_verified_pending
            PhoneAuthError.TOO_MANY_REQUESTS -> if (method == LoginMethod.SMS || method == LoginMethod.RESET)
                R.string.login_sms_too_many_requests else R.string.login_too_many_requests
            PhoneAuthError.STORAGE -> R.string.login_signout_storage_error
            PhoneAuthError.CANCELLED -> R.string.error_unknown
            PhoneAuthError.UNKNOWN -> R.string.error_unknown
        }
    }

    private fun openDemoHome() {
        val role = viewModel.state.value.account?.role ?: return
        val destination = if (role == UserRole.ELDER) ElderHomeActivity::class.java else CaregiverHomeActivity::class.java
        startActivity(Intent(this, destination))
    }

    private fun startPhoneSignIn(onContinue: () -> Unit) {
        val policyUrl = BuildConfig.PHONE_AUTH_PRIVACY_POLICY_URL
        val localTrial = BuildConfig.DEBUG && BuildConfig.PHONE_AUTH_LOCAL_TRIAL &&
            isLoopbackTrialUrl(BuildConfig.PHONE_AUTH_BASE_URL)
        if (localTrial && !localDemo) {
            AlertDialog.Builder(this)
                .setTitle(R.string.login_trial_privacy_title)
                .setMessage(R.string.login_trial_privacy_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.login_agree_continue) { _, _ -> onContinue() }
                .show()
            return
        }
        if (localDemo || BuildConfig.PHONE_AUTH_BASE_URL.isBlank() || !policyUrl.startsWith("https://")) {
            onContinue()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.login_privacy_title)
            .setMessage(R.string.login_privacy_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.login_view_privacy) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(policyUrl))) }
            }
            .setPositiveButton(R.string.login_agree_continue) { _, _ -> onContinue() }
            .show()
    }

    private fun isLoopbackTrialUrl(url: String): Boolean =
        url.startsWith("http://10.0.2.2:") || url.startsWith("http://127.0.0.1:") ||
            url.startsWith("http://localhost:")

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
