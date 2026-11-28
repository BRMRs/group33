package com.group33.server.config

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AcceptanceFileClockTest {
    @TempDir lateinit var directory: Path
    private val fallback = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)

    @Test fun followsSystemUntilTheAcceptanceHostPublishesAnEpoch() {
        val file = directory.resolve("clock").also { it.toFile().writeText("SYSTEM\n") }
        val clock = serverClock(file.toString(), "memory", fallback)
        assertEquals(1234, clock.millis())
        file.toFile().writeText("5678\n")
        assertEquals(5678, clock.millis())
        val zoned = clock.withZone(ZoneOffset.ofHours(8))
        file.toFile().writeText("6789\n")
        assertEquals(6789, zoned.millis())
        assertEquals(ZoneOffset.ofHours(8), zoned.zone)
    }

    @Test fun refusesAPathReplacedByASymbolicLink() {
        val file = directory.resolve("clock").also { it.toFile().writeText("SYSTEM\n") }
        val target = directory.resolve("other").also { it.toFile().writeText("9999\n") }
        val clock = serverClock(file.toString(), "memory", fallback)
        file.toFile().delete()
        java.nio.file.Files.createSymbolicLink(file, target)
        assertThrows(java.io.IOException::class.java) { clock.millis() }
    }

    @Test fun isUnavailableToProductionPersistence() {
        val file = directory.resolve("clock").also { it.toFile().writeText("SYSTEM\n") }
        assertThrows(IllegalArgumentException::class.java) {
            serverClock(file.toString(), "postgres-local", fallback)
        }
        assertSame(fallback, serverClock(null, "postgres-local", fallback))
    }
}
