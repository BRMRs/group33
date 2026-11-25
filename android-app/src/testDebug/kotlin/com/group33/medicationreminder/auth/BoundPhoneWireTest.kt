package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

/** Real loopback HTTP, fictional credentials; no SDK, SMS or deployed service is used. */
class BoundPhoneWireTest {
    private val installation = "00000000-0000-4000-8000-000000000001"
    private val capability = "A".repeat(43)
    private val responses = LinkedBlockingQueue<Pair<Int, String>>()
    private val requests = LinkedBlockingQueue<Pair<String, JsonObject>>()
    private lateinit var server: ServerSocket
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var client: FusionServerClient
    @Before fun before() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        worker.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream()
                    val requestLine = input.line()
                    var length = 0
                    while (true) {
                        val header = input.line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val bytes = ByteArray(length)
                    for (i in bytes.indices) bytes[i] = input.read().also { value -> check(value >= 0) }.toByte()
                    requests.add(requestLine.split(' ')[1] to Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject)
                    val response = responses.remove()
                    val body = response.second.toByteArray(Charsets.UTF_8)
                    if (response.first > 0) it.getOutputStream().use { output ->
                        output.write("HTTP/1.1 ${response.first} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body)
                    }
                }
            }
        }
        client = FusionServerClient("http://127.0.0.1:${server.localPort}", true)
    }
    private fun InputStream.line(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = read(); check(next >= 0)
            if (next == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(next); check(bytes.size() <= 8192)
        }
    }
    @After fun after() { server.close(); worker.shutdownNow(); check(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    private fun grant() = buildJsonObject {
        put("schemeCode", "scheme"); put("fusionAuthToken", "sdk-only")
        put("authenticationAttemptToken", capability); put("expiresInSeconds", 900)
    }.toString()
    private fun registration(echo: String = installation) = buildJsonObject {
        put("status", "REGISTRATION_REQUIRED"); put("registrationTicket", "T".repeat(43))
        put("registrationTicketExpiresInSeconds", 300); put("installationId", echo)
    }.toString()
    private fun signedIn(echo: String = installation) = buildJsonObject {
        put("status", "SIGNED_IN"); put("installationId", echo)
        put("accessToken", "fictional.session"); put("expiresInSeconds", 900)
        putJsonObject("user") { put("id", "server-user"); put("role", "CAREGIVER"); put("displayName", "Fixture") }
    }.toString()
    private fun answer(body: String, status: Int = 200) { responses.add(status to body) }

    @Test fun start_refresh_verify_register_send_exact_bound_shapes_and_keep_capability_private() = runBlocking {
        answer(grant()); val exchange = BoundPhoneExchange.start(client, installation, { 0 }) { true }
        var request = requests.remove()
        assertEquals("/api/v1/auth/fusion/token", request.first)
        assertEquals(setOf("installationId"), request.second.keys)
        answer(grant()); exchange.refresh(); request = requests.remove()
        assertEquals(setOf("installationId", "authenticationAttemptToken"), request.second.keys)
        assertEquals(capability, request.second["authenticationAttemptToken"]!!.jsonPrimitive.content)
        assertEquals("sdk-only", exchange.sdkToken())
        answer(registration()); exchange.verify("fictional-proof", null); request = requests.remove()
        assertEquals("/api/v1/auth/fusion/verify", request.first)
        assertEquals(setOf("installationId", "authenticationAttemptToken", "verifyToken"), request.second.keys)
        assertEquals(installation, request.second["installationId"]!!.jsonPrimitive.content)
        answer(signedIn()); val result = client.register(installation, "T".repeat(43), UserRole.CAREGIVER, "Fixture")
        assertEquals("server-user", (result as VerifiedPhone.Existing).session.account.id)
        request = requests.remove()
        assertEquals("/api/v1/auth/phone/register", request.first)
        assertEquals(setOf("installationId", "registrationTicket", "role", "displayName"), request.second.keys)
        assertFalse(request.first.contains(capability))
    }

    @Test fun sdk_sms_keeps_expected_number_but_elder_registration_omits_optional_name() {
        answer(registration()); client.verify(installation, capability, "fictional-proof", "13800000000")
        assertEquals("13800000000", requests.remove().second["expectedPhone"]!!.jsonPrimitive.content)
        answer(signedIn()); client.register(installation, "T".repeat(43), UserRole.ELDER, null)
        assertEquals(setOf("installationId", "registrationTicket", "role"), requests.remove().second.keys)
    }

    @Test fun wrong_or_missing_installation_echo_is_rejected_before_session_publication() {
        for (body in listOf(signedIn("00000000-0000-4000-8000-000000000002"),
                signedIn().let { Json.parseToJsonElement(it).jsonObject.filterKeys { key -> key != "installationId" }.let(::JsonObject).toString() },
                registration("foreign"))) {
            answer(body)
            try { client.verify(installation, capability, "fictional-proof", null); fail("Foreign response accepted") }
            catch (_: Exception) { }
        }
        assertEquals(3, requests.size)
    }

    @Test fun temporary_error_preserves_envelope_code_and_makes_no_automatic_retry() {
        answer("""{"code":"OPERATION_IN_PROGRESS","retryable":true}""", 409)
        try { client.grant(installation); fail("409 accepted") }
        catch (error: FusionHttpException) { assertEquals(409, error.status); assertEquals("OPERATION_IN_PROGRESS", error.code) }
        assertEquals(1, requests.size)
    }

    @Test fun malformed_session_lifetime_is_not_coerced_from_string() {
        answer(signedIn().replace("\"expiresInSeconds\":900", "\"expiresInSeconds\":\"900\""))
        try { client.verify(installation, capability, "fictional-proof", null); fail("Malformed lifetime accepted") }
        catch (_: Exception) { }
    }

    @Test fun connection_lost_after_proof_body_does_not_replay_the_http_post() {
        answer("", -1); answer("", -1) // A second response is available only to detect an unwanted replay.
        try { client.verify(installation, capability, "fictional-proof", null); fail("Lost response accepted") }
        catch (_: java.io.IOException) { }
        assertEquals(1, requests.size)
        assertEquals(1, responses.size)
    }
}
