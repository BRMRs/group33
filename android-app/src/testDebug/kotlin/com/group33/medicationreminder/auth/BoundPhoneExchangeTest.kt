package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BoundPhoneExchangeTest {
    private val installation = "00000000-0000-4000-8000-000000000001"
    private val capability = "A".repeat(43)
    private fun grant(token: String = "sdk-only", cap: String = capability, scheme: String = "scheme", ttl: Long = 900) =
        FusionSdkGrant(scheme, token, cap, ttl)
    private inner class Server : PhoneServerClient {
        val grants = mutableListOf<Pair<String, String?>>()
        var verifications = 0
        var onGrant: () -> FusionSdkGrant = { grant() }
        var onVerify: () -> VerifiedPhone = { VerifiedPhone.New("T".repeat(43)) }
        override fun grant(installationId: String, authenticationAttemptToken: String?): FusionSdkGrant {
            grants += installationId to authenticationAttemptToken
            return onGrant()
        }
        override fun verify(installationId: String, authenticationAttemptToken: String, token: String,
                            expectedPhone: String?): VerifiedPhone {
            assertEquals(installation, installationId); assertEquals(capability, authenticationAttemptToken)
            assertEquals("proof", token); verifications++
            return onVerify()
        }
        override fun register(installationId: String, ticket: String, role: UserRole, displayName: String?): VerifiedPhone =
            error("Not a registration fixture")
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected fail-closed attempt") } catch (_: Exception) { }
    }

    @Test fun external_authentication_replacement_stops_sdk_callback_token() = runBlocking {
        val server = Server(); val current = AtomicBoolean(true)
        val exchange = BoundPhoneExchange.start(server, installation, { 0 }, current::get, current::get)
        assertEquals("sdk-only", exchange.sdkToken())
        current.set(false)
        assertNull(exchange.sdkToken())
    }

    @Test fun stale_preflight_validation_cannot_start_http_for_replaced_attempt() = runBlocking {
        val server = Server(); val current = AtomicBoolean(true)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var first = true
        val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            rejects { BoundPhoneExchange.start(server, installation, { 0 }, current::get) {
                val answer = current.get()
                if (first) { first = false; entered.complete(Unit); release.await() }
                answer
            } }
        }
        try {
            kotlinx.coroutines.withTimeout(5_000) { entered.await() }
            current.set(false); release.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000) { pending.await() }
            assertEquals(0, server.grants.size)
        } finally { release.complete(Unit); pending.cancel() }
    }

    @Test fun start_and_refresh_keep_original_installation_and_private_capability() = runBlocking {
        val server = Server(); val clock = AtomicLong(0)
        val exchange = BoundPhoneExchange.start(server, installation, clock::get, { true }) { true }
        assertEquals("sdk-only", exchange.sdkToken()); assertNotEquals(capability, exchange.sdkToken())
        clock.set(240_000); server.onGrant = { grant("new-sdk", ttl = 660) }
        assertTrue(exchange.refresh()); assertEquals("new-sdk", exchange.sdkToken())
        assertEquals(listOf(installation to null, installation to capability), server.grants)
    }

    @Test fun refresh_cannot_extend_original_deadline_even_with_a_new_900_second_grant() = runBlocking {
        val server = Server(); val clock = AtomicLong(0)
        val exchange = BoundPhoneExchange.start(server, installation, clock::get, { true }) { true }
        clock.set(899_000); assertTrue(exchange.refresh())
        clock.set(900_000); assertNull(exchange.sdkToken())
        rejects { exchange.verify("proof", null) }; assertEquals(0, server.verifications)
        assertEquals(2, server.grants.size)
    }

    @Test fun shorter_refresh_lifetime_tightens_deadline() = runBlocking {
        val server = Server(); val clock = AtomicLong(0)
        val exchange = BoundPhoneExchange.start(server, installation, clock::get, { true }) { true }
        clock.set(100_000); server.onGrant = { grant(ttl = 2) }; exchange.refresh()
        clock.set(102_000); assertNull(exchange.sdkToken())
        rejects { exchange.refresh() }; assertEquals(2, server.grants.size)
    }

    @Test fun late_initial_grant_is_discarded_before_sdk_initialization() = runBlocking {
        val server = Server(); val clock = AtomicLong(0)
        server.onGrant = { clock.set(900_000); grant() }
        rejects { BoundPhoneExchange.start(server, installation, clock::get, { true }) { true } }
        assertEquals(1, server.grants.size); assertEquals(0, server.verifications)
    }

    @Test fun malformed_or_retargeted_refresh_closes_scene_without_a_new_start() = runBlocking {
        for (bad in listOf(grant(cap = "B".repeat(43)), grant(scheme = "other"), grant(ttl = 901),
                grant(cap = ""), grant(token = ""), grant(ttl = 0))) {
            val server = Server()
            val exchange = BoundPhoneExchange.start(server, installation, { 0 }, { true }) { true }
            server.onGrant = { bad }; rejects { exchange.refresh() }; assertNull(exchange.sdkToken())
            rejects { exchange.refresh() }; assertEquals(2, server.grants.size)
            assertEquals(capability, server.grants.last().second)
        }
    }

    @Test fun signout_or_replacement_during_grant_discards_response() = runBlocking {
        val server = Server(); val current = AtomicBoolean(true)
        server.onGrant = { current.set(false); grant() }
        rejects { BoundPhoneExchange.start(server, installation, { 0 }, current::get, current::get) }
        assertEquals(1, server.grants.size)
    }

    @Test fun late_or_foreign_generation_verification_never_publishes_result() = runBlocking {
        for (expire in listOf(true, false)) {
            val server = Server(); val clock = AtomicLong(0); val current = AtomicBoolean(true)
            val exchange = BoundPhoneExchange.start(server, installation, clock::get, current::get, current::get)
            server.onVerify = {
                if (expire) clock.set(900_000) else current.set(false)
                VerifiedPhone.New("T".repeat(43))
            }
            rejects { exchange.verify("proof", null) }; assertNull(exchange.sdkToken())
            assertEquals(1, server.verifications)
        }
    }

    @Test fun uncertain_supplier_result_cannot_resend_proof_or_refresh() = runBlocking {
        val server = Server(); val exchange = BoundPhoneExchange.start(server, installation, { 0 }, { true }) { true }
        server.onVerify = { throw FusionHttpException(503, "PHONE_AUTH_UNAVAILABLE") }
        rejects { exchange.verify("proof", null) }; rejects { exchange.verify("proof", null) }
        assertFalse(exchange.refresh()); assertEquals(1, server.verifications); assertEquals(1, server.grants.size)
    }

    @Test fun unknown_refresh_closes_original_attempt_instead_of_falling_back_to_start() = runBlocking {
        val server = Server(); val exchange = BoundPhoneExchange.start(server, installation, { 0 }, { true }) { true }
        server.onGrant = { throw java.io.IOException("Fictional lost response") }
        rejects { exchange.refresh() }; assertNull(exchange.sdkToken())
        rejects { exchange.refresh() }; assertEquals(2, server.grants.size)
        assertEquals(capability, server.grants.last().second)
    }

    @Test fun cancellation_or_closed_scene_prevents_more_network_work() = runBlocking {
        val server = Server(); val exchange = BoundPhoneExchange.start(server, installation, { 0 }, { true }) { true }
        exchange.close(); assertNull(exchange.sdkToken())
        rejects { exchange.verify("proof", null) }; assertEquals(0, server.verifications)
    }

    @Test fun valid_verification_is_single_use_and_invalid_installation_never_calls_http() = runBlocking {
        val server = Server(); val exchange = BoundPhoneExchange.start(server, installation, { 0 }, { true }) { true }
        assertTrue(exchange.verify("proof", null) is VerifiedPhone.New)
        rejects { exchange.verify("proof", null) }; assertEquals(1, server.verifications)
        rejects { BoundPhoneExchange.start(server, "anonymous-installation", { 0 }, { true }) { true } }
        assertEquals(1, server.grants.size)
    }

    @Test fun refresh_and_verification_are_serialized_under_the_same_original_capability() = runBlocking {
        val server = Server(); val exchange = BoundPhoneExchange.start(server, installation, { 0 }, { true }) { true }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        server.onGrant = { entered.complete(Unit); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); grant() }
        val refresh = async { exchange.refresh() }
        try {
            kotlinx.coroutines.withTimeout(10_000) { entered.await() }
            val verify = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                exchange.verify("proof", null)
            }
            assertEquals(0, server.verifications)
            release.countDown()
            assertTrue(refresh.await()); assertTrue(verify.await() is VerifiedPhone.New)
            assertEquals(1, server.verifications)
        } finally { release.countDown() }
    }

    @Test fun refresh_response_after_signout_or_deadline_cannot_replace_sdk_token() = runBlocking {
        for (expired in listOf(true, false)) {
            val server = Server(); val clock = AtomicLong(0); val current = AtomicBoolean(true)
            val exchange = BoundPhoneExchange.start(server, installation, clock::get, current::get, current::get)
            server.onGrant = { if (expired) clock.set(900_000) else current.set(false); grant("late-sdk") }
            rejects { exchange.refresh() }; assertNull(exchange.sdkToken())
            assertEquals(2, server.grants.size); assertEquals(0, server.verifications)
        }
    }

    @Test fun suspended_private_store_validation_cannot_cross_deadline_and_start_network_work() = runBlocking {
        for (refresh in listOf(true, false)) {
            val server = Server(); val clock = AtomicLong(0); val delayValidation = AtomicBoolean(false)
            val exchange = BoundPhoneExchange.start(server, installation, clock::get, { true }) {
                if (delayValidation.get()) clock.set(900_000)
                true
            }
            delayValidation.set(true)
            rejects { if (refresh) exchange.refresh() else exchange.verify("proof", null) }
            assertEquals(1, server.grants.size); assertEquals(0, server.verifications)
            assertNull(exchange.sdkToken())
        }
    }

    @Test fun ticket_expiry_is_anchored_before_network_response_not_when_role_ui_is_opened() = runBlocking {
        val server = Server(); val clock = AtomicLong(1_000)
        val exchange = BoundPhoneExchange.start(server, installation, clock::get, { true }) { true }
        server.onVerify = { clock.set(5_000); VerifiedPhone.New("T".repeat(43)) }
        val result = exchange.verify("proof", null) as VerifiedPhone.New
        assertEquals(301_000L, result.localDeadline)
    }
}
