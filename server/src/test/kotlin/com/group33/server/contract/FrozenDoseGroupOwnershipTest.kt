package com.group33.server.contract

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.proposal.FrozenDoseGroup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/** Actual caller-alias/decoded-storage regressions, not persistence or live transport evidence. */
@SpringBootTest
class FrozenDoseGroupOwnershipTest(@Autowired private val mapper: ObjectMapper) {
    private val payload = """{"doseGroupId":"00000000-0000-4000-8000-000000000024","elderId":"fictional-elder","executionScopeId":"fictional-env/install/elder","plannedAtEpochMillis":1792454400000,"members":[{"generationId":"00000000-0000-4000-8000-000000000100","occurrence":{"planId":"plan-1","localDate":"2026-10-20","localTime":"08:00"},"planRevision":1,"generatedAtEpochMillis":1792450800000,"executionTimeZoneId":"Asia/Shanghai","plannedAtEpochMillis":1792454400000,"resolvedOffsetSeconds":28800,"resolutionPolicyVersion":"P0_TIME_V1","sourceSnapshotVersion":"confirmed-before-generation","sourceSnapshotServerAtEpochMillis":1792368000000}]}"""
    private fun seed() = ContractProtocol.json.decodeFromString<FrozenDoseGroup>(payload)
    private fun members(): MutableList<OccurrenceGenerationEvidence> {
        val first = seed().members.single()
        return mutableListOf(first, first.copy(
            generationId = "00000000-0000-4000-8000-000000000132",
            occurrence = first.occurrence.copy(planId = "fictional-second-plan"),
        ))
    }
    private fun encode(group: FrozenDoseGroup) = ContractProtocol.json.encodeToString(group)
    private fun unmodifiable(group: FrozenDoseGroup) {
        val before = encode(group)
        val mutations = listOf<() -> Unit>(
            { (group.members as MutableList).clear() },
            { (group.members as MutableList).reverse() },
            { (group.members.subList(0, 1) as MutableList).clear() },
            { val iterator = (group.members as MutableList).iterator(); iterator.next(); iterator.remove() },
        )
        for (mutation in mutations) {
            assertThrows(UnsupportedOperationException::class.java) { mutation() }
            assertEquals(before, encode(group))
        }
    }
    @Test fun `constructor owns members after caller clears original list`() {
        val source = members()
        val template = seed()
        val frozen = FrozenDoseGroup(template.doseGroupId, template.elderId, template.executionScopeId, template.plannedAtEpochMillis, source)
        val before = encode(frozen); val hash = frozen.hashCode()
        source.clear()
        assertEquals(before, encode(frozen))
        assertEquals(hash, frozen.hashCode())
        assertEquals(2, frozen.members.size)
    }
    @Test fun `copy owns supplied ordered members after reorder replacement and clear`() {
        val original = seed(); val source = members()
        val frozen = original.copy(members = source)
        val before = encode(frozen); val order = frozen.members.map { it.occurrence }
        source.reverse(); source[0] = source[0].copy(planRevision = source[0].planRevision + 1); source.clear()
        assertEquals(before, encode(frozen))
        assertEquals(order, frozen.members.map { it.occurrence })
        assertEquals(frozen, frozen.copy())
        assertEquals(original, original.copy())
    }
    @Test fun `getter sublist and iterator never expose mutable member storage`() {
        unmodifiable(seed().copy(members = members()))
    }
    @Test fun `kotlinx decode preserves original wire and immutable value semantics`() {
        val original = seed().copy(members = members())
        val decoded = ContractProtocol.json.decodeFromString<FrozenDoseGroup>(encode(original))
        assertEquals(original, decoded); assertEquals(original.hashCode(), decoded.hashCode())
        assertEquals(encode(original), encode(decoded))
        unmodifiable(decoded)
    }
    @Test fun `actual Boot Jackson decode and copy keep owned immutable ordered storage`() {
        val original = seed().copy(members = members())
        val decoded = mapper.readValue<FrozenDoseGroup>(encode(original))
        assertEquals(original, decoded)
        assertEquals(ContractProtocol.json.parseToJsonElement(encode(original)), ContractProtocol.json.parseToJsonElement(mapper.writeValueAsString(decoded)))
        unmodifiable(decoded)
        val caller = decoded.members.toMutableList(); val copied = decoded.copy(members = caller)
        val before = encode(copied); caller.reverse(); caller.clear()
        assertEquals(before, encode(copied))
    }
}
