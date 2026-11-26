package com.group33.server.auth

import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress
import java.util.ArrayDeque
import org.springframework.http.HttpStatus

/** One process, bounded state, monotonic quota windows. The deployment must verify its proxy CIDRs. */
internal class TrustedProxyPhoneRateLimit(
    trustedProxyCidrs: List<String>,
    private val requestsPerMinute: Int = 30,
    private val maxClients: Int = 10_000,
    private val nanoTime: () -> Long = System::nanoTime,
) : BoundPhoneRateLimit {
    private val proxies: List<ProxyNetwork>
    private val clients = HashMap<LiteralAddress, Window>()
    private val expiry = ArrayDeque<Window>()

    init {
        require(requestsPerMinute in 1..120 && maxClients in 1..10_000) { "Invalid phone quota bounds" }
        require(trustedProxyCidrs.size in 1..16) { "Explicit trusted proxy networks are required" }
        proxies = trustedProxyCidrs.map(ProxyNetwork::parse)
    }

    @Synchronized
    override fun retryAfterSeconds(request: HttpServletRequest): Long? {
        val address = clientAddress(request)
        val now = nanoTime()
        while (expiry.isNotEmpty() && now - expiry.first.start >= MINUTE) {
            val old = expiry.removeFirst()
            clients.remove(old.address)
        }
        val existing = clients[address]
        if (existing != null) {
            if (existing.count >= requestsPerMinute) return remaining(existing, now)
            existing.count++
            return null
        }
        // Eviction must not let churned addresses reset another client's current quota.
        if (clients.size >= maxClients) return remaining(expiry.first, now)
        val window = Window(address, now)
        clients[address] = window
        expiry.addLast(window)
        return null
    }

    private fun clientAddress(request: HttpServletRequest): LiteralAddress {
        var current = LiteralAddress.parse(request.remoteAddr) ?: malformedTransport()
        if (proxies.none { it.contains(current) }) malformedTransport()
        val lines = request.getHeaders("X-Forwarded-For").asSequence().take(2).toList()
        if (lines.size != 1 || lines[0].length !in 1..2048) malformedTransport()
        val parts = lines[0].split(',')
        if (parts.size !in 1..16) malformedTransport()
        val chain = parts.map { LiteralAddress.parse(it.trim()) ?: malformedTransport() }
        // Discard only known trusted hops, from the socket towards the caller. A caller's
        // arbitrary leftmost XFF prefix never overrides the first untrusted address.
        for (hop in chain.asReversed()) {
            if (proxies.none { it.contains(current) }) return current
            current = hop
        }
        if (proxies.any { it.contains(current) }) malformedTransport()
        return current
    }

    private fun remaining(window: Window, now: Long): Long {
        val elapsed = (now - window.start).coerceAtLeast(0)
        return ((MINUTE - elapsed + SECOND - 1) / SECOND).coerceIn(1, 60)
    }

    private class Window(val address: LiteralAddress, val start: Long, var count: Int = 1)

    private companion object {
        const val SECOND = 1_000_000_000L
        const val MINUTE = 60 * SECOND
        fun malformedTransport(): Nothing = throw PhoneAuthFailure(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST")
    }
}

/** Numeric literals only: no hostname lookup, ports, zone identifiers or ambiguous IPv4 spelling. */
private class LiteralAddress private constructor(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is LiteralAddress && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "LiteralAddress(redacted)"

    companion object {
        fun parse(raw: String?): LiteralAddress? {
            if (raw == null || raw.length !in 2..45) return null
            val bytes = if (':' in raw) {
                if (!raw.matches(Regex("[0-9a-fA-F:.]+"))) return null
                try { InetAddress.getByName(raw).address } catch (_: Exception) { return null }
            } else {
                val pieces = raw.split('.')
                if (pieces.size != 4) return null
                val values = pieces.map { part ->
                    val value = part.toIntOrNull() ?: return null
                    if (value !in 0..255 || value.toString() != part) return null
                    value.toByte()
                }
                values.toByteArray()
            }
            return LiteralAddress(bytes)
        }
    }
}

private class ProxyNetwork(private val network: LiteralAddress, private val prefix: Int) {
    fun contains(address: LiteralAddress): Boolean {
        if (address.bytes.size != network.bytes.size) return false
        for (index in network.bytes.indices) {
            val bits = (prefix - index * 8).coerceIn(0, 8)
            val mask = if (bits == 0) 0 else (0xff shl (8 - bits)) and 0xff
            if ((address.bytes[index].toInt() and mask) != (network.bytes[index].toInt() and mask)) return false
        }
        return true
    }

    companion object {
        fun parse(raw: String): ProxyNetwork {
            try {
                val pieces = raw.split('/')
                require(pieces.size == 2)
                val address = checkNotNull(LiteralAddress.parse(pieces[0]))
                require(':' !in pieces[0] || address.bytes.size == 16)
                val prefix = pieces[1].toInt()
                require(prefix.toString() == pieces[1] && prefix in 1..address.bytes.size * 8)
                for (index in address.bytes.indices) {
                    val hostBits = (8 - (prefix - index * 8)).coerceIn(0, 8)
                    val hostMask = (1 shl hostBits) - 1
                    require(address.bytes[index].toInt() and hostMask == 0)
                }
                return ProxyNetwork(address, prefix)
            } catch (_: Exception) {
                throw IllegalArgumentException("Trusted proxy network configuration is invalid")
            }
        }
    }
}
