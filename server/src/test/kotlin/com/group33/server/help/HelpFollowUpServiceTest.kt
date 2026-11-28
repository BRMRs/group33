package com.group33.server.help

import com.group33.server.auth.AnonymousUser
import com.group33.server.occurrence.HelpOperation
import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.TimeResolutionPolicy
import com.group33.shared.contract.CaregiverFollowUpState
import com.group33.shared.contract.CaregiverFollowUpView
import com.group33.shared.contract.HelpRequest
import com.group33.shared.model.UserRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

private inline fun <reified T> typed(value: Any): T {
    assertInstanceOf(T::class.java, value)
    return value as T
}

class HelpFollowUpServiceTest {
    private val elder = AnonymousUser("elder-1", "elder-installation", "Fictional Elder", UserRole.ELDER)
    private val caregiver = AnonymousUser("caregiver-1", "caregiver-installation", "Fictional Caregiver", UserRole.CAREGIVER)
    private val evidence = OccurrenceGenerationEvidence(
        "00000000-0000-4000-8000-000000000100",
        OccurrenceKey("00000000-0000-4000-8000-000000000200", "2026-11-28", "20:00"),
        1, 1_795_875_000_000, "Asia/Shanghai", 1_795_875_600_000, 28_800,
        TimeResolutionPolicy.P0_TIME_V1, "confirmed-v1", 1_795_874_000_000,
    )
    private val intent = HelpRequest(
        "00000000-0000-4000-8000-000000000300", elder.id, "durable-group-id",
        listOf(evidence.generationId), 1_795_875_600_001,
    )

    @Test fun `duplicate Help returns the original receipt and one pending follow-up`() {
        val store = FakeHelpStore(caregiver.id, elder.id)
        val service = HelpFollowUpService(store, Clock.fixed(Instant.ofEpochMilli(1_795_875_600_010), ZoneOffset.UTC))
        val operationId = UUID.fromString(intent.actionId)

        val first = typed<HelpResult.Accepted>(service.accept(elder, operationId, intent))
        val duplicate = typed<HelpResult.Accepted>(service.accept(elder, operationId, intent))
        assertEquals(first.receipt, duplicate.receipt)
        assertEquals(1, store.operations.size)

        val read = typed<HelpResult.FollowUps>(service.followUps(caregiver, elder.id))
        assertEquals(1, read.items.size)
        assertEquals(CaregiverFollowUpState.NEEDS_CONTACT, read.items.single().state)
        assertEquals(listOf(evidence.generationId), read.items.single().occurrenceIds)
        assertEquals(intent.occurredAtEpochMillis, read.items.single().occurredAtEpochMillis)
    }

    @Test fun `same action id with changed payload is rejected without replacing evidence`() {
        val store = FakeHelpStore(caregiver.id, elder.id)
        val service = HelpFollowUpService(store)
        val operationId = UUID.fromString(intent.actionId)
        typed<HelpResult.Accepted>(service.accept(elder, operationId, intent))

        val result = service.accept(elder, operationId, intent.copy(occurredAtEpochMillis = intent.occurredAtEpochMillis + 1))
        assertEquals(HelpRejection.IDEMPOTENCY_KEY_REUSED, typed<HelpResult.Rejected>(result).reason)
        assertEquals(intent, store.operations.getValue(operationId).request)
    }

    @Test fun `missing pairing never creates a follow-up`() {
        val operationId = UUID.fromString(intent.actionId)
        val unpaired = FakeHelpStore(null, elder.id)
        assertEquals(HelpRejection.RESOURCE_NOT_ACCESSIBLE,
            typed<HelpResult.Rejected>(HelpFollowUpService(unpaired).accept(elder, operationId, intent)).reason)
        assertEquals(0, unpaired.operations.size)

    }

    @Test fun `only the currently paired caregiver can read pending follow-ups`() {
        val store = FakeHelpStore(caregiver.id, elder.id)
        val service = HelpFollowUpService(store)
        typed<HelpResult.Accepted>(service.accept(elder, UUID.fromString(intent.actionId), intent))
        val stranger = caregiver.copy(id = "caregiver-2")
        assertEquals(HelpRejection.RESOURCE_NOT_ACCESSIBLE,
            typed<HelpResult.Rejected>(service.followUps(stranger, elder.id)).reason)
        assertEquals(HelpRejection.ROLE_NOT_ALLOWED,
            typed<HelpResult.Rejected>(service.followUps(elder, elder.id)).reason)
    }

    private class FakeHelpStore(
        private val caregiverId: String?,
        private val elderId: String,
    ) : HelpStore {
        val operations = linkedMapOf<UUID, HelpOperation>()
        override fun <T> transaction(work: (HelpTransaction) -> T): T = work(object : HelpTransaction {
            override fun activeCaregiver(elderId: String) = caregiverId.takeIf { elderId == this@FakeHelpStore.elderId }
            override fun operation(actionId: UUID) = operations[actionId]
            override fun insert(operation: HelpOperation) {
                check(operations.putIfAbsent(UUID.fromString(operation.request.actionId), operation) == null)
            }
            override fun followUps(elderId: String): List<CaregiverFollowUpView> = operations.values
                .map { it.followUp }.filter { it.elderId == elderId }
        })
    }
}
