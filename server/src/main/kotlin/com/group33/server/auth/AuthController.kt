package com.group33.server.auth

import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.AuthResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/anonymous")
    @ResponseStatus(HttpStatus.CREATED)
    fun anonymousLogin(@RequestBody request: AnonymousLoginRequest): AuthResponse =
        authService.anonymousLogin(request)
}
