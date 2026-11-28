package com.group33.server.help

import com.group33.server.pairing.PairingHttpFixture
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc

@SpringBootTest(properties = [
    "app.photo.enabled=true", "app.photo.root=/tmp/group33-help-http",
    "app.plan.enabled=true", "app.occurrence.enabled=true", "app.help.enabled=true",
])
@AutoConfigureMockMvc
class HelpFollowUpHttpTest(@Autowired mvc: MockMvc) {
    private val http = PairingHttpFixture(mvc)
    private val json = ContractProtocol.json

    @Test fun `duplicate Help returns one accepted receipt and one caregiver work item`() {
        val elder = http.login(UserRole.ELDER)
        val caregiver = http.login(UserRole.CAREGIVER)
        val pending = http.pending(elder, caregiver)
        http.relationship(http.call(elder, "POST", "/api/v1/pairing-requests/${pending.id}/accept"))
        val actionId = UUID.randomUUID().toString()
        val request = HelpRequest(actionId, elder.id, "durable-group",
            listOf(UUID.randomUUID().toString()), 1_795_875_600_001)
        val body = json.encodeToString(HelpRequest.serializer(), request)

        val firstResponse = http.call(elder, "POST", "/api/v1/help", body, actionId)
        assertEquals(200, firstResponse.status)
        val first = json.decodeFromString<HelpReceipt>(firstResponse.contentAsString)
        assertEquals(HelpActionSyncState.ACCEPTED, first.status)
        val duplicate = http.call(elder, "POST", "/api/v1/help", body, actionId)
        assertEquals(first, json.decodeFromString<HelpReceipt>(duplicate.contentAsString))

        val read = http.call(caregiver, "GET", "/api/v1/elders/${elder.id}/caregiver-follow-ups", null, null)
        assertEquals(200, read.status)
        val items = json.decodeFromString(ItemsResponse.serializer(CaregiverFollowUpView.serializer()),
            read.contentAsString).items
        assertEquals(1, items.size)
        assertEquals(CaregiverFollowUpState.NEEDS_CONTACT, items.single().state)
        assertEquals(request.occurredAtEpochMillis, items.single().occurredAtEpochMillis)

        val changed = json.encodeToString(HelpRequest.serializer(),
            request.copy(occurredAtEpochMillis = request.occurredAtEpochMillis + 1))
        http.failure(http.call(elder, "POST", "/api/v1/help", changed, actionId),
            409, "IDEMPOTENCY_KEY_REUSED")
    }

    @Test fun `unpaired and forged Help requests are rejected without a work item`() {
        val elder = http.login(UserRole.ELDER)
        val caregiver = http.login(UserRole.CAREGIVER)
        val actionId = UUID.randomUUID().toString()
        val request = HelpRequest(actionId, elder.id, "durable-group",
            listOf(UUID.randomUUID().toString()), 1_795_875_600_001)
        http.failure(http.call(elder, "POST", "/api/v1/help",
            json.encodeToString(HelpRequest.serializer(), request), actionId),
            404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(caregiver, "POST", "/api/v1/help",
            json.encodeToString(HelpRequest.serializer(), request.copy(elderId = caregiver.id)), actionId),
            403, "ROLE_NOT_ALLOWED")
        http.failure(http.call(elder, "POST", "/api/v1/help",
            json.encodeToString(HelpRequest.serializer(), request).dropLast(1) + ",\"actorId\":\"forged\"}", actionId),
            400, "MALFORMED_REQUEST")
    }
}
