package com.group33.medicationreminder.core.data.journal

import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.OnlineCommandKind
import com.group33.medicationreminder.core.data.contract.OnlineCommandState
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.core.data.contract.CacheState
import com.group33.medicationreminder.core.data.contract.CommandResultKind
import com.group33.medicationreminder.core.data.contract.CommandResultRef
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.shared.model.ApiError
import com.group33.shared.model.FieldError
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Uses real file-backed Room through the public journal, never DAO queries or in-memory fakes. */
class RoomCommandJournalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = RepositoryScope("fictional-cloud", "fictional-install", "fictional-user")
    private val namespace = "journal-test-${UUID.randomUUID()}"
    private val opened = mutableListOf<RoomCommandJournal>()
    private fun open(s: RepositoryScope = scope) = RoomCommandJournal.open(context, s, namespace).also { opened += it }

    @After fun cleanup() {
        opened.forEach { it.close() }
        context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(namespace) }?.forEach { it.delete() }
    }

    @Test fun commandIdentitySurvivesClosingAndReopeningDatabase() = runBlocking {
        val first = open()
        val command = first.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        first.close()
        val record = checkNotNull(open().find(command.operationId))
        assertEquals(command.operationId, record.operationId)
        assertEquals(scope, record.scope)
        assertEquals("fictional-plan", record.targetId)
        assertEquals(OnlineCommandState.NOT_SUBMITTED, record.state)
    }

    @Test fun originalPayloadBindingSurvivesReopenAndCannotBeReplaced() = runBlocking {
        val first = open()
        val command = first.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        assertTrue(first.bind(command, "original".toByteArray()))
        first.close()
        val reopened = open()
        assertTrue(reopened.bind(command, "original".toByteArray()))
        assertFalse(reopened.bind(command, "changed".toByteArray()))
        assertEquals(OnlineCommandState.NOT_SUBMITTED, reopened.find(command.operationId)?.state)
    }

    @Test fun independentConnectionsHaveOnlyOneClaimWinner() = runBlocking {
        val first = open()
        val second = open()
        val command = first.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        first.bind(command, "original".toByteArray())
        val start = CompletableDeferred<Unit>()
        val attempts = listOf(first, second).map { journal ->
            async(Dispatchers.IO) {
                start.await()
                journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 100), 500)
            }
        }
        start.complete(Unit)
        val winners = attempts.awaitAll().filterNotNull()
        assertEquals(1, winners.size)
        assertEquals(command, winners.single().command)
        assertArrayEquals("original".toByteArray(), winners.single().payloadBytes())
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, first.find(command.operationId)?.state)
    }

    @Test fun abandonedClaimRecoversAtExpiryAndOldOwnerCannotOverwriteResult() = runBlocking {
        val first = open()
        val command = first.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        first.bind(command, "original".toByteArray())
        val old = checkNotNull(first.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 100), 500))
        first.close()
        val reopened = open()
        assertNull(reopened.claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-a", 599), 500))
        val replacement = checkNotNull(reopened.claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-a", 600), 500))
        assertEquals(command, replacement.command)
        assertArrayEquals("original".toByteArray(), replacement.payloadBytes())
        val result = CommandResultRef(CommandResultKind.REMINDER_PLAN, "accepted-plan")
        assertFalse(reopened.finish(old, CommandCompletion.Confirmed(result, CacheState.APPLIED)))
        assertTrue(reopened.finish(replacement, CommandCompletion.Confirmed(result, CacheState.REFRESH_REQUIRED)))
        reopened.close()
        val record = checkNotNull(open().find(command.operationId))
        assertEquals(OnlineCommandState.CONFIRMED, record.state)
        assertEquals(result, record.resultRef)
        assertEquals(CacheState.REFRESH_REQUIRED, record.cacheState)
        assertNull(open().claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-a", 2000), 500))
    }

    @Test fun unknownCompletionResumesOnlyTheOriginalBoundRequest() = runBlocking {
        val journal = open()
        val command = journal.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        assertNull(journal.claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-a", 0), 500))
        assertNull(journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), 500))
        journal.bind(command, "original".toByteArray())
        assertNull(journal.claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-a", 0), 500))
        val original = checkNotNull(journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), 500))
        assertTrue(journal.finish(original, CommandCompletion.Unknown))
        assertFalse(journal.finish(original, CommandCompletion.Unknown))
        journal.close()
        val reopened = open()
        assertNull(reopened.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 1), 500))
        val resumed = checkNotNull(reopened.claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-a", 1), 500))
        assertEquals(command, resumed.command)
        assertArrayEquals("original".toByteArray(), resumed.payloadBytes())
    }

    @Test fun rejectionSurvivesReopenAndRequiresExplicitOriginalRequestRetry() = runBlocking {
        val journal = open()
        val command = journal.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        journal.bind(command, "original".toByteArray())
        val claim = checkNotNull(journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), 500))
        val error = RepositoryError.Remote(ApiError("VALIDATION_FAILED", "fictional-message", false,
            "fictional-request", listOf(FieldError("name", "REQUIRED")), "fictional-reason"))
        assertTrue(journal.finish(claim, CommandCompletion.Rejected(error)))
        journal.close()
        val reopened = open()
        assertEquals(OnlineCommandState.REJECTED, reopened.find(command.operationId)?.state)
        assertEquals(error, reopened.find(command.operationId)?.error)
        assertNull(reopened.claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-a", 1), 500))
        assertFalse(reopened.bind(command, "corrected".toByteArray()))
        val retry = checkNotNull(reopened.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 1), 500))
        assertEquals(command, retry.command)
        assertArrayEquals("original".toByteArray(), retry.payloadBytes())
        assertNull(reopened.find(command.operationId)?.error)
    }

    @Test fun confirmedCommandOnlyReconcilesCacheAndNeverDispatchesAgain() = runBlocking {
        val journal = open()
        val command = journal.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        assertFalse(journal.markCacheApplied(command))
        journal.bind(command, "original".toByteArray())
        val claim = checkNotNull(journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), 500))
        val result = CommandResultRef(CommandResultKind.REMINDER_PLAN, "accepted-plan")
        journal.finish(claim, CommandCompletion.Confirmed(result, CacheState.REFRESH_REQUIRED))
        assertFalse(journal.markCacheApplied(command.copy(targetId = "different-plan")))
        assertTrue(journal.markCacheApplied(command))
        assertFalse(journal.finish(claim, CommandCompletion.Unknown))
        journal.close()
        val reopened = open()
        assertEquals(result, reopened.find(command.operationId)?.resultRef)
        assertEquals(CacheState.APPLIED, reopened.find(command.operationId)?.cacheState)
        for (mode in ClaimMode.entries) {
            assertNull(reopened.claim(command, mode, JournalTime("boot-b", 0), 500))
        }
    }

    @Test fun observersRecoverIdentityAndSeeOtherConnectionStateChangesWithoutPayload() = runBlocking {
        val observer = open()
        val updates = Channel<List<com.group33.medicationreminder.core.data.contract.OnlineCommandRecord>>(Channel.UNLIMITED)
        val job = launch { observer.observe().collect { updates.send(it) } }
        try {
            withTimeout(5000) {
                assertTrue(updates.receive().isEmpty())
                val writer = open()
                val command = writer.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
                val created = updates.receive().single()
                assertEquals(command.operationId, created.operationId)
                assertEquals(OnlineCommandState.NOT_SUBMITTED, created.state)
                writer.bind(command, "private-payload".toByteArray())
                val claim = checkNotNull(writer.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), 500))
                var record = updates.receive().single()
                while (record.state != OnlineCommandState.AWAITING_CONFIRMATION) record = updates.receive().single()
                assertFalse(record.toString().contains("private-payload"))
                assertFalse(claim.toString().contains("private-payload"))
            }
        } finally {
            job.cancel()
            updates.close()
        }
    }

    @Test fun environmentInstallationAndUserAreIsolatedAndContextCannotBeForged() = runBlocking {
        val owner = open()
        val command = owner.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        owner.bind(command, "original".toByteArray())
        val claim = checkNotNull(owner.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), 500))
        for (foreignScope in listOf(scope.copy(environmentId = "local"),
            scope.copy(installationId = "other-install"), scope.copy(userId = "other-user"))) {
            val foreign = open(foreignScope)
            assertNull(foreign.find(command.operationId))
            assertFalse(foreign.bind(command, "original".toByteArray()))
            assertNull(foreign.claim(command.copy(scope = foreignScope), ClaimMode.RESUME_UNKNOWN, JournalTime("boot-b", 0), 500))
            assertFalse(foreign.finish(claim, CommandCompletion.Unknown))
        }
        for (forged in listOf(command.copy(targetId = "other-plan"),
            command.copy(commandKind = OnlineCommandKind.DEACTIVATE_PLAN))) {
            assertFalse(owner.bind(forged, "original".toByteArray()))
            assertNull(owner.claim(forged, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-b", 0), 500))
        }
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, owner.find(command.operationId)?.state)
        val first = open(RepositoryScope("a", "bc", "d"))
        val another = open(RepositoryScope("ab", "c", "d"))
        val distinct = first.begin(OnlineCommandKind.ISSUE_PAIRING_CODE, null)
        assertNull(another.find(distinct.operationId))
    }

    @Test fun newBootRecoversOriginalBytesAndCallerCannotMutateStoredPayload() = runBlocking {
        val journal = open()
        val command = journal.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        val bytes = "original".toByteArray()
        journal.bind(command, bytes)
        bytes.fill(0)
        val old = checkNotNull(journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 10_000), 500))
        old.payloadBytes().fill(0)
        journal.close()
        val reopened = open()
        val recovered = checkNotNull(reopened.claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-b", 0), 500))
        assertArrayEquals("original".toByteArray(), recovered.payloadBytes())
        assertFalse(reopened.finish(old, CommandCompletion.Unknown))
        assertTrue(reopened.finish(recovered, CommandCompletion.Unknown))
    }

    @Test fun invalidLeaseAndClosedStorageNeverClaimSuccess() = runBlocking {
        val journal = open()
        val command = journal.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        journal.bind(command, "original".toByteArray())
        for (lease in listOf(0L, -1L)) {
            try {
                journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), lease)
                fail("Invalid lease must fail before writing")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", Long.MAX_VALUE), 1)
            fail("Overflow must fail before writing")
        } catch (_: ArithmeticException) { }
        journal.close()
        try {
            journal.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), 500)
            fail("Closed storage must not return a successful claim")
        } catch (_: IllegalStateException) { }
        assertEquals(OnlineCommandState.NOT_SUBMITTED, open().find(command.operationId)?.state)
    }

    @Test fun simultaneousUnknownRecoveryHasOneWinnerAcrossIndependentConnections() = runBlocking {
        val first = open()
        val second = open()
        val command = first.begin(OnlineCommandKind.CONFIRM_PLAN, "fictional-plan")
        first.bind(command, "original".toByteArray())
        val abandoned = checkNotNull(first.claim(command, ClaimMode.EXPLICIT, JournalTime("boot-a", 0), 500))
        val start = CompletableDeferred<Unit>()
        val attempts = listOf(first, second).map { journal ->
            async(Dispatchers.IO) {
                start.await()
                journal.claim(command, ClaimMode.RESUME_UNKNOWN, JournalTime("boot-a", 500), 500)
            }
        }
        start.complete(Unit)
        val winners = attempts.awaitAll().filterNotNull()
        assertEquals(1, winners.size)
        assertEquals(command, winners.single().command)
        assertArrayEquals("original".toByteArray(), winners.single().payloadBytes())
        assertFalse(first.finish(abandoned, CommandCompletion.Unknown))
        assertTrue(second.finish(winners.single(), CommandCompletion.Unknown))
    }
}
