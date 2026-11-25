package com.group33.medicationreminder.auth

import android.app.Activity
import com.group33.shared.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/** Real provider adapter. The SDK and app server must both succeed before the app signs in. */
internal class FusionPhoneAuthRepository(
    private val client: PhoneServerClient,
    private val sessions: PhoneSessionAccess,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : PhoneAuthRepository, ActivityBoundPhoneAuth {
    private var activity = WeakReference<Activity>(null)
    private class PendingRegistration(val ticket: String, val attempt: PhoneSignInAttempt, val deadline: Long)
    private val activeExchange = AtomicReference<BoundPhoneExchange?>(null)
    private val activeScene = AtomicReference<CompletableDeferred<Pair<VerifiedPhone?, PhoneAuthError?>>?>(null)
    private val pendingTicket = AtomicReference<PendingRegistration?>(null)
    // Keep attempt changes and ticket publication ordered across suspending store calls.
    // Provider/HTTP work must remain outside this lock.
    private val authenticationState = Mutex()

    override fun attach(activity: Activity) { this.activity = WeakReference(activity) }

    override suspend fun restore(): PhoneAccount? = sessions.restore()

    override suspend fun oneTap(): PhoneAuthResult = authenticate(preferSms = false)

    override suspend fun smsSignIn(phone: String): PhoneAuthResult = authenticate(preferSms = true, smsPhone = phone)

    private suspend fun authenticate(preferSms: Boolean, smsPhone: String = ""): PhoneAuthResult {
        return try {
        val host = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return PhoneAuthResult.Failed(PhoneAuthError.UNKNOWN)
        val attempt = beginAuthentication()
        val exchange = beginExchange(attempt)
        val scene = runScene(host, exchange, preferSms, smsPhone)
            ?: return PhoneAuthResult.Failed(if (preferSms) PhoneAuthError.UNKNOWN else PhoneAuthError.ONE_TAP_UNAVAILABLE)
        val verified = scene.first ?: return PhoneAuthResult.Failed(scene.second ?: PhoneAuthError.UNKNOWN)
        completeVerifiedResponse(attempt, verified)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PhoneAuthResult.Failed(error.asPhoneError())
        }
    }

    /** Called only with a verified server response; the original attempt fences disk writes. */
    internal suspend fun beginAuthentication(): PhoneSignInAttempt = authenticationState.withLock {
        sessions.begin().also {
            activeExchange.getAndSet(null)?.close()
            activeScene.getAndSet(null)?.complete(Pair(null, PhoneAuthError.CANCELLED))
            pendingTicket.set(null)
        }
    }

    internal suspend fun completeVerifiedResponse(attempt: PhoneSignInAttempt, verified: VerifiedPhone): PhoneAuthResult =
        when (verified) {
            is VerifiedPhone.New -> authenticationState.withLock {
                val deadline = verified.localDeadline ?: (now() + verified.expiresInSeconds * 1000)
                if (sessions.isCurrent(attempt) && now() < deadline) {
                    pendingTicket.set(PendingRegistration(verified.ticket, attempt, deadline))
                    PhoneAuthResult.RegistrationRequired
                } else PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
            }
            is VerifiedPhone.Existing -> signedIn(attempt, verified.session)
        }

    private suspend fun beginExchange(attempt: PhoneSignInAttempt): BoundPhoneExchange {
        val exchange = BoundPhoneExchange.start(client, attempt.installationId, now) { sessions.isCurrent(attempt) }
        return authenticationState.withLock {
            if (!sessions.isCurrent(attempt)) { exchange.close(); throw PhoneSessionStorageException() }
            activeExchange.getAndSet(exchange)?.close()
            exchange
        }
    }

    private suspend fun runScene(
        host: Activity, exchange: BoundPhoneExchange, preferSms: Boolean, smsPhone: String = "",
    ): Pair<VerifiedPhone?, PhoneAuthError?>? =
        coroutineScope {
            val result = CompletableDeferred<Pair<VerifiedPhone?, PhoneAuthError?>>()
            authenticationState.withLock {
                check(activeExchange.get() === exchange && exchange.sdkToken() != null)
                activeScene.getAndSet(result)?.complete(Pair(null, PhoneAuthError.CANCELLED))
            }
            var response: VerifiedPhone? = null
            var serverFailure: PhoneAuthError? = null
            val bridge = FusionSdkBridge(host, preferSms, smsPhone, object : FusionSdkBridge.Listener {
                override fun refreshSdkToken(): String? = exchange.sdkToken()

                override fun verifyOnServer(token: String, completion: (Boolean, PhoneAuthError?) -> Unit) {
                    launch {
                        serverFailure = null
                        val verified = try {
                            exchange.verify(token, smsPhone.takeIf { preferSms })
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            serverFailure = error.asPhoneError()
                            null
                        }
                        response = verified
                        completion(verified != null, serverFailure)
                    }
                }

                override fun finished(verified: Boolean, error: PhoneAuthError?) {
                    result.complete(Pair(if (verified) response else null, serverFailure ?: error))
                }
            })
            val refresh = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(4 * 60_000L)
                    try {
                        if (!exchange.refresh()) break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // An uncertain refresh closes the original scene; never silently Start again.
                        result.complete(Pair(null, PhoneAuthError.NETWORK))
                        break
                    }
                }
            }
            try {
                withContext(Dispatchers.Main) {
                    if (!result.isCompleted && activeExchange.get() === exchange) {
                        checkNotNull(exchange.sdkToken()).let { bridge.start(exchange.schemeCode, it) }
                    } else result.complete(Pair(null, PhoneAuthError.CANCELLED))
                }
                withTimeoutOrNull(minOf(10 * 60_000L, exchange.remainingMillis())) { result.await() }
            } finally {
                refresh.cancel()
                exchange.close()
                activeExchange.compareAndSet(exchange, null)
                activeScene.compareAndSet(result, null)
                bridge.close()
            }
        }

    override suspend fun register(role: UserRole, displayName: String?, password: String?): PhoneAuthResult {
        if (!password.isNullOrBlank()) return PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
        val pending = pendingTicket.get() ?: return PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
        return try {
            if (now() >= pending.deadline || !sessions.isCurrent(pending.attempt) || now() >= pending.deadline) {
                pendingTicket.compareAndSet(pending, null)
                return PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
            }
            // A lost/uncertain response may have consumed the ticket. Never resend it or a new role.
            if (!pendingTicket.compareAndSet(pending, null)) return PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
            when (val result = withContext(Dispatchers.IO) {
                if (!sessions.isCurrent(pending.attempt) || now() >= pending.deadline) null
                else client.register(pending.attempt.installationId, pending.ticket, role, displayName)
            }) {
                null -> PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
                is VerifiedPhone.Existing -> {
                    if (now() >= pending.deadline) PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
                    else completeVerifiedResponse(pending.attempt, result)
                }
                is VerifiedPhone.New -> PhoneAuthResult.Failed(PhoneAuthError.UNKNOWN)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PhoneAuthResult.Failed(error.asPhoneError())
        }
    }

    private suspend fun signedIn(attempt: PhoneSignInAttempt, session: AppSession): PhoneAuthResult {
        val saved = sessions.complete(attempt, session)
        return if (saved) PhoneAuthResult.SignedIn(session.account) else PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED)
    }

    override suspend fun signOut(): Unit = authenticationState.withLock {
        activeExchange.getAndSet(null)?.close()
        activeScene.getAndSet(null)?.complete(Pair(null, PhoneAuthError.CANCELLED))
        pendingTicket.set(null)
        withContext(Dispatchers.IO) { sessions.clear() }
    }

    // SMS is performed by Alibaba's configured fallback within the Fusion scene.
    override suspend fun requestSms(phone: String) = PhoneActionResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun verifySms(phone: String, code: String) = PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun passwordSignIn(phone: String, password: String) =
        PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
    override suspend fun resetPassword(phone: String, code: String, newPassword: String) =
        PhoneActionResult.Failed(PhoneAuthError.NOT_CONFIGURED)

    // #118 does not provide a password-reset protocol. Do not consume a login proof as a reset.
    override suspend fun resetPasswordWithSms(phone: String, newPassword: String) =
        PhoneActionResult.Failed(PhoneAuthError.NOT_CONFIGURED)

    private fun Exception.asPhoneError(): PhoneAuthError {
        if (this is PhoneSessionStorageException) return PhoneAuthError.STORAGE
        val response = this as? FusionHttpException ?: return PhoneAuthError.NETWORK
        return when (response.code) {
            "INVALID_CREDENTIALS" -> PhoneAuthError.WRONG_PASSWORD
            "PASSWORD_NOT_SET" -> PhoneAuthError.PASSWORD_NOT_SET
            "ACCOUNT_NOT_FOUND" -> PhoneAuthError.ACCOUNT_NOT_FOUND
            "PHONE_VERIFICATION_FAILED" -> PhoneAuthError.INVALID_CODE
            "PHONE_MISMATCH" -> PhoneAuthError.PHONE_MISMATCH
            "TOO_MANY_REQUESTS" -> PhoneAuthError.TOO_MANY_REQUESTS
            "PHONE_AUTH_UNAVAILABLE" -> PhoneAuthError.NOT_CONFIGURED
            "REGISTRATION_INVALID" -> PhoneAuthError.CODE_EXPIRED
            "SERVICE_UNAVAILABLE", "OPERATION_IN_PROGRESS" -> PhoneAuthError.NETWORK
            else -> when (response.status) {
                429 -> PhoneAuthError.TOO_MANY_REQUESTS
                503 -> PhoneAuthError.NOT_CONFIGURED
                else -> PhoneAuthError.NETWORK
            }
        }
    }
}
