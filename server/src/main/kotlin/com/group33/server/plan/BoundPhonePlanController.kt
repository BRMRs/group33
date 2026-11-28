package com.group33.server.plan

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.auth.BoundPhoneBearer
import com.group33.server.web.ApiException
import com.group33.shared.contract.ConfirmReminderPlanRequest
import com.group33.shared.contract.DeactivateReminderPlanRequest
import com.group33.shared.contract.UpdateReminderPlanRequest
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@ConditionalOnProperty(name = ["app.bound-phone.enabled", "app.plan.enabled"], havingValue = "true")
internal class BoundPhonePlanController(private val plans: BoundPhonePlanOperations, mapper: ObjectMapper) {
    private val wire = PlanHttpSupport(mapper)

    @PostMapping("/api/v1/elders/{elderId}/reminder-plan-confirmations", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun confirm(@PathVariable elderId: String, @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        wire.noQuery(request)
        val key = wire.operation(request)
        val input = wire.decode(body, ConfirmReminderPlanRequest::class.java)
        return wire.confirmationResult(plans.confirm(BoundPhoneBearer.token(request), elderId, key, input))
    }

    @GetMapping("/api/v1/elders/{elderId}/medications")
    fun medications(@PathVariable elderId: String, request: HttpServletRequest): ResponseEntity<*> {
        wire.noQuery(request)
        return wire.confirmationResult(plans.medications(BoundPhoneBearer.token(request), elderId))
    }

    @GetMapping("/api/v1/elders/{elderId}/reminder-plans")
    fun plans(@PathVariable elderId: String, request: HttpServletRequest): ResponseEntity<*> {
        wire.noQuery(request)
        return wire.confirmationResult(plans.plans(BoundPhoneBearer.token(request), elderId))
    }

    @PatchMapping("/api/v1/reminder-plans/{planId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(@PathVariable planId: String, @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        wire.noQuery(request)
        val input = wire.decode(body, UpdateReminderPlanRequest::class.java)
        return wire.mutationResult(plans.update(BoundPhoneBearer.token(request), planId, wire.operation(request), input))
    }

    @PostMapping("/api/v1/reminder-plans/{planId}/deactivate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deactivate(@PathVariable planId: String, @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        wire.noQuery(request)
        val input = wire.decode(body, DeactivateReminderPlanRequest::class.java)
        return wire.mutationResult(plans.deactivate(BoundPhoneBearer.token(request), planId, wire.operation(request), input), true)
    }

    @ExceptionHandler(ApiException::class)
    fun invalid(exception: ApiException) = wire.error(exception.status, exception.code)
}
