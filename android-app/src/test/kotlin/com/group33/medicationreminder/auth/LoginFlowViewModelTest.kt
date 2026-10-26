package com.group33.medicationreminder.auth

import androidx.lifecycle.ViewModelStore
import com.group33.shared.model.UserRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = FakeRepository()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun `role cannot be chosen before verified new account`() = runTest(dispatcher) {
        val model = model(); runCurrent()
        model.chooseRole(UserRole.ELDER)
        model.verifySms("bad", "123456")
        assertEquals(LoginStep.SIGN_IN, model.state.value.step)
        assertEquals(0, repository.verifications)
        assertEquals(LoginNotice.InvalidPhone, model.state.value.notice)

        model.verifySms("13800000000", "123456"); runCurrent()
        assertEquals(LoginStep.CHOOSE_ROLE, model.state.value.step)
        assertEquals(1, repository.verifications)
        assertNull(model.state.value.account)
    }

    @Test fun `existing account restores directly without offering role change`() = runTest(dispatcher) {
        repository.restored = PhoneAccount("server-user", UserRole.CAREGIVER, "Mei")
        val model = model(); runCurrent()
        assertEquals(LoginStep.SIGNED_IN, model.state.value.step)
        assertEquals(UserRole.CAREGIVER, model.state.value.account?.role)
        model.chooseRole(UserRole.ELDER)
        assertEquals(UserRole.CAREGIVER, model.state.value.account?.role)
        assertEquals(0, repository.registrations)
    }

    @Test fun `caregiver details allow no password but require a name`() = runTest(dispatcher) {
        val model = model(); runCurrent()
        model.verifySms("13900000000", "123456"); runCurrent()
        model.chooseRole(UserRole.CAREGIVER)
        assertEquals(LoginStep.CAREGIVER_DETAILS, model.state.value.step)
        model.completeCaregiver("  ", "")
        assertEquals(LoginNotice.InvalidName, model.state.value.notice)
        assertEquals(0, repository.registrations)

        model.completeCaregiver(" Mei ", ""); runCurrent()
        assertEquals(1, repository.registrations)
        assertEquals(UserRole.CAREGIVER, repository.registeredRole)
        assertEquals("Mei", repository.registeredName)
        assertNull(repository.registeredPassword)
        assertEquals(LoginStep.SIGNED_IN, model.state.value.step)
    }

    @Test fun `unavailable one tap exposes alternate methods without pretending success`() = runTest(dispatcher) {
        repository.oneTapResult = PhoneAuthResult.Failed(PhoneAuthError.NOT_CONFIGURED)
        val model = model(); runCurrent()
        model.oneTap(); runCurrent()
        assertEquals(LoginStep.SIGN_IN, model.state.value.step)
        assertTrue(model.state.value.alternativesExpanded)
        assertEquals(LoginNotice.AuthFailure(PhoneAuthError.NOT_CONFIGURED), model.state.value.notice)
        assertNull(model.state.value.account)
    }

    @Test fun `duplicate taps and late sign in cannot restore an account after sign out`() = runTest(dispatcher) {
        val delayed = CompletableDeferred<PhoneAuthResult>()
        repository.delayedOneTap = delayed
        val model = model(); runCurrent()
        model.oneTap(); runCurrent()
        model.oneTap()
        assertEquals(1, repository.oneTapCalls)
        assertTrue(model.state.value.busy)
        model.signOut(); runCurrent()
        delayed.complete(PhoneAuthResult.SignedIn(PhoneAccount("old", UserRole.ELDER, "Old")))
        runCurrent()
        assertEquals(LoginStep.SIGN_IN, model.state.value.step)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.account)
    }

    @Test fun `reset password returns to password entry without signing in`() = runTest(dispatcher) {
        val model = model(); runCurrent()
        model.showMethod(LoginMethod.RESET)
        model.resetPassword("13800000000", "123456", "new-password"); runCurrent()
        assertEquals(LoginMethod.PASSWORD, model.state.value.method)
        assertEquals(LoginNotice.PasswordReset, model.state.value.notice)
        assertEquals(LoginStep.SIGN_IN, model.state.value.step)
        assertNull(model.state.value.account)
    }

    private fun model(): LoginFlowViewModel = LoginFlowViewModel(repository).also { store.put("login", it) }

    private class FakeRepository : PhoneAuthRepository {
        var restored: PhoneAccount? = null
        var verifications = 0
        var registrations = 0
        var registeredRole: UserRole? = null
        var registeredName: String? = null
        var registeredPassword: String? = null
        var oneTapCalls = 0
        var oneTapResult: PhoneAuthResult = PhoneAuthResult.RegistrationRequired
        var delayedOneTap: CompletableDeferred<PhoneAuthResult>? = null

        override suspend fun restore() = restored
        override suspend fun oneTap(): PhoneAuthResult {
            oneTapCalls++
            return delayedOneTap?.await() ?: oneTapResult
        }
        override suspend fun requestSms(phone: String) = PhoneActionResult.Success
        override suspend fun verifySms(phone: String, code: String): PhoneAuthResult {
            verifications++
            return PhoneAuthResult.RegistrationRequired
        }
        override suspend fun passwordSignIn(phone: String, password: String) =
            PhoneAuthResult.Failed(PhoneAuthError.ACCOUNT_NOT_FOUND)
        override suspend fun resetPassword(phone: String, code: String, newPassword: String) = PhoneActionResult.Success
        override suspend fun register(role: UserRole, displayName: String?, password: String?): PhoneAuthResult {
            registrations++
            registeredRole = role
            registeredName = displayName
            registeredPassword = password
            return PhoneAuthResult.SignedIn(PhoneAccount("new", role, displayName.orEmpty()))
        }
        override suspend fun signOut() = Unit
    }
}
