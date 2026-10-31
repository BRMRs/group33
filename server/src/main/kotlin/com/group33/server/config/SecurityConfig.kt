package com.group33.server.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetSequenceKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity, errors: ApiSecurityHandlers): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers("/health", "/api/v1/auth/anonymous").permitAll()
            it.anyRequest().authenticated()
        }
        .exceptionHandling {
            it.authenticationEntryPoint(errors)
            it.accessDeniedHandler(errors)
        }
        .oauth2ResourceServer {
            it.jwt {}
            it.authenticationEntryPoint(errors)
            it.accessDeniedHandler(errors)
        }
        .build()

    @Bean
    fun jwtSecretKey(@Value("\${app.jwt.secret}") secret: String): SecretKey {
        // The checked-in local default is a sentinel, never usable signing material.
        // Persistent runtimes separately require an explicit stable private secret.
        if (secret == "GROUP33_LOCAL_DEVELOPMENT_SECRET_CHANGE_BEFORE_DEPLOYMENT_2026") {
            return KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
        }
        require(secret.toByteArray(StandardCharsets.UTF_8).size >= 32) {
            "app.jwt.secret must contain at least 32 UTF-8 bytes"
        }
        return SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    }

    @Bean
    fun jwtDecoder(
        secretKey: SecretKey,
        @Value("\${app.jwt.issuer}") issuer: String,
    ): JwtDecoder = NimbusJwtDecoder
        .withSecretKey(secretKey)
        .macAlgorithm(MacAlgorithm.HS256)
        .build()
        .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer)) }

    @Bean
    fun jwtEncoder(secretKey: SecretKey): JwtEncoder {
        val jwk = OctetSequenceKey.Builder(secretKey)
            .algorithm(JWSAlgorithm.HS256)
            .build()
        val jwkSource = ImmutableJWKSet<SecurityContext>(JWKSet(jwk))
        return NimbusJwtEncoder(jwkSource)
    }
}
