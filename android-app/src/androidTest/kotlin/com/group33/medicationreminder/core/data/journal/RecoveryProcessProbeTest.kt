package com.group33.medicationreminder.core.data.journal

import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit two-process/real-reboot probe on a designated disposable emulator only. */
class RecoveryProcessProbeTest {
    @Test fun platform_clock_and_optional_process_reboot_probe() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val first = AndroidJournalClock(context).now()
        val second = AndroidJournalClock(context).now()
        assertEquals(first.bootSession, second.bootSession)
        assertTrue(second.elapsedMillis >= first.elapsedMillis)
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("recoveryProbe") != "s1-20261029") return@runBlocking
        val namespace = "recovery-process-test-s1-20261029"
        val expected = File(context.noBackupFilesDir, "$namespace.expected")
        val scope = RepositoryScope("probe-cloud", "probe-install", "probe-user")
        val journal = RoomCommandJournal.open(context, scope, namespace)
        try {
            when (args.getString("phase")) {
                "prepare" -> {
                    assertFalse(expected.exists())
                    val command = journal.begin(OnlineCommandKind.UPDATE_PLAN, "probe-plan")
                    assertTrue(journal.bind(command, "probe-original-payload".toByteArray()))
                    assertNotNull(journal.claim(command, ClaimMode.EXPLICIT, first, 125_000))
                    expected.writeText(listOf(command.operationId, android.os.Process.myPid().toString(),
                        first.bootSession, first.elapsedMillis.toString()).joinToString("\n"))
                }
                "verify-process" -> {
                    val saved = expected.readLines()
                    assertNotEquals(saved[1].toInt(), android.os.Process.myPid())
                    assertEquals(saved[2], first.bootSession)
                    assertTrue(first.elapsedMillis - saved[3].toLong() < 125_000)
                    val command = CommandContext(saved[0], scope, OnlineCommandKind.UPDATE_PLAN, "probe-plan")
                    assertNull(journal.claim(command, ClaimMode.RESUME_UNKNOWN, first, 125_000))
                    assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, journal.find(saved[0])?.state)
                }
                "verify-reboot" -> {
                    val saved = expected.readLines()
                    assertNotEquals(saved[2], first.bootSession)
                    val session = object : RecoverySession {
                        override val scope = scope
                        override suspend fun isCurrent() = true
                    }
                    var sends = 0
                    val adapter = object : CommandRecoveryAdapter {
                        override suspend fun execute(claim: CommandClaim, session: RecoverySession): RecoveryReply {
                            sends++
                            assertEquals(saved[0], claim.command.operationId)
                            assertArrayEquals("probe-original-payload".toByteArray(), claim.payloadBytes())
                            return RecoveryReply.Confirmed(CommandResultRef(CommandResultKind.REMINDER_PLAN, "probe-result"))
                        }
                        override suspend fun reconcile(record: OnlineCommandRecord, session: RecoverySession) = Outcome.Success(Unit)
                    }
                    val coordinator = CommandRecoveryCoordinator(journal, RecoveryAccess { Outcome.Success(session) },
                        mapOf(OnlineCommandKind.UPDATE_PLAN to adapter), AndroidJournalClock(context)::now)
                    val result = coordinator.resume(saved[0])
                    assertTrue(result is Outcome.Success)
                    assertEquals(CacheState.APPLIED, (result as Outcome.Success).value.cacheState)
                    assertEquals(1, sends)
                    expected.delete()
                }
                else -> error("Explicit probe phase required")
            }
        } finally { journal.close() }
        if (!expected.exists()) context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(namespace) }?.forEach { it.delete() }
    }
}
