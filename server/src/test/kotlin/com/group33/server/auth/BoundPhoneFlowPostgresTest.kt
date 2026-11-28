package com.group33.server.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Tag("postgres")
internal class BoundPhoneFlowPostgresTest {
    private val schema = "s2_phone_flow_" + UUID.randomUUID().toString().replace("-", "")
    private val initial = 1_800_000_000_000L
    private val now = AtomicLong(initial)
    private val clock = object : Clock() {
        override fun instant() = Instant.ofEpochMilli(now.get())
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = Clock.fixed(instant(), zone)
    }
    private val installation = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val jwt = BoundPhoneJwt(ByteArray(32) { 8 }, "phone-test", "phone-test-db", clock, 0)
    private val mapper = ObjectMapper()
    private val grants = AtomicInteger()
    private val verifies = AtomicInteger()
    private var grant: () -> BoundPhoneGrant = { BoundPhoneGrant("fictional-sdk-token", now.get() + 900_000) }
    private var verify: () -> String? = { "+8613800000000" }
    private val provider = object : BoundPhoneProvider {
        override fun grant(): BoundPhoneGrant { grants.incrementAndGet(); return grant.invoke() }
        override fun verify(proof: String): String? { verifies.incrementAndGet(); return verify.invoke() }
    }
    private fun service() = BoundPhoneAuthentication(BoundPhoneIdentityStore(source(schema)), provider, jwt, clock, "fictional-scheme", ByteArray(32) { 5 })

