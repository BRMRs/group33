package com.group33.server.auth

import com.aliyun.dypnsapi20170525.Client
import com.aliyun.dypnsapi20170525.models.*
import com.aliyun.teaopenapi.models.Config
import com.aliyun.teautil.models.RuntimeOptions
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** Actual PostgreSQL + pinned SDK seam; every supplier response is fictional and no external call is made. */
@Tag("postgres")
internal class BoundPhoneProviderPostgresTest {
    private val schema = "s1_provider_" + UUID.randomUUID().toString().replace("-", "")
    private val now = AtomicLong(1_800_000_000_000L)
    private val clock = object : Clock() {
        override fun instant() = Instant.ofEpochMilli(now.get())
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }
    private val installation = UUID.randomUUID()
    private val jwt = BoundPhoneJwt(ByteArray(32) { 8 }, "provider-test", "provider-test-db", clock, 0)
    private val pool = FusionCallExecutor.pool(1)
    private val result = AtomicReference("PASS")
    private val verifyCalls = AtomicInteger()
    private val hold = AtomicBoolean()
    private val release = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val mapper = ObjectMapper()

    private fun source(selected: String? = schema) = PGSimpleDataSource().apply {
        val url = requireNotNull(System.getenv("S2_PG_INTEGRATION_URL"))
        require(url == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local" ||
            System.getenv("CI") == "true" && url == "jdbc:postgresql://127.0.0.1:5432/group33_phone_fixture")
        setURL(url); user = requireNotNull(System.getenv("S2_PG_INTEGRATION_USER"))
        password = requireNotNull(System.getenv("S2_PG_INTEGRATION_PASSWORD"))
        connectTimeout = 5; socketTimeout = 10
        if (selected != null) { currentSchema = selected; applicationName = selected }
    }
    private fun scalar(sql: String) = source().connection.use { c -> c.createStatement().use { s ->
        s.executeQuery(sql).use { r -> check(r.next()); r.getLong(1) }
    } }
    private fun count(table: String) = scalar("SELECT count(*) FROM $table")
    private fun noIdentityWrites() {
        for (table in listOf("phone_accounts", "phone_account_installations", "phone_auth_sessions", "phone_bound_registration_tickets")) assertEquals(0, count(table), table)
    }
    private fun noHeldTransaction() = assertEquals(0, scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='$schema' " +
        "AND pid <> pg_backend_pid() AND state IN ('active','idle in transaction','idle in transaction (aborted)')"))

    private fun authentication(responseMillis: Long = 15_000): BoundPhoneAuthentication {
        val client = object : Client(Config().setAccessKeyId("FICTIONAL_KEY").setAccessKeySecret("FICTIONAL_SECRET")) {
            override fun getFusionAuthTokenWithOptions(request: GetFusionAuthTokenRequest, runtime: RuntimeOptions): GetFusionAuthTokenResponse {
                noHeldTransaction(); assertEquals("fictional-scheme", request.schemeCode)
                return GetFusionAuthTokenResponse().setBody(GetFusionAuthTokenResponseBody().setSuccess(true).setCode("OK").setModel("fictional-sdk-token"))
            }
            override fun verifyWithFusionAuthTokenWithOptions(request: VerifyWithFusionAuthTokenRequest, runtime: RuntimeOptions): VerifyWithFusionAuthTokenResponse {
                noHeldTransaction(); verifyCalls.incrementAndGet()
                try {
                    while (hold.get() && release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    val status = result.get()
                    return VerifyWithFusionAuthTokenResponse().setBody(VerifyWithFusionAuthTokenResponseBody()
                        .setSuccess(status != "OUTAGE").setCode(if (status == "OUTAGE") "PRIVATE_UPSTREAM_FAILURE" else "OK")
                        .setModel(VerifyWithFusionAuthTokenResponseBody.VerifyWithFusionAuthTokenResponseBodyModel()
                            .setVerifyResult(if (status == "MALFORMED") "PRIVATE_UNKNOWN_RESULT" else status).setPhoneNumber("+12025550123")))
                } finally { finished.countDown() }
            }
        }
        val provider = AlibabaBoundPhoneProvider("FICTIONAL_KEY", "FICTIONAL_SECRET", "fictional-scheme", "fictional.package", "FICTIONAL_SIGN", clock,
            FusionCallExecutor(pool, FusionCallPolicy(responseMillis = responseMillis))) { client }
        return BoundPhoneAuthentication(BoundPhoneIdentityStore(source()), provider, jwt, clock, "fictional-scheme", ByteArray(32) { 5 })
    }

    @BeforeEach fun prepare() {
        source(null).connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        for (name in listOf("bound-phone-identity.sql", "bound-phone-auth-flow.sql")) {
            source().connection.use { c -> c.createStatement().use {
                it.execute(checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText())
            } }
        }
    }
    @AfterEach fun clean() {
        release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        source(null).connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    @Test fun `bounded SDK grant proof registration and relogin preserve the bound identity`() {
        val service = authentication()
        val capability = service.start(installation).getValue("authenticationAttemptToken") as String
        val pending = service.verify(installation, capability, "fictional-proof-one", "+12025550123")
        val first = service.register(installation, pending.getValue("registrationTicket") as String, UserRole.CAREGIVER, "Fixture")
        val firstClaims = checkNotNull(jwt.validate(first.getValue("accessToken") as String))
        val again = service.start(installation).getValue("authenticationAttemptToken") as String
        val second = service.verify(installation, again, "fictional-proof-two", null)
        val secondClaims = checkNotNull(jwt.validate(second.getValue("accessToken") as String))
        assertEquals(firstClaims.userId, secondClaims.userId); assertEquals(installation, secondClaims.installationId)
        assertNotEquals(firstClaims.sessionId, secondClaims.sessionId)
        assertEquals(1, count("phone_accounts")); assertEquals(2, count("phone_auth_sessions")); assertEquals(2, verifyCalls.get())
    }

    private fun verifyHttp(service: BoundPhoneAuthentication, capability: String) =
        MockMvcBuilders.standaloneSetup(BoundPhoneAuthController(service) { null }).build().perform(
            post("/api/v1/auth/fusion/verify").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                mapOf("installationId" to installation, "authenticationAttemptToken" to capability, "verifyToken" to "fictional-proof"))))
            .andReturn().response

    @Test fun `late SDK PASS cannot create a registration ticket account or session after caller timeout`() {
        val capability = authentication().start(installation).getValue("authenticationAttemptToken") as String
        val service = authentication(responseMillis = 500)
        hold.set(true)
        val response = verifyHttp(service, capability)
        assertEquals(503, response.status); assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("PHONE_AUTH_UNAVAILABLE", mapper.readTree(response.contentAsString).get("code").asText())
        assertFalse(response.contentAsString.contains("PRIVATE_")); assertEquals(1, verifyCalls.get()); noIdentityWrites()
        assertEquals(1, count("phone_auth_proofs")); assertEquals(1, scalar("SELECT count(*) FROM phone_auth_attempts WHERE state='CLOSED'"))
        release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS)); noIdentityWrites()
        assertEquals(401, verifyHttp(service, capability).status); assertEquals(1, verifyCalls.get())
    }

    @ParameterizedTest @ValueSource(strings = ["UNKNOWN", "OUTAGE", "MALFORMED"])
    fun `explicit denial and unavailable outcomes retain proof fencing without creating identities`(status: String) {
        val service = authentication(); val capability = service.start(installation).getValue("authenticationAttemptToken") as String
        result.set(status)
        val response = verifyHttp(service, capability)
        assertEquals(if (status == "UNKNOWN") 401 else 503, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control")); assertFalse(response.contentAsString.contains("PRIVATE_"))
        noIdentityWrites(); assertEquals(1, count("phone_auth_proofs")); assertEquals(1, verifyCalls.get())
        assertEquals(401, verifyHttp(service, capability).status); assertEquals(1, verifyCalls.get())
    }
}
