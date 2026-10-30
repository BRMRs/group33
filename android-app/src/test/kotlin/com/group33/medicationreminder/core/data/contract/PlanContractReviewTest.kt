package com.group33.medicationreminder.core.data.contract

import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Date-range input contract only; does not verify plan persistence or event generation. */
class PlanContractReviewTest {
    @Test fun `R01 date range accepts an inclusive leap day`() {
        val range = LocalDateRange("2024-02-29", "2024-02-29")

        assertEquals("2024-02-29", range.fromDate)
        assertEquals("2024-02-29", range.throughDate)
    }

    @Test fun `R01 date range rejects a nonexistent leap day as the end date`() {
        assertThrows(DateTimeParseException::class.java) {
            LocalDateRange("2026-02-28", "2026-02-29")
        }
    }
}
