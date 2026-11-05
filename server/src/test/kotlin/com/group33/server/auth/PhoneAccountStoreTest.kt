package com.group33.server.auth

import com.group33.shared.model.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class PhoneAccountStoreTest {
    @Test
    fun `ticket is single use and account survives store recreation with fixed role`() {
        val url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        fun store() = PhoneAccountStore(url, "sa", "", "a-32-byte-test-key-for-phone-hashing")
        val first = store()
        val ticket = first.newRegistrationTicket("13800000000")
        val account = first.register(ticket, UserRole.CAREGIVER, "Demo")
        assertNotNull(account)
        assertNull(first.register(ticket, UserRole.ELDER, null))
        val second = store()
        assertEquals(account, second.findByPhone("13800000000"))
        val laterTicket = second.newRegistrationTicket("13800000000")
        assertEquals(account, second.register(laterTicket, UserRole.ELDER, null))
    }
}
