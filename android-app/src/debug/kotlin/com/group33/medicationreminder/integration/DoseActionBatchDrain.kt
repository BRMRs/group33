package com.group33.medicationreminder.integration

/** Drains every persisted action available at recovery time without changing its identity. */
internal suspend fun <T> drainDoseActionBatches(
    readBatch: suspend () -> List<T>,
    process: suspend (T) -> Unit,
) {
    while (true) {
        val batch = readBatch()
        if (batch.isEmpty()) return
        batch.forEach { process(it) }
    }
}
