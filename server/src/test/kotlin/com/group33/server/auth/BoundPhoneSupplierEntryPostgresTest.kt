package com.group33.server.auth

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/** Inject scheduler delay immediately after a real successful DB commit. */
@Tag("postgres")
internal class BoundPhoneSupplierEntryPostgresTest {
    private val schema = "s1_supplier_entry_" + UUID.randomUUID().toString().replace("-", "")
    private val initial = 1_800_000_000_000L
    private val now = AtomicLong(initial)
    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now.get())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }
    private val installation = UUID.randomUUID()
    private val grants = AtomicInteger()
    private val verifies = AtomicInteger()
    private val afterNextCommit = AtomicReference<(() -> Unit)?>(null)
    private val jwt = BoundPhoneJwt(ByteArray(32) { 9 }, "s1-entry-test", "s1-entry-test-profile", clock, 0)
    private val provider = object : BoundPhoneProvider {
        override fun grant(): BoundPhoneGrant {
            grants.incrementAndGet()
            return BoundPhoneGrant("fictional-provider-grant", now.get() + 900_000L)
        }
        override fun verify(proof: String): String? {
            verifies.incrementAndGet()
            return "+8613800000000"
        }
    }

    private fun rawSource(schemaName: String? = null) = PGSimpleDataSource().apply {
        val url = requireNotNull(System.getenv("S2_PG_INTEGRATION_URL"))
        require(url == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local" ||
            System.getenv("CI") == "true" && url == "jdbc:postgresql://127.0.0.1:5432/group33_phone_fixture")
        setURL(url)
        user = requireNotNull(System.getenv("S2_PG_INTEGRATION_USER"))
        password = requireNotNull(System.getenv("S2_PG_INTEGRATION_PASSWORD"))
        connectTimeout = 5
        socketTimeout = 10
        if (schemaName != null) { currentSchema = schemaName; applicationName = schemaName }
    }

    private fun wrapped(connection: Connection): Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java),
    ) { _, method, args ->
        val result = try { method.invoke(connection, *(args ?: emptyArray())) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
        if (method.name == "commit") afterNextCommit.getAndSet(null)?.invoke()
        result
    } as Connection

    private fun service(): BoundPhoneAuthentication {
        val actual = rawSource(schema)
        val source = object : DataSource by actual {
            override fun getConnection(): Connection = wrapped(actual.connection)
        }
        return BoundPhoneAuthentication(BoundPhoneIdentityStore(source), provider, jwt, clock,
            "fictional-scheme", ByteArray(32) { 6 })
    }

    @BeforeEach fun prepare() {
        rawSource().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        for (name in listOf("bound-phone-identity.sql", "bound-phone-auth-flow.sql")) {
            rawSource(schema).connection.use { c -> c.createStatement().use {
                it.execute(checkNotNull(javaClass.getResource("/db/prototypes/$name")).readText())
            } }
        }
    }

    @AfterEach fun clean() {
        rawSource().connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    @Test fun `RF03 start whose reservation commit reaches D must not call provider grant`() {
        // Before commit: claim is timely. After actual commit: an injected scheduler pause reaches D.
        afterNextCommit.set { now.set(initial + 900_000L) }
        val failure = assertThrows(PhoneAuthFailure::class.java) { service().start(installation) }
        assertEquals("PHONE_VERIFICATION_FAILED", failure.code)
        assertEquals(0, grants.get(), "At the original deadline, no GRANT may be sent upstream")
    }

    @Test fun `RF03 refresh whose reservation commit reaches D must not resend provider grant`() {
        val capability = service().start(installation).getValue("authenticationAttemptToken") as String
        assertEquals(1, grants.get())
        now.set(initial + 30_000L)
        afterNextCommit.set { now.set(initial + 900_000L) }
        val failure = assertThrows(PhoneAuthFailure::class.java) { service().refresh(installation, capability) }
        assertEquals("PHONE_VERIFICATION_FAILED", failure.code)
        assertEquals(1, grants.get(), "The expired refresh must not send a second GRANT upstream")
    }

    @Test fun `RF03 verify whose reservation commit reaches D must not send proof to provider`() {
        val capability = service().start(installation).getValue("authenticationAttemptToken") as String
        afterNextCommit.set { now.set(initial + 900_000L) }
        val failure = assertThrows(PhoneAuthFailure::class.java) {
            service().verify(installation, capability, "fictional-proof-at-expired-entry", null)
        }
        assertEquals("PHONE_VERIFICATION_FAILED", failure.code)
        assertEquals(0, verifies.get(), "Rejecting completion is insufficient once an expired VERIFY was sent")
    }

    @Test fun `control timely reservation commit permits exactly one grant and verification`() {
        val capability = service().start(installation).getValue("authenticationAttemptToken") as String
        val result = service().verify(installation, capability, "fictional-valid-proof", null)
        assertEquals("REGISTRATION_REQUIRED", result["status"])
        assertEquals(1, grants.get())
        assertEquals(1, verifies.get())
    }
    @Test fun `reservation commit at lease expiry cannot start supplier work before D`() {
        afterNextCommit.set { now.set(initial + 20_000L) }
        val failure = assertThrows(PhoneAuthFailure::class.java) { service().start(installation) }
        assertEquals("PHONE_VERIFICATION_FAILED", failure.code)
        assertEquals(0, grants.get())
    }

    @Test fun `remaining lease must cover the full supplier budget at entry`() {
        afterNextCommit.set { now.set(initial + 5_000L) }
        val failure = assertThrows(PhoneAuthFailure::class.java) { service().start(installation) }
        assertEquals("PHONE_VERIFICATION_FAILED", failure.code)
        assertEquals(0, grants.get())
    }

    @Test fun `deadline is checked after supplier entry validation transaction commits`() {
        afterNextCommit.set { afterNextCommit.set { now.set(initial + 900_000L) } }
        val failure = assertThrows(PhoneAuthFailure::class.java) { service().start(installation) }
        assertEquals("PHONE_VERIFICATION_FAILED", failure.code)
        assertEquals(0, grants.get())
    }

}
