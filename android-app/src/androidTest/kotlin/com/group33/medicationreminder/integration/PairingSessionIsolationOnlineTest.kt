package com.group33.medicationreminder.integration

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.caregiver.PairingNotice
import com.group33.medicationreminder.caregiver.PairingWorkflowViewModel
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.AuthResponse
import com.group33.shared.model.UserRole
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.group33.shared.model.ApiError

/** Real local HTTP, private session and Room; only credential fault/response timing are injected. */
class PairingSessionIsolationOnlineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val base = com.group33.medicationreminder.BuildConfig.DEBUG_SERVER_URL
    private val protocol = ContractProtocol.json
    private val stores = mutableListOf<ViewModelStore>()
    private fun operation() = UUID.randomUUID().toString()
    private fun optIn() = assumeTrue("Requires a dedicated fictional-data local service",
        InstrumentationRegistry.getArguments().getString("pairingSessionIsolation") == "true")
    private suspend fun waitFor(label: String, check: suspend () -> Boolean) {
        try { withTimeout(30_000) { while (!check()) delay(25) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out waiting for $label", error) }
    }
    @After fun clearModels() { instrumentation.runOnMainSync { stores.forEach { it.clear() } } }

    private class ReadFault(val unauthorized: Boolean, val hold: Boolean) {
        val seen = CompletableDeferred<Int>()
        val release = CompletableDeferred<Unit>()
    }
    private inner class ControlledHttp : ChainHttp(base) {
        val next = AtomicReference<ReadFault?>(null)
        val writes = AtomicInteger()
        val mutations = AtomicInteger()
        val temporaryRead = AtomicBoolean()
        val temporaryWrite = AtomicBoolean()
        val lastRealStatus = AtomicInteger()
        val writeDigests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        private fun unavailable() = Reply(503, protocol.encodeToString(ApiError.serializer(),
            ApiError("SERVICE_UNAVAILABLE", "Temporary local test fault", true, "c1-temporary-test"))
            .toByteArray(Charsets.UTF_8), "application/json", "1", "c1-temporary-test")
        override suspend fun call(method: String, path: String, token: String?, operationId: String?,
                                  json: String?, photo: File?, photoType: String?): Reply {
            if (method in setOf("POST", "DELETE", "PUT")) mutations.incrementAndGet()
            if (method == "POST" && path == "/api/v1/pairing-requests") {
                writes.incrementAndGet()
                writeDigests += digest(operationId.orEmpty()) to digest(json.orEmpty())
                if (temporaryWrite.getAndSet(false)) {
                    val actual = super.call(method, path, token, operationId, json, photo, photoType)
                    lastRealStatus.set(actual.status)
                    check(actual.status == 201)
                    return unavailable() // Deliberate client fault after genuine server acceptance.
                }
            }
            if (method == "GET" && path == "/api/v1/pairing-requests" && temporaryRead.getAndSet(false))
                return unavailable() // Contract fixture, not evidence of a real server outage.
            val fault = if (method == "GET" && path == "/api/v1/pairing-requests") next.getAndSet(null) else null
            // Ask the real Server to reject an invalid credential, rather than fabricate a 401 reply.
            val reply = super.call(method, path, if (fault?.unauthorized == true) "invalid-test-credential" else token,
                operationId, json, photo, photoType)
            lastRealStatus.set(reply.status)
            fault?.seen?.complete(reply.status)
            if (fault?.hold == true) withContext(NonCancellable) { fault.release.await() }
            return reply
        }
    }
    private class Fixture(val repository: DebugChainRepository, val transport: ChainHttp,
                          val model: PairingWorkflowViewModel, val elderId: String, val elderToken: String)

    private suspend fun fixture(): Fixture {
        val transport = ControlledHttp()
        val repository = DebugChainRepository(context, base, "c1-isolation-${operation()}", transport)
        assertTrue(repository.login(UserRole.CAREGIVER, "C1 isolation Caregiver") is Outcome.Success)
        waitFor("trusted fixture") { repository.services.trusted.value != null }
        val raw = ChainHttp(base)
        val login = raw.call("POST", "/api/v1/auth/anonymous", json = protocol.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(operation(), UserRole.ELDER, "C1 isolation Elder")))
        assertEquals(201, login.status)
        val elder = protocol.decodeFromString(AuthResponse.serializer(), login.text())
        val issued = raw.call("POST", "/api/v1/pairing-codes", elder.accessToken, operation(), "{}")
        assertEquals(201, issued.status)
        val code = protocol.decodeFromString(PairingCodeView.serializer(), issued.text())
        val begun = repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) as Outcome.Success
        val submitted = repository.requestPairing(code.code, begun.value)
        assertTrue(submitted is OnlineCommandResult.Confirmed)
        val request = (submitted as OnlineCommandResult.Confirmed).value
        assertEquals(200, raw.call("POST", "/api/v1/pairing-requests/${request.id}/accept", elder.accessToken, operation(), "{}").status)
        assertTrue(repository.refresh() is Outcome.Success)
        assertTrue(repository.observeRelationships().first().value.any { it.elderId == elder.user.id && it.status == CareRelationshipStatus.ACTIVE })
        lateinit var model: PairingWorkflowViewModel
        instrumentation.runOnMainSync {
            model = PairingWorkflowViewModel(repository, repository, repository, repository.services.trusted)
            stores += ViewModelStore().apply { put("c1-isolation", model) }
        }
        waitFor("observed authorized fixture") { model.state.value.ready && model.state.value.requestsKnown && model.state.value.requests.any { it.elderId == elder.user.id } }
        instrumentation.runOnMainSync { model.setCode("00112233") }
        assertEquals("00112233", model.state.value.code)
        return Fixture(repository, transport, model, elder.user.id, elder.accessToken)
    }

    @Test fun current401ClearsSessionDraftRequestsAndRelationships(): Unit = runBlocking {
        optIn()
        val f = fixture()
        val transport = f.transport as ControlledHttp
        val fault = ReadFault(unauthorized = true, hold = false)
        transport.next.set(fault)
        assertTrue(f.repository.refresh() is Outcome.Failure)
        assertEquals(401, withTimeout(30_000) { fault.seen.await() })
        waitFor("401 clears current session") { f.repository.services.trusted.value == null && !f.model.state.value.ready }
        assertEquals(AuthReadiness.SIGNED_OUT, f.repository.observeSession().first().readiness)
        assertNull(f.repository.services.scope.value)
        assertEquals("", f.model.state.value.code)
        assertTrue(f.model.state.value.requests.isEmpty())
        assertEquals(PairingNotice.SessionUnavailable, f.model.state.value.notice)
        val requests = f.repository.observeRequests().first()
        val relationships = f.repository.observeRelationships().first()
        assertEquals(AccessState.UNKNOWN, requests.access)
        assertEquals(AccessState.UNKNOWN, relationships.access)
        assertTrue(requests.value.isEmpty())
        assertTrue(relationships.value.isEmpty())
        assertTrue(f.repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) is Outcome.Failure)
        assertEquals(1, transport.writes.get())
    }

    @Test fun obsolete401CannotSignOutReauthenticatedSession(): Unit = runBlocking { obsoleteRead(true) }

    @Test fun obsoleteSuccessCannotAuthorizeReauthenticatedCache(): Unit = runBlocking { obsoleteRead(false) }

    @Test fun forbiddenOperationRetainsSessionAndNeverAutomaticallyReplays(): Unit = runBlocking {
        optIn()
        val f = fixture()
        val transport = f.transport as ControlledHttp
        val trusted = f.repository.services.trusted.value
        val begun = f.repository.begin(OnlineCommandKind.ISSUE_PAIRING_CODE, null) as Outcome.Success
        val result = f.repository.issueCode(begun.value)
        assertEquals(403, transport.lastRealStatus.get())
        assertTrue(result is OnlineCommandResult.Rejected)
        assertEquals("ROLE_NOT_ALLOWED", (result as OnlineCommandResult.Rejected).error.error.code)
        assertTrue("403 must retain common session", trusted == f.repository.services.trusted.value)
        assertEquals(AuthReadiness.READY, f.repository.observeSession().first().readiness)
        val writes = transport.mutations.get()
        val resumed = f.repository.resume(begun.value.operationId) as Outcome.Success
        assertEquals(OnlineCommandState.REJECTED, resumed.value.state)
        assertEquals(writes, transport.mutations.get())
        assertNotEquals(AccessState.AUTHORIZED, f.repository.observeRelationships().first().access)
    }

    @Test fun revokedRelationship404ClearsAuthorizationButRetainsSession(): Unit = runBlocking {
        optIn()
        val f = fixture()
        val trusted = f.repository.services.trusted.value
        val relationship = f.repository.observeRelationships().first().value.single { it.elderId == f.elderId }
        assertEquals(200, ChainHttp(base).call("POST", "/api/v1/care-relationships/${relationship.id}/revoke",
            f.elderToken, operation(), "{}").status)
        val result = f.repository.refresh(f.elderId)
        assertEquals(404, (f.transport as ControlledHttp).lastRealStatus.get())
        assertTrue(result is Outcome.Failure)
        val error = (result as Outcome.Failure).error as RepositoryError.Remote
        assertEquals("RESOURCE_NOT_ACCESSIBLE", error.error.code)
        assertTrue("404 must not sign out common session", trusted == f.repository.services.trusted.value)
        assertEquals(AuthReadiness.READY, f.repository.observeSession().first().readiness)
        waitFor("denied cache no longer shown") { !f.model.state.value.requestsKnown && f.model.state.value.requests.isEmpty() }
        assertNotEquals(AccessState.AUTHORIZED, f.repository.observeRelationships().first().access)
        assertNotEquals(AccessState.AUTHORIZED, f.repository.observePhotos(f.elderId).first().access)
    }

    @Test fun temporaryReadFailureRetainsStaleCacheWithoutClaimingRefreshSuccess(): Unit = runBlocking {
        optIn()
        val f = fixture()
        val transport = f.transport as ControlledHttp
        val trusted = f.repository.services.trusted.value
        val before = f.repository.observeRequests().first()
        transport.temporaryRead.set(true)
        instrumentation.runOnMainSync { f.model.refresh() }
        waitFor("temporary read failure") { !f.model.state.value.busy && f.model.state.value.notice == PairingNotice.RefreshFailed }
        val after = f.repository.observeRequests().first()
        assertEquals(AccessState.AUTHORIZED, after.access)
        assertTrue(after.isStale)
        assertEquals(before.lastSuccessfulSyncAtEpochMillis, after.lastSuccessfulSyncAtEpochMillis)
        assertTrue(after.value.any { it.elderId == f.elderId })
        assertTrue(f.model.state.value.requestsKnown && f.model.state.value.stale)
        assertTrue("Temporary read fault must not end session", trusted == f.repository.services.trusted.value)
        instrumentation.runOnMainSync { f.model.refresh() }
        waitFor("explicit read recovers") { !f.model.state.value.busy && f.model.state.value.notice == PairingNotice.Refreshed && !f.model.state.value.stale }
        assertEquals(1, transport.writes.get())
    }

    @Test fun temporaryWriteResultRetainsOriginalKeyAndPayloadUntilRecovery(): Unit = runBlocking {
        optIn()
        val f = fixture()
        val transport = f.transport as ControlledHttp
        val raw = ChainHttp(base)
        val login = raw.call("POST", "/api/v1/auth/anonymous", json = protocol.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(operation(), UserRole.ELDER, "Temporary write Elder")))
        assertEquals(201, login.status)
        val elder = protocol.decodeFromString(AuthResponse.serializer(), login.text())
        val issued = raw.call("POST", "/api/v1/pairing-codes", elder.accessToken, operation(), "{}")
        assertEquals(201, issued.status)
        val code = protocol.decodeFromString(PairingCodeView.serializer(), issued.text())
        val original = (f.repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) as Outcome.Success).value
        val before = f.repository.observeCommands().first().map { it.operationId }.toSet()
        val index = transport.writeDigests.size
        transport.temporaryWrite.set(true)
        val result = f.repository.requestPairing(code.code, original)
        assertEquals(201, transport.lastRealStatus.get())
        assertTrue(result is OnlineCommandResult.AwaitingConfirmation)
        assertTrue("Unknown result must retain original operation", result.operationId == original.operationId)
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, f.repository.observeCommands().first().single { it.operationId == original.operationId }.state)
        assertTrue(f.repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) is Outcome.Failure)
        val recovered = f.repository.resume(original.operationId) as Outcome.Success
        assertEquals(OnlineCommandState.CONFIRMED, recovered.value.state)
        assertEquals(CacheState.APPLIED, recovered.value.cacheState)
        assertEquals(before, f.repository.observeCommands().first().map { it.operationId }.toSet())
        assertEquals(index + 2, transport.writeDigests.size)
        assertEquals(transport.writeDigests[index], transport.writeDigests[index + 1])
        val response = raw.call("GET", "/api/v1/pairing-requests", elder.accessToken)
        assertEquals(200, response.status)
        val request = protocol.decodeFromString(ItemsResponse.serializer(PairingRequestView.serializer()), response.text()).items.single()
        assertEquals(PairingRequestStatus.PENDING, request.status)
        assertNull(request.relationshipId)
    }

    private suspend fun obsoleteRead(unauthorized: Boolean) = coroutineScope {
        optIn()
        val f = fixture()
        val repository = f.repository
        val transport = f.transport as ControlledHttp
        val old = checkNotNull(repository.services.trusted.value)
        val fault = ReadFault(unauthorized, hold = true)
        transport.next.set(fault)
        val pending = async(Dispatchers.IO) { repository.refresh() }
        lateinit var fresh: TrustedSessionContext
        try {
            assertEquals(if (unauthorized) 401 else 200, withTimeout(30_000) { fault.seen.await() })
            assertTrue(repository.services.signOut() is Outcome.Success)
            waitFor("logout clears form") { !f.model.state.value.ready && f.model.state.value.code.isEmpty() && f.model.state.value.requests.isEmpty() }
            assertTrue(repository.login(UserRole.CAREGIVER, "C1 isolation Caregiver") is Outcome.Success)
            waitFor("new trusted generation") { repository.services.trusted.value?.let { it != old } == true && f.model.state.value.ready }
            fresh = checkNotNull(repository.services.trusted.value)
            assertTrue("Same anonymous account scope must be preserved", old.scope == fresh.scope)
            assertNotEquals(old.generation, fresh.generation)
            assertTrue(f.model.state.value.requests.isEmpty())
            assertEquals("", f.model.state.value.code)
        } finally { fault.release.complete(Unit) }
        assertTrue(withTimeout(30_000) { pending.await() } is Outcome.Failure)
        assertTrue("Late response must preserve the replacement lease", fresh == repository.services.trusted.value)
        assertEquals(AuthReadiness.READY, repository.observeSession().first().readiness)
        assertTrue("Replacement account must remain current", old.scope.userId == repository.observeSession().first().user?.id)
        assertNotEquals(AccessState.AUTHORIZED, repository.observeRequests().first().access)
        assertNotEquals(AccessState.AUTHORIZED, repository.observeRelationships().first().access)
        assertTrue(f.model.state.value.requests.isEmpty())
        assertEquals("", f.model.state.value.code)
        assertEquals(1, transport.writes.get())
        // Only a fresh request under the replacement lease may authorize current cache again.
        assertTrue(repository.refresh() is Outcome.Success)
        waitFor("current authorized refresh") { f.model.state.value.requestsKnown && f.model.state.value.requests.any { it.elderId == f.elderId } }
        assertTrue("Fresh refresh must preserve the replacement lease", fresh == repository.services.trusted.value)
        assertEquals(AccessState.AUTHORIZED, repository.observeRelationships().first().access)
        assertEquals(1, transport.writes.get())
    }
}
