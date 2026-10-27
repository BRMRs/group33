package com.group33.server.plan

import com.group33.server.photo.storage.PhotoObjectStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Explicit opt-in; the shared persistence runtime verifies the photo and v7 prerequisites. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.plan.enabled"], havingValue = "true")
internal class ReminderPlanHttpConfiguration {
    @Bean fun reminderPlanConfirmationService(store: PlanConfirmationStore, objects: PhotoObjectStore, clock: Clock) =
        ReminderPlanConfirmationService(store, objects, clock)
}
