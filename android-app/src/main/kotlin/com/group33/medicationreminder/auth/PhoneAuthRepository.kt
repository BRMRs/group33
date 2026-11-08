package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import android.app.Activity

internal interface ActivityBoundPhoneAuth { fun attach(activity: Activity) }

/** UI-facing boundary only. S1/S2 still need to agree the real wire and session contract. */
interface PhoneAuthRepository {
    suspend fun restore(): PhoneAccount?
    suspend fun oneTap(): PhoneAuthResult
    suspend fun smsSignIn(phone: String): PhoneAuthResult
    suspend fun requestSms(phone: String): PhoneActionResult
    suspend fun verifySms(phone: String, code: String): PhoneAuthResult
    suspend fun passwordSignIn(phone: String, password: String): PhoneAuthResult
    suspend fun resetPassword(phone: String, code: String, newPassword: String): PhoneActionResult
    suspend fun resetPasswordWithSms(phone: String, newPassword: String): PhoneActionResult
    suspend fun register(role: UserRole, displayName: String?, password: String?): PhoneAuthResult
    suspend fun signOut()
}

data class PhoneAccount(val id: String, val role: UserRole, val displayName: String)

enum class PhoneAuthError {
    NOT_CONFIGURED, INVALID_PHONE, PHONE_MISMATCH, INVALID_CODE, CODE_EXPIRED, WRONG_PASSWORD,
    PASSWORD_NOT_SET, ACCOUNT_NOT_FOUND, NETWORK, TOO_MANY_REQUESTS, STORAGE,
    NO_SIM, CELLULAR_UNAVAILABLE, ONE_TAP_UNAVAILABLE, PASSWORD_FEATURE_PENDING, CANCELLED, UNKNOWN,
}

sealed interface PhoneAuthResult {
    data class SignedIn(val account: PhoneAccount) : PhoneAuthResult
    data object RegistrationRequired : PhoneAuthResult
    data class Failed(val error: PhoneAuthError) : PhoneAuthResult
}

sealed interface PhoneActionResult {
    data object Success : PhoneActionResult
    data class Failed(val error: PhoneAuthError) : PhoneActionResult
}

/** Never turns an unconfigured provider into a successful login. */
object UnconfiguredPhoneAuthRepository : PhoneAuthRepository {
    override suspend fun restore(): PhoneAccount? = null
    override suspend fun oneTap() = PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun smsSignIn(phone: String) = PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun requestSms(phone: String) = PhoneActionResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun verifySms(phone: String, code: String) = PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun passwordSignIn(phone: String, password: String) = PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun resetPassword(phone: String, code: String, newPassword: String) =
        PhoneActionResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun resetPasswordWithSms(phone: String, newPassword: String) =
        PhoneActionResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun register(role: UserRole, displayName: String?, password: String?) =
        PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun signOut() = Unit
}
