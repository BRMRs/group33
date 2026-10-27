package com.group33.server.pairing

import com.group33.server.auth.CareRelationshipAccess
import com.group33.server.auth.UnpairedCareRelationshipAccess
import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.test.web.servlet.MockMvc

/** No overrides: default local HTTP beans share the actual in-memory Store. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
internal class PairingLifecycleProductionWiringTest(
    @Autowired mvc: MockMvc,
    @Autowired private val store: PairingDecisionStore,
    @Autowired private val access: CareRelationshipAccess,
) {
    private val http = PairingHttpFixture(mvc)
    @Test fun `default local configuration shares issuance consumption and decisions without enabling medical access`() {
        assertInstanceOf(InMemoryPairingCodeStore::class.java, store)
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER)
        val p = http.pending(e, c)
        assertEquals(PairingRequestStatus.PENDING, p.status)
        val r = http.relationship(http.call(e, "POST", "/api/v1/pairing-requests/${p.id}/accept"))
        assertTrue(StoredCareRelationshipAccess(store).isActive(c.id, e.id))
        assertInstanceOf(UnpairedCareRelationshipAccess::class.java, access)
        assertFalse(access.isActive(c.id, e.id))
        val revoked = http.relationship(http.call(c, "POST", "/api/v1/care-relationships/${r.id}/revoke"))
        assertEquals(CareRelationshipStatus.REVOKED, revoked.status)
        assertFalse(StoredCareRelationshipAccess(store).isActive(c.id, e.id))
    }
}
