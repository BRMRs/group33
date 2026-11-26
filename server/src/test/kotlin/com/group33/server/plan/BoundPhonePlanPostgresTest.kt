package com.group33.server.plan

import com.group33.server.auth.*
import com.group33.server.pairing.*
import com.group33.server.photo.*
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.sql.DataSource

@Tag("postgres")
internal class BoundPhonePlanPostgresTest {
    @TempDir lateinit var directory: Path
    private val schema = "s2_phone_plan_" + UUID.randomUUID().toString().replace("-", "")
    private val now = 1_800_000_000L
    private val clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)
    private val jwt = BoundPhoneJwt(ByteArray(32) { 8 }, "plan-fixture", "plan-fixture", clock)
    private lateinit var postgres: PostgresPairingCodeStore
    private lateinit var objects: LocalFilePhotoObjectStore
    private lateinit var original: ConfirmReminderPlanResponse
    private lateinit var confirmInput: ConfirmReminderPlanRequest
    private lateinit var relation: CareRelationshipView
    private lateinit var elder: AnonymousUser
    private lateinit var phone: BoundPhoneSession
    private lateinit var input: UpdateReminderPlanRequest
    private val acquired = AtomicInteger()
    private val installation = UUID.randomUUID()
    private val hash = "1".repeat(64)

    @BeforeEach fun prepare() {
        val source = BoundPhoneFlowPostgresTest.source(schema)
        val observed = object : DataSource by source {
            override fun getConnection(): Connection { acquired.incrementAndGet(); return source.connection }
        }
        BoundPhoneFlowPostgresTest.source().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        source.connection.use { connection ->
            val files = listOf("pairing-code-store-v1.sql", "pairing-request-store-v2.sql", "pairing-decision-store-v3.sql",
                "pairing-directory-store-v4.sql", "anonymous-identity-store-v5.sql", "medication-photo-store-v6.sql",
                "plan-confirmation-store-v7.sql", "plan-mutation-store-v8.sql", "bound-phone-identity.sql")
            connection.createStatement().use { statement -> files.forEach { name ->
                statement.execute(checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText())
            } }
        }
        postgres = PostgresPairingCodeStore(observed, PairingReceiptCipher(ByteArray(32) { 3 }))
        val registered = PostgresAnonymousIdentityStore(source).getOrCreate("fictional-elder-installation", "Fixture Elder", UserRole.ELDER)
        elder = registered
        phone = session(installation)
        // Fixture setup drives the old services to seed a relationship/plan. No anonymous account
        // is inserted for the phone actor. Test execution enters through the bound JWT methods.
        val setupCaregiver = AnonymousUser(phone.user.id, installation.toString(), "Fixture Caregiver", UserRole.CAREGIVER)
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java, PairingCodeIssuanceService(postgres, clock).issue(elder, key())).view.code
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java, PairingRequestService(postgres, clock).request(
            setupCaregiver, key(), RequestPairingRequest(code), InetAddress.getByName("192.0.2.44"))).view
        relation = assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(postgres, clock).accept(elder, pending.id, key())).view
        val bytes = ByteArrayOutputStream().use { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it); it.toByteArray() }
        objects = LocalFilePhotoObjectStore(directory.resolve("objects"))
        val photo = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            MedicationPhotoService(postgres, objects, clock).upload(elder, elder.id, key(), bytes, "image/png")).view
        val draft = ReminderPlanDraft(listOf("08:00"), "2026-10-27", "Asia/Shanghai")
        val acknowledgment = TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai")
        confirmInput = ConfirmReminderPlanRequest(photo.id, MedicationText("Fixture", "Fixture"), draft, acknowledgment)
        original = assertInstanceOf(PlanConfirmationResult.Confirmed::class.java,
            ReminderPlanConfirmationService(postgres, objects, clock).confirm(setupCaregiver, elder.id, key(), confirmInput)).view
        input = UpdateReminderPlanRequest(1, draft.copy(localTimes = listOf("09:00")), acknowledgment)
    }

    @AfterEach fun clean() {
        BoundPhoneFlowPostgresTest.source().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    @Test fun `relogin keeps original receipt while another installation cannot take it over`() {
        val op = key()
        val opened = acquired.get()
        val receipt = accepted(edit(phone, op))
        assertEquals(2L, receipt.plan.revision)
        assertEquals(1, acquired.get() - opened, "identity, authorization, effect and receipt must use one connection")
        val renewed = session(installation)
        assertNotEquals(phone.claims.sessionId, renewed.claims.sessionId)
        assertEquals(receipt, accepted(edit(renewed, op)))
        rejected(edit(session(UUID.randomUUID()), op), PlanMutationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(1, operationCount())
    }

    @Test fun `relationship revocation prevents even reading the original successful receipt`() {
        val op = key()
        accepted(edit(phone, op))
        assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(postgres, clock).revoke(elder, relation.id, key()))
        rejected(edit(phone, op), PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(1, operationCount())
    }

    @Test fun `revoked session and ambiguous namespace both deny effects and receipts`() {
        val op = key(); accepted(edit(phone, op))
        identity().transaction { it.revokeSession(phone.claims.userId, installation, phone.claims.sessionId, now * 1000) }
        rejected(edit(phone, op), PlanMutationRejection.UNAUTHENTICATED)
        val next = session(installation)
        BoundPhoneFlowPostgresTest.source(schema).connection.use { connection ->
            connection.prepareStatement("INSERT INTO anonymous_identity(id, installation_id, display_name, role) VALUES (?, ?, ?, ?)").use {
                it.setString(1, phone.user.id); it.setString(2, "fictional-collision"); it.setString(3, "Fixture"); it.setString(4, "CAREGIVER")
                it.executeUpdate()
            }
        }
        rejected(edit(next, op), PlanMutationRejection.UNAUTHENTICATED)
        assertEquals(1, operationCount())
    }

    @Test fun `invalid token is rejected before opening a connection`() {
        val wrong = BoundPhoneJwt(ByteArray(32) { 9 }, "plan-fixture", "plan-fixture", clock).issue(phone.claims)
        val opened = acquired.get()
        rejected(service().updatePhone(postgres, jwt, wrong, original.plan.id, key(), input), PlanMutationRejection.UNAUTHENTICATED)
        assertEquals(opened, acquired.get())
        assertEquals(0, operationCount())
    }

    @Test fun `phone deactivation preserves its original stop receipt`() {
        val op = key(); val request = DeactivateReminderPlanRequest(1)
        fun stop() = service().deactivatePhone(postgres, jwt, jwt.issue(phone.claims), original.plan.id, op, request)
        val result = accepted(stop())
        assertFalse(result.plan.active)
        assertEquals(result, accepted(stop()))
        assertEquals(1, operationCount())
    }

    @Test fun `phone confirmation uses one primary transaction replays on same installation and rejects takeover`() {
        val op = key()
        val opened = acquired.get()
        val created = confirmed(confirm(phone, op))
        assertEquals(1, acquired.get() - opened, "phone confirmation must use one connection")
        assertEquals(1L, created.plan.revision)
        val renewed = session(installation)
        assertEquals(created, confirmed(confirm(renewed, op)))
        confirmRejected(confirm(session(UUID.randomUUID()), op), PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED)
        assertEquals(2, confirmationCount())
        val wrong = BoundPhoneJwt(ByteArray(32) { 9 }, "plan-fixture", "plan-fixture", clock).issue(phone.claims)
        val before = acquired.get()
        confirmRejected(confirmations().confirmPhone(postgres, jwt, wrong, elder.id, key(), confirmInput), PlanConfirmationRejection.UNAUTHENTICATED)
        assertEquals(before, acquired.get())
    }

    @Test fun `phone collection reads execute in one read only snapshot without guard lock and deny after revocation`() {
        val phoneElder = identity().transaction {
            val user = it.registerVerifiedPhone("2".repeat(64), UserRole.ELDER, null)
            it.openSession(UUID.fromString(user.id), UUID.randomUUID(), jwt.audience, now, now + 3600)
        }
        val elderSetup = AnonymousUser(phoneElder.user.id, phoneElder.claims.installationId.toString(), "Fixture", UserRole.ELDER)
        val caregiverSetup = AnonymousUser(phone.user.id, installation.toString(), "Fixture", UserRole.CAREGIVER)
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java, PairingCodeIssuanceService(postgres, clock).issue(elderSetup, key())).view.code
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java, PairingRequestService(postgres, clock).request(
            caregiverSetup, key(), RequestPairingRequest(code), InetAddress.getByName("192.0.2.45"))).view
        val phoneRelation = assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(postgres, clock).accept(elderSetup, pending.id, key())).view
        val bytes = ByteArrayOutputStream().use { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it); it.toByteArray() }
        val photo = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            MedicationPhotoService(postgres, objects, clock).upload(elderSetup, phoneElder.user.id, key(), bytes, "image/png")).view
        val saved = confirmed(confirmations().confirmPhone(postgres, jwt, jwt.issue(phone.claims), phoneElder.user.id, key(),
            confirmInput.copy(photoId = photo.id)))
        confirmRejected(confirmations().confirmPhone(postgres, jwt, jwt.issue(phoneElder.claims), phoneElder.user.id, key(),
            confirmInput.copy(photoId = photo.id)), PlanConfirmationRejection.ROLE_NOT_ALLOWED)
        // Hold pairing_issuance_guard FOR UPDATE on another connection to prove phonePlanRead does not touch the write guard.
        BoundPhoneFlowPostgresTest.source(schema).connection.use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.executeQuery("SELECT id FROM pairing_issuance_guard WHERE id = 1 FOR UPDATE").close() }
            for (reader in listOf(phone, phoneElder)) {
                val opened = acquired.get()
                val meds = assertInstanceOf(PlanConfirmationResult.Medications::class.java,
                    confirmations().medicationsPhone(postgres, jwt, jwt.issue(reader.claims), phoneElder.user.id)).items
                assertEquals(1, acquired.get() - opened)
                assertEquals(listOf(saved.medication), meds)
                val plans = assertInstanceOf(PlanConfirmationResult.Plans::class.java,
                    confirmations().plansPhone(postgres, jwt, jwt.issue(reader.claims), phoneElder.user.id)).value
                assertEquals(listOf(saved.plan), plans.items)
            }
            blocker.rollback()
        }
        assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(postgres, clock).revoke(elderSetup, phoneRelation.id, key()))
        confirmRejected(confirmations().medicationsPhone(postgres, jwt, jwt.issue(phone.claims), phoneElder.user.id),
            PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        confirmRejected(confirmations().plansPhone(postgres, jwt, jwt.issue(phone.claims), phoneElder.user.id),
            PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE)
        assertEquals(listOf(saved.medication), assertInstanceOf(PlanConfirmationResult.Medications::class.java,
            confirmations().medicationsPhone(postgres, jwt, jwt.issue(phoneElder.claims), phoneElder.user.id)).items)
        identity().transaction { it.revokeSession(phoneElder.claims.userId, phoneElder.claims.installationId, phoneElder.claims.sessionId, now * 1000) }
        confirmRejected(confirmations().plansPhone(postgres, jwt, jwt.issue(phoneElder.claims), phoneElder.user.id),
            PlanConfirmationRejection.UNAUTHENTICATED)
    }

    private fun identity() = BoundPhoneIdentityStore(BoundPhoneFlowPostgresTest.source(schema))
    private fun session(installation: UUID) = identity().transaction {
        val user = it.registerVerifiedPhone(hash, UserRole.CAREGIVER, "Fixture Caregiver")
        it.openSession(UUID.fromString(user.id), installation, jwt.audience, now, now + 3600)
    }
    private fun key() = UUID.randomUUID()
    private fun service() = ReminderPlanMutationService(postgres, clock)
    private fun confirmations() = ReminderPlanConfirmationService(postgres, objects, clock)
    private fun confirm(session: BoundPhoneSession, op: UUID) =
        confirmations().confirmPhone(postgres, jwt, jwt.issue(session.claims), elder.id, op, confirmInput)
    private fun edit(session: BoundPhoneSession, op: UUID) =
        service().updatePhone(postgres, jwt, jwt.issue(session.claims), original.plan.id, op, input)
    private fun accepted(value: PlanMutationResult) = assertInstanceOf(PlanMutationResult.Accepted::class.java, value).view
    private fun confirmed(value: PlanConfirmationResult) = assertInstanceOf(PlanConfirmationResult.Confirmed::class.java, value).view
    private fun rejected(value: PlanMutationResult, reason: PlanMutationRejection) =
        assertEquals(reason, assertInstanceOf(PlanMutationResult.Rejected::class.java, value).reason)
    private fun confirmRejected(value: PlanConfirmationResult, reason: PlanConfirmationRejection) =
        assertEquals(reason, assertInstanceOf(PlanConfirmationResult.Rejected::class.java, value).reason)
    private fun operationCount() = BoundPhoneFlowPostgresTest.source(schema).connection.use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM plan_mutation_operation").use { rows ->
            rows.next(); rows.getInt(1)
        } }
    }
    private fun confirmationCount() = BoundPhoneFlowPostgresTest.source(schema).connection.use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM plan_confirmation_operation").use { rows ->
            rows.next(); rows.getInt(1)
        } }
    }
}
