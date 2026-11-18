package com.group33.server.capability

import com.group33.server.auth.AnonymousUser
import com.group33.server.auth.CareRelationshipAccess
import com.group33.server.capability.CapabilityPostgresTestSupport.source
import com.group33.server.capability.CapabilityPostgresTestSupport.elder
import com.group33.server.capability.CapabilityPostgresTestSupport.report
import com.group33.server.capability.CapabilityPostgresTestSupport.owner
import com.group33.server.capability.CapabilityPostgresTestSupport.install
import com.group33.server.capability.CapabilityPostgresTestSupport.admin
import com.group33.server.capability.CapabilityPostgresTestSupport.columns
import com.group33.server.capability.CapabilityPostgresTestSupport.control
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Tag("postgres")
internal class PostgresCapabilityStoreTest {
    private val schema = "s2_capability_it_" + UUID.randomUUID().toString().replace("-", "")
    private val clock = CapabilityDatabaseClock()
    private val active = AtomicBoolean(false)
    private val caregiver = elder.copy(id="fictional-caregiver", installationId="fictional-caregiver-install", role=UserRole.CAREGIVER)
    private val access = CareRelationshipAccess { actor, target -> active.get() && actor == caregiver.id && target == elder.id }
    private fun store(wait: Long=10_000) = PostgresCapabilityStore(source(schema),wait)
    private fun service(metadata: CapabilityStore=store()) = CapabilityService(metadata,access,clock)
    private fun key(value: ReminderCapabilityReport) = CapabilityOperationKey(elder.id,UUID.fromString(value.reportId))
    private fun submit(value: ReminderCapabilityReport, actor: AnonymousUser=elder, operation: UUID=UUID.fromString(value.reportId),
        using: CapabilityService=service(), target: String=actor.id) = using.report(actor,target,operation,value)
    private fun accepted(result: CapabilityAcceptance) = assertInstanceOf(CapabilityAcceptance.Accepted::class.java,result).receipt
    private fun rejected(result: CapabilityAcceptance, reason: CapabilityRejection) { assertEquals(reason,assertInstanceOf(CapabilityAcceptance.Rejected::class.java,result).reason) }
    private fun current() = assertInstanceOf(CapabilityRead.Current::class.java,service().lastKnown(elder,elder)).view
    private fun sql(text: String) = source(schema).connection.use { c -> c.createStatement().use { it.execute(text) } }
    private fun scalar(text: String) = source(schema).connection.use { c -> columns(c,text).single() }
    private fun counts() = listOf("capability_observation","capability_current","capability_operation").map { scalar("SELECT count(*) FROM $it").toInt() }
    @BeforeEach fun prepare() { admin("CREATE SCHEMA $schema"); install(schema) }
    @AfterEach fun cleanup() { admin("DROP SCHEMA IF EXISTS $schema CASCADE") }

