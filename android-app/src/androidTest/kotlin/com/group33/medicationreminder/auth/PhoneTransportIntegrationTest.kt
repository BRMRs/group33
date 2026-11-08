package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Dedicated loopback fixtures exercise real JSON/HTTP only; no Alibaba/network service. */
class PhoneTransportIntegrationTest {
    private fun serve(status: Int, body: String, block: (FusionServerClient) -> Unit): String {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val line = reader.readLine()
                        var length = 0
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        val buffer = CharArray(length); var count = 0
                        while (count < length) { val n = reader.read(buffer, count, length-count); if (n < 0) break; count += n }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().use {
                            it.write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            it.write(bytes)
                        }
                        line + "\n" + String(buffer, 0, count)
                    }
                }
                block(FusionServerClient("http://127.0.0.1:${server.localPort}", true))
                return request.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun signed_in_response_preserves_server_role_and_original_lifetime() {
        val request = serve(200, """{"status":"SIGNED_IN","user":{"id":"fixture","displayName":"Fixture","role":"ELDER"},"accessToken":"fictional.token","expiresInSeconds":900}""") { client ->
            val session = (client.verify("fictional-proof", "13800000000") as VerifiedPhone.Existing).session
            assertEquals(UserRole.ELDER, session.account.role)
            assertEquals(900L, session.expiresInSeconds)
        }
        assertTrue(request.startsWith("POST /api/v1/auth/fusion/verify "))
        val payload = JSONObject(request.substringAfter('\n'))
        assertEquals("fictional-proof", payload.getString("verifyToken"))
        assertEquals("13800000000", payload.getString("expectedPhone"))
        assertFalse(payload.has("installationId")) // New binding fields remain pending joint decisions.
    }
    @Test fun registration_required_does_not_construct_an_account_or_session() {
        serve(200, """{"status":"REGISTRATION_REQUIRED","registrationTicket":"fixture-ticket"}""") { client ->
            assertEquals("fixture-ticket", (client.verify("fictional-proof") as VerifiedPhone.New).ticket)
        }
    }
    @Test fun registration_sends_existing_trial_fields_without_password_or_client_actor() {
        val request = serve(201, """{"status":"SIGNED_IN","user":{"id":"fixture","displayName":"Fixture","role":"CAREGIVER"},"accessToken":"fictional.token","expiresInSeconds":900}""") {
            it.register("fixture-ticket", UserRole.CAREGIVER, "Fixture")
        }
        val payload = JSONObject(request.substringAfter('\n'))
        assertEquals("fixture-ticket", payload.getString("registrationTicket"))
        assertEquals("CAREGIVER", payload.getString("role"))
        assertFalse(payload.has("password")); assertFalse(payload.has("userId"))
    }
    @Test fun non_positive_session_lifetime_is_rejected() {
        serve(200, """{"status":"SIGNED_IN","user":{"id":"fixture","displayName":"Fixture","role":"ELDER"},"accessToken":"fictional.token","expiresInSeconds":0}""") {
            try { it.verify("fictional-proof"); fail("Invalid lifetime must not authenticate") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun large_error_body_is_bounded_and_not_retained_in_exception() {
        serve(503, "x".repeat(50_000)) {
            try { it.grant(); fail("503 must fail") }
            catch (error: FusionHttpException) {
                assertEquals(503, error.status); assertNull(error.code)
                assertFalse(error.toString().contains("xxxx"))
            }
        }
    }
}
