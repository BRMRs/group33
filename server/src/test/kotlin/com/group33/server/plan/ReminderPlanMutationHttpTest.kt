package com.group33.server.plan

import com.group33.server.pairing.PairingHttpFixture
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

@SpringBootTest(properties=["app.plan.enabled=true","app.photo.enabled=true","app.persistence.mode=memory"])
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
internal class ReminderPlanMutationHttpTest(@Autowired private val mvc: MockMvc) {
    private val http=PairingHttpFixture(mvc)
    private fun key()=UUID.randomUUID().toString()
    private data class Fixture(val elder: PairingHttpFixture.Session,val caregiver: PairingHttpFixture.Session,
        val relation: String,val original: ConfirmReminderPlanResponse) {
        val path get()="/api/v1/reminder-plans/${original.plan.id}"
        fun edit()=UpdateReminderPlanRequest(1,ReminderPlanDraft(listOf("09:00"),"2026-10-27","Asia/Shanghai"),
            TimeZoneAcknowledgement("Asia/Shanghai",false,"Asia/Shanghai","Asia/Shanghai"),MedicationText("新虚构药品","新虚构用量"))
    }
    private fun fixture(): Fixture {
        val e=http.login(UserRole.ELDER); val c=http.login(UserRole.CAREGIVER)
        val pending=http.request(http.call(c,"POST",PairingHttpFixture.REQUESTS,
            http.json.encodeToString(RequestPairingRequest.serializer(),RequestPairingRequest(http.issue(e).code)),
            remote="192.0.2.${peers.incrementAndGet()}"),201)
        val relation=http.relationship(http.call(e,"POST","${PairingHttpFixture.REQUESTS}/${pending.id}/accept")).id
        val bytes=ByteArrayOutputStream().use { ImageIO.write(BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",it); it.toByteArray() }
        val photoResponse=mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/elders/${e.id}/medication-photos")
            .file(MockMultipartFile("file","fictional.png","image/png",bytes))
            .header("Authorization","Bearer ${e.token}").header(ContractProtocol.IDEMPOTENCY_HEADER,key())).andReturn().response
        assertEquals(201,photoResponse.status)
        val photo=http.json.decodeFromString<MedicationPhotoView>(photoResponse.contentAsString)
        val input=ConfirmReminderPlanRequest(photo.id,MedicationText("虚构药品","虚构用量"),
            ReminderPlanDraft(listOf("08:00"),"2026-10-27","Asia/Shanghai"),TimeZoneAcknowledgement("Asia/Shanghai",false,"Asia/Shanghai","Asia/Shanghai"))
        val response=http.call(c,"POST","/api/v1/elders/${e.id}/reminder-plan-confirmations",http.json.encodeToString(ConfirmReminderPlanRequest.serializer(),input))
        assertEquals(201,response.status)
        return Fixture(e,c,relation,http.json.decodeFromString(response.contentAsString))
    }
    private fun encode(value: UpdateReminderPlanRequest)=http.json.encodeToString(UpdateReminderPlanRequest.serializer(),value)
    private fun edit(f: Fixture,body: String=encode(f.edit()),id: String?=key(),actor: PairingHttpFixture.Session=f.caregiver,path: String=f.path)=
        http.call(actor,"PATCH",path,body,id)
    private fun success(response: MockHttpServletResponse): UpdateReminderPlanResponse {
        assertEquals(200,response.status); http.noStore(response)
        return http.json.decodeFromString(response.contentAsString)
    }
    @Test fun `signed current caregiver edits and stops through unchanged shared responses and reads retained versions`() {
        val f=fixture(); val id=key(); val saved=success(edit(f,id=id))
        assertEquals(2L,saved.plan.revision); assertEquals("新虚构药品",saved.medication.displayName)
        assertEquals(saved,success(edit(f,id=id)))
        http.failure(edit(f),409,"PLAN_VERSION_CONFLICT")
        val stopKey=key(); val stopped=http.call(f.caregiver,"POST",f.path+"/deactivate","{\"expectedRevision\":2}",stopKey)
        assertEquals(200,stopped.status); http.noStore(stopped)
        val plan=http.json.decodeFromString<ReminderPlanView>(stopped.contentAsString)
        assertFalse(plan.active); assertEquals(3L,plan.revision)
        assertEquals(stopped.contentAsString,http.call(f.caregiver,"POST",f.path+"/deactivate","{\"expectedRevision\":2}",stopKey).contentAsString)
        assertEquals(saved,success(edit(f,id=id)))
        for(actor in listOf(f.elder,f.caregiver)) {
            val read=http.call(actor,"GET","/api/v1/elders/${f.elder.id}/reminder-plans",null)
            assertEquals(200,read.status)
            val collection=http.json.decodeFromString<ReminderPlanCollection>(read.contentAsString)
            assertEquals(listOf(plan),collection.items); assertEquals(setOf(1L,2L,3L),collection.planRevisions.map { it.revision }.toSet())
            assertEquals("虚构药品",collection.planRevisions.single { it.revision==1L }.medicationSnapshot.displayName)
        }
    }
    @Test fun `revocation forbids old success replay edit and stop while elder retains read access`() {
        val f=fixture(); val id=key(); success(edit(f,id=id))
        http.call(f.elder,"POST","/api/v1/care-relationships/${f.relation}/revoke")
        http.failure(edit(f,id=id),404,"RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(f.caregiver,"POST",f.path+"/deactivate","{\"expectedRevision\":2}"),404,"RESOURCE_NOT_ACCESSIBLE")
        assertEquals(200,http.call(f.elder,"GET","/api/v1/elders/${f.elder.id}/reminder-plans",null).status)
    }
    @Test fun `unsigned unrelated own wrong-role and nonexistent plans return safe scoped errors`() {
        val f=fixture()
        http.failure(http.call(f.caregiver,"PATCH",f.path,encode(f.edit()),token=null),401,"UNAUTHENTICATED")
        http.failure(edit(f,actor=f.elder),403,"ROLE_NOT_ALLOWED")
        http.failure(edit(f,actor=http.login(UserRole.CAREGIVER)),404,"RESOURCE_NOT_ACCESSIBLE")
        for(id in listOf(key(),"invalid-id","1-1-1-1-1")) http.failure(edit(f,path="/api/v1/reminder-plans/$id"),404,"RESOURCE_NOT_ACCESSIBLE")
    }
    @ParameterizedTest @ValueSource(strings=["missing","null","string","fraction","boolean","overflow","duplicate","trailing","unknown","bool-name","null-body","array-body"])
    fun `ambiguous malformed and noninteger revisions are rejected before business changes`(kind: String) {
        val f=fixture(); val good=encode(f.edit())
        val bad=when(kind) {
            "missing"->good.replace("\"expectedRevision\":1,","")
            "null"->good.replace("\"expectedRevision\":1","\"expectedRevision\":null")
            "string"->good.replace("\"expectedRevision\":1","\"expectedRevision\":\"1\"")
            "fraction"->good.replace("\"expectedRevision\":1","\"expectedRevision\":1.0")
            "boolean"->good.replace("\"expectedRevision\":1","\"expectedRevision\":true")
            "overflow"->good.replace("\"expectedRevision\":1","\"expectedRevision\":9223372036854775808")
            "duplicate"->good.replace("\"expectedRevision\":1","\"expectedRevision\":1,\"expectedRevision\":1")
            "trailing"->good+" {}"
            "unknown"->good.dropLast(1)+",\"active\":true}"
            "bool-name"->good.replace("\"新虚构药品\"","true")
            "null-body"->"null"
            else->"[]"
        }
        http.failure(edit(f,bad),400,"MALFORMED_REQUEST")
        assertEquals(2L,success(edit(f)).plan.revision)
    }
    @ParameterizedTest @ValueSource(strings=["null","missing","string","fraction","boolean","unknown","duplicate","trailing"])
    fun `deactivate decoder obeys same strict boundary as edit`(kind:String) {
        val f=fixture()
        val bad=when(kind) {
            "null"->"{\"expectedRevision\":null}"
            "missing"->"{}"
            "string"->"{\"expectedRevision\":\"1\"}"
            "fraction"->"{\"expectedRevision\":1.2}"
            "boolean"->"{\"expectedRevision\":true}"
            "unknown"->"{\"expectedRevision\":1,\"active\":false}"
            "duplicate"->"{\"expectedRevision\":1,\"expectedRevision\":1}"
            else->"{\"expectedRevision\":1}{}"
        }
        http.failure(http.call(f.caregiver,"POST",f.path+"/deactivate",bad),400,"MALFORMED_REQUEST")
        assertEquals(2L,success(edit(f)).plan.revision)
    }
    @Test fun `well formed invalid revision has field error and cannot alter plan`() {
        val f=fixture(); val response=edit(f,encode(f.edit().copy(expectedRevision=0)))
        assertEquals(422,response.status); http.noStore(response)
        val failure=http.json.decodeFromString<ApiError>(response.contentAsString)
        assertEquals("VALIDATION_FAILED",failure.code); assertEquals("expectedRevision",failure.fieldErrors!!.single().field)
        assertEquals(2L,success(edit(f)).plan.revision)
    }
    @Test fun `both routes require one full idempotency header and reject query parameters`() {
        val f=fixture()
        http.failure(edit(f,id=null),400,"MALFORMED_REQUEST")
        http.failure(edit(f,id="1-1-1-1-1"),400,"MALFORMED_REQUEST")
        http.failure(edit(f,path=f.path+"?unexpected=true"),400,"MALFORMED_REQUEST")
        http.failure(http.call(f.caregiver,"POST",f.path+"/deactivate","{\"expectedRevision\":1}",duplicateKey=true),400,"MALFORMED_REQUEST")
        assertEquals(2L,success(edit(f)).plan.revision)
    }
    companion object {
        private val root=Files.createTempDirectory("s2-plan-mutation-http-")
        private val peers=AtomicInteger(20)
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) { registry.add("app.photo.root") { root.toString() } }
        @JvmStatic @AfterAll fun cleanup() { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
