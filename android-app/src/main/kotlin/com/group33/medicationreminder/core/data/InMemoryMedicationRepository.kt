package com.group33.medicationreminder.core.data

import com.group33.shared.model.MedicationSchedule
import com.group33.shared.model.LegacyScheduleRules
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryMedicationRepository : MedicationRepository {
    private val mutableSchedules = MutableStateFlow<List<MedicationSchedule>>(emptyList())

    override fun observeSchedulesForCaregiver(caregiverId: String): Flow<List<MedicationSchedule>> =
        mutableSchedules.map { schedules ->
            schedules.filter { it.caregiverId == caregiverId }
        }.distinctUntilChanged()

    override fun observeSchedulesForElderOn(
        elderId: String,
        date: LocalDate,
    ): Flow<List<MedicationSchedule>> = mutableSchedules.map { schedules ->
        schedules.filter { it.elderId == elderId && LegacyScheduleRules.occursOn(it, date) }
    }.distinctUntilChanged()

    override suspend fun saveSchedule(schedule: MedicationSchedule) {
        require(LegacyScheduleRules.validate(schedule).isEmpty()) { "Invalid development reminder plan" }
        mutableSchedules.update { current ->
            current.filterNot { it.id == schedule.id } + schedule
        }
    }
}

