package com.group33.medicationreminder.caregiver.plan
import org.junit.Assert.*
import org.junit.Test
class OcrCandidatesTest {
    @Test fun preservesLiteralStrengthWithoutInferringDose() {
        assertEquals(listOf("示例药品", "500mg", "Example medicine"), OcrCandidates.lines(" 示例药品 \n500mg\nExample medicine\n500mg"))
    }
    @Test fun blankAndOversizedLinesAreNotCandidates() {
        assertTrue(OcrCandidates.lines(" \n" + "x".repeat(201)).isEmpty())
    }
    @Test fun limitsResultsWithoutTruncatingIndividualCandidates() {
        assertEquals(40, OcrCandidates.lines((1..100).joinToString("\n")).size)
    }
    @Test fun selectsExplicitNameAndRejectsAmbiguity() {
        assertEquals("示例药片", OcrCandidates.name("药品名称：示例药片\n500mg\n生产日期"))
        assertEquals("Example tablets", OcrCandidates.name("Medicine name: Example tablets\n500mg"))
        assertNull(OcrCandidates.name("Drug name: Alpha\nDrug name: Beta"))
        assertNull(OcrCandidates.name("500mg\nTEST IMAGE - NOT MEDICATION"))
        assertEquals("示例胶囊", OcrCandidates.name("示例胶囊\n500mg"))
    }
}
