package com.group33.server.auth

import com.group33.server.config.SecurityConfig
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.springframework.security.oauth2.jwt.JwtException

@Tag("postgres")
class PostgresCurrentPhoneAccountTest {
    private val schema = "s2_phone_current_" + UUID.randomUUID().toString().replace("-", "")
    private fun sql(text: String) = CurrentPhoneReadProcess.connection(schema).use { it.createStatement().use { s -> s.execute(text) } }
    private fun scalar(text: String): String? = CurrentPhoneReadProcess.connection(schema).use { c ->
        c.createStatement().use { s -> s.executeQuery(text).use { r -> check(r.next()); r.getString(1) } }
    }
    private fun store(options: String = "") = CurrentPhoneReadProcess.store(schema, options)
    private fun account() = store().let { checkNotNull(it.register(it.newRegistrationTicket("13800000033"), UserRole.CAREGIVER, "Fictional")) }
    private fun credentials() = SecurityConfig().let { config ->
        val key = config.jwtSecretKey("FICTIONAL_CURRENT_PHONE_PG_SIGNING_KEY_2026")
        Triple(config, key, JwtService(config.jwtEncoder(key), "group33-server", 3600))
    }
    @BeforeEach fun prepare() { CurrentPhoneReadProcess.connection().use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } } }
    @AfterEach fun cleanup() { CurrentPhoneReadProcess.connection().use { it.createStatement().use { s -> s.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }

    @Test fun `read by id uses current durable row and leaves tickets and business rows unchanged`() {
        val user = account()
        val before = scalar("SELECT xmin::text || phone_hash || user_id || role || display_name FROM phone_accounts")
        assertEquals(user, store().findByUserId(user.id))
        assertNull(store().findByUserId(UUID.randomUUID().toString()))
        assertEquals(before, scalar("SELECT xmin::text || phone_hash || user_id || role || display_name FROM phone_accounts"))
        assertEquals("0", scalar("SELECT count(*) FROM phone_registration_tickets"))
    }

    @Test fun `uninitialized read never creates account tables or consumes proof`() {
        assertThrows(SQLException::class.java) { store().findByUserId(UUID.randomUUID().toString()) }
        assertEquals("0", scalar("SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace='$schema'::regnamespace"))
    }

    @Test fun `database enforced read only connection still returns current account`() {
        val user = account()
        assertEquals(user, store("&options=-c%20default_transaction_read_only%3Don").findByUserId(user.id))
    }

    @Test fun `same signed token observes current role and deletion instead of caching account authority`() {
        val user = account(); val (config, key, issuer) = credentials()
        val token = issuer.issueForPhone(user.id, user.role.name).value
        val decoder = config.jwtDecoder(key, "group33-server", store())
        assertEquals(user.id, decoder.decode(token).subject)
        sql("UPDATE phone_accounts SET role='ELDER'")
        assertThrows(JwtException::class.java) { decoder.decode(token) }
        sql("UPDATE phone_accounts SET role='CAREGIVER'")
        assertEquals(user.id, decoder.decode(token).subject)
        sql("DELETE FROM phone_accounts")
        assertThrows(JwtException::class.java) { decoder.decode(token) }
    }

    @Test fun `temporary storage failure neither initializes missing table nor permanently invalidates token`() {
        val user = account(); val (config, key, issuer) = credentials()
        val token = issuer.issueForPhone(user.id, user.role.name).value
        val decoder = config.jwtDecoder(key, "group33-server", store())
        sql("ALTER TABLE phone_accounts RENAME TO withheld_current_fixture")
        val failure = assertThrows(PhoneAccountReadUnavailable::class.java) { decoder.decode(token) }
        assertNull(failure.cause)
        assertNull(scalar("SELECT to_regclass('$schema.phone_accounts')::text"))
        sql("ALTER TABLE withheld_current_fixture RENAME TO phone_accounts")
        assertEquals(user.id, decoder.decode(token).subject)
    }

    @Test fun `read lock contention has a real bounded failure and releases resources for same token retry`() {
        val user = account(); val (config, key, issuer) = credentials()
        val token = issuer.issueForPhone(user.id, user.role.name).value
        val decoder = config.jwtDecoder(key, "group33-server", store())
        CurrentPhoneReadProcess.connection(schema).use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE phone_accounts IN ACCESS EXCLUSIVE MODE") }
            try {
                val start = System.nanoTime()
                assertThrows(PhoneAccountReadUnavailable::class.java) { decoder.decode(token) }
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertTrue(elapsed in 4_000..15_000, "Bounded query wait exceeded the tested range")
            } finally { blocker.rollback() }
        }
        assertEquals(user.id, decoder.decode(token).subject)
    }

    @Test fun `independent JVM and actual database restart retain current account lookup`() {
        val user = account()
        for (command in listOf("stop", "start")) {
            val process = ProcessBuilder(CurrentPhoneReadProcess.required("S2_PHONE_PG_PYTHON"), CurrentPhoneReadProcess.required("S2_PHONE_PG_MANAGER"), command)
                .redirectErrorStream(true).start()
            assertTrue(process.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
        }
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            System.getProperty("s2.phone.test.classpath"), CurrentPhoneReadProcess::class.java.name, schema, user.id)
            .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
            assertEquals("${user.id}:${user.role.name}", process.inputStream.bufferedReader().readText().trim())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}

object CurrentPhoneReadProcess {
    fun required(key: String) = requireNotNull(System.getenv(key))
    private fun url() = required("S2_PHONE_PG_URL").also { require(it == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local") }
    fun connection(schema: String? = null) = DriverManager.getConnection(url() + (schema?.let { "?currentSchema=$it" } ?: ""),
        required("S2_PHONE_PG_USER"), required("S2_PHONE_PG_PASSWORD"))
    fun store(schema: String, options: String = ""): PhoneAccountStore {
        require(schema.matches(Regex("s2_phone_current_[a-f0-9]{32}")))
        return PhoneAccountStore(url()+"?currentSchema=$schema"+options, required("S2_PHONE_PG_USER"), required("S2_PHONE_PG_PASSWORD"),
            "FICTIONAL_CURRENT_PHONE_PG_HASH_KEY_2026")
    }
    @JvmStatic fun main(args: Array<String>) {
        val user = checkNotNull(store(args[0]).findByUserId(args[1]))
        println("${user.id}:${user.role.name}")
    }
}
