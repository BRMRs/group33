package com.group33.server.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.MACSigner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class BoundPhoneJwtTest {
    private val key = ByteArray(32) { 7 }
    private val now = 1_800_000_000L
    private val validator = BoundPhoneJwt(key, "phone-issuer", "phone-audience", Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC))
    private val valid = mapOf("iss" to "phone-issuer", "aud" to "phone-audience", "sub" to UUID.randomUUID().toString(),
        "jti" to UUID.randomUUID().toString(), "installationId" to UUID.randomUUID().toString(), "role" to "ELDER",
        "iat" to now, "exp" to now + 300, "type" to "phone", "authVersion" to 1)
    private val mapper = ObjectMapper()

    @Test fun `only the exact typed signed claim profile is accepted`() {
        assertNotNull(validator.validate(sign(valid)))
        val invalid = mapOf("iss" to "other", "aud" to listOf("phone-audience"), "type" to "anonymous", "role" to "ADMIN",
            "authVersion" to "1", "sub" to "1-1-1-1-1", "installationId" to "bad", "jti" to "bad", "exp" to "1800000300",
            "iat" to now + 61)
        for ((field, value) in invalid) assertNull(validator.validate(sign(valid + (field to value))), field)
        for (field in valid.keys) assertNull(validator.validate(sign(valid - field)), field)
        assertNull(validator.validate(sign(valid + ("unexpected" to 1))))
        assertNull(validator.validate(sign(valid, type = "JWT")))
        assertNull(validator.validate(sign(valid, signingKey = ByteArray(32) { 1 })))
    }

    @Test fun `time profile rejects the expiry boundary and duplicate claims`() {
        assertNotNull(validator.validate(sign(valid + mapOf("iat" to now - 500, "exp" to now - 59))))
        assertNull(validator.validate(sign(valid + mapOf("iat" to now - 500, "exp" to now - 60))))
        val duplicate = mapper.writeValueAsString(valid).dropLast(1) + ",\"authVersion\":1}"
        assertNull(validator.validate(signJson(duplicate, "group33-phone+jwt", key)))
    }

    private fun sign(claims: Map<String, Any>, type: String = "group33-phone+jwt", signingKey: ByteArray = key) =
        signJson(mapper.writeValueAsString(claims), type, signingKey)
    private fun signJson(value: String, type: String, signingKey: ByteArray) =
        JWSObject(JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType(type)).build(), Payload(value))
            .also { it.sign(MACSigner(signingKey)) }.serialize()
}
