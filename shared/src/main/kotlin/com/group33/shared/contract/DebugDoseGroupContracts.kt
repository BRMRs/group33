package com.group33.shared.contract

import kotlinx.serialization.Serializable

/** Opt-in integration transport for an Elder's entire local dose group. */
@Serializable
data class DebugDoseGroupRequest(
    val actionId: String,
    val occurrences: List<OccurrenceKey>,
    val kind: DoseActionKind,
    val recordedAtEpochMillis: Long,
)

@Serializable
data class DebugDoseGroupReceipt(val actionId: String, val receivedAtEpochMillis: Long, val events: List<DoseEventView>)
