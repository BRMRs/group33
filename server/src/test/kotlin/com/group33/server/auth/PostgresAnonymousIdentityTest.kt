package com.group33.server.auth

import com.group33.server.pairing.PostgresPairingCodeStoreTest
import com.group33.server.web.ApiException
import com.group33.shared.model.UserRole
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource

@Tag("postgres")
internal class PostgresAnonymousIdentityTest {
    private val schema = "s2_identity_it_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var source: PGSimpleDataSource
    @BeforeEach fun prepare() {
        source = PostgresPairingCodeStoreTest.dataSource(schema)
        PostgresPairingCodeStoreTest.dataSource().connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        sql(checkNotNull(javaClass.getResource("/db/prototypes/anonymous-identity-store-v5.sql")).readText())
    }
    @AfterEach fun cleanup() { PostgresPairingCodeStoreTest.dataSource().connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }
    private fun store() = PostgresAnonymousIdentityStore(PostgresPairingCodeStoreTest.dataSource(schema))
    private fun sql(value: String) { source.connection.use { it.createStatement().use { s -> s.execute(value) } } }
    private fun scalar(value: String) = source.connection.use { it.createStatement().use { s -> s.executeQuery(value).use { r -> check(r.next()); r.getString(1) } } }
    @Test fun `identity survives repository reconstruction with same role and exact updated display name`() {
        val first = store().getOrCreate("fixture-installation", "虚构用户", UserRole.ELDER)
        val restored = store().getOrCreate("fixture-installation", "更新后的虚构用户", UserRole.ELDER)
        assertEquals(first.id, restored.id); assertEquals("更新后的虚构用户", restored.displayName)
        assertEquals(restored, store().findById(first.id)); assertNull(store().findById("missing"))
        assertEquals("1", scalar("SELECT count(*) FROM anonymous_identity"))
    }
    @Test fun `role cannot change and rejected update preserves stored name and role`() {
        val first = store().getOrCreate("fixture-installation", "Original", UserRole.ELDER)
        val conflict = assertThrows(ApiException::class.java) { store().getOrCreate("fixture-installation", "Changed", UserRole.CAREGIVER) }
        assertEquals("INSTALLATION_ROLE_CONFLICT", conflict.code); assertEquals(first, store().findById(first.id))
    }
    private fun concurrent(work: (Int) -> Any): List<Any> {
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        try { val futures = (0..7).map { i -> pool.submit<Any> { start.await(); work(i) } }; start.countDown(); return futures.map { it.get(10, TimeUnit.SECONDS) } }
        finally { start.countDown(); pool.shutdownNow() }
    }
    @Test fun `eight independent connections restore exactly one identity`() {
        val results = concurrent { store().getOrCreate("fixture-installation", "Fixture", UserRole.ELDER) }.map { it as AnonymousUser }
        assertEquals(1, results.map { it.id }.distinct().size); assertEquals("1", scalar("SELECT count(*) FROM anonymous_identity"))
    }
    @Test fun `concurrent conflicting roles choose one fixed role and never overwrite it`() {
        val results = concurrent { i -> try { store().getOrCreate("fixture-installation", "Fixture", if (i % 2 == 0) UserRole.ELDER else UserRole.CAREGIVER) } catch (failure: ApiException) { failure.code } }
        val accepted = results.filterIsInstance<AnonymousUser>()
        assertEquals(4, accepted.size); assertEquals(1, accepted.map { it.role }.distinct().size); assertEquals(4, results.count { it == "INSTALLATION_ROLE_CONFLICT" })
    }
    @Test fun `deferred commit failure rolls back first registration before safe retry`() {
        sql("""CREATE FUNCTION fail_identity() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'PRIVATE identity failure'; END ${'$'}${'$'}""")
        sql("CREATE CONSTRAINT TRIGGER fail_identity AFTER INSERT ON anonymous_identity DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_identity()")
        val failure = assertThrows(IdentityStoreUnavailable::class.java) { store().getOrCreate("fixture-installation", "Fixture", UserRole.ELDER) }
        assertFalse(failure.toString().contains("PRIVATE")); assertNull(failure.cause); assertEquals("0", scalar("SELECT count(*) FROM anonymous_identity"))
        sql("DROP TRIGGER fail_identity ON anonymous_identity"); assertNotNull(store().getOrCreate("fixture-installation", "Fixture", UserRole.ELDER))
    }
    @Test fun `deferred update failure preserves original profile and stable identity`() {
        val first = store().getOrCreate("fixture-installation", "Original", UserRole.CAREGIVER)
        sql("""CREATE FUNCTION fail_identity_update() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'PRIVATE identity failure'; END ${'$'}${'$'}""")
        sql("CREATE CONSTRAINT TRIGGER fail_identity_update AFTER UPDATE ON anonymous_identity DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_identity_update()")
        assertThrows(IdentityStoreUnavailable::class.java) { store().getOrCreate("fixture-installation", "Changed", UserRole.CAREGIVER) }; assertEquals(first, store().findById(first.id))
    }
    @Test fun `SQL fixed role uniqueness and safe lookup failure are enforced`() {
        store().getOrCreate("fixture-installation", "Fixture", UserRole.ELDER)
        assertEquals("23505", assertThrows(SQLException::class.java) { sql("INSERT INTO anonymous_identity SELECT 'another-id', installation_id, display_name, role FROM anonymous_identity") }.sqlState)
        assertEquals("23514", assertThrows(SQLException::class.java) { sql("UPDATE anonymous_identity SET role = 'ADMIN'") }.sqlState)
        sql("ALTER TABLE anonymous_identity RENAME TO unavailable_identity")
        val failure = assertThrows(IdentityStoreUnavailable::class.java) { store().findById("missing") }; assertFalse(failure.toString().contains(schema)); assertNull(failure.cause)
    }
    @Test fun `actual database restart preserves identity and fixed role`() {
        val first = store().getOrCreate("fixture-installation", "Fixture", UserRole.ELDER)
        fun control(action: String) {
            val child = ProcessBuilder(PostgresPairingCodeStoreTest.required("S2_PG_PYTHON"), PostgresPairingCodeStoreTest.required("S2_PG_MANAGER_SCRIPT"), action).redirectErrorStream(true).start()
            try { assertTrue(child.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, child.exitValue()) } finally { if (child.isAlive) child.destroyForcibly() }
        }
        try { control("stop") } finally { control("start") }
        assertEquals(first, store().getOrCreate("fixture-installation", "Fixture", UserRole.ELDER))
        assertThrows(ApiException::class.java) { store().getOrCreate("fixture-installation", "Fixture", UserRole.CAREGIVER) }
    }
}
