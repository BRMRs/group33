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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ReminderPlanConfirmationServiceTest {
    @TempDir lateinit var temporary: Path
    private val elder = AnonymousUser("elder-plan", "installation-e-plan", "虚构长者", UserRole.ELDER)
    private val caregiver = AnonymousUser("caregiver-plan", "installation-c-plan", "虚构照护者", UserRole.CAREGIVER)
    private val stranger = caregiver.copy(id = "other-caregiver", installationId = "other-caregiver-installation")
    private val otherElder = elder.copy(id = "other-elder", installationId = "other-elder-installation")
    private val clock = PlanClock()
    private val store = InMemoryPairingCodeStore(5_000)
    private val counter = AtomicInteger(123456)
    private val objects: PhotoObjectStore get() = LocalFilePhotoObjectStore(temporary.resolve("objects"))
    private fun key() = UUID.randomUUID()
    private fun service(metadata: PlanConfirmationStore = store, storage: PhotoObjectStore = objects) =
        ReminderPlanConfirmationService(metadata, storage, clock)
    private fun photo(who: AnonymousUser = elder): MedicationPhotoView {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { output -> assertTrue(ImageIO.write(image, "png", output)); output.toByteArray() }
        val result = MedicationPhotoService(store, objects, clock).upload(who, who.id, key(), bytes, "image/png")
        return assertInstanceOf(PhotoBusinessResult.Uploaded::class.java, result).view
    }
    private fun input(id: String = photo().id) = ConfirmReminderPlanRequest(id, MedicationText("虚构药品", "虚构用量", "原说明"),
        ReminderPlanDraft(listOf("20:00", "08:00"), "2026-10-27", "Asia/Shanghai"),
        TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai"))
    private fun pair(c: AnonymousUser = caregiver): CareRelationshipView {
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store, clock) { counter.incrementAndGet().toString().padStart(8, '0') }.issue(elder, key())).view.code
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java,
            PairingRequestService(store, clock).request(c, key(), RequestPairingRequest(code), InetAddress.getByName("192.0.2.23"))).view
        return assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(store, clock).accept(elder, pending.id, key())).view
    }
    private fun confirmed(value: PlanConfirmationResult) = assertInstanceOf(PlanConfirmationResult.Confirmed::class.java, value).view
    private fun rejection(value: PlanConfirmationResult, reason: PlanConfirmationRejection): PlanConfirmationResult.Rejected {
        val result = assertInstanceOf(PlanConfirmationResult.Rejected::class.java, value); assertEquals(reason, result.reason); return result
    }
    private fun sizes() = store.confirmationTransaction { listOf(it.medications(elder.id).size, it.plans(elder.id).size, it.revisions(elder.id).size) }
    private fun confirm(request: ConfirmReminderPlanRequest, operation: UUID = key(), actor: AnonymousUser = caregiver, target: String = elder.id) =
        service().confirm(actor, target, operation, request)

    @Test fun `real accepted pairing and ready photo atomically create Medication plan revision and receipt`() {
        pair(); val request = input(); val operation = key(); val value = confirmed(confirm(request, operation))
        assertNotEquals(value.medication.id, value.plan.id); UUID.fromString(value.medication.id); UUID.fromString(value.plan.id)
        assertEquals(request.photoId, value.medication.photoId); assertEquals(elder.id, value.medication.elderId)
        assertEquals(request.medication.displayName, value.medication.displayName); assertEquals(request.medication.dosageText, value.medication.dosageText)
        assertEquals(request.medication.instructions, value.medication.instructions); assertTrue(value.medication.active)
        assertEquals(value.medication.id, value.plan.medicationId); assertEquals(elder.id, value.plan.elderId)
        assertEquals(1L, value.plan.revision); assertTrue(value.plan.active); assertEquals(listOf("08:00", "20:00"), value.plan.localTimes)
        assertEquals(clock.millis(), value.plan.confirmedAtEpochMillis); assertEquals(clock.millis(), value.plan.effectiveFromEpochMillis)
        assertEquals(listOf(1,1,1), sizes())
        store.confirmationTransaction { tx ->
            assertEquals(PlanConfirmationCopies.initialRevision(value), tx.revisions(elder.id).single())
            assertEquals(value, tx.confirmationOperation(PlanConfirmationKey(caregiver.id, operation))!!.receipt)
        }
    }

    @Test fun `original success replays after clock advance and service reconstruction with reordered times`() {
        pair(); val request = input(); val operation = key(); val original = confirmed(confirm(request, operation))
        clock.now.addAndGet(Duration.ofDays(40).toMillis())
        val changedOrder = request.copy(plan = request.plan.copy(localTimes = request.plan.localTimes.reversed()))
        assertEquals(original, confirmed(service().confirm(caregiver, elder.id, operation, changedOrder)))
        assertEquals(listOf(1,1,1), sizes())
    }

    @Test fun `different medications may share the same name photo and reminder instant`() {
        pair(); val request = input(); val a = confirmed(confirm(request)); val b = confirmed(confirm(request))
        assertNotEquals(a.medication.id, b.medication.id); assertNotEquals(a.plan.id, b.plan.id)
        assertEquals(a.medication.displayName, b.medication.displayName); assertEquals(a.plan.localTimes, b.plan.localTimes)
        assertEquals(listOf(2,2,2), sizes())
    }

    @Test fun `unpaired and pending actors cannot create or inspect medical data`() {
        val request = input(); val operation = key()
        rejection(confirm(request, operation), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejection(service().medications(caregiver, elder.id), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store, clock).issue(elder, key())).view.code
        PairingRequestService(store, clock).request(caregiver, key(), RequestPairingRequest(code), InetAddress.getByName("192.0.2.9"))
        rejection(confirm(request, operation), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertNull(store.confirmationTransaction { it.confirmationOperation(PlanConfirmationKey(caregiver.id, operation)) })
        assertEquals(listOf(0,0,0), sizes())
    }

    @Test fun `self wrong role is forbidden while unrelated elder and caregiver resources are inaccessible`() {
        pair(); val request = input()
        rejection(confirm(request, actor = elder), PlanConfirmationRejection.ROLE_NOT_ALLOWED)
        rejection(confirm(request, actor = otherElder), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejection(confirm(request, actor = stranger), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejection(confirm(request, target = otherElder.id), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(listOf(0,0,0), sizes())
    }

    @Test fun `revocation is checked before receipt replay or exposing changed payload conflict`() {
        val relation = pair(); val request = input(); val operation = key(); confirmed(confirm(request, operation))
        PairingDecisionService(store, clock).revoke(elder, relation.id, key())
        rejection(confirm(request, operation), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejection(confirm(request.copy(medication = request.medication.copy(displayName = "修改内容")), operation), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        rejection(service().plans(caregiver, elder.id), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(1, assertInstanceOf(PlanConfirmationResult.Plans::class.java, service().plans(elder, elder.id)).value.items.size)
        assertEquals(1, assertInstanceOf(PlanConfirmationResult.Medications::class.java, service().medications(elder, elder.id)).items.size)
    }

    @Test fun `new caregiver cannot replay another caregiver receipt after re-pairing`() {
        val relation = pair(); val request = input(); val operation = key(); val old = confirmed(confirm(request, operation))
        PairingDecisionService(store, clock).revoke(elder, relation.id, key()); pair(stranger)
        val own = confirmed(confirm(request, operation, stranger))
        assertNotEquals(old.medication.id, own.medication.id)
        rejection(confirm(request, operation), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(2, assertInstanceOf(PlanConfirmationResult.Medications::class.java, service().medications(stranger, elder.id)).items.size)
    }

    @ParameterizedTest @ValueSource(strings = ["photo", "name", "dose", "instructions", "blank-instructions", "null-instructions", "zone-ack", "time", "duplicate-time", "start", "end", "installation", "target"])
    fun `accepted operation binding cannot silently change`(kind: String) {
        pair(); val request = input(); val operation = key(); confirmed(confirm(request, operation))
        var actor = caregiver; var target = elder.id
        val changed = when (kind) {
            "photo" -> request.copy(photoId = photo().id)
            "name" -> request.copy(medication = request.medication.copy(displayName = " " + request.medication.displayName))
            "dose" -> request.copy(medication = request.medication.copy(dosageText = "新用量"))
            "instructions" -> request.copy(medication = request.medication.copy(instructions = " 原说明"))
            "blank-instructions" -> request.copy(medication = request.medication.copy(instructions = " "))
            "null-instructions" -> request.copy(medication = request.medication.copy(instructions = null))
            "zone-ack" -> request.copy(timeZoneAcknowledgement = request.timeZoneAcknowledgement.copy(mismatchAcknowledged = true))
            "time" -> request.copy(plan = request.plan.copy(localTimes = listOf("09:00")))
            "duplicate-time" -> request.copy(plan = request.plan.copy(localTimes = listOf("08:00", "08:00", "20:00")))
            "start" -> request.copy(plan = request.plan.copy(startDate = "2026-10-28"))
            "end" -> request.copy(plan = request.plan.copy(endDate = "2026-12-31"))
            "installation" -> { actor = caregiver.copy(installationId = "new-installation"); request }
            "target" -> { target = otherElder.id; request }
            else -> error("test input")
        }
        rejection(confirm(changed, operation, actor, target), if (kind == "target") PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE else PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(listOf(1,1,1), sizes())
    }

    @Test fun `missing nonready and other Elder photos have indistinguishable inaccessible errors`() {
        pair(); val foreign = photo(otherElder)
        for (id in listOf(key().toString(), foreign.id)) {
            assertEquals(PlanConfirmationResult.Rejected(PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE), confirm(input(id)))
        }
        val reserved = key(); store.photoTransaction { tx -> tx.putPhotoOperation(PhotoUploadKey(elder.id, key()),
            PhotoUploadOperation(elder.installationId, elder.id, "f".repeat(64), reserved, PhotoUploadState.STAGING)) }
        rejection(confirm(input(reserved.toString())), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(listOf(0,0,0), sizes())
    }

    @ParameterizedTest @ValueSource(strings = ["photo", "name", "dose", "empty-times", "duplicate-times", "date", "range", "offset-zone", "ack-zone", "caregiver-zone", "observed-zone", "mismatch", "unknown-caregiver", "unknown-elder"])
    fun `invalid text schedule and timezone confirmation leave no partial resource and retain rejected binding`(kind: String) {
        pair(); val good = input(); val operation = key()
        val bad = when (kind) {
            "photo" -> good.copy(photoId = " ")
            "name" -> good.copy(medication = good.medication.copy(displayName = "\t "))
            "dose" -> good.copy(medication = good.medication.copy(dosageText = "\n"))
            "empty-times" -> good.copy(plan = good.plan.copy(localTimes = emptyList()))
            "duplicate-times" -> good.copy(plan = good.plan.copy(localTimes = listOf("08:00", "08:00")))
            "date" -> good.copy(plan = good.plan.copy(startDate = "2026-02-30"))
            "range" -> good.copy(plan = good.plan.copy(endDate = "2026-10-26"))
            "offset-zone" -> good.copy(plan = good.plan.copy(executionTimeZoneId = "+08:00"))
            "ack-zone" -> good.copy(timeZoneAcknowledgement = good.timeZoneAcknowledgement.copy(executionTimeZoneId = "Etc/UTC"))
            "caregiver-zone" -> good.copy(timeZoneAcknowledgement = good.timeZoneAcknowledgement.copy(caregiverTimeZoneId = "Bad/Zone", mismatchAcknowledged = true))
            "observed-zone" -> good.copy(timeZoneAcknowledgement = good.timeZoneAcknowledgement.copy(observedElderTimeZoneId = "", mismatchAcknowledged = true))
            "mismatch" -> good.copy(timeZoneAcknowledgement = good.timeZoneAcknowledgement.copy(caregiverTimeZoneId = "Europe/London"))
            "unknown-caregiver" -> good.copy(timeZoneAcknowledgement = good.timeZoneAcknowledgement.copy(caregiverTimeZoneId = null))
            "unknown-elder" -> good.copy(timeZoneAcknowledgement = good.timeZoneAcknowledgement.copy(observedElderTimeZoneId = null))
            else -> error("test input")
        }
        val failure = rejection(confirm(bad, operation), PlanConfirmationRejection.VALIDATION_FAILED)
        assertFalse(failure.violations.isEmpty()); assertEquals(listOf(0,0,0), sizes())
        assertEquals(failure, confirm(bad, operation))
        rejection(confirm(good, operation), PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(listOf(1,1,1), run { confirmed(confirm(good)); sizes() })
    }

    @Test fun `unknown or mismatched known contexts require explicit acknowledgement and resolved execution zone`() {
        pair(); val good = input()
        for (ack in listOf(good.timeZoneAcknowledgement.copy(caregiverTimeZoneId = null, observedElderTimeZoneId = null, mismatchAcknowledged = true),
            good.timeZoneAcknowledgement.copy(caregiverTimeZoneId = "Europe/London", mismatchAcknowledged = true))) {
            confirmed(confirm(good.copy(timeZoneAcknowledgement = ack)))
        }
        assertEquals(listOf(2,2,2), sizes())
    }

    @Test fun `medication text whitespace Unicode and null versus empty optional instructions remain exact`() {
        pair(); val base = input()
        for (instructions in listOf(null, "", " \t ", " 需要保留的说明\n")) {
            val text = MedicationText(" e\u0301 虚构药品 ", " 原始虚构用量 ", instructions)
            val view = confirmed(confirm(base.copy(medication = text)))
            assertEquals(text, PlanConfirmationCopies.initialRevision(view).medicationSnapshot)
        }
        assertEquals(listOf(4,4,4), sizes())
    }

    @Test fun `intraplan DST duplicates reject atomically including open end and date line skip`() {
        pair(); val good = input()
        for (draft in listOf(ReminderPlanDraft(listOf("02:30", "03:30"), "2026-03-09", "America/New_York"),
            ReminderPlanDraft(listOf("12:00"), "2011-12-30", "Pacific/Apia", "2011-12-31"))) {
            val failure = rejection(confirm(good.copy(plan = draft, timeZoneAcknowledgement = TimeZoneAcknowledgement(draft.executionTimeZoneId, true))), PlanConfirmationRejection.OVERLAPPING_PLAN)
            assertEquals(listOf(ConfirmationViolation("plan.localTimes", "DUPLICATE_RESOLVED_INSTANT")), failure.violations)
            assertEquals(listOf(0,0,0), sizes())
        }
    }

    @ParameterizedTest @ValueSource(strings = ["medication", "plan", "revision", "receipt"])
    fun `fault after each publication write rolls back the whole confirmation including its operation`(point: String) {
        pair(); val request = input(); val operation = key()
        val faulty = object : PlanConfirmationStore by store {
            override fun <T> confirmationTransaction(work: (PlanConfirmationTransaction) -> T): T = store.confirmationTransaction { tx ->
                work(object : PlanConfirmationTransaction by tx {
                    override fun insertMedication(value: MedicationView) { tx.insertMedication(value); if (point == "medication") error("fixture") }
                    override fun insertPlan(value: ReminderPlanView) { tx.insertPlan(value); if (point == "plan") error("fixture") }
                    override fun insertRevision(value: PlanRevisionView) { tx.insertRevision(value); if (point == "revision") error("fixture") }
                    override fun putConfirmationOperation(key: PlanConfirmationKey, value: PlanConfirmationOperation) {
                        tx.putConfirmationOperation(key, value); if (point == "receipt") error("fixture")
                    }
                })
            }
        }
        rejection(service(faulty).confirm(caregiver, elder.id, operation, request), PlanConfirmationRejection.SERVICE_UNAVAILABLE)
        assertEquals(listOf(0,0,0), sizes()); assertNull(store.confirmationTransaction { it.confirmationOperation(PlanConfirmationKey(caregiver.id, operation)) })
        assertEquals(1, assertInstanceOf(PhotoBusinessResult.Listed::class.java, MedicationPhotoService(store, objects, clock).list(elder, elder.id)).items.size)
        confirmed(confirm(request, operation)); assertEquals(listOf(1,1,1), sizes())
    }

    @ParameterizedTest @ValueSource(strings = ["missing", "corrupt"])
    fun `unavailable private object retains binding and original retry can succeed after repair`(kind: String) {
        pair(); val request = input(); val operation = key()
        val file = temporary.resolve("objects").resolve(request.photoId + ".blob")
        val objectKey = store.photoTransaction { it.readyPhoto(request.photoId)!!.objectKey }
        val source = objects.read(objectKey)
        if (kind == "missing") Files.delete(file) else {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            Files.write(file, ByteArray(source.size) { 1 })
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"))
        }
        rejection(confirm(request, operation), PlanConfirmationRejection.SERVICE_UNAVAILABLE)
        assertEquals(listOf(0,0,0), sizes())
        assertEquals(PlanConfirmationRejection.SERVICE_UNAVAILABLE,
            store.confirmationTransaction { it.confirmationOperation(PlanConfirmationKey(caregiver.id, operation))!!.rejection!!.reason })
        rejection(confirm(request.copy(medication = request.medication.copy(displayName = "换掉的输入")), operation), PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED)
        Files.deleteIfExists(file)
        source.inputStream().use { objects.putIfAbsent(objectKey, it) }
        confirmed(confirm(request, operation)); assertEquals(listOf(1,1,1), sizes())
    }

    @Test fun `many concurrent identical operations converge on one accepted result`() {
        pair(); val request = input(); val operation = key(); val executor = Executors.newFixedThreadPool(12); val go = CountDownLatch(1)
        try {
            val futures = (1..12).map { executor.submit(Callable { go.await(); confirmed(confirm(request, operation)) }) }
            go.countDown(); val views = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(views.all { it == views.first() }); assertEquals(listOf(1,1,1), sizes())
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `concurrent changed payload same key has one winner and one conflict`() {
        pair(); val request = input(); val operation = key(); val executor = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val futures = listOf(request, request.copy(medication = request.medication.copy(displayName = "另一个原始意图"))).map {
                executor.submit(Callable { go.await(); confirm(it, operation) })
            }
            go.countDown(); val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is PlanConfirmationResult.Confirmed })
            assertEquals(1, results.count { it == PlanConfirmationResult.Rejected(PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED) })
            assertEquals(listOf(1,1,1), sizes())
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `confirmation and revoke use the same guard so revoke after commit blocks future replay`() {
        val relation = pair(); val request = input(); val operation = key()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2); val real = objects
        val blocking = object : PhotoObjectStore by real { override fun read(key: PhotoObjectKey): ByteArray {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return real.read(key)
        } }
        try {
            val save = executor.submit(Callable { service(storage = blocking).confirm(caregiver, elder.id, operation, request) })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val revoke = executor.submit(Callable { PairingDecisionService(store, clock).revoke(elder, relation.id, key()) })
            assertThrows(TimeoutException::class.java) { revoke.get(100, TimeUnit.MILLISECONDS) }
            release.countDown(); confirmed(save.get(5, TimeUnit.SECONDS))
            assertInstanceOf(PairingDecisionResult.Relationship::class.java, revoke.get(5, TimeUnit.SECONDS))
            rejection(confirm(request, operation), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
            assertEquals(listOf(1,1,1), sizes())
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `revoke first prevents a new confirmation and leaves all medical resources absent`() {
        val relation = pair(); val request = input(); PairingDecisionService(store, clock).revoke(caregiver, relation.id, key())
        rejection(confirm(request), PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE); assertEquals(listOf(0,0,0), sizes())
    }

    @Test fun `caller list mutation cannot alter saved plan revision or original receipt`() {
        pair(); val mutable = mutableListOf("08:00", "20:00"); val good = input(); val request = good.copy(plan = good.plan.copy(localTimes = mutable))
        val operation = key(); val accepted = confirmed(confirm(request, operation)); mutable.clear()
        assertThrows(UnsupportedOperationException::class.java) { (accepted.plan.localTimes as MutableList<String>).clear() }
        val plan = assertInstanceOf(PlanConfirmationResult.Plans::class.java, service().plans(elder, elder.id)).value
        assertEquals(listOf("08:00", "20:00"), plan.items.single().localTimes)
        assertEquals(plan.items.single().localTimes, plan.planRevisions.single().localTimes)
        assertEquals(accepted, confirmed(confirm(good, operation)))
    }

    @Test fun `store refuses receipt rebinding accepted replacement and incomplete publication`() {
        pair(); val request = input(); val operation = key(); val accepted = confirmed(confirm(request, operation)); val key = PlanConfirmationKey(caregiver.id, operation)
        val old = store.confirmationTransaction { it.confirmationOperation(key)!! }
        for (changed in listOf(PlanConfirmationOperation("another-installation", elder.id, old.fingerprint, old.receipt),
            PlanConfirmationOperation(old.installationId, elder.id, "e".repeat(64), old.receipt),
            PlanConfirmationOperation(old.installationId, elder.id, old.fingerprint, rejection = PlanConfirmationResult.Rejected(PlanConfirmationRejection.VALIDATION_FAILED)))) {
            assertThrows(IllegalStateException::class.java) { store.confirmationTransaction { it.putConfirmationOperation(key, changed) } }
        }
        assertThrows(IllegalStateException::class.java) { store.confirmationTransaction { it.insertMedication(accepted.medication.copy(id = UUID.randomUUID().toString())) } }
        val another = confirmed(confirm(request))
        val mismatched = PlanConfirmationOperation(caregiver.installationId, elder.id, "c".repeat(64),
            ConfirmReminderPlanResponse(accepted.medication, another.plan))
        assertThrows(IllegalStateException::class.java) { store.confirmationTransaction {
            it.putConfirmationOperation(PlanConfirmationKey(caregiver.id, UUID.randomUUID()), mismatched)
        } }
        assertEquals(listOf(2,2,2), sizes())
    }

    @Test fun `busy and interrupted store failures have controlled results and preserve interrupt`() {
        pair(); val request = input()
        val busy = object : PlanConfirmationStore by store { override fun <T> confirmationTransaction(work: (PlanConfirmationTransaction) -> T): T = throw CodeStoreBusy() }
        rejection(service(busy).confirm(caregiver, elder.id, key(), request), PlanConfirmationRejection.OPERATION_IN_PROGRESS)
        val interrupted = object : PlanConfirmationStore by store { override fun <T> confirmationTransaction(work: (PlanConfirmationTransaction) -> T): T = throw InterruptedException() }
        try {
            rejection(service(interrupted).confirm(caregiver, elder.id, key(), request), PlanConfirmationRejection.SERVICE_UNAVAILABLE)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    private class PlanClock : Clock() {
        val now = AtomicLong(Instant.parse("2026-10-27T00:00:00Z").toEpochMilli())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now.get())
    }
}
