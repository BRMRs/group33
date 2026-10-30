package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import android.content.Context
import com.group33.medicationreminder.BuildConfig
import java.security.MessageDigest

internal fun createPhoneAuthRepository(localDemoRequested: Boolean, context: Context): PhoneAuthRepository =
    if (localDemoRequested) LocalDemoPhoneAuthRepository else createFusionRepository(context)

internal fun createFusionRepository(context: Context): PhoneAuthRepository = try {
    val localTrial = BuildConfig.PHONE_AUTH_LOCAL_TRIAL && BuildConfig.PHONE_AUTH_BASE_URL.startsWith("http://10.0.2.2:")
    if (BuildConfig.PHONE_AUTH_BASE_URL.isBlank() || (!localTrial && !BuildConfig.PHONE_AUTH_PRIVACY_POLICY_URL.startsWith("https://"))) {
        UnconfiguredPhoneAuthRepository
    } else {
        FusionPhoneAuthRepository(context, BuildConfig.PHONE_AUTH_BASE_URL, allowHttp = localTrial)
    }
} catch (_: Exception) { UnconfiguredPhoneAuthRepository }

/** Debug-only, in-memory UI rehearsal. It never verifies a real phone number or sends an SMS. */
internal object LocalDemoPhoneAuthRepository : PhoneAuthRepository {
    // Represents the SIM number returned by the carrier SDK in the local preview.
    const val ONE_TAP_PHONE = "13800000000"
    const val CODE = "123456"

    private data class DemoAccount(val account: PhoneAccount, var passwordDigest: String?)
    private val accounts = mutableMapOf<String, DemoAccount>()
    private var currentPhone: String? = null
    private var pendingPhone: String? = null
    private var codePhone: String? = null

    override suspend fun restore(): PhoneAccount? = currentPhone?.let { accounts[it]?.account }

    override suspend fun oneTap(): PhoneAuthResult = verified(ONE_TAP_PHONE)

    override suspend fun requestSms(phone: String): PhoneActionResult {
        if (!phone.matches(Regex("\\+?[0-9]{8,15}"))) {
            return PhoneActionResult.Failed(PhoneAuthError.INVALID_PHONE)
        }
        codePhone = phone
        return PhoneActionResult.Success
    }

    override suspend fun verifySms(phone: String, code: String): PhoneAuthResult {
        if (codePhone != phone || code != CODE) return PhoneAuthResult.Failed(PhoneAuthError.INVALID_CODE)
        codePhone = null
        return verified(phone)
    }

    override suspend fun passwordSignIn(phone: String, password: String): PhoneAuthResult {
        val record = accounts[phone] ?: return PhoneAuthResult.Failed(PhoneAuthError.ACCOUNT_NOT_FOUND)
        val digest = record.passwordDigest ?: return PhoneAuthResult.Failed(PhoneAuthError.PASSWORD_NOT_SET)
        if (digest != digest(password)) return PhoneAuthResult.Failed(PhoneAuthError.WRONG_PASSWORD)
        currentPhone = phone
        return PhoneAuthResult.SignedIn(record.account)
    }

    override suspend fun resetPassword(phone: String, code: String, newPassword: String): PhoneActionResult {
        if (codePhone != phone || code != CODE) return PhoneActionResult.Failed(PhoneAuthError.INVALID_CODE)
        val record = accounts[phone] ?: return PhoneActionResult.Failed(PhoneAuthError.ACCOUNT_NOT_FOUND)
        codePhone = null
        record.passwordDigest = digest(newPassword)
        return PhoneActionResult.Success
    }

    override suspend fun register(role: UserRole, displayName: String?, password: String?): PhoneAuthResult {
        val phone = pendingPhone ?: return PhoneAuthResult.Failed(PhoneAuthError.INVALID_CODE)
        val account = PhoneAccount(
            id = "local-demo-$phone",
            role = role,
            displayName = if (role == UserRole.ELDER) "Elder demo" else displayName.orEmpty(),
        )
        accounts[phone] = DemoAccount(account, password?.let(::digest))
        pendingPhone = null
        currentPhone = phone
        return PhoneAuthResult.SignedIn(account)
    }

    override suspend fun signOut() {
        currentPhone = null
        pendingPhone = null
    }

    private fun verified(phone: String): PhoneAuthResult {
        val record = accounts[phone]
        return if (record == null) {
            pendingPhone = phone
            PhoneAuthResult.RegistrationRequired
        } else {
            currentPhone = phone
            PhoneAuthResult.SignedIn(record.account)
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
