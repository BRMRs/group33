package com.group33.server.plan

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import com.group33.server.pairing.VerifiedPairingActorResolver
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.UpdateReminderPlanRequest
import com.group33.shared.contract.DeactivateReminderPlanRequest
import com.group33.shared.contract.ConfirmReminderPlanRequest
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ItemsResponse
import com.group33.shared.model.ApiError
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
import java.util.Collections
import java.util.UUID

/** Existing plan wire routes only. Authenticated actors and resource IDs never come from the body. */
@RestController
@ConditionalOnProperty(name = ["app.plan.enabled"], havingValue = "true")
internal class ReminderPlanController(private val actors: VerifiedPairingActorResolver,
    private val service: ReminderPlanConfirmationService, private val mutations: ReminderPlanMutationService, mapper: ObjectMapper) {
    // These coercions belong only to this route; never change the shared application mapper.
    private val strictMapper = mapper.copy().apply {
        for (shape in listOf(CoercionInputShape.String, CoercionInputShape.Float, CoercionInputShape.Boolean))
            coercionConfigFor(LogicalType.Integer).setCoercion(shape, CoercionAction.Fail)
        coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
        coercionConfigFor(String::class.java).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
        for (shape in listOf(CoercionInputShape.String, CoercionInputShape.Integer, CoercionInputShape.Float))
            coercionConfigFor(LogicalType.Boolean).setCoercion(shape, CoercionAction.Fail)
    }
    private fun reader(type: Class<*>) = strictMapper.readerFor(type)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    @PostMapping("/api/v1/elders/{elderId}/reminder-plan-confirmations", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun confirm(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt)
        if (actor.role != UserRole.CAREGIVER) return error(
            if (actor.id == elderId) HttpStatus.FORBIDDEN else HttpStatus.NOT_FOUND,
            if (actor.id == elderId) "ROLE_NOT_ALLOWED" else "RESOURCE_NOT_ACCESSIBLE")
        noQuery(request)
        val key = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER))
            .singleOrNull()?.takeIf { UUID_PATTERN.matches(it) } ?: malformed()
        val input: ConfirmReminderPlanRequest = try { reader(ConfirmReminderPlanRequest::class.java).readValue<ConfirmReminderPlanRequest?>(body) ?: malformed() }
            catch (_: JsonProcessingException) { malformed() }
        return result(service.confirm(actor, elderId, UUID.fromString(key), input))
    }

    @PatchMapping("/api/v1/reminder-plans/{planId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(@AuthenticationPrincipal jwt: Jwt, @PathVariable planId: String,
        @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt); noQuery(request)
        val input = decode<UpdateReminderPlanRequest>(body, UpdateReminderPlanRequest::class.java)
        return mutationResult(mutations.update(actor, planId, operation(request), input))
    }

    @PostMapping("/api/v1/reminder-plans/{planId}/deactivate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deactivate(@AuthenticationPrincipal jwt: Jwt, @PathVariable planId: String,
        @RequestBody body: String, request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt); noQuery(request)
        val input = decode<DeactivateReminderPlanRequest>(body, DeactivateReminderPlanRequest::class.java)
        return mutationResult(mutations.deactivate(actor, planId, operation(request), input), true)
    }
    private fun <T> decode(body: String, type: Class<T>): T = try { reader(type).readValue<T?>(body) ?: malformed() }
        catch (_: JsonProcessingException) { malformed() }
    private fun operation(request: HttpServletRequest): UUID = UUID.fromString(Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER))
        .singleOrNull()?.takeIf { UUID_PATTERN.matches(it) } ?: malformed())
    private fun mutationResult(value: PlanMutationResult, deactivate: Boolean = false): ResponseEntity<*> = when (value) {
        is PlanMutationResult.Accepted -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(if (deactivate) value.view.plan else value.view)
        is PlanMutationResult.Rejected -> when (value.reason) {
            PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, value.reason.name)
            PlanMutationRejection.ROLE_NOT_ALLOWED -> error(HttpStatus.FORBIDDEN, value.reason.name)
            PlanMutationRejection.PLAN_VERSION_CONFLICT, PlanMutationRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, value.reason.name)
            PlanMutationRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, value.reason.name, true, 1)
            PlanMutationRejection.SERVICE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, value.reason.name, true)
            PlanMutationRejection.VALIDATION_FAILED, PlanMutationRejection.OVERLAPPING_PLAN -> error(HttpStatus.UNPROCESSABLE_ENTITY,
                value.reason.name, fields = value.violations.map { FieldError(it.field, it.code) }.takeIf { it.isNotEmpty() })
        }
    }

    @GetMapping("/api/v1/elders/{elderId}/medications")
    fun medications(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt); noQuery(request)
        return result(service.medications(actor, elderId))
    }

    @GetMapping("/api/v1/elders/{elderId}/reminder-plans")
    fun plans(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.resolve(jwt); noQuery(request)
        return result(service.plans(actor, elderId))
    }

    private fun result(value: PlanConfirmationResult): ResponseEntity<*> = when (value) {
        is PlanConfirmationResult.Confirmed -> ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore()).body(value.view)
        is PlanConfirmationResult.Medications -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ItemsResponse(value.items))
        is PlanConfirmationResult.Plans -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value.value)
        is PlanConfirmationResult.Rejected -> when (value.reason) {
            PlanConfirmationRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, value.reason.name)
            PlanConfirmationRejection.ROLE_NOT_ALLOWED -> error(HttpStatus.FORBIDDEN, value.reason.name)
            PlanConfirmationRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, value.reason.name)
            PlanConfirmationRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, value.reason.name, true, 1)
            PlanConfirmationRejection.SERVICE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, value.reason.name, true)
            PlanConfirmationRejection.VALIDATION_FAILED, PlanConfirmationRejection.OVERLAPPING_PLAN ->
                error(HttpStatus.UNPROCESSABLE_ENTITY, value.reason.name,
                    fields = value.violations.map { FieldError(it.field, it.code) }.takeIf { it.isNotEmpty() })
        }
    }

    @ExceptionHandler(ApiException::class)
    fun invalid(exception: ApiException) = error(exception.status, exception.code)
    private fun noQuery(request: HttpServletRequest) { if (request.queryString != null) malformed() }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed plan request")
    private fun error(status: HttpStatus, code: String, retryable: Boolean = false,
        retry: Long? = null, fields: List<FieldError>? = null): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Plan request could not be completed", retryable, fields)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", body.requestId)
        if (retry != null) response.header("Retry-After", retry.toString())
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        return response.body(body)
    }
    private companion object { val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") }
}
