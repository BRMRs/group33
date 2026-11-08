package com.group33.server.pairing

import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.test.web.servlet.MockMvc

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
internal class PairingDirectoryHttpTest(@Autowired mvc: MockMvc) {
    private val http = PairingHttpFixture(mvc)
    private fun requests(s: PairingHttpFixture.Session, suffix: String = "") = http.json.decodeFromString<ItemsResponse<PairingRequestView>>(
        http.call(s, "GET", "/api/v1/pairing-requests$suffix", null).also { assertEquals(200, it.status); http.noStore(it) }.contentAsString).items
    private fun relationships(s: PairingHttpFixture.Session) = http.json.decodeFromString<ItemsResponse<CareRelationshipView>>(
        http.call(s, "GET", "/api/v1/care-relationships", null).also { assertEquals(200, it.status); http.noStore(it) }.contentAsString).items
    @Test fun `empty lists and terminal participant filtering use existing Shared wrapper`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val outsider = http.login(UserRole.ELDER)
        assertTrue(requests(e).isEmpty()); assertTrue(relationships(c).isEmpty())
        val p = http.pending(e, c)
        assertEquals(p, requests(c, "?status=PENDING").single())
        val r = http.relationship(http.call(e, "POST", "/api/v1/pairing-requests/${p.id}/accept"))
        http.relationship(http.call(c, "POST", "/api/v1/care-relationships/${r.id}/revoke"))
        assertEquals(PairingRequestStatus.ACCEPTED, requests(e).single().status)
        assertEquals(CareRelationshipStatus.REVOKED, relationships(c).single().status)
        assertTrue(requests(outsider).isEmpty()); assertTrue(relationships(outsider).isEmpty())
    }
    @Test fun `only the supported single status filter is accepted`() {
        val e = http.login(UserRole.ELDER)
        for (query in listOf("?status=", "?status=pending", "?status=UNKNOWN", "?status=PENDING&status=ACCEPTED", "?elderId=other"))
            http.failure(http.call(e, "GET", "/api/v1/pairing-requests$query", null), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "GET", "/api/v1/care-relationships?status=ACTIVE", null), 400, "MALFORMED_REQUEST")
        assertTrue(requests(e, "?status=EXPIRED").isEmpty())
    }
    @Test fun `collection routes require actual authentication`() {
        val e = http.login(UserRole.ELDER)
        for (route in listOf("/api/v1/pairing-requests", "/api/v1/care-relationships")) {
            http.failure(http.call(e, "GET", route, null, token = null), 401, "UNAUTHENTICATED")
            http.failure(http.call(e, "GET", route, null, token = "invalid"), 401, "UNAUTHENTICATED")
        }
    }
    @Test fun `own code DELETE closes pending and replays empty 204 without consuming replacement`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val code = http.issue(e)
        val p = http.request(http.submit(c, code.code), 201); val op = UUID.randomUUID().toString()
        val route = "/api/v1/pairing-codes/${code.id}"
        val deleted = http.call(e, "DELETE", route, null, op); assertEquals(204, deleted.status); assertEquals("", deleted.contentAsString); http.noStore(deleted)
        assertEquals(PairingClosedReason.ELDER_CANCELLED, requests(c).single().closedReason)
        http.failure(http.call(e, "POST", "/api/v1/pairing-requests/${p.id}/accept"), 409, "PAIRING_REQUEST_CLOSED")
        val next = http.issue(e)
        assertEquals(204, http.call(e, "DELETE", route, "{}", op).status)
        http.request(http.submit(c, next.code), 201)
    }
    @Test fun `foreign caller and Caregiver cannot remove an Elders code`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val other = http.login(UserRole.ELDER); val code = http.issue(e)
        http.failure(http.call(other, "DELETE", "/api/v1/pairing-codes/${code.id}", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(other, "DELETE", "/api/v1/pairing-codes/missing", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(c, "DELETE", "/api/v1/pairing-codes/${code.id}", null), 403, "ROLE_NOT_ALLOWED")
        http.request(http.submit(c, code.code), 201)
    }
    @Test fun `strict DELETE header body and query validation preserve current code`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val code = http.issue(e); val route = "/api/v1/pairing-codes/${code.id}"
        for (op in listOf(null, "invalid", "1-1-1-1-1")) http.failure(http.call(e, "DELETE", route, null, op), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "DELETE", route, null, duplicateKey = true), 400, "MALFORMED_REQUEST")
        for (body in listOf("null", "[]", "{\"actorId\":\"spoof\"}", "{} {}")) http.failure(http.call(e, "DELETE", route, body), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "DELETE", "$route?elderId=spoof", null), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "DELETE", route, contentType = "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE")
        http.request(http.submit(c, code.code), 201)
    }
    @Test fun `same deletion key on a newly owned code conflicts and preserves original success`() {
        val e = http.login(UserRole.ELDER); val c = http.login(UserRole.CAREGIVER); val code = http.issue(e); val op = UUID.randomUUID().toString()
        assertEquals(204, http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status)
        val next = http.issue(e)
        http.failure(http.call(e, "DELETE", "/api/v1/pairing-codes/${next.id}", null, op), 409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(204, http.call(e, "DELETE", "/api/v1/pairing-codes/${code.id}", null, op).status)
        http.request(http.submit(c, next.code), 201)
    }
}
