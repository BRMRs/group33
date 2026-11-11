package com.group33.medicationreminder.integration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseActionBatchDrainTest {
    @Test fun oneRecoveryDrainsTwentySixActionsWithoutChangingTheirIds() = runTest {
        val pending = (1..26).map { "action-$it" }.toMutableList()
        val processed = mutableListOf<String>()

        drainDoseActionBatches(
            readBatch = { pending.take(25) },
            process = { actionId ->
                processed += actionId
                assertTrue(pending.remove(actionId))
            },
        )

        assertTrue(pending.isEmpty())
        assertEquals((1..26).map { "action-$it" }, processed)
    }
}
