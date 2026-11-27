package com.group33.medicationreminder.integration

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.AuthResponse
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit local-service opt-in. Exercises the real Settings route, Room and HTTP adapters. */
class PairingFormOnlineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DebugChainApplication
    private val services get() = app.repository.services
    private val http = ChainHttp(com.group33.medicationreminder.BuildConfig.DEBUG_SERVER_URL)
    private fun operation() = UUID.randomUUID().toString()
    private suspend fun waitFor(label: String, check: suspend () -> Boolean) {
        try { withTimeout(20_000) { while (!check()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out waiting for $label", error) }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun screenshot(activity: android.app.Activity, name: String) {
        instrumentation.runOnMainSync {
            val list = activity.findViewById<View>(R.id.pairing_requests_container)
            list.requestRectangleOnScreen(android.graphics.Rect(0, 0, list.width, minOf(list.height, 600)), true)
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.noBackupFilesDir, "pairing112-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun requestCancelAndApproveThroughRealSettings(): Unit = runBlocking {
        assumeTrue("Requires a dedicated local test service", InstrumentationRegistry.getArguments().getString("pairing112") == "true")
        val protocol = ContractProtocol.json
        val login = http.call("POST", "/api/v1/auth/anonymous", json = protocol.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(operation(), UserRole.ELDER, "Pairing test Elder")))
        assertEquals(201, login.status)
        val elder = protocol.decodeFromString(AuthResponse.serializer(), login.text())
        suspend fun issue(): PairingCodeView {
            val response = http.call("POST", "/api/v1/pairing-codes", elder.accessToken, operation(), "{}")
            assertEquals(201, response.status)
            return protocol.decodeFromString(PairingCodeView.serializer(), response.text())
        }
        assertTrue(services.auth.login(UserRole.CAREGIVER, "Pairing test Caregiver") is Outcome.Success)
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, CaregiverHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaregiverHomeActivity
        instrumentation.runOnMainSync {
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_settings
        }
        waitFor("initial submit enabled") {
            var enabled = false
            instrumentation.runOnMainSync { enabled = activity.findViewById<View>(R.id.pairing_submit_button)?.isEnabled == true }
            enabled
        }
        suspend fun submit(code: String): PairingRequestView {
            waitFor("next submit enabled") {
                var enabled = false
                instrumentation.runOnMainSync { enabled = activity.findViewById<View>(R.id.pairing_submit_button).isEnabled }
                enabled
            }
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.pairing_code_input).setText(code)
                assertTrue(activity.findViewById<View>(R.id.pairing_submit_button).performClick())
            }
            return withTimeout(20_000) { services.pairing.observeRequests().first { cached ->
                cached.access == AccessState.AUTHORIZED && cached.value.any { it.elderId == elder.user.id && it.status == PairingRequestStatus.PENDING }
            }.value.single { it.elderId == elder.user.id && it.status == PairingRequestStatus.PENDING } }
        }
        val request = submit(issue().code)
        assertTrue(services.pairing.observeRelationships().first().value.none { it.elderId == elder.user.id && it.status == CareRelationshipStatus.ACTIVE })
        instrumentation.waitForIdleSync()
        screenshot(activity, "pending")
        var cancelDiagnostic = ""
        try { waitFor("target cancel enabled") {
            val index = services.pairing.observeRequests().first().value.indexOfFirst { it.id == request.id }
            var clicked = false
            instrumentation.runOnMainSync {
                val container = activity.findViewById<ViewGroup>(R.id.pairing_requests_container)
                val state = androidx.lifecycle.ViewModelProvider(activity)[com.group33.medicationreminder.caregiver.PairingWorkflowViewModel::class.java].state.value
                cancelDiagnostic = "index=$index rows=${container.childCount} ready=${state.ready} busy=${state.busy} recovery=${state.needsRecovery} known=${state.requestsKnown} requests=${state.requests.size}"
                val row = container.getChildAt(index)
                if (row != null) descendants(row).filterIsInstance<android.widget.Button>()
                    .firstOrNull { it.text == activity.getString(R.string.pairing_cancel) && it.isEnabled }
                    ?.let { clicked = it.performClick() }
            }
            clicked
        } } catch (error: AssertionError) { throw AssertionError(cancelDiagnostic, error) }
        waitFor("cancelled cache row") { services.pairing.observeRequests().first().value.any { it.id == request.id && it.status == PairingRequestStatus.CANCELLED } }
        instrumentation.waitForIdleSync(); screenshot(activity, "cancelled")
        val next = submit(issue().code)
        assertNotEquals(request.id, next.id)
        val accepted = http.call("POST", "/api/v1/pairing-requests/${next.id}/accept", elder.accessToken, operation(), "{}")
        assertEquals(200, accepted.status)
        waitFor("refresh enabled") {
            var enabled = false
            instrumentation.runOnMainSync { enabled = activity.findViewById<View>(R.id.pairing_refresh_button).isEnabled }
            enabled
        }
        instrumentation.runOnMainSync { activity.findViewById<View>(R.id.pairing_refresh_button).performClick() }
        waitFor("accepted relationship") { services.pairing.observeRelationships().first().value.any { it.elderId == elder.user.id && it.status == CareRelationshipStatus.ACTIVE } }
        instrumentation.waitForIdleSync(); screenshot(activity, "accepted")
    }
}
