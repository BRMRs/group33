package com.group33.server.pairing

import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(PairingLifecycleHttpTest.Controls::class)
internal class PairingLifecycleHttpTest(
    @Autowired mvc: MockMvc,
    @Autowired private val store: ControlledStore,
    @Autowired private val clock: TestClock,
    @Autowired private val generator: Generator,
    @Autowired private val encoder: JwtEncoder,
) {
    private val http = PairingHttpFixture(mvc)
    private val path = PairingHttpFixture.REQUESTS
    @BeforeEach fun reset() { store.backing = InMemoryPairingCodeStore(20); store.failBefore.set(false); store.failAfter.set(false); clock.time.set(1_800_000_000_000); generator.counter.set(0) }
    private fun key() = UUID.randomUUID().toString()
    private fun elder() = http.login(UserRole.ELDER)
    private fun caregiver() = http.login(UserRole.CAREGIVER)

    @Test fun `real JWT flow consumes leading zero code and accepts then revokes without replay resurrection`() {
        val e = elder(); val c = caregiver(); val code = http.issue(e); assertEquals("00000001", code.code)
        val requestKey = key(); val p = http.request(http.submit(c, code.code, requestKey), 201)
        assertEquals(PairingRequestStatus.PENDING, p.status)
        val op = key(); val r = http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op))
        assertEquals(e.id, r.elderId); assertEquals(c.id, r.caregiverId)
        assertEquals(r.id, http.request(http.call(c, "GET", "$path/${p.id}", null)).relationshipId)
        val revoked = http.relationship(http.call(c, "POST", "/api/v1/care-relationships/${r.id}/revoke"))
        assertEquals(CareRelationshipStatus.REVOKED, revoked.status)
        assertEquals(r, http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op)))
        assertFalse(StoredCareRelationshipAccess(store).isActive(c.id, e.id))
        assertEquals(p, http.request(http.submit(c, code.code, requestKey), 201))
    }
    @Test fun `missing invalid expired tampered wrong issuer and unresolved signed identities cannot mutate`() {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c)
        fun signed(id: String = e.id, role: Any = "ELDER", issuer: String = "group33-server", expires: Instant = Instant.now().plusSeconds(300)) =
            encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                .subject(id).issuer(issuer).issuedAt(Instant.now().minusSeconds(7200)).expiresAt(expires)
                .claim("type", "anonymous").claim("role", role).build())).tokenValue
        val tampered = e.token.split('.').toMutableList().also { it[2] = (if (it[2][0] == 'A') "B" else "A") + it[2].drop(1) }.joinToString(".")
        for (token in listOf(null, "invalid", tampered, signed(expires = Instant.now().minusSeconds(3600)),
            signed(issuer = "wrong"), signed(id = "unknown"), signed(role = "CAREGIVER"), signed(role = listOf("ELDER")))) {
            val response = http.call(e, "POST", "$path/${p.id}/accept", token = token)
            http.failure(response, 401, "UNAUTHENTICATED"); assertNotNull(response.getHeader("WWW-Authenticate"))
        }
        assertEquals(PairingRequestStatus.PENDING, http.request(http.call(e, "GET", "$path/${p.id}", null)).status)
    }
    @Test fun `caregiver cannot consent and unrelated caller gets same generic error as absent request`() {
        val e = elder(); val c = caregiver(); val outsider = elder(); val p = http.pending(e, c)
        http.failure(http.call(c, "POST", "$path/${p.id}/accept"), 403, "ROLE_NOT_ALLOWED")
        http.failure(http.call(c, "POST", "$path/${p.id}/reject"), 403, "ROLE_NOT_ALLOWED")
        for (id in listOf(p.id, "missing")) {
            http.failure(http.call(outsider, "GET", "$path/$id", null), 404, "RESOURCE_NOT_ACCESSIBLE")
            http.failure(http.call(outsider, "POST", "$path/$id/accept"), 404, "RESOURCE_NOT_ACCESSIBLE")
            http.failure(http.call(outsider, "DELETE", "$path/$id", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        }
        http.failure(http.call(e, "POST", path, "{\"code\":\"00000001\"}"), 403, "ROLE_NOT_ALLOWED")
    }
    @Test fun `strict code decoding rejects numeric null duplicate unknown trailing and caller identity fields`() {
        val e = elder(); val c = caregiver(); val code = http.issue(e)
        for (body in listOf("{\"code\":12345678}", "{\"code\":null}", "{}", "[]", "null", "{", "{} {}",
            "{\"code\":\"00000001\",\"code\":\"00000001\"}", "{\"code\":\"00000001\",\"actorId\":\"spoof\"}")) {
            http.failure(http.call(c, "POST", path, body), 400, "MALFORMED_REQUEST")
        }
        http.request(http.submit(c, code.code), 201)
    }
    @Test fun `missing malformed duplicate and abbreviated operation keys are rejected before mutation`() {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c)
        for (op in listOf(null, "", "1-1-1-1-1", "invalid", key() + ",x"))
            http.failure(http.call(e, "POST", "$path/${p.id}/accept", key = op), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "POST", "$path/${p.id}/accept", duplicateKey = true), 400, "MALFORMED_REQUEST")
        val op = key(); val first = http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op.uppercase()))
        assertEquals(first, http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op)))
    }
    @Test fun `decision empty body and query restrictions cannot be bypassed on accepted replay`() {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c); val op = key()
        val first = http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op))
        for (body in listOf(null, "", "null", "[]", "{\"elderId\":\"spoof\"}", "{} {}"))
            http.failure(http.call(e, "POST", "$path/${p.id}/accept", body, op), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "POST", "$path/${p.id}/accept?elderId=spoof", key = op), 400, "MALFORMED_REQUEST")
        http.failure(http.call(c, "GET", "$path/${p.id}?status=PENDING", null), 400, "MALFORMED_REQUEST")
        assertEquals(first, http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op)))
    }
    @ParameterizedTest @ValueSource(booleans = [true, false])
    fun `either participant cancellation returns empty 204 and successful replay remains 204`(byElder: Boolean) {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c); val actor = if (byElder) e else c; val op = key()
        repeat(2) {
            val response = http.call(actor, "DELETE", "$path/${p.id}", null, op)
            assertEquals(204, response.status); assertEquals("", response.contentAsString); http.noStore(response)
        }
        val closed = http.request(http.call(e, "GET", "$path/${p.id}", null))
        assertEquals(if (byElder) PairingClosedReason.ELDER_CANCELLED else PairingClosedReason.CAREGIVER_CANCELLED, closed.closedReason)
        http.failure(http.call(e, "POST", "$path/${p.id}/accept"), 409, "PAIRING_REQUEST_CLOSED")
        http.failure(http.call(actor, "DELETE", "$path/${p.id}", null), 409, "PAIRING_REQUEST_CLOSED")
    }
    @Test fun `reject returns terminal Shared view and no relation`() {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c); val op = key()
        val closed = http.request(http.call(e, "POST", "$path/${p.id}/reject", key = op))
        assertEquals(PairingClosedReason.ELDER_REJECTED, closed.closedReason)
        assertEquals(closed, http.request(http.call(e, "POST", "$path/${p.id}/reject", key = op)))
        http.failure(http.call(e, "POST", "$path/${p.id}/accept"), 409, "PAIRING_REQUEST_CLOSED")
        assertFalse(StoredCareRelationshipAccess(store).isActive(c.id, e.id))
    }
    @ParameterizedTest @ValueSource(longs = [-1, 0, 1])
    fun `HTTP consent respects exclusive deadline`(offset: Long) {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c); clock.time.set(p.expiresAtEpochMillis + offset)
        val response = http.call(e, "POST", "$path/${p.id}/accept")
        if (offset < 0) http.relationship(response)
        else { http.failure(response, 409, "PAIRING_REQUEST_CLOSED"); assertEquals(PairingClosedReason.CODE_EXPIRED, http.request(http.call(e, "GET", "$path/${p.id}", null)).closedReason) }
    }
    @Test fun `second caregiver conflict can retry same operation only after revocation`() {
        val e = elder(); val c = caregiver(); val c2 = caregiver(); val p = http.pending(e, c)
        val r = http.relationship(http.call(e, "POST", "$path/${p.id}/accept"))
        val p2 = http.pending(e, c2); val op = key()
        http.failure(http.call(e, "POST", "$path/${p2.id}/accept", key = op), 409, "ELDER_ALREADY_PAIRED")
        http.failure(http.call(c2, "POST", "/api/v1/care-relationships/${r.id}/revoke"), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.relationship(http.call(c, "POST", "/api/v1/care-relationships/${r.id}/revoke"))
        http.relationship(http.call(e, "POST", "$path/${p2.id}/accept", key = op))
        assertTrue(StoredCareRelationshipAccess(store).isActive(c2.id, e.id)); assertFalse(StoredCareRelationshipAccess(store).isActive(c.id, e.id))
    }
    @Test fun `same key changed request code or target conflicts without altering accepted evidence`() {
        val e = elder(); val c = caregiver(); val op = key(); val code = http.issue(e); val p = http.request(http.submit(c, code.code, op), 201)
        http.failure(http.submit(c, "99999999", op), 409, "IDEMPOTENCY_KEY_REUSED")
        val acceptOp = key(); http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = acceptOp))
        val p2 = http.pending(e, c)
        http.failure(http.call(e, "POST", "$path/${p2.id}/accept", key = acceptOp), 409, "IDEMPOTENCY_KEY_REUSED")
    }
    @Test fun `installation limit retry header and same rejected key recovery use server clock`() {
        val c = caregiver()
        repeat(5) { http.failure(http.submit(c, "99999999"), 422, "PAIRING_CODE_UNAVAILABLE") }
        val op = key(); val limited = http.submit(c, "99999999", op)
        http.failure(limited, 429, "RETRY_LATER", true); assertEquals("60", limited.getHeader("Retry-After"))
        clock.time.addAndGet(60_000); http.failure(http.submit(c, "99999999", op), 422, "PAIRING_CODE_UNAVAILABLE")
    }
    @Test fun `forged forwarded headers do not bypass twenty attempts per socket peer`() {
        repeat(20) { i -> http.failure(http.call(caregiver(), "POST", path, "{\"code\":\"99999999\"}", forwarded = "198.51.100.$i"), 422, "PAIRING_CODE_UNAVAILABLE") }
        val c = caregiver(); val limited = http.call(c, "POST", path, "{\"code\":\"99999999\"}", forwarded = "203.0.113.9")
        http.failure(limited, 429, "RETRY_LATER", true); assertEquals("60", limited.getHeader("Retry-After"))
        http.failure(http.call(c, "POST", path, "{\"code\":\"99999999\"}", remote = "192.0.2.2"), 422, "PAIRING_CODE_UNAVAILABLE")
    }
    @Test fun `IPv6 spellings share one peer bucket and hostname peer fails without DNS lookup`() {
        repeat(20) { i -> http.failure(http.call(caregiver(), "POST", path, "{\"code\":\"99999999\"}", remote = if (i % 2 == 0) "::1" else "0:0:0:0:0:0:0:1"), 422, "PAIRING_CODE_UNAVAILABLE") }
        http.failure(http.call(caregiver(), "POST", path, "{\"code\":\"99999999\"}", remote = "::1"), 429, "RETRY_LATER", true)
        http.failure(http.call(caregiver(), "POST", path, "{\"code\":\"99999999\"}", remote = "example.invalid"), 503, "SERVICE_UNAVAILABLE", true)
    }
    @Test fun `service failure is safe 503 and precommit failure rolls back while lost response replays committed result`() {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c); val op = key()
        store.failBefore.set(true)
        val failed = http.call(e, "POST", "$path/${p.id}/accept", key = op)
        http.failure(failed, 503, "SERVICE_UNAVAILABLE", true); assertFalse(failed.contentAsString.contains("PRIVATE"))
        assertEquals(PairingRequestStatus.PENDING, http.request(http.call(e, "GET", "$path/${p.id}", null)).status)
        store.failAfter.set(true)
        http.failure(http.call(e, "POST", "$path/${p.id}/accept", key = op), 503, "SERVICE_UNAVAILABLE", true)
        val accepted = http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op))
        assertEquals(accepted.id, http.request(http.call(e, "GET", "$path/${p.id}", null)).relationshipId)
    }
    @Test fun `contention maps to retryable 409 then exact intent succeeds`() {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c); val op = key()
        val pool = Executors.newSingleThreadExecutor(); val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val held = pool.submit { store.backing.transaction { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val response = http.call(e, "POST", "$path/${p.id}/accept", key = op)
            http.failure(response, 409, "OPERATION_IN_PROGRESS", true); assertEquals("1", response.getHeader("Retry-After"))
        } finally { release.countDown(); held.get(5, TimeUnit.SECONDS); pool.shutdownNow() }
        http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op))
    }
    @Test fun `concurrent HTTP accept retries create only one accepted relationship`() {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c); val op = key()
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        try {
            val results = (1..6).map { pool.submit<CareRelationshipView> { start.await(); http.relationship(http.call(e, "POST", "$path/${p.id}/accept", key = op)) } }
            start.countDown(); assertEquals(1, results.map { it.get(10, TimeUnit.SECONDS).id }.distinct().size)
        } finally { start.countDown(); pool.shutdownNow() }
    }
    @Test fun `wrong media method and DELETE payload do not execute commands`() {
        val e = elder(); val c = caregiver(); val p = http.pending(e, c)
        http.failure(http.call(e, "POST", "$path/${p.id}/accept", contentType = "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE")
        http.failure(http.call(e, "GET", "$path/${p.id}/accept", null), 405, "METHOD_NOT_ALLOWED")
        http.failure(http.call(c, "DELETE", "$path/${p.id}", "{\"actorId\":\"spoof\"}"), 400, "MALFORMED_REQUEST")
        http.failure(http.call(c, "DELETE", "$path/${p.id}", contentType = "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE")
        assertEquals(PairingRequestStatus.PENDING, http.request(http.call(e, "GET", "$path/${p.id}", null)).status)
    }

    @TestConfiguration(proxyBeanMethods = false) class Controls {
        @Bean @Primary fun lifecycleStore() = ControlledStore()
        @Bean @Primary fun lifecycleClock() = TestClock()
        @Bean @Primary fun lifecycleGenerator() = Generator()
    }
    class TestClock : Clock() {
        val time = AtomicLong(1_800_000_000_000)
        override fun instant(): Instant = Instant.ofEpochMilli(time.get())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }
    class Generator : PairingCodeGenerator { val counter = AtomicInteger(); override fun nextCode() = counter.incrementAndGet().toString().padStart(8, '0') }
    class ControlledStore : PairingDecisionStore {
        var backing = InMemoryPairingCodeStore(20)
        val failBefore = AtomicBoolean(); val failAfter = AtomicBoolean()
        override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = decisionTransaction { work(it) }
        override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T = decisionTransaction { work(it) }
        override fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T {
            val result = backing.decisionTransaction { tx ->
                val value = work(tx)
                if (failBefore.getAndSet(false)) error("PRIVATE injected rollback")
                value
            }
            if (failAfter.getAndSet(false)) error("PRIVATE injected lost response")
            return result
        }
    }
}
