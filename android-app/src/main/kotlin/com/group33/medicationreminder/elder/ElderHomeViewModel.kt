package com.group33.medicationreminder.elder

import androidx.lifecycle.ViewModel
import com.group33.medicationreminder.core.data.MedicationRepository
import com.group33.medicationreminder.core.data.RepositoryProvider
import com.group33.medicationreminder.core.data.DemoPreview
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

class ElderHomeViewModel(
    repository: MedicationRepository = RepositoryProvider.medicationRepository,
    clock: Clock = DemoPreview.clock,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val schedules = flow {
        while (true) {
            emit(LocalDate.now(clock))
            delay(60_000)
        }
    }.distinctUntilChanged().flatMapLatest { date ->
        repository.observeSchedulesForElderOn(DemoPreview.ELDER_ID, date)
    }
}

