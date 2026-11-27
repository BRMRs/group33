package com.group33.medicationreminder.integration

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.caregiver.PairingWorkflowViewModel
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.AuthResponse
import com.group33.shared.model.UserRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Dedicated local-service opt-in; real Settings, command journal, Room and HTTP. */
class PairingFormFailureOnlineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DebugChainApplication
    private val services get() = app.repository.services
    private val http = ChainHttp(com.group33.medicationreminder.BuildConfig.DEBUG_SERVER_URL)
    private val protocol = ContractProtocol.json
    private fun operation() = UUID.randomUUID().toString()

    private suspend fun waitFor(label: String, check: suspend () -> Boolean) {
        try { withTimeout(20_000) { while (!check()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out waiting for $label", error) }
    }

    private fun screenshot(activity: CaregiverHomeActivity, stage: String, target: View) {
        instrumentation.runOnMainSync {
            val scroll = activity.findViewById<NestedScrollView>(R.id.caregiver_scroll)
            val rect = Rect(0, 0, target.width, target.height)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.scrollTo(0, rect.top)
        }
        instrumentation.waitForIdleSync()
        // Capture the completed scroll frame, rather than the preceding display buffer.
        android.os.SystemClock.sleep(250)
        instrumentation.runOnMainSync {
            val visible = Rect()
            assertTrue("Evidence target must be visible", target.getGlobalVisibleRect(visible))
            assertTrue("Evidence target must have visible height", visible.height() > 0)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.noBackupFilesDir, "pairing-failure-$stage.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun invalidatedCodeAndElderRejectionNeverAuthorizeOrAutomaticallyResubmit(): Unit = runBlocking {
        assumeTrue("Requires a dedicated local test service", InstrumentationRegistry.getArguments().getString("pairing112") == "true")
        val login = http.call("POST", "/api/v1/auth/anonymous", json = protocol.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(operation(), UserRole.ELDER, "Failure test Elder")))
        assertEquals(201, login.status)
        val elder = protocol.decodeFromString(AuthResponse.serializer(), login.text())
        suspend fun issue(): PairingCodeView {
            val response = http.call("POST", "/api/v1/pairing-codes", elder.accessToken, operation(), "{}")
            assertEquals(201, response.status)
            return protocol.decodeFromString(PairingCodeView.serializer(), response.text())
        }
        assertTrue(services.auth.login(UserRole.CAREGIVER, "Pairing test Caregiver") is Outcome.Success)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, CaregiverHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaregiverHomeActivity
        lateinit var model: PairingWorkflowViewModel
        instrumentation.runOnMainSync {
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_settings
            model = ViewModelProvider(activity)[PairingWorkflowViewModel::class.java]
        }
        suspend fun idle() = waitFor("form ready") { model.state.value.let { it.ready && !it.busy && !it.needsRecovery } }
        suspend fun submit(code: String) {
            idle()
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.pairing_code_input).setText(code)
                assertTrue(activity.findViewById<View>(R.id.pairing_submit_button).performClick())
            }
            idle()
        }
        suspend fun refresh() {
            idle()
            instrumentation.runOnMainSync { assertTrue(activity.findViewById<View>(R.id.pairing_refresh_button).performClick()) }
            idle()
        }
        suspend fun noTargetRelationship() {
            val relationships = withTimeout(20_000) { services.pairing.observeRelationships().first {
                it.access == AccessState.AUTHORIZED && it.hasLocalData
            } }
            assertTrue(relationships.value.none {
                it.elderId == elder.user.id && it.status == CareRelationshipStatus.ACTIVE
            })
        }
        suspend fun commandIds() = services.commands.observeCommands().first().map { it.operationId }.toSet()
        val before = commandIds()
        val invalidated = issue()
        assertEquals(204, http.call("DELETE", "/api/v1/pairing-codes/${invalidated.id}", elder.accessToken, operation(), "{}").status)
        submit(invalidated.code)
        val rejectedCommand = withTimeout(20_000) { services.commands.observeCommands().first { rows -> rows.any {
            it.operationId !in before && it.commandKind == OnlineCommandKind.REQUEST_PAIRING && it.state == OnlineCommandState.REJECTED
        } } }.single { it.operationId !in before }
        val error = rejectedCommand.error as RepositoryError.Remote
        assertEquals("PAIRING_CODE_UNAVAILABLE", error.error.code)
        assertTrue(model.state.value.requests.none { it.elderId == elder.user.id })
        noTargetRelationship()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(activity.getString(R.string.pairing_notice_rejected_title),
                activity.findViewById<TextView>(R.id.pairing_notice_title).text.toString())
        }
        screenshot(activity, "invalidated", activity.findViewById(R.id.pairing_notice_card))
        val afterFailure = commandIds()
        refresh()
        assertEquals(afterFailure, commandIds())
        assertEquals(OnlineCommandState.REJECTED, services.commands.observeCommands().first().single {
            it.operationId == rejectedCommand.operationId }.state)

        submit(issue().code)
        val request = withTimeout(20_000) { services.pairing.observeRequests().first { cached -> cached.value.any {
            it.elderId == elder.user.id && it.status == PairingRequestStatus.PENDING
        } } }.value.single { it.elderId == elder.user.id }
        noTargetRelationship()
        assertEquals(200, http.call("POST", "/api/v1/pairing-requests/${request.id}/reject", elder.accessToken, operation(), "{}").status)
        refresh()
        waitFor("declined row") { model.state.value.requests.any { it.id == request.id && it.status == PairingRequestStatus.REJECTED } }
        noTargetRelationship()
        instrumentation.waitForIdleSync()
        fun targetRow(): View {
            val index = model.state.value.requests.indexOfFirst { it.id == request.id }
            val row = activity.findViewById<android.view.ViewGroup>(R.id.pairing_requests_container).getChildAt(index)
            val text = ((row as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup).getChildAt(0) as TextView
            assertTrue(text.text.toString().contains(activity.getString(R.string.pairing_status_rejected)))
            return row
        }
        lateinit var row: View
        instrumentation.runOnMainSync { row = targetRow() }
        screenshot(activity, "rejected", row)
        val afterDecline = commandIds()
        refresh()
        assertEquals(afterDecline, commandIds())
        assertEquals(1, model.state.value.requests.count { it.elderId == elder.user.id })
        assertEquals(PairingRequestStatus.REJECTED, model.state.value.requests.single { it.id == request.id }.status)
        noTargetRelationship()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { row = targetRow() }
        screenshot(activity, "rejected-refreshed", row)
    }
}
