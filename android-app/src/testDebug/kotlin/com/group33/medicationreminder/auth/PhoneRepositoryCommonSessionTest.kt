package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PhoneRepositoryCommonSessionTest {
    private class Sessions : PhoneSessionAccess {
        private class Attempt : PhoneSignInAttempt
        var current: PhoneSignInAttempt? = null
        var account: PhoneAccount? = null
        var writes = 0
        var failStorage = false
        override suspend fun begin(): PhoneSignInAttempt = Attempt().also { current = it; account = null }
        override suspend fun isCurrent(attempt: PhoneSignInAttempt): Boolean {
            if (failStorage) throw PhoneSessionStorageException()
            return current === attempt
        }
        override suspend fun complete(attempt: PhoneSignInAttempt, session: AppSession): Boolean {
            if (!isCurrent(attempt)) return false
            current = null; account = session.account; writes++; return true
        }
        override suspend fun restore() = account
        override suspend fun clear() { current = null; account = null }
    }
    private class Server : PhoneServerClient {
        var calls = 0
        var ticket: String? = null
        var failure: Exception? = null
        var beforeResponse: (() -> Unit)? = null
        var response: VerifiedPhone = VerifiedPhone.Existing(session())
        override fun grant(): FusionSdkGrant = error("SDK not part of this session-boundary test")
        override fun verify(token: String, expectedPhone: String?): VerifiedPhone = error("No provider proof fixture")
        override fun register(ticket: String, role: UserRole, displayName: String?): VerifiedPhone {
            calls++; this.ticket = ticket
            val result = response
            val error = failure
            beforeResponse?.invoke()
            error?.let { throw it }
            return result
        }
    }
    private class Fixture {
        val sessions = Sessions()
        val server = Server()
        val repo = FusionPhoneAuthRepository(server, sessions)
        suspend fun pending() { repo.completeVerifiedResponse(repo.beginAuthentication(), VerifiedPhone.New("fictional-ticket")) }
    }

    @Test fun new_account_requires_role_without_publishing_a_session() = runBlocking {
        val f = Fixture(); f.pending()
        assertNull(f.repo.restore()); assertEquals(0, f.sessions.writes)
        assertTrue(f.repo.register(UserRole.ELDER, null, null) is PhoneAuthResult.SignedIn)
        assertEquals("fictional-ticket", f.server.ticket); assertEquals(1, f.sessions.writes)
    }
    @Test fun existing_account_uses_the_verified_server_role() = runBlocking {
        val f = Fixture(); val response = f.repo.completeVerifiedResponse(f.repo.beginAuthentication(), VerifiedPhone.Existing(session()))
        assertEquals(UserRole.CAREGIVER, (response as PhoneAuthResult.SignedIn).account.role)
        assertEquals(f.sessions.account, f.repo.restore()); assertEquals(0, f.server.calls)
    }
    @Test fun duplicate_verified_completion_cannot_save_again() = runBlocking {
        val f = Fixture(); val attempt = f.repo.beginAuthentication()
        f.repo.completeVerifiedResponse(attempt, VerifiedPhone.Existing(session()))
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED), f.repo.completeVerifiedResponse(attempt, VerifiedPhone.Existing(session())))
        assertEquals(1, f.sessions.writes)
    }
    @Test fun signout_fences_late_verified_response() = runBlocking {
        val f = Fixture(); val attempt = f.repo.beginAuthentication(); f.repo.signOut()
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED), f.repo.completeVerifiedResponse(attempt, VerifiedPhone.Existing(session())))
        assertNull(f.repo.restore()); assertEquals(0, f.sessions.writes)
    }
    @Test fun host_signout_blocks_old_registration_before_http() = runBlocking {
        val f = Fixture(); f.pending(); f.sessions.clear()
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED), f.repo.register(UserRole.ELDER, null, null))
        assertEquals(0, f.server.calls)
    }
    @Test fun new_authentication_discards_previous_registration_ticket() = runBlocking {
        val f = Fixture(); f.pending(); f.repo.beginAuthentication()
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED), f.repo.register(UserRole.ELDER, null, null))
        assertEquals(0, f.server.calls)
    }
    @Test fun transient_registration_failure_keeps_original_ticket_for_explicit_retry() = runBlocking {
        val f = Fixture(); f.pending(); f.server.failure = FusionHttpException(503, "SERVICE_UNAVAILABLE")
        assertTrue(f.repo.register(UserRole.ELDER, null, null) is PhoneAuthResult.Failed)
        assertNull(f.repo.restore()); assertEquals(1, f.server.calls)
        f.server.failure = null
        assertTrue(f.repo.register(UserRole.ELDER, null, null) is PhoneAuthResult.SignedIn)
        assertEquals("fictional-ticket", f.server.ticket); assertEquals(2, f.server.calls)
    }
    @Test fun storage_failure_is_reported_before_registration_http() = runBlocking {
        val f = Fixture(); f.pending(); f.sessions.failStorage = true
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.STORAGE), f.repo.register(UserRole.ELDER, null, null))
        assertEquals(0, f.server.calls)
    }
    @Test fun registration_cancellation_is_not_mapped_to_a_network_error() = runBlocking {
        val f = Fixture(); f.pending(); f.server.failure = CancellationException()
        try { f.repo.register(UserRole.ELDER, null, null); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }
    @Test fun password_is_not_silently_discarded_during_registration() = runBlocking {
        val f = Fixture(); f.pending()
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED), f.repo.register(UserRole.CAREGIVER, "Example", "password"))
        assertEquals(0, f.server.calls); assertNull(f.repo.restore())
    }

    @Test fun signout_while_registration_http_is_pending_rejects_late_success() = runBlocking {
        val f = Fixture(); f.pending()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.server.beforeResponse = {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release registration response" }
        }
        val registration = async { f.repo.register(UserRole.ELDER, null, null) }
        try {
            withTimeout(10_000) { entered.await() }
            f.repo.signOut()
        } finally { release.countDown() }
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED), registration.await())
        assertNull(f.repo.restore()); assertEquals(0, f.sessions.writes)
        assertEquals(1, f.server.calls)
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED), f.repo.register(UserRole.ELDER, null, null))
        assertEquals(1, f.server.calls)
    }

    @Test fun late_registration_success_cannot_consume_replacement_ticket() = runBlocking {
        val f = Fixture(); f.pending()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.server.beforeResponse = {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release registration response" }
        }
        val oldRegistration = async { f.repo.register(UserRole.ELDER, null, null) }
        try {
            withTimeout(10_000) { entered.await() }
            val replacement = f.repo.beginAuthentication()
            assertEquals(PhoneAuthResult.RegistrationRequired,
                f.repo.completeVerifiedResponse(replacement, VerifiedPhone.New("replacement-ticket")))
        } finally { release.countDown() }
        assertEquals(PhoneAuthResult.Failed(PhoneAuthError.CODE_EXPIRED), oldRegistration.await())
        assertNull(f.repo.restore()); assertEquals(0, f.sessions.writes)
        f.server.beforeResponse = null
        f.server.response = VerifiedPhone.Existing(AppSession(
            PhoneAccount("replacement-user", UserRole.ELDER, "Example elder"), "replacement.bearer", 900))
        val result = f.repo.register(UserRole.ELDER, null, null) as PhoneAuthResult.SignedIn
        assertEquals("replacement-user", result.account.id)
        assertEquals("replacement-ticket", f.server.ticket)
        assertEquals(result.account, f.repo.restore()); assertEquals(1, f.sessions.writes)
        assertEquals(2, f.server.calls)
    }
    companion object {
        private fun session() = AppSession(PhoneAccount("fictional-user", UserRole.CAREGIVER, "Example caregiver"), "fictional.bearer", 900)
    }
}
