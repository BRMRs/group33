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
}
