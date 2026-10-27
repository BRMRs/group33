package com.group33.server.pairing

import com.group33.shared.contract.*
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.ApiError
import com.group33.shared.model.UserRole
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/** Test-only real anonymous login / Bearer HTTP client. No authentication bypass. */
internal class PairingHttpFixture(private val mvc: MockMvc) {
    data class Session(val token: String, val id: String)
    val json = ContractProtocol.json
    fun login(role: UserRole): Session {
        val body = json.encodeToString(AnonymousLoginRequest.serializer(), AnonymousLoginRequest(UUID.randomUUID().toString(), role, "虚构用户"))
        val response = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/anonymous").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(201, response.status)
        val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(response.contentAsString)
        return Session(node["accessToken"].asText(), node["user"]["id"].asText())
    }
    fun call(s: Session, method: String, path: String, body: String? = "{}", key: String? = UUID.randomUUID().toString(),
             token: String? = s.token, remote: String = "192.0.2.1", forwarded: String? = null,
             duplicateKey: Boolean = false, contentType: String = MediaType.APPLICATION_JSON_VALUE): MockHttpServletResponse {
        val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path).with { it.remoteAddr = remote; it }
        if (token != null) request.header("Authorization", "Bearer $token")
        if (key != null && method != "GET") {
            request.header(ContractProtocol.IDEMPOTENCY_HEADER, key)
            if (duplicateKey) request.header(ContractProtocol.IDEMPOTENCY_HEADER, key)
        }
        if (forwarded != null) {
            request.header("X-Forwarded-For", forwarded)
            request.header("Forwarded", "for=$forwarded")
        }
        if (body != null || method == "POST") request.contentType(contentType)
        if (body != null) request.content(body)
        return mvc.perform(request).andReturn().response
    }
    fun issue(e: Session): PairingCodeView {
        val response = call(e, "POST", "/api/v1/pairing-codes")
        assertEquals(201, response.status)
        return json.decodeFromString<PairingCodeView>(response.contentAsString)
    }
    fun submit(c: Session, code: String, key: String = UUID.randomUUID().toString()) =
        call(c, "POST", REQUESTS, json.encodeToString(RequestPairingRequest.serializer(), RequestPairingRequest(code)), key)
    fun pending(e: Session, c: Session) = request(submit(c, issue(e).code), 201)
    fun request(response: MockHttpServletResponse, status: Int = 200): PairingRequestView {
        assertEquals(status, response.status); noStore(response)
        return json.decodeFromString<PairingRequestView>(response.contentAsString)
    }
    fun relationship(response: MockHttpServletResponse): CareRelationshipView {
        assertEquals(200, response.status); noStore(response)
        return json.decodeFromString<CareRelationshipView>(response.contentAsString)
    }
    fun failure(response: MockHttpServletResponse, status: Int, code: String, retryable: Boolean = false): ApiError {
        assertEquals(status, response.status)
        val result = json.decodeFromString<ApiError>(response.contentAsString)
        assertEquals(code, result.code); assertEquals(retryable, result.retryable)
        assertEquals(result.requestId, response.getHeader("X-Request-ID")); assertNotNull(UUID.fromString(result.requestId))
        assertNull(result.reason); assertNull(result.fieldErrors); noStore(response)
        return result
    }
    fun noStore(response: MockHttpServletResponse) { assertTrue(response.getHeader("Cache-Control").orEmpty().contains("no-store")) }
    companion object { const val REQUESTS = "/api/v1/pairing-requests" }
}
