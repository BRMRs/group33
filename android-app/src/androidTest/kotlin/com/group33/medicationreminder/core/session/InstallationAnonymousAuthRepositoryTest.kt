package com.group33.medicationreminder.core.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.shared.model.UserRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android SharedPreferences, without a fake AuthRepository. */
@RunWith(AndroidJUnit4::class)
class InstallationAnonymousAuthRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("group33_anonymous_installation", 0)

    @Before
    fun reset() { assertTrue(prefs.edit().clear().commit()) }

    @After
    fun cleanUp() { assertTrue(prefs.edit().clear().commit()) }

    @Test
    fun newInstallationIsSignedOut() = runBlocking {
        val state = InstallationAnonymousAuthRepository(context).observeSession().first()
        assertEquals(AuthReadiness.SIGNED_OUT, state.readiness)
        assertNull(state.user)
    }

    @Test
    fun recreatedAdapterRestoresSameIdentityRoleAndName() = runBlocking {
        val original = InstallationAnonymousAuthRepository(context)
        assertTrue(original.login(UserRole.ELDER, "  Fictional Elder  ") is Outcome.Success)
        val expected = original.observeSession().first()
        // Flush pending apply writes to disk before recreating the adapter.
        assertTrue(prefs.edit().commit())
        val restored = InstallationAnonymousAuthRepository(context).observeSession().first()
        assertEquals(expected, restored)
        assertEquals(AuthReadiness.READY, restored.readiness)
        assertEquals("Fictional Elder", restored.user?.displayName)
        assertEquals(UserRole.ELDER, restored.user?.role)
        assertTrue(restored.user!!.id.startsWith("install:"))
    }

    @Test
    fun conflictingRoleIsRejectedAndCannotOverwritePersistedSession() = runBlocking {
        val repository = InstallationAnonymousAuthRepository(context)
        repository.login(UserRole.ELDER, "Fictional Elder")
        val expected = repository.observeSession().first()
        val restored = InstallationAnonymousAuthRepository(context)
        val result = restored.login(UserRole.CAREGIVER, "Conflicting role")
        assertEquals(Outcome.Failure(RepositoryError.Local(LocalFailureCode.INVALID_INPUT)), result)
        assertEquals(expected, restored.observeSession().first())
        assertTrue(prefs.edit().commit())
        assertEquals(expected, InstallationAnonymousAuthRepository(context).observeSession().first())
    }
}
