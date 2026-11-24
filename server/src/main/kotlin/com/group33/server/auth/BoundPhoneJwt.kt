package com.group33.server.auth

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.group33.shared.model.UserRole
import java.time.Clock
import java.util.Date
import java.util.UUID

/** Explicit profile. Use a dedicated deployment key and an exact issuer/audience. */
internal class BoundPhoneJwt(
    key: ByteArray,
    private val issuer: String,
    val audience: String,
    private val clock: Clock,
    val allowance: Long = 60,
) {
    private val signer = MACSigner(key.copyOf())
    private val verifier = MACVerifier(key.copyOf(), emptySet())

    init {
        require(issuer.isNotBlank() && audience.isNotBlank() && audience.length <= 128)
        require(allowance in 0..60)
    }

    fun issue(claims: BoundPhoneSessionClaims): String {
        require(claims.profile == audience && claims.authVersion == 1)
        val fields = JWTClaimsSet.Builder().issuer(issuer).audience(audience)
            .subject(claims.userId.toString()).jwtID(claims.sessionId.toString())
            .issueTime(Date(claims.issuedAtSeconds * 1000)).expirationTime(Date(claims.expiresAtSeconds * 1000))
            .claim("type", "phone").claim("authVersion", 1).claim("installationId", claims.installationId.toString())
            .claim("role", claims.role.name).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).type(TYPE).build(), fields).also { it.sign(signer) }.serialize()
    }

    /** Signature, profile, claim types and time all pass before any caller reaches a database. */
    fun validate(token: String): BoundPhoneSessionClaims? {
        if (token.length !in 1..4096) return null
        return try {
            val jwt = SignedJWT.parse(token)
            if (jwt.header.algorithm != JWSAlgorithm.HS256 || jwt.header.type != TYPE ||
                jwt.header.includedParams != setOf("alg", "typ") || !jwt.verify(verifier)) return null
            val header = json.readTree(jwt.parsedParts[0].decode())
            if (header.fieldNames().asSequence().toSet() != setOf("alg", "typ")) return null
            val fields = json.readTree(jwt.payload.toBytes())
            if (!fields.isObject || fields.fieldNames().asSequence().toSet() != CLAIMS || fields.text("iss") != issuer ||
                fields.text("aud") != audience || fields.text("type") != "phone" || fields.integer("authVersion") != 1L) return null
            val issued = fields.integer("iat") ?: return null
            val expires = fields.integer("exp") ?: return null
            val now = clock.instant().epochSecond
            if (issued !in 0..MAX_SECONDS || expires !in 1..MAX_SECONDS || expires <= issued ||
                issued > now + allowance || expires <= now - allowance) return null
            BoundPhoneSessionClaims(
                canonicalPhoneUuid(fields.text("sub")) ?: return null,
                canonicalPhoneUuid(fields.text("installationId")) ?: return null,
                canonicalPhoneUuid(fields.text("jti")) ?: return null,
                UserRole.valueOf(fields.text("role") ?: return null), audience, issued, expires,
            )
        } catch (_: Exception) { null }
    }

    private fun JsonNode.text(name: String) = get(name)?.takeIf { it.isTextual }?.textValue()
    private fun JsonNode.integer(name: String) = get(name)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

    private companion object {
        val TYPE = JOSEObjectType("group33-phone+jwt")
        val CLAIMS = setOf("iss", "aud", "sub", "jti", "iat", "exp", "type", "authVersion", "installationId", "role")
        val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        const val MAX_SECONDS = 253402300799L
    }
}

internal fun canonicalPhoneUuid(value: String?): UUID? = try {
    if (value?.length != 36) null else UUID.fromString(value).takeIf { it.toString() == value }
} catch (_: IllegalArgumentException) { null }
