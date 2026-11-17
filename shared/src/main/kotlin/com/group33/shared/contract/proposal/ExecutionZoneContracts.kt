package com.group33.shared.contract.proposal

import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.UUID

/** PROPOSED #15 wire only. No endpoint, production binding or Room integration is enabled. */
@Serializable
data class ExecutionZoneReport(
    val reportId: String, val observationSequence: Long,
    val ianaTimeZoneId: String, val observedAtEpochMillis: Long,
) {
    init {
        require(UUID.fromString(reportId).toString() == reportId)
        require(observationSequence > 0 && observedAtEpochMillis >= 0)
        require(ianaTimeZoneId in ZoneId.getAvailableZoneIds())
    }
}

/** Source is an opaque authorized execution-binding identity, not physical device attestation. */
@Serializable
data class TrustedExecutionZone(
    val elderId: String, val executionSourceId: String, val report: ExecutionZoneReport,
    val serverReceivedAtEpochMillis: Long,
) { init { require(elderId.isNotBlank() && executionSourceId.isNotBlank() && serverReceivedAtEpochMillis >= 0) } }

/** Acknowledges the original report only; never overwrites a newer current context. */
@Serializable
data class ExecutionZoneReceipt(val reportId: String, val receivedAtEpochMillis: Long) {
    init { require(UUID.fromString(reportId).toString() == reportId && receivedAtEpochMillis >= 0) }
}

@Serializable enum class ExecutionZoneAvailability { AVAILABLE, UNAVAILABLE }
@Serializable
data class ExecutionZoneContext(
    val elderId: String, val availability: ExecutionZoneAvailability,
    val current: TrustedExecutionZone? = null,
) {
    init {
        require(elderId.isNotBlank() && (current == null || current.elderId == elderId))
        require((availability == ExecutionZoneAvailability.AVAILABLE) == (current != null))
    }
}
