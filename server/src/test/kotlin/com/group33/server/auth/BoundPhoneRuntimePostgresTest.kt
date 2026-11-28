package com.group33.server.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.MedicationReminderServerApplication
import com.group33.server.config.PersistenceRuntime
import com.group33.server.migration.SchemaMigrations
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.postgresql.ds.PGSimpleDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext

/** Actual Boot/HTTP/primary database composition; synthetic provider is registered only by this test. */
@Tag("postgres")
internal class BoundPhoneRuntimePostgresTest {
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val schema = "phone_runtime_$id"
    private val owner = "phone_owner_$id"
    private val runtime = "phone_app_$id"
    private val password get() = required("S2_PG_INTEGRATION_PASSWORD")
    private val calls = AtomicInteger()
    private val createdClients = mutableListOf<String>()
    private val clientGroup = "phone_client_$id"
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    @TempDir lateinit var directory: Path
    private var app: ServletWebServerApplicationContext? = null
    private fun dataSource(schema: String? = null) = PGSimpleDataSource().apply {
        val url = required("S2_PG_INTEGRATION_URL")
        require(url == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local" ||
            System.getenv("CI") == "true" && url == "jdbc:postgresql://127.0.0.1:5432/group33_phone_fixture")
        setURL(url); user = required("S2_PG_INTEGRATION_USER"); password = required("S2_PG_INTEGRATION_PASSWORD")
        connectTimeout = 5; socketTimeout = 15; schema?.let { currentSchema = it }
    }
    private fun admin(sql: String) = dataSource().connection.use { c -> c.createStatement().use { it.execute(sql) } }
    private fun source(user: String) = dataSource(schema).apply { this.user = user; this.password = this@BoundPhoneRuntimePostgresTest.password }
    private fun execute(sql: String) = source(owner).connection.use { c -> c.createStatement().use { it.execute(sql) } }
    private fun scalar(sql: String): String = source(owner).connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getString(1) } }
    }
    @BeforeEach fun prepare() {
        // All identifiers are generated ASCII. Password uses a parameter, never SQL interpolation or logging.
        dataSource().connection.use { c ->
            c.createStatement().use { it.execute("CREATE ROLE $owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS");
                it.execute("CREATE ROLE $runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS") }
            for (role in listOf(owner, runtime)) c.prepareStatement("SELECT format('ALTER ROLE %I PASSWORD %L', ?, ?)").use { s ->
                s.setString(1, role); s.setString(2, password)
                s.executeQuery().use { r -> check(r.next()); c.createStatement().use { it.execute(r.getString(1)) } }
            }
        }
        val manager = required("S2_PG_INTEGRATION_USER")
        require(manager.matches(Regex("[a-z][a-z0-9_]{0,62}")))
        admin("GRANT $owner TO $manager WITH SET TRUE")
        admin("CREATE SCHEMA $schema AUTHORIZATION $owner")
        for (role in listOf("anon", "authenticated")) {
            val exists = dataSource().connection.use { c -> c.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname=?").use { q ->
                q.setString(1, role); q.executeQuery().use { it.next() }
            } }
            if (!exists) { admin("CREATE ROLE $role NOLOGIN"); createdClients.add(role) }
        }
        admin("CREATE ROLE $clientGroup NOLOGIN")
        for ((name, value) in listOf("jwt" to 1, "phone" to 2, "receipt" to 3)) {
            val path = directory.resolve(name); Files.write(path, ByteArray(32) { value.toByte() })
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }
    @AfterEach fun cleanup() {
        app?.close()
        execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        admin("DROP ROLE IF EXISTS $runtime, $owner, $clientGroup")
        for (role in createdClients) admin("DROP ROLE $role")
    }
    private fun migrate(target: String = SchemaMigrations.VERSION) {
        Flyway.configure().dataSource(source(owner)).defaultSchema(schema).schemas(schema).createSchemas(false)
            .table("group33_schema_history").locations("classpath:db/migration").target(target)
            .baselineOnMigrate(false).cleanDisabled(true).group(true).loggers(*emptyArray<String>()).load().migrate()
    }
    private fun grants() {
        execute("GRANT USAGE ON SCHEMA $schema TO $runtime; GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA $schema TO $runtime")
        if (scalar("SELECT count(*) FROM pg_class WHERE relnamespace='$schema'::regnamespace AND relname='phone_bound_registration_tickets'") == "1")
            execute("GRANT DELETE ON phone_bound_registration_tickets TO $runtime")
    }
    private fun properties() = mutableMapOf(
        "server.address" to "127.0.0.1", "server.port" to "0", "server.forward-headers-strategy" to "none",
        "spring.main.banner-mode" to "off", "logging.level.root" to "OFF", "app.bound-phone.enabled" to "true",
        "app.fusion.enabled" to "false", "app.persistence.mode" to "postgres-local", "app.persistence.require-versioned-schema" to "true",
        "app.persistence.jdbc-url" to required("S2_PG_INTEGRATION_URL"), "app.persistence.schema" to schema,
        "app.persistence.username" to runtime, "app.persistence.password" to password,
        "app.persistence.receipt-key-file" to directory.resolve("receipt").toString(),
        "app.jwt.secret" to "FICTIONAL_LEGACY_SIGNING_KEY_FOR_RUNTIME_TEST_2026",
        "app.bound-phone.jwt-key-file" to directory.resolve("jwt").toString(),
        "app.bound-phone.phone-hash-key-file" to directory.resolve("phone").toString(),
        "app.bound-phone.jwt-issuer" to "https://phone.fixture.invalid", "app.bound-phone.jwt-audience" to "fixture-phone",
        "app.bound-phone.access-key-id" to "FICTIONAL_ID", "app.bound-phone.access-key-secret" to "FICTIONAL_SECRET",
        "app.bound-phone.scheme-code" to "fictional-scheme", "app.bound-phone.android-package" to "com.group33.medicationreminder",
        "app.bound-phone.android-signature" to "ab".repeat(16), "app.bound-phone.trusted-proxy-cidrs" to "127.0.0.1/32")
    private fun start(values: Map<String, String> = properties()): ServletWebServerApplicationContext {
        val application = SpringApplication(MedicationReminderServerApplication::class.java)
        application.setRegisterShutdownHook(false)
        application.addInitializers(ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
            context.beanFactory.registerSingleton("fixtureProvider", object : BoundPhoneProvider {
                override fun grant(): BoundPhoneGrant { calls.incrementAndGet(); return BoundPhoneGrant("fictional-grant", System.currentTimeMillis() + 300_000) }
                override fun verify(proof: String): String { calls.incrementAndGet(); return "+12025550123" }
            })
        })
        return (application.run(*values.map { "--${it.key}=${it.value}" }.toTypedArray()) as ServletWebServerApplicationContext).also { app = it }
    }
    private fun post(route: String, body: Map<String, Any>, forwarded: String? = "203.0.113.7"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${checkNotNull(app).webServer.port}/api/v1/auth/$route"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
        if (forwarded != null) request.header("X-Forwarded-For", forwarded)
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun ok(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow())
        return mapper.readTree(response.body())
    }
    private fun attempt(installation: String): String = ok(post("fusion/token", mapOf("installationId" to installation)))
        .get("authenticationAttemptToken").asText()
    private fun verify(installation: String, capability: String): JsonNode = ok(post("fusion/verify", mapOf(
        "installationId" to installation, "authenticationAttemptToken" to capability, "verifyToken" to UUID.randomUUID().toString())))

    @Test fun `formal migration restricted role actual routes and restart retain the same account attempt and session`() {
        migrate(); grants(); val first = start()
        assertSame(first.getBean(PersistenceRuntime::class.java).primary, first.getBean(BoundPhoneInfrastructure::class.java).primary)
        val install = UUID.randomUUID().toString()
        val ticket = verify(install, attempt(install)); assertEquals("REGISTRATION_REQUIRED", ticket["status"].asText())
        val request = mapOf("installationId" to install, "registrationTicket" to ticket["registrationTicket"].asText(), "role" to "CAREGIVER", "displayName" to "Fixture")
        val signed = ok(post("phone/register", request)); assertEquals("SIGNED_IN", signed["status"].asText())
        val token = signed["accessToken"].asText(); val claims = assertNotNullClaims(first.getBean(BoundPhoneJwt::class.java).validate(token))
        assertEquals(UUID.fromString(install), claims.installationId)
        assertNotEquals(claims.userId, claims.installationId)
        assertEquals(422, post("phone/register", request).statusCode())
        val pending = attempt(install)
        val original = scalar("SELECT string_agg(version,',' ORDER BY installed_rank) FROM group33_schema_history")
        first.close(); app = null; val second = start()
        val retained = assertNotNullClaims(second.getBean(BoundPhoneJwt::class.java).validate(token))
        assertNotNull(second.getBean(BoundPhoneIdentityStore::class.java).transaction {
            it.lockCurrentSession(retained, System.currentTimeMillis() / 1000, 60)
        })
        val login = verify(install, pending)
        assertEquals("SIGNED_IN", login["status"].asText()); assertEquals(signed["user"]["id"], login["user"]["id"])
        assertEquals(original, scalar("SELECT string_agg(version,',' ORDER BY installed_rank) FROM group33_schema_history"))
        assertEquals("1", scalar("SELECT count(*) FROM phone_accounts")); assertEquals("2", scalar("SELECT count(*) FROM phone_auth_sessions"))
        assertEquals(401, post("anonymous", mapOf("installationId" to install, "role" to "ELDER")).statusCode())
        assertEquals(0, second.getBeansOfType(AuthController::class.java).size)
        val protectedRequest = HttpRequest.newBuilder(URI("http://127.0.0.1:${second.webServer.port}/api/v1/elders/${claims.userId}/medications"))
            .header("Authorization", "Bearer $token").GET().build()
        assertEquals(401, http.send(protectedRequest, HttpResponse.BodyHandlers.ofString()).statusCode())
        val health = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${second.webServer.port}/health")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, health.statusCode())
    }
    private fun assertNotNullClaims(claims: BoundPhoneSessionClaims?): BoundPhoneSessionClaims { assertNotNull(claims); return checkNotNull(claims) }

    @Test fun `missing forwarding and exhausted quota cannot call provider or insert another attempt`() {
        migrate(); grants(); start(properties().apply { put("app.bound-phone.requests-per-minute", "1") })
        val body = mapOf("installationId" to UUID.randomUUID().toString())
        assertEquals(400, post("fusion/token", body, null).statusCode()); assertEquals(0, calls.get())
        ok(post("fusion/token", body))
        val denied = post("fusion/token", body, "198.51.100.8, 203.0.113.7")
        assertEquals(429, denied.statusCode()); assertTrue(denied.headers().firstValue("Retry-After").orElseThrow().toLong() in 1..60)
        assertEquals("TOO_MANY_REQUESTS", mapper.readTree(denied.body())["code"].asText())
        assertEquals(1, calls.get()); assertEquals("1", scalar("SELECT count(*) FROM phone_auth_attempts"))
    }
    @ParameterizedTest @ValueSource(strings = ["missing-table", "column", "key", "reference", "public", "owner", "rls", "delete"])
    fun `structural or privilege drift fails before provider use and writes no history`(kind: String) {
        migrate(); grants()
        when (kind) {
            "missing-table" -> execute("DROP TABLE phone_auth_proofs")
            "column" -> execute("ALTER TABLE phone_auth_sessions RENAME COLUMN auth_version TO changed_version")
            "key" -> execute("ALTER TABLE phone_auth_proofs DROP CONSTRAINT phone_auth_proofs_pkey")
            "reference" -> execute("ALTER TABLE phone_auth_sessions DROP CONSTRAINT phone_auth_sessions_user_id_installation_id_fkey")
            "public" -> execute("GRANT SELECT ON phone_accounts TO PUBLIC")
            "owner" -> admin("GRANT $owner TO $runtime")
            "rls" -> execute("ALTER TABLE phone_accounts ENABLE ROW LEVEL SECURITY")
            "delete" -> execute("REVOKE DELETE ON phone_bound_registration_tickets FROM $runtime")
        }
        val before = scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history")
        val failure = assertThrows(Exception::class.java) { start() }
        assertTrue(generateSequence(failure as Throwable) { it.cause }.any { it.message == "Bound phone schema or runtime privileges do not match this release" })
        assertEquals(0, calls.get())
        assertEquals(before, scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history"))
    }
    @Test fun `public column grant cannot bypass backend only schema gate`() {
        migrate(); grants(); execute("GRANT SELECT(phone_hash) ON phone_accounts TO PUBLIC")
        val failure = assertThrows(Exception::class.java) { start() }
        assertTrue(generateSequence(failure as Throwable) { it.cause }.any {
            it.message == "Bound phone schema or runtime privileges do not match this release"
        })
        assertEquals(0, calls.get())
    }
    @ParameterizedTest @ValueSource(strings = ["anon-column", "authenticated-column", "inherited-column", "truncate", "references", "trigger-or-maintain"])
    fun `client direct inherited column and destructive table grants cannot bypass the gate`(kind: String) {
        migrate(); grants()
        when (kind) {
            "anon-column" -> execute("GRANT SELECT(phone_hash) ON phone_accounts TO anon")
            "authenticated-column" -> execute("GRANT UPDATE(phone_hash) ON phone_accounts TO authenticated")
            "inherited-column" -> {
                execute("GRANT SELECT(phone_hash) ON phone_accounts TO $clientGroup")
                admin("GRANT $clientGroup TO anon")
            }
            "truncate" -> execute("GRANT TRUNCATE ON phone_auth_proofs TO anon")
            "references" -> execute("GRANT REFERENCES(phone_hash) ON phone_accounts TO authenticated")
            "trigger-or-maintain" -> {
                val privilege = if (scalar("SHOW server_version_num").toInt() >= 170000) "MAINTAIN" else "TRIGGER"
                execute("GRANT $privilege ON phone_auth_proofs TO authenticated")
            }
        }
        if (kind in setOf("anon-column", "inherited-column")) {
            execute("GRANT USAGE ON SCHEMA $schema TO anon")
            admin("GRANT anon TO ${required("S2_PG_INTEGRATION_USER")} WITH SET TRUE")
            // The client's column read really succeeds, although whole-table SELECT remains false.
            dataSource(schema).connection.use { c -> c.createStatement().use { statement ->
                statement.execute("SET ROLE anon")
                statement.executeQuery("SELECT phone_hash FROM phone_accounts LIMIT 0").close()
                statement.executeQuery("SELECT has_table_privilege(current_user,'phone_accounts','SELECT')").use { r ->
                    check(r.next()); assertFalse(r.getBoolean(1))
                }
            } }
        }
        val before = scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history")
        val failure = assertThrows(Exception::class.java) { start() }
        assertTrue(generateSequence(failure as Throwable) { it.cause }.any {
            it.message == "Bound phone schema or runtime privileges do not match this release"
        })
        assertEquals(0, calls.get())
        assertEquals(before, scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history"))
    }
    @ParameterizedTest @ValueSource(strings = ["pg_read_all_data", "pg_write_all_data", "pg_read_all_data-no-inherit", "pg_write_all_data-no-inherit"])
    fun `predefined all data roles cannot bypass private phone privileges`(case: String) {
        val role = case.removeSuffix("-no-inherit")
        val inherits = !case.endsWith("-no-inherit")
        migrate(); grants(); admin("GRANT $role TO anon WITH INHERIT ${if (inherits) "TRUE" else "FALSE"}, SET TRUE")
        assertEquals(inherits.toString(), scalar("SELECT has_table_privilege('anon','phone_auth_proofs','" +
            (if (role == "pg_read_all_data") "SELECT" else "INSERT") + "')::text"))
        assertEquals("true", scalar("SELECT pg_has_role('anon','$role','SET')::text"))
        val before = scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history")
        val failure = assertThrows(Exception::class.java) { start() }
        assertTrue(generateSequence(failure as Throwable) { it.cause }.any {
            it.message == "Bound phone schema or runtime privileges do not match this release"
        })
        assertEquals(0, calls.get())
        assertEquals(before, scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history"))
    }
    @Test fun `previous release cannot boot and formal upgrade preserves old identity data`() {
        migrate("11"); grants()
        execute("INSERT INTO anonymous_identity(id,installation_id,display_name,role) VALUES ('fictional-id','fictional-install','Fixture','ELDER')")
        assertThrows(Exception::class.java) { start() }; assertEquals(0, calls.get())
        assertEquals(1, SchemaMigrations(source(owner), schema).migrate()); grants(); start()
        assertEquals("fictional-id", scalar("SELECT id FROM anonymous_identity"))
        assertEquals("12", scalar("SELECT max(version::int) FROM group33_schema_history"))
    }
    @Test fun `unmanaged phone trial blocks formal upgrade without recording history or partially creating tables`() {
        migrate("11"); execute("CREATE TABLE phone_trial (marker text); INSERT INTO phone_trial VALUES ('preserved')")
        assertThrows(IllegalStateException::class.java) { SchemaMigrations(source(owner), schema).migrate() }
        assertEquals("11", scalar("SELECT max(version::int) FROM group33_schema_history"))
        assertEquals("preserved", scalar("SELECT marker FROM phone_trial"))
        assertEquals("0", scalar("SELECT count(*) FROM pg_class WHERE relnamespace='$schema'::regnamespace AND relname='phone_accounts'"))
    }
}
