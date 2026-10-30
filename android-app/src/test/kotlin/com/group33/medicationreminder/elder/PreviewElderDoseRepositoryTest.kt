package com.group33.medicationreminder.elder

import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.contract.OccurrenceKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreviewElderDoseRepositoryTest {
    private val occurrence = OccurrenceKey("preview-plan-a", "2026-10-22", "08:00")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `both actions accept one millisecond before deadline`() = runBlocking {
        for (kind in DoseActionKind.entries) {
            val repository = repositoryAt("2026-10-22T01:59:59.999Z")
            val result = repository.recordAction(occurrence, kind)
            assertTrue(result is Outcome.Success)
            assertEquals(Instant.parse("2026-10-22T01:59:59.999Z").toEpochMilli(),
                (result as Outcome.Success).value.recordedAtEpochMillis)
        }
    }

    @Test
    fun `both actions reject before planned time at deadline and after deadline`() = runBlocking {
        for (time in listOf("2026-10-21T23:59:59.999Z", "2026-10-22T02:00:00Z", "2026-10-22T02:00:00.001Z")) {
            for (kind in DoseActionKind.entries) {
                val repository = repositoryAt(time)
                assertTrue(repository.recordAction(occurrence, kind) is Outcome.Failure)
                assertNull(repository.statusOf(occurrence))
            }
        }
    }

    @Test
    fun `old occurrence cannot reopen in next days response window`() = runBlocking {
        val repository = repositoryAt("2026-10-23T00:30:00Z")
        assertTrue(repository.recordAction(occurrence, DoseActionKind.TAKEN) is Outcome.Failure)
        assertNull(repository.statusOf(occurrence))
    }

    private fun repositoryAt(instant: String) =
        PreviewElderDoseRepository(Clock.fixed(Instant.parse(instant), zone))
}
