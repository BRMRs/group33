package com.group33.medicationreminder.core.session

import android.content.Context
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.UserView
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

/** Authenticated HTTP adapter input only; never expose credentials in a ViewModel or log. */
internal class BearerCredential internal constructor(internal val value: String)

internal class AuthenticationAttempt internal constructor(
    val installationId: String,
    internal val environmentId: String,
    internal val generation: String,
    internal val startedAtMillis: Long,
    internal val epoch: String,
)

/** Capture before asynchronous work; check again before applying its result. */
internal class SessionLease internal constructor(
    val scope: RepositoryScope,
    internal val generation: String,
    internal val epoch: String,
)

/**
 * Device-local foundation, not an authentication server or AuthRepository login implementation.
 * One configured environment per instance. Only a verified HTTP auth adapter may complete a login.
 * Host must clear/deactivate its old store and cancel old work before changing environment.
 */
internal class PrivateSessionStore(
    context: Context,
    private val environmentId: String,
    namespace: String = "private-session",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val file = EncryptedSessionFile(context.applicationContext, environmentId, namespace)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSession(): Flow<SessionView> = flow {
        restore()
        emitAll(file.state.transformLatest { observation ->
            emit(observation.view)
            while (observation.view.readiness == AuthReadiness.READY && file.state.value == observation) {
                val expiry = access {
                    val record = current()
                    publish(record)
                    record.expiresAtMillis
                }
                if (expiry !is Outcome.Success || expiry.value <= 0 || file.state.value != observation) break
                delay((expiry.value - clock.millis()).coerceAtLeast(1))
                // Re-arm if wall-clock adjustment wakes us before the current expiry.
                restore()
            }
        })
    }

    suspend fun restore(): Outcome<SessionView> = access {
        val record = current()
        publish(record)
        SessionView(if (record.user == null) AuthReadiness.SIGNED_OUT else AuthReadiness.READY, record.user)
    }

    /** Persist the installation identity and invalidate previous authentication attempts first. */
    suspend fun beginAuthentication(): Outcome<AuthenticationAttempt> {
        file.requestClear()
        return access {
            val old = file.readOrCreate()
            val now = clock.millis().also { require(it >= 0) }
            val next = old.cleared()
            file.write(next)
            file.acknowledgeClear()
            publish(next)
            AuthenticationAttempt(next.installationId, environmentId, next.generation, now, file.epoch)
        }
    }

    suspend fun completeAuthentication(
        attempt: AuthenticationAttempt,
        user: UserView,
        accessToken: String,
        expiresInSeconds: Long,
    ): Outcome<SessionLease> {
        if (user.id.isBlank() || user.displayName.isBlank() ||
            !accessToken.matches(Regex("[A-Za-z0-9._~+/-]+=*")) || expiresInSeconds <= 0) return invalid()
        val expiresAt = try {
            Math.addExact(attempt.startedAtMillis, Math.multiplyExact(expiresInSeconds, 1_000L))
        } catch (_: ArithmeticException) { return invalid() }
        return access {
            val old = file.readOrCreate()
            if (attempt.environmentId != environmentId || attempt.installationId != old.installationId ||
                attempt.generation != old.generation || attempt.epoch != file.epoch || file.clearRequested ||
                clock.millis() !in attempt.startedAtMillis until expiresAt) {
                return@access notReady()
            }
            val next = SessionDiskRecord(old.installationId, UUID.randomUUID().toString(), user,
                accessToken, attempt.startedAtMillis, expiresAt)
            file.write(next)
            publish(next)
            Outcome.Success(lease(next))
        }.flatten()
    }

    /** Clearing credentials leaves installation identity and all business/journal files intact. */
    suspend fun clear(): Outcome<Unit> {
        file.requestClear()
        return access { publish(current()) }
    }

    /** A 401 from an obsolete request cannot clear a newer successful login. */
    suspend fun invalidate(lease: SessionLease): Outcome<Boolean> = access {
        val old = current()
        if (!matches(old, lease)) return@access false
        file.requestClear()
        publish(current())
        true
    }

    suspend fun acquire(): Outcome<SessionLease> = access {
        val record = current()
        publish(record)
        if (record.user == null) notReady() else Outcome.Success(lease(record))
    }.flatten()

    /** Network layer only. Re-check the same lease after HTTP before updating current data. */
    suspend fun credential(lease: SessionLease): Outcome<BearerCredential> = access {
        val record = current()
        publish(record)
        if (!matches(record, lease)) notReady() else Outcome.Success(BearerCredential(checkNotNull(record.token)))
    }.flatten()

    private fun current(): SessionDiskRecord {
        val record = file.readOrCreate()
        if (file.clearRequested) return record.cleared().also {
            file.write(it)
            file.acknowledgeClear()
        }
        if (record.user == null) return record
        val now = clock.millis()
        return if (now < record.issuedAtMillis || now >= record.expiresAtMillis) {
            record.cleared().also(file::write)
        } else record
    }

    private fun lease(record: SessionDiskRecord) = SessionLease(
        RepositoryScope(environmentId, record.installationId, checkNotNull(record.user).id), record.generation, file.epoch)

    private fun matches(record: SessionDiskRecord, lease: SessionLease): Boolean = record.user != null &&
        lease.scope == RepositoryScope(environmentId, record.installationId, record.user.id) &&
        lease.generation == record.generation && lease.epoch == file.epoch

    private fun publish(record: SessionDiskRecord) {
        file.state.value = SessionObservation(
            SessionView(if (record.user == null) AuthReadiness.SIGNED_OUT else AuthReadiness.READY, record.user),
            record.generation, record.expiresAtMillis, file.epoch)
    }

    private suspend fun <T> access(block: () -> T): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            file.locked { Outcome.Success(block()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No raw crypto/filesystem diagnostics or retained user/token in observable state.
            file.storageFailure()
            Outcome.Failure(RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE))
        }
    }

    private fun invalid() = Outcome.Failure(RepositoryError.Local(LocalFailureCode.INVALID_INPUT))
    private fun notReady() = Outcome.Failure(RepositoryError.Local(LocalFailureCode.SESSION_NOT_READY))
    private fun <T> Outcome<Outcome<T>>.flatten(): Outcome<T> = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> this
    }
}
