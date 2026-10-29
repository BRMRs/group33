package com.group33.medicationreminder.core.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.group33.medicationreminder.core.data.contract.*
import com.group33.medicationreminder.core.data.journal.RoomCommandJournal
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real private files + AndroidKeyStore; fictional authenticated-response inputs, no server claim. */
@RunWith(AndroidJUnit4::class)
class PrivateSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val namespace = "session-test-${UUID.randomUUID()}"
    private val environments = mutableSetOf<String>()
    private val clock = TestClock()
    private val user = User("fictional-elder", "Fictional private name", UserRole.ELDER)
    private val secret = "FICTIONAL_PRIVATE_BEARER_83"

    private fun store(environment: String = "local") = PrivateSessionStore(context, environment, namespace, clock)
        .also { environments.add(environment) }
    private fun <T> success(result: Outcome<T>): T {
        assertTrue(result is Outcome.Success)
        return (result as Outcome.Success).value
    }
    private fun localFailure(result: Outcome<*>, code: LocalFailureCode) {
        assertEquals(Outcome.Failure(RepositoryError.Local(code)), result)
    }
    private suspend fun state(s: PrivateSessionStore) = s.observeSession().first()
    private suspend fun login(s: PrivateSessionStore, token: String = secret): SessionLease {
        val attempt = success(s.beginAuthentication())
        return success(s.completeAuthentication(attempt, user, token, 60))
    }
    private fun file(environment: String = "local"): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$namespace\u0000$environment".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "sessions/$digest.bin")
    }

    @After fun cleanup() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        environments.forEach { env ->
            val base = file(env)
            base.setWritable(true)
            File(base.path.removeSuffix(".bin") + ".lock").setWritable(true)
            listOf(base, File(base.path + ".bak"), File(base.path + ".new"),
                File(base.path.removeSuffix(".bin") + ".lock")).forEach { it.deleteRecursively() }
            keys.deleteEntry("group33.session.${base.nameWithoutExtension}")
        }
    }

    @Test fun installation_and_verified_response_survive_real_reopen_without_plaintext() = runBlocking {
        val first = store()
        val lease = login(first)
        val second = store()
        assertEquals(user, success(second.restore()).user)
        val restored = success(second.acquire())
        assertEquals(lease.scope, restored.scope)
        assertEquals(secret, success(second.credential(restored)).value)
        val stored = file().readBytes().toString(Charsets.ISO_8859_1)
        listOf(secret, user.displayName, user.id, lease.scope.installationId).forEach { assertFalse(stored.contains(it)) }
        assertFalse(state(second).toString().contains(secret))
        assertFalse(success(second.credential(restored)).toString().contains(secret))
        assertTrue(file().canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
    }

    @Test fun concurrent_instances_keep_one_installation_and_only_latest_attempt_can_complete() = runBlocking {
        val a = store(); val b = store()
        val attempts = coroutineScope {
            val first = async { success(a.beginAuthentication()) }
            val second = async { success(b.beginAuthentication()) }
            listOf(first.await(), second.await())
        }
        assertEquals(attempts[0].installationId, attempts[1].installationId)
        val results = attempts.map { a.completeAuthentication(it, user, secret, 60) }
        assertEquals(1, results.count { it is Outcome.Success })
        assertEquals(1, results.count { it == Outcome.Failure(RepositoryError.Local(LocalFailureCode.SESSION_NOT_READY)) })
    }

    @Test fun clear_rejects_late_login_and_preserves_installation_and_business_journal() = runBlocking {
        val s = store(); val oldLease = login(s)
        val journalNamespace = "journal-test-${UUID.randomUUID()}"
        val journal = RoomCommandJournal.open(context, oldLease.scope, journalNamespace)
        val command = journal.begin(OnlineCommandKind.ISSUE_PAIRING_CODE, null)
        try {
            val pending = success(s.beginAuthentication())
            success(s.clear())
            localFailure(s.completeAuthentication(pending, user, secret, 60), LocalFailureCode.SESSION_NOT_READY)
            localFailure(s.credential(oldLease), LocalFailureCode.SESSION_NOT_READY)
            val next = success(store().beginAuthentication())
            assertEquals(oldLease.scope.installationId, next.installationId)
            assertEquals(command.operationId, journal.find(command.operationId)?.operationId)
            assertEquals(AuthReadiness.SIGNED_OUT, state(s).readiness)
        } finally {
            journal.close()
            context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(journalNamespace) }?.forEach { it.delete() }
        }
    }

    @Test fun obsolete_unauthorized_result_cannot_clear_new_login_and_tokens_rotate_once() = runBlocking {
        val a = store(); val old = login(a)
        val b = store(); val fresh = login(b, "FICTIONAL_NEW_TOKEN")
        assertFalse(success(a.invalidate(old)))
        localFailure(a.credential(old), LocalFailureCode.SESSION_NOT_READY)
        assertEquals("FICTIONAL_NEW_TOKEN", success(a.credential(fresh)).value)
        assertEquals(user, state(b).user)
        val attempt = success(a.beginAuthentication())
        success(a.completeAuthentication(attempt, user, secret, 60))
        localFailure(a.completeAuthentication(attempt, user, "SECOND_RESULT", 60), LocalFailureCode.SESSION_NOT_READY)
    }

    @Test fun expiry_exact_deadline_and_clock_before_issue_fail_closed_after_reopen() = runBlocking {
        val s = store(); val lease = login(s)
        clock.now += 60_000
        localFailure(store().credential(lease), LocalFailureCode.SESSION_NOT_READY)
        assertNull(success(s.restore()).user)
        val fresh = login(s)
        clock.now -= 1
        localFailure(store().credential(fresh), LocalFailureCode.SESSION_NOT_READY)
        assertNull(success(s.restore()).user)
    }

    @Test fun environments_and_foreign_lease_are_isolated_without_redirecting_credentials() = runBlocking {
        val local = store("local"); val cloud = store("cloud")
        val a = login(local, "LOCAL_TOKEN"); val b = login(cloud, "CLOUD_TOKEN")
        assertNotEquals(a.scope.installationId, b.scope.installationId)
        localFailure(cloud.credential(a), LocalFailureCode.SESSION_NOT_READY)
        localFailure(local.credential(b), LocalFailureCode.SESSION_NOT_READY)
        assertEquals("LOCAL_TOKEN", success(local.credential(a)).value)
        assertEquals("CLOUD_TOKEN", success(cloud.credential(b)).value)
        assertFalse(success(cloud.invalidate(a)))
    }

    @Test fun corrupt_ciphertext_is_not_overwritten_and_never_restores_an_identity() = runBlocking {
        val s = store(); login(s)
        val corrupted = file().readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file().writeBytes(corrupted)
        localFailure(store().restore(), LocalFailureCode.STORAGE_UNAVAILABLE)
        assertNull(state(s).user)
        assertArrayEquals(corrupted, file().readBytes())
        localFailure(s.beginAuthentication(), LocalFailureCode.STORAGE_UNAVAILABLE)
    }

    @Test fun missing_keystore_key_does_not_silently_generate_a_replacement_for_existing_data() = runBlocking {
        val s = store(); login(s)
        val bytes = file().readBytes()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "group33.session.${file().nameWithoutExtension}"
        keys.deleteEntry(alias)
        localFailure(store().restore(), LocalFailureCode.STORAGE_UNAVAILABLE)
        assertFalse(keys.containsAlias(alias))
        assertArrayEquals(bytes, file().readBytes())
    }

    @Test fun invalid_or_expired_auth_response_cannot_create_ready_session() = runBlocking {
        val s = store(); val attempt = success(s.beginAuthentication())
        localFailure(s.completeAuthentication(attempt, user, "bad\r\nAuthorization", 60), LocalFailureCode.INVALID_INPUT)
        localFailure(s.completeAuthentication(attempt, user, secret, Long.MAX_VALUE), LocalFailureCode.INVALID_INPUT)
        clock.now += 60_000
        localFailure(s.completeAuthentication(attempt, user, secret, 60), LocalFailureCode.SESSION_NOT_READY)
        assertEquals(AuthReadiness.SIGNED_OUT, state(s).readiness)
    }

    @Test fun filesystem_failure_returns_typed_failure_without_publishing_success() = runBlocking {
        val s = store(); val attempt = success(s.beginAuthentication())
        val lock = File(file().path.removeSuffix(".bin") + ".lock")
        assertTrue(lock.setWritable(false, false))
        try {
            localFailure(s.completeAuthentication(attempt, user, secret, 60), LocalFailureCode.STORAGE_UNAVAILABLE)
            assertNull(state(s).user)
        } finally { assertTrue(lock.setWritable(true, true)) }
        assertNull(success(store().restore()).user)
    }

    @Test fun expiry_between_ready_emission_and_timer_setup_notifies_observer() = runBlocking {
        val s = store(); login(s)
        val expired = withTimeout(2_000) {
            s.observeSession().first { view ->
                if (view.readiness == AuthReadiness.READY) {
                    clock.now += 60_000
                    false
                } else view.readiness == AuthReadiness.SIGNED_OUT
            }
        }
        assertNull(expired.user)
    }

    @Test fun failed_clear_blocks_old_credentials_and_retries_clear_on_restore() = runBlocking {
        val s = store(); val lease = login(s)
        val lock = File(file().path.removeSuffix(".bin") + ".lock")
        assertTrue(lock.setWritable(false, false))
        try { localFailure(s.clear(), LocalFailureCode.STORAGE_UNAVAILABLE) }
        finally { assertTrue(lock.setWritable(true, true)) }
        localFailure(store().credential(lease), LocalFailureCode.SESSION_NOT_READY)
        assertNull(success(store().restore()).user)
        localFailure(s.acquire(), LocalFailureCode.SESSION_NOT_READY)
    }

    private class TestClock(var now: Long = 1_800_000_000_000L) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis(): Long = now
    }
}
