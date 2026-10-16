package com.group33.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.web.ApiErrors
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

@Component
class ApiSecurityHandlers(private val mapper: ObjectMapper) : AuthenticationEntryPoint, AccessDeniedHandler {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        response.setHeader("WWW-Authenticate", "Bearer")
        ApiErrors.write(
            response, mapper, HttpStatus.UNAUTHORIZED,
            ApiErrors.body("UNAUTHENTICATED", "Authentication is required"),
        )
    }

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AccessDeniedException,
    ) {
        ApiErrors.write(
            response, mapper, HttpStatus.FORBIDDEN,
            ApiErrors.body("ROLE_NOT_ALLOWED", "Operation is not allowed"),
        )
    }
}
