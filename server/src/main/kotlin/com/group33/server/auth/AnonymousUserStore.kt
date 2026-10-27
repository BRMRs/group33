package com.group33.server.auth

import com.group33.shared.model.UserRole
import org.springframework.stereotype.Repository

/** Public seam preserves the existing login/controller API while runtime selects its actual persistence. */
interface AnonymousIdentityStore {
    fun findById(id: String): AnonymousUser?
    fun getOrCreate(installationId: String, displayName: String, role: UserRole): AnonymousUser
}

class IdentityStoreUnavailable : RuntimeException("Identity store is temporarily unavailable")

@Repository
class AnonymousUserStore(private val identities: AnonymousIdentityStore) {
    fun findById(id: String) = identities.findById(id)
    fun getOrCreate(installationId: String, displayName: String, role: UserRole) =
        identities.getOrCreate(installationId, displayName, role)
}
