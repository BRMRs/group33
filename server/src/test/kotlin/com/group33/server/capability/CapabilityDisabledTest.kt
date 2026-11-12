package com.group33.server.capability

import com.group33.server.config.PersistenceRuntime
import com.group33.server.pairing.PairingHttpFixture
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.MockMvc

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
internal class CapabilityDisabledTest(@Autowired mvc: MockMvc, @Autowired val context: ApplicationContext,
    @Autowired val runtime: PersistenceRuntime) {
    private val http = PairingHttpFixture(mvc)
    @Test fun `default runtime does not register capability route or service`() {
        assertNull(runtime.capability); assertTrue(context.getBeansOfType(CapabilityService::class.java).isEmpty())
        val e = http.login(UserRole.ELDER)
        http.failure(http.call(e, "PUT", "/api/v1/elders/${e.id}/reminder-capability"), 404, "RESOURCE_NOT_ACCESSIBLE")
    }
}
