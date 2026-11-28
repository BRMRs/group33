package com.group33.server.auth

import com.group33.server.config.ApiSecurityHandlers
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.bound-phone.enabled"], havingValue = "true")
internal class BoundPhoneSecurityConfiguration {
    /** These capability/proof endpoints do not consume the legacy anonymous bearer-token filter. */
    @Bean @Order(0)
    fun boundPhoneAuthenticationSecurity(http: HttpSecurity, errors: ApiSecurityHandlers): SecurityFilterChain = http
        .securityMatcher("/api/v1/auth/fusion/token", "/api/v1/auth/fusion/verify", "/api/v1/auth/phone/register")
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.POST, "/api/v1/auth/fusion/token", "/api/v1/auth/fusion/verify",
                "/api/v1/auth/phone/register").permitAll()
            it.anyRequest().denyAll()
        }
        .exceptionHandling { it.authenticationEntryPoint(errors); it.accessDeniedHandler(errors) }
        .build()
    /** Each remaining PS2 path stays closed until its dedicated current-state transaction is wired. */
    @Bean @Order(2)
    fun boundPhonePendingBusinessSecurity(http: HttpSecurity, errors: ApiSecurityHandlers): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/health").permitAll()
            it.anyRequest().denyAll()
        }
        .exceptionHandling { it.authenticationEntryPoint(errors); it.accessDeniedHandler(errors) }
        .build()

}
