package com.group33.server.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.shared.model.ApiError
import com.group33.shared.model.FieldError
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.util.UUID

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val fieldErrors: List<FieldError>? = null,
) : RuntimeException(message)

object ApiErrors {
    fun body(
        code: String,
        message: String,
        retryable: Boolean = false,
        fieldErrors: List<FieldError>? = null,
    ) = ApiError(code, message, retryable, UUID.randomUUID().toString(), fieldErrors)

    fun write(response: HttpServletResponse, mapper: ObjectMapper, status: HttpStatus, error: ApiError) {
        response.status = status.value()
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setHeader("X-Request-ID", error.requestId)
        mapper.writeValue(response.outputStream, error)
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun domain(exception: ApiException): ResponseEntity<ApiError> = respond(
        exception.status,
        ApiErrors.body(exception.code, exception.message, fieldErrors = exception.fieldErrors),
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed(): ResponseEntity<ApiError> = respond(
        HttpStatus.BAD_REQUEST,
        ApiErrors.body("MALFORMED_REQUEST", "Request body is malformed"),
    )

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(): ResponseEntity<ApiError> = respond(
        HttpStatus.BAD_REQUEST,
        ApiErrors.body("MALFORMED_REQUEST", "Required request header is missing"),
    )

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(): ResponseEntity<ApiError> = respond(
        HttpStatus.NOT_FOUND,
        ApiErrors.body("RESOURCE_NOT_ACCESSIBLE", "Resource is not accessible"),
    )

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun unsupportedMethod(): ResponseEntity<ApiError> = respond(
        HttpStatus.METHOD_NOT_ALLOWED,
        ApiErrors.body("METHOD_NOT_ALLOWED", "Request method is not allowed"),
    )

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun unsupportedMediaType(): ResponseEntity<ApiError> = respond(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        ApiErrors.body("UNSUPPORTED_MEDIA_TYPE", "Request content type is not supported"),
    )

    @ExceptionHandler(Exception::class)
    fun unexpected(): ResponseEntity<ApiError> = respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ApiErrors.body("SERVICE_UNAVAILABLE", "Service could not complete the request", retryable = true),
    )

    private fun respond(status: HttpStatus, error: ApiError): ResponseEntity<ApiError> =
        ResponseEntity.status(status).header("X-Request-ID", error.requestId).body(error)
}
