package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.MedicationRepository
import com.group33.medicationreminder.core.data.RepositoryProvider
import com.group33.medicationreminder.core.data.DemoPreview
import com.group33.shared.model.MedicationSchedule
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.launch

class CaregiverHomeViewModel(
    private val repository: MedicationRepository = RepositoryProvider.medicationRepository,
    private val clock: Clock = DemoPreview.clock,
) : ViewModel() {
    val schedules = repository.observeSchedulesForCaregiver(DemoPreview.CAREGIVER_ID)

    fun addDemoSchedule() {
        val now = clock.millis()
        viewModelScope.launch {
            repository.saveSchedule(
                MedicationSchedule(
                    id = UUID.randomUUID().toString(),
                    medicationId = "demo-medication",
                    elderId = DemoPreview.ELDER_ID,
                    caregiverId = DemoPreview.CAREGIVER_ID,
                    times = listOf("08:00", "20:00"),
                    startDate = LocalDate.now(clock).toString(),
                    updatedAtEpochMillis = now,
                ),
            )
        }
    }
}

