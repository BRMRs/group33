package com.group33.server.web

import com.group33.server.ScheduleStore
import com.group33.server.ScheduleOwnershipConflict
import com.group33.server.auth.CareRelationshipAccess
import com.group33.shared.model.LegacyScheduleRules
import com.group33.shared.model.FieldError
import com.group33.shared.model.MedicationSchedule
import com.group33.shared.model.UserRole
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/schedules")
class ScheduleController(
    private val scheduleStore: ScheduleStore,
    private val relationships: CareRelationshipAccess,
) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): List<MedicationSchedule> = when (jwt.role()) {
        UserRole.CAREGIVER -> scheduleStore.all().filter {
            relationships.isActive(jwt.subject, it.elderId)
        }
        UserRole.ELDER -> scheduleStore.all().filter { it.elderId == jwt.subject }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody schedule: MedicationSchedule,
    ): MedicationSchedule {
        if (jwt.role() != UserRole.CAREGIVER) {
            throw ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED", "Only caregivers can create reminder plans")
        }
        if (!relationships.isActive(jwt.subject, schedule.elderId)) {
            throw inaccessible()
        }
        if (schedule.caregiverId != jwt.subject) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Reminder plan fields are invalid",
                listOf(FieldError("caregiverId", "AUTHENTICATED_USER_MISMATCH")),
            )
        }
        val errors = LegacyScheduleRules.validate(schedule)
        if (errors.isNotEmpty()) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Reminder plan fields are invalid", errors,
            )
        }
        return try {
            scheduleStore.save(schedule)
        } catch (_: ScheduleOwnershipConflict) {
            throw inaccessible()
        }
    }

    private fun Jwt.role(): UserRole = runCatching {
        UserRole.valueOf(getClaimAsString("role"))
    }.getOrElse {
        throw ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED", "Token contains an unsupported role")
    }

    private fun inaccessible() = ApiException(
        HttpStatus.NOT_FOUND, "RESOURCE_NOT_ACCESSIBLE", "Resource is not accessible",
    )
}
