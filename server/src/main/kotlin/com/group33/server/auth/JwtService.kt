package com.group33.server.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.stereotype.Service
import java.time.Instant

data class IssuedToken(
    val value: String,
    val expiresInSeconds: Long,
)

@Service
class JwtService(
    private val jwtEncoder: JwtEncoder,
    @Value("\${app.jwt.issuer}") private val issuer: String,
    @Value("\${app.jwt.expires-in-seconds}") private val expiresInSeconds: Long,
) {
    fun issueFor(user: AnonymousUser): IssuedToken {
        return issue(user.id, user.role.name, "anonymous")
    }

    fun issueForPhone(userId: String, role: String): IssuedToken = issue(userId, role, "phone")

    private fun issue(userId: String, role: String, type: String): IssuedToken {
        val issuedAt = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(expiresInSeconds))
            .subject(userId)
            .claim("role", role)
            .claim("type", type)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims))
        return IssuedToken(token.tokenValue, expiresInSeconds)
    }
}
