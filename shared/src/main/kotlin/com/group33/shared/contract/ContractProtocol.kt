package com.group33.shared.contract

import kotlinx.serialization.json.Json

/** Wire configuration, NOT the canonical-payload hashing/validation algorithm. */
object ContractProtocol {
    const val BASELINE = "T01-T07/2026-10-16-r1"
    const val CANONICALIZATION_VERSION = "P0_CANONICAL_V1"
    const val IDEMPOTENCY_HEADER = "Idempotency-Key"

    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        coerceInputValues = false
    }
}
