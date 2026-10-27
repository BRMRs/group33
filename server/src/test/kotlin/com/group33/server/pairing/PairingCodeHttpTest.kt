package com.group33.server.pairing

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.PairingCodeView
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.ApiError
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real Boot filters/JWT decoder/controller/service/transaction. Controls exist only in this test context. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(PairingCodeHttpTest.Controls::class)
internal class PairingCodeHttpTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val encoder: JwtEncoder,
    @Autowired private val clock: TestClock,
    @Autowired private val generator: TestGenerator,
    @Autowired private val store: TestStore,
) {
    private val json = ContractProtocol.json
    private val start = 1_800_000_000_000L

    @BeforeEach fun reset() {
        clock.millis.set(start)
        generator.next.set(0)
        generator.mode = "normal"
        store.backing = InMemoryPairingCodeStore(30)
        store.failReceipt.set(false)
    }

    @Test fun `HTTP01 real signed Elder login issues Shared decodable leading zero code`() {
        val response = issue(login())
        val result = view(response)
        assertEquals("00000001", result.code)
        assertEquals(start + 600_000, result.expiresAtEpochMillis)
        assertNotNull(UUID.fromString(result.id))
        assertTrue(response.getHeader("Cache-Control").orEmpty().contains("no-store"))
    }

    @Test fun `HTTP02 missing expired tampered and wrong issuer tokens cannot issue`() {
        val session = login()
        val parts = session.token.split('.').toMutableList()
        parts[2] = (if (parts[2][0] == 'A') "B" else "A") + parts[2].drop(1)
        listOf(null, "invalid.token.value", parts.joinToString("."),
            signed(session.id, expiry = Instant.now().minusSeconds(3_600)),
            signed(session.id, issuer = "wrong-issuer")).forEach { token ->
            val response = issue(session, token = token)
            error(response, 401, "UNAUTHENTICATED")
            assertNotNull(response.getHeader("WWW-Authenticate"))
        }
        assertEquals(0, generator.next.get())
    }

    @Test fun `HTTP03 Caregiver cannot use an Elder operation or malformed input to bypass role`() {
        val operation = UUID.randomUUID().toString()
        view(issue(login(), operation))
        val caregiver = login(UserRole.CAREGIVER)
        error(issue(caregiver, operation), 403, "ROLE_NOT_ALLOWED")
        error(issue(caregiver, null, body = "{\"elderId\":\"spoof\"}"), 403, "ROLE_NOT_ALLOWED")
        assertEquals(1, generator.next.get())
    }

    @Test fun `HTTP04 historical accepted replay preserves original code and expiry after renewal and expiry`() {
        val elder = login()
        val key = UUID.randomUUID().toString()
        val first = view(issue(elder, key))
        clock.millis.addAndGet(10)
        val replacement = view(issue(elder))
        assertNotEquals(first.id, replacement.id)
        clock.millis.addAndGet(600_001)
        assertEquals(first, view(issue(elder, key)))
        store.backing.transaction { tx ->
            assertEquals(replacement.id, tx.activeCode(elder.id)?.id)
            assertEquals(2, tx.issuanceTimes(elder.id).size)
        }
    }

    @Test fun `HTTP05 same key is isolated across Elders and explicit renew replaces only own code`() {
        val elder = login()
        val other = login()
        val key = UUID.randomUUID().toString()
        val first = view(issue(elder, key))
        val second = view(issue(other, key))
        val renewed = view(issue(elder))
        assertEquals(first, view(issue(elder, key)))
        assertEquals(second, view(issue(other, key)))
        store.backing.transaction { tx ->
            assertEquals(renewed.id, tx.activeCode(elder.id)?.id)
            assertEquals(second.id, tx.activeCode(other.id)?.id)
        }
    }

    @Test fun `HTTP06 missing malformed shortened comma separated and duplicate UUID headers have no effects`() {
        val elder = login()
        listOf(null, "", " ", "not-a-uuid", "1-1-1-1-1", UUID.randomUUID().toString() + ",x")
            .forEach { key -> error(issue(elder, key), 400, "MALFORMED_REQUEST") }
        val key = UUID.randomUUID().toString()
        val duplicate = mvc.perform(post(PATH).header("Authorization", "Bearer ${elder.token}")
            .header(ContractProtocol.IDEMPOTENCY_HEADER, key, key)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
        error(duplicate, 400, "MALFORMED_REQUEST")
        assertEquals(0, generator.next.get())
        assertEquals(view(issue(elder, key.uppercase())), view(issue(elder, key)))
    }

    @Test fun `HTTP07 invalid empty request shapes and caller supplied identity are rejected without quota`() {
        val elder = login()
        listOf("", "null", "[]", "123", "\"{}\"", "{", "{} {}", "{} trailing",
            "{\"elderId\":\"spoof\"}", "{\"installationId\":\"spoof\"}", "{\"extra\":null}")
            .forEach { body -> error(issue(elder, body = body), 400, "MALFORMED_REQUEST") }
        val query = mvc.perform(post(PATH).queryParam("elderId", "spoof")
            .header("Authorization", "Bearer ${elder.token}")
            .header(ContractProtocol.IDEMPOTENCY_HEADER, UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
        error(query, 400, "MALFORMED_REQUEST")
        assertEquals(0, generator.next.get())
        view(issue(elder, body = " { \n } "))
    }

    @Test fun `HTTP08 real rolling limit rounds Retry After up and original rejected key succeeds after wait`() {
        val elder = login()
        val firstKey = UUID.randomUUID().toString()
        view(issue(elder, firstKey))
        clock.millis.addAndGet(1)
        repeat(2) { view(issue(elder)) }
        val limitedKey = UUID.randomUUID().toString()
        val limited = issue(elder, limitedKey)
        error(limited, 429, "RETRY_LATER", retryable = true)
        assertEquals("60", limited.getHeader("Retry-After"))
        view(issue(elder, firstKey))
        assertEquals(3, generator.next.get())
        clock.millis.set(start + 60_000)
        view(issue(elder, limitedKey))
    }

    @Test fun `HTTP09 actual store contention maps to unknown completion and retries original key`() {
        val elder = login()
        val key = UUID.randomUUID().toString()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val holder = executor.submit {
            store.backing.transaction { locked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val busy = issue(elder, key)
            error(busy, 409, "OPERATION_IN_PROGRESS", retryable = true)
            assertEquals("1", busy.getHeader("Retry-After"))
        } finally {
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
        assertEquals(0, generator.next.get())
        val accepted = view(issue(elder, key))
        assertEquals(accepted, view(issue(elder, key)))
    }

    @Test fun `HTTP11 failed receipt write rolls back code quota and operation before same key retry`() {
        val elder = login()
        val key = UUID.randomUUID()
        store.failReceipt.set(true)
        val failed = issue(elder, key.toString())
        error(failed, 503, "SERVICE_UNAVAILABLE", retryable = true)
        assertFalse(failed.contentAsString.contains("PRIVATE"))
        store.backing.transaction { tx ->
            assertNull(tx.activeCode(elder.id))
            assertTrue(tx.issuanceTimes(elder.id).isEmpty())
            assertNull(tx.operation(CodeIssueKey(elder.id, key)))
        }
        val accepted = view(issue(elder, key.toString()))
        assertEquals(accepted, view(issue(elder, key.toString())))
        store.backing.transaction { assertEquals(1, it.issuanceTimes(elder.id).size) }
    }

    @Test fun `HTTP11 invalid generator exception and exhausted collisions are safe transient failures`() {
        val elder = login()
        val key = UUID.randomUUID().toString()
        for (mode in listOf("invalid", "throw")) {
            generator.mode = mode
            error(issue(elder, key), 503, "SERVICE_UNAVAILABLE", retryable = true)
        }
        generator.mode = "normal"
        val existing = view(issue(elder, key))
        generator.mode = existing.code
        val retryKey = UUID.randomUUID().toString()
        error(issue(elder, retryKey), 503, "SERVICE_UNAVAILABLE", retryable = true)
        store.backing.transaction { assertEquals(existing.id, it.activeCode(elder.id)?.id) }
        generator.mode = "normal"
        view(issue(elder, retryKey))
        store.backing.transaction { assertEquals(2, it.issuanceTimes(elder.id).size) }
    }

    @Test fun `HTTP12 signed missing subject mismatched role and unexpected claim types require session recovery`() {
        val elder = login()
        listOf(signed("fictional-missing-user"), signed(elder.id, role = "CAREGIVER"),
            signed(elder.id, type = "unexpected"), signed(elder.id, role = listOf("ELDER")))
            .forEach { token ->
                val response = issue(elder, token = token)
                error(response, 401, "UNAUTHENTICATED")
                assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
                assertFalse(response.contentAsString.contains(elder.id))
            }
        assertEquals(0, generator.next.get())
        view(issue(elder))
    }

    @Test fun `HTTP13 retained different binding maps to conflict without overwriting evidence`() {
        val elder = login()
        val key = UUID.randomUUID()
        val operation = CodeIssueOperation(CodeIssueBinding(elder.installation, elder.id, "different-fingerprint"))
        store.backing.transaction { it.putOperation(CodeIssueKey(elder.id, key), operation) }
        error(issue(elder, key.toString()), 409, "IDEMPOTENCY_KEY_REUSED")
        store.backing.transaction { tx ->
            assertEquals(operation, tx.operation(CodeIssueKey(elder.id, key)))
            assertNull(tx.activeCode(elder.id))
        }
    }

    @Test fun `HTTP shared transaction handles concurrent same intent once through real HTTP filters`() {
        val elder = login()
        val key = UUID.randomUUID().toString()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val calls = (1..6).map { executor.submit<PairingCodeView> {
                check(start.await(5, TimeUnit.SECONDS)); view(issue(elder, key))
            } }
            start.countDown()
            assertEquals(1, calls.map { it.get(10, TimeUnit.SECONDS) }.toSet().size)
            assertEquals(1, generator.next.get())
            store.backing.transaction { assertEquals(1, it.issuanceTimes(elder.id).size) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `HTTP wrong content type and method retain safe structured errors`() {
        val elder = login()
        val wrongType = mvc.perform(post(PATH).header("Authorization", "Bearer ${elder.token}")
            .contentType(MediaType.TEXT_PLAIN).content("{}")).andReturn().response
        error(wrongType, 415, "UNSUPPORTED_MEDIA_TYPE")
        error(mvc.perform(get(PATH).header("Authorization", "Bearer ${elder.token}"))
            .andReturn().response, 405, "METHOD_NOT_ALLOWED")
        assertEquals(0, generator.next.get())
    }

    @Test fun `HTTP malformed retry cannot alter accepted evidence or renew code`() {
        val elder = login()
        val key = UUID.randomUUID().toString()
        val first = view(issue(elder, key))
        error(issue(elder, key, body = "{\"elderId\":\"spoof\"}"), 400, "MALFORMED_REQUEST")
        assertEquals(first, view(issue(elder, key)))
        store.backing.transaction { assertEquals(1, it.issuanceTimes(elder.id).size) }
    }

    private data class Session(val token: String, val id: String, val installation: String)
    private fun login(role: UserRole = UserRole.ELDER): Session {
        val installation = UUID.randomUUID().toString()
        val response = mvc.perform(post("/api/v1/auth/anonymous").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(AnonymousLoginRequest(installation, role, "Fictional"))))
            .andReturn().response
        assertEquals(201, response.status)
        val root = mapper.readTree(response.contentAsString)
        return Session(root["accessToken"].asText(), root["user"]["id"].asText(), installation)
    }

    private fun issue(session: Session, key: String? = UUID.randomUUID().toString(), body: String = "{}",
        token: String? = session.token): MockHttpServletResponse {
        val request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
        if (key != null) request.header(ContractProtocol.IDEMPOTENCY_HEADER, key)
        if (token != null) request.header("Authorization", "Bearer $token")
        return mvc.perform(request).andReturn().response
    }

    private fun view(response: MockHttpServletResponse): PairingCodeView {
        assertEquals(201, response.status)
        return json.decodeFromString<PairingCodeView>(response.contentAsString)
    }

    private fun error(response: MockHttpServletResponse, status: Int, code: String, retryable: Boolean = false) {
        assertEquals(status, response.status)
        val error = json.decodeFromString<ApiError>(response.contentAsString)
        assertEquals(code, error.code)
        assertEquals(retryable, error.retryable)
        assertEquals(error.requestId, response.getHeader("X-Request-ID"))
        assertNotNull(UUID.fromString(error.requestId))
        assertNull(error.reason)
        assertNull(error.fieldErrors)
    }

    private fun signed(subject: String, role: Any = "ELDER", type: Any = "anonymous",
        expiry: Instant = Instant.now().plusSeconds(300), issuer: String = "group33-server"): String =
        encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer(issuer).subject(subject).issuedAt(Instant.now().minusSeconds(7_200))
                .expiresAt(expiry).claim("role", role).claim("type", type).build())).tokenValue

    @TestConfiguration(proxyBeanMethods = false)
    class Controls {
        @Bean @Primary fun testClock() = TestClock()
        @Bean @Primary fun testGenerator() = TestGenerator()
        @Bean @Primary fun testStore() = TestStore()
    }

    class TestClock : Clock() {
        val millis = AtomicLong()
        override fun instant(): Instant = Instant.ofEpochMilli(millis.get())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    class TestGenerator : PairingCodeGenerator {
        val next = AtomicInteger()
        @Volatile var mode = "normal"
        override fun nextCode(): String = when (val current = mode) {
            "normal" -> next.incrementAndGet().toString().padStart(8, '0')
            "invalid" -> "invalid"
            "throw" -> throw IllegalStateException("PRIVATE generator details")
            else -> current
        }
    }

    class TestStore : PairingCodeStore {
        @Volatile var backing = InMemoryPairingCodeStore(30)
        val failReceipt = AtomicBoolean()
        override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = backing.transaction { tx ->
            work(object : PairingCodeTransaction by tx {
                override fun putOperation(key: CodeIssueKey, value: CodeIssueOperation) {
                    tx.putOperation(key, value)
                    if (failReceipt.getAndSet(false)) throw IllegalStateException("PRIVATE receipt details")
                }
            })
        }
    }

    companion object { private const val PATH = "/api/v1/pairing-codes" }
}
