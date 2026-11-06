package com.group33.server.photo

import com.group33.server.web.ApiErrors
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.http.ResponseEntity

/** Multipart resolution can fail before a controller is selected, so controller-local handlers cannot cover it. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = ["app.photo.enabled"], havingValue = "true")
internal class PhotoMultipartErrorHandler {
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge() = respond(HttpStatus.PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE")

    @ExceptionHandler(MultipartException::class)
    fun malformed() = respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST")

    private fun respond(status: HttpStatus, code: String) = ApiErrors.body(code, "Photo request could not be completed").let {
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Request-ID", it.requestId).body(it)
    }
}
