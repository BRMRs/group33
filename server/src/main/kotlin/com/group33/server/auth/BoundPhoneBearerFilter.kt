package com.group33.server.auth

import com.group33.server.config.ApiSecurityHandlers
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal object BoundPhoneBearer {
    private val header = Regex("(?i:Bearer) +([^\\s]+)")
    fun token(request: HttpServletRequest): String {
        val values = request.getHeaders("Authorization").asSequence().take(2).toList()
        val value = values.singleOrNull()?.takeIf { it.length <= 4110 } ?: invalid()
        return header.matchEntire(value)?.groupValues?.get(1)?.takeIf { it.length <= 4096 } ?: invalid()
    }
    private fun invalid(): Nothing = throw BadCredentialsException("Phone authentication is required")
}

/** Cryptographic/profile validation precedes body binding and any application/database work. */
internal class BoundPhoneBearerFilter(private val jwt: BoundPhoneJwt, private val errors: ApiSecurityHandlers) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val claims = try { jwt.validate(BoundPhoneBearer.token(request)) } catch (_: BadCredentialsException) { null }
        if (claims == null) {
            response.setHeader("Cache-Control", "no-store")
            errors.commence(request, response, BadCredentialsException("Phone authentication is required"))
            return
        }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(claims, null, emptyList())
        SecurityContextHolder.setContext(context)
        try { chain.doFilter(request, response) } finally { SecurityContextHolder.clearContext() }
    }
}
