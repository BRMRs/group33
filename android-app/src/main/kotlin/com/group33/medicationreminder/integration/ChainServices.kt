package com.group33.medicationreminder.integration

import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.flow.StateFlow

/** Interfaces supplied by the authenticated Application host; no login implementation or credentials. */
internal data class ChainServices(
    val auth: AuthRepository, val pairing: PairingRepository, val photos: MedicationPhotoRepository,
    val plans: ReminderPlanRepository, val commands: OnlineCommandRepository,
    val scope: StateFlow<RepositoryScope?>, val trusted: StateFlow<TrustedSessionContext?>,
    val signOut: suspend () -> Outcome<Unit>,
)
