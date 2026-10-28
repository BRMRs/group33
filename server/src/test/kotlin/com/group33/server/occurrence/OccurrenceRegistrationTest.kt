package com.group33.server.occurrence

import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.*
import com.group33.server.photo.*
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.server.plan.*
import com.group33.shared.contract.*
import com.group33.shared.model.DoseStatus
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Identical assertions execute against real memory and real PostgreSQL transactions. */
internal abstract class OccurrenceRegistrationContract {
    @TempDir lateinit var directory: Path
    protected lateinit var store: OccurrenceRegistrationStore
    protected val elder = AnonymousUser("fictional-registration-elder", "fixture-registration-install", "虚构长者", UserRole.ELDER)
    protected val caregiver = AnonymousUser("fictional-registration-caregiver", "fixture-caregiver-install", "虚构照护者", UserRole.CAREGIVER)
    protected val clock = RegistrationClock()
    protected lateinit var original: ConfirmReminderPlanResponse
    protected lateinit var relation: CareRelationshipView
    protected lateinit var objects: LocalFilePhotoObjectStore
    protected fun key() = UUID.randomUUID()
    protected open fun prepareStore(): OccurrenceRegistrationStore = InMemoryPairingCodeStore(10_000)
    @BeforeEach fun prepare() {
        store = prepareStore()
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java, PairingCodeIssuanceService(store, clock).issue(elder, key())).view.code
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java, PairingRequestService(store, clock).request(caregiver, key(),
            RequestPairingRequest(code), InetAddress.getByName("192.0.2.77"))).view
        relation = assertInstanceOf(PairingDecisionResult.Relationship::class.java, PairingDecisionService(store, clock).accept(elder, pending.id, key())).view
        objects = LocalFilePhotoObjectStore(directory.resolve("objects"))
        val bytes = ByteArrayOutputStream().use { ImageIO.write(BufferedImage(1,1,BufferedImage.TYPE_INT_RGB), "png", it); it.toByteArray() }
        val photo = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            MedicationPhotoService(store, objects, clock).upload(elder, elder.id, key(), bytes, "image/png")).view
        original = confirm(ReminderPlanDraft(listOf("08:00", "20:00"), "2026-01-01", "Asia/Shanghai", "2026-12-31"), photo.id)
    }
    protected fun confirm(draft: ReminderPlanDraft, photoId: String = original.medication.photoId!!) =
        assertInstanceOf(PlanConfirmationResult.Confirmed::class.java, ReminderPlanConfirmationService(store, objects, clock).confirm(
            caregiver, elder.id, key(), ConfirmReminderPlanRequest(photoId, MedicationText(" 原虚构药 ", "原虚构量", " 原说明 "), draft,
                TimeZoneAcknowledgement(draft.executionTimeZoneId, true)))).view
    protected fun evidence(plan: ReminderPlanView = original.plan, date: String = "2026-10-28", time: String = "08:00", generation: UUID = key()): OccurrenceGenerationEvidence {
        val zone = ZoneId.of(plan.executionTimeZoneId)
        val instant = PlanSelfOverlapValidator.resolve(LocalDate.parse(date).atTime(LocalTime.parse(time)), zone)
        return OccurrenceGenerationEvidence(generation.toString(), OccurrenceKey(plan.id, date, time), plan.revision,
            clock.millis(), plan.executionTimeZoneId, instant.toEpochMilli(), instant.atZone(zone).offset.totalSeconds,
            TimeResolutionPolicy.P0_TIME_V1, " fixture source context ", clock.millis())
    }
    protected fun service(value: OccurrenceRegistrationStore = store) = OccurrenceRegistrationService(value, clock)
    protected fun register(vararg items: OccurrenceGenerationEvidence, operation: UUID = key(), actor: AnonymousUser = elder, target: String = elder.id) =
        service().register(actor, target, operation, OccurrenceRegistrationRequest(items.toList()))
    protected fun accepted(result: RegistrationResult) = assertInstanceOf(RegistrationResult.Accepted::class.java, result).response
    protected fun rejected(result: RegistrationResult, reason: RegistrationRejection = RegistrationRejection.VALIDATION_FAILED): RegistrationResult.Rejected {
        val failure = assertInstanceOf(RegistrationResult.Rejected::class.java, result); assertEquals(reason, failure.reason); return failure
    }
    protected fun stored(e: OccurrenceGenerationEvidence) = store.registrationTransaction { it.occurrence(e.occurrence) }

    @Test fun `batch uses retained medication text unique tuples and complete original receipt`() {
        val a = evidence(); val b = evidence(time = "20:00"); val op = key()
        val result = accepted(register(a, b, operation = op))
        assertEquals(listOf(a.generationId, b.generationId), result.items.map { it.generationId })
        assertEquals(2, result.items.map { it.event.id }.distinct().size)
        result.items.forEach { r ->
            assertEquals(" 原虚构药 ", r.event.medicationSnapshot.displayName)
            assertEquals(" 原说明 ", r.event.medicationSnapshot.instructions)
            assertEquals(r.event.plannedAtEpochMillis + 7_200_000, r.event.responseDeadlineEpochMillis)
            assertEquals(DoseStatus.SCHEDULED, r.event.status); assertTrue(r.event.lateTakenReports.isEmpty())
        }
        clock.now.addAndGet(30 * 86_400_000L)
        assertEquals(result, accepted(register(a, b, operation = op)))
        assertEquals(result, accepted(register(a, b)))
    }
    @Test fun `old generated revision survives later edit and deactivation before delayed registration`() {
        val old = evidence(); clock.now.addAndGet(1000)
        val edited = assertInstanceOf(PlanMutationResult.Accepted::class.java, ReminderPlanMutationService(store, clock).update(caregiver,
            original.plan.id, key(), UpdateReminderPlanRequest(1, ReminderPlanDraft(listOf("09:00"), "2026-01-01", "Asia/Shanghai"),
                TimeZoneAcknowledgement("Asia/Shanghai", true), MedicationText("新虚构药", "新虚构量")))).view
        ReminderPlanMutationService(store, clock).deactivate(caregiver, original.plan.id, key(), DeactivateReminderPlanRequest(2))
        val result = accepted(register(old)).items.single().event
        assertEquals(1, result.planRevision); assertEquals(" 原虚构药 ", result.medicationSnapshot.displayName)
        assertEquals(old, result.generationEvidence); assertNotEquals(edited.medication.displayName, result.medicationSnapshot.displayName)
        assertEquals(3, store.mutationTransaction { it.revisions(elder.id) }.size)
    }
    @Test fun `registered event and original receipt remain frozen through later plan mutations`() {
        val e = evidence(); val op = key(); val first = accepted(register(e, operation = op))
        ReminderPlanMutationService(store, clock).deactivate(caregiver, original.plan.id, key(), DeactivateReminderPlanRequest(1))
        assertEquals(first, accepted(register(e, operation = op))); assertEquals(first.items.single(), stored(e)!!.receipt)
        val inactive = store.mutationTransaction { it.plans(elder.id).single() }
        rejected(register(evidence(inactive)), RegistrationRejection.VALIDATION_FAILED)
    }
    @Test fun `accepted generation in a new batch replays frozen receipt despite later resolver changes`() {
        val e = evidence(); val first = accepted(register(e))
        val changedResolver = OccurrenceRegistrationService(store, clock, resolve = { dateTime, zone ->
            PlanSelfOverlapValidator.resolve(dateTime, zone).plusSeconds(3600)
        })
        assertEquals(first, accepted(changedResolver.register(elder, elder.id, key(), OccurrenceRegistrationRequest(listOf(e)))))
        val fresh = evidence(time = "20:00")
        rejected(changedResolver.register(elder, elder.id, key(), OccurrenceRegistrationRequest(listOf(fresh))))
        assertNull(stored(fresh)); assertEquals(first.items.single(), stored(e)!!.receipt)
    }
    @Test fun `current self role checks precede replay and no pairing is required for elder after revocation`() {
        val e = evidence(); val op = key(); val first = accepted(register(e, operation = op))
        rejected(register(e, operation = op, actor = caregiver), RegistrationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejected(register(e, actor = caregiver, target = caregiver.id), RegistrationRejection.ROLE_NOT_ALLOWED)
        rejected(register(e, operation = op, actor = elder.copy(id = "stranger")), RegistrationRejection.RESOURCE_NOT_ACCESSIBLE)
        PairingDecisionService(store, clock).revoke(elder, relation.id, key())
        assertEquals(first, accepted(register(e, operation = op)))
    }
    @Test fun `foreign and missing plans use generic inaccessible result without binding operation`() {
        val op = key(); val e = evidence()
        for (id in listOf(key().toString(), "1-1-1-1-1", "not-a-plan"))
            rejected(register(e.copy(occurrence = e.occurrence.copy(planId = id)), operation = op), RegistrationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejected(register(e, actor = elder.copy(id = "another-elder"), target = "another-elder"), RegistrationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertNull(store.registrationTransaction { it.registrationOperation(RegistrationKey(elder.id, op)) })
        accepted(register(e, operation = op))
    }
    @ParameterizedTest @ValueSource(strings = ["time", "revision", "generation", "generated-at", "zone", "planned", "offset", "source", "source-time", "installation", "order"])
    fun `accepted key binds every field installation and item order`(kind: String) {
        val a = evidence(); val b = evidence(time = "20:00"); val op = key(); val first = accepted(register(a,b,operation=op))
        val changed = when(kind) {
            "time" -> a.copy(occurrence = a.occurrence.copy(localTime = "20:00"))
            "revision" -> a.copy(planRevision = 99)
            "generation" -> a.copy(generationId = key().toString())
            "generated-at" -> a.copy(generatedAtEpochMillis = a.generatedAtEpochMillis + 1)
            "zone" -> a.copy(executionTimeZoneId = "Etc/UTC")
            "planned" -> a.copy(plannedAtEpochMillis = a.plannedAtEpochMillis + 1)
            "offset" -> a.copy(resolvedOffsetSeconds = 0)
            "source" -> a.copy(sourceSnapshotVersion = "changed")
            "source-time" -> a.copy(sourceSnapshotServerAtEpochMillis = a.sourceSnapshotServerAtEpochMillis + 1)
            else -> a
        }
        val result = if (kind == "order") register(b,a,operation=op)
            else register(changed,b,operation=op,actor=if(kind=="installation") elder.copy(installationId="changed") else elder)
        rejected(result, RegistrationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(first, accepted(register(a,b,operation=op)))
    }
    @ParameterizedTest @ValueSource(strings = ["date", "time", "revision", "generation", "zone", "planned", "offset", "source", "source-time", "generated-at", "range"])
    fun `invalid evidence binds rejection but creates no event and corrected input uses new intent`(kind: String) {
        val a = evidence(); val op = key()
        val bad = when(kind) {
            "date" -> a.copy(occurrence = a.occurrence.copy(localDate = "2026-02-30"))
            "time" -> a.copy(occurrence = a.occurrence.copy(localTime = "8:00"))
            "revision" -> a.copy(planRevision = 99)
            "generation" -> a.copy(generationId = "invalid")
            "zone" -> a.copy(executionTimeZoneId = "Etc/UTC")
            "planned" -> a.copy(plannedAtEpochMillis = a.plannedAtEpochMillis + 1)
            "offset" -> a.copy(resolvedOffsetSeconds = 0)
            "source" -> a.copy(sourceSnapshotVersion = " ")
            "source-time" -> a.copy(sourceSnapshotServerAtEpochMillis = -1)
            "generated-at" -> a.copy(generatedAtEpochMillis = -1)
            else -> evidence(date = "2027-01-01")
        }
        assertTrue(rejected(register(bad,operation=op)).violations.isNotEmpty())
        assertNull(stored(a)); assertNull(store.registrationTransaction { it.registrationOperation(RegistrationKey(elder.id,op))!!.response })
        rejected(register(a,operation=op), RegistrationRejection.IDEMPOTENCY_KEY_REUSED)
        accepted(register(a))
    }
    @Test fun `complete batch rejects duplicates empty and last invalid item without partial events`() {
        val a=evidence(); val b=evidence(time="20:00")
        rejected(register()); rejected(register(a,a)); rejected(register(a,b.copy(generationId=a.generationId)))
        rejected(register(a,b.copy(plannedAtEpochMillis=0)))
        assertNull(stored(a)); assertNull(stored(b)); accepted(register(a,b))
    }
    @Test fun `conflicting generation or tuple never replaces accepted evidence regardless of request order`() {
        val a=evidence(); val first=accepted(register(a))
        val fresh=evidence(time="20:00")
        for (bad in listOf(a.copy(generationId=key().toString()), a.copy(sourceSnapshotVersion="other"), evidence(date="2026-10-29").copy(generationId=a.generationId))) {
            val failure=rejected(register(fresh,bad)); assertTrue(failure.violations.isNotEmpty())
            assertNull(stored(fresh)); assertEquals(first.items.single(),stored(a)!!.receipt)
        }
    }
    @Test fun `gap fold half hour gap and skipped day use frozen P0 time vectors`() {
        for ((zone,date,time,expected) in listOf(
            listOf("America/New_York","2026-03-08","02:30","2026-03-08T07:30:00Z"),
            listOf("America/New_York","2026-11-01","01:30","2026-11-01T05:30:00Z"),
            listOf("Australia/Lord_Howe","2026-10-04","02:15","2026-10-03T15:45:00Z"),
            listOf("Pacific/Apia","2011-12-30","08:00","2011-12-30T18:00:00Z"))) {
            val plan=confirm(ReminderPlanDraft(listOf(time),date,zone,date)).plan
            val e=evidence(plan,date,time); assertEquals(Instant.parse(expected).toEpochMilli(),e.plannedAtEpochMillis)
            val result=accepted(register(e)).items.single().event
            assertEquals(e.plannedAtEpochMillis,result.plannedAtEpochMillis); assertEquals(e.resolvedOffsetSeconds,result.generationEvidence.resolvedOffsetSeconds)
            rejected(register(e.copy(generationId=key().toString(),plannedAtEpochMillis=e.plannedAtEpochMillis+3_600_000)))
        }
    }
    @ParameterizedTest @ValueSource(longs = [-1,0,7_199_999,7_200_000,7_200_001])
    fun `initial registration status respects planned instant and exact two hour cutoff`(offset: Long) {
        val e=evidence();clock.now.set(e.plannedAtEpochMillis+offset)
        val status=accepted(register(e)).items.single().event.status
        assertEquals(when { offset<0 -> DoseStatus.SCHEDULED;offset<7_200_000 -> DoseStatus.DUE;else -> DoseStatus.MISSED },status)
    }
    @Test fun `parallel retries and separate intents return exactly one canonical event`() {
        val e=evidence(); val op=key();val pool=Executors.newFixedThreadPool(8)
        try {
            val results=(1..8).map { pool.submit<OccurrenceRegistrationResponse> { accepted(register(e,operation=op)) } }.map { it.get(30,TimeUnit.SECONDS) }
            assertEquals(1,results.distinct().size)
            val more=(1..8).map { pool.submit<OccurrenceRegistrationResponse> { accepted(register(e)) } }.map { it.get(30,TimeUnit.SECONDS) }
            assertEquals(results.first(),more.first());assertEquals(1,more.map { it.items.single().event.id }.distinct().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun `same nominal tuple different retained revision does not create a second event`() {
        val old=evidence();val first=accepted(register(old))
        val edited=assertInstanceOf(PlanMutationResult.Accepted::class.java,ReminderPlanMutationService(store,clock).update(caregiver,
            original.plan.id,key(),UpdateReminderPlanRequest(1,ReminderPlanDraft(listOf("08:00"),"2026-01-01","Asia/Shanghai"),
                TimeZoneAcknowledgement("Asia/Shanghai",true),MedicationText("later fixture","later fixture")))).view
        rejected(register(evidence(edited.plan)))
        assertEquals(first.items.single(),stored(old)!!.receipt)
    }
    @Test fun `rejected original intent may explicitly retry after missing confirmed revision appears`() {
        val future=evidence().copy(planRevision=2);val op=key()
        rejected(register(future,operation=op));assertNull(stored(future))
        val plan=original.plan
        assertInstanceOf(PlanMutationResult.Accepted::class.java,ReminderPlanMutationService(store,clock).update(caregiver,
            plan.id,key(),UpdateReminderPlanRequest(1,ReminderPlanDraft(plan.localTimes,plan.startDate,plan.executionTimeZoneId,plan.endDate),
                TimeZoneAcknowledgement(plan.executionTimeZoneId,true))))
        val first=accepted(register(future,operation=op));assertEquals(2,first.items.single().event.planRevision)
        assertEquals(first,accepted(register(future,operation=op)))
    }
    @Test fun `exception after business and receipt writes rolls back complete transaction`() {
        val e=evidence();val op=key()
        val failing=object:OccurrenceRegistrationStore by store {
            override fun <T> registrationTransaction(work:(OccurrenceRegistrationTransaction)->T):T = store.registrationTransaction { tx ->
                work(tx);error("PRIVATE fixture failure after writes")
            }
        }
        rejected(service(failing).register(elder,elder.id,op,OccurrenceRegistrationRequest(listOf(e))),RegistrationRejection.SERVICE_UNAVAILABLE)
        assertNull(stored(e));assertNull(store.registrationTransaction { it.registrationOperation(RegistrationKey(elder.id,op)) })
        accepted(register(e,operation=op))
    }
    @Test fun `incomplete Store calls cannot commit an event without its operation receipt`() {
        val first=evidence();val template=accepted(register(first)).items.single()
        val other=evidence(date="2026-10-29");val origin=RegistrationKey(elder.id,key())
        val event=template.event.copy(id=key().toString(),occurrence=other.occurrence,generationEvidence=other,
            plannedAtEpochMillis=other.plannedAtEpochMillis,responseDeadlineEpochMillis=other.plannedAtEpochMillis+7_200_000)
        val raw=RegisteredOccurrence(elder.installationId,origin,OccurrenceRegistrationReceipt(other.generationId,clock.millis(),event))
        assertThrows(Exception::class.java) { store.registrationTransaction { it.insertOccurrence(raw) } }
        assertNull(stored(other));assertEquals(template,stored(first)!!.receipt)
    }
    @Test fun `returned lists detach input and cannot mutate accepted receipt`() {
        val e=evidence();val op=key();val items=mutableListOf(e)
        val first=accepted(service().register(elder,elder.id,op,OccurrenceRegistrationRequest(items)));items.clear()
        assertThrows(UnsupportedOperationException::class.java) { (first.items as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (first.items.single().event.lateTakenReports as MutableList).clear() }
        assertEquals(first,accepted(register(e,operation=op)))
        assertFalse(stored(e).toString().contains("虚构"));assertFalse(first.toString().contains("password"))
    }
}
internal class OccurrenceRegistrationTest : OccurrenceRegistrationContract()
internal class RegistrationClock : Clock() {
    val now=AtomicLong(Instant.parse("2026-10-27T00:00:00Z").toEpochMilli())
    override fun instant(): Instant = Instant.ofEpochMilli(now.get())
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(),zone)
}
