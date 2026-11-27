package com.group33.medicationreminder.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real local HTTP429 and Room journal; only the waiting function is controlled. */
class PairingRateLimitOnlineTest {
    private val json = ContractProtocol.json
    private fun operation() = UUID.randomUUID().toString()
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private inner class ObservedHttp(base: String) : ChainHttp(base) {
        val attempts = mutableListOf<Triple<Int, Pair<String, String>, String?>>()
        override suspend fun call(method: String, path: String, token: String?, operationId: String?,
                                  json: String?, photo: File?, photoType: String?): Reply {
            val reply = super.call(method, path, token, operationId, json, photo, photoType)
            if (method == "POST" && path == "/api/v1/pairing-requests") {
                if (reply.status == 429) {
                    assertEquals("RETRY_LATER", this@PairingRateLimitOnlineTest.json.decodeFromString(ApiError.serializer(), reply.text()).code)
                }
                attempts += Triple(reply.status, digest(operationId.orEmpty()) to digest(json.orEmpty()), reply.retryAfter)
            }
            return reply
        }
    }

    @Test fun realRateLimitWaitsAndRetainsOriginalCommand(): Unit = runBlocking {
        assumeTrue("Dedicated fictional-data service required",
            InstrumentationRegistry.getArguments().getString("pairingRateLimit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val base = com.group33.medicationreminder.BuildConfig.DEBUG_SERVER_URL
        val raw = ChainHttp(base)
        val login = raw.call("POST", "/api/v1/auth/anonymous", json = json.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(operation(), UserRole.ELDER, "C1 limit Elder")))
        assertEquals(201, login.status)
        val elder = json.decodeFromString(AuthResponse.serializer(), login.text())
        val issued = raw.call("POST", "/api/v1/pairing-codes", elder.accessToken, operation(), "{}")
        assertEquals(201, issued.status)
        val code = json.decodeFromString(PairingCodeView.serializer(), issued.text())
        assertEquals(204, raw.call("DELETE", "/api/v1/pairing-codes/${code.id}", elder.accessToken, operation(), "{}").status)
        val transport = ObservedHttp(base)
        val enteredWait = CompletableDeferred<Long>()
        val releaseWait = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        val repository = DebugChainRepository(context, base, "c1-limit-${operation()}", transport) { millis ->
            waits += millis
            if (waits.size == 1) { enteredWait.complete(millis); releaseWait.await() }
        }
        assertTrue(repository.login(UserRole.CAREGIVER, "C1 limit Caregiver") is Outcome.Success)
        val trusted = repository.services.trusted.value
        assertNotNull(trusted)
        repeat(5) {
            val command = (repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) as Outcome.Success).value
            assertTrue(repository.requestPairing(code.code, command) is OnlineCommandResult.Rejected)
            assertEquals(422, transport.attempts.last().first)
        }
        val command = (repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) as Outcome.Success).value
        val limited = async { repository.requestPairing(code.code, command) }
        try {
            val requested = withTimeout(30_000) { enteredWait.await() }
            assertEquals(429, transport.attempts.last().first)
            val retrySeconds = checkNotNull(transport.attempts.last().third).toLong()
            assertTrue(retrySeconds in 1..60)
            assertEquals(retrySeconds * 1_000, requested)
            assertFalse("Wait must finish before returning the unknown outcome", limited.isCompleted)
            assertTrue(repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) is Outcome.Failure)
            assertEquals(6, transport.attempts.size)
        } finally { releaseWait.complete(Unit) }
        val result = withTimeout(30_000) { limited.await() }
        assertTrue(result is OnlineCommandResult.AwaitingConfirmation)
        assertEquals(command.operationId, result.operationId)
        val before = repository.observeCommands().first().map { it.operationId }.toSet()
        assertEquals(6, before.size)
        assertTrue(repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) is Outcome.Failure)
        val resumed = repository.resume(command.operationId) as Outcome.Success
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, resumed.value.state)
        assertEquals(429, transport.attempts.last().first)
        assertEquals(7, transport.attempts.size)
        assertEquals(transport.attempts[5].second, transport.attempts[6].second)
        assertEquals(checkNotNull(transport.attempts.last().third).toLong() * 1_000, waits.last())
        assertEquals(before, repository.observeCommands().first().map { it.operationId }.toSet())
        assertEquals(trusted, repository.services.trusted.value)
        assertTrue(repository.observeRequests().first().value.isEmpty())
        assertTrue(repository.observeRelationships().first().value.isEmpty())
    }
}
