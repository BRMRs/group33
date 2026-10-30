package com.group33.medicationreminder.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.shared.model.UserRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real repository completion + encrypted file/Keystore; verified replies are fictional inputs. */
class PhoneSessionFencingTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(app.noBackupFilesDir, "phone-session-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(app) {
        override fun getNoBackupFilesDir() = directory
        override fun getApplicationContext(): Context = this
    }
    private val environment = "https://phone-fixture.invalid/"
    private fun session(id: String) = AppSession(PhoneAccount(id, UserRole.ELDER, "Fictional"),
        "FICTIONAL_PRIVATE_TOKEN_$id", System.currentTimeMillis() + 60_000)
    private fun repository() = FusionPhoneAuthRepository(context, environment, false)

    @After fun cleanup() { directory.setWritable(true, true); directory.deleteRecursively() }

    @Test fun lateVerifiedResponseAfterSignOutCannotPersistOrRestoreOldAccount() = runBlocking {
        val old = repository()
        val attempt = old.beginAuthentication()
        repository().signOut()
        assertTrue(old.completeVerifiedResponse(attempt, VerifiedPhone.Existing(session("old"))) is PhoneAuthResult.Failed)
        assertNull(repository().restore())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun obsoleteRegistrationAndDuplicateCompletionCannotReplaceNewAccount() = runBlocking {
        val old = repository(); val oldAttempt = old.beginAuthentication()
        assertEquals(PhoneAuthResult.RegistrationRequired, old.completeVerifiedResponse(oldAttempt, VerifiedPhone.New("fixture-ticket")))
        val fresh = repository(); val freshAttempt = fresh.beginAuthentication()
        assertTrue(fresh.completeVerifiedResponse(freshAttempt, VerifiedPhone.Existing(session("fresh"))) is PhoneAuthResult.SignedIn)
        assertTrue(old.completeVerifiedResponse(oldAttempt, VerifiedPhone.Existing(session("old"))) is PhoneAuthResult.Failed)
        assertTrue(fresh.completeVerifiedResponse(freshAttempt, VerifiedPhone.Existing(session("duplicate"))) is PhoneAuthResult.Failed)
        assertEquals("fresh", repository().restore()?.id)
    }

    @Test fun failedDeletionBlocksOldRestoreAndLateCompletionUntilClearCanPersist() = runBlocking {
        val storage = PhoneSessionStore(context, environment)
        assertTrue(storage.save(storage.beginAttempt(), session("old")))
        val pending = storage.beginAttempt()
        assertTrue(directory.setWritable(false, false))
        try {
            var failed = false
            try { repository().signOut() } catch (_: Exception) { failed = true }
            assertTrue("A failed file deletion must be surfaced", failed)
            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("phone-auth-session-") })
            assertFalse(storage.save(pending, session("late")))
            var restored = false
            try { restored = PhoneSessionStore(context, environment).restore() != null } catch (_: Exception) { }
            assertFalse(restored)
        } finally { assertTrue(directory.setWritable(true, true)) }
        assertNull(PhoneSessionStore(context, environment).restore())
        assertNull(repository().restore())
    }

    @Test fun profilesKeepIndependentAttemptsAndPrivateRecords() = runBlocking {
        val a = PhoneSessionStore(context, environment)
        val b = PhoneSessionStore(context, "https://other-fixture.invalid/")
        val aa = a.beginAttempt(); val bb = b.beginAttempt()
        assertTrue(a.save(aa, session("a")))
        assertFalse(b.save(aa, session("foreign")))
        assertTrue(b.save(bb, session("b")))
        a.clear()
        assertNull(a.restore())
        assertEquals("b", b.restore()?.account?.id)
        directory.listFiles().orEmpty().forEach { file ->
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("FICTIONAL_PRIVATE_TOKEN"))
        }
    }
}
