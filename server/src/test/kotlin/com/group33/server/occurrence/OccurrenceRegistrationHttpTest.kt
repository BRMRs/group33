package com.group33.server.occurrence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.group33.server.config.PersistenceRuntime
import com.group33.server.pairing.*
import com.group33.server.plan.PlanSelfOverlapValidator
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/** Real filters/login/consent/photo/plan preparation; the existing transactional service receives HTTP inputs. */
@SpringBootTest(properties = ["app.occurrence.enabled=true", "app.plan.enabled=true", "app.photo.enabled=true", "app.persistence.mode=memory"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(OccurrenceRegistrationHttpTest.Controls::class)
internal class OccurrenceRegistrationHttpTest(@Autowired private val mvc: MockMvc,
    @Autowired private val store: ControlledStore, @Autowired private val encoder: JwtEncoder) {
    private val http = PairingHttpFixture(mvc)
    private val mapper = ObjectMapper()
    @BeforeEach fun resetFailure() { store.failure = null }
    private fun key() = UUID.randomUUID().toString()
    private fun path(id: String) = "/api/v1/elders/$id/occurrence-registrations"
    private data class Fixture(val elder: PairingHttpFixture.Session, val caregiver: PairingHttpFixture.Session,
        val relationship: String, val confirmation: ConfirmReminderPlanResponse, val evidence: OccurrenceGenerationEvidence)
    private fun fixture(): Fixture {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER)
        val pending = http.request(http.call(c, "POST", PairingHttpFixture.REQUESTS,
            http.json.encodeToString(RequestPairingRequest.serializer(), RequestPairingRequest(http.issue(e).code)),
            remote = "192.0.2.${peers.incrementAndGet()}"), 201)
        val relation = http.relationship(http.call(e, "POST", "${PairingHttpFixture.REQUESTS}/${pending.id}/accept"))
        val bytes = ByteArrayOutputStream().use { out ->
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
            try { check(ImageIO.write(image, "png", out)); out.toByteArray() } finally { image.flush() }
        }
        val photoResponse = mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/elders/${e.id}/medication-photos")
            .file(MockMultipartFile("file", "fictional.png", "image/png", bytes))
            .header("Authorization", "Bearer ${e.token}").header(ContractProtocol.IDEMPOTENCY_HEADER, key())).andReturn().response
        assertEquals(201, photoResponse.status)
        val photo = http.json.decodeFromString<MedicationPhotoView>(photoResponse.contentAsString)
        val request = ConfirmReminderPlanRequest(photo.id, MedicationText(" 虚构药品 ", "虚构用量", " 原始说明 "),
            ReminderPlanDraft(listOf("08:00", "20:00"), "2026-01-01", "Asia/Shanghai"), TimeZoneAcknowledgement("Asia/Shanghai", true))
        val saved = http.call(c, "POST", "/api/v1/elders/${e.id}/reminder-plan-confirmations",
            http.json.encodeToString(ConfirmReminderPlanRequest.serializer(), request))
        assertEquals(201, saved.status)
        val confirmation = http.json.decodeFromString<ConfirmReminderPlanResponse>(saved.contentAsString)
        val zone = ZoneId.of("Asia/Shanghai")
        val instant = PlanSelfOverlapValidator.resolve(LocalDateTime.of(2026, 10, 29, 8, 0), zone)
        val evidence = OccurrenceGenerationEvidence(key(), OccurrenceKey(confirmation.plan.id, "2026-10-29", "08:00"), 1,
            instant.minusSeconds(3600).toEpochMilli(), zone.id, instant.toEpochMilli(), instant.atZone(zone).offset.totalSeconds,
            TimeResolutionPolicy.P0_TIME_V1, " fictional retained context ", instant.minusSeconds(7200).toEpochMilli())
        return Fixture(e, c, relation.id, confirmation, evidence)
    }
    private fun encode(vararg items: OccurrenceGenerationEvidence) = http.json.encodeToString(
        OccurrenceRegistrationRequest.serializer(), OccurrenceRegistrationRequest(items.toList()))
    private fun send(f: Fixture, op: String? = key(), body: String = encode(f.evidence)) =
        http.call(f.elder, "POST", path(f.elder.id), body, op)
    private fun accepted(response: MockHttpServletResponse): OccurrenceRegistrationResponse {
        assertEquals(200, response.status); http.noStore(response)
        assertEquals(setOf("items"), mapper.readTree(response.contentAsString).fieldNames().asSequence().toSet())
        assertFalse(response.contentAsString.contains(root.toString()))
        return http.json.decodeFromString<OccurrenceRegistrationResponse>(response.contentAsString)
    }
    private fun stored(evidence: OccurrenceGenerationEvidence) = store.registrationTransaction { it.occurrence(evidence.occurrence) }

    @Test fun `owner registers a real plan with frozen text and exact replay preserves original receipt`() {
        val f = fixture(); val op = key(); val original = accepted(send(f, op.uppercase()))
        val event = original.items.single().event
        assertEquals(f.evidence, event.generationEvidence)
        assertEquals(f.confirmation.plan.id, event.occurrence.planId)
        assertEquals(" 虚构药品 ", event.medicationSnapshot.displayName)
        assertEquals(f.evidence.plannedAtEpochMillis + 7_200_000, event.responseDeadlineEpochMillis)
        assertEquals(original, accepted(send(f, op))); assertEquals(original, accepted(send(f)))
        http.failure(send(f, op, encode(f.evidence.copy(sourceSnapshotVersion = "changed"))), 409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(original.items.single(), stored(f.evidence)!!.receipt)
    }
    @Test fun `invalid last item rejects whole batch and retains rejected payload binding`() {
        val f = fixture(); val op = key()
        val bad = f.evidence.copy(generationId = key(), occurrence = f.evidence.occurrence.copy(localTime = "25:00"))
        val body = encode(f.evidence, bad)
        repeat(2) {
            val response = send(f, op, body); assertEquals(422, response.status); http.noStore(response)
            val error = http.json.decodeFromString<ApiError>(response.contentAsString)
            assertEquals("VALIDATION_FAILED", error.code); assertFalse(error.retryable)
            assertTrue(error.fieldErrors!!.all { it.field.startsWith("items[1].") })
            assertEquals(error.requestId, response.getHeader("X-Request-ID"))
        }
        assertNull(stored(f.evidence)); assertNull(stored(bad))
        http.failure(send(f, op), 409, "IDEMPOTENCY_KEY_REUSED")
        accepted(send(f))
    }
    @Test fun `empty registration is validation failure and does not consume a later different operation`() {
        val f = fixture(); val response = send(f, body = "{\"items\":[]}")
        assertEquals(422, response.status)
        assertEquals("items", http.json.decodeFromString<ApiError>(response.contentAsString).fieldErrors!!.single().field)
        assertNull(stored(f.evidence)); accepted(send(f))
    }
    @Test fun `foreign and missing plans disclose no validation details and cannot bind an operation`() {
        val f = fixture(); val foreign = fixture(); val op = key()
        for (id in listOf(foreign.confirmation.plan.id, key()))
            http.failure(send(f, op, encode(f.evidence.copy(occurrence = f.evidence.occurrence.copy(planId = id)))),
                404, "RESOURCE_NOT_ACCESSIBLE")
        assertNull(stored(f.evidence)); accepted(send(f, op))
    }
    @Test fun `current identity and role precede body details and caregiver cannot register even after consent`() {
        val f = fixture(); val outsider = http.login(UserRole.ELDER); val stranger = http.login(UserRole.CAREGIVER)
        http.failure(http.call(outsider, "POST", path(f.elder.id), "{", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(stranger, "POST", path(f.elder.id), "{", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(f.caregiver, "POST", path(f.elder.id), "{", null), 403, "ROLE_NOT_ALLOWED")
        http.failure(http.call(f.caregiver, "POST", path(f.caregiver.id), "{", null), 403, "ROLE_NOT_ALLOWED")
        val op = key(); val first = accepted(send(f, op))
        http.relationship(http.call(f.caregiver, "POST", "/api/v1/care-relationships/${f.relationship}/revoke"))
        http.failure(http.call(f.caregiver, "POST", path(f.elder.id), "{", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        assertEquals(first, accepted(send(f, op)))
    }
    @Test fun `missing invalid expired wrong-role and removed identities cannot replay a receipt`() {
        val f = fixture(); val op = key(); accepted(send(f, op))
        fun signed(id: String = f.elder.id, role: String = "ELDER", expiry: Instant = Instant.now().plusSeconds(300)) =
            encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                .subject(id).issuer("group33-server").issuedAt(Instant.now().minusSeconds(7200)).expiresAt(expiry)
                .claim("type", "anonymous").claim("role", role).build())).tokenValue
        for (token in listOf(null, "invalid", signed(id = "missing"), signed(role = "CAREGIVER"), signed(expiry = Instant.now().minusSeconds(3600)))) {
            val response = http.call(f.elder, "POST", path(f.elder.id), encode(f.evidence), op, token)
            http.failure(response, 401, "UNAUTHENTICATED"); assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
        }
    }
    @ParameterizedTest
    @ValueSource(strings = ["null", "array", "missing", "null-items", "object-items", "null-item", "unknown-root", "unknown-evidence",
        "missing-field", "null-occurrence", "unknown-occurrence", "numeric-plan", "string-revision", "float-revision", "overflow-revision",
        "string-time", "float-offset", "overflow-offset", "numeric-policy", "unknown-policy", "duplicate", "nested-duplicate", "trailing"])
    fun `strict nested decoder rejects coercion ambiguity and unknown identity fields before consuming operation`(kind: String) {
        val f = fixture(); val op = key(); val valid = encode(f.evidence)
        val tree = mapper.readTree(valid) as ObjectNode; val node = tree["items"][0] as ObjectNode
        val occurrence = node["occurrence"] as ObjectNode
        val invalid = when (kind) {
            "null" -> "null"; "array" -> "[]"; "missing" -> "{}"
            "null-items" -> { tree.putNull("items"); tree.toString() }
            "object-items" -> "{\"items\":{}}"; "null-item" -> "{\"items\":[null]}"
            "unknown-root" -> { tree.put("actorId", "spoof"); tree.toString() }
            "unknown-evidence" -> { node.put("installationId", "spoof"); tree.toString() }
            "missing-field" -> { node.remove("sourceSnapshotVersion"); tree.toString() }
            "null-occurrence" -> { node.putNull("occurrence"); tree.toString() }
            "unknown-occurrence" -> { occurrence.put("offset", 0); tree.toString() }
            "numeric-plan" -> { occurrence.put("planId", 1); tree.toString() }
            "string-revision" -> { node.put("planRevision", "1"); tree.toString() }
            "float-revision" -> { node.put("planRevision", 1.0); tree.toString() }
            "overflow-revision" -> valid.replace("\"planRevision\":1", "\"planRevision\":9223372036854775808")
            "string-time" -> { node.put("plannedAtEpochMillis", f.evidence.plannedAtEpochMillis.toString()); tree.toString() }
            "float-offset" -> { node.put("resolvedOffsetSeconds", 28800.0); tree.toString() }
            "overflow-offset" -> { node.put("resolvedOffsetSeconds", 2147483648L); tree.toString() }
            "numeric-policy" -> { node.put("resolutionPolicyVersion", 0); tree.toString() }
            "unknown-policy" -> { node.put("resolutionPolicyVersion", "OTHER"); tree.toString() }
            "duplicate" -> valid.dropLast(1) + ",\"items\":[]}"
            "nested-duplicate" -> valid.replace("\"planRevision\":1", "\"planRevision\":1,\"planRevision\":2")
            else -> "$valid {}"
        }
        http.failure(send(f, op, invalid), 400, "MALFORMED_REQUEST")
        assertNull(stored(f.evidence)); accepted(send(f, op))
    }
    @Test fun `header and query validation also apply to accepted replays`() {
        val f = fixture(); val op = key(); val first = accepted(send(f, op))
        for (header in listOf(null, "", "1-1-1-1-1", "$op,x", " $op "))
            http.failure(send(f, header), 400, "MALFORMED_REQUEST")
        http.failure(http.call(f.elder, "POST", path(f.elder.id), encode(f.evidence), op, duplicateKey = true), 400, "MALFORMED_REQUEST")
        http.failure(http.call(f.elder, "POST", path(f.elder.id) + "?actorId=spoof", encode(f.evidence), op), 400, "MALFORMED_REQUEST")
        assertEquals(first, accepted(send(f, op)))
    }
    @Test fun `bounded contention and private database failures are retryable without event publication`() {
        val f = fixture(); val op = key()
        store.failure = CodeStoreBusy()
        val busy = send(f, op); http.failure(busy, 409, "OPERATION_IN_PROGRESS", true); assertEquals("1", busy.getHeader("Retry-After"))
        store.failure = java.sql.SQLException("PRIVATE password token jdbc")
        val failed = send(f, op); http.failure(failed, 503, "SERVICE_UNAVAILABLE", true)
        assertFalse(failed.contentAsString.contains("PRIVATE")); assertFalse(failed.contentAsString.contains("jdbc"))
        store.failure = null; assertNull(stored(f.evidence)); accepted(send(f, op))
    }
    @Test fun `parallel HTTP recovery creates one canonical event and returns identical original receipts`() {
        val f = fixture(); val op = key(); val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map { pool.submit<OccurrenceRegistrationResponse> { accepted(send(f, op)) } }
            val responses = tasks.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, responses.distinct().size)
            assertEquals(responses.first().items.single(), stored(f.evidence)!!.receipt)
        } finally { pool.shutdownNow() }
    }
    @Test fun `method and content type errors use existing transport envelope`() {
        val f = fixture()
        http.failure(http.call(f.elder, "GET", path(f.elder.id), null), 405, "METHOD_NOT_ALLOWED")
        http.failure(http.call(f.elder, "POST", path(f.elder.id), encode(f.evidence), contentType = "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE")
    }
    @TestConfiguration(proxyBeanMethods = false)
    class Controls {
        @Bean fun controlledRegistrationStore(runtime: PersistenceRuntime) = ControlledStore(runtime.pairing as OccurrenceRegistrationStore)
        @Bean @Primary fun controlledRegistrationService(control: ControlledStore, clock: Clock) =
            OccurrenceRegistrationService(object : OccurrenceRegistrationStore by control.backing {
                override fun <T> registrationTransaction(work: (OccurrenceRegistrationTransaction) -> T): T =
                    control.registrationTransaction(work)
            }, clock)
    }
    class ControlledStore(val backing: OccurrenceRegistrationStore) {
        @Volatile var failure: Exception? = null
        fun <T> registrationTransaction(work: (OccurrenceRegistrationTransaction) -> T): T {
            failure?.let { throw it }; return backing.registrationTransaction(work)
        }
    }
    companion object {
        private val peers = AtomicInteger(100)
        private val root = Files.createTempDirectory("group33-occurrence-http-")
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) { registry.add("app.photo.root") { root.toString() } }
        @JvmStatic @AfterAll fun cleanup() { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
