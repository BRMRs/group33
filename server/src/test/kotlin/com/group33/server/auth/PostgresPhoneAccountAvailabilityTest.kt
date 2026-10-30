package com.group33.server.auth

import com.group33.shared.model.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real isolated PostgreSQL locks, rather than a mocked statement timeout. */
@Tag("postgres")
class PostgresPhoneAccountAvailabilityTest {
    private val schema = "s2_phone_current_" + UUID.randomUUID().toString().replace("-", "")
    private fun sql(text: String) = CurrentPhoneReadProcess.connection(schema).use { c -> c.createStatement().use { it.execute(text) } }
    private fun count(table: String) = CurrentPhoneReadProcess.connection(schema).use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> check(r.next()); r.getInt(1) } }
    }
    @BeforeEach fun prepare() { CurrentPhoneReadProcess.connection().use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } } }
    @AfterEach fun cleanup() { CurrentPhoneReadProcess.connection().use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } } }

    private fun blocked(table: String, operation: () -> Unit) {
        CurrentPhoneReadProcess.connection(schema).use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE $table IN ACCESS EXCLUSIVE MODE") }
            try {
                val start = System.nanoTime()
                val failure = assertThrows(PhoneAccountStorageUnavailable::class.java, operation)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertTrue(elapsed in 4_000..15_000, "Database lock wait exceeded the tested query budget")
                assertNull(failure.cause)
            } finally { blocker.rollback() }
        }
    }

    @Test fun `login lookup lock is bounded and same account recovers after release`() {
        val store = CurrentPhoneReadProcess.store(schema)
        val user = checkNotNull(store.register(store.newRegistrationTicket("13800000077"), UserRole.CAREGIVER, "Fictional"))
        blocked("phone_accounts") { store.findByPhone("13800000077") }
        assertEquals(user, store.findByPhone("13800000077"))
        assertEquals(1, count("phone_accounts")); assertEquals(0, count("phone_registration_tickets"))
    }

    @Test fun `registration lock after ticket deletion rolls back then same ticket succeeds once`() {
        val store = CurrentPhoneReadProcess.store(schema)
        val ticket = store.newRegistrationTicket("13800000077")
        blocked("phone_accounts") { store.register(ticket, UserRole.CAREGIVER, "Fictional") }
        assertEquals(0, count("phone_accounts")); assertEquals(1, count("phone_registration_tickets"))
        val user = checkNotNull(store.register(ticket, UserRole.CAREGIVER, "Fictional"))
        assertEquals(user, store.findByPhone("13800000077"))
        assertNull(store.register(ticket, UserRole.ELDER, null))
        assertEquals(1, count("phone_accounts")); assertEquals(0, count("phone_registration_tickets"))
    }

    @Test fun `ticket creation lock is bounded and leaves earlier ticket usable`() {
        val store = CurrentPhoneReadProcess.store(schema)
        val original = store.newRegistrationTicket("13800000077")
        blocked("phone_registration_tickets") { store.newRegistrationTicket("13800000078") }
        assertEquals(1, count("phone_registration_tickets")); assertEquals(0, count("phone_accounts"))
        assertNotNull(store.register(original, UserRole.CAREGIVER, "Fictional"))
        val next = store.newRegistrationTicket("13800000078")
        assertNotNull(store.register(next, UserRole.ELDER, null))
        assertEquals(2, count("phone_accounts"))
    }
}
