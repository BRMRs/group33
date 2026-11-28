package com.group33.server.auth

import com.group33.shared.model.UserRole
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
internal class PostgresBoundPhoneIdentityTest {
    private val schema = "s2_phone_identity_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var source: PGSimpleDataSource
    private val phoneHash = "1".repeat(64)
    private val installation = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val profile = "group33-api:fictional-test"
    private val issued = 1_800_000_000L
    private val expires = issued + 86_400

    private fun dataSource(schema: String? = null) = PGSimpleDataSource().apply {
        val url = requireNotNull(System.getenv("S2_PG_INTEGRATION_URL"))
        require(url == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local" ||
            System.getenv("CI") == "true" && url == "jdbc:postgresql://127.0.0.1:5432/group33_phone_fixture") {
            "Only explicit isolated local or CI phone fixtures are allowed"
        }
        setURL(url)
        user = requireNotNull(System.getenv("S2_PG_INTEGRATION_USER"))
        password = requireNotNull(System.getenv("S2_PG_INTEGRATION_PASSWORD"))
        connectTimeout = 5; socketTimeout = 10
        schema?.let { currentSchema = it; applicationName = it }
    }
    @BeforeEach fun prepare() {
        source = dataSource(schema)
        dataSource().connection.use {
            it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") }
        }
        sql(checkNotNull(javaClass.getResource("/db/prototypes/bound-phone-identity.sql")).readText())
    }
    @AfterEach fun cleanup() {
        dataSource().connection.use {
            it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }
    private fun store() = BoundPhoneIdentityStore(dataSource(schema))
    private fun sql(sql: String) = source.connection.use { it.createStatement().use { s -> s.execute(sql) } }
    private fun scalar(sql: String) = source.connection.use { it.createStatement().use { s -> s.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) } } }
    private fun open(hash: String = phoneHash, install: UUID = installation, role: UserRole = UserRole.CAREGIVER) = store().transaction {
        val user = it.registerVerifiedPhone(hash, role, "Fictional Caregiver")
        it.openSession(UUID.fromString(user.id), install, profile, issued, expires)
    }
    private fun copy(c: BoundPhoneSessionClaims, user: UUID = c.userId, install: UUID = c.installationId,
                     session: UUID = c.sessionId, role: UserRole = c.role, audience: String = c.profile,
                     iat: Long = c.issuedAtSeconds, exp: Long = c.expiresAtSeconds, version: Int = c.authVersion) =
        BoundPhoneSessionClaims(user, install, session, role, audience, iat, exp, version)
    private fun current(c: BoundPhoneSessionClaims, now: Long = issued + 1, skew: Long = 60) =
        store().transaction { it.lockCurrentSession(c, now, skew) }

    @Test fun `one fixed account survives new repository and independent installations`() {
        val first = open()
        val second = open(install = UUID.randomUUID(), role = UserRole.ELDER)
        assertEquals(first.user, second.user)
        assertEquals(UserRole.CAREGIVER, second.user.role)
        assertNotEquals(first.claims.sessionId, second.claims.sessionId)
        assertEquals("1", scalar("SELECT count(*) FROM phone_accounts"))
        assertEquals("2", scalar("SELECT count(*) FROM phone_account_installations"))
        assertEquals("2", scalar("SELECT count(*) FROM phone_auth_sessions"))
        assertEquals(first.user, current(first.claims)); assertEquals(first.user, current(second.claims))
    }

    @Test fun `two accounts may share an installation without displacing either account`() {
        val first = open()
        val second = open(hash = "2".repeat(64), role = UserRole.ELDER)
        assertNotEquals(first.user.id, second.user.id)
        assertEquals(first.claims.installationId, second.claims.installationId)
        assertEquals(first.user, current(first.claims)); assertEquals(second.user, current(second.claims))
        assertEquals("2", scalar("SELECT count(*) FROM phone_account_installations"))
    }

    @Test fun `new login on the same installation preserves old sessions and original account name`() {
        val first = open()
        val second = store().transaction {
            val user = it.registerVerifiedPhone(phoneHash, UserRole.ELDER, null)
            it.openSession(UUID.fromString(user.id), installation, profile, issued + 1, expires + 1)
        }
        assertEquals(first.user, second.user)
        assertEquals(first.user, current(first.claims)); assertEquals(second.user, current(second.claims))
        assertEquals("1", scalar("SELECT count(*) FROM phone_account_installations"))
        assertEquals("2", scalar("SELECT count(*) FROM phone_auth_sessions"))
    }

    @Test fun `eight concurrent connections converge to one fixed account and retain eight sessions`() {
        val pool = Executors.newFixedThreadPool(8); val ready = CountDownLatch(8); val start = CountDownLatch(1)
        try {
            val futures = (0..7).map { index -> pool.submit<BoundPhoneSession> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                open(install = UUID.randomUUID(), role = if (index % 2 == 0) UserRole.ELDER else UserRole.CAREGIVER)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val all = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, all.map { it.user }.distinct().size)
            assertEquals("1", scalar("SELECT count(*) FROM phone_accounts"))
            assertEquals("8", scalar("SELECT count(*) FROM phone_auth_sessions"))
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun `foreign claim fields cannot match the current persisted identity`() {
        val original = open()
        val invalid = listOf(copy(original.claims, user = UUID.randomUUID()), copy(original.claims, install = UUID.randomUUID()),
            copy(original.claims, session = UUID.randomUUID()), copy(original.claims, role = UserRole.ELDER),
            copy(original.claims, audience = "group33-api:other"), copy(original.claims, iat = issued - 1),
            copy(original.claims, exp = expires + 1), copy(original.claims, version = 0))
        invalid.forEach { assertNull(current(it)) }
        assertEquals(original.user, current(original.claims))
    }

    @Test fun `exact clock allowance is shared with the caller policy and never exceeds sixty seconds`() {
        val original = open()
        assertNull(current(original.claims, issued - 61))
        assertEquals(original.user, current(original.claims, issued - 60))
        assertEquals(original.user, current(original.claims, expires + 59))
        assertNull(current(original.claims, expires + 60))
        assertNull(current(original.claims, expires, 0))
        assertThrows(IllegalArgumentException::class.java) { current(original.claims, issued + 1, 61) }
    }

    @Test fun `revoking one session leaves a separately issued session usable`() {
        val first = open(); val second = open()
        store().transaction { it.revokeSession(first.claims.userId, installation, first.claims.sessionId, issued * 1000 + 1) }
        assertNull(current(first.claims)); assertEquals(second.user, current(second.claims))
        assertEquals("2", scalar("SELECT count(*) FROM phone_auth_sessions"))
    }

    @Test fun `revoking an installation does not revoke another installation or resurrect on new session`() {
        val first = open(); val second = open(install = UUID.randomUUID())
        store().transaction { it.revokeInstallation(first.claims.userId, installation, issued * 1000 + 1) }
        assertNull(current(first.claims)); assertEquals(second.user, current(second.claims))
        assertThrows(BoundPhoneIdentityRevoked::class.java) { open() }
        assertEquals("2", scalar("SELECT count(*) FROM phone_auth_sessions"))
    }

    @Test fun `revoked account denies current sessions and cannot be recreated by registration`() {
        val original = open()
        store().transaction { it.revokeAccount(original.claims.userId, issued * 1000 + 1) }
        assertNull(current(original.claims)); assertThrows(BoundPhoneIdentityRevoked::class.java) { open() }
        assertEquals("1", scalar("SELECT count(*) FROM phone_accounts"))
        assertEquals("1", scalar("SELECT count(*) FROM phone_auth_sessions"))
    }

    @Test fun `deferred session failure rolls back account installation and session without leaking diagnostics`() {
        sql("""CREATE FUNCTION fail_phone_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
            RAISE EXCEPTION 'PRIVATE fictional database diagnostics'; END $$""")
        sql("CREATE CONSTRAINT TRIGGER fail_phone_commit AFTER INSERT ON phone_auth_sessions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_phone_commit()")
        val failure = assertThrows(BoundPhoneIdentityUnavailable::class.java) { open() }
        assertNull(failure.cause); assertFalse(failure.toString().contains("PRIVATE"))
        listOf("phone_accounts", "phone_account_installations", "phone_auth_sessions").forEach {
            assertEquals("0", scalar("SELECT count(*) FROM $it"))
        }
        sql("DROP TRIGGER fail_phone_commit ON phone_auth_sessions")
        assertNotNull(open())
    }

    @Test fun `runtime never creates a missing schema and maps SQL failure to sanitized unavailable`() {
        sql("DROP TABLE phone_auth_sessions")
        val failure = assertThrows(BoundPhoneIdentityUnavailable::class.java) { open() }
        assertNull(failure.cause); assertFalse(failure.toString().contains(schema))
        assertEquals("0", scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='$schema' AND table_name='phone_auth_sessions'"))
        assertEquals("0", scalar("SELECT count(*) FROM phone_accounts"))
    }

    @Test fun `identity and caller work roll back on the same connection`() {
        sql("CREATE TABLE fictional_effect(value INTEGER NOT NULL)")
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                val tx = BoundPhoneIdentityTransaction(connection)
                val user = tx.registerVerifiedPhone(phoneHash, UserRole.ELDER, null)
                val session = tx.openSession(UUID.fromString(user.id), installation, profile, issued, expires)
                assertEquals(user, tx.lockCurrentSession(session.claims, issued + 1))
                connection.createStatement().use { it.execute("INSERT INTO fictional_effect VALUES (1)") }
            } finally { connection.rollback() }
        }
        assertEquals("0", scalar("SELECT count(*) FROM fictional_effect"))
        assertEquals("0", scalar("SELECT count(*) FROM phone_accounts"))
        assertEquals("0", scalar("SELECT count(*) FROM phone_auth_sessions"))
    }

    @Test fun `bounded lock failure rolls back and same session works after the lock releases`() {
        val original = open()
        source.connection.use { blocking ->
            blocking.autoCommit = false
            blocking.createStatement().use { it.execute("LOCK TABLE phone_auth_sessions IN ACCESS EXCLUSIVE MODE") }
            try {
                val failure = assertThrows(BoundPhoneIdentityUnavailable::class.java) { current(original.claims) }
                assertNull(failure.cause)
            } finally { blocking.rollback() }
        }
        assertEquals(original.user, current(original.claims))
    }

    @Test fun `actual transaction uses bounded SQL and closes the acquired connection`() {
        val captured = java.util.concurrent.atomic.AtomicReference<Connection>()
        val observed = object : javax.sql.DataSource by source {
            override fun getConnection(): Connection = source.connection.also(captured::set)
        }
        BoundPhoneIdentityStore(observed).transaction {
            captured.get().createStatement().use { statement ->
                statement.executeQuery("SHOW statement_timeout").use { rows -> assertTrue(rows.next()); assertEquals("5s", rows.getString(1)) }
            }
        }
        assertTrue(captured.get().isClosed)
    }

    @Test fun `sensitive session metadata uses redacted diagnostics and no plaintext tokens are stored`() {
        val original = open()
        assertEquals("BoundPhoneSession(redacted)", original.toString())
        assertEquals("BoundPhoneSessionClaims(redacted)", original.claims.toString())
        assertEquals("0", scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='$schema' AND column_name IN ('phone','access_token','jwt','registration_ticket','verify_token')"))
    }
}