    @BeforeEach fun prepare() {
        source().connection.use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") } }
        for (name in listOf("bound-phone-identity.sql", "bound-phone-auth-flow.sql")) {
            source(schema).connection.use { connection -> connection.createStatement().use { statement ->
                statement.execute(checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText())
            } }
        }
    }
    @AfterEach fun clean() {
        source().connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    @Test fun `strict HTTP start refresh verify register and relogin retain the bound identity`() {
        val mvc = MockMvcBuilders.standaloneSetup(BoundPhoneAuthController(service()) { null }).build()
        fun request(path: String, body: String) = mvc.perform(post("/api/v1/auth/$path").contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().response.also { assertEquals("no-store", it.getHeader("Cache-Control")) }
        fun ok(path: String, input: Map<String, Any>) = request(path, mapper.writeValueAsString(input)).let {
            assertEquals(200, it.status); mapper.readTree(it.contentAsString)
        }
        val start = ok("fusion/token", mapOf("installationId" to installation))
        val capability = start.get("authenticationAttemptToken").asText()
        assertEquals(900, start.get("expiresInSeconds").asInt())
        assertEquals(400, request("fusion/token", """{"installationId":"$installation","authenticationAttemptToken":null}""").status)
        assertEquals(400, request("fusion/token", """{"installationId":"$installation","installationId":"$other"}""").status)
        assertEquals(400, request("fusion/token", """{"installationId":"$installation","role":"ELDER"}""").status)
        assertEquals(1, grants.get())
        now.addAndGet(60_000)
        val bound = mapOf("installationId" to installation, "authenticationAttemptToken" to capability)
        val refresh = ok("fusion/token", bound)
        assertEquals(capability, refresh.get("authenticationAttemptToken").asText())
        assertEquals(840, refresh.get("expiresInSeconds").asInt())
        val pending = ok("fusion/verify", bound + mapOf("verifyToken" to "fictional-one", "expectedPhone" to "13800000000"))
        assertEquals("REGISTRATION_REQUIRED", pending.get("status").asText())
        assertFalse(pending.has("accessToken")); assertEquals(300, pending.get("registrationTicketExpiresInSeconds").asInt())
        val registration = mapOf("registrationTicket" to pending.get("registrationTicket").asText(), "installationId" to installation,
            "role" to "CAREGIVER", "displayName" to "Fixture")
        assertEquals(422, request("phone/register", mapper.writeValueAsString(registration + ("installationId" to other))).status)
        val first = ok("phone/register", registration)
        val firstClaims = checkNotNull(jwt.validate(first.get("accessToken").asText()))
        assertEquals(installation, firstClaims.installationId)
        assertEquals(UserRole.CAREGIVER, firstClaims.role)
        assertEquals(422, request("phone/register", mapper.writeValueAsString(registration)).status)
        val again = service().start(installation)["authenticationAttemptToken"] as String
        val second = service().verify(installation, again, "fictional-two", null)
        val secondClaims = checkNotNull(jwt.validate(second.getValue("accessToken") as String))
        assertEquals(firstClaims.userId, secondClaims.userId)
        assertNotEquals(firstClaims.sessionId, secondClaims.sessionId)
        assertEquals(1, count("phone_accounts")); assertEquals(2, count("phone_auth_sessions"))
    }

    @Test fun `foreign installation and cross attempt proof reuse never call the supplier twice`() {
        val a = service().start(installation).getValue("authenticationAttemptToken") as String
        val b = service().start(other).getValue("authenticationAttemptToken") as String
        failure("PHONE_VERIFICATION_FAILED") { service().verify(other, a, "same-proof", null) }
        assertEquals(0, verifies.get())
        service().verify(installation, a, "same-proof", null)
        failure("PHONE_VERIFICATION_FAILED") { service().verify(other, b, "same-proof", null) }
        assertEquals(1, verifies.get()); assertEquals(1, count("phone_auth_proofs"))
    }

    @Test fun `unknown provider outcome keeps the fingerprint and closes the attempt`() {
        val a = service().start(installation).getValue("authenticationAttemptToken") as String
        verify = { error("private mocked supplier details") }
        val failure = failure("PHONE_AUTH_UNAVAILABLE") { service().verify(installation, a, "uncertain", null) }
        assertNull(failure.cause)
        failure("PHONE_VERIFICATION_FAILED") { service().refresh(installation, a) }
        val b = service().start(other).getValue("authenticationAttemptToken") as String
        failure("PHONE_VERIFICATION_FAILED") { service().verify(other, b, "uncertain", null) }
        assertEquals(1, verifies.get()); assertEquals(1, count("phone_auth_proofs"))
        assertEquals(0, count("phone_bound_registration_tickets"))
    }

    @Test fun `supplier call releases DB locks and completion is fenced at lease expiry`() {
        val a = service().start(installation).getValue("authenticationAttemptToken") as String
        val called = CountDownLatch(1)
        val release = CountDownLatch(1)
        verify = { called.countDown(); check(release.await(5, TimeUnit.SECONDS)); "+8613800000000" }
        Executors.newSingleThreadExecutor().let { executor ->
            try {
                val pending = executor.submit<PhoneAuthFailure> {
                    failure("PHONE_VERIFICATION_FAILED") { service().verify(installation, a, "late", null) }
                }
                assertTrue(called.await(5, TimeUnit.SECONDS))
                failure("OPERATION_IN_PROGRESS") { service().refresh(installation, a) }
                now.addAndGet(20_000)
                release.countDown(); assertNotNull(pending.get(5, TimeUnit.SECONDS))
                failure("PHONE_VERIFICATION_FAILED") { service().refresh(installation, a) }
            } finally { release.countDown(); executor.shutdownNow() }
        }
        assertEquals(0, count("phone_bound_registration_tickets"))
    }

    @Test fun `ticket expires on its own clock and initial grant can shorten the attempt`() {
        grant = { BoundPhoneGrant("short", initial + 120_000) }
        val a = service().start(installation).getValue("authenticationAttemptToken") as String
        now.set(initial + 90_000)
        val ticket = service().verify(installation, a, "registration", null).getValue("registrationTicket") as String
        now.set(initial + 120_000)
        failure("PHONE_VERIFICATION_FAILED") { service().refresh(installation, a) }
        now.set(initial + 390_000)
        failure("REGISTRATION_INVALID") { service().register(installation, ticket, UserRole.ELDER, null) }
        assertEquals(0, count("phone_accounts"))
    }

    @Test fun `a verified SMS intent mismatch creates no account or ticket`() {
        val a = service().start(installation).getValue("authenticationAttemptToken") as String
        failure("PHONE_MISMATCH") { service().verify(installation, a, "fictional-sms", "13900000000") }
        assertEquals(0, count("phone_accounts")); assertEquals(0, count("phone_bound_registration_tickets"))
    }

    private fun failure(code: String, action: () -> Unit): PhoneAuthFailure =
        assertThrows(PhoneAuthFailure::class.java, action).also { assertEquals(code, it.code) }

    private fun count(table: String) = source(schema).connection.use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) } }
    }

    companion object {
        fun source(schema: String? = null) = PGSimpleDataSource().apply {
            val url = requireNotNull(System.getenv("S2_PG_INTEGRATION_URL"))
            require(url == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local" ||
                System.getenv("CI") == "true" && url == "jdbc:postgresql://127.0.0.1:5432/group33_phone_fixture")
            setURL(url)
            user = requireNotNull(System.getenv("S2_PG_INTEGRATION_USER"))
            password = requireNotNull(System.getenv("S2_PG_INTEGRATION_PASSWORD"))
            connectTimeout = 5; socketTimeout = 10
            if (schema != null) { currentSchema = schema; applicationName = schema }
        }
    }

    @Test fun `refresh cannot retarget an original attempt to a changed configured scheme`() {
        val original = service().start(installation)
        val capability = original.getValue("authenticationAttemptToken") as String
        now.addAndGet(60_000)
        val changed = BoundPhoneAuthentication(BoundPhoneIdentityStore(source(schema)), provider, jwt, clock,
            "different-scheme", ByteArray(32) { 5 })
        val response = try { changed.refresh(installation, capability) } catch (failure: PhoneAuthFailure) {
            assertEquals("PHONE_VERIFICATION_FAILED", failure.code)
            null
        }
        if (response != null) {
            assertEquals(original.getValue("schemeCode"), response.getValue("schemeCode"),
                "a surviving original attempt must not echo a different scheme")
            assertEquals(capability, response.getValue("authenticationAttemptToken"))
        }
    }

    @Test fun `old scheme capability cannot authorize proof work on a newly configured supplier`() {
        val capability = service().start(installation).getValue("authenticationAttemptToken") as String
        val foreignVerifies = AtomicInteger()
        val changedProvider = object : BoundPhoneProvider {
            override fun grant() = BoundPhoneGrant("other-scheme-token", now.get() + 900_000)
            override fun verify(proof: String): String? { foreignVerifies.incrementAndGet(); return "+8613900000000" }
        }
        val changed = BoundPhoneAuthentication(BoundPhoneIdentityStore(source(schema)), changedProvider, jwt, clock,
            "different-scheme", ByteArray(32) { 5 })
        try { changed.verify(installation, capability, "proof-from-new-scheme", null) }
        catch (failure: PhoneAuthFailure) { assertEquals("PHONE_VERIFICATION_FAILED", failure.code) }
        assertEquals(0, foreignVerifies.get(), "original capability must be fenced before calling a new scheme provider")
        assertEquals(0, count("phone_bound_registration_tickets"))
    }

    @Test fun `registration ticket keeps its original scheme and survives foreign scheme rejection`() {
        val capability = service().start(installation).getValue("authenticationAttemptToken") as String
        val ticket = service().verify(installation, capability, "scheme-ticket-proof", null)
            .getValue("registrationTicket") as String
        val changed = BoundPhoneAuthentication(BoundPhoneIdentityStore(source(schema)), provider, jwt, clock,
            "different-scheme", ByteArray(32) { 5 })
        failure("REGISTRATION_INVALID") { changed.register(installation, ticket, UserRole.ELDER, null) }
        assertEquals(0, count("phone_accounts"))
        assertEquals("SIGNED_IN", service().register(installation, ticket, UserRole.ELDER, null)["status"])
        assertEquals(1, count("phone_accounts"))
        assertEquals(1, verifies.get())
    }

}
