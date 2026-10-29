package com.group33.medicationreminder.auth

import androidx.lifecycle.ViewModel
import android.app.Activity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.group33.shared.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LoginStep { SIGN_IN, CHOOSE_ROLE, CAREGIVER_DETAILS, SIGNED_IN }
enum class LoginMethod { CHOICE, SMS, PASSWORD, RESET }

sealed interface LoginNotice {
    data object InvalidPhone : LoginNotice
    data object InvalidCode : LoginNotice
    data object MissingPassword : LoginNotice
    data object InvalidName : LoginNotice
    data object CodeSent : LoginNotice
    data object PasswordReset : LoginNotice
    data class AuthFailure(val error: PhoneAuthError) : LoginNotice
}

data class LoginFlowState(
    val step: LoginStep = LoginStep.SIGN_IN,
    val method: LoginMethod = LoginMethod.CHOICE,
    val alternativesExpanded: Boolean = false,
    val busy: Boolean = false,
    val notice: LoginNotice? = null,
    val account: PhoneAccount? = null,
)

/** Client flow only; a real Repository must supply verified accounts and session restoration. */
class LoginFlowViewModel(private val repository: PhoneAuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginFlowState(busy = true))
    val state = mutableState.asStateFlow()
    private var generation = 0

    internal fun attachActivity(activity: Activity) {
        (repository as? ActivityBoundPhoneAuth)?.attach(activity)
    }

    init {
        val request = ++generation
        viewModelScope.launch {
            val account = try { repository.restore() } catch (_: Exception) { null }
            if (request != generation) return@launch
            mutableState.value = if (account == null) LoginFlowState() else
                LoginFlowState(step = LoginStep.SIGNED_IN, account = account)
        }
    }

    fun toggleAlternatives() {
        val current = state.value
        if (current.step != LoginStep.SIGN_IN || current.busy) return
        mutableState.value = current.copy(alternativesExpanded = !current.alternativesExpanded, notice = null)
    }

    fun showMethod(method: LoginMethod) {
        val current = state.value
        if (current.step != LoginStep.SIGN_IN || current.busy) return
        mutableState.value = current.copy(method = method, alternativesExpanded = true, notice = null)
    }

    fun oneTap() {
        val current = state.value
        if (current.step != LoginStep.SIGN_IN || current.busy) return
        runAuth { repository.oneTap() }
    }

    fun requestSms(phone: String) {
        if (!validPhone(phone)) return notice(LoginNotice.InvalidPhone)
        runAction { repository.requestSms(phone.trim()) }
    }

    fun verifySms(phone: String, code: String) {
        if (!validPhone(phone)) return notice(LoginNotice.InvalidPhone)
        if (!validCode(code)) return notice(LoginNotice.InvalidCode)
        runAuth { repository.verifySms(phone.trim(), code.trim()) }
    }

    fun passwordSignIn(phone: String, password: String) {
        if (!validPhone(phone)) return notice(LoginNotice.InvalidPhone)
        if (password.isBlank()) return notice(LoginNotice.MissingPassword)
        runAuth { repository.passwordSignIn(phone.trim(), password) }
    }

    fun resetPassword(phone: String, code: String, newPassword: String) {
        if (!validPhone(phone)) return notice(LoginNotice.InvalidPhone)
        if (!validCode(code)) return notice(LoginNotice.InvalidCode)
        if (newPassword.isBlank()) return notice(LoginNotice.MissingPassword)
        runAction(onSuccess = {
            mutableState.value = state.value.copy(method = LoginMethod.PASSWORD, notice = LoginNotice.PasswordReset)
        }) { repository.resetPassword(phone.trim(), code.trim(), newPassword) }
    }

    fun chooseRole(role: UserRole) {
        if (state.value.step != LoginStep.CHOOSE_ROLE || state.value.busy) return
        if (role == UserRole.CAREGIVER) {
            mutableState.value = state.value.copy(step = LoginStep.CAREGIVER_DETAILS, notice = null)
        } else {
            runAuth { repository.register(UserRole.ELDER, null, null) }
        }
    }

    fun completeCaregiver(displayName: String, password: String?) {
        if (state.value.step != LoginStep.CAREGIVER_DETAILS || state.value.busy) return
        val name = displayName.trim()
        if (name.isEmpty() || name.length > 40) return notice(LoginNotice.InvalidName)
        runAuth { repository.register(UserRole.CAREGIVER, name, password?.takeIf { it.isNotBlank() }) }
    }

    fun backToRole() {
        if (state.value.step == LoginStep.CAREGIVER_DETAILS && !state.value.busy) {
            mutableState.value = state.value.copy(step = LoginStep.CHOOSE_ROLE, notice = null)
        }
    }

    fun cancelRegistration() {
        if (state.value.step == LoginStep.CHOOSE_ROLE && !state.value.busy) signOut()
    }

    fun signOut() {
        generation++ // A response from a previous account cannot restore it after sign-out.
        mutableState.value = LoginFlowState(busy = true)
        val request = generation
        viewModelScope.launch {
            try { repository.signOut() } catch (_: Exception) {
                // Local presentation must still be cleared even if remote sign-out fails.
            } finally {
                if (request == generation) mutableState.value = LoginFlowState()
            }
        }
    }

    private fun runAuth(block: suspend () -> PhoneAuthResult) {
        if (state.value.busy) return
        val request = ++generation
        mutableState.value = state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            val result = try { block() } catch (_: Exception) { PhoneAuthResult.Failed(PhoneAuthError.UNKNOWN) }
            if (request != generation) return@launch
            val current = state.value
            mutableState.value = when (result) {
                is PhoneAuthResult.SignedIn -> current.copy(
                    step = LoginStep.SIGNED_IN, account = result.account, busy = false, notice = null,
                )
                PhoneAuthResult.RegistrationRequired -> current.copy(
                    step = LoginStep.CHOOSE_ROLE, busy = false, notice = null,
                )
                is PhoneAuthResult.Failed -> current.copy(
                    busy = false,
                    alternativesExpanded = current.alternativesExpanded || current.method == LoginMethod.CHOICE,
                    notice = LoginNotice.AuthFailure(result.error),
                )
            }
        }
    }

    private fun runAction(onSuccess: () -> Unit = { notice(LoginNotice.CodeSent) }, block: suspend () -> PhoneActionResult) {
        if (state.value.busy) return
        val request = ++generation
        mutableState.value = state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            val result = try { block() } catch (_: Exception) { PhoneActionResult.Failed(PhoneAuthError.UNKNOWN) }
            if (request != generation) return@launch
            mutableState.value = state.value.copy(busy = false)
            when (result) {
                PhoneActionResult.Success -> onSuccess()
                is PhoneActionResult.Failed -> notice(LoginNotice.AuthFailure(result.error))
            }
        }
    }

    private fun notice(value: LoginNotice) {
        if (!state.value.busy) mutableState.value = state.value.copy(notice = value)
    }

    private fun validPhone(value: String) = value.trim().matches(Regex("\\+?[0-9]{8,15}"))
    private fun validCode(value: String) = value.trim().matches(Regex("[0-9]{4,8}"))

    class Factory(private val repository: PhoneAuthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginFlowViewModel(repository) as T
    }
}
