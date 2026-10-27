package com.group33.server.pairing

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.PairingCodeView
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.UserRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

/** No bean overrides: the production SecureRandom/shared-store configuration must also execute. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
internal class PairingCodeProductionWiringTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val store: PairingCodeStore,
    @Autowired private val generator: PairingCodeGenerator,
) {
    @Test fun `HTTP10 production random generator and singleton store execute and replay a real login intent`() {
        assertInstanceOf(SecurePairingCodeGenerator::class.java, generator)
        assertInstanceOf(InMemoryPairingCodeStore::class.java, store)
        val login = mvc.perform(post("/api/v1/auth/anonymous").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(AnonymousLoginRequest(UUID.randomUUID().toString(), UserRole.ELDER, "Fictional"))))
            .andReturn().response
        assertEquals(201, login.status)
        val identity = mapper.readTree(login.contentAsString)
        val key = UUID.randomUUID()
        val before = System.currentTimeMillis()
        fun issue(): PairingCodeView {
            val response = mvc.perform(post("/api/v1/pairing-codes")
                .header("Authorization", "Bearer ${identity["accessToken"].asText()}")
                .header(ContractProtocol.IDEMPOTENCY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
            assertEquals(201, response.status)
            return ContractProtocol.json.decodeFromString<PairingCodeView>(response.contentAsString)
        }
        val first = issue()
        assertTrue(first.code.matches(Regex("[0-9]{8}")))
        assertTrue(first.expiresAtEpochMillis in (before + 600_000)..(System.currentTimeMillis() + 600_000))
        assertEquals(first, issue())
        store.transaction { tx ->
            val id = identity["user"]["id"].asText()
            assertEquals(first.id, tx.activeCode(id)?.id)
            assertEquals(1, tx.issuanceTimes(id).size)
            assertNotNull(tx.operation(CodeIssueKey(id, key))?.receipt)
        }
    }
}
