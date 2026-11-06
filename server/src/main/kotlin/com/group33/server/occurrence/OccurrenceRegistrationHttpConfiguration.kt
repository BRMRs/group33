package com.group33.server.occurrence

import com.group33.server.config.PersistenceRuntime
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.occurrence.enabled"], havingValue = "true")
internal class OccurrenceRegistrationHttpConfiguration {
    // Reuse the existing runtime instance without registering a second bean under its parent interfaces.
    @Bean fun occurrenceRegistrationService(runtime: PersistenceRuntime, clock: Clock) =
        OccurrenceRegistrationService(checkNotNull(runtime.pairing as? OccurrenceRegistrationStore), clock)
}
