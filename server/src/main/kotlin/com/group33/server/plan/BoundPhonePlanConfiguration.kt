package com.group33.server.plan

import com.group33.server.auth.BoundPhoneBearerFilter
import com.group33.server.auth.BoundPhoneInfrastructure
import com.group33.server.config.ApiSecurityHandlers
import com.group33.server.config.PersistenceRuntime
import com.group33.server.pairing.PostgresPairingCodeStore
import com.group33.shared.contract.DeactivateReminderPlanRequest
import com.group33.shared.contract.UpdateReminderPlanRequest
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter

/** Compose the existing S2 algorithm with the exact phone primary, clock and profile. */
internal class BoundPhonePlanOperations(runtime: PersistenceRuntime, private val infrastructure: BoundPhoneInfrastructure) {
    private val postgres = checkNotNull(runtime.pairing as? PostgresPairingCodeStore)
    private val mutations = ReminderPlanMutationService(postgres, infrastructure.clock)
    fun update(token: String, id: String, key: UUID, input: UpdateReminderPlanRequest) =
        mutations.updatePhone(postgres, infrastructure.jwt, token, id, key, input)
    fun deactivate(token: String, id: String, key: UUID, input: DeactivateReminderPlanRequest) =
        mutations.deactivatePhone(postgres, infrastructure.jwt, token, id, key, input)
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.bound-phone.enabled", "app.plan.enabled"], havingValue = "true")
internal class BoundPhonePlanConfiguration {
    @Bean fun boundPhonePlanOperations(runtime: PersistenceRuntime, infrastructure: BoundPhoneInfrastructure) =
        BoundPhonePlanOperations(runtime, infrastructure)

    @Bean @Order(1)
    fun boundPhonePlanSecurity(http: HttpSecurity, infrastructure: BoundPhoneInfrastructure, errors: ApiSecurityHandlers): SecurityFilterChain = http
        .securityMatchers {
            it.requestMatchers(HttpMethod.PATCH, "/api/v1/reminder-plans/*")
            it.requestMatchers(HttpMethod.POST, "/api/v1/reminder-plans/*/deactivate")
        }
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().authenticated() }
        .exceptionHandling { it.authenticationEntryPoint(errors); it.accessDeniedHandler(errors) }
        .addFilterBefore(BoundPhoneBearerFilter(infrastructure.jwt, errors), AuthorizationFilter::class.java)
        .build()
}
