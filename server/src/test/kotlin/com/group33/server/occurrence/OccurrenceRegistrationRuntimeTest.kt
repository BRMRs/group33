package com.group33.server.occurrence

import com.group33.server.config.PersistenceRuntimeConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class OccurrenceRegistrationRuntimeTest {
    @Test fun `registration route and service remain absent by default`() {
        ApplicationContextRunner().withUserConfiguration(OccurrenceRegistrationHttpConfiguration::class.java,
            OccurrenceRegistrationController::class.java).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.containsBean("occurrenceRegistrationService"))
            assertTrue(context.getBeansOfType(OccurrenceRegistrationController::class.java).isEmpty())
        }
    }
    @Test fun `registration cannot start without confirmed-plan runtime`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistenceRuntimeConfiguration().create(MockEnvironment().withProperty("app.occurrence.enabled", "true"))
        }
    }
    @Test fun `registration cannot silently start plans without private photos`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistenceRuntimeConfiguration().create(MockEnvironment().withProperty("app.occurrence.enabled", "true")
                .withProperty("app.plan.enabled", "true"))
        }
    }
    @Test fun `explicit memory runtime shares the same registration and plan transaction`() {
        val runtime = PersistenceRuntimeConfiguration().create(MockEnvironment().withProperty("app.occurrence.enabled", "true")
            .withProperty("app.plan.enabled", "true").withProperty("app.photo.enabled", "true"))
        assertInstanceOf(OccurrenceRegistrationStore::class.java, runtime.pairing)
        assertNotNull(OccurrenceRegistrationHttpConfiguration().occurrenceRegistrationService(runtime, java.time.Clock.systemUTC()))
    }
}