    @Test fun `first report atomically stores original evidence current and complete receipt across reconstructed adapters`() {
        assertNull(current()); val input=report(); val original=accepted(submit(input))
        assertTrue(original.applied); assertEquals(input.reasons,original.current.reasons)
        assertEquals(100L,original.current.observedAtEpochMillis); assertEquals(clock.millis(),original.receivedAtEpochMillis)
        assertEquals(listOf(1,1,1),counts())
        clock.now.addAndGet(50_000)
        assertEquals(original,accepted(submit(input))); assertEquals(original.current,current())
        store().transaction { tx -> assertEquals(input,tx.observation(owner(),1)); assertEquals(original,tx.operation(key(input))!!.receipt) }
    }
    @Test fun `lower delayed report acknowledges original arrival without replacing newest view and replay remains original`() {
        val high=accepted(submit(report(9))); clock.now.addAndGet(1_000)
        val low=report(1).copy(deliveryCondition=DeliveryCondition.UNAVAILABLE,notificationPermissionGranted=false)
        val receipt=accepted(submit(low)); assertFalse(receipt.applied); assertEquals(high.current,receipt.current)
        assertTrue(receipt.receivedAtEpochMillis>receipt.current.receivedAtEpochMillis)
        val newer=accepted(submit(report(10))); clock.now.addAndGet(100_000)
        assertEquals(receipt,accepted(submit(low))); assertEquals(newer.current,current()); assertEquals(listOf(3,1,3),counts())
    }
    @Test fun `higher sequence wins despite backward observed and receipt clocks`() {
        accepted(submit(report().copy(observedAtEpochMillis=900_000)))
        clock.now.addAndGet(-10_000)
        val newer=accepted(submit(report(2).copy(observedAtEpochMillis=1)))
        assertTrue(newer.applied); assertEquals(2L,current()!!.observationSequence); assertEquals(1L,current()!!.observedAtEpochMillis)
    }
    @ParameterizedTest @ValueSource(strings=["reportId","sequence","observedAt","delivery","permission","channel","alarm","reason-text","reason-order","installation"])
    fun `accepted operation binds every original field reason order and installation`(field: String) {
        val input=report(); val original=accepted(submit(input)); var actor=elder
        val changed=when(field) {
            "reportId"->input.copy(reportId=UUID.randomUUID().toString())
            "sequence"->input.copy(observationSequence=2)
            "observedAt"->input.copy(observedAtEpochMillis=101)
            "delivery"->input.copy(deliveryCondition=DeliveryCondition.DEGRADED)
            "permission"->input.copy(notificationPermissionGranted=false)
            "channel"->input.copy(channelEnabled=false)
            "alarm"->input.copy(exactAlarmAccess=ExactAlarmAccess.DENIED)
            "reason-text"->input.copy(reasons=listOf("keep spaces","fictional","fictional"))
            "reason-order"->input.copy(reasons=input.reasons.reversed())
            else->{ actor=elder.copy(installationId="another-installation"); input }
        }
        rejected(submit(changed,actor,UUID.fromString(input.reportId)),CapabilityRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(original,accepted(submit(input))); assertEquals(original.current,current()); assertEquals(listOf(1,1,1),counts())
    }
    @Test fun `same sequence different report identity conflicts and rejected binding remains durable`() {
        accepted(submit(report(5))); val conflict=report(5)
        rejected(submit(conflict),CapabilityRejection.CAPABILITY_SEQUENCE_CONFLICT)
        store().transaction { assertNull(it.operation(key(conflict))!!.receipt); assertEquals(conflict,it.operation(key(conflict))!!.binding.report) }
        rejected(submit(conflict.copy(observationSequence=6)),CapabilityRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(listOf(1,1,2),counts())
    }
    @ParameterizedTest @ValueSource(strings=["zero","negative","time","mismatch","not-uuid"])
    fun `invalid original reports retain rejected payload without observation current or accepted receipt`(kind:String) {
        val id=UUID.randomUUID();val good=report(id=id)
        val bad=when(kind) { "zero"->good.copy(observationSequence=0); "negative"->good.copy(observationSequence=-1)
            "time"->good.copy(observedAtEpochMillis=-1); "mismatch"->good.copy(reportId=UUID.randomUUID().toString()); else->good.copy(reportId="not-a-uuid") }
        rejected(submit(bad,operation=id),CapabilityRejection.INVALID_REPORT)
        assertEquals(listOf(0,0,1),counts()); assertNull(current())
        val saved=store().transaction { it.operation(CapabilityOperationKey(elder.id,id))!! }
        assertEquals(bad,saved.binding.report); assertNull(saved.receipt)
        rejected(submit(good),CapabilityRejection.IDEMPOTENCY_KEY_REUSED)
        rejected(submit(bad,operation=id),CapabilityRejection.INVALID_REPORT)
    }
    @Test fun `actor and owner scopes and role checks precede accepted receipt lookup`() {
        val value=report(); val first=accepted(submit(value))
        rejected(submit(value,actor=caregiver),CapabilityRejection.ROLE_NOT_ALLOWED)
        val other=elder.copy(id="other-fictional-elder",installationId="other-fictional-install")
        rejected(submit(value,actor=other,target=elder.id),CapabilityRejection.RESOURCE_NOT_ACCESSIBLE)
        val separate=accepted(submit(value,actor=other)); assertEquals(first.current,current())
        assertEquals(separate.current,assertInstanceOf(CapabilityRead.Current::class.java,service().lastKnown(other,other)).view)
        assertEquals(listOf(2,2,2),counts())
    }
    @Test fun `relationship seam is rechecked on each lastKnown read and elder retains own data`() {
        accepted(submit(report()));assertEquals(CapabilityRead.NotAccessible,service().lastKnown(caregiver,elder))
        active.set(true);assertEquals(current(),assertInstanceOf(CapabilityRead.Current::class.java,service().lastKnown(caregiver,elder)).view)
        active.set(false);assertEquals(CapabilityRead.NotAccessible,service().lastKnown(caregiver,elder));assertNotNull(current())
        // This is deliberately a controlled policy seam, not a real relationship transaction/HTTP claim.
    }
    @Test fun `input output and store-read list mutation never changes original durable evidence`() {
        val reasons=mutableListOf("原始虚构说明 ",""," 原始虚构说明 ");val input=report().copy(reasons=reasons)
        val first=accepted(submit(input));reasons.clear();(first.current.reasons as MutableList<String>).clear()
        val read=current()!!;(read.reasons as MutableList<String>).clear()
        store().transaction { (it.operation(key(input))!!.binding.report.reasons as MutableList<String>).clear() }
        val replay=accepted(submit(input.copy(reasons=listOf("原始虚构说明 ",""," 原始虚构说明 "))))
        assertEquals(listOf("原始虚构说明 ",""," 原始虚构说明 "),replay.current.reasons);assertEquals(replay.current,current())
    }
    private fun <T> parallel(count:Int,work:(Int)->T):List<T> {
        val pool=Executors.newFixedThreadPool(count);val start=CountDownLatch(1)
        try { val jobs=(0 until count).map { i->pool.submit<T> { start.await();work(i) } };start.countDown();return jobs.map { it.get(30,TimeUnit.SECONDS) } }
        finally { pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
    }
    @Test fun `eight independent adapter connections serialize one original operation to one effect`() {
        val value=report();val receipts=parallel(8) { accepted(submit(value)) }
        assertEquals(1,receipts.distinct().size);assertEquals(listOf(1,1,1),counts());assertEquals(receipts.first().current,current())
    }
    @Test fun `distinct concurrent sequences retain highest with all observation receipts`() {
        val results=parallel(8) { accepted(submit(report(it+1L))) }
        assertEquals(8,results.size);assertEquals(8L,current()!!.observationSequence);assertEquals(listOf(8,1,8),counts())
    }
    @Test fun `competing reports for same sequence have one winner with no overwritten evidence`() {
        val results=parallel(8) { submit(report(1)) }
        assertEquals(1,results.count { it is CapabilityAcceptance.Accepted })
        assertEquals(7,results.count { it==CapabilityAcceptance.Rejected(CapabilityRejection.CAPABILITY_SEQUENCE_CONFLICT) })
        assertEquals(listOf(1,1,8),counts())
    }
    @ParameterizedTest @ValueSource(strings=["capability_observation","capability_current","capability_operation","commit"])
    fun `actual SQL fault at every write and deferred commit rolls back evidence current and receipt`(point:String) {
        val old=accepted(submit(report(1)));val value=report(2);val table=if(point=="commit") "capability_operation" else point
        sql("CREATE FUNCTION fail_capability_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fictional SQL fault'; RETURN NEW; END $$;")
        val trigger=if(point=="commit") "CREATE CONSTRAINT TRIGGER cap_fail AFTER INSERT ON $table DEFERRABLE INITIALLY DEFERRED"
            else "CREATE TRIGGER cap_fail BEFORE INSERT OR UPDATE ON $table"
        sql("$trigger FOR EACH ROW EXECUTE FUNCTION fail_capability_write();")
        assertThrows(SQLException::class.java) { submit(value) }
        assertEquals(old.current,current());assertEquals(listOf(1,1,1),counts())
        store().transaction { assertNull(it.observation(owner(),2));assertNull(it.operation(key(value))) }
        sql("DROP TRIGGER cap_fail ON $table; DROP FUNCTION fail_capability_write();")
        val receipt=accepted(submit(value));assertTrue(receipt.applied);assertEquals(listOf(2,1,2),counts());assertEquals(receipt,accepted(submit(value)))
    }
    @Test fun `partial Store calls cannot commit orphan observation or current without accepted receipt`() {
        val input=report();assertThrows(SQLException::class.java) { store().transaction { it.putObservation(owner(),input) } }
        assertEquals(listOf(0,0,0),counts())
        val view=ReminderCapabilityView(input.reportId,1,100,input.deliveryCondition,true,true,input.exactAlarmAccess,input.reasons,clock.millis())
        assertThrows(SQLException::class.java) { store().transaction { it.putObservation(owner(),input);it.putCurrent(owner(),view) } }
        assertEquals(listOf(0,0,0),counts());accepted(submit(input))
    }
    @Test fun `database and adapter reject original receipt replacement observation rewrite and sequence regression`() {
        val input=report();val receipt=accepted(submit(input));accepted(submit(report(2)))
        val before=counts()
        assertThrows(SQLException::class.java) { sql("UPDATE capability_observation SET report_payload=jsonb_set(report_payload,'{reasons}','[]')") }
        assertThrows(SQLException::class.java) { sql("UPDATE capability_operation SET receipt_payload=jsonb_set(receipt_payload,'{receivedAtEpochMillis}','1')") }
        assertThrows(SQLException::class.java) { sql("UPDATE capability_current SET observation_sequence=1") }
        assertThrows(IllegalStateException::class.java) { store().transaction { tx->val op=tx.operation(key(input))!!;tx.putOperation(key(input),CapabilityOperation(op.binding,rejection=CapabilityRejection.INVALID_REPORT)) } }
        assertEquals(before,counts());assertEquals(receipt,accepted(submit(input)));assertEquals(2L,current()!!.observationSequence)
    }
    @Test fun `bounded row-lock wait reports unknown completion without adding observations or receipts`() {
        source(schema).connection.use { blocker->
            blocker.autoCommit=false;blocker.createStatement().use { it.executeQuery("SELECT id FROM capability_guard WHERE id=1 FOR UPDATE").close() }
            val input=report()
            try {
                assertEquals(CapabilityAcceptance.InProgress,submit(input,using=service(store(0))))
                assertEquals(CapabilityRead.InProgress,service(store(0)).lastKnown(elder,elder))
            } finally { blocker.rollback() }
            assertEquals(listOf(0,0,0),counts());accepted(submit(input))
        }
    }
    @Test fun `schema reapplication is nondestructive and independent environment has separate operation identities`() {
        val input=report();val original=accepted(submit(input));install(schema);assertEquals(original,accepted(submit(input)))
        val separate="s2_capability_it_"+UUID.randomUUID().toString().replace("-","")
        admin("CREATE SCHEMA $separate")
        try { install(separate);val other=accepted(submit(input.copy(reasons=listOf("other environment")),using=service(PostgresCapabilityStore(source(separate)))))
            assertEquals(listOf("other environment"),other.current.reasons);assertEquals(original,accepted(submit(input))) }
        finally { admin("DROP SCHEMA $separate CASCADE") }
    }
    private fun process(mode:String,input:ReminderCapabilityReport,now:Long):String {
        val child=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("s2.postgres.test.classpath"),
            "com.group33.server.capability.CapabilityPostgresProcessProbe",schema,mode,input.reportId,input.observationSequence.toString(),now.toString()).redirectErrorStream(true).start()
        try { assertTrue(child.waitFor(15,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),"Separate JVM failed; private output omitted");return child.inputStream.bufferedReader().readText().trim() }
        finally { if(child.isAlive) child.destroyForcibly() }
    }
    @Test fun `original receipt survives independent issuing and replay JVM exits after newer report`() {
        val old=report(1);val original=process("submit",old,clock.millis())
        process("submit",report(2),clock.millis()+10_000)
        assertEquals(original,process("replay",old,clock.millis()+90_000));assertEquals(2L,current()!!.observationSequence);assertEquals(listOf(2,1,2),counts())
    }
    @Test fun `actual database stop and restart preserves original receipts lower reports and newest current`() {
        val old=report();val original=accepted(submit(old));val high=accepted(submit(report(3)));val low=report(2);val delayed=accepted(submit(low))
        try { control("stop");assertThrows(SQLException::class.java) { current() } } finally { control("start") }
        clock.now.addAndGet(900_000)
        assertEquals(original,accepted(submit(old)));assertEquals(delayed,accepted(submit(low)));assertEquals(high.current,current())
        assertEquals(listOf(3,1,3),counts())
    }
    @Test fun `actual current UPDATE fault rolls back newly inserted higher observation and receipt`() {
        val old=accepted(submit(report()));val higher=report(2)
        sql("CREATE FUNCTION fail_current_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fictional update fault'; RETURN NEW; END $$; " +
            "CREATE TRIGGER cap_fail_update BEFORE UPDATE ON capability_current FOR EACH ROW EXECUTE FUNCTION fail_current_update();")
        assertThrows(SQLException::class.java) { submit(higher) };assertEquals(old.current,current());assertEquals(listOf(1,1,1),counts())
        sql("DROP TRIGGER cap_fail_update ON capability_current; DROP FUNCTION fail_current_update();")
        accepted(submit(higher));assertEquals(listOf(2,1,2),counts())
    }
    @Test fun `failed delayed acknowledgment leaves highest current and no accepted lower observation`() {
        val high=accepted(submit(report(9)));val delayed=report(1)
        sql("CREATE FUNCTION fail_delayed_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fictional delayed fault'; RETURN NEW; END $$; " +
            "CREATE TRIGGER cap_fail_delayed BEFORE INSERT ON capability_operation FOR EACH ROW EXECUTE FUNCTION fail_delayed_receipt();")
        assertThrows(SQLException::class.java) { submit(delayed) };assertEquals(high.current,current());assertEquals(listOf(1,1,1),counts())
        sql("DROP TRIGGER cap_fail_delayed ON capability_operation; DROP FUNCTION fail_delayed_receipt();")
        val receipt=accepted(submit(delayed));assertFalse(receipt.applied);assertEquals(high.current,receipt.current);assertEquals(listOf(2,1,2),counts())
    }

    @Test fun `missing guard fails closed without creating schema or falling back to memory`() {
        sql("DELETE FROM capability_guard")
        assertThrows(IllegalStateException::class.java) { submit(report()) }
        assertEquals(listOf(0,0,0),counts());assertEquals("0",scalar("SELECT count(*) FROM capability_guard"))
        assertEquals("PostgresCapabilityStore(REDACTED)",store().toString())
    }
}
