package com.group33.server.auth

import com.group33.server.web.ApiException
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class PhoneAuthControllerTest {
    private val provider = mock(FusionAuthProvider::class.java)
    private val accounts = mock(PhoneAccountStore::class.java)
    private val jwt = mock(JwtService::class.java)
    private val request = mock(HttpServletRequest::class.java).also { `when`(it.remoteAddr).thenReturn("127.0.0.1") }
    private fun controller(enabled: Boolean = true) =
        PhoneAuthController(provider, accounts, jwt, enabled, "scheme", "local-test-secret-with-at-least-32-bytes")

    @Test
    fun `disabled authentication never calls provider`() {
        assertThrows(ApiException::class.java) { controller(false).sdkToken(request) }
        verifyNoInteractions(provider, accounts, jwt)
    }

    @Test
    fun `unverified token cannot create account or session`() {
        `when`(provider.verify("bad-proof")).thenReturn(null)
        assertThrows(ApiException::class.java) { controller().verify(FusionVerifyRequest("bad-proof"), request) }
        verifyNoInteractions(accounts, jwt)
    }

    @Test
    fun `new verified phone gets registration ticket rather than session`() {
        `when`(provider.verify("valid-proof")).thenReturn("13800000000")
        `when`(accounts.findByPhone("13800000000")).thenReturn(null)
        `when`(accounts.newRegistrationTicket("13800000000")).thenReturn("ticket")
        val response = controller().verify(FusionVerifyRequest("valid-proof"), request)
        assertEquals("REGISTRATION_REQUIRED", response.status)
        assertEquals("ticket", response.registrationTicket)
        assertEquals(null, response.accessToken)
        verifyNoInteractions(jwt)
    }

    @Test
    fun `existing verified account uses stored role and gets app session`() {
        val user = User("id-1", "Caregiver", UserRole.CAREGIVER)
        `when`(provider.verify("valid-proof")).thenReturn("13800000000")
        `when`(accounts.findByPhone("13800000000")).thenReturn(user)
        `when`(jwt.issueForPhone("id-1", "CAREGIVER")).thenReturn(IssuedToken("session", 3600))
        val response = controller().verify(FusionVerifyRequest("valid-proof"), request)
        assertEquals("SIGNED_IN", response.status)
        assertEquals(UserRole.CAREGIVER, response.user?.role)
        assertEquals("session", response.accessToken)
    }
}
