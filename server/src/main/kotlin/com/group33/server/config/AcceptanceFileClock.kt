package com.group33.server.config

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.Channels
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** CI-only mutable time seam. Explicitly unavailable to the PostgreSQL runtime. */
internal class AcceptanceFileClock(
    private val file: Path,
    private val fallback: Clock = Clock.systemUTC(),
    private val clockZone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    init {
        require(file.isAbsolute && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
    }

    override fun getZone(): ZoneId = clockZone
    override fun withZone(zone: ZoneId): Clock =
        if (zone == clockZone) this else AcceptanceFileClock(file, fallback.withZone(zone), zone)
    override fun instant(): Instant = Instant.ofEpochMilli(millis())
    override fun millis(): Long {
        val options = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        val value = Files.newByteChannel(file, options).use { channel ->
            Channels.newReader(channel, Charsets.UTF_8).use { it.readText() }
        }.trim()
        if (value == "SYSTEM") return fallback.millis()
        return checkNotNull(value.toLongOrNull()?.takeIf { it > 0 }) {
            "Acceptance clock file must contain SYSTEM or a positive epoch millisecond"
        }
    }
}

internal fun serverClock(
    fileSetting: String?,
    persistenceMode: String,
    fallback: Clock = Clock.systemUTC(),
): Clock {
    if (fileSetting.isNullOrBlank()) return fallback
    require(persistenceMode == "memory") { "Acceptance clock is restricted to memory persistence" }
    return AcceptanceFileClock(Path.of(fileSetting), fallback)
}
