package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.model.UserRole

/**
 * Session identity issued by the authentication/session adapter, never by a screen.
 * `generation` must change whenever the authenticated session or its scope changes.
 */
data class TrustedSessionContext(
    val scope: RepositoryScope,
    val generation: Long,
) {
    init { require(generation > 0) }
}

/** The only account/elder scope C2/C3 should pass to their feature ViewModels/Repositories. */
data class CaregiverElderContext(
    val scope: RepositoryScope,
    val sessionGeneration: Long,
    val relationshipId: String,
    val elderId: String,
    val elderDisplayName: String,
) {
    /** Use before applying an async result so a previous Elder/session cannot repopulate the UI. */
    fun isCurrentFor(current: CaregiverElderContext): Boolean =
        scope == current.scope &&
            sessionGeneration == current.sessionGeneration &&
            relationshipId == current.relationshipId &&
            elderId == current.elderId
}

data class AvailableElder(
    val relationshipId: String,
    val elderId: String,
    val displayName: String,
)

sealed interface CaregiverContextState {
    data object SignedOut : CaregiverContextState
    data object RestoringSession : CaregiverContextState
    data object LoadingRelationships : CaregiverContextState
    data object NoActiveElder : CaregiverContextState
    data class SelectElder(val elders: List<AvailableElder>) : CaregiverContextState
    data class Active(val context: CaregiverElderContext) : CaregiverContextState
}

/** Pure mapping: it cannot grant server access; the Repository still authorizes every request. */
object CaregiverContextResolver {
    fun resolve(
        session: SessionView,
        trustedSession: TrustedSessionContext?,
        relationships: Cached<List<CareRelationshipView>>?,
        selectedRelationshipId: String?,
    ): CaregiverContextState {
        if (session.readiness == AuthReadiness.SIGNED_OUT) return CaregiverContextState.SignedOut
        if (session.readiness == AuthReadiness.RESTORING) return CaregiverContextState.RestoringSession

        val user = session.user ?: return CaregiverContextState.RestoringSession
        val trusted = trustedSession ?: return CaregiverContextState.RestoringSession
        if (user.role != UserRole.CAREGIVER || trusted.scope.userId != user.id) {
            return CaregiverContextState.SignedOut
        }

        val cached = relationships ?: return CaregiverContextState.LoadingRelationships
        if (cached.access != AccessState.AUTHORIZED) return CaregiverContextState.LoadingRelationships

        val elders = cached.value.asSequence()
            .filter {
                it.status == CareRelationshipStatus.ACTIVE &&
                    it.caregiverId == user.id &&
                    it.caregiver.id == user.id &&
                    it.caregiver.role == UserRole.CAREGIVER &&
                    it.elder.role == UserRole.ELDER &&
                    it.elderId == it.elder.id
            }
            .distinctBy { it.id }
            .map { AvailableElder(it.id, it.elderId, it.elder.displayName) }
            .toList()

        if (elders.isEmpty()) return CaregiverContextState.NoActiveElder
        val selected = elders.singleOrNull { it.relationshipId == selectedRelationshipId }
            ?: return CaregiverContextState.SelectElder(elders)
        return CaregiverContextState.Active(
            CaregiverElderContext(
                scope = trusted.scope,
                sessionGeneration = trusted.generation,
                relationshipId = selected.relationshipId,
                elderId = selected.elderId,
                elderDisplayName = selected.displayName,
            ),
        )
    }
}
