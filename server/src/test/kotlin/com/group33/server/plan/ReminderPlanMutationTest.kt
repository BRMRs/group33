package com.group33.server.plan

import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.*
import com.group33.server.photo.*
import com.group33.server.photo.storage.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Path
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Same behavioral contract runs against real copy-on-write memory and the real PostgreSQL adapter. */
internal abstract class PlanMutationStoreContract {
    @TempDir lateinit var directory: Path
    protected lateinit var store: PlanMutationStore
    protected val elder=AnonymousUser("fictional-edit-elder","fixture-e-installation","虚构长者",UserRole.ELDER)
    protected val caregiver=AnonymousUser("fictional-edit-caregiver","fixture-c-installation","虚构照护者",UserRole.CAREGIVER)
    protected val clock=MutationClock()
    protected lateinit var original: ConfirmReminderPlanResponse
    protected lateinit var request: ConfirmReminderPlanRequest
    protected lateinit var relation: CareRelationshipView
    protected val confirmKey=UUID.randomUUID()
    protected open fun prepareStore(): PlanMutationStore = InMemoryPairingCodeStore(10_000)
    protected fun key()=UUID.randomUUID()
    protected fun service(value: PlanMutationStore = store)=ReminderPlanMutationService(value,clock)
    @BeforeEach fun prepare() {
        store=prepareStore()
        val code=assertInstanceOf(CodeIssuanceResult.Issued::class.java,PairingCodeIssuanceService(store,clock).issue(elder,key())).view.code
        val pending=assertInstanceOf(PairingRequestResult.Success::class.java,PairingRequestService(store,clock).request(caregiver,key(),
            RequestPairingRequest(code),InetAddress.getByName("192.0.2.44"))).view
        relation=assertInstanceOf(PairingDecisionResult.Relationship::class.java,PairingDecisionService(store,clock).accept(elder,pending.id,key())).view
        val bytes=ByteArrayOutputStream().use { ImageIO.write(BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",it); it.toByteArray() }
        val objects=LocalFilePhotoObjectStore(directory.resolve("objects"))
        val photo=assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,MedicationPhotoService(store,objects,clock).upload(elder,elder.id,key(),bytes,"image/png")).view
        request=ConfirmReminderPlanRequest(photo.id,MedicationText("虚构原药品","虚构原用量"," 原说明 "),
            ReminderPlanDraft(listOf("08:00","20:00"),"2026-10-27","Asia/Shanghai"),
            TimeZoneAcknowledgement("Asia/Shanghai",false,"Asia/Shanghai","Asia/Shanghai"))
        original=assertInstanceOf(PlanConfirmationResult.Confirmed::class.java,
            ReminderPlanConfirmationService(store,objects,clock).confirm(caregiver,elder.id,confirmKey,request)).view
    }
    protected fun input(revision: Long=1)=UpdateReminderPlanRequest(revision,request.plan.copy(localTimes=listOf("09:00")),
        request.timeZoneAcknowledgement,MedicationText(" 新虚构药品 "," 新虚构用量 ",null))
    protected fun accepted(result: PlanMutationResult)=assertInstanceOf(PlanMutationResult.Accepted::class.java,result).view
    protected fun rejected(result: PlanMutationResult,reason: PlanMutationRejection) {
        assertEquals(reason,assertInstanceOf(PlanMutationResult.Rejected::class.java,result).reason)
    }
    protected fun state()=store.mutationTransaction { Triple(it.medications(elder.id),it.plans(elder.id),it.revisions(elder.id)) }
    protected fun update(value: UpdateReminderPlanRequest=input(),id: UUID=key(),actor: AnonymousUser=caregiver,planId: String=original.plan.id)=
        service().update(actor,planId,id,value)
    @Test fun `edit commits text plan and frozen new revision while original receipt and revision survive`() {
        val before=state(); clock.now.addAndGet(12_345)
        val edited=accepted(update())
        assertEquals(2L,edited.plan.revision); assertEquals(original.plan.id,edited.plan.id)
        assertEquals(original.medication.id,edited.medication.id); assertEquals(original.medication.photoId,edited.medication.photoId)
        assertEquals(" 新虚构药品 ",edited.medication.displayName); assertNull(edited.medication.instructions)
        assertEquals(clock.millis(),edited.plan.effectiveFromEpochMillis); assertEquals(clock.millis(),edited.plan.confirmedAtEpochMillis)
        assertTrue(edited.plan.active); val after=state()
        assertEquals(listOf(edited.medication),after.first); assertEquals(listOf(edited.plan),after.second)
        assertTrue(after.third.containsAll(before.third)); assertEquals(2,after.third.size)
        assertEquals(PlanConfirmationCopies.initialRevision(ConfirmReminderPlanResponse(edited.medication,edited.plan)),after.third.single { it.revision==2L })
        assertEquals(original,store.confirmationTransaction { it.confirmationOperation(PlanConfirmationKey(caregiver.id,confirmKey))!!.receipt })
    }
    @Test fun `absent optional text keeps current medication and own previous revision never overlaps`() {
        val same=UpdateReminderPlanRequest(1,request.plan,request.timeZoneAcknowledgement)
        assertEquals(original.medication,accepted(update(same)).medication); assertEquals(2,state().third.size)
    }
    @Test fun `retry before revision check returns original edit even after another edit and deactivation`() {
        val id=key(); val first=input(); val receipt=accepted(update(first,id))
        accepted(update(input(2).copy(medication=MedicationText("另一次虚构编辑","虚构用量"))))
        accepted(service().deactivate(caregiver,original.plan.id,key(),DeactivateReminderPlanRequest(3)))
        clock.now.addAndGet(86_400_000)
        assertEquals(receipt,accepted(service().update(caregiver,original.plan.id.uppercase(),id,
            first.copy(plan=first.plan.copy(localTimes=first.plan.localTimes.reversed())))))
        assertEquals(4,state().third.size); assertFalse(state().second.single().active)
    }
    @Test fun `deactivation retains history medication and exact original stop receipt across later edits`() {
        val id=key(); val stopped=accepted(service().deactivate(caregiver,original.plan.id,id,DeactivateReminderPlanRequest(1)))
        assertFalse(stopped.plan.active); assertEquals(2L,stopped.plan.revision); assertEquals(original.medication,stopped.medication)
        val later=accepted(update(input(2))); assertFalse(later.plan.active)
        assertEquals(stopped,accepted(service().deactivate(caregiver,original.plan.id,id,DeactivateReminderPlanRequest(1))))
        assertEquals(3,state().third.size); assertTrue(state().third.single { it.revision==1L }.active)
    }
    @Test fun `new stale edit and stop both conflict without any business mutation`() {
        accepted(update()); val saved=state()
        rejected(update(),PlanMutationRejection.PLAN_VERSION_CONFLICT)
        rejected(service().deactivate(caregiver,original.plan.id,key(),DeactivateReminderPlanRequest(1)),PlanMutationRejection.PLAN_VERSION_CONFLICT)
        assertEquals(saved,state())
    }
    @ParameterizedTest @ValueSource(strings=["revision","text","time","duplicate","zone","ack","installation","target"])
    fun `accepted intent cannot be rebound to changed content or context`(kind: String) {
        val id=key(); val first=input(); accepted(update(first,id)); val saved=state()
        var actor=caregiver; var planId=original.plan.id
        val changed=when(kind) {
            "revision"->first.copy(expectedRevision=2)
            "text"->first.copy(medication=first.medication!!.copy(instructions=""))
            "time"->first.copy(plan=first.plan.copy(localTimes=listOf("10:00")))
            "duplicate"->first.copy(plan=first.plan.copy(localTimes=listOf("09:00","09:00")))
            "zone"->first.copy(plan=first.plan.copy(executionTimeZoneId="Etc/UTC"))
            "ack"->first.copy(timeZoneAcknowledgement=first.timeZoneAcknowledgement.copy(mismatchAcknowledged=true))
            "installation"->{actor=actor.copy(installationId="different-installation");first}
            else->{planId=key().toString();first}
        }
        rejected(update(changed,id,actor,planId),if(kind=="target") PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE else PlanMutationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(saved,state())
    }
    @Test fun `current authorization precedes accepted replay and changed fingerprint discovery`() {
        val id=key(); accepted(update(id=id))
        PairingDecisionService(store,clock).revoke(elder,relation.id,key())
        rejected(update(id=id),PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejected(update(input().copy(expectedRevision=99),id),PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejected(service().deactivate(caregiver,original.plan.id,key(),DeactivateReminderPlanRequest(2)),PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(2,state().third.size)
    }
    @Test fun `wrong role unrelated and missing plan are safely distinguished only for own elder`() {
        rejected(update(actor=elder),PlanMutationRejection.ROLE_NOT_ALLOWED)
        rejected(update(actor=caregiver.copy(id="stranger")),PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
        for(id in listOf(key().toString(),"not-a-plan","1-1-1-1-1"))
            rejected(update(planId=id),PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(1,state().third.size)
    }
    @ParameterizedTest @ValueSource(strings=["revision","name","dosage","date","times","zone","ack","dst"])
    fun `invalid requests preserve old confirmed resources and retain rejected binding`(kind: String) {
        val good=input(); val bad=when(kind) {
            "revision"->good.copy(expectedRevision=0)
            "name"->good.copy(medication=good.medication!!.copy(displayName="\t "))
            "dosage"->good.copy(medication=good.medication!!.copy(dosageText=""))
            "date"->good.copy(plan=good.plan.copy(startDate="2026-02-30"))
            "times"->good.copy(plan=good.plan.copy(localTimes=listOf("09:00","09:00")))
            "zone"->good.copy(plan=good.plan.copy(executionTimeZoneId="+08:00"))
            "ack"->good.copy(timeZoneAcknowledgement=good.timeZoneAcknowledgement.copy(caregiverTimeZoneId=null))
            else->good.copy(plan=ReminderPlanDraft(listOf("02:30","03:30"),"2026-03-09","America/New_York"),
                timeZoneAcknowledgement=TimeZoneAcknowledgement("America/New_York",true))
        }
        val saved=state(); val id=key()
        rejected(update(bad,id),if(kind=="dst") PlanMutationRejection.OVERLAPPING_PLAN else PlanMutationRejection.VALIDATION_FAILED)
        assertEquals(saved,state()); rejected(update(good,id),PlanMutationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(saved,state()); accepted(update(good))
    }
    @Test fun `parallel same-key requests apply once and different stale keys have one winner`() {
        val pool=Executors.newFixedThreadPool(8)
        try {
            val id=key(); val results=(1..8).map { pool.submit<PlanMutationResult> { update(id=id) } }.map { it.get(30,TimeUnit.SECONDS) }
            val receipt=accepted(results.first()); results.forEach { assertEquals(receipt,accepted(it)) }
            assertEquals(2,state().third.size)
            val next=(1..8).map { pool.submit<PlanMutationResult> { update(input(2)) } }.map { it.get(30,TimeUnit.SECONDS) }
            assertEquals(1,next.count { it is PlanMutationResult.Accepted })
            assertEquals(7,next.count { it==PlanMutationResult.Rejected(PlanMutationRejection.PLAN_VERSION_CONFLICT) })
            assertEquals(3,state().third.size)
        } finally { pool.shutdownNow() }
    }
    @ParameterizedTest @ValueSource(strings=["medication","plan","revision","receipt"])
    fun `adapter failure after every staged write rolls back whole mutation then same intent can retry`(point: String) {
        val saved=state(); val id=key()
        val fault=object : PlanMutationStore by store {
            override fun <T> mutationTransaction(work: (PlanMutationTransaction)->T): T = store.mutationTransaction { tx ->
                work(object : PlanMutationTransaction by tx {
                    override fun updateMedication(value: MedicationView) { tx.updateMedication(value); if(point=="medication") error("fixture fault") }
                    override fun updatePlan(value: ReminderPlanView,expectedRevision: Long) { tx.updatePlan(value,expectedRevision); if(point=="plan") error("fixture fault") }
                    override fun insertRevision(value: PlanRevisionView) { tx.insertRevision(value); if(point=="revision") error("fixture fault") }
                    override fun putMutationOperation(key: PlanMutationKey,value: PlanMutationOperation) { tx.putMutationOperation(key,value); if(point=="receipt") error("fixture fault") }
                })
            }
        }
        rejected(service(fault).update(caregiver,original.plan.id,id,input()),PlanMutationRejection.SERVICE_UNAVAILABLE)
        assertEquals(saved,state()); assertNull(store.mutationTransaction { it.mutationOperation(PlanMutationKey(caregiver.id,PlanMutationAction.UPDATE,id)) })
        accepted(update(id=id)); assertEquals(2,state().third.size)
    }
    @ParameterizedTest @ValueSource(strings=["same-medication","other-medication","other-elder","inactive","disjoint","one-minute"])
    fun `cross-plan overlap uses identity activity and full date context only`(kind: String) {
        val other=original.plan.copy(id=key().toString(),localTimes=listOf(if(kind=="one-minute") "09:01" else "09:00"),
            medicationId=if(kind=="other-medication") key().toString() else original.medication.id,
            elderId=if(kind=="other-elder") "other-elder" else elder.id,active=kind!="inactive",
            startDate=if(kind=="disjoint") "2026-10-26" else "2026-10-27",endDate="2026-10-26".takeIf { kind=="disjoint" })
        // Test-only read fixture: v7's initial-confirmation seam intentionally creates one plan per
        // new Medication. No new public same-Medication creation API or weakened SQL constraint.
        val fixture=object : PlanMutationStore by store {
            override fun <T> mutationTransaction(work:(PlanMutationTransaction)->T): T=store.mutationTransaction { tx ->
                work(object : PlanMutationTransaction by tx { override fun plans(elderId:String)=tx.plans(elderId)+other })
            }
        }
        val saved=state(); val result=service(fixture).update(caregiver,original.plan.id,key(),input())
        if(kind=="same-medication") { rejected(result,PlanMutationRejection.OVERLAPPING_PLAN); assertEquals(saved,state()) }
        else { accepted(result); assertEquals(2,state().third.size) }
    }

    @Test fun `explicit retry of rejected original overlap can succeed after precondition repair`() {
        var occupied=true
        val other=original.plan.copy(id=key().toString(),localTimes=listOf("09:00"))
        val fixture=object : PlanMutationStore by store {
            override fun <T> mutationTransaction(work:(PlanMutationTransaction)->T): T=store.mutationTransaction { tx ->
                work(object : PlanMutationTransaction by tx { override fun plans(elderId:String)=tx.plans(elderId)+other.copy(active=occupied) })
            }
        }
        val id=key(); val payload=input(); val app=service(fixture)
        rejected(app.update(caregiver,original.plan.id,id,payload),PlanMutationRejection.OVERLAPPING_PLAN)
        assertNull(store.mutationTransaction { it.mutationOperation(PlanMutationKey(caregiver.id,PlanMutationAction.UPDATE,id))!!.receipt })
        occupied=false
        val receipt=accepted(app.update(caregiver,original.plan.id,id,payload))
        assertEquals(2L,receipt.plan.revision); assertEquals(2,state().third.size)
        assertEquals(receipt,accepted(app.update(caregiver,original.plan.id,id,payload)))
    }
    @Test fun `revision overflow rejects without changing resources or wrapping revision`() {
        val fixture=object : PlanMutationStore by store {
            override fun <T> mutationTransaction(work:(PlanMutationTransaction)->T): T=store.mutationTransaction { tx ->
                work(object : PlanMutationTransaction by tx { override fun plan(id:String)=tx.plan(id)?.copy(revision=Long.MAX_VALUE) })
            }
        }
        val saved=state()
        rejected(service(fixture).update(caregiver,original.plan.id,key(),input(Long.MAX_VALUE)),PlanMutationRejection.VALIDATION_FAILED)
        assertEquals(saved,state())
    }
    @Test fun `accepted receipt lists cannot mutate retained store state`() {
        val id=key(); val view=accepted(update(id=id)); val saved=state()
        assertThrows(UnsupportedOperationException::class.java) { (view.plan.localTimes as MutableList<String>).add("11:00") }
        assertEquals(saved,state()); assertEquals(view,accepted(update(id=id)))
    }

    @Test fun `partial Store call cannot commit medication text without matching plan and receipt`() {
        val saved=state()
        assertThrows(Exception::class.java) { store.mutationTransaction { tx ->
            tx.updateMedication(original.medication.copy(displayName="partial fictional edit"))
        } }
        assertEquals(saved,state())
    }
    @Test fun `partial Store call cannot commit plan without retained revision and receipt`() {
        val saved=state()
        assertThrows(Exception::class.java) { store.mutationTransaction { tx ->
            tx.updatePlan(original.plan.copy(revision=2,localTimes=listOf("10:00")),1)
        } }
        assertEquals(saved,state())
    }

    @Test fun `busy store returns explicit original-intent retry without side effects`() {
        val busy=object : PlanMutationStore by store { override fun <T> mutationTransaction(work:(PlanMutationTransaction)->T): T=throw CodeStoreBusy() }
        rejected(service(busy).update(caregiver,original.plan.id,key(),input()),PlanMutationRejection.OPERATION_IN_PROGRESS)
        assertEquals(1,state().third.size)
    }
}
internal class ReminderPlanMutationTest : PlanMutationStoreContract()
internal class MutationClock : Clock() {
    val now=AtomicLong(Instant.parse("2026-10-27T02:00:00Z").toEpochMilli())
    override fun getZone(): ZoneId=ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock=this
    override fun instant(): Instant=Instant.ofEpochMilli(now.get())
}
