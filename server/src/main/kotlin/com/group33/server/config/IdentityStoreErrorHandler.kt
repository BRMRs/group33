package com.group33.server.config

import com.group33.server.auth.IdentityStoreUnavailable
import com.group33.server.web.ApiErrors
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.core.annotation.Order

@Order(-1)
@RestControllerAdvice
internal class IdentityStoreErrorHandler {
    @ExceptionHandler(IdentityStoreUnavailable::class)
    fun unavailable(): ResponseEntity<*> {
        val body = ApiErrors.body("SERVICE_UNAVAILABLE", "Session storage is temporarily unavailable", true)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
            .header("X-Request-ID", body.requestId).body(body)
    }
}
