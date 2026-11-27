package com.group33.medicationreminder.auth

import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import com.group33.medicationreminder.core.session.PrivateSessionStore
import com.group33.medicationreminder.integration.ChainHttp
import com.group33.medicationreminder.integration.DebugChainRepository
import com.group33.shared.model.UserRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Fictional verified responses through the actual S1 Keystore/store and business HTTP adapter. No SDK/provider call. */
class CommonPhoneSessionIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(context: android.content.Context, val http: Transport = Transport()) {
        val environment = "phone-session-test:${UUID.randomUUID()}"
        val namespace = "phone-test-${UUID.randomUUID()}"
        val store = PrivateSessionStore(context, environment, namespace)
        val phone = CommonPhoneSession(store)
        val business = DebugChainRepository(context, http.baseUrl, http = http,
            environment = environment, sessionStore = store, anonymousLoginAllowed = false)
        suspend fun login(id: String = "fictional-caregiver", token: String = "fictional.phone.token") {
            assertTrue(phone.complete(phone.begin(), AppSession(
                PhoneAccount(id, UserRole.CAREGIVER, "Fictional caregiver"), token, 900)))
            withTimeout(5_000) { business.observeSession().first { it.readiness == AuthReadiness.READY && it.user?.id == id } }
        }
    }
    private class Transport : ChainHttp("https://phone-fixture.invalid") {
        var calls = 0
        var token: String? = null
        var started: CompletableDeferred<Unit>? = null
        var response: CompletableDeferred<Reply>? = null
        override suspend fun call(method: String, path: String, token: String?, operationId: String?,
                                  json: String?, photo: File?, photoType: String?): Reply {
            calls++; this.token = token; started?.complete(Unit)
            return response?.await() ?: Reply(401, byteArrayOf(), null, null, null)
        }
    }
    @Test fun common_store_replacement_revokes_synchronous_sdk_token_without_phone_logout() = runBlocking {
        for (replacement in listOf("clear", "new-attempt", "completed-login")) {
            val f = Fixture(context)
            try {
                val attempt = f.phone.begin()
                assertTrue(f.phone.isCurrentSnapshot(attempt))
                var calls = 0
                val client = object : PhoneServerClient {
                    override fun grant(installationId: String, authenticationAttemptToken: String?): FusionSdkGrant {
                        calls++; return FusionSdkGrant("fixture-scheme", "fixture-sdk-token", "A".repeat(43), 900)
                    }
                    override fun verify(installationId: String, authenticationAttemptToken: String, token: String,
                                        expectedPhone: String?): VerifiedPhone = error("No SDK proof")
                    override fun register(installationId: String, ticket: String, role: UserRole,
                                          displayName: String?): VerifiedPhone = error("No registration")
                }
                val exchange = BoundPhoneExchange.start(client, attempt.installationId, { 0 },
                    { f.phone.isCurrentSnapshot(attempt) }) { f.phone.isCurrent(attempt) }
                assertEquals("fixture-sdk-token", exchange.sdkToken())
                // A second store instance shares the exact private environment/namespace.
                val external = PrivateSessionStore(context, f.environment, f.namespace)
                when (replacement) {
                    "clear" -> assertTrue(external.clear() is Outcome.Success)
                    "new-attempt" -> assertTrue(external.beginAuthentication() is Outcome.Success)
                    else -> assertTrue(f.phone.complete(attempt, AppSession(
                        PhoneAccount("fixture-account", UserRole.CAREGIVER, "Fixture"), "fixture.token", 900)))
                }
                assertFalse(f.phone.isCurrentSnapshot(attempt))
                var callbackToken: String? = "not-called"
                InstrumentationRegistry.getInstrumentation().runOnMainSync { callbackToken = exchange.sdkToken() }
                assertNull(callbackToken); assertEquals(1, calls)
                try { exchange.refresh(); fail("Old scene refreshed") } catch (_: Exception) { }
                assertEquals(1, calls)
            } finally { f.phone.clear(); f.business.close() }
        }
    }

    @Test fun phone_and_business_observe_the_same_real_private_session() = runBlocking {
        val f = Fixture(context)
        try {
            f.login()
            assertEquals(f.phone.restore()?.id, f.business.services.scope.value?.userId)
            val lease = (f.store.acquire() as Outcome.Success).value
            assertEquals(f.environment, lease.scope.environmentId)
            assertNotEquals(lease.scope.userId, lease.scope.installationId)
            assertEquals("fictional.phone.token", (f.store.credential(lease) as Outcome.Success).value.value)
            assertEquals(0, f.http.calls)
        } finally { f.phone.clear() }
    }
    @Test fun configured_phone_profile_cannot_silently_create_an_anonymous_actor() = runBlocking {
        val f = Fixture(context)
        try {
            f.login()
            assertTrue(f.business.login(UserRole.ELDER, "Different role") is Outcome.Failure)
            assertEquals(0, f.http.calls)
            assertEquals(UserRole.CAREGIVER, f.phone.restore()?.role)
        } finally { f.phone.clear() }
    }
    @Test fun denied_phone_business_token_clears_the_same_session_without_fallback() = runBlocking {
        val f = Fixture(context)
        try {
            f.login()
            assertTrue(f.business.refresh() is Outcome.Failure)
            assertEquals("fictional.phone.token", f.http.token)
            assertNull(f.phone.restore())
            assertNull(f.business.services.trusted.value)
            assertEquals(1, f.http.calls)
        } finally { f.phone.clear() }
    }
    @Test fun late_401_from_previous_phone_account_cannot_clear_new_account() = runBlocking {
        val transport = Transport().apply { started = CompletableDeferred(); response = CompletableDeferred() }
        val f = Fixture(context, transport)
        try {
            f.login("first-account", "first.token")
            val oldRead = async { f.business.refresh() }
            withTimeout(5_000) { transport.started!!.await() }
            f.login("second-account", "second.token")
            transport.response!!.complete(ChainHttp.Reply(401, byteArrayOf(), null, null, null))
            oldRead.await()
            assertEquals("second-account", f.phone.restore()?.id)
            assertEquals("second-account", f.business.services.scope.value?.userId)
        } finally { transport.response?.complete(ChainHttp.Reply(401, byteArrayOf(), null, null, null)); f.phone.clear() }
    }
    @Test fun merged_dashboard_401_clears_common_phone_session_without_fallback() = runBlocking {
        val f = Fixture(context)
        try {
            f.login()
            val dashboard = checkNotNull(f.business.services.caregiverDashboard)
            assertNotNull(f.business.services.doseEvents)
            assertTrue(dashboard.refresh("fictional-elder", LocalDateRange("2026-11-19", "2026-11-19")) is Outcome.Failure)
            assertEquals("fictional.phone.token", f.http.token)
            assertNull(f.phone.restore())
            assertNull(f.business.services.trusted.value)
            assertEquals(1, f.http.calls)
        } finally { f.phone.clear() }
    }
    @Test fun late_dashboard_401_does_not_clear_replacement_phone_session() = runBlocking {
        val transport = Transport().apply { started = CompletableDeferred(); response = CompletableDeferred() }
        val f = Fixture(context, transport)
        try {
            f.login("first-account", "first.token")
            val oldRead = async { checkNotNull(f.business.services.caregiverDashboard)
                .refresh("fictional-elder", LocalDateRange("2026-11-19", "2026-11-19")) }
            withTimeout(5_000) { transport.started!!.await() }
            f.login("second-account", "second.token")
            transport.response!!.complete(ChainHttp.Reply(401, byteArrayOf(), null, null, null))
            assertTrue(oldRead.await() is Outcome.Failure)
            assertEquals("second-account", f.phone.restore()?.id)
            assertEquals("second-account", f.business.services.scope.value?.userId)
        } finally { transport.response?.complete(ChainHttp.Reply(401, byteArrayOf(), null, null, null)); f.phone.clear() }
    }
    @Test fun reopen_uses_the_common_session_and_logout_preserves_installation() = runBlocking {
        val f = Fixture(context)
        try {
            f.login()
            val oldLease = (f.store.acquire() as Outcome.Success).value
            val reopened = CommonPhoneSession(PrivateSessionStore(context, f.environment, f.namespace))
            assertEquals(f.phone.restore(), reopened.restore())
            assertTrue(f.business.services.signOut() is Outcome.Success)
            assertNull(reopened.restore())
            val attempt = (f.store.beginAuthentication() as Outcome.Success).value
            assertEquals(oldLease.scope.installationId, attempt.installationId)
            assertTrue((f.store.isCurrentAuthentication(attempt) as Outcome.Success).value)
            f.store.clear()
            assertFalse((f.store.isCurrentAuthentication(attempt) as Outcome.Success).value)
        } finally { f.phone.clear() }
    }
    @Test fun another_profile_or_logout_cannot_complete_an_old_phone_attempt() = runBlocking {
        val f = Fixture(context)
        try {
            val old = f.phone.begin()
            f.store.clear()
            assertFalse(f.phone.isCurrent(old))
            assertFalse(f.phone.complete(old, AppSession(PhoneAccount("old", UserRole.ELDER, "Old"), "old.token", 900)))
            assertNull(f.phone.restore())
        } finally { f.phone.clear() }
    }

    @Test fun suspended_old_ticket_check_cannot_replace_new_ticket_in_real_store() = runBlocking {
        val f = Fixture(context)
        try {
            withTimeout(10_000) {
                val checked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var pauseNextCheck = true
                val guarded = object : PhoneSessionAccess by f.phone {
                    override suspend fun isCurrent(attempt: PhoneSignInAttempt): Boolean {
                        val result = f.phone.isCurrent(attempt)
                        if (pauseNextCheck) {
                            pauseNextCheck = false
                            checked.complete(Unit)
                            release.await()
                        }
                        return result
                    }
                }
                var calls = 0
                var usedTicket: String? = null
                val client = object : PhoneServerClient {
                    override fun grant(installationId: String, authenticationAttemptToken: String?): FusionSdkGrant = error("No provider call")
                    override fun verify(installationId: String, authenticationAttemptToken: String, token: String, expectedPhone: String?): VerifiedPhone = error("No provider proof")
                    override fun register(installationId: String, ticket: String, role: UserRole, displayName: String?): VerifiedPhone {
                        calls++; usedTicket = ticket
                        return VerifiedPhone.Existing(AppSession(
                            PhoneAccount("replacement-account", UserRole.CAREGIVER, "Example caregiver"),
                            "replacement.token", 900))
                    }
                }
                val repository = FusionPhoneAuthRepository(client, guarded)
                val oldAttempt = repository.beginAuthentication()
                val oldResponse = async(start = CoroutineStart.UNDISPATCHED) {
                    repository.completeVerifiedResponse(oldAttempt, VerifiedPhone.New("old-ticket"))
                }
                checked.await()
                val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                    val attempt = repository.beginAuthentication()
                    repository.completeVerifiedResponse(attempt, VerifiedPhone.New("replacement-ticket"))
                }
                release.complete(Unit)
                oldResponse.await()
                assertEquals(PhoneAuthResult.RegistrationRequired, replacement.await())
                assertNull(f.phone.restore())
                assertTrue(repository.register(UserRole.CAREGIVER, "Example caregiver", null) is PhoneAuthResult.SignedIn)
                assertEquals("replacement-ticket", usedTicket); assertEquals(1, calls)
                assertEquals("replacement-account", f.phone.restore()?.id)
                val reopened = CommonPhoneSession(PrivateSessionStore(context, f.environment, f.namespace))
                assertEquals(f.phone.restore(), reopened.restore())
            }
        } finally { f.phone.clear() }
    }
}
