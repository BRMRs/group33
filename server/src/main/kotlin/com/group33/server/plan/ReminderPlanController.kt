package com.group33.server.plan

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.pairing.VerifiedPairingActorResolver
import com.group33.server.web.ApiException
import com.group33.shared.contract.UpdateReminderPlanRequest
import com.group33.shared.contract.DeactivateReminderPlanRequest
import com.group33.shared.contract.ConfirmReminderPlanRequest
import com.group33.shared.contract.ItemsResponse
import com.group33.shared.model.FieldError
import com.group33.shared.model.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

/** Existing plan wire routes only. Authenticated actors and resource IDs never come from the body. */
@RestController
@ConditionalOnProperty(name = ["app.plan.enabled"], havingValue = "true")
@ConditionalOnProperty(name = ["app.bound-phone.enabled"], havingValue = "false", matchIfMissing = true)
internal class ReminderPlanController(private val actors: VerifiedPairingActorResolver,
    private val service: ReminderPlanConfirmationService, private val mutations: ReminderPlanMutationService, mapper: ObjectMapper) {
    private val wire = PlanHttpSupport(mapper)

    @PostMapping("/api/v1/elders/{elderId}/reminder-plan-confirmations", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun confirm(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        if (actor.role != UserRole.CAREGIVER) return wire.error(
            if (actor.id == elderId) HttpStatus.FORBIDDEN else HttpStatus.NOT_FOUND,
            if (actor.id == elderId) "ROLE_NOT_ALLOWED" else "RESOURCE_NOT_ACCESSIBLE")
        wire.noQuery(request)
        val key = wire.operation(request)
        val input = wire.decode(body, ConfirmReminderPlanRequest::class.java)
        return result(service.confirm(actor, elderId, key, input))
    }

    @PatchMapping("/api/v1/reminder-plans/{planId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(@AuthenticationPrincipal jwt: Jwt, @PathVariable planId: String,
        @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt); wire.noQuery(request)
        val input = wire.decode<UpdateReminderPlanRequest>(body, UpdateReminderPlanRequest::class.java)
        return wire.mutationResult(mutations.update(actor, planId, wire.operation(request), input))
    }

    @PostMapping("/api/v1/reminder-plans/{planId}/deactivate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deactivate(@AuthenticationPrincipal jwt: Jwt, @PathVariable planId: String,
        @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt); wire.noQuery(request)
        val input = wire.decode<DeactivateReminderPlanRequest>(body, DeactivateReminderPlanRequest::class.java)
        return wire.mutationResult(mutations.deactivate(actor, planId, wire.operation(request), input), true)
    }
    @GetMapping("/api/v1/elders/{elderId}/medications")
    fun medications(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt); wire.noQuery(request)
        return result(service.medications(actor, elderId))
    }

    @GetMapping("/api/v1/elders/{elderId}/reminder-plans")
    fun plans(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt); wire.noQuery(request)
        return result(service.plans(actor, elderId))
    }

    private fun result(value: PlanConfirmationResult): ResponseEntity<*> = when (value) {
        is PlanConfirmationResult.Confirmed -> ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore()).body(value.view)
        is PlanConfirmationResult.Medications -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ItemsResponse(value.items))
        is PlanConfirmationResult.Plans -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value.value)
        is PlanConfirmationResult.Rejected -> when (value.reason) {
            PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE -> wire.error(HttpStatus.NOT_FOUND, value.reason.name)
            PlanConfirmationRejection.ROLE_NOT_ALLOWED -> wire.error(HttpStatus.FORBIDDEN, value.reason.name)
            PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED -> wire.error(HttpStatus.CONFLICT, value.reason.name)
            PlanConfirmationRejection.OPERATION_IN_PROGRESS -> wire.error(HttpStatus.CONFLICT, value.reason.name, true, 1)
            PlanConfirmationRejection.SERVICE_UNAVAILABLE -> wire.error(HttpStatus.SERVICE_UNAVAILABLE, value.reason.name, true)
            PlanConfirmationRejection.VALIDATION_FAILED, PlanConfirmationRejection.OVERLAPPING_PLAN ->
                wire.error(HttpStatus.UNPROCESSABLE_ENTITY, value.reason.name,
                    fields = value.violations.map { FieldError(it.field, it.code) }.takeIf { it.isNotEmpty() })
        }
    }

    @ExceptionHandler(ApiException::class)
    fun invalid(exception: ApiException) = wire.error(exception.status, exception.code)
}
