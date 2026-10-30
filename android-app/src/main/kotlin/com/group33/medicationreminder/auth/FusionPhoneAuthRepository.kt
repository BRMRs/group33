package com.group33.medicationreminder.auth

import android.app.Activity
import android.content.Context
import com.group33.shared.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

internal interface ActivityBoundPhoneAuth {
    fun attach(activity: Activity)
}

/** Real provider adapter. The SDK and app server must both succeed before the app signs in. */
internal class FusionPhoneAuthRepository(
    context: Context,
    baseUrl: String,
    allowHttp: Boolean,
) : PhoneAuthRepository, ActivityBoundPhoneAuth {
    private val client = FusionServerClient(baseUrl, allowHttp)
    private val sessions = PhoneSessionStore(context.applicationContext, baseUrl)
    private var activity = WeakReference<Activity>(null)
    private class PendingRegistration(val ticket: String, val attempt: PhoneSessionAttempt)
    private val pendingTicket = AtomicReference<PendingRegistration?>(null)

    override fun attach(activity: Activity) { this.activity = WeakReference(activity) }

    override suspend fun restore(): PhoneAccount? = withContext(Dispatchers.IO) { sessions.restore()?.account }

    override suspend fun oneTap(): PhoneAuthResult {
        return try {
        val host = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return PhoneAuthResult.Failed(PhoneAuthError.UNKNOWN)
        val attempt = beginAuthentication()
        val grant = withContext(Dispatchers.IO) { client.grant() }
        // SDK token renewal is synchronous; prefetch so its callback never blocks the UI.
        val replacement = withContext(Dispatchers.IO) { client.grant() }
        val verified = runScene(host, grant, replacement.token)
            ?: return PhoneAuthResult.Failed(PhoneAuthError.UNKNOWN)
        completeVerifiedResponse(attempt, verified)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PhoneAuthResult.Failed(error.asPhoneError())
        }
    }

    /** Called only with a verified server response; the original attempt fences disk writes. */
    internal suspend fun beginAuthentication(): PhoneSessionAttempt = withContext(Dispatchers.IO) { sessions.beginAttempt() }

    internal suspend fun completeVerifiedResponse(attempt: PhoneSessionAttempt, verified: VerifiedPhone): PhoneAuthResult =
        when (verified) {
            is VerifiedPhone.New -> {
                if (sessions.isCurrent(attempt)) {
                    pendingTicket.set(PendingRegistration(verified.ticket, attempt))
                    PhoneAuthResult.RegistrationRequired
                } else PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
            }
            is VerifiedPhone.Existing -> signedIn(attempt, verified.session)
        }

    private suspend fun runScene(host: Activity, grant: FusionSdkGrant, replacement: String): VerifiedPhone? =
        coroutineScope {
            val result = CompletableDeferred<VerifiedPhone?>()
            val freshToken = AtomicReference(replacement)
            var response: VerifiedPhone? = null
            val bridge = FusionSdkBridge(host, object : FusionSdkBridge.Listener {
                override fun refreshSdkToken(): String = freshToken.get()

                override fun verifyOnServer(token: String, completion: (Boolean) -> Unit) {
                    launch {
                        val verified = try {
                            withContext(Dispatchers.IO) { client.verify(token) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                        response = verified
                        completion(verified != null)
                    }
                }

                override fun finished(verified: Boolean) {
                    result.complete(if (verified) response else null)
                }
            })
            val refresh = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(4 * 60_000L)
                    try {
                        freshToken.set(client.grant().token)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Keep the previous token; the SDK will fail closed if it expires.
                    }
                }
            }
            try {
                withContext(Dispatchers.Main) { bridge.start(grant.schemeCode, grant.token) }
                withTimeoutOrNull(10 * 60_000L) { result.await() }
            } finally {
                refresh.cancel()
                bridge.close()
            }
        }

    override suspend fun register(role: UserRole, displayName: String?, password: String?): PhoneAuthResult {
        if (!password.isNullOrBlank()) return PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
        val pending = pendingTicket.get() ?: return PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
        if (!sessions.isCurrent(pending.attempt)) return PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
        return try {
            when (val result = withContext(Dispatchers.IO) { client.register(pending.ticket, role, displayName) }) {
                is VerifiedPhone.Existing -> {
                    pendingTicket.compareAndSet(pending, null)
                    completeVerifiedResponse(pending.attempt, result)
                }
                is VerifiedPhone.New -> PhoneAuthResult.Failed(PhoneAuthError.UNKNOWN)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PhoneAuthResult.Failed(error.asPhoneError())
        }
    }

    private suspend fun signedIn(attempt: PhoneSessionAttempt, session: AppSession): PhoneAuthResult {
        val saved = withContext(Dispatchers.IO) { sessions.save(attempt, session) }
        return if (saved) PhoneAuthResult.SignedIn(session.account) else PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
    }

    override suspend fun signOut() {
        pendingTicket.set(null)
        withContext(Dispatchers.IO) { sessions.clear() }
    }

    // SMS is performed by Alibaba's configured fallback within the Fusion scene.
    override suspend fun requestSms(phone: String) = PhoneActionResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun verifySms(phone: String, code: String) = PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun passwordSignIn(phone: String, password: String) = PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun resetPassword(phone: String, code: String, newPassword: String) =
        PhoneActionResult.Failed(PhoneAuthError.NOT_CONFIGURED)

    private fun Exception.asPhoneError(): PhoneAuthError = when ((this as? FusionHttpException)?.status) {
        401 -> PhoneAuthError.INVALID_CODE
        429 -> PhoneAuthError.TOO_MANY_REQUESTS
        503 -> PhoneAuthError.NOT_CONFIGURED
        else -> PhoneAuthError.NETWORK
    }
}
