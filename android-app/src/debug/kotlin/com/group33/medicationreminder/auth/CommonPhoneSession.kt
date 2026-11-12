package com.group33.medicationreminder.auth

import com.group33.medicationreminder.core.data.contract.*
import com.group33.medicationreminder.core.session.AuthenticationAttempt
import com.group33.medicationreminder.core.session.PrivateSessionStore
import com.group33.shared.contract.UserView

/** Opaque attempt; neither the UI nor SDK gets session credentials. */
internal interface PhoneSignInAttempt

internal interface PhoneSessionAccess {
    suspend fun begin(): PhoneSignInAttempt
    suspend fun isCurrent(attempt: PhoneSignInAttempt): Boolean
    suspend fun complete(attempt: PhoneSignInAttempt, session: AppSession): Boolean
    suspend fun restore(): PhoneAccount?
    suspend fun clear()
}

/** Uses the host's exact S1 store instance, not a second phone session file. */
internal class CommonPhoneSession(private val store: PrivateSessionStore) : PhoneSessionAccess {
    private class Attempt(val core: AuthenticationAttempt) : PhoneSignInAttempt
    override suspend fun begin(): PhoneSignInAttempt = Attempt(store.beginAuthentication().required())
    override suspend fun isCurrent(attempt: PhoneSignInAttempt): Boolean =
        (attempt as? Attempt)?.let { store.isCurrentAuthentication(it.core).required() } ?: false

    override suspend fun complete(attempt: PhoneSignInAttempt, session: AppSession): Boolean {
        val core = (attempt as? Attempt)?.core ?: return false
        val account = session.account
        return when (val result = store.completeAuthentication(core,
            UserView(account.id, account.displayName, account.role), session.token, session.expiresInSeconds)) {
            is Outcome.Success -> true
            is Outcome.Failure -> if ((result.error as? RepositoryError.Local)?.code == LocalFailureCode.SESSION_NOT_READY)
                false else throw PhoneSessionStorageException()
        }
    }

    override suspend fun restore(): PhoneAccount? = store.restore().required().user?.let {
        PhoneAccount(it.id, it.role, it.displayName)
    }
    override suspend fun clear() { store.clear().required() }
    private fun <T> Outcome<T>.required(): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw PhoneSessionStorageException()
    }
}

internal class PhoneSessionStorageException : Exception("Session storage unavailable")
