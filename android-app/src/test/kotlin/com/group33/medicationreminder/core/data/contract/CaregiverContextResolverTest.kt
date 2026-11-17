package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverContextResolverTest {
    private val caregiver = User("caregiver-1", "Caregiver", UserRole.CAREGIVER)
    private val elderA = User("elder-a", "Elder A", UserRole.ELDER)
    private val elderB = User("elder-b", "Elder B", UserRole.ELDER)
    private val scope = RepositoryScope("cloud-demo", "install-1", caregiver.id)
    private val trusted = TrustedSessionContext(scope, generation = 7)
    private val session = SessionView(AuthReadiness.READY, caregiver)

    private fun relationship(id: String, elder: User, status: CareRelationshipStatus = CareRelationshipStatus.ACTIVE) =
        CareRelationshipView(id, elder.id, caregiver.id, elder, caregiver, status, 1L)

    private fun cache(vararg values: CareRelationshipView, access: AccessState = AccessState.AUTHORIZED) =
        Cached(values.toList(), true, 1L, false, access)

    @Test fun `only explicitly selected active relationship becomes current context`() {
        val relationships = cache(relationship("rel-a", elderA), relationship("rel-b", elderB))
        val unselected = CaregiverContextResolver.resolve(session, trusted, relationships, null)
        assertEquals(listOf("rel-a", "rel-b"), (unselected as CaregiverContextState.SelectElder).elders.map { it.relationshipId })

        val selected = CaregiverContextResolver.resolve(session, trusted, relationships, "rel-b") as CaregiverContextState.Active
        assertEquals("elder-b", selected.context.elderId)
        assertEquals("rel-b", selected.context.relationshipId)
        assertEquals(7L, selected.context.sessionGeneration)
        assertTrue(!selected.context.isCurrentFor(selected.context.copy(relationshipId = "rel-a", elderId = "elder-a")))
        assertTrue(!selected.context.isCurrentFor(selected.context.copy(sessionGeneration = 8)))
    }

    @Test fun `revoked pending malformed and unauthorized relationships never become current`() {
        val revoked = relationship("revoked", elderA, CareRelationshipStatus.REVOKED)
        val malformed = relationship("wrong-role", elderB).copy(elder = caregiver)
        assertEquals(CaregiverContextState.NoActiveElder,
            CaregiverContextResolver.resolve(session, trusted, cache(revoked, malformed), "revoked"))
        assertEquals(CaregiverContextState.LoadingRelationships,
            CaregiverContextResolver.resolve(session, trusted, cache(relationship("rel-a", elderA), access = AccessState.UNKNOWN), "rel-a"))
    }

    @Test fun `session mismatch signout and restoration invalidate previous elder`() {
        assertEquals(CaregiverContextState.SignedOut,
            CaregiverContextResolver.resolve(session.copy(readiness = AuthReadiness.SIGNED_OUT), trusted, cache(relationship("rel-a", elderA)), "rel-a"))
        assertEquals(CaregiverContextState.RestoringSession,
            CaregiverContextResolver.resolve(session, null, cache(relationship("rel-a", elderA)), "rel-a"))
        assertEquals(CaregiverContextState.SignedOut,
            CaregiverContextResolver.resolve(session.copy(user = caregiver.copy(id = "other")), trusted, cache(relationship("rel-a", elderA)), "rel-a"))
        assertTrue(TrustedSessionContext(scope, 8).generation != trusted.generation)
    }
}
