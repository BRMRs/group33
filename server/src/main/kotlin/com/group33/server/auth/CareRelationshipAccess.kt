package com.group33.server.auth

import org.springframework.stereotype.Component

fun interface CareRelationshipAccess {
    fun isActive(caregiverId: String, elderId: String): Boolean
}

/** No accepted pairing exists in this scaffold. Replace only with verified relationship lookup. */
@Component
class UnpairedCareRelationshipAccess : CareRelationshipAccess {
    override fun isActive(caregiverId: String, elderId: String): Boolean = false
}
