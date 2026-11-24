package com.group33.server.auth

import com.group33.shared.model.UserRole
import org.springframework.http.HttpStatus
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The supplier adapter must use the existing bounded #97 execution, including no automatic retry. */
internal interface BoundPhoneProvider {
    fun grant(): BoundPhoneGrant
    /** Return null for explicit denial, throw for unavailable or uncertain completion. */
    fun verify(proof: String): String?
}

internal class BoundPhoneGrant(val value: String, val expiresAtMillis: Long) {
    override fun toString() = "BoundPhoneGrant(redacted)"
}

internal class PhoneAuthFailure(val status: HttpStatus, val code: String, val retryable: Boolean = false) :
    RuntimeException("Phone authentication could not complete")

internal class BoundPhoneAuthentication(
    private val db: BoundPhoneIdentityStore,
    private val provider: BoundPhoneProvider,
    private val jwt: BoundPhoneJwt,
    private val clock: Clock,
    private val scheme: String,
    phoneHashKey: ByteArray,
    private val sessionSeconds: Long = 86400,
) {
    private val phoneKey = SecretKeySpec(phoneHashKey.copyOf(), "HmacSHA256")
    private val profile get() = jwt.audience

    init {
        require(phoneHashKey.size >= 32 && scheme.isNotBlank())
        require(sessionSeconds in 1..86400)
    }

    fun start(installation: UUID): Map<String, Any> = safe {
        val capability = newCapability()
        val hash = digest(capability)
        val lease = db.primaryTransaction { connection ->
            val store = BoundPhoneAttemptStore(connection)
            val id = UUID.randomUUID()
            store.start(id, hash, installation, profile, clock.millis() + ATTEMPT_MS)
            reserve(store, hash, installation, PhoneWork.GRANT)
        }
        grant(capability, installation, lease, initial = true)
    }

    fun refresh(installation: UUID, capability: String): Map<String, Any> = safe {
        val lease = reserve(installation, capability, PhoneWork.GRANT)
        grant(capability, installation, lease, initial = false)
    }

    fun verify(installation: UUID, capability: String, proof: String, expectedPhone: String?): Map<String, Any> = safe {
        val lease = reserve(installation, capability, PhoneWork.VERIFY, digest(proof))
        val phone = supplier(installation, capability, lease) { provider.verify(proof) }
        if (phone == null || phone.isBlank()) {
            close(installation, capability, lease)
            invalidAttempt()
        }
        // expectedPhone is intent matching, never the account key. Preserve the existing SMS comparison.
        if (expectedPhone != null && comparablePhone(expectedPhone) != comparablePhone(phone)) {
            close(installation, capability, lease)
            throw PhoneAuthFailure(HttpStatus.UNAUTHORIZED, "PHONE_MISMATCH")
        }
        val hash = HexFormat.of().formatHex(Mac.getInstance("HmacSHA256").apply { init(phoneKey) }.doFinal(phone.toByteArray()))
        val result = db.primaryTransaction { connection ->
            val attempts = BoundPhoneAttemptStore(connection)
            val attempt = completing(attempts, capability, installation, lease)
            val identity = BoundPhoneIdentityTransaction(connection)
            val account = identity.accountForVerifiedPhone(hash)
            val terminal = if (account == null) {
                val ticket = newCapability()
                attempts.issueTicket(digest(ticket), attempt.id, installation, profile, hash, clock.millis() + TICKET_SECONDS * 1000)
                VerifiedPhone.Registration(ticket)
            } else {
                val now = clock.instant().epochSecond
                VerifiedPhone.SignedIn(identity.openSession(UUID.fromString(account.id), installation, profile, now, now + sessionSeconds))
            }
            completing(attempts, capability, installation, lease)
            attempts.finish(attempt.id, AttemptState.CONSUMED, attempt.deadline)
            terminal
        }
        when (result) {
            is VerifiedPhone.Registration -> mapOf("status" to "REGISTRATION_REQUIRED", "registrationTicket" to result.ticket,
                "registrationTicketExpiresInSeconds" to TICKET_SECONDS, "installationId" to installation)
            is VerifiedPhone.SignedIn -> signedIn(result.session)
        }
    }

    fun register(installation: UUID, ticket: String, role: UserRole, displayName: String?): Map<String, Any> = safe {
        val session = db.primaryTransaction { connection ->
            val hash = BoundPhoneAttemptStore(connection).consumeTicket(digest(ticket), installation, profile, clock::millis)
                ?: throw PhoneAuthFailure(HttpStatus.UNPROCESSABLE_ENTITY, "REGISTRATION_INVALID")
            val identity = BoundPhoneIdentityTransaction(connection)
            val account = identity.registerVerifiedPhone(hash, role, displayName)
            val now = clock.instant().epochSecond
            identity.openSession(UUID.fromString(account.id), installation, profile, now, now + sessionSeconds)
        }
        signedIn(session)
    }

    private fun signedIn(session: BoundPhoneSession): Map<String, Any> {
        // Signing occurs after the session/ticket transaction commits. Do not retry the supplier on failure.
        val token = try { jwt.issue(session.claims) } catch (_: Exception) { unavailable() }
        val ttl = session.claims.expiresAtSeconds - clock.instant().epochSecond
        if (ttl <= 0) unavailable()
        return mapOf("status" to "SIGNED_IN", "accessToken" to token, "expiresInSeconds" to ttl,
            "user" to session.user, "installationId" to session.claims.installationId)
    }

    private fun grant(capability: String, installation: UUID, lease: UUID, initial: Boolean): Map<String, Any> {
        val supplied = supplier(installation, capability, lease, provider::grant)
        return db.primaryTransaction { connection ->
            val store = BoundPhoneAttemptStore(connection)
            val attempt = completing(store, capability, installation, lease)
            val deadline = if (initial) minOf(attempt.deadline, supplied.expiresAtMillis) else attempt.deadline
            val remaining = (minOf(deadline, supplied.expiresAtMillis) - clock.millis()) / 1000
            if (supplied.value.isBlank() || remaining <= 0) {
                store.finish(attempt.id, AttemptState.CLOSED, deadline)
                null
            } else {
                store.finish(attempt.id, AttemptState.PENDING, deadline)
                mapOf("fusionAuthToken" to supplied.value, "schemeCode" to scheme, "expiresInSeconds" to remaining,
                    "authenticationAttemptToken" to capability)
            }
        } ?: unavailable()
    }

    private fun reserve(installation: UUID, capability: String, work: PhoneWork, proof: String? = null): UUID =
        db.primaryTransaction { reserve(BoundPhoneAttemptStore(it), digest(capability), installation, work, proof) }

    private fun reserve(store: BoundPhoneAttemptStore, hash: String, installation: UUID, work: PhoneWork, proof: String? = null): UUID {
        val attempt = store.current(hash, installation, profile) ?: invalidAttempt()
        val now = clock.millis()
        if (attempt.state != AttemptState.PENDING || attempt.deadline <= now) invalidAttempt()
        if (attempt.leaseUntil != null) {
            if (attempt.leaseUntil <= now) invalidAttempt()
            throw PhoneAuthFailure(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", true)
        }
        if (attempt.deadline - now <= WORK_MS || proof != null && !store.rememberProof(profile, proof)) invalidAttempt()
        // A proof insert may wait on another transaction. Recheck the budget before making any provider call.
        val claimTime = clock.millis()
        if (attempt.deadline - claimTime <= WORK_MS) invalidAttempt()
        return UUID.randomUUID().also { store.claim(attempt.id, it, claimTime + WORK_MS, work) }
    }

    private fun completing(store: BoundPhoneAttemptStore, capability: String, installation: UUID, lease: UUID): PhoneAttempt {
        val attempt = store.current(digest(capability), installation, profile) ?: invalidAttempt()
        val now = clock.millis()
        if (attempt.state != AttemptState.PENDING || attempt.lease != lease || attempt.leaseUntil == null ||
            now >= attempt.leaseUntil || now >= attempt.deadline) invalidAttempt()
        return attempt
    }

    private fun close(installation: UUID, capability: String, lease: UUID) {
        db.primaryTransaction { connection ->
            val store = BoundPhoneAttemptStore(connection)
            val attempt = store.current(digest(capability), installation, profile)
            if (attempt?.lease == lease && attempt.state == AttemptState.PENDING) {
                store.finish(attempt.id, AttemptState.CLOSED, attempt.deadline)
            }
        }
    }

    private fun <T> supplier(installation: UUID, capability: String, lease: UUID, call: () -> T): T = try {
        call()
    } catch (failure: Exception) {
        // If even closing fails, the durable expired lease still excludes re-execution and late completion.
        try { close(installation, capability, lease) } catch (_: BoundPhoneIdentityUnavailable) { }
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        unavailable()
    }

    private fun <T> safe(call: () -> T): T = try { call() }
        catch (_: BoundPhoneIdentityUnavailable) { throw PhoneAuthFailure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", true) }
        catch (_: BoundPhoneIdentityRevoked) { invalidAttempt() }

    private sealed interface VerifiedPhone {
        class Registration(val ticket: String) : VerifiedPhone
        class SignedIn(val session: BoundPhoneSession) : VerifiedPhone
    }

    private companion object {
        const val ATTEMPT_MS = 900_000L
        const val TICKET_SECONDS = 300L
        // This DB lease leaves margin over the existing 15s supplier call budget.
        const val WORK_MS = 20_000L
        val random = SecureRandom()
        fun newCapability() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        fun digest(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
        fun comparablePhone(value: String) = value.removePrefix("+86").takeIf { value.startsWith("+86") && it.length == 11 } ?: value
        fun invalidAttempt(): Nothing = throw PhoneAuthFailure(HttpStatus.UNAUTHORIZED, "PHONE_VERIFICATION_FAILED")
        fun unavailable(): Nothing = throw PhoneAuthFailure(HttpStatus.SERVICE_UNAVAILABLE, "PHONE_AUTH_UNAVAILABLE", true)
    }
}
