package com.group33.server.auth

import com.group33.shared.model.UserRole
import com.group33.server.web.ApiException
import com.group33.shared.model.FieldError
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Repository
class AnonymousUserStore {
    private val usersByInstallationId = ConcurrentHashMap<String, AnonymousUser>()

    /** Read-only resolution after JWT verification; never creates a user for a missing subject. */
    fun findById(id: String): AnonymousUser? = usersByInstallationId.values.firstOrNull { it.id == id }

    fun getOrCreate(installationId: String, displayName: String, role: UserRole): AnonymousUser =
        usersByInstallationId.compute(installationId) { _, existing ->
            if (existing != null && existing.role != role) {
                throw ApiException(
                    HttpStatus.CONFLICT,
                    "INSTALLATION_ROLE_CONFLICT",
                    "An installation cannot change roles",
                    listOf(FieldError("role", "ROLE_FIXED")),
                )
            }

            existing?.copy(displayName = displayName) ?: AnonymousUser(
                id = UUID.randomUUID().toString(),
                installationId = installationId,
                displayName = displayName,
                role = role,
            )
        }!!
}
