package com.group33.server.occurrence

import com.group33.server.pairing.PairingHttpFixture
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import java.nio.file.Files

/** No test store/service overrides: catches aliases hidden by a Primary fault-control fixture. */
@SpringBootTest(properties = ["app.occurrence.enabled=true", "app.plan.enabled=true", "app.photo.enabled=true", "app.persistence.mode=memory"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
internal class OccurrenceRegistrationWiringTest(@Autowired private val mvc: MockMvc) {
    @Test fun `unmodified production configuration starts and executes the registration service`() {
        val http = PairingHttpFixture(mvc); val elder = http.login(UserRole.ELDER)
        val response = http.call(elder, "POST", "/api/v1/elders/${elder.id}/occurrence-registrations", "{\"items\":[]}")
        assertEquals(422, response.status)
        val error = http.json.decodeFromString<com.group33.shared.model.ApiError>(response.contentAsString)
        assertEquals("VALIDATION_FAILED", error.code)
        assertEquals("items", error.fieldErrors!!.single().field)
    }
    companion object {
        private val root = Files.createTempDirectory("group33-occurrence-wiring-")
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) { registry.add("app.photo.root") { root.toString() } }
        @JvmStatic @AfterAll fun cleanup() { Files.walk(root).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
