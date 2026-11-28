package com.group33.server.auth

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

internal class BoundPhoneProxyRateLimitTest {
    private val now = AtomicLong()
    private fun limiter(quota: Int = 1, clients: Int = 10) = TrustedProxyPhoneRateLimit(
        listOf("192.0.2.0/24", "2001:db8:1::/48"), quota, clients, now::get)

    private fun request(chain: String = "203.0.113.7", peer: String = "192.0.2.1") =
        MockHttpServletRequest().apply { remoteAddr = peer; addHeader("X-Forwarded-For", chain) }

    private fun rejected(work: () -> Unit) {
        val failure = assertThrows(PhoneAuthFailure::class.java, work)
        assertEquals(HttpStatus.BAD_REQUEST, failure.status)
        assertEquals("MALFORMED_REQUEST", failure.code)
        assertNull(failure.cause)
    }

    @Test fun `caller controlled leftmost prefixes cannot reset the actual client quota`() {
        val limit = limiter()
        assertNull(limit.retryAfterSeconds(request("198.51.100.1, 203.0.113.7")))
        assertEquals(60L, limit.retryAfterSeconds(request("198.51.100.2, 203.0.113.7")))
        assertNull(limit.retryAfterSeconds(request("198.51.100.2, 203.0.113.8")))
    }

    @Test fun `only verified peer and intermediate proxy networks are discarded`() {
        val limit = limiter()
        assertNull(limit.retryAfterSeconds(request("198.51.100.9, 203.0.113.7, 2001:db8:1::2")))
        assertEquals(60L, limit.retryAfterSeconds(request("203.0.113.7")))
        rejected { limit.retryAfterSeconds(request("203.0.113.99", "198.51.100.9")) }
        rejected { limit.retryAfterSeconds(request("192.0.2.2")) }
    }

    @Test fun `equivalent IPv6 and mapped IPv4 spellings share a quota`() {
        val limit = limiter()
        assertNull(limit.retryAfterSeconds(request("2001:db8:abcd::1")))
        assertEquals(60L, limit.retryAfterSeconds(request("2001:0db8:abcd:0000:0000:0000:0000:0001")))
        assertNull(limit.retryAfterSeconds(request("203.0.113.7")))
        assertEquals(60L, limit.retryAfterSeconds(request("::ffff:203.0.113.7", "::ffff:192.0.2.1")))
    }

    @Test fun `missing duplicate oversized or nonliteral forwarding metadata fails closed`() {
        val limit = limiter()
        rejected { limit.retryAfterSeconds(MockHttpServletRequest().apply { remoteAddr = "192.0.2.1" }) }
        rejected { limit.retryAfterSeconds(request().apply { addHeader("X-Forwarded-For", "203.0.113.8") }) }
        for (invalid in listOf("", "203.0.113.7,", "203.0.113.7:80", "[2001:db8::1]",
            "2001:db8::1%eth0", "caller.invalid", "203.000.113.7", "203.0.113.256",
            (1..17).joinToString(",") { "203.0.113.7" }, "x".repeat(2049))) {
            rejected { limit.retryAfterSeconds(request(invalid)) }
        }
        // Malformed requests did not consume or create quota entries.
        assertNull(limit.retryAfterSeconds(request()))
    }

    @Test fun `exact minute boundary releases the quota and Retry After rounds up`() {
        val limit = limiter()
        assertNull(limit.retryAfterSeconds(request()))
        now.set(59_000_000_001)
        assertEquals(1L, limit.retryAfterSeconds(request()))
        now.set(60_000_000_000)
        assertNull(limit.retryAfterSeconds(request()))
        assertEquals(60L, limit.retryAfterSeconds(request()))
    }

    @Test fun `capacity exhaustion does not evict an active clients quota`() {
        val limit = limiter(clients = 1)
        assertNull(limit.retryAfterSeconds(request()))
        assertEquals(60L, limit.retryAfterSeconds(request("203.0.113.8")))
        assertEquals(60L, limit.retryAfterSeconds(request()))
        now.set(60_000_000_000)
        assertNull(limit.retryAfterSeconds(request("203.0.113.8")))
        assertEquals(60L, limit.retryAfterSeconds(request()))
    }

    @Test fun `monotonic wrap and a backwards test clock never reset an active window`() {
        now.set(Long.MAX_VALUE - 10)
        val limit = limiter()
        assertNull(limit.retryAfterSeconds(request()))
        now.set(Long.MIN_VALUE + 10)
        assertEquals(60L, limit.retryAfterSeconds(request()))
        now.set(Long.MIN_VALUE + 60_000_000_000)
        assertNull(limit.retryAfterSeconds(request()))
        now.addAndGet(-100)
        assertEquals(60L, limit.retryAfterSeconds(request()))
    }

    @Test fun `concurrent requests cannot exceed one atomic per client allowance`() {
        val limit = limiter(quota = 30)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..1000).map { Callable { limit.retryAfterSeconds(request()) } })
                .map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(30, results.count { it == null })
            assertEquals(970, results.count { it == 60L })
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `proxy configuration requires bounded canonical networks without a wildcard`() {
        for (cidr in listOf("0.0.0.0/0", "::/0", "proxy.internal/24", "192.0.2.1/24",
            "192.0.2.0/024", "192.0.2.0/33", "::ffff:192.0.2.0/24", "2001:db8::1/48")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { TrustedProxyPhoneRateLimit(listOf(cidr)) }
            assertFalse(failure.message.orEmpty().contains(cidr))
        }
        assertThrows(IllegalArgumentException::class.java) { TrustedProxyPhoneRateLimit(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TrustedProxyPhoneRateLimit(List(17) { "192.0.2.0/24" }) }
        assertThrows(IllegalArgumentException::class.java) { limiter(quota = 121) }
        assertThrows(IllegalArgumentException::class.java) { limiter(clients = 10_001) }
    }
}
