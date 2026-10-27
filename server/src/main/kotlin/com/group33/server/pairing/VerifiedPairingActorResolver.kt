package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.server.auth.AnonymousUserStore
import com.group33.server.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

/** Called only with Spring Security's decoded/verified principal; request identity fields are never a source. */
@Component
internal class VerifiedPairingActorResolver(private val users: AnonymousUserStore) {
    fun resolve(jwt: Jwt): AnonymousUser {
        val user = jwt.subject?.let(users::findById)
        if (user == null || jwt.claims["type"] != "anonymous" || jwt.claims["role"] != user.role.name) {
            // In-memory identity loss requires session restoration, not reconstructing an identity from claims.
            throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Restore the current session")
        }
        return user
    }
}
