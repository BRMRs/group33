package com.group33.medicationreminder.core.session

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** With phase=prepare/verify, the host kills the first process between invocations. */
class SessionProcessRecoveryTest {
    @Test fun private_session_reopens_in_a_new_store_or_explicit_cold_process_probe() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("sessionProbe") ?: UUID.randomUUID().toString()
        require(nonce.matches(Regex("[A-Za-z0-9-]+")))
        val namespace = "process-session-$nonce"
        val expected = File(context.noBackupFilesDir, "$namespace.expected")
        val phase = args.getString("phase")
        require(phase == null || phase == "prepare" || phase == "verify")
        val user = User("process-fixture-user", "Fictional process user", UserRole.ELDER)
        val token = "FICTIONAL_PROCESS_SESSION_TOKEN"
        fun <T> ok(result: Outcome<T>): T {
            assertTrue(result is Outcome.Success)
            return (result as Outcome.Success).value
        }
        val store = PrivateSessionStore(context, "local", namespace)
        try {
            if (phase != "verify") {
                val attempt = ok(store.beginAuthentication())
                ok(store.completeAuthentication(attempt, user, token, 3_600))
                expected.writeText("${Process.myPid()}\n${attempt.installationId}")
            }
            if (phase != "prepare") {
                val lines = expected.readLines()
                if (phase == "verify") assertNotEquals(lines[0].toInt(), Process.myPid())
                val fresh = PrivateSessionStore(context, "local", namespace)
                assertEquals(user, ok(fresh.restore()).user)
                val lease = ok(fresh.acquire())
                assertEquals(lines[1], lease.scope.installationId)
                assertEquals(token, ok(fresh.credential(lease)).value)
            }
        } finally {
            if (phase != "prepare") {
                expected.delete()
                val hash = MessageDigest.getInstance("SHA-256").digest("$namespace\u0000local".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val directory = File(context.noBackupFilesDir, "sessions")
                directory.listFiles()?.filter { it.name.startsWith(hash) }?.forEach { it.delete() }
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("group33.session.$hash") }
            }
        }
    }
}
