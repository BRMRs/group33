package com.group33.medicationreminder.core.data.journal

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.group33.medicationreminder.core.data.contract.CommandContext
import com.group33.medicationreminder.core.data.contract.OnlineCommandKind
import com.group33.medicationreminder.core.data.contract.OnlineCommandRecord
import com.group33.medicationreminder.core.data.contract.OnlineCommandState
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.core.data.contract.CacheState
import com.group33.medicationreminder.core.data.contract.CommandResultKind
import com.group33.medicationreminder.core.data.contract.CommandResultRef
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.model.ApiError
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Private local infrastructure. The host supplies the trusted session/environment scope. */
class RoomCommandJournal private constructor(
    private val db: CommandJournalDatabase,
    val scope: RepositoryScope,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private fun ensureOpen() = check(!closed.get()) { "Command journal is closed" }

    suspend fun begin(kind: OnlineCommandKind, targetId: String?): CommandContext {
        ensureOpen()
        val command = CommandContext(UUID.randomUUID().toString(), scope, kind, targetId)
        db.commands().insert(CommandRow(command.operationId, kind.name, targetId))
        return command
    }

    fun observe(): Flow<List<OnlineCommandRecord>> = flow {
        ensureOpen()
        emitAll(db.commands().observe().map { rows -> rows.map { it.record() } })
    }

    suspend fun find(operationId: String): OnlineCommandRecord? {
        ensureOpen()
        return db.commands().find(operationId)?.record()
    }

    private fun CommandRow.record() =
        OnlineCommandRecord(operationId, scope, OnlineCommandKind.valueOf(kind), targetId,
            OnlineCommandState.valueOf(state), resultKind?.let { kind ->
                CommandResultRef(CommandResultKind.valueOf(kind), checkNotNull(resultId))
            }, cacheState?.let(CacheState::valueOf), rejectionJson?.let { json ->
                RepositoryError.Remote(ContractProtocol.json.decodeFromString(ApiError.serializer(), json))
            })

    suspend fun bind(command: CommandContext, canonicalPayload: ByteArray): Boolean {
        ensureOpen()
        val payload = canonicalPayload.copyOf()
        return db.withTransaction {
            val row = matching(command) ?: return@withTransaction false
            if (row.payload != null) row.payload.contentEquals(payload)
            else {
                db.commands().update(row.copy(payload = payload))
                true
            }
        }
    }

    private suspend fun matching(command: CommandContext): CommandRow? {
        if (command.scope != scope) return null
        return db.commands().find(command.operationId)?.takeIf {
            it.kind == command.commandKind.name && it.targetId == command.targetId &&
                it.canonicalVersion == command.canonicalizationVersion
        }
    }

    /** Persist AWAITING before handing bytes to transport; this does not prove a network send. */
    suspend fun claim(command: CommandContext, mode: ClaimMode, now: JournalTime, leaseMillis: Long): CommandClaim? {
        ensureOpen()
        require(leaseMillis > 0)
        val until = Math.addExact(now.elapsedMillis, leaseMillis)
        return db.withTransaction {
            val row = matching(command) ?: return@withTransaction null
            val payload = row.payload ?: return@withTransaction null
            if (row.claimToken != null && row.claimBoot == now.bootSession &&
                checkNotNull(row.claimUntil) > now.elapsedMillis) return@withTransaction null
            val allowed = when (OnlineCommandState.valueOf(row.state)) {
                OnlineCommandState.NOT_SUBMITTED, OnlineCommandState.REJECTED -> mode == ClaimMode.EXPLICIT
                OnlineCommandState.AWAITING_CONFIRMATION -> mode == ClaimMode.RESUME_UNKNOWN
                OnlineCommandState.CONFIRMED -> false
            }
            if (!allowed) return@withTransaction null
            val token = UUID.randomUUID().toString()
            db.commands().update(row.copy(state = OnlineCommandState.AWAITING_CONFIRMATION.name,
                claimToken = token, claimBoot = now.bootSession, claimUntil = until, rejectionJson = null))
            CommandClaim(command, token, payload)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) db.close()
    }

    suspend fun markCacheApplied(command: CommandContext): Boolean {
        ensureOpen()
        return db.withTransaction {
            val row = matching(command) ?: return@withTransaction false
            if (row.state != OnlineCommandState.CONFIRMED.name) return@withTransaction false
            db.commands().update(row.copy(cacheState = CacheState.APPLIED.name))
            true
        }
    }

    /** Only the current token can finish. An expired owner loses authority once replaced. */
    suspend fun finish(claim: CommandClaim, completion: CommandCompletion): Boolean {
        ensureOpen()
        return db.withTransaction {
            val row = matching(claim.command) ?: return@withTransaction false
            if (row.claimToken != claim.token) return@withTransaction false
            val updated = when (completion) {
                is CommandCompletion.Rejected -> row.copy(state = OnlineCommandState.REJECTED.name,
                    rejectionJson = ContractProtocol.json.encodeToString(ApiError.serializer(), completion.error.error))
                CommandCompletion.Unknown -> row.copy(state = OnlineCommandState.AWAITING_CONFIRMATION.name)
                is CommandCompletion.Confirmed -> row.copy(state = OnlineCommandState.CONFIRMED.name,
                    resultKind = completion.resultRef.kind.name, resultId = completion.resultRef.id,
                    cacheState = completion.cacheState.name)
            }
            db.commands().update(updated.copy(claimToken = null, claimBoot = null, claimUntil = null))
            true
        }
    }

    companion object {
        fun open(context: Context, scope: RepositoryScope, namespace: String = "command-journal"): RoomCommandJournal {
            require(namespace.matches(Regex("[A-Za-z0-9_-]+")))
            val identity = listOf(scope.environmentId, scope.installationId, scope.userId)
                .joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val db = Room.databaseBuilder(context.applicationContext, CommandJournalDatabase::class.java,
                File(context.noBackupFilesDir, "$namespace-$hash.db").absolutePath)
                .enableMultiInstanceInvalidation().build()
            return RoomCommandJournal(db, scope)
        }
    }
}
