package com.group33.server.plan

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.FieldError
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.Collections
import java.util.UUID

/** The same accepted plan wire/error rules for exclusive anonymous and phone routes. */
internal class PlanHttpSupport(mapper: ObjectMapper) {
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

    fun <T> decode(body: String, type: Class<T>): T = try { reader(type).readValue<T?>(body) ?: malformed() }
        catch (_: JsonProcessingException) { malformed() }
    fun operation(request: HttpServletRequest): UUID = UUID.fromString(Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER))
        .singleOrNull()?.takeIf { UUID_PATTERN.matches(it) } ?: malformed())
    fun mutationResult(value: PlanMutationResult, deactivate: Boolean = false): ResponseEntity<*> = when (value) {
        is PlanMutationResult.Accepted -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(if (deactivate) value.view.plan else value.view)
        is PlanMutationResult.Rejected -> when (value.reason) {
            PlanMutationRejection.UNAUTHENTICATED -> error(HttpStatus.UNAUTHORIZED, value.reason.name)
            PlanMutationRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, value.reason.name)
            PlanMutationRejection.ROLE_NOT_ALLOWED -> error(HttpStatus.FORBIDDEN, value.reason.name)
            PlanMutationRejection.PLAN_VERSION_CONFLICT, PlanMutationRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, value.reason.name)
            PlanMutationRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, value.reason.name, true, 1)
            PlanMutationRejection.SERVICE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, value.reason.name, true)
            PlanMutationRejection.VALIDATION_FAILED, PlanMutationRejection.OVERLAPPING_PLAN -> error(HttpStatus.UNPROCESSABLE_ENTITY,
                value.reason.name, fields = value.violations.map { FieldError(it.field, it.code) }.takeIf { it.isNotEmpty() })
        }
    }

    fun noQuery(request: HttpServletRequest) { if (request.queryString != null) malformed() }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed plan request")
    fun error(status: HttpStatus, code: String, retryable: Boolean = false,
        retry: Long? = null, fields: List<FieldError>? = null): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Plan request could not be completed", retryable, fields)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", body.requestId)
        if (retry != null) response.header("Retry-After", retry.toString())
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        return response.body(body)
    }
    private companion object { val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") }
}
