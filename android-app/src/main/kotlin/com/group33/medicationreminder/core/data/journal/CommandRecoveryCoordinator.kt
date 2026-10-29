package com.group33.medicationreminder.core.data.journal

import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/** Captured by trusted session infrastructure, never created from a UI-selected actor. */
internal interface RecoverySession {
    val scope: RepositoryScope
    suspend fun isCurrent(): Boolean
}

/** Checks connectivity and captures one authenticated generation for the whole attempt. */
internal fun interface RecoveryAccess {
    suspend fun acquire(): Outcome<RecoverySession>
}

internal sealed interface RecoveryReply {
    data class Confirmed(val resultRef: CommandResultRef) : RecoveryReply
    data class Rejected(val error: RepositoryError.Remote) : RecoveryReply
    data object Unknown : RecoveryReply
    /** Existing 409 OPERATION_IN_PROGRESS, Retry-After: 1. No polling endpoint. */
    data object InProgress : RecoveryReply
}

/** Feature infrastructure owns HTTP decoding and idempotent scoped Room cache reconciliation. */
internal interface CommandRecoveryAdapter {
    suspend fun execute(claim: CommandClaim, session: RecoverySession): RecoveryReply
    suspend fun reconcile(record: OnlineCommandRecord, session: RecoverySession): Outcome<Unit>
}

/**
 * Recovery only, over the merged private journal. Feature writers still canonicalize/bind inputs
 * and perform their initial online submission. No fake or adapter is registered in production.
 */
internal class CommandRecoveryCoordinator(
    private val journal: RoomCommandJournal,
    private val access: RecoveryAccess,
    private val adapters: Map<OnlineCommandKind, CommandRecoveryAdapter>,
    private val now: suspend () -> JournalTime,
) : OnlineCommandRepository {
    override suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext> = storage {
        Outcome.Success(journal.begin(kind, targetId))
    }

    override fun observeCommands(): Flow<List<OnlineCommandRecord>> = journal.observe()

    override suspend fun resume(operationId: String): Outcome<OnlineCommandRecord> = recover(operationId, false)

    override suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord> = recover(operationId, true)

    private suspend fun recover(id: String, explicit: Boolean): Outcome<OnlineCommandRecord> = storage {
        val record = journal.find(id) ?: return@storage failure(LocalFailureCode.INVALID_INPUT)
        val eligible = if (explicit) record.state == OnlineCommandState.REJECTED else
            record.state == OnlineCommandState.AWAITING_CONFIRMATION || record.state == OnlineCommandState.CONFIRMED
        // A background scan can safely include unattempted and rejected entries without sending them.
        if (!eligible || record.state == OnlineCommandState.CONFIRMED && record.cacheState == CacheState.APPLIED) {
            return@storage Outcome.Success(record)
        }
        val adapter = adapters[record.commandKind] ?: return@storage failure(LocalFailureCode.INVALID_INPUT)
        val acquired = access.acquire()
        if (acquired is Outcome.Failure) return@storage acquired
        val session = (acquired as Outcome.Success).value
        if (!current(session)) return@storage failure(LocalFailureCode.SESSION_NOT_READY)
        if (record.state == OnlineCommandState.CONFIRMED) return@storage reconcile(record, adapter, session)

        val command = CommandContext(record.operationId, record.scope, record.commandKind, record.targetId)
        val claim = journal.claim(command, if (explicit) ClaimMode.EXPLICIT else ClaimMode.RESUME_UNKNOWN,
            now(), CLAIM_MILLIS) ?: return@storage latest(id)
        // Claim can wait for a Room transaction. Re-check before handing private bytes to transport.
        if (!current(session)) return@storage failure(LocalFailureCode.SESSION_NOT_READY)
        val reply = try {
            withTimeoutOrNull(HTTP_MILLIS) { adapter.execute(claim, session) } ?: RecoveryReply.Unknown
        } catch (cancelled: CancellationException) {
            // May already have reached the server. Persisted AWAITING and claim survive cancellation.
            throw cancelled
        } catch (_: Exception) {
            RecoveryReply.Unknown
        }
        if (!current(session)) return@storage failure(LocalFailureCode.SESSION_NOT_READY)
        val completion = when (reply) {
            is RecoveryReply.Confirmed -> CommandCompletion.Confirmed(reply.resultRef, CacheState.REFRESH_REQUIRED)
            is RecoveryReply.Rejected -> if (reply.error.error.code == "OPERATION_IN_PROGRESS") {
                delay(1_000)
                CommandCompletion.Unknown
            } else CommandCompletion.Rejected(reply.error)
            RecoveryReply.InProgress -> {
                // Keep the durable claim during the wait; a concurrent/process-restored caller
                // cannot ignore Retry-After by seeing a released unknown claim immediately.
                delay(1_000)
                CommandCompletion.Unknown
            }
            RecoveryReply.Unknown -> CommandCompletion.Unknown
        }
        if (!current(session)) return@storage failure(LocalFailureCode.SESSION_NOT_READY)
        if (!journal.finish(claim, completion)) return@storage latest(id)
        val finished = journal.find(id) ?: return@storage failure(LocalFailureCode.STORAGE_UNAVAILABLE)
        if (finished.state == OnlineCommandState.CONFIRMED) reconcile(finished, adapter, session)
        else Outcome.Success(finished)
    }

    private suspend fun reconcile(
        record: OnlineCommandRecord,
        adapter: CommandRecoveryAdapter,
        session: RecoverySession,
    ): Outcome<OnlineCommandRecord> {
        if (!current(session)) return failure(LocalFailureCode.SESSION_NOT_READY)
        val applied = try {
            adapter.reconcile(record, session) is Outcome.Success
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (!current(session)) return failure(LocalFailureCode.SESSION_NOT_READY)
        // Never turn an accepted server receipt into a failed save when cache application fails.
        if (applied) journal.markCacheApplied(CommandContext(record.operationId, record.scope,
            record.commandKind, record.targetId))
        return latest(record.operationId)
    }

    private suspend fun current(session: RecoverySession) = session.scope == journal.scope && session.isCurrent()
    private suspend fun latest(id: String): Outcome<OnlineCommandRecord> = journal.find(id)?.let { Outcome.Success(it) }
        ?: failure(LocalFailureCode.STORAGE_UNAVAILABLE)

    private suspend fun <T> storage(block: suspend () -> Outcome<T>): Outcome<T> = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { failure(LocalFailureCode.STORAGE_UNAVAILABLE) }

    private fun failure(code: LocalFailureCode) = Outcome.Failure(RepositoryError.Local(code))

    companion object {
        private const val HTTP_MILLIS = 120_000L
        private const val CLAIM_MILLIS = HTTP_MILLIS + 5_000L
    }
}
