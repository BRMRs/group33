package com.group33.shared.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiErrorSerializationTest {
    @Test
    fun `structured errors round trip with canonical fields and explicit null reason`() {
        val error = ApiError(
            "VALIDATION_FAILED", "Reminder plan fields are invalid", false, "request-1",
            listOf(FieldError("startDate", "INVALID_DATE")),
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(error)
        assertEquals(error, json.decodeFromString<ApiError>(encoded))
        assertEquals(
            setOf("code", "message", "retryable", "requestId", "fieldErrors", "reason"),
            json.parseToJsonElement(encoded).jsonObject.keys,
        )
    }
}
