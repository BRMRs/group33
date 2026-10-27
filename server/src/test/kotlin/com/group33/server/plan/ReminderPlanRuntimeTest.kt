package com.group33.server.plan

import com.group33.server.config.PersistenceRuntimeConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment

internal class ReminderPlanRuntimeTest {
    @Test fun `plan routes and service are absent unless explicitly enabled`() {
        ApplicationContextRunner().withUserConfiguration(ReminderPlanHttpConfiguration::class.java,ReminderPlanController::class.java)
            .run { context -> assertNull(context.startupFailure); assertFalse(context.containsBean("reminderPlanConfirmationService"));
                assertEquals(0,context.getBeansOfType(ReminderPlanController::class.java).size) }
    }
    @Test fun `plan enablement requires private photo runtime even in memory mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistenceRuntimeConfiguration().create(MockEnvironment().withProperty("app.plan.enabled","true"))
        }
        val runtime=PersistenceRuntimeConfiguration().create(MockEnvironment()
            .withProperty("app.plan.enabled","true").withProperty("app.photo.enabled","true"))
        assertInstanceOf(PlanConfirmationStore::class.java,runtime.pairing)
    }
}
