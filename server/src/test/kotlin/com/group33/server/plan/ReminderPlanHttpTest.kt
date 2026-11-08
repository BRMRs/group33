package com.group33.server.plan

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.pairing.PairingHttpFixture
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/** Full filters, real signed sessions, consent, photo IO and initial confirmation transaction. */
@SpringBootTest(properties = ["app.plan.enabled=true", "app.photo.enabled=true", "app.persistence.mode=memory"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
internal class ReminderPlanHttpTest(@Autowired private val mvc: MockMvc, @Autowired private val store: PlanConfirmationStore,
    @Autowired private val objects: com.group33.server.photo.storage.PhotoObjectStore) {
    private val http = PairingHttpFixture(mvc)
    private val mapper = ObjectMapper()
    private fun key() = UUID.randomUUID().toString()
    private fun path(id: String) = "/api/v1/elders/$id/reminder-plan-confirmations"
    private fun pair(e: PairingHttpFixture.Session, c: PairingHttpFixture.Session): String {
        val input = http.json.encodeToString(RequestPairingRequest.serializer(), RequestPairingRequest(http.issue(e).code))
        val pending = http.request(http.call(c, "POST", PairingHttpFixture.REQUESTS, input,
            remote = "192.0.2.${peers.incrementAndGet()}"), 201)
        return http.relationship(http.call(e, "POST", "${PairingHttpFixture.REQUESTS}/${pending.id}/accept")).id
    }
    private fun photo(e: PairingHttpFixture.Session): MedicationPhotoView {
        val source = ByteArrayOutputStream().use { out ->
            val image = BufferedImage(1,1,BufferedImage.TYPE_INT_RGB)
            try { assertTrue(ImageIO.write(image, "png", out)); out.toByteArray() } finally { image.flush() }
        }
        val response = mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/elders/${e.id}/medication-photos")
            .file(MockMultipartFile("file", "fictional.png", "image/png", source))
            .header("Authorization", "Bearer ${e.token}").header(ContractProtocol.IDEMPOTENCY_HEADER, key())).andReturn().response
        assertEquals(201, response.status)
        return http.json.decodeFromString<MedicationPhotoView>(response.contentAsString)
    }
    private fun input(photoId: String) = ConfirmReminderPlanRequest(photoId, MedicationText("虚构药品", "虚构用量", " 保留说明 "),
        ReminderPlanDraft(listOf("20:00", "08:00"), "2026-10-27", "Asia/Shanghai"),
        TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai"))
    private fun encode(input: ConfirmReminderPlanRequest) = http.json.encodeToString(ConfirmReminderPlanRequest.serializer(), input)
    private fun confirm(c: PairingHttpFixture.Session, id: String, input: ConfirmReminderPlanRequest, operation: String? = key()) =
        http.call(c, "POST", path(id), encode(input), operation)
    private fun accepted(response: MockHttpServletResponse): ConfirmReminderPlanResponse {
        assertEquals(201, response.status); http.noStore(response)
        assertEquals(setOf("medication", "plan"), mapper.readTree(response.contentAsString).fieldNames().asSequence().toSet())
        assertFalse(response.contentAsString.contains(root.toString()))
        return http.json.decodeFromString<ConfirmReminderPlanResponse>(response.contentAsString)
    }
    private fun medications(s: PairingHttpFixture.Session, id: String): List<MedicationView> {
        val response = http.call(s, "GET", "/api/v1/elders/$id/medications", null)
        assertEquals(200, response.status); http.noStore(response)
        assertEquals(setOf("items"), mapper.readTree(response.contentAsString).fieldNames().asSequence().toSet())
        return http.json.decodeFromString<ItemsResponse<MedicationView>>(response.contentAsString).items
    }
    private fun plans(s: PairingHttpFixture.Session, id: String): ReminderPlanCollection {
        val response = http.call(s, "GET", "/api/v1/elders/$id/reminder-plans", null)
        assertEquals(200, response.status); http.noStore(response)
        assertEquals(setOf("items", "planRevisions"), mapper.readTree(response.contentAsString).fieldNames().asSequence().toSet())
        return http.json.decodeFromString<ReminderPlanCollection>(response.contentAsString)
    }
    private data class Fixture(val elder: PairingHttpFixture.Session, val caregiver: PairingHttpFixture.Session,
        val relationship: String, val request: ConfirmReminderPlanRequest)
    private fun fixture(): Fixture {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER)
        return Fixture(e, c, pair(e,c), input(photo(e).id))
    }

    @Test fun `empty and saved views decode with shared wire DTOs for elder and current caregiver`() {
        val f = fixture(); val e=f.elder; val c=f.caregiver
        assertTrue(medications(e,e.id).isEmpty()); assertEquals(ReminderPlanCollection(emptyList(),emptyList()),plans(c,e.id))
        val saved=accepted(confirm(c,e.id,f.request)); assertEquals(e.id,saved.medication.elderId)
        assertEquals(saved.medication.id,saved.plan.medicationId); assertEquals(1L,saved.plan.revision)
        assertEquals(listOf("08:00","20:00"),saved.plan.localTimes); assertNull(saved.plan.endDate)
        for (s in listOf(e,c)) {
            assertEquals(listOf(saved.medication),medications(s,e.id))
            assertEquals(ReminderPlanCollection(listOf(saved.plan), listOf(PlanConfirmationCopies.initialRevision(saved))),plans(s,e.id))
        }
    }

    @Test fun `same operation replays exact resource IDs timestamps and text with canonical time order`() {
        val f=fixture(); val op=key(); val original=accepted(confirm(f.caregiver,f.elder.id,f.request,op.uppercase()))
        val replay=accepted(confirm(f.caregiver,f.elder.id,f.request.copy(plan=f.request.plan.copy(localTimes=listOf("08:00","20:00"))),op))
        assertEquals(original,replay); assertEquals(1,medications(f.elder,f.elder.id).size)
        val changed=f.request.copy(medication=f.request.medication.copy(instructions="changed"))
        http.failure(confirm(f.caregiver,f.elder.id,changed,op),409,"IDEMPOTENCY_KEY_REUSED")
        val second=accepted(confirm(f.caregiver,f.elder.id,f.request)); assertNotEquals(original.plan.id,second.plan.id)
        assertEquals(2,plans(f.elder,f.elder.id).items.size)
    }

    @Test fun `pending unrelated and revoked callers cannot save query or replay accepted receipts`() {
        val f=fixture(); val e=f.elder; val c=f.caregiver; val other=http.login(UserRole.CAREGIVER); val stranger=http.login(UserRole.ELDER)
        http.request(http.submit(other,http.issue(e).code),201)
        val op=key(); val original=accepted(confirm(c,e.id,f.request,op))
        for (caller in listOf(other,stranger)) {
            http.failure(confirm(caller,e.id,f.request),404,"RESOURCE_NOT_ACCESSIBLE")
            for (suffix in listOf("medications","reminder-plans"))
                http.failure(http.call(caller,"GET","/api/v1/elders/${e.id}/$suffix",null),404,"RESOURCE_NOT_ACCESSIBLE")
        }
        http.relationship(http.call(c,"POST","/api/v1/care-relationships/${f.relationship}/revoke"))
        http.failure(confirm(c,e.id,f.request,op),404,"RESOURCE_NOT_ACCESSIBLE")
        for (suffix in listOf("medications","reminder-plans"))
            http.failure(http.call(c,"GET","/api/v1/elders/${e.id}/$suffix",null),404,"RESOURCE_NOT_ACCESSIBLE")
        assertEquals(listOf(original.medication),medications(e,e.id)); assertEquals(listOf(original.plan),plans(e,e.id).items)
    }

    @Test fun `all routes require signed verified JWTs and elder cannot create a plan for self`() {
        val f=fixture()
        for (token in listOf(null,"invalid")) {
            for ((method,route,body) in listOf(Triple("POST",path(f.elder.id),encode(f.request)),
                Triple("GET","/api/v1/elders/${f.elder.id}/medications",null),
                Triple("GET","/api/v1/elders/${f.elder.id}/reminder-plans",null))) {
                val response=http.call(f.caregiver,method,route,body,token=token)
                http.failure(response,401,"UNAUTHENTICATED"); assertNotNull(response.getHeader("WWW-Authenticate"))
            }
        }
        http.failure(confirm(f.elder,f.elder.id,f.request),403,"ROLE_NOT_ALLOWED")
        assertTrue(medications(f.elder,f.elder.id).isEmpty())
    }

    @Test fun `validation is field scoped after authorization and rejected operation stays payload bound`() {
        val f=fixture(); val op=key(); val invalid=f.request.copy(medication=f.request.medication.copy(displayName="  "))
        repeat(2) {
            val response=confirm(f.caregiver,f.elder.id,invalid,op); assertEquals(422,response.status); http.noStore(response)
            val body=http.json.decodeFromString<ApiError>(response.contentAsString)
            assertEquals("VALIDATION_FAILED",body.code); assertFalse(body.retryable); assertNull(body.reason)
            assertEquals(listOf(com.group33.shared.model.FieldError("medication.displayName","REQUIRED")),body.fieldErrors)
            assertEquals(body.requestId,response.getHeader("X-Request-ID")); assertNotNull(UUID.fromString(body.requestId))
        }
        http.failure(confirm(f.caregiver,f.elder.id,f.request,op),409,"IDEMPOTENCY_KEY_REUSED")
        val outsider=http.login(UserRole.CAREGIVER)
        http.failure(confirm(outsider,f.elder.id,invalid),404,"RESOURCE_NOT_ACCESSIBLE")
        assertTrue(medications(f.elder,f.elder.id).isEmpty()); accepted(confirm(f.caregiver,f.elder.id,f.request))
    }

    @Test fun `missing and foreign photos are non disclosing and no resources are published`() {
        val f=fixture(); val other=http.login(UserRole.ELDER)
        for (id in listOf(key(),photo(other).id))
            http.failure(confirm(f.caregiver,f.elder.id,f.request.copy(photoId=id)),404,"RESOURCE_NOT_ACCESSIBLE")
        assertTrue(medications(f.elder,f.elder.id).isEmpty()); assertTrue(plans(f.elder,f.elder.id).planRevisions.isEmpty())
    }

    @Test fun `DST resolved collision returns scoped overlap error and commits no resources`() {
        val f=fixture(); val zone="America/New_York"
        val invalid=f.request.copy(plan=ReminderPlanDraft(listOf("02:30","03:30"),"2027-03-14",zone,"2027-03-14"),
            timeZoneAcknowledgement=TimeZoneAcknowledgement(zone,false,zone,zone))
        val response=confirm(f.caregiver,f.elder.id,invalid); assertEquals(422,response.status); http.noStore(response)
        val error=http.json.decodeFromString<ApiError>(response.contentAsString)
        assertEquals("OVERLAPPING_PLAN",error.code)
        assertEquals(listOf(com.group33.shared.model.FieldError("plan.localTimes","DUPLICATE_RESOLVED_INSTANT")),error.fieldErrors)
        assertTrue(medications(f.elder,f.elder.id).isEmpty())
    }

    @Test fun `missing private object is retryable and original rejected binding survives repair`() {
        val f=fixture(); val op=key()
        val objectKey=store.confirmationTransaction { checkNotNull(it.readyPhoto(f.request.photoId)).objectKey }
        val blob=root.resolve("${objectKey.objectId}.blob"); val source=Files.readAllBytes(blob); Files.delete(blob)
        http.failure(confirm(f.caregiver,f.elder.id,f.request,op),503,"SERVICE_UNAVAILABLE",true)
        assertTrue(medications(f.elder,f.elder.id).isEmpty())
        http.failure(confirm(f.caregiver,f.elder.id,f.request.copy(medication=f.request.medication.copy(instructions="changed")),op),409,"IDEMPOTENCY_KEY_REUSED")
        source.inputStream().use { objects.putIfAbsent(objectKey,it) }
        val saved=accepted(confirm(f.caregiver,f.elder.id,f.request,op))
        assertEquals(saved,accepted(confirm(f.caregiver,f.elder.id,f.request,op)))
    }

    @ParameterizedTest @ValueSource(strings=["null","[]","{}","unknown","numeric-photo","null-medication","missing-plan",
        "null-ack","numeric-time","boolean-name","string-ack","numeric-ack","duplicate","trailing"])
    fun `strict transport rejects malformed incomplete coercible or ambiguous payloads`(kind:String) {
        val f=fixture(); val tree=mapper.readTree(encode(f.request)) as com.fasterxml.jackson.databind.node.ObjectNode
        val body=when(kind) {
            "null","[]","{}" -> kind
            "unknown" -> { tree.put("actorId","spoof"); tree.toString() }
            "numeric-photo" -> { tree.put("photoId",123); tree.toString() }
            "null-medication" -> { tree.putNull("medication"); tree.toString() }
            "missing-plan" -> { tree.remove("plan"); tree.toString() }
            "null-ack" -> { tree.putNull("timeZoneAcknowledgement"); tree.toString() }
            "numeric-time" -> { (tree["plan"] as com.fasterxml.jackson.databind.node.ObjectNode).putArray("localTimes").add(800); tree.toString() }
            "boolean-name" -> { (tree["medication"] as com.fasterxml.jackson.databind.node.ObjectNode).put("displayName",true); tree.toString() }
            "string-ack" -> { (tree["timeZoneAcknowledgement"] as com.fasterxml.jackson.databind.node.ObjectNode).put("mismatchAcknowledged","false"); tree.toString() }
            "numeric-ack" -> { (tree["timeZoneAcknowledgement"] as com.fasterxml.jackson.databind.node.ObjectNode).put("mismatchAcknowledged",0); tree.toString() }
            "duplicate" -> encode(f.request).dropLast(1)+",\"photoId\":\"ambiguous\"}"
            else -> encode(f.request)+" {}"
        }
        http.failure(http.call(f.caregiver,"POST",path(f.elder.id),body),400,"MALFORMED_REQUEST")
        assertTrue(medications(f.elder,f.elder.id).isEmpty())
    }

    @Test fun `single full operation header and no extra query parameters are required`() {
        val f=fixture()
        for (op in listOf(null,"","bad","1-1-1-1-1",key()+","+key()))
            http.failure(confirm(f.caregiver,f.elder.id,f.request,op),400,"MALFORMED_REQUEST")
        http.failure(http.call(f.caregiver,"POST",path(f.elder.id),encode(f.request),duplicateKey=true),400,"MALFORMED_REQUEST")
        http.failure(http.call(f.caregiver,"POST",path(f.elder.id)+"?elderId=spoof",encode(f.request)),400,"MALFORMED_REQUEST")
        for (suffix in listOf("medications","reminder-plans"))
            http.failure(http.call(f.elder,"GET","/api/v1/elders/${f.elder.id}/$suffix?actorId=spoof",null),400,"MALFORMED_REQUEST")
        http.failure(http.call(f.caregiver,"POST",path(f.elder.id),encode(f.request),contentType="text/plain"),415,"UNSUPPORTED_MEDIA_TYPE")
        assertTrue(medications(f.elder,f.elder.id).isEmpty())
    }

    @Test fun `concurrent authenticated HTTP retries publish one medication plan revision and receipt`() {
        val f=fixture(); val op=key(); val pool=Executors.newFixedThreadPool(8); val go=CountDownLatch(1)
        try {
            val futures=(1..8).map { pool.submit(Callable { go.await();accepted(confirm(f.caregiver,f.elder.id,f.request,op)) }) }
            go.countDown(); val results=futures.map { it.get(20,TimeUnit.SECONDS) }
            assertTrue(results.all { it==results.first() }); assertEquals(1,medications(f.elder,f.elder.id).size)
            assertEquals(1,plans(f.elder,f.elder.id).planRevisions.size)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
    }
    companion object {
        private val root=Files.createTempDirectory("s2-plan-http-")
        private val peers=AtomicInteger(1)
        @JvmStatic @DynamicPropertySource fun properties(registry:DynamicPropertyRegistry) { registry.add("app.photo.root") { root.toString() } }
        @JvmStatic @AfterAll fun cleanup() { Files.walk(root).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
