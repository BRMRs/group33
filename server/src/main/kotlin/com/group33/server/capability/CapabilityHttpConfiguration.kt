package com.group33.server.capability

import com.group33.server.auth.CareRelationshipAccess
import com.group33.server.config.PersistenceRuntime
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.capability.enabled"], havingValue = "true")
internal class CapabilityHttpConfiguration {
    @Bean fun capabilityStore(runtime: PersistenceRuntime): CapabilityStore = checkNotNull(runtime.capability)
    @Bean fun capabilityService(store: CapabilityStore, relationships: CareRelationshipAccess) = CapabilityService(store, relationships)
}
