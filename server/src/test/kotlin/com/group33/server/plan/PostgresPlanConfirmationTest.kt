package com.group33.server.plan

import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.server.photo.*
import com.group33.server.photo.storage.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
class PostgresPlanConfirmationTest {
    @TempDir lateinit var temporary: Path
    private val schema = "s2_plan_it_" + UUID.randomUUID().toString().replace("-", "")
    private val elder = AnonymousUser("fictional-plan-elder", "fictional-plan-elder-installation", "虚构长者", UserRole.ELDER)
    private val caregiver = AnonymousUser("fictional-plan-caregiver", "fictional-plan-caregiver-installation", "虚构照护者", UserRole.CAREGIVER)
    private val stranger = caregiver.copy(id = "fictional-other-caregiver", installationId = "fictional-other-installation")
    private val otherElder = elder.copy(id = "fictional-other-elder", installationId = "fictional-other-elder-installation")
    private val clock = PlanClock(); private val sequence = AtomicInteger(1000)
    private lateinit var source: PGSimpleDataSource; private lateinit var cipherKey: ByteArray
    private val root get() = temporary.resolve("objects")
    private fun operation() = UUID.randomUUID()
    private fun objects() = LocalFilePhotoObjectStore(root)
    private fun store(wait: Long = 10_000) = PostgresPairingCodeStore(dataSource(schema), PairingReceiptCipher(cipherKey), wait)
    private fun service(metadata: PlanConfirmationStore = store(), storage: PhotoObjectStore = objects()) = ReminderPlanConfirmationService(metadata, storage, clock)
    private val scripts = listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql",
        "pairing-directory-store-v4.sql", "anonymous-identity-store-v5.sql", "medication-photo-store-v6.sql", "plan-confirmation-store-v7.sql")

    @BeforeEach fun prepare() {
        cipherKey = Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE"))); source = dataSource(schema)
        dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        install(); objects()
    }
    @AfterEach fun cleanup() {
        dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }
    private fun install(names: List<String> = scripts) {
        source.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                // v7 contains dollar-quoted SQL/DO blocks. Execute full scripts through the real PG driver,
                // never split on semicolons inside a function body.
                for (name in names) statement.execute(checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText())
            }
            connection.commit()
        }
    }
    private fun sql(text: String) = source.connection.use { c -> c.createStatement().use { it.execute(text) } }
    private fun scalar(text: String) = source.connection.use { c -> c.createStatement().use { it.executeQuery(text).use { r -> check(r.next()); r.getString(1) } } }
    private fun counts() = listOf("medication", "reminder_plan", "reminder_plan_revision", "plan_confirmation_operation")
        .map { scalar("SELECT count(*) FROM $it").toInt() }
    private fun pair(c: AnonymousUser = caregiver): CareRelationshipView {
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store(), clock) { sequence.incrementAndGet().toString().padStart(8, '0') }.issue(elder, operation())).view.code
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java,
            PairingRequestService(store(), clock).request(c, operation(), RequestPairingRequest(code), InetAddress.getByName("192.0.2.30"))).view
        return assertInstanceOf(PairingDecisionResult.Relationship::class.java, PairingDecisionService(store(), clock).accept(elder, pending.id, operation())).view
    }
    private fun photo(who: AnonymousUser = elder): MedicationPhotoView {
        val bytes = ByteArrayOutputStream().use { out -> ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray() }
        return assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            MedicationPhotoService(store(), objects(), clock).upload(who, who.id, operation(), bytes, "image/png")).view
    }
    private fun input(id: String = photo().id) = ConfirmReminderPlanRequest(id, MedicationText("虚构药品", "虚构用量", " 保留说明 "),
        ReminderPlanDraft(listOf("20:00", "08:00"), "2026-10-27", "Asia/Shanghai"),
        TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai"))
    private fun confirm(input: ConfirmReminderPlanRequest, id: UUID = operation(), who: AnonymousUser = caregiver) = service().confirm(who, elder.id, id, input)
    private fun accepted(result: PlanConfirmationResult) = assertInstanceOf(PlanConfirmationResult.Confirmed::class.java, result).view
    private fun rejected(result: PlanConfirmationResult, code: PlanConfirmationRejection): PlanConfirmationResult.Rejected {
        val value = assertInstanceOf(PlanConfirmationResult.Rejected::class.java, result); assertEquals(code, value.reason); return value
    }
    private fun record(id: UUID) = store().confirmationTransaction { checkNotNull(it.confirmationOperation(PlanConfirmationKey(caregiver.id, id))) }

    @Test fun `real current relationship and ready object commit all four resource records and restore from new store`() {
        pair(); val request = input(); val id = operation(); val original = accepted(confirm(request, id))
        assertEquals(listOf(1,1,1,1), counts()); assertEquals(original, record(id).receipt)
        clock.now.addAndGet(Duration.ofDays(31).toMillis())
        assertEquals(original, accepted(confirm(request.copy(plan = request.plan.copy(localTimes = request.plan.localTimes.reversed())), id)))
        store().confirmationTransaction { tx ->
            assertEquals(listOf(original.medication), tx.medications(elder.id)); assertEquals(listOf(original.plan), tx.plans(elder.id))
            assertEquals(listOf(PlanConfirmationCopies.initialRevision(original)), tx.revisions(elder.id))
        }
        assertEquals(listOf(1,1,1,1), counts())
    }

    @ParameterizedTest @ValueSource(strings = ["medication", "reminder_plan", "reminder_plan_revision", "plan_confirmation_operation"])
    fun `real immediate SQL fault at every write rolls back all resources and receipt`(table: String) {
        pair(); val request = input(); val id = operation()
        sql("CREATE FUNCTION fail_plan_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture write fault'; RETURN NEW; END $$; " +
            "CREATE TRIGGER fail_write BEFORE INSERT ON $table FOR EACH ROW EXECUTE FUNCTION fail_plan_write();")
        rejected(confirm(request,id), PlanConfirmationRejection.SERVICE_UNAVAILABLE)
        assertEquals(listOf(0,0,0,0), counts()); assertEquals("1", scalar("SELECT count(*) FROM medication_photo"))
        sql("DROP TRIGGER fail_write ON $table; DROP FUNCTION fail_plan_write();")
        accepted(confirm(request,id)); assertEquals(listOf(1,1,1,1), counts())
    }

    @Test fun `real deferred commit failure rolls back all four writes then original input can be accepted`() {
        pair(); val request=input();val id=operation()
        sql("CREATE FUNCTION fail_plan_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture commit fault'; RETURN NEW; END $$; " +
            "CREATE CONSTRAINT TRIGGER fail_commit AFTER INSERT ON plan_confirmation_operation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_plan_commit();")
        rejected(confirm(request,id),PlanConfirmationRejection.SERVICE_UNAVAILABLE);assertEquals(listOf(0,0,0,0),counts())
        sql("DROP TRIGGER fail_commit ON plan_confirmation_operation; DROP FUNCTION fail_plan_commit();")
        accepted(confirm(request,id));assertEquals(listOf(1,1,1,1),counts())
    }

    @Test fun `database deferred reverse references reject a partial low level publication at commit`() {
        pair(); val result=accepted(confirm(input())); val id=operation()
        assertThrows(SQLException::class.java) { store().confirmationTransaction { tx -> tx.insertMedication(result.medication.copy(id=id.toString())) } }
        assertEquals(listOf(1,1,1,1),counts())
        assertThrows(SQLException::class.java) { sql("INSERT INTO medication (id,elder_id,display_name,dosage_text,instructions,photo_id,active,initial_receipt_id) " +
            "SELECT '$id',elder_id,display_name,dosage_text,instructions,photo_id,active,'${operation()}' FROM medication LIMIT 1") }
        assertEquals(listOf(1,1,1,1),counts())
    }

    @Test fun `accepted receipt keeps its original snapshot after a later current row and revision change`() {
        pair();val request=input();val id=operation();val original=accepted(confirm(request,id))
        source.connection.use { connection ->
            connection.autoCommit=false; connection.createStatement().use { s ->
                s.execute("INSERT INTO reminder_plan_revision SELECT plan_id,2,elder_id,medication_id,ARRAY['09:00'],start_date,end_date,execution_time_zone_id,active,effective_from,confirmed_at,'新文本',dosage_text,instructions FROM reminder_plan_revision WHERE revision=1")
                s.execute("UPDATE medication SET display_name='新文本'; UPDATE reminder_plan SET revision=2,local_times=ARRAY['09:00']")
            };connection.commit()
        }
        val current=store().confirmationTransaction { it.plans(elder.id).single() }
        assertEquals(2L,current.revision);assertEquals(listOf("09:00"),current.localTimes)
        assertEquals(original,accepted(confirm(request,id)));assertEquals(original,record(id).receipt)
        val revisions=store().confirmationTransaction { it.revisions(elder.id) }
        assertEquals(2,revisions.size);assertEquals(PlanConfirmationCopies.initialRevision(original),revisions.first())
    }

    @Test fun `many independent connections with one operation converge on one result`() {
        pair();val request=input();val id=operation();val executor=Executors.newFixedThreadPool(8);val go=CountDownLatch(1)
        try {
            val futures=(1..8).map { executor.submit(Callable { go.await();accepted(confirm(request,id)) }) }
            go.countDown();val values=futures.map { it.get(20,TimeUnit.SECONDS) }
            assertTrue(values.all { it==values.first() });assertEquals(listOf(1,1,1,1),counts())
        } finally { executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test fun `different payloads on one key produce one winner and one conflict across connections`() {
        pair();val request=input();val id=operation();val executor=Executors.newFixedThreadPool(2);val go=CountDownLatch(1)
        try {
            val futures=listOf(request,request.copy(medication=request.medication.copy(displayName="新意图"))).map { body ->
                executor.submit(Callable { go.await();confirm(body,id) }) }
            go.countDown();val results=futures.map { it.get(20,TimeUnit.SECONDS) }
            assertEquals(1,results.count { it is PlanConfirmationResult.Confirmed })
            assertEquals(1,results.count { it==PlanConfirmationResult.Rejected(PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED) })
            assertEquals(listOf(1,1,1,1),counts())
        } finally { executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test fun `rejected input and exact optional text binding survive store reconstruction`() {
        pair();val good=input();val id=operation();val invalid=good.copy(medication=good.medication.copy(displayName=" \t"))
        val failure=rejected(confirm(invalid,id),PlanConfirmationRejection.VALIDATION_FAILED)
        assertEquals(failure,record(id).rejection);assertEquals(failure,confirm(invalid,id))
        rejected(confirm(good,id),PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(listOf(0,0,0,1),counts())
        for (instructions in listOf(null,""," \t "," e\u0301 原文本 ")) {
            val result=accepted(confirm(good.copy(medication=good.medication.copy(instructions=instructions))))
            assertEquals(instructions,result.medication.instructions)
            assertEquals(instructions,store().confirmationTransaction { it.revisions(elder.id).single { r -> r.id==result.plan.id }.medicationSnapshot.instructions })
        }
        assertEquals(listOf(4,4,4,5),counts())
    }

    @Test fun `missing private object error retains original request and is re-evaluated after repair`() {
        pair();val request=input();val id=operation();val key=store().photoTransaction { it.readyPhoto(request.photoId)!!.objectKey };val bytes=objects().read(key)
        Files.delete(root.resolve(request.photoId+".blob"))
        rejected(confirm(request,id),PlanConfirmationRejection.SERVICE_UNAVAILABLE)
        assertEquals(PlanConfirmationRejection.SERVICE_UNAVAILABLE,record(id).rejection!!.reason)
        rejected(confirm(request.copy(medication=request.medication.copy(dosageText="新用量")),id),PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED)
        bytes.inputStream().use { objects().putIfAbsent(key,it) }
        accepted(confirm(request,id));assertEquals(listOf(1,1,1,1),counts())
    }

    @Test fun `unrelated pending and revoked actors cannot read or replay accepted resources`() {
        val request=input();rejected(confirm(request),PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        val relation=pair();val id=operation();val original=accepted(confirm(request,id))
        rejected(confirm(request,id,stranger),PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejected(service().plans(stranger,elder.id),PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        PairingDecisionService(store(),clock).revoke(elder,relation.id,operation())
        rejected(confirm(request,id),PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejected(confirm(request.copy(photoId=operation().toString()),id),PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(listOf(original.medication),assertInstanceOf(PlanConfirmationResult.Medications::class.java,service().medications(elder,elder.id)).items)
        pair(stranger)
        assertEquals(listOf(original.plan),assertInstanceOf(PlanConfirmationResult.Plans::class.java,service().plans(stranger,elder.id)).value.items)
        rejected(confirm(request,id),PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
    }

    @Test fun `confirmation holds actual database guard and revoke waits until its commit`() {
        val relation=pair();val request=input();val id=operation();val entered=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        val real=objects();val blocking=object:PhotoObjectStore by real { override fun read(key:PhotoObjectKey):ByteArray {
            entered.countDown();check(release.await(5,TimeUnit.SECONDS));return real.read(key)
        } }
        try {
            val save=executor.submit(Callable { service(storage=blocking).confirm(caregiver,elder.id,id,request) });assertTrue(entered.await(5,TimeUnit.SECONDS))
            val revoke=executor.submit(Callable { PairingDecisionService(store(),clock).revoke(elder,relation.id,operation()) })
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='$schema' AND wait_event_type='Lock'")=="0") {
                assertTrue(System.nanoTime()<deadline,"Revocation must wait on actual DB lock");Thread.yield()
            }
            release.countDown();accepted(save.get(8,TimeUnit.SECONDS));assertInstanceOf(PairingDecisionResult.Relationship::class.java,revoke.get(8,TimeUnit.SECONDS))
            rejected(confirm(request,id),PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE);assertEquals(listOf(1,1,1,1),counts())
        } finally { release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test fun `bounded database lock contention returns operation in progress and original key can retry`() {
        pair();val request=input();val id=operation()
        source.connection.use { connection ->
            connection.autoCommit=false;connection.createStatement().use { it.executeQuery("SELECT id FROM pairing_issuance_guard WHERE id=1 FOR UPDATE").close() }
            rejected(service(store(0)).confirm(caregiver,elder.id,id,request),PlanConfirmationRejection.OPERATION_IN_PROGRESS)
            connection.rollback()
        }
        accepted(confirm(request,id));assertEquals(listOf(1,1,1,1),counts())
    }

    @ParameterizedTest @ValueSource(strings=["installation","fingerprint","target","receipt","downgrade"])
    fun `adapter cannot replace accepted binding or original receipt`(kind:String) {
        pair();val request=input();val id=operation();val accepted=accepted(confirm(request,id));val old=record(id);val key=PlanConfirmationKey(caregiver.id,id)
        val bad=when(kind) {
            "installation"->PlanConfirmationOperation("other",elder.id,old.fingerprint,old.receipt)
            "fingerprint"->PlanConfirmationOperation(old.installationId,elder.id,"f".repeat(64),old.receipt)
            "target"->PlanConfirmationOperation(old.installationId,otherElder.id,old.fingerprint,old.receipt)
            "receipt"->PlanConfirmationOperation(old.installationId,elder.id,old.fingerprint,accepted.copy(medication=accepted.medication.copy(instructions="改写")))
            else->PlanConfirmationOperation(old.installationId,elder.id,old.fingerprint,rejection=PlanConfirmationResult.Rejected(PlanConfirmationRejection.VALIDATION_FAILED))
        }
        assertThrows(IllegalStateException::class.java) { store().confirmationTransaction { it.putConfirmationOperation(key,bad) } }
        assertEquals(accepted,record(id).receipt);assertEquals(listOf(1,1,1,1),counts())
    }

    @Test fun `SQL constraints reject invalid receipt shape wrong relationship malformed times and missing current revision`() {
        pair();val original=accepted(confirm(input()));val before=counts()
        val statements=listOf(
            "UPDATE plan_confirmation_operation SET receipt_payload=NULL",
            "UPDATE plan_confirmation_operation SET receipt_revision=2",
            "UPDATE plan_confirmation_operation SET receipt_payload=jsonb_set(receipt_payload,'{plan,medicationId}',to_jsonb('${operation()}'::text))",
            "UPDATE medication SET elder_id='unrelated-elder'",
            "UPDATE reminder_plan SET local_times=ARRAY['08:00','08:00']",
            "UPDATE reminder_plan SET local_times=ARRAY['24:00']",
            "UPDATE reminder_plan SET local_times=ARRAY[NULL]::text[]",
            "UPDATE reminder_plan SET local_times=ARRAY[]::text[]",
            "UPDATE reminder_plan SET revision=2",
            "DELETE FROM reminder_plan_revision WHERE plan_id='${original.plan.id}'",
            "DELETE FROM plan_confirmation_operation")
        for (statement in statements) { assertThrows(SQLException::class.java) { sql(statement) };assertEquals(before,counts()) }
    }

    @Test fun `first plan with proleptic year zero survives DB text roundtrip without date reinterpretation`() {
        pair();val body=input().copy(plan=ReminderPlanDraft(listOf("08:00"),"0000-01-01","Etc/UTC","0000-01-01"),timeZoneAcknowledgement=TimeZoneAcknowledgement("Etc/UTC",true))
        val id=operation();val value=accepted(confirm(body,id));assertEquals("0000-01-01",value.plan.startDate)
        assertEquals(value,accepted(confirm(body,id)));assertEquals(value.plan,store().confirmationTransaction { it.plans(elder.id).single() })
    }

    @Test fun `DST collision and foreign photo rejection persist without publishing a partial plan`() {
        pair();val good=input();val bad=good.copy(plan=ReminderPlanDraft(listOf("02:30","03:30"),"2026-03-09","America/New_York"),timeZoneAcknowledgement=TimeZoneAcknowledgement("America/New_York",true))
        val id=operation();val failure=rejected(confirm(bad,id),PlanConfirmationRejection.OVERLAPPING_PLAN)
        assertEquals(failure,record(id).rejection);rejected(confirm(good,id),PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED)
        val foreign=photo(otherElder);rejected(confirm(good.copy(photoId=foreign.id)),PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(listOf(0,0,0,2),counts())
    }

    @Test fun `accepted data and receipt survive actual PostgreSQL stop and start`() {
        pair();val request=input();val id=operation();val original=accepted(confirm(request,id))
        try { control("stop") } finally { control("start") }
        clock.now.addAndGet(Duration.ofDays(100).toMillis())
        assertEquals(original,accepted(confirm(request,id)));assertEquals(listOf(1,1,1,1),counts())
        assertEquals(original,record(id).receipt)
    }

    @Test fun `independent writer and reader JVMs recover the same confirmation`() {
        pair();val request=input();val id=operation()
        fun run(mode:String) {
            val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("s2.postgres.test.classpath"),
                PlanConfirmationPostgresProcessProbe::class.java.name,mode,schema,root.toString(),id.toString(),request.photoId).redirectErrorStream(true).start()
            try { assertTrue(process.waitFor(30,TimeUnit.SECONDS));val output=process.inputStream.bufferedReader().readText();assertEquals(0,process.exitValue(),output);assertTrue(output.contains("verified")) }
            finally { if(process.isAlive)process.destroyForcibly() }
        }
        run("write");val original=record(id).receipt!!;run("read");assertEquals(original,record(id).receipt);assertEquals(listOf(1,1,1,1),counts())
    }

    @Test fun `additive schema upgrades existing photos without changing v1 to v6 or erasing accepted plans`() {
        // Recreate the same private test schema at v6 to model an existing photo installation.
        source.connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA $schema CASCADE; CREATE SCHEMA $schema") } }
        install(scripts.dropLast(1));pair();val request=input();assertEquals("1",scalar("SELECT count(*) FROM medication_photo"))
        install(listOf(scripts.last()));val id=operation();val original=accepted(confirm(request,id));install()
        assertEquals(original,accepted(confirm(request,id)));assertEquals(listOf(1,1,1,1),counts())
    }

    private fun control(action:String) {
        val process=ProcessBuilder(required("S2_PG_PYTHON"),required("S2_PG_MANAGER_SCRIPT"),action).redirectErrorStream(true).start()
        try { assertTrue(process.waitFor(40,TimeUnit.SECONDS));assertEquals(0,process.exitValue()) } finally { if(process.isAlive)process.destroyForcibly() }
    }
    private class PlanClock:Clock() {
        val now=AtomicLong(Instant.parse("2026-10-27T00:00:00Z").toEpochMilli());override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this;override fun instant():Instant=Instant.ofEpochMilli(now.get())
    }
}
