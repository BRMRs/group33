package com.group33.shared.contract.proposal

import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.UUID

private val zoneWireName = Regex("[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*")

/** PROPOSED #15 wire only. Report IDs are canonical 36-character lowercase UUID text.
 * Zone validation here is representation-only: a receiver may have an older tzdb than the sender.
 * Server report acceptance must separately validate its own registry and authenticated source.
 */
@Serializable
data class ExecutionZoneReport(
    val reportId: String, val observationSequence: Long,
    val ianaTimeZoneId: String, val observedAtEpochMillis: Long,
) {
    init {
        require(UUID.fromString(reportId).toString() == reportId)
        require(observationSequence > 0 && observedAtEpochMillis >= 0)
        require(ianaTimeZoneId.length in 1..255 && zoneWireName.matches(ianaTimeZoneId))
        require(ianaTimeZoneId.split('/').none { it == "." || it == ".." })
    }
}

/** Source is an opaque authorized execution-binding identity, not physical device attestation. */
@Serializable
data class TrustedExecutionZone(
    val elderId: String, val executionSourceId: String, val report: ExecutionZoneReport,
    val serverReceivedAtEpochMillis: Long,
) { init { require(elderId.isNotBlank() && executionSourceId.isNotBlank() && serverReceivedAtEpochMillis >= 0) } }

/** Acknowledges the original canonical lowercase report UUID only; never replaces current context. */
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

/** A local consumption view, not a replacement for the original Server report stored for sync.
 * No local date may be calculated unless the resulting context is AVAILABLE. The original wire
 * remains intact so a registry update can make it consumable later. Never guess a fallback zone.
 */
fun ExecutionZoneContext.forLocalRuntime(
    recognizesZone: (String) -> Boolean = { it in ZoneId.getAvailableZoneIds() },
): ExecutionZoneContext {
    if (availability == ExecutionZoneAvailability.UNAVAILABLE) return this
    val zone = checkNotNull(current).report.ianaTimeZoneId
    return if (runCatching { recognizesZone(zone) }.getOrDefault(false)) this
        else copy(availability = ExecutionZoneAvailability.UNAVAILABLE, current = null)
}
