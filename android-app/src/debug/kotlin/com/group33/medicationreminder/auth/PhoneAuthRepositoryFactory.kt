package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import java.security.MessageDigest

internal fun createPhoneAuthRepository(localDemoRequested: Boolean): PhoneAuthRepository =
    if (localDemoRequested) LocalDemoPhoneAuthRepository else UnconfiguredPhoneAuthRepository

/** Debug-only, in-memory UI rehearsal. It never verifies a real phone number or sends an SMS. */
internal object LocalDemoPhoneAuthRepository : PhoneAuthRepository {
    const val ELDER_PHONE = "13800000000"
    const val CAREGIVER_PHONE = "13900000000"
    const val CODE = "123456"

    private data class DemoAccount(val account: PhoneAccount, var passwordDigest: String?)
    private val accounts = mutableMapOf<String, DemoAccount>()
    private var currentPhone: String? = null
    private var pendingPhone: String? = null
    private var codePhone: String? = null

    override suspend fun restore(): PhoneAccount? = currentPhone?.let { accounts[it]?.account }

    override suspend fun oneTap(): PhoneAuthResult = verified(ELDER_PHONE)

    override suspend fun requestSms(phone: String): PhoneActionResult {
        if (!samplePhone(phone)) return PhoneActionResult.Failed(PhoneAuthError.INVALID_PHONE)
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

    private fun samplePhone(phone: String) = phone == ELDER_PHONE || phone == CAREGIVER_PHONE

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
