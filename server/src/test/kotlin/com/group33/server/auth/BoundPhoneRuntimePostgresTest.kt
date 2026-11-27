package com.group33.server.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.MedicationReminderServerApplication
import com.group33.server.config.PersistenceRuntime
import com.group33.server.pairing.*
import com.group33.server.plan.*
import com.group33.server.photo.*
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
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
        execute("GRANT DELETE ON pairing_active_code, pairing_issuance_time, pairing_request_attempt TO $runtime")
        if (scalar("SELECT count(*) FROM pg_class WHERE relnamespace='$schema'::regnamespace AND relname='phone_bound_registration_tickets'") == "1")
            execute("GRANT DELETE ON phone_bound_registration_tickets TO $runtime")
        if (scalar("SELECT count(*) FROM pg_class WHERE relnamespace='$schema'::regnamespace AND relname='server_principal'") == "1")
            execute("REVOKE INSERT,UPDATE ON server_principal FROM $runtime")
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
        val protectedRequest = HttpRequest.newBuilder(URI("http://127.0.0.1:${second.webServer.port}/api/v1/elders/${claims.userId}/medication-photos"))
            .header("Authorization", "Bearer invalid").GET().build()
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
    @ParameterizedTest @ValueSource(strings = ["missing-registry", "registry-write", "registry-public-column", "registry-client-read",
        "function-execute", "function-public-execute", "function-path", "trigger-disabled", "projection-drift", "dose-reference", "registry-maintain", "cascading-reference", "runtime-all-data", "runtime-all-write"])
    fun `registry integrity privilege and trigger drift reject startup without repairing history`(kind: String) {
        migrate(); grants()
        when(kind) {
            "missing-registry" -> execute("DROP TABLE server_principal CASCADE")
            "registry-write" -> execute("GRANT INSERT ON server_principal TO $runtime")
            "registry-public-column" -> execute("GRANT SELECT(id) ON server_principal TO PUBLIC")
            "registry-client-read" -> execute("GRANT SELECT ON server_principal TO anon")
            "function-execute" -> execute("GRANT EXECUTE ON FUNCTION server_principal_sync() TO $runtime")
            "function-public-execute" -> execute("GRANT EXECUTE ON FUNCTION server_principal_sync() TO PUBLIC")
            "function-path" -> execute("ALTER FUNCTION server_principal_sync() SET search_path TO public,pg_catalog")
            "trigger-disabled" -> execute("ALTER TABLE anonymous_identity DISABLE TRIGGER server_principal_sync")
            "projection-drift" -> execute("INSERT INTO server_principal(id,kind,role) VALUES ('fictional-orphan','PHONE','ELDER')")
            "dose-reference" -> execute("ALTER TABLE dose_group_operation DROP CONSTRAINT dose_group_operation_principal")
            "registry-maintain" -> {
                val privilege=if(scalar("SHOW server_version_num").toInt()>=170000) "MAINTAIN" else "TRIGGER"
                execute("GRANT $privilege ON server_principal TO $runtime")
            }
            "runtime-all-data" -> admin("GRANT pg_read_all_data TO $runtime WITH INHERIT FALSE, SET TRUE")
            "runtime-all-write" -> admin("GRANT pg_write_all_data TO $runtime")
            "cascading-reference" -> execute("ALTER TABLE dose_group_operation DROP CONSTRAINT dose_group_operation_principal; " +
                "ALTER TABLE dose_group_operation ADD CONSTRAINT dose_group_operation_principal FOREIGN KEY(elder_id) REFERENCES server_principal(id) ON DELETE CASCADE")
        }
        val history=scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history")
        val failure=assertThrows(Exception::class.java){ start() }
        assertTrue(generateSequence(failure as Throwable){it.cause}.any{
            it.message=="Primary principal registry or private privileges do not match this release"
        })
        assertEquals(0,calls.get())
        assertEquals(history,scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history"))
    }
    private fun doseReceipt(id:String,key:UUID=UUID.randomUUID()) {
        source(runtime).connection.use { c -> c.prepareStatement("""INSERT INTO dose_group_operation
            (elder_id,operation_id,installation_id,request_payload,response_payload) VALUES (?,?,?,?::jsonb,?::jsonb)""").use { q ->
            q.setString(1,id);q.setObject(2,key);q.setString(3,UUID.randomUUID().toString())
            // Structural FK fixture, not a claimed HTTP dose acceptance case.
            q.setString(4,"{\"actionId\":\"$key\",\"occurrences\":[{}]}")
            q.setString(5,"{\"actionId\":\"$key\",\"events\":[{}]}");q.executeUpdate()
        } }
    }
    @Test fun `formal forward migration accepts phone Elder reference without anonymous alias and retains old receipt bytes`() {
        migrate("12");grants()
        val anonymous=PostgresAnonymousIdentityStore(source(runtime)).getOrCreate("fictional-old-elder-install","Fixture",UserRole.ELDER)
        doseReceipt(anonymous.id)
        val phone=BoundPhoneIdentityStore(source(runtime)).transaction { it.registerVerifiedPhone("d".repeat(64),UserRole.ELDER,null) }
        val key=UUID.randomUUID()
        val before=scalar("SELECT (to_jsonb(d)||jsonb_build_object('_xmin',d.xmin::text))::text FROM dose_group_operation d")
        val old=assertThrows(SQLException::class.java){doseReceipt(phone.id,key)}
        assertEquals("23503",old.sqlState)
        assertEquals(1,SchemaMigrations(source(owner),schema).migrate());grants()
        assertEquals(before,scalar("SELECT (to_jsonb(d)||jsonb_build_object('_xmin',d.xmin::text))::text FROM dose_group_operation d WHERE elder_id='${anonymous.id}'"))
        assertEquals("PHONE",scalar("SELECT kind FROM server_principal WHERE id='${phone.id}'"))
        assertEquals("0",scalar("SELECT count(*) FROM anonymous_identity WHERE id='${phone.id}'"))
        doseReceipt(phone.id,key)
        val deletion=assertThrows(SQLException::class.java){execute("DELETE FROM phone_accounts WHERE user_id='${phone.id}'")}
        assertEquals("23503",deletion.sqlState)
        assertEquals("2",scalar("SELECT count(*) FROM dose_group_operation"))
        assertEquals("1",scalar("SELECT count(*) FROM phone_accounts WHERE user_id='${phone.id}'"))
        assertEquals("1",scalar("SELECT count(*) FROM server_principal WHERE id='${phone.id}'"))
        start() // read-only gate accepts a legitimate upgrade, performs no DDL or provider call.
        assertEquals(0,calls.get())
    }
    @Test fun `root lifecycle is atomic immutable and private without merging colliding identities`() {
        migrate();grants()
        val identity=BoundPhoneIdentityStore(source(runtime))
        val phone=identity.transaction {it.registerVerifiedPhone("e".repeat(64),UserRole.ELDER,null)}
        assertEquals("PHONE:ELDER",scalar("SELECT kind||':'||role FROM server_principal WHERE id='${phone.id}'"))
        assertThrows(SQLException::class.java){execute("UPDATE phone_accounts SET role='CAREGIVER' WHERE user_id='${phone.id}'")}
        assertThrows(SQLException::class.java){execute("UPDATE phone_accounts SET user_id='${UUID.randomUUID()}' WHERE user_id='${phone.id}'")}
        assertThrows(SQLException::class.java){execute("INSERT INTO anonymous_identity VALUES ('${phone.id}','fictional-collision','Fixture','ELDER')")}
        assertEquals("0",scalar("SELECT count(*) FROM anonymous_identity WHERE id='${phone.id}'"))
        source(runtime).connection.use { c ->
            for(sql in listOf("INSERT INTO server_principal VALUES ('fictional-forged','PHONE','ELDER')",
                "UPDATE server_principal SET role='CAREGIVER' WHERE id='${phone.id}'", "DELETE FROM server_principal WHERE id='${phone.id}'")) {
                val failure=assertThrows(SQLException::class.java){c.createStatement().use{it.execute(sql)}}
                assertEquals("42501",failure.sqlState)
            }
            c.createStatement().use { q -> q.executeQuery("SELECT has_function_privilege(current_user,'server_principal_sync()','EXECUTE')").use { r ->
                check(r.next());assertFalse(r.getBoolean(1))
            } }
        }
        execute("DELETE FROM phone_accounts WHERE user_id='${phone.id}'")
        assertEquals("0",scalar("SELECT count(*) FROM server_principal"))
        val anonymous=PostgresAnonymousIdentityStore(source(runtime)).getOrCreate("fictional-name-updates","Original",UserRole.ELDER)
        assertEquals(anonymous.id,PostgresAnonymousIdentityStore(source(runtime)).getOrCreate("fictional-name-updates","Renamed",UserRole.ELDER).id)
        assertEquals("ANONYMOUS:ELDER",scalar("SELECT kind||':'||role FROM server_principal WHERE id='${anonymous.id}'"))
    }
    @Test fun `ambiguous pre registry identities refuse adoption without merging repairing or recording V13`() {
        migrate("12");grants()
        val phone=BoundPhoneIdentityStore(source(runtime)).transaction {it.registerVerifiedPhone("a".repeat(64),UserRole.ELDER,null)}
        execute("INSERT INTO anonymous_identity VALUES ('${phone.id}','fictional-pre-registry-collision','Fixture','ELDER')")
        val history=scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history")
        val before=scalar("SELECT row_to_json(p)::text FROM phone_accounts p")
        val failure=assertThrows(IllegalStateException::class.java){SchemaMigrations(source(owner),schema).migrate()}
        assertNull(failure.cause);assertFalse(failure.toString().contains(phone.id))
        assertEquals(history,scalar("SELECT string_agg(xmin::text,',' ORDER BY installed_rank) FROM group33_schema_history"))
        assertEquals(before,scalar("SELECT row_to_json(p)::text FROM phone_accounts p"))
        assertEquals("1",scalar("SELECT count(*) FROM anonymous_identity WHERE id='${phone.id}'"))
        assertEquals("0",scalar("SELECT count(*) FROM pg_class WHERE relnamespace='$schema'::regnamespace AND relname='server_principal'"))
    }
    @Test fun `simultaneous inserts from different identity namespaces commit only one root and one registry entry`() {
        migrate();grants();val id=UUID.randomUUID().toString();val ready=CountDownLatch(2);val go=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        try {
            val jobs=listOf(false,true).map { phone -> executor.submit<Boolean> {
                source(runtime).connection.use { c ->
                    ready.countDown();check(go.await(5,TimeUnit.SECONDS))
                    try {
                        c.prepareStatement(if(phone) "INSERT INTO phone_accounts(phone_hash,user_id,role,display_name) VALUES (?,?,'ELDER','Fixture')"
                            else "INSERT INTO anonymous_identity(id,installation_id,role,display_name) VALUES (?,?,'ELDER','Fixture')").use { q ->
                            q.setString(1,if(phone) "b".repeat(64) else id);q.setString(2,if(phone) id else "fictional-concurrent-install")
                            q.executeUpdate()
                        };true
                    } catch(failure:SQLException){assertEquals("23505",failure.sqlState);false}
                }
            } }
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown()
            assertEquals(1,jobs.count{it.get(10,TimeUnit.SECONDS)})
            assertEquals("1",scalar("SELECT count(*) FROM server_principal WHERE id='$id'"))
            assertEquals("1",scalar("SELECT (SELECT count(*) FROM anonymous_identity WHERE id='$id')+(SELECT count(*) FROM phone_accounts WHERE user_id='$id')"))
        } finally {go.countDown();executor.shutdownNow()}
    }
    private fun phoneLogin(installation: String, role: UserRole = UserRole.CAREGIVER): JsonNode {
        val verified = verify(installation, attempt(installation))
        return if (verified["status"].asText() == "SIGNED_IN") verified else ok(post("phone/register", mapOf(
            "installationId" to installation, "registrationTicket" to verified["registrationTicket"].asText(),
            "role" to role.name, "displayName" to "Fixture")))
    }
    private fun planProperties() = properties().apply {
        put("app.plan.enabled", "true"); put("app.photo.enabled", "true")
        put("app.photo.root", directory.resolve("objects").toString())
    }
    private data class PlanFixture(val original: ConfirmReminderPlanResponse, val confirmInput: ConfirmReminderPlanRequest,
                                   val edit: UpdateReminderPlanRequest, val caregiverToken: String, val caregiverInstallation: String,
                                   val elderToken: String, val elderClaims: BoundPhoneSessionClaims, val relation: CareRelationshipView)
    private fun seedPhonePlan(): PlanFixture {
        val context = checkNotNull(app); val infra = context.getBean(BoundPhoneInfrastructure::class.java)
        // Administrative fixture setup for the still-closed pairing/photo paths.
        // Actual plan confirmation, read and mutation HTTP below use the application's restricted runtime store.
        val store = PostgresPairingCodeStore(source(owner), PairingReceiptCipher(Files.readAllBytes(directory.resolve("receipt"))))
        val install = UUID.randomUUID().toString(); val caregiver = phoneLogin(install)
        val caregiverClaims = assertNotNullClaims(infra.jwt.validate(caregiver["accessToken"].asText()))
        // Independent phone Elder fixture, no corresponding anonymous row. No claim that its
        // photo/pairing HTTP path is wired yet; plan confirmation, reads and writes below use the actual routes.
        val elder = infra.identity.transaction { tx ->
            val user = tx.registerVerifiedPhone("f".repeat(64), UserRole.ELDER, null)
            val now = infra.clock.instant().epochSecond
            tx.openSession(UUID.fromString(user.id), UUID.randomUUID(), infra.jwt.audience, now, now + 86400)
        }
        val elderSetup = AnonymousUser(elder.user.id, elder.claims.installationId.toString(), elder.user.displayName, UserRole.ELDER)
        val caregiverSetup = AnonymousUser(caregiverClaims.userId.toString(), install, "Fixture", UserRole.CAREGIVER)
        val code = assertInstanceOf(CodeIssuanceResult.Issued::class.java,
            PairingCodeIssuanceService(store, infra.clock).issue(elderSetup, UUID.randomUUID())).view.code
        val pending = assertInstanceOf(PairingRequestResult.Success::class.java,
            PairingRequestService(store, infra.clock).request(caregiverSetup, UUID.randomUUID(), RequestPairingRequest(code), InetAddress.getByName("192.0.2.44"))).view
        val relation = assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(store, infra.clock).accept(elderSetup, pending.id, UUID.randomUUID())).view
        val bytes = ByteArrayOutputStream().use { out -> ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray() }
        val objects = LocalFilePhotoObjectStore(directory.resolve("objects"))
        val photo = assertInstanceOf(PhotoBusinessResult.Uploaded::class.java,
            MedicationPhotoService(store, objects, infra.clock).upload(elderSetup, elder.user.id, UUID.randomUUID(), bytes, "image/png")).view
        val zone = "Asia/Shanghai"; val date = LocalDate.ofInstant(infra.clock.instant(), ZoneId.of(zone)).toString()
        val draft = ReminderPlanDraft(listOf("08:00"), date, zone)
        val acknowledgment = TimeZoneAcknowledgement(zone, false, zone, zone)
        val confirmInput = ConfirmReminderPlanRequest(photo.id, MedicationText("Fixture", "Fixture"), draft, acknowledgment)
        val original = assertInstanceOf(PlanConfirmationResult.Confirmed::class.java,
            ReminderPlanConfirmationService(store, objects, infra.clock).confirm(caregiverSetup, elder.user.id, UUID.randomUUID(), confirmInput)).view
        assertEquals("0", scalar("SELECT count(*) FROM anonymous_identity WHERE id IN ('${elder.user.id}','${caregiverClaims.userId}')"))
        assertEquals("2", scalar("SELECT count(*) FROM server_principal WHERE kind='PHONE'"))
        return PlanFixture(original, confirmInput, UpdateReminderPlanRequest(1, draft.copy(localTimes=listOf("09:00")), acknowledgment),
            caregiver["accessToken"].asText(), install, infra.jwt.issue(elder.claims), elder.claims, relation)
    }
    private fun confirmHttp(elderId: String, token: String, key: UUID, body: String, suffix: String = ""): HttpResponse<String> {
        val path = "/api/v1/elders/$elderId/reminder-plan-confirmations$suffix"
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${checkNotNull(app).webServer.port}$path"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token").header(ContractProtocol.IDEMPOTENCY_HEADER, key.toString())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
    private fun readHttp(elderId: String, resource: String, token: String, suffix: String = ""): HttpResponse<String> {
        val path = "/api/v1/elders/$elderId/$resource$suffix"
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${checkNotNull(app).webServer.port}$path"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $token").GET().build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
    private fun created(response: HttpResponse<String>): JsonNode {
        assertEquals(201, response.statusCode(), response.body())
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"))
        return mapper.readTree(response.body())
    }
    private fun planRequest(fixture: PlanFixture, token: String = fixture.caregiverToken, key: UUID,
                            stop: Boolean = false, body: String = mapper.writeValueAsString(fixture.edit),
                            suffix: String = ""): HttpResponse<String> {
        val path = "/api/v1/reminder-plans/${fixture.original.plan.id}" + (if (stop) "/deactivate" else "") + suffix
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${checkNotNull(app).webServer.port}$path"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token").header(ContractProtocol.IDEMPOTENCY_HEADER, key.toString())
            .method(if (stop) "POST" else "PATCH", HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
    private fun problem(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode(), response.body())
        val error = mapper.readTree(response.body()); assertEquals(code, error["code"].asText())
        assertEquals(error["requestId"].asText(), response.headers().firstValue("X-Request-ID").orElseThrow())
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"))
        if (status == 401) assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow())
    }
    @Test fun `real phone plan routes retain original write and stop receipts through restart and re login`() {
        migrate(); grants(); val values = planProperties(); start(values); val fixture = seedPhonePlan(); val key = UUID.randomUUID()
        val first = ok(planRequest(fixture, key=key)); assertEquals(2, first["plan"]["revision"].asInt())
        app?.close(); app=null; start(values)
        val renewed = phoneLogin(fixture.caregiverInstallation)["accessToken"].asText()
        val replay = ok(planRequest(fixture, token=renewed, key=key)); assertEquals(first, replay)
        val other = phoneLogin(UUID.randomUUID().toString())["accessToken"].asText()
        problem(planRequest(fixture, token=other, key=key),409,"IDEMPOTENCY_KEY_REUSED")
        assertEquals("1",scalar("SELECT count(*) FROM plan_mutation_operation"))
        val stopKey=UUID.randomUUID(); val stopBody=mapper.writeValueAsString(DeactivateReminderPlanRequest(2))
        val stopped=ok(planRequest(fixture,token=renewed,key=stopKey,stop=true,body=stopBody)); assertFalse(stopped["active"].asBoolean())
        assertEquals(stopped,ok(planRequest(fixture,token=renewed,key=stopKey,stop=true,body=stopBody)))
        assertEquals("2",scalar("SELECT count(*) FROM plan_mutation_operation"));assertEquals("3",scalar("SELECT revision FROM reminder_plan"))
    }
    @Test fun `current session and relationship revocation deny actual HTTP effects and original receipt replay`() {
        migrate(); grants(); start(planProperties()); val fixture=seedPhonePlan();val key=UUID.randomUUID()
        ok(planRequest(fixture,key=key))
        val infra=checkNotNull(app).getBean(BoundPhoneInfrastructure::class.java)
        val claims=assertNotNullClaims(infra.jwt.validate(fixture.caregiverToken))
        infra.identity.transaction { it.revokeSession(claims.userId,claims.installationId,claims.sessionId,infra.clock.millis()) }
        problem(planRequest(fixture,key=key),401,"UNAUTHENTICATED")
        problem(planRequest(fixture,key=UUID.randomUUID()),401,"UNAUTHENTICATED")
        val renewed=phoneLogin(fixture.caregiverInstallation)["accessToken"].asText()
        val store=PostgresPairingCodeStore(source(owner),PairingReceiptCipher(Files.readAllBytes(directory.resolve("receipt"))))
        val elder=AnonymousUser(fixture.elderClaims.userId.toString(),fixture.elderClaims.installationId.toString(),"Fixture",UserRole.ELDER)
        assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(store,infra.clock).revoke(elder,fixture.relation.id,UUID.randomUUID()))
        problem(planRequest(fixture,token=renewed,key=key),404,"RESOURCE_NOT_ACCESSIBLE")
        problem(planRequest(fixture,token=renewed,key=UUID.randomUUID()),404,"RESOURCE_NOT_ACCESSIBLE")
        assertEquals("1",scalar("SELECT count(*) FROM plan_mutation_operation"));assertEquals("2",scalar("SELECT revision FROM reminder_plan"))
    }
    @Test fun `phone Elder keeps role on forbidden plan edit and malformed requests do not mutate state`() {
        migrate();grants();start(planProperties());val fixture=seedPhonePlan()
        problem(planRequest(fixture,token=fixture.elderToken,key=UUID.randomUUID()),403,"ROLE_NOT_ALLOWED")
        problem(planRequest(fixture,key=UUID.randomUUID(),body="{} {}"),400,"MALFORMED_REQUEST")
        problem(planRequest(fixture,key=UUID.randomUUID(),suffix="?actor=forged"),400,"MALFORMED_REQUEST")
        problem(planRequest(fixture,token="invalid",key=UUID.randomUUID(),body="invalid"),401,"UNAUTHENTICATED")
        assertEquals("0",scalar("SELECT count(*) FROM plan_mutation_operation"));assertEquals("1",scalar("SELECT revision FROM reminder_plan"))
        assertEquals("ELDER",scalar("SELECT role FROM phone_accounts WHERE user_id='${fixture.elderClaims.userId}'"))
    }
    @Test fun `previous release cannot boot and formal upgrade preserves old identity data`() {
        migrate("11"); grants()
        execute("INSERT INTO anonymous_identity(id,installation_id,display_name,role) VALUES ('fictional-id','fictional-install','Fixture','ELDER')")
        assertThrows(Exception::class.java) { start() }; assertEquals(0, calls.get())
        assertEquals(SchemaMigrations.VERSION.toInt()-11, SchemaMigrations(source(owner), schema).migrate()); grants(); start()
        assertEquals("fictional-id", scalar("SELECT id FROM anonymous_identity"))
        assertEquals(SchemaMigrations.VERSION, scalar("SELECT max(version::int) FROM group33_schema_history"))
    }
    @Test fun `unmanaged phone trial blocks formal upgrade without recording history or partially creating tables`() {
        migrate("11"); execute("CREATE TABLE phone_trial (marker text); INSERT INTO phone_trial VALUES ('preserved')")
        assertThrows(IllegalStateException::class.java) { SchemaMigrations(source(owner), schema).migrate() }
        assertEquals("11", scalar("SELECT max(version::int) FROM group33_schema_history"))
        assertEquals("preserved", scalar("SELECT marker FROM phone_trial"))
        assertEquals("0", scalar("SELECT count(*) FROM pg_class WHERE relnamespace='$schema'::regnamespace AND relname='phone_accounts'"))
    }
    @Test fun `phone confirmation and collection read routes enforce current auth replay and read only snapshots`() {
        migrate(); grants(); val values = planProperties(); start(values); val fixture = seedPhonePlan()
        val elderId = fixture.elderClaims.userId.toString(); val confirmKey = UUID.randomUUID()
        val body = mapper.writeValueAsString(fixture.confirmInput.copy(
            medication = MedicationText("Confirmed By Phone", "1 tablet", "after meal"),
            plan = fixture.confirmInput.plan.copy(localTimes = listOf("18:00", "08:00"))))
        val confirmed = created(confirmHttp(elderId, fixture.caregiverToken, confirmKey, body))
        assertEquals("Confirmed By Phone", confirmed["medication"]["displayName"].asText())
        assertEquals(listOf("08:00", "18:00"), confirmed["plan"]["localTimes"].map { it.asText() })
        app?.close(); app = null; start(values)
        val renewed = phoneLogin(fixture.caregiverInstallation)["accessToken"].asText()
        assertEquals(confirmed, created(confirmHttp(elderId, renewed, confirmKey, body)))
        val otherInstall = phoneLogin(UUID.randomUUID().toString())["accessToken"].asText()
        problem(confirmHttp(elderId, otherInstall, confirmKey, body), 409, "IDEMPOTENCY_KEY_REUSED")
        problem(confirmHttp(elderId, fixture.elderToken, UUID.randomUUID(), body), 403, "ROLE_NOT_ALLOWED")
        for (token in listOf(renewed, fixture.elderToken)) {
            val meds = ok(readHttp(elderId, "medications", token))["items"]
            assertEquals(2, meds.size())
            val plans = ok(readHttp(elderId, "reminder-plans", token))
            assertEquals(2, plans["items"].size())
            assertEquals(2, plans["planRevisions"].size())
        }
        problem(readHttp(elderId, "medications", renewed, "?actorId=forged"), 400, "MALFORMED_REQUEST")
        problem(readHttp(elderId, "reminder-plans", "invalid"), 401, "UNAUTHENTICATED")
        val infra = checkNotNull(app).getBean(BoundPhoneInfrastructure::class.java)
        val store = PostgresPairingCodeStore(source(owner), PairingReceiptCipher(Files.readAllBytes(directory.resolve("receipt"))))
        val elder = AnonymousUser(elderId, fixture.elderClaims.installationId.toString(), "Fixture", UserRole.ELDER)
        assertInstanceOf(PairingDecisionResult.Relationship::class.java,
            PairingDecisionService(store, infra.clock).revoke(elder, fixture.relation.id, UUID.randomUUID()))
        problem(confirmHttp(elderId, renewed, confirmKey, body), 404, "RESOURCE_NOT_ACCESSIBLE")
        problem(readHttp(elderId, "medications", renewed), 404, "RESOURCE_NOT_ACCESSIBLE")
        problem(readHttp(elderId, "reminder-plans", renewed), 404, "RESOURCE_NOT_ACCESSIBLE")
        assertEquals(2, ok(readHttp(elderId, "medications", fixture.elderToken))["items"].size())
        infra.identity.transaction { it.revokeSession(fixture.elderClaims.userId, fixture.elderClaims.installationId, fixture.elderClaims.sessionId, infra.clock.millis()) }
        problem(readHttp(elderId, "medications", fixture.elderToken), 401, "UNAUTHENTICATED")
        problem(readHttp(elderId, "reminder-plans", fixture.elderToken), 401, "UNAUTHENTICATED")
    }
    @Test fun `full phone pairing photo plan occurrence and dose group HTTP journey works end to end without anonymous rows`() {
        migrate(); grants()
        val values = planProperties().apply {
            put("app.occurrence.enabled", "true")
            put("app.dose-group.enabled", "true")
        }
        start(values)
        val port = checkNotNull(app).webServer.port
        val infra = checkNotNull(app).getBean(BoundPhoneInfrastructure::class.java)
        val caregiverInstall = UUID.randomUUID().toString()
        val caregiverToken = phoneLogin(caregiverInstall, UserRole.CAREGIVER)["accessToken"].asText()
        val caregiverClaims = assertNotNullClaims(infra.jwt.validate(caregiverToken))
        val elderSession = infra.identity.transaction { tx ->
            val user = tx.registerVerifiedPhone("9".repeat(64), UserRole.ELDER, null)
            val now = infra.clock.instant().epochSecond
            tx.openSession(UUID.fromString(user.id), UUID.randomUUID(), infra.jwt.audience, now, now + 86400)
        }
        val elderId = elderSession.user.id
        val elderToken = infra.jwt.issue(elderSession.claims)

        fun httpCall(method: String, path: String, token: String, key: UUID? = null, body: String? = null,
                     forwarded: String? = "203.0.113.9"): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $token")
            if (key != null) builder.header(ContractProtocol.IDEMPOTENCY_HEADER, key.toString())
            if (forwarded != null) builder.header("X-Forwarded-For", forwarded)
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }

        // 1. Pairing code issuance & deletion
        val firstCodeKey = UUID.randomUUID()
        val firstCode = created(httpCall("POST", "/api/v1/pairing-codes", elderToken, firstCodeKey, "{}"))
        assertEquals(firstCode, created(httpCall("POST", "/api/v1/pairing-codes", elderToken, firstCodeKey, "{}")))
        problem(httpCall("POST", "/api/v1/pairing-codes", caregiverToken, UUID.randomUUID(), "{}"), 403, "ROLE_NOT_ALLOWED")
        val deleteCodeKey = UUID.randomUUID()
        assertEquals(204, httpCall("DELETE", "/api/v1/pairing-codes/${firstCode["id"].asText()}", elderToken, deleteCodeKey).statusCode())
        assertEquals(204, httpCall("DELETE", "/api/v1/pairing-codes/${firstCode["id"].asText()}", elderToken, deleteCodeKey).statusCode())

        // 2. Issue second pairing code, request pairing, inspect directory, accept relationship
        val secondCode = created(httpCall("POST", "/api/v1/pairing-codes", elderToken, UUID.randomUUID(), "{}"))
        val reqKey = UUID.randomUUID()
        val reqBody = mapper.writeValueAsString(mapOf("code" to secondCode["code"].asText()))
        val pendingReq = created(httpCall("POST", "/api/v1/pairing-requests", caregiverToken, reqKey, reqBody))
        assertEquals(pendingReq, created(httpCall("POST", "/api/v1/pairing-requests", caregiverToken, reqKey, reqBody)))
        assertEquals(pendingReq["id"].asText(), ok(httpCall("GET", "/api/v1/pairing-requests/${pendingReq["id"].asText()}", elderToken))["id"].asText())
        assertEquals(1, ok(httpCall("GET", "/api/v1/pairing-requests?status=PENDING", elderToken))["items"].size())
        val acceptKey = UUID.randomUUID()
        val relationship = ok(httpCall("POST", "/api/v1/pairing-requests/${pendingReq["id"].asText()}/accept", elderToken, acceptKey, "{}"))
        assertEquals(relationship, ok(httpCall("POST", "/api/v1/pairing-requests/${pendingReq["id"].asText()}/accept", elderToken, acceptKey, "{}")))
        assertEquals(1, ok(httpCall("GET", "/api/v1/care-relationships", caregiverToken))["items"].size())

        // 3. Upload medication photo via multipart HTTP, list photos & read photo content
        val pngBytes = ByteArrayOutputStream().use { out -> ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray() }
        val boundary = "phone-multipart-boundary"
        val multipartBody = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"med.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            write(pngBytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        fun uploadPhoto(token: String, targetElder: String, key: UUID): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/elders/$targetElder/medication-photos"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $token")
                .header(ContractProtocol.IDEMPOTENCY_HEADER, key.toString())
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody)).build()
            return http.send(req, HttpResponse.BodyHandlers.ofString())
        }
        val photoKey = UUID.randomUUID()
        val uploadedPhoto = created(uploadPhoto(elderToken, elderId, photoKey))
        assertEquals(uploadedPhoto, created(uploadPhoto(elderToken, elderId, photoKey)))
        problem(uploadPhoto(caregiverToken, caregiverClaims.userId.toString(), UUID.randomUUID()), 403, "ROLE_NOT_ALLOWED")
        val photoId = uploadedPhoto["id"].asText()
        assertEquals(1, ok(httpCall("GET", "/api/v1/elders/$elderId/medication-photos", caregiverToken))["items"].size())
        val contentReq = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/medication-photos/$photoId/content"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $caregiverToken").GET().build()
        val contentRes = http.send(contentReq, HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, contentRes.statusCode())
        assertArrayEquals(pngBytes, contentRes.body())

        // 4. Confirm reminder plan via phone Caregiver
        val zone = "Asia/Shanghai"
        val localDate = LocalDate.ofInstant(infra.clock.instant(), ZoneId.of(zone)).toString()
        val draft = ReminderPlanDraft(listOf("08:00"), localDate, zone)
        val ack = TimeZoneAcknowledgement(zone, false, zone, zone)
        val confirmBody = mapper.writeValueAsString(ConfirmReminderPlanRequest(photoId, MedicationText("Phone Med", "1 pill"), draft, ack))
        val confirmed = created(confirmHttp(elderId, caregiverToken, UUID.randomUUID(), confirmBody))
        val planId = confirmed["plan"]["id"].asText()

        // 5. Register occurrence via phone Elder
        val plannedInstant = infra.clock.instant().minusSeconds(60)
        val plannedTime = LocalDateTime.ofInstant(plannedInstant, ZoneId.of(zone))
        val exactTime = "%02d:%02d".format(plannedTime.hour, plannedTime.minute)
        val exactDate = plannedTime.toLocalDate().toString()
        val editKey = UUID.randomUUID()
        val updatedPlan = ok(httpCall("PATCH", "/api/v1/reminder-plans/$planId", caregiverToken, editKey,
            mapper.writeValueAsString(UpdateReminderPlanRequest(1, ReminderPlanDraft(listOf(exactTime), exactDate, zone), ack))))
        assertEquals(2, updatedPlan["plan"]["revision"].asInt())
        val resolvedInstant = PlanSelfOverlapValidator.resolve(LocalDateTime.parse("${exactDate}T$exactTime"), ZoneId.of(zone))
        val evidence = OccurrenceGenerationEvidence(UUID.randomUUID().toString(),
            OccurrenceKey(planId, exactDate, exactTime), 2,
            resolvedInstant.minusSeconds(300).toEpochMilli(), zone, resolvedInstant.toEpochMilli(),
            resolvedInstant.atZone(ZoneId.of(zone)).offset.totalSeconds, TimeResolutionPolicy.P0_TIME_V1,
            "phone-e2e-v1", resolvedInstant.minusSeconds(600).toEpochMilli())
        val regKey = UUID.randomUUID()
        val regBody = mapper.writeValueAsString(OccurrenceRegistrationRequest(listOf(evidence)))
        val registered = ok(httpCall("POST", "/api/v1/elders/$elderId/occurrence-registrations", elderToken, regKey, regBody))
        assertEquals(registered, ok(httpCall("POST", "/api/v1/elders/$elderId/occurrence-registrations", elderToken, regKey, regBody)))
        problem(httpCall("POST", "/api/v1/elders/$elderId/occurrence-registrations", caregiverToken, UUID.randomUUID(), regBody), 403, "ROLE_NOT_ALLOWED")

        // 6. Record dose action group via phone Elder & read dose events via phone Caregiver and phone Elder
        val doseKey = UUID.randomUUID()
        val doseReq = DebugDoseGroupRequest(doseKey.toString(), listOf(evidence.occurrence), DoseActionKind.TAKEN, resolvedInstant.toEpochMilli() + 1000)
        val doseBody = mapper.writeValueAsString(doseReq)
        val doseReceipt = ok(httpCall("POST", "/api/v1/elders/$elderId/dose-action-groups", elderToken, doseKey, doseBody))
        assertEquals(doseReceipt, ok(httpCall("POST", "/api/v1/elders/$elderId/dose-action-groups", elderToken, doseKey, doseBody)))
        assertEquals("TAKEN", doseReceipt["events"][0]["status"].asText())
        for (readerToken in listOf(elderToken, caregiverToken)) {
            val events = ok(httpCall("GET", "/api/v1/elders/$elderId/dose-events?fromDate=$exactDate&throughDate=$exactDate", readerToken))["items"]
            assertEquals(1, events.size())
            assertEquals("TAKEN", events[0]["status"].asText())
        }

        // 7. Revoke relationship via phone HTTP route and verify immediate Caregiver cutoff across photo, plan and dose-events
        val revokeRes = ok(httpCall("POST", "/api/v1/care-relationships/${relationship["id"].asText()}/revoke", elderToken, UUID.randomUUID(), "{}"))
        assertEquals("REVOKED", revokeRes["status"].asText())
        problem(httpCall("GET", "/api/v1/elders/$elderId/medication-photos", caregiverToken), 404, "RESOURCE_NOT_ACCESSIBLE")
        problem(httpCall("GET", "/api/v1/elders/$elderId/medications", caregiverToken), 404, "RESOURCE_NOT_ACCESSIBLE")
        problem(httpCall("GET", "/api/v1/elders/$elderId/dose-events?fromDate=$exactDate&throughDate=$exactDate", caregiverToken), 404, "RESOURCE_NOT_ACCESSIBLE")
        assertEquals("0", scalar("SELECT count(*) FROM anonymous_identity"))
        assertEquals("2", scalar("SELECT count(*) FROM server_principal WHERE kind='PHONE'"))
    }
}
