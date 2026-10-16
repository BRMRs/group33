package com.group33.medicationreminder.core.data

object RepositoryProvider {
    val medicationRepository: MedicationRepository by lazy {
        InMemoryMedicationRepository()
    }
}

