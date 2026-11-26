package com.group33.medicationreminder.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** One in-memory Server capability, bound to the original S1 installation and monotonic deadline.
 * No capability/installation is exposed to the provider. Uncertain work is never retried here. */
internal class BoundPhoneExchange private constructor(
    private val client: PhoneServerClient,
    val installationId: String,
    private val original: FusionSdkGrant,
    startedAt: Long,
    private val now: () -> Long,
    private val currentSnapshot: () -> Boolean,
    private val isCurrent: suspend () -> Boolean,
) {
    private val work = Mutex()
    @Volatile private var closed = false
    @Volatile private var verifying = false
    @Volatile private var deadline = startedAt + original.expiresInSeconds * 1000
    @Volatile private var token = original.token
    val schemeCode get() = original.schemeCode
    fun remainingMillis(): Long = (deadline - now()).coerceAtLeast(0)
    fun sdkToken(): String? {
        if (!runCatching(currentSnapshot).getOrDefault(false)) { close(); return null }
        return token.takeIf { !closed && !verifying && now() < deadline }
    }
    fun close() { closed = true; token = "" }

    private suspend fun checkCurrent() {
        check(!closed && now() < deadline) { "Original phone attempt unavailable" }
        val current = isCurrent()
        // The private-store check may suspend; recheck time/close after it returns.
        check(current && currentSnapshot() && !closed && now() < deadline) { "Original phone attempt unavailable" }
    }

    /** False means verification has already taken ownership; do not renew or start another attempt. */
    suspend fun refresh(): Boolean = work.withLock {
        if (verifying) return@withLock false
        try {
            checkCurrent()
            val requestedAt = now()
            val grant = withContext(Dispatchers.IO) {
                checkCurrent()
                client.grant(installationId, original.authenticationAttemptToken)
            }
            checkCurrent()
            validate(grant)
            check(grant.schemeCode == original.schemeCode &&
                grant.authenticationAttemptToken == original.authenticationAttemptToken)
            deadline = minOf(deadline, requestedAt + grant.expiresInSeconds * 1000)
            checkCurrent()
            token = grant.token
            true
        } catch (error: Exception) { close(); throw error }
    }

    suspend fun verify(proof: String, expectedPhone: String?): VerifiedPhone = work.withLock {
        try {
            checkCurrent()
            check(!verifying)
            require(proof.isNotBlank() && proof.length <= 8192)
            verifying = true
            val requestedAt = now()
            val response = withContext(Dispatchers.IO) {
                checkCurrent()
                client.verify(installationId, original.authenticationAttemptToken, proof, expectedPhone)
            }
            checkCurrent()
            if (response is VerifiedPhone.New) VerifiedPhone.New(response.ticket, response.expiresInSeconds,
                requestedAt + response.expiresInSeconds * 1000) else response
        } finally { close() }
    }

    companion object {
        suspend fun start(client: PhoneServerClient, installationId: String,
                          now: () -> Long, currentSnapshot: () -> Boolean, isCurrent: suspend () -> Boolean): BoundPhoneExchange {
            require(runCatching { UUID.fromString(installationId).toString() == installationId }.getOrDefault(false))
            check(isCurrent())
            val requestedAt = now()
            val grant = withContext(Dispatchers.IO) {
                check(isCurrent())
                check(currentSnapshot())
                client.grant(installationId)
            }
            validate(grant)
            check(isCurrent())
            return BoundPhoneExchange(client, installationId, grant, requestedAt, now, currentSnapshot, isCurrent).also {
                try { it.checkCurrent() } catch (error: Exception) { it.close(); throw error }
            }
        }

        private fun validate(grant: FusionSdkGrant) {
            require(grant.schemeCode.isNotBlank() && grant.token.isNotBlank())
            require(Regex("[A-Za-z0-9_-]{43}").matches(grant.authenticationAttemptToken))
            require(grant.expiresInSeconds in 1..900)
        }
    }
}
