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
    private val installation = "00000000-0000-4000-8000-000000000001"
    private val capability = "A".repeat(43)
    private fun serve(status: Int, body: String, block: (FusionServerClient) -> Unit): String {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
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
        val request = serve(200, """{"status":"SIGNED_IN","user":{"id":"fixture","displayName":"Fixture","role":"ELDER"},"accessToken":"fictional.token","expiresInSeconds":900,"installationId":"$installation"}""") { client ->
            val session = (client.verify(installation, capability, "fictional-proof", "13800000000") as VerifiedPhone.Existing).session
            assertEquals(UserRole.ELDER, session.account.role)
            assertEquals(900L, session.expiresInSeconds)
        }
        assertTrue(request.startsWith("POST /api/v1/auth/fusion/verify "))
        val payload = JSONObject(request.substringAfter('\n'))
        assertEquals("fictional-proof", payload.getString("verifyToken"))
        assertEquals("13800000000", payload.getString("expectedPhone"))
        assertEquals(installation, payload.getString("installationId"))
        assertEquals(capability, payload.getString("authenticationAttemptToken"))
    }
    @Test fun registration_required_does_not_construct_an_account_or_session() {
        serve(200, """{"status":"REGISTRATION_REQUIRED","registrationTicket":"${"T".repeat(43)}","registrationTicketExpiresInSeconds":300,"installationId":"$installation"}""") { client ->
            assertEquals("T".repeat(43), (client.verify(installation, capability, "fictional-proof") as VerifiedPhone.New).ticket)
        }
    }
    @Test fun registration_sends_bound_installation_without_password_or_client_actor() {
        val request = serve(201, """{"status":"SIGNED_IN","user":{"id":"fixture","displayName":"Fixture","role":"CAREGIVER"},"accessToken":"fictional.token","expiresInSeconds":900,"installationId":"$installation"}""") {
            it.register(installation, "T".repeat(43), UserRole.CAREGIVER, "Fixture")
        }
        val payload = JSONObject(request.substringAfter('\n'))
        assertEquals("T".repeat(43), payload.getString("registrationTicket"))
        assertEquals(installation, payload.getString("installationId"))
        assertEquals("CAREGIVER", payload.getString("role"))
        assertFalse(payload.has("password")); assertFalse(payload.has("userId"))
    }
    @Test fun non_positive_session_lifetime_is_rejected() {
        serve(200, """{"status":"SIGNED_IN","user":{"id":"fixture","displayName":"Fixture","role":"ELDER"},"accessToken":"fictional.token","expiresInSeconds":0,"installationId":"$installation"}""") {
            try { it.verify(installation, capability, "fictional-proof"); fail("Invalid lifetime must not authenticate") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun large_error_body_is_bounded_and_not_retained_in_exception() {
        serve(503, "x".repeat(50_000)) {
            try { it.grant(installation); fail("503 must fail") }
            catch (error: FusionHttpException) {
                assertEquals(503, error.status); assertNull(error.code)
                assertFalse(error.toString().contains("xxxx"))
            }
        }
    }

    @Test fun lost_response_after_proof_body_does_not_replay_on_android_http() {
        ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<Int> {
                    var count = 0
                    server.soTimeout = 2_000
                    repeat(2) {
                        val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { return@submit count }
                        socket.use {
                            it.soTimeout = 2_000
                            val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                            reader.readLine()
                            var length = 0
                            while (true) {
                                val header = reader.readLine() ?: break
                                if (header.isEmpty()) break
                                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            }
                            repeat(length) { check(reader.read() >= 0) }
                            count++ // Close after consuming the complete body, without returning a response.
                        }
                        server.soTimeout = 500
                    }
                    count
                }
                val client = FusionServerClient("http://127.0.0.1:${server.localPort}", true)
                try { client.verify(installation, capability, "fictional-proof"); fail("Lost response accepted") }
                catch (_: java.io.IOException) { }
                assertEquals(1, received.get(5, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
}
