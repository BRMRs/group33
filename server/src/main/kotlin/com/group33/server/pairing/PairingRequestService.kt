package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.PairingRequestView
import com.group33.shared.contract.RequestPairingRequest
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.net.InetAddress
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

internal enum class PairingRequestRejection {
    ROLE_FORBIDDEN, IDEMPOTENCY_KEY_REUSED, PAIRING_CODE_UNAVAILABLE, RESOURCE_NOT_ACCESSIBLE,
    RATE_LIMITED, OPERATION_IN_PROGRESS,
}

/** Internal results; HTTP mapping and retry headers are deliberately not registered here. */
internal sealed interface PairingRequestResult {
    class Success(val view: PairingRequestView) : PairingRequestResult {
        override fun toString() = "PairingRequestSuccess(REDACTED)"
    }
    data class Rejected(val reason: PairingRequestRejection, val retryAfterSeconds: Long? = null) : PairingRequestResult
}

/**
 * Trusted actor and transport-resolved address only; never construct either from untrusted body/forwarded headers.
 * This service creates PENDING requests only. It neither establishes nor checks an ACTIVE relationship.
 */
internal class PairingRequestService(private val store: PairingRequestStore, private val clock: Clock = Clock.systemUTC()) {
    fun request(actor: AnonymousUser, operationId: UUID, input: RequestPairingRequest, sourceAddress: InetAddress): PairingRequestResult {
        if (actor.role != UserRole.CAREGIVER) return rejected(PairingRequestRejection.ROLE_FORBIDDEN)
        val key = PairingRequestKey(actor.id, operationId)
        val fingerprint = hash("${ContractProtocol.CANONICALIZATION_VERSION}:REQUEST_PAIRING:${input.code}")
        // The typed byte address avoids spelling/host-name aliases; no DNS lookup occurs here.
        val addressHash = hash(HexFormat.of().formatHex(sourceAddress.address))
        return transaction { tx ->
            val previous = tx.requestOperation(key)
            if (previous != null && (previous.installationId != actor.installationId || previous.fingerprint != fingerprint)) {
                return@transaction rejected(PairingRequestRejection.IDEMPOTENCY_KEY_REUSED)
            }
            previous?.receipt?.let { return@transaction PairingRequestResult.Success(it) }
            fun reject(reason: PairingRequestRejection, retryAfter: Long? = null): PairingRequestResult.Rejected {
                val result = rejected(reason, retryAfter)
                tx.putRequestOperation(key, PairingRequestOperation(actor.installationId, fingerprint, rejection = result))
                return result
            }
            val now = clock.millis() // after lock acquisition
            val cutoff = Math.subtractExact(now, WINDOW_MILLIS)
            val byInstallation = tx.installationAttempts(actor.installationId).filter { it > cutoff }
            val byAddress = tx.addressAttempts(addressHash).filter { it > cutoff }
            val waits = listOf(byInstallation to 5, byAddress to 20).mapNotNull { (times, limit) ->
                if (times.size < limit) null else Math.subtractExact(Math.addExact(times.min(), WINDOW_MILLIS), now)
            }
            if (waits.isNotEmpty()) return@transaction reject(PairingRequestRejection.RATE_LIMITED, 1 + (waits.max() - 1) / 1_000)
            // Invalid/expired/used codes count as attempts; accepted receipt replay above does not guess again.
            tx.putInstallationAttempts(actor.installationId, byInstallation + now)
            tx.putAddressAttempts(addressHash, byAddress + now)
            if (!input.code.matches(Regex("[0-9]{8}"))) return@transaction reject(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
            val code = tx.unconsumedCode(hash(input.code), now)
                ?: return@transaction reject(PairingRequestRejection.PAIRING_CODE_UNAVAILABLE)
            val view = PairingRequestView(UUID.randomUUID().toString(), code.elderId,
                User(actor.id, actor.displayName, actor.role), PairingRequestStatus.PENDING, code.expiresAtEpochMillis)
            tx.consume(code, view)
            tx.putRequestOperation(key, PairingRequestOperation(actor.installationId, fingerprint, receipt = view))
            PairingRequestResult.Success(view)
        }
    }

    /** Participant-only detail preserves terminal metadata separately from the original success receipt. */
    fun detail(actor: AnonymousUser, requestId: String): PairingRequestResult = transaction { tx ->
        val view = tx.request(requestId, clock.millis())
            ?: return@transaction rejected(PairingRequestRejection.RESOURCE_NOT_ACCESSIBLE)
        val participant = (actor.role == UserRole.ELDER && actor.id == view.elderId) ||
            (actor.role == UserRole.CAREGIVER && actor.id == view.caregiver.id)
        if (participant) PairingRequestResult.Success(view) else rejected(PairingRequestRejection.RESOURCE_NOT_ACCESSIBLE)
    }

    private fun transaction(work: (PairingRequestTransaction) -> PairingRequestResult): PairingRequestResult = try {
        store.requestTransaction(work)
    } catch (_: CodeStoreBusy) {
        rejected(PairingRequestRejection.OPERATION_IN_PROGRESS, 1)
    }

    private fun rejected(reason: PairingRequestRejection, wait: Long? = null) = PairingRequestResult.Rejected(reason, wait)
    private fun hash(value: String) = PairingCodeIssuanceService.hash(value)

    private companion object { const val WINDOW_MILLIS = 60_000L }
}
