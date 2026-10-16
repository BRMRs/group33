package com.group33.server

import com.group33.shared.model.MedicationSchedule
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap

interface ScheduleStore {
    fun all(): List<MedicationSchedule>
    fun save(schedule: MedicationSchedule): MedicationSchedule
}

class ScheduleOwnershipConflict : RuntimeException()

@Repository
class InMemoryScheduleStore : ScheduleStore {
    private val schedules = ConcurrentHashMap<String, MedicationSchedule>()

    override fun all(): List<MedicationSchedule> = schedules.values.sortedBy { it.id }

    override fun save(schedule: MedicationSchedule): MedicationSchedule {
        return schedules.compute(schedule.id) { _, existing ->
            if (existing != null &&
                (existing.elderId != schedule.elderId || existing.caregiverId != schedule.caregiverId)
            ) {
                throw ScheduleOwnershipConflict()
            }
            schedule
        }!!
    }
}
