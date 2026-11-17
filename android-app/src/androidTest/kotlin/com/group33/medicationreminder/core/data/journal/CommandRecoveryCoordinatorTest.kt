package com.group33.medicationreminder.core.data.journal

import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.model.ApiError
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real private Room journal; scripted transport/session boundaries, no HTTP integration claim. */
class CommandRecoveryCoordinatorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = RepositoryScope("fictional-cloud", "fictional-install", "fictional-user")
    private val namespace = "recovery-test-${UUID.randomUUID()}"
    private val opened = mutableListOf<RoomCommandJournal>()
    private val payload = "fictional-original-canonical-request".toByteArray()
    private val result = CommandResultRef(CommandResultKind.REMINDER_PLAN, "fictional-plan")
    private val rejected = RepositoryError.Remote(ApiError("REVISION_CONFLICT", "fixture", false, "fixture-request"))
    private var time = JournalTime("fixture-boot", 1_000)
    private val calls = AtomicInteger()
    private var reconciliations = 0
    private val received = mutableListOf<Pair<String, ByteArray>>()
    @Volatile private var valid = true
    private var acquired: Outcome<RecoverySession> = Outcome.Success(object : RecoverySession {
        override val scope = this@CommandRecoveryCoordinatorTest.scope
        override suspend fun isCurrent() = valid
    })
    private var execute: suspend (CommandClaim) -> RecoveryReply = { RecoveryReply.Confirmed(result) }
    private var apply: suspend (OnlineCommandRecord) -> Outcome<Unit> = { Outcome.Success(Unit) }
    private val adapter = object : CommandRecoveryAdapter {
        override suspend fun execute(claim: CommandClaim, session: RecoverySession): RecoveryReply {
            calls.incrementAndGet()
            synchronized(received) { received += claim.command.operationId to claim.payloadBytes() }
            return execute(claim)
        }
        override suspend fun reconcile(record: OnlineCommandRecord, session: RecoverySession): Outcome<Unit> {
            reconciliations++
            return apply(record)
        }
    }
    private fun open() = RoomCommandJournal.open(context, scope, namespace).also { opened += it }
    private fun coordinator(journal: RoomCommandJournal) = CommandRecoveryCoordinator(journal,
        RecoveryAccess { acquired }, mapOf(OnlineCommandKind.UPDATE_PLAN to adapter), { time })
    private fun <T> success(outcome: Outcome<T>): T {
        assertTrue(outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }
    private suspend fun awaiting(journal: RoomCommandJournal): CommandContext {
        val c = journal.begin(OnlineCommandKind.UPDATE_PLAN, "fictional-plan")
        assertTrue(journal.bind(c, payload))
        val claim = checkNotNull(journal.claim(c, ClaimMode.EXPLICIT, time, 1))
        assertTrue(journal.finish(claim, CommandCompletion.Unknown))
        return c
    }

    @After fun cleanup() {
        opened.forEach { it.close() }
        context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(namespace) }?.forEach { it.delete() }
    }

    @Test fun begin_observe_and_background_resume_never_submit_unattempted_or_rejected() = runBlocking {
        val j = open(); val c = coordinator(j)
        val fresh = success(c.begin(OnlineCommandKind.UPDATE_PLAN, "fictional-plan"))
        assertEquals(OnlineCommandState.NOT_SUBMITTED, success(c.resume(fresh.operationId)).state)
        val old = awaiting(j)
        val claim = checkNotNull(j.claim(old, ClaimMode.RESUME_UNKNOWN, time, 1))
        j.finish(claim, CommandCompletion.Rejected(rejected))
        assertEquals(OnlineCommandState.REJECTED, success(c.resume(old.operationId)).state)
        assertEquals(2, c.observeCommands().first().size)
        assertEquals(0, calls.get())
        assertEquals(0, reconciliations)
    }

    @Test fun fresh_independent_writer_notifies_active_observer_on_first_commit() = runBlocking {
        repeat(20) { count ->
            val observer = open()
            val updates = Channel<List<OnlineCommandRecord>>(Channel.UNLIMITED)
            val job = launch { observer.observe().collect { updates.send(it) } }
            try {
                withTimeout(3_000) {
                    assertEquals(count, updates.receive().size)
                    val writer = open()
                    val command = success(coordinator(writer).begin(OnlineCommandKind.UPDATE_PLAN, "fictional-plan"))
                    var rows = updates.receive()
                    while (rows.none { it.operationId == command.operationId }) rows = updates.receive()
                    assertEquals(OnlineCommandState.NOT_SUBMITTED, rows.single { it.operationId == command.operationId }.state)
                    writer.close()
                }
            } finally {
                job.cancelAndJoin()
                updates.close()
                observer.close()
            }
        }
    }

    @Test fun reopen_unknown_response_loss_reuses_original_key_payload_and_result() = runBlocking {
        val j = open(); val command = awaiting(j)
        j.close()
        val c = coordinator(open())
        execute = { throw IOException("fictional lost response") }
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, success(c.resume(command.operationId)).state)
        execute = { RecoveryReply.Confirmed(result) }
        val confirmed = success(c.resume(command.operationId))
        assertEquals(OnlineCommandState.CONFIRMED, confirmed.state)
        assertEquals(result, confirmed.resultRef)
        assertEquals(CacheState.APPLIED, confirmed.cacheState)
        assertEquals(2, calls.get())
        received.forEach { (id, bytes) -> assertEquals(command.operationId, id); assertArrayEquals(payload, bytes) }
    }

    @Test fun confirmed_cache_failure_is_still_success_and_resume_only_reconciles() = runBlocking {
        val j = open(); val command = awaiting(j); val c = coordinator(j)
        apply = { Outcome.Failure(RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE)) }
        val pending = success(c.resume(command.operationId))
        assertEquals(OnlineCommandState.CONFIRMED, pending.state)
        assertEquals(CacheState.REFRESH_REQUIRED, pending.cacheState)
        assertEquals(result, pending.resultRef)
        j.close()
        apply = { Outcome.Success(Unit) }
        val reopened = coordinator(open())
        assertEquals(CacheState.APPLIED, success(reopened.resume(command.operationId)).cacheState)
        reopened.resume(command.operationId)
        assertEquals(1, calls.get())
        assertEquals(2, reconciliations)
    }

    @Test fun journal_cache_ack_failure_returns_known_confirmation_and_reopens_for_cache_only() = runBlocking {
        val j = open(); val command = awaiting(j)
        apply = { j.close(); Outcome.Success(Unit) }
        val receipt = success(coordinator(j).resume(command.operationId))
        assertEquals(OnlineCommandState.CONFIRMED, receipt.state)
        assertEquals(result, receipt.resultRef)
        assertEquals(CacheState.REFRESH_REQUIRED, receipt.cacheState)
        val reopened = open()
        assertEquals(OnlineCommandState.CONFIRMED, reopened.find(command.operationId)?.state)
        apply = { Outcome.Success(Unit) }
        assertEquals(CacheState.APPLIED, success(coordinator(reopened).resume(command.operationId)).cacheState)
        assertEquals(1, calls.get())
    }

    @Test fun cache_local_timeout_retains_confirmed_receipt_for_later_reconciliation() = runBlocking {
        val j = open(); val command = awaiting(j)
        apply = { withTimeout(1) { delay(1_000); Outcome.Success(Unit) } }
        val receipt = success(coordinator(j).resume(command.operationId))
        assertEquals(OnlineCommandState.CONFIRMED, receipt.state)
        assertEquals(CacheState.REFRESH_REQUIRED, receipt.cacheState)
        assertEquals(result, j.find(command.operationId)?.resultRef)
        assertEquals(1, calls.get())
    }

    @Test fun parent_cancellation_during_cache_keeps_receipt_and_propagates_cancellation() = runBlocking {
        val j = open(); val command = awaiting(j)
        val entered = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        apply = { entered.complete(Unit); never.await(); Outcome.Success(Unit) }
        val job = async { coordinator(j).resume(command.operationId) }
        withTimeout(2_000) { entered.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(OnlineCommandState.CONFIRMED, j.find(command.operationId)?.state)
        assertEquals(CacheState.REFRESH_REQUIRED, j.find(command.operationId)?.cacheState)
        apply = { Outcome.Success(Unit) }
        assertEquals(CacheState.APPLIED, success(coordinator(j).resume(command.operationId)).cacheState)
        assertEquals(1, calls.get())
    }

    @Test fun rejected_requires_explicit_online_retry_and_preserves_original_intent() = runBlocking {
        val j = open(); val command = awaiting(j); val c = coordinator(j)
        execute = { RecoveryReply.Rejected(rejected) }
        assertEquals(rejected, success(c.resume(command.operationId)).error)
        c.resume(command.operationId)
        acquired = Outcome.Failure(RepositoryError.Local(LocalFailureCode.OFFLINE))
        assertEquals(acquired, c.retryRejected(command.operationId))
        assertEquals(1, calls.get())
        acquired = Outcome.Success(object : RecoverySession {
            override val scope = this@CommandRecoveryCoordinatorTest.scope
            override suspend fun isCurrent() = true
        })
        execute = { RecoveryReply.Confirmed(result) }
        assertEquals(OnlineCommandState.CONFIRMED, success(c.retryRejected(command.operationId)).state)
        assertArrayEquals(received[0].second, received[1].second)
        assertEquals(received[0].first, received[1].first)
        assertFalse(j.bind(command, "corrected-content".toByteArray()))
        val corrected = success(c.begin(OnlineCommandKind.UPDATE_PLAN, "fictional-plan"))
        assertNotEquals(command.operationId, corrected.operationId)
    }

    @Test fun concurrent_instances_send_once_and_keep_receipt_before_cache_work() = runBlocking {
        val j = open(); val command = awaiting(j)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        execute = { entered.complete(Unit); release.await(); RecoveryReply.Confirmed(result) }
        apply = { record ->
            assertEquals(result, open().find(record.operationId)?.resultRef)
            assertEquals(CacheState.REFRESH_REQUIRED, open().find(record.operationId)?.cacheState)
            Outcome.Success(Unit)
        }
        val first = async { coordinator(j).resume(command.operationId) }
        withTimeout(2_000) { entered.await() }
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION,
            success(coordinator(open()).resume(command.operationId)).state)
        release.complete(Unit)
        assertEquals(CacheState.APPLIED, success(first.await()).cacheState)
        assertEquals(1, calls.get())
    }

    @Test fun replaced_claim_fences_old_transport_result_and_prevents_cache_application() = runBlocking {
        val j = open(); val command = awaiting(j)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        execute = { entered.complete(Unit); release.await(); RecoveryReply.Confirmed(result) }
        val old = async { coordinator(j).resume(command.operationId) }
        withTimeout(2_000) { entered.await() }
        time = JournalTime("fixture-next-boot", 0)
        val next = checkNotNull(open().claim(command, ClaimMode.RESUME_UNKNOWN, time, 100))
        assertTrue(j.finish(next, CommandCompletion.Rejected(rejected)))
        release.complete(Unit)
        assertEquals(OnlineCommandState.REJECTED, success(old.await()).state)
        assertEquals(0, reconciliations)
    }

    @Test fun session_change_during_transport_leaves_unknown_and_never_applies_old_response() = runBlocking {
        val j = open(); val command = awaiting(j)
        execute = { valid = false; RecoveryReply.Confirmed(result) }
        assertEquals(Outcome.Failure(RepositoryError.Local(LocalFailureCode.SESSION_NOT_READY)),
            coordinator(j).resume(command.operationId))
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, j.find(command.operationId)?.state)
        assertNull(j.find(command.operationId)?.resultRef)
        assertEquals(0, reconciliations)
    }

    @Test fun cancellation_preserves_durable_claim_then_new_boot_can_resume_same_intent() = runBlocking {
        val j = open(); val command = awaiting(j)
        val entered = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        execute = { entered.complete(Unit); never.await(); RecoveryReply.Unknown }
        val cancelled = async { coordinator(j).resume(command.operationId) }
        withTimeout(2_000) { entered.await() }
        cancelled.cancelAndJoin()
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, j.find(command.operationId)?.state)
        coordinator(open()).resume(command.operationId)
        assertEquals(1, calls.get())
        time = JournalTime("fixture-next-boot", 0)
        execute = { RecoveryReply.Confirmed(result) }
        assertEquals(OnlineCommandState.CONFIRMED, success(coordinator(open()).resume(command.operationId)).state)
        assertEquals(2, calls.get())
        assertArrayEquals(received[0].second, received[1].second)
    }

    @Test fun transport_local_timeout_returns_unknown_without_cancelling_active_caller() = runBlocking {
        val j = open(); val command = awaiting(j)
        execute = { withTimeout(1) { delay(1_000); RecoveryReply.Confirmed(result) } }
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION,
            success(coordinator(j).resume(command.operationId)).state)
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, j.find(command.operationId)?.state)
        assertEquals(0, reconciliations)
    }

    @Test fun operation_in_progress_holds_claim_during_retry_after_and_stays_unknown() = runBlocking {
        val j = open(); val command = awaiting(j)
        val entered = CompletableDeferred<Unit>()
        execute = {
            entered.complete(Unit)
            RecoveryReply.Rejected(RepositoryError.Remote(ApiError("OPERATION_IN_PROGRESS", "fixture", true, "fixture")))
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val first = async { coordinator(j).resume(command.operationId) }
        withTimeout(2_000) { entered.await() }
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, success(coordinator(open()).resume(command.operationId)).state)
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, success(withTimeout(3_000) { first.await() }).state)
        assertTrue(android.os.SystemClock.elapsedRealtime() - started >= 1_000)
        assertEquals(1, calls.get())
        assertNull(j.find(command.operationId)?.error)
    }

    @Test fun foreign_session_missing_adapter_and_closed_storage_never_send() = runBlocking {
        val j = open(); val command = awaiting(j)
        acquired = Outcome.Success(object : RecoverySession {
            override val scope = this@CommandRecoveryCoordinatorTest.scope.copy(userId = "other-user")
            override suspend fun isCurrent() = true
        })
        assertEquals(Outcome.Failure(RepositoryError.Local(LocalFailureCode.SESSION_NOT_READY)),
            coordinator(j).resume(command.operationId))
        assertEquals(Outcome.Failure(RepositoryError.Local(LocalFailureCode.INVALID_INPUT)),
            CommandRecoveryCoordinator(j, RecoveryAccess { acquired }, emptyMap(), { time }).resume(command.operationId))
        j.close()
        assertEquals(Outcome.Failure(RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE)),
            coordinator(j).resume(command.operationId))
        assertEquals(0, calls.get())
    }
}
