package com.group33.server.photo

import com.group33.server.auth.BoundPhoneBearer
import com.group33.server.auth.BoundPhoneBearerFilter
import com.group33.server.auth.BoundPhoneInfrastructure
import com.group33.server.config.ApiSecurityHandlers
import com.group33.server.config.PersistenceRuntime
import com.group33.server.pairing.PostgresPairingCodeStore
import com.group33.server.photo.storage.PhotoObjectStore
import com.group33.server.web.ApiErrors
import com.group33.server.web.ApiException
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ItemsResponse
import com.group33.shared.model.ApiError
import com.group33.shared.model.FieldError
import jakarta.servlet.http.HttpServletRequest
import java.util.Collections
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartHttpServletRequest

internal class BoundPhonePhotoOperations(runtime: PersistenceRuntime, objects: PhotoObjectStore,
                                         private val infrastructure: BoundPhoneInfrastructure) {
    private val postgres = checkNotNull(runtime.pairing as? PostgresPairingCodeStore)
    private val service = MedicationPhotoService(postgres, objects, infrastructure.clock)
    fun upload(token: String, elderId: String, key: UUID, bytes: ByteArray, declaredType: String) =
        service.uploadPhone(postgres, infrastructure.jwt, token, elderId, key, bytes, declaredType)
    fun list(token: String, elderId: String) =
        service.listPhone(postgres, infrastructure.jwt, token, elderId)
    fun content(token: String, photoId: String) =
        service.contentPhone(postgres, infrastructure.jwt, token, photoId)
}

@RestController
@ConditionalOnProperty(name = ["app.bound-phone.enabled", "app.photo.enabled"], havingValue = "true")
internal class BoundPhonePhotoController(private val photos: BoundPhonePhotoOperations) {
    @PostMapping("/api/v1/elders/{elderId}/medication-photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable elderId: String, request: MultipartHttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        noQuery(request)
        val key = Collections.list(request.getHeaders(ContractProtocol.IDEMPOTENCY_HEADER)).singleOrNull()
            ?.takeIf { UUID_PATTERN.matches(it) } ?: malformed()
        if (request.multiFileMap.keys != setOf("file") || request.getFiles("file").size != 1 || request.parameterMap.isNotEmpty()) malformed()
        val file = request.getFiles("file").single()
        return try { result(photos.upload(token, elderId, UUID.fromString(key), file.bytes, file.contentType.orEmpty())) }
            catch (_: Exception) { error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", true) }
    }

    @GetMapping("/api/v1/elders/{elderId}/medication-photos")
    fun list(@PathVariable elderId: String, request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        noQuery(request)
        return result(photos.list(token, elderId))
    }

    @GetMapping("/api/v1/medication-photos/{photoId}/content")
    fun content(@PathVariable photoId: String, request: HttpServletRequest): ResponseEntity<*> {
        val token = BoundPhoneBearer.token(request)
        noQuery(request)
        return result(photos.content(token, photoId))
    }

    private fun result(value: PhotoBusinessResult): ResponseEntity<*> = when (value) {
        is PhotoBusinessResult.Uploaded -> ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(value.view)
        is PhotoBusinessResult.Listed -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ItemsResponse(value.items))
        is PhotoBusinessResult.Content -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(value.contentType)).header("X-Content-Type-Options", "nosniff").body(value.bytes)
        is PhotoBusinessResult.Rejected -> when (value.reason) {
            PhotoBusinessRejection.UNAUTHENTICATED -> error(HttpStatus.UNAUTHORIZED, value.reason.name)
            PhotoBusinessRejection.RESOURCE_NOT_ACCESSIBLE -> error(HttpStatus.NOT_FOUND, value.reason.name)
            PhotoBusinessRejection.ROLE_NOT_ALLOWED -> error(HttpStatus.FORBIDDEN, value.reason.name)
            PhotoBusinessRejection.IDEMPOTENCY_KEY_REUSED -> error(HttpStatus.CONFLICT, value.reason.name)
            PhotoBusinessRejection.OPERATION_IN_PROGRESS -> error(HttpStatus.CONFLICT, value.reason.name, true, 1)
            PhotoBusinessRejection.SERVICE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, value.reason.name, true)
            PhotoBusinessRejection.PHOTO_TOO_LARGE -> error(HttpStatus.PAYLOAD_TOO_LARGE, value.reason.name)
            PhotoBusinessRejection.PHOTO_TYPE_UNSUPPORTED -> error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, value.reason.name)
            PhotoBusinessRejection.VALIDATION_FAILED -> error(HttpStatus.UNPROCESSABLE_ENTITY, value.reason.name,
                fields = listOf(FieldError("file", checkNotNull(value.inputReason).name)))
        }
    }

    @ExceptionHandler(ApiException::class)
    fun invalid(exception: ApiException) = error(exception.status, exception.code)
    private fun noQuery(request: HttpServletRequest) { if (request.queryString != null) malformed() }
    private fun malformed(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed photo request")
    private fun error(status: HttpStatus, code: String, retryable: Boolean = false, retry: Long? = null, fields: List<FieldError>? = null): ResponseEntity<ApiError> {
        val body = ApiErrors.body(code, "Photo request could not be completed", retryable, fields)
        val response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", body.requestId)
        if (retry != null) response.header("Retry-After", retry.toString())
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        return response.body(body)
    }
    private companion object { val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.bound-phone.enabled", "app.photo.enabled"], havingValue = "true")
internal class BoundPhonePhotoConfiguration {
    @Bean fun boundPhonePhotoOperations(runtime: PersistenceRuntime, objects: PhotoObjectStore,
                                        infrastructure: BoundPhoneInfrastructure) =
        BoundPhonePhotoOperations(runtime, objects, infrastructure)

    @Bean @Order(1)
    fun boundPhonePhotoSecurity(http: HttpSecurity, infrastructure: BoundPhoneInfrastructure, errors: ApiSecurityHandlers): SecurityFilterChain = http
        .securityMatchers {
            it.requestMatchers(HttpMethod.POST, "/api/v1/elders/*/medication-photos")
            it.requestMatchers(HttpMethod.GET, "/api/v1/elders/*/medication-photos")
            it.requestMatchers(HttpMethod.GET, "/api/v1/medication-photos/*/content")
        }
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().authenticated() }
        .exceptionHandling { it.authenticationEntryPoint(errors); it.accessDeniedHandler(errors) }
        .addFilterBefore(BoundPhoneBearerFilter(infrastructure.jwt, errors), AuthorizationFilter::class.java)
        .build()
}
