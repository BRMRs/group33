package com.group33.server.capability

import com.group33.server.pairing.PairingHttpFixture
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["app.capability.enabled=true"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(CapabilityHttpTest.Controls::class)
internal class CapabilityHttpTest(@Autowired mvc: MockMvc, @Autowired val store: ControlledStore,
    @Autowired val encoder: JwtEncoder) {
    private val http = PairingHttpFixture(mvc)
    @BeforeEach fun reset() { store.backing = InMemoryCapabilityStore(); store.failure = null }
    private fun key() = UUID.randomUUID().toString()
    private fun elder() = http.login(UserRole.ELDER)
    private fun path(id: String) = "/api/v1/elders/$id/reminder-capability"
    private fun body(op: String, seq: Long = 1, at: Long = 100) =
        """{"reportId":"$op","observationSequence":$seq,"observedAtEpochMillis":$at,"deliveryCondition":"AVAILABLE","notificationPermissionGranted":true,"channelEnabled":true,"exactAlarmAccess":"GRANTED","reasons":["虚构测试原因"]}"""
    private fun put(e: PairingHttpFixture.Session, op: String, json: String = body(op)) = http.call(e, "PUT", path(e.id), json, op)
    private fun receipt(response: org.springframework.mock.web.MockHttpServletResponse): ReminderCapabilityReceipt {
        assertEquals(200, response.status); http.noStore(response)
        return http.json.decodeFromString<ReminderCapabilityReceipt>(response.contentAsString)
    }
    @Test fun `unpaired owner reports and replays original historical current after newer and delayed reports`() {
        val e = elder(); val a = key(); val first = receipt(put(e, a))
        val b = key(); val newer = receipt(put(e, b, body(b, 8, 1)))
        val c = key(); val delayed = receipt(put(e, c, body(c, 4, 999)))
        assertTrue(first.applied); assertTrue(newer.applied); assertFalse(delayed.applied)
        assertEquals(newer.current, delayed.current); assertEquals(first, receipt(put(e, a)))
        assertEquals(delayed, receipt(put(e, c, body(c, 4, 999))))
    }
    @Test fun `all bound payload changes conflict and same sequence different identity conflicts`() {
        val e = elder(); val op = key(); val original = receipt(put(e, op))
        val changed = listOf(body(op, 2), body(op, at = 101), body(op).replace("AVAILABLE", "DEGRADED"),
            body(op).replace("GRANTED", "DENIED"), body(op).replace("true", "false"),
            body(op).replace("虚构测试原因", "changed"), body(key()))
        changed.forEach { http.failure(put(e, op, it), 409, "IDEMPOTENCY_KEY_REUSED") }
        http.failure(put(e, key()), 409, "CAPABILITY_SEQUENCE_CONFLICT")
        assertEquals(original, receipt(put(e, op)))
    }
    @ParameterizedTest @ValueSource(strings = ["sequence", "time", "identity"])
    fun `well formed invalid reports bind rejected intent without partial current`(kind: String) {
        val e = elder(); val op = key()
        val invalid = when(kind) { "sequence" -> body(op, 0); "time" -> body(op, at = -1); else -> body(key()) }
        repeat(2) { http.failure(put(e, op, invalid), 422, "VALIDATION_FAILED") }
        http.failure(put(e, op), 409, "IDEMPOTENCY_KEY_REUSED")
        assertTrue(receipt(put(e, key())).applied)
    }
    @ParameterizedTest @ValueSource(strings = ["numeric-id", "string-sequence", "float-sequence", "overflow", "string-time",
        "null-boolean", "missing-boolean", "string-boolean", "numeric-boolean", "numeric-enum", "padded-enum",
        "unknown-enum", "null-reason", "numeric-reason", "boolean-reason", "object-reasons", "unknown", "duplicate", "trailing", "array"])
    fun `strict route decoding rejects coercions and ambiguous bodies without consuming operation`(kind: String) {
        val e = elder(); val op = key(); val valid = body(op)
        val invalid = when(kind) {
            "numeric-id" -> valid.replace("\"$op\"", "12")
            "string-sequence" -> valid.replace("\"observationSequence\":1", "\"observationSequence\":\"1\"")
            "float-sequence" -> valid.replace("\"observationSequence\":1", "\"observationSequence\":1.0")
            "overflow" -> valid.replace("\"observationSequence\":1", "\"observationSequence\":9223372036854775808")
            "string-time" -> valid.replace("\"observedAtEpochMillis\":100", "\"observedAtEpochMillis\":\"100\"")
            "null-boolean" -> valid.replace("\"channelEnabled\":true", "\"channelEnabled\":null")
            "missing-boolean" -> valid.replace("\"channelEnabled\":true,", "")
            "string-boolean" -> valid.replace("\"channelEnabled\":true", "\"channelEnabled\":\"true\"")
            "numeric-boolean" -> valid.replace("\"channelEnabled\":true", "\"channelEnabled\":1")
            "numeric-enum" -> valid.replace("\"AVAILABLE\"", "0")
            "padded-enum" -> valid.replace("AVAILABLE", " AVAILABLE ")
            "unknown-enum" -> valid.replace("GRANTED", "OTHER")
            "null-reason" -> valid.replace("\"虚构测试原因\"", "null")
            "numeric-reason" -> valid.replace("\"虚构测试原因\"", "42")
            "boolean-reason" -> valid.replace("\"虚构测试原因\"", "true")
            "object-reasons" -> valid.replace("[\"虚构测试原因\"]", "{}")
            "unknown" -> valid.dropLast(1) + ",\"installationId\":\"spoof\"}"
            "duplicate" -> valid.dropLast(1) + ",\"channelEnabled\":false}"
            "trailing" -> "$valid {}"
            else -> "[]"
        }
        http.failure(put(e, op, invalid), 400, "MALFORMED_REQUEST")
        receipt(put(e, op))
    }
    @Test fun `current trusted identity authorizes before payload details and pairing never grants write`() {
        val e = elder(); val c = http.login(UserRole.CAREGIVER); val outsider = elder()
        http.failure(http.call(outsider, "PUT", path(e.id), "{", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(c, "PUT", path(e.id), "{", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(c, "PUT", path(c.id), "{", null), 403, "ROLE_NOT_ALLOWED")
        val pending = http.pending(e, c)
        val relationship = http.relationship(http.call(e, "POST", "${PairingHttpFixture.REQUESTS}/${pending.id}/accept"))
        http.failure(http.call(c, "PUT", path(e.id), "{", null), 403, "ROLE_NOT_ALLOWED")
        val op = key(); val original = receipt(put(e, op))
        http.relationship(http.call(c, "POST", "/api/v1/care-relationships/${relationship.id}/revoke"))
        http.failure(http.call(c, "PUT", path(e.id), "{", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        assertEquals(original, receipt(put(e, op)))
    }
    @Test fun `signed stale missing expired wrong role and unknown identities cannot replay receipt`() {
        val e = elder(); val op = key(); receipt(put(e, op))
        fun signed(id: String = e.id, role: String = "ELDER", expires: Instant = Instant.now().plusSeconds(300)) =
            encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                .subject(id).issuer("group33-server").issuedAt(Instant.now().minusSeconds(7200)).expiresAt(expires)
                .claim("type", "anonymous").claim("role", role).build())).tokenValue
        for (token in listOf(null, "invalid", signed(id = "gone"), signed(role = "CAREGIVER"), signed(expires = Instant.now().minusSeconds(3600)))) {
            val response = http.call(e, "PUT", path(e.id), body(op), op, token)
            http.failure(response, 401, "UNAUTHENTICATED"); assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
        }
    }
    @Test fun `malformed operation headers and query cannot bypass replay validation`() {
        val e = elder(); val op = key(); val original = receipt(put(e, op))
        for (value in listOf(null, "", "1-1-1-1-1", "$op,x"))
            http.failure(http.call(e, "PUT", path(e.id), body(op), value), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "PUT", path(e.id), body(op), op, duplicateKey = true), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "PUT", path(e.id)+"?installationId=spoof", body(op), op), 400, "MALFORMED_REQUEST")
        assertEquals(original, receipt(http.call(e, "PUT", path(e.id), body(op), op.uppercase())))
    }
    @Test fun `bounded contention and database exceptions have safe retryable error envelopes`() {
        val e = elder(); val op = key()
        store.failure = CapabilityStoreBusy()
        val busy = put(e, op); http.failure(busy, 409, "OPERATION_IN_PROGRESS", true); assertEquals("1", busy.getHeader("Retry-After"))
        store.failure = java.sql.SQLException("PRIVATE reasons token password")
        val failed = put(e, op); http.failure(failed, 503, "SERVICE_UNAVAILABLE", true); assertFalse(failed.contentAsString.contains("PRIVATE"))
        store.failure = null; receipt(put(e, op))
    }
    @Test fun `parallel HTTP retries return one original receipt`() {
        val e = elder(); val op = key(); val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { pool.submit<ReminderCapabilityReceipt> { receipt(put(e, op)) } }
            assertEquals(1, results.map { it.get(10, TimeUnit.SECONDS) }.distinct().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun `existing DTO response and unrelated decoder remain unchanged`() {
        val e = elder(); val op = key(); receipt(put(e, op))
        http.failure(http.call(e, "GET", path(e.id), null), 405, "METHOD_NOT_ALLOWED")
        http.failure(http.call(e, "PUT", path(e.id), body(op), op, contentType = "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE")
        http.failure(http.call(http.login(UserRole.CAREGIVER), "POST", PairingHttpFixture.REQUESTS, "{\"code\":12345678}"), 400, "MALFORMED_REQUEST")
    }
    @TestConfiguration(proxyBeanMethods = false) class Controls { @Bean @Primary fun controlledCapabilityStore() = ControlledStore() }
    class ControlledStore : CapabilityStore {
        var backing = InMemoryCapabilityStore()
        @Volatile var failure: Exception? = null
        override fun <T> transaction(work: (CapabilityTransaction) -> T): T { failure?.let { throw it }; return backing.transaction(work) }
    }
}
