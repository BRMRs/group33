package com.group33.server.help

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.auth.AnonymousUser
import com.group33.server.config.PersistenceRuntime
import com.group33.server.occurrence.HelpOperation
import com.group33.server.occurrence.OccurrenceRegistrationStore
import com.group33.server.pairing.CodeStoreBusy
import com.group33.server.pairing.VerifiedPairingActorResolver
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ItemsResponse
import com.group33.shared.contract.CaregiverFollowUpView
import com.group33.shared.contract.HelpActionSyncState
import com.group33.shared.contract.HelpReceipt
import com.group33.shared.contract.HelpRequest
import com.group33.shared.model.ApiError
import com.group33.shared.model.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

internal enum class HelpRejection {
    RESOURCE_NOT_ACCESSIBLE, ROLE_NOT_ALLOWED, VALIDATION_FAILED,
    IDEMPOTENCY_KEY_REUSED, OPERATION_IN_PROGRESS, SERVICE_UNAVAILABLE,
}
internal sealed interface HelpResult {
    data class Accepted(val receipt: HelpReceipt) : HelpResult
    data class FollowUps(val items: List<CaregiverFollowUpView>) : HelpResult
    data class Rejected(val reason: HelpRejection) : HelpResult
}

internal interface HelpStore {
    fun <T> transaction(work: (HelpTransaction) -> T): T
}
internal interface HelpTransaction {
    fun activeCaregiver(elderId: String): String?
    fun operation(actionId: UUID): HelpOperation?
    fun insert(operation: HelpOperation)
    fun followUps(elderId: String): List<CaregiverFollowUpView>
}
internal class OccurrenceBackedHelpStore(private val store: OccurrenceRegistrationStore) : HelpStore {
    override fun <T> transaction(work: (HelpTransaction) -> T): T = store.registrationTransaction { tx ->
        work(object : HelpTransaction {
            override fun activeCaregiver(elderId: String) = tx.activeRelationship(elderId)?.caregiverId
            override fun operation(actionId: UUID) = tx.helpOperation(actionId)
            override fun insert(operation: HelpOperation) = tx.insertHelpOperation(operation)
            override fun followUps(elderId: String) = tx.caregiverFollowUps(elderId)
        })
    }
}

/** Minimal P0 Help transaction: original intent, idempotent receipt and caregiver work item commit together. */
internal class HelpFollowUpService(
    private val store: HelpStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun accept(actor: AnonymousUser, operationId: UUID, request: HelpRequest): HelpResult {
        if (actor.role != UserRole.ELDER) return HelpResult.Rejected(HelpRejection.ROLE_NOT_ALLOWED)
        if (request.actionId != operationId.toString() || request.elderId != actor.id) {
            return HelpResult.Rejected(HelpRejection.VALIDATION_FAILED)
        }
        return try {
            store.transaction { tx ->
                tx.operation(operationId)?.let { previous ->
                    return@transaction if (previous.request == request) HelpResult.Accepted(previous.receipt)
                    else HelpResult.Rejected(HelpRejection.IDEMPOTENCY_KEY_REUSED)
                }
                tx.activeCaregiver(actor.id)
                    ?: return@transaction HelpResult.Rejected(HelpRejection.RESOURCE_NOT_ACCESSIBLE)
                val receivedAt = maxOf(clock.millis(), request.occurredAtEpochMillis)
                val receipt = HelpReceipt(request.actionId, request.groupId, HelpActionSyncState.ACCEPTED, receivedAt)
                val followUp = CaregiverFollowUpView(
                    request.actionId, request.groupId, request.elderId,
                    request.occurrenceIds, request.occurredAtEpochMillis, receivedAt,
                )
                tx.insert(HelpOperation(request, receipt, followUp))
                HelpResult.Accepted(receipt)
            }
        } catch (_: CodeStoreBusy) {
            HelpResult.Rejected(HelpRejection.OPERATION_IN_PROGRESS)
        } catch (_: Exception) {
            HelpResult.Rejected(HelpRejection.SERVICE_UNAVAILABLE)
        }
    }

    fun followUps(actor: AnonymousUser, elderId: String): HelpResult {
        if (actor.role != UserRole.CAREGIVER) return HelpResult.Rejected(HelpRejection.ROLE_NOT_ALLOWED)
        return try {
            store.transaction { tx ->
                if (tx.activeCaregiver(elderId) != actor.id) {
                    HelpResult.Rejected(HelpRejection.RESOURCE_NOT_ACCESSIBLE)
                } else HelpResult.FollowUps(tx.followUps(elderId))
            }
        } catch (_: CodeStoreBusy) {
            HelpResult.Rejected(HelpRejection.OPERATION_IN_PROGRESS)
        } catch (_: Exception) {
            HelpResult.Rejected(HelpRejection.SERVICE_UNAVAILABLE)
        }
    }

}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.help.enabled"], havingValue = "true")
internal class HelpFollowUpConfiguration {
    @Bean fun helpFollowUpService(runtime: PersistenceRuntime, clock: Clock) =
        HelpFollowUpService(OccurrenceBackedHelpStore(
            checkNotNull(runtime.pairing as? OccurrenceRegistrationStore)), clock)
}

@RestController
@ConditionalOnProperty(name = ["app.help.enabled"], havingValue = "true")
internal class HelpFollowUpController(
    private val actors: VerifiedPairingActorResolver,
    private val service: HelpFollowUpService,
    mapper: ObjectMapper,
) {
    private val reader = mapper.readerFor(HelpRequest::class.java)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)

    @PostMapping("/api/v1/help", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun accept(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(ContractProtocol.IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody body: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        noQuery(request)
        val key = uuid(idempotencyKey)
        val intent = try { reader.readValue<HelpRequest?>(body) ?: malformed() }
        catch (_: JsonProcessingException) { malformed() }
        return response(service.accept(actors.resolve(jwt), key, intent))
    }

    @GetMapping("/api/v1/elders/{elderId}/caregiver-follow-ups")
    fun followUps(@AuthenticationPrincipal jwt: Jwt, @PathVariable elderId: String,
        request: HttpServletRequest): ResponseEntity<*> {
        noQuery(request)
        return response(service.followUps(actors.resolve(jwt), elderId))
    }

    @ExceptionHandler(ApiException::class)
    fun invalid(exception: ApiException) = error(exception.status, exception.code)

    private fun response(result: HelpResult): ResponseEntity<*> = when (result) {
        is HelpResult.Accepted -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.receipt)
        is HelpResult.FollowUps -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ItemsResponse(result.items))
        is HelpResult.Rejected -> when (result.reason) {
            HelpRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, result.reason.name)
            HelpRejection.ROLE_NOT_ALLOWED -> error(HttpStatus.FORBIDDEN, result.reason.name)
            HelpRejection.VALIDATION_FAILED -> error(HttpStatus.UNPROCESSABLE_ENTITY, result.reason.name)
            HelpRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, result.reason.name)
            HelpRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, result.reason.name, true, 1)
            HelpRejection.SERVICE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, result.reason.name, true)
        }
    }
    private fun uuid(value: String) = runCatching { UUID.fromString(value) }.getOrElse { malformed() }
    private fun noQuery(request: HttpServletRequest) { if (request.queryString != null) malformed() }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed Help request")
    private fun error(status: HttpStatus, code: String, retryable: Boolean = false, retry: Long? = null): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Help request could not be completed", retryable)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", body.requestId)
        if (retry != null) response.header("Retry-After", retry.toString())
        return response.body(body)
    }
}
