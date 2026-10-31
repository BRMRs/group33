package com.group33.server.migration

import com.group33.server.auth.PostgresAnonymousIdentityStore
import com.group33.server.config.PersistenceRuntimeConfiguration
import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.shared.model.UserRole
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("postgres")
class PostgresSchemaMigrationTest {
    private val schema = "s2_migration_it_" + UUID.randomUUID().toString().replace("-", "")
    @TempDir lateinit var directory: Path
    private fun execute(sql: String) = dataSource(schema).connection.use { c -> c.createStatement().use { it.execute(sql) } }
    private fun scalar(sql: String): String? = dataSource(schema).connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getString(1) } }
    }
    private fun runner() = SchemaMigrations(dataSource(schema), schema)
    private fun initialFive() = Flyway.configure().dataSource(dataSource(schema)).defaultSchema(schema).schemas(schema)
        .createSchemas(false).table("group33_schema_history").locations("classpath:db/migration")
        .target("5").baselineOnMigrate(false).cleanDisabled(true).group(true).loggers(*emptyArray<String>()).load().migrate()
    private fun history() = scalar("SELECT string_agg(version, ',' ORDER BY installed_rank) FROM group33_schema_history")
    private fun cipher() = PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE"))))
    private fun seedReceipt(): Pair<com.group33.server.auth.AnonymousUser, Pair<UUID, com.group33.shared.contract.PairingCodeView>> {
        val actor = PostgresAnonymousIdentityStore(dataSource(schema)).getOrCreate("fictional-migration-installation", "迁移测试", UserRole.ELDER)
        val key = UUID.randomUUID()
        val result = PairingCodeIssuanceService(PostgresPairingCodeStore(dataSource(schema), cipher()), Clock.systemUTC(), PairingCodeGenerator { "01234567" }).issue(actor, key)
        return actor to (key to assertInstanceOf(CodeIssuanceResult.Issued::class.java, result).view)
    }

    @BeforeEach fun prepare() {
        dataSource().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
    }
    @AfterEach fun cleanup() {
        dataSource().connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    @Test fun `fresh schema is ordered complete repeatable and read only validation changes no history`() {
        assertEquals(10, runner().migrate())
        assertEquals("1,2,3,4,5,6,7,8,9,10", history())
        val before = scalar("SELECT string_agg(xmin::text, ',' ORDER BY installed_rank) FROM group33_schema_history")
        assertEquals(0, runner().migrate())
        SchemaMigrations(dataSource(schema).apply { options = "-c default_transaction_read_only=on" }, schema).validate()
        assertEquals(before, scalar("SELECT string_agg(xmin::text, ',' ORDER BY installed_rank) FROM group33_schema_history"))
        assertEquals("registered_occurrence", scalar("SELECT 'registered_occurrence'::regclass::text"))
    }

    @Test fun `validation of empty schema does not install any table`() {
        assertThrows(IllegalStateException::class.java) { runner().validate() }
        assertEquals("0", scalar("SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace='$schema'::regnamespace"))
    }

    @Test fun `absent schema is never created implicitly`() {
        execute("DROP SCHEMA $schema")
        assertThrows(IllegalStateException::class.java) { runner().migrate() }
        assertNull(scalar("SELECT to_regnamespace('$schema')::text"))
    }

    @Test fun `unmanaged nonempty schema fails without baseline adoption or changes`() {
        execute("CREATE TABLE foreign_fixture (value text); INSERT INTO foreign_fixture VALUES ('preserved')")
        assertThrows(IllegalStateException::class.java) { runner().migrate() }
        assertEquals("preserved", scalar("SELECT value FROM foreign_fixture"))
        assertNull(scalar("SELECT to_regclass('$schema.group33_schema_history')::text"))
        assertNull(scalar("SELECT to_regclass('$schema.pairing_issuance_guard')::text"))
    }

    @Test fun `even known manual prototypes are not silently marked migrated`() {
        execute(checkNotNull(javaClass.getResource("/db/prototypes/pairing-code-store-v1.sql")).readText())
        assertThrows(IllegalStateException::class.java) { runner().migrate() }
        assertEquals("1", scalar("SELECT count(*) FROM pairing_issuance_guard"))
        assertNull(scalar("SELECT to_regclass('$schema.group33_schema_history')::text"))
    }

    @Test fun `upgrade preserves actual identity encrypted original code and later registration runtime`() {
        initialFive()
        val (actor, request) = seedReceipt()
        val ciphertext = scalar("SELECT encode(receipt_cipher,'hex') FROM pairing_issue_operation")
        assertEquals(5, runner().migrate())
        val retainedActor = PostgresAnonymousIdentityStore(dataSource(schema)).findById(actor.id)
        assertEquals(actor, retainedActor)
        val replay = PairingCodeIssuanceService(PostgresPairingCodeStore(dataSource(schema), cipher()), Clock.systemUTC(), PairingCodeGenerator { error("Original receipt must replay") }).issue(actor, request.first)
        assertEquals(request.second, assertInstanceOf(CodeIssuanceResult.Issued::class.java, replay).view)
        assertEquals(ciphertext, scalar("SELECT encode(receipt_cipher,'hex') FROM pairing_issue_operation"))
        assertNotNull(PersistenceRuntimeConfiguration().create(MockEnvironment()
            .withProperty("app.persistence.mode", "postgres-local")
            .withProperty("app.persistence.jdbc-url", required("S2_PG_INTEGRATION_URL"))
            .withProperty("app.persistence.username", required("S2_PG_INTEGRATION_USER"))
            .withProperty("app.persistence.password", required("S2_PG_INTEGRATION_PASSWORD"))
            .withProperty("app.persistence.schema", schema)
            .withProperty("app.persistence.receipt-key-file", required("S2_PG_RECEIPT_KEY_FILE"))
            .withProperty("app.jwt.secret", "FICTIONAL_STABLE_MIGRATION_TEST_SECRET_2026")
            .withProperty("app.photo.enabled", "true").withProperty("app.plan.enabled", "true")
            .withProperty("app.occurrence.enabled", "true")).pairing)
    }

    @Test fun `actual late history SQL failure rolls back all pending DDL and original data survives retry`() {
        initialFive()
        val (actor, _) = seedReceipt()
        execute("ALTER TABLE group33_schema_history ADD CONSTRAINT migration_failure_fixture CHECK (version <> '9')")
        val failure = assertThrows(IllegalStateException::class.java) { runner().migrate() }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(required("S2_PG_INTEGRATION_PASSWORD")))
        assertEquals("1,2,3,4,5", history())
        assertNull(scalar("SELECT to_regclass('$schema.medication_photo')::text"))
        assertNull(scalar("SELECT to_regclass('$schema.registered_occurrence')::text"))
        assertEquals(actor, PostgresAnonymousIdentityStore(dataSource(schema)).findById(actor.id))
        execute("ALTER TABLE group33_schema_history DROP CONSTRAINT migration_failure_fixture")
        assertEquals(5, runner().migrate())
        runner().validate()
        assertEquals("1", scalar("SELECT count(*) FROM pairing_issue_operation"))
    }

    @Test fun `changed applied checksum is rejected by migrate and validate with no automatic repair`() {
        runner().migrate()
        execute("UPDATE group33_schema_history SET checksum=checksum+1 WHERE version='3'")
        assertThrows(IllegalStateException::class.java) { runner().migrate() }
        assertThrows(IllegalStateException::class.java) { runner().validate() }
        assertEquals("10", scalar("SELECT count(*) FROM group33_schema_history"))
    }

    @Test fun `future release history fails instead of running an older administrative release`() {
        runner().migrate()
        execute("INSERT INTO group33_schema_history VALUES (11,'11','future fixture','SQL','V11__future_fixture.sql',1,current_user,current_timestamp,0,true)")
        assertThrows(IllegalStateException::class.java) { runner().migrate() }
        assertThrows(IllegalStateException::class.java) { runner().validate() }
        assertEquals("11", scalar("SELECT count(*) FROM group33_schema_history"))
    }

    @Test fun `deleted applied history entry fails rather than replaying an old migration`() {
        runner().migrate()
        execute("DELETE FROM group33_schema_history WHERE version='4'")
        assertThrows(IllegalStateException::class.java) { runner().migrate() }
        assertThrows(IllegalStateException::class.java) { runner().validate() }
        assertEquals("9", scalar("SELECT count(*) FROM group33_schema_history"))
    }

    @Test fun `independent concurrent migrators apply each version once`() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (1..4).map { pool.submit<Int> { start.await(); runner().migrate() } }
            start.countDown()
            assertEquals(10, futures.sumOf { it.get(30, TimeUnit.SECONDS) })
            assertEquals("1,2,3,4,5,6,7,8,9,10", history())
            runner().validate()
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private fun child(command: String): Pair<Int, String> {
        val password = directory.resolve("migration-password")
        Files.writeString(password, required("S2_PG_INTEGRATION_PASSWORD"))
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-------"))
        val builder = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            System.getProperty("s2.postgres.test.classpath"), SchemaMigrationCli::class.java.name, command).redirectErrorStream(true)
        builder.environment().putAll(mapOf("GROUP33_MIGRATION_URL" to required("S2_PG_INTEGRATION_URL"),
            "GROUP33_MIGRATION_USER" to required("S2_PG_INTEGRATION_USER"), "GROUP33_MIGRATION_SCHEMA" to schema,
            "GROUP33_MIGRATION_PASSWORD_FILE" to password.toString()))
        val process = builder.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally { if (process.isAlive) process.destroyForcibly(); Files.deleteIfExists(password) }
    }

    @Test fun `real command JVM and database restart preserve original business receipts and history`() {
        assertEquals(0, child("migrate").first)
        val (actor, request) = seedReceipt()
        for (action in listOf("stop", "start")) {
            val process = ProcessBuilder(required("S2_PG_PYTHON"), required("S2_PG_MANAGER_SCRIPT"), action).redirectErrorStream(true).start()
            assertTrue(process.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
        }
        val checked = child("validate")
        assertEquals(0, checked.first, checked.second)
        assertEquals("Schema migration history is current: 10", checked.second.trim())
        assertEquals(actor, PostgresAnonymousIdentityStore(dataSource(schema)).findById(actor.id))
        val replay = PairingCodeIssuanceService(PostgresPairingCodeStore(dataSource(schema), cipher()), Clock.systemUTC(), PairingCodeGenerator { error("No regeneration") }).issue(actor, request.first)
        assertEquals(request.second, assertInstanceOf(CodeIssuanceResult.Issued::class.java, replay).view)
        assertEquals(0, child("migrate").first)
    }
}
