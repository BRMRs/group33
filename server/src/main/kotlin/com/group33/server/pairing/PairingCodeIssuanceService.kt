package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.PairingCodeView
import com.group33.shared.model.UserRole
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

internal fun interface PairingCodeGenerator { fun nextCode(): String }

internal class SecurePairingCodeGenerator : PairingCodeGenerator {
    private val random = SecureRandom()
    override fun nextCode(): String = random.nextInt(100_000_000).toString().padStart(8, '0')
}

internal enum class CodeIssueRejection {
    ROLE_FORBIDDEN, IDEMPOTENCY_KEY_REUSED, RATE_LIMITED, OPERATION_IN_PROGRESS, CODE_GENERATION_UNAVAILABLE,
}

internal sealed interface CodeIssuanceResult {
    class Issued(val view: PairingCodeView) : CodeIssuanceResult {
        override fun toString() = "Issued(REDACTED)"
    }
    data class Rejected(val reason: CodeIssueRejection, val retryAfterSeconds: Long? = null) : CodeIssuanceResult
}

/** Plain application service; future HTTP wiring supplies a verified actor, never a caller-selected elderId. */
internal class PairingCodeIssuanceService(
    private val store: PairingCodeStore,
    private val clock: Clock = Clock.systemUTC(),
    private val generator: PairingCodeGenerator = SecurePairingCodeGenerator(),
) {
    fun issue(actor: AnonymousUser, operationId: UUID): CodeIssuanceResult {
        if (actor.role != UserRole.ELDER) return CodeIssuanceResult.Rejected(CodeIssueRejection.ROLE_FORBIDDEN)
        val key = CodeIssueKey(actor.id, operationId)
        // The existing issue route has an empty JSON body and a self target; no caller target is accepted.
        val binding = CodeIssueBinding(actor.installationId, actor.id,
            hash("${ContractProtocol.CANONICALIZATION_VERSION}:ISSUE_PAIRING_CODE:{}"))
        return try {
            store.transaction { tx ->
                val previous = tx.operation(key)
                if (previous != null && previous.binding != binding) {
                    return@transaction CodeIssuanceResult.Rejected(CodeIssueRejection.IDEMPOTENCY_KEY_REUSED)
                }
                previous?.receipt?.let { return@transaction CodeIssuanceResult.Issued(it.replay()) }
                fun reject(reason: CodeIssueRejection, retryAfter: Long? = null): CodeIssuanceResult.Rejected {
                    val rejected = CodeIssuanceResult.Rejected(reason, retryAfter)
                    tx.putOperation(key, CodeIssueOperation(binding, rejection = rejected))
                    return rejected
                }
                // Read time after obtaining the transaction, not while waiting for another issuer.
                val now = clock.millis()
                val cutoff = Math.subtractExact(now, RATE_WINDOW_MILLIS)
                val recent = tx.issuanceTimes(actor.id).filter { it > cutoff }
                if (recent.size >= ISSUANCE_LIMIT) {
                    val wait = Math.subtractExact(Math.addExact(recent.min(), RATE_WINDOW_MILLIS), now)
                    return@transaction reject(CodeIssueRejection.RATE_LIMITED, 1 + (wait - 1) / 1_000)
                }
                var raw: String? = null
                var codeHash: String? = null
                for (attempt in 1..MAX_GENERATION_ATTEMPTS) {
                    val candidate = generator.nextCode()
                    if (!candidate.matches(Regex("[0-9]{8}"))) {
                        return@transaction reject(CodeIssueRejection.CODE_GENERATION_UNAVAILABLE)
                    }
                    val candidateHash = hash(candidate)
                    if (!tx.hasEffectiveHash(candidateHash, now)) {
                        raw = candidate
                        codeHash = candidateHash
                        break
                    }
                }
                if (raw == null) return@transaction reject(CodeIssueRejection.CODE_GENERATION_UNAVAILABLE)
                val view = PairingCodeView(UUID.randomUUID().toString(), raw, Math.addExact(now, VALIDITY_MILLIS))
                tx.replaceActiveCode(StoredPairingCode(view.id, actor.id, checkNotNull(codeHash), view.expiresAtEpochMillis))
                tx.putIssuanceTimes(actor.id, recent + now)
                tx.putOperation(key, CodeIssueOperation(binding, receipt = CodeIssueReceipt(view)))
                CodeIssuanceResult.Issued(view)
            }
        } catch (_: CodeStoreBusy) {
            CodeIssuanceResult.Rejected(CodeIssueRejection.OPERATION_IN_PROGRESS, 1)
        }
    }

    companion object {
        private const val VALIDITY_MILLIS = 600_000L
        private const val RATE_WINDOW_MILLIS = 60_000L
        private const val ISSUANCE_LIMIT = 3
        private const val MAX_GENERATION_ATTEMPTS = 32
        internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
