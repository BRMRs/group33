package com.group33.medicationreminder.core.data

import com.group33.shared.model.MedicationSchedule
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface MedicationRepository {
    fun observeSchedulesForCaregiver(caregiverId: String): Flow<List<MedicationSchedule>>

    fun observeSchedulesForElderOn(elderId: String, date: LocalDate): Flow<List<MedicationSchedule>>

    suspend fun saveSchedule(schedule: MedicationSchedule)
}

