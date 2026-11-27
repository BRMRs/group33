package com.group33.medicationreminder.integration

import android.content.Intent
import android.os.Process
import android.view.View
import android.widget.EditText
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit three-phase local fault-proxy test; the host force-stops between phases. */
class PairingResponseLossOnlineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DebugChainApplication
    private val services get() = app.repository.services
    private val http = ChainHttp(com.group33.medicationreminder.BuildConfig.DEBUG_SERVER_URL)
    private val protocol = ContractProtocol.json
    private val fixture get() = File(app.noBackupFilesDir, "pairing-loss-fixture.json")
    private fun optIn() = assumeTrue("Requires dedicated fault proxy and phase orchestration",
        InstrumentationRegistry.getArguments().getString("pairingResponseLoss") == "true")
    private suspend fun waitFor(label: String, check: suspend () -> Boolean) {
        try { withTimeout(30_000) { while (!check()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out waiting for $label", error) }
    }
    private fun open(): Pair<CaregiverHomeActivity, PairingWorkflowViewModel> {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, CaregiverHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaregiverHomeActivity
        lateinit var model: PairingWorkflowViewModel
        instrumentation.runOnMainSync {
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_settings
            model = ViewModelProvider(activity)[PairingWorkflowViewModel::class.java]
        }
        return activity to model
    }
    private suspend fun records() = withTimeout(30_000) { services.commands.observeCommands().first() }
    private fun save(stage: String, record: OnlineCommandRecord) {
        File(app.noBackupFilesDir, "pairing-loss-$stage.json").writeText(JSONObject()
            .put("state", record.state.name).put("cache", record.cacheState?.name)
            .put("pid", Process.myPid()).toString())
    }
    private suspend fun serverRequests(token: String): List<PairingRequestView> {
        val response = http.call("GET", "/api/v1/pairing-requests", token)
        assertEquals(200, response.status)
        return protocol.decodeFromString(ItemsResponse.serializer(PairingRequestView.serializer()), response.text()).items
    }

    @Test fun prepareUnknown(): Unit = runBlocking { prepare(false) }

    /** Intentionally interrupted by the host after the durable in-flight marker. */
    @Test fun prepareInFlight(): Unit = runBlocking { prepare(true) }

    private suspend fun prepare(inFlight: Boolean) {
        optIn()
        assertTrue(services.auth.login(UserRole.CAREGIVER, "Pairing test Caregiver") is Outcome.Success)
        val login = http.call("POST", "/api/v1/auth/anonymous", json = protocol.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(UUID.randomUUID().toString(), UserRole.ELDER, "Response loss Elder")))
        assertEquals(201, login.status)
        val elder = protocol.decodeFromString(AuthResponse.serializer(), login.text())
        val issued = http.call("POST", "/api/v1/pairing-codes", elder.accessToken, UUID.randomUUID().toString(), "{}")
        assertEquals(201, issued.status)
        val code = protocol.decodeFromString(PairingCodeView.serializer(), issued.text())
        val (activity, model) = open()
        waitFor("ready form") { model.state.value.ready && !model.state.value.busy }
        val before = records().map { it.operationId }.toSet()
        instrumentation.runOnMainSync {
            activity.findViewById<EditText>(R.id.pairing_code_input).setText(code.code)
            assertTrue(activity.findViewById<View>(R.id.pairing_submit_button).performClick())
        }
        if (inFlight) {
            waitFor("durable in-flight command") { model.state.value.busy && records().any {
                it.operationId !in before && it.state == OnlineCommandState.AWAITING_CONFIRMATION
            } }
        } else waitFor("unknown result") { model.state.value.needsRecovery && !model.state.value.busy }
        val command = records().single { it.operationId !in before }
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, command.state)
        instrumentation.runOnMainSync {
            assertFalse(activity.findViewById<View>(R.id.pairing_submit_button).isEnabled)
            model.submitCode()
        }
        assertEquals(before + command.operationId, records().map { it.operationId }.toSet())
        var remote = emptyList<PairingRequestView>()
        waitFor("single server request") { remote = serverRequests(elder.accessToken); remote.size == 1 }
        val request = remote.single()
        assertEquals(PairingRequestStatus.PENDING, request.status)
        assertNull(request.relationshipId)
        fixture.writeText(JSONObject().put("operation", command.operationId)
            .put("elderToken", elder.accessToken).put("elder", elder.user.id)
            .put("request", request.id).put("pid", Process.myPid())
            .put("boot", android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT))
            .put("startedElapsed", android.os.SystemClock.elapsedRealtime())
            .put("commandIds", org.json.JSONArray((before + command.operationId).toList())).toString())
        if (inFlight) {
            assertTrue("HTTP work must still be in flight", model.state.value.busy)
            save("inflight", command)
            File(app.noBackupFilesDir, "pairing-loss-inflight-ready.json").writeText(
                JSONObject().put("pid", Process.myPid()).put("busy", true).toString())
            awaitCancellation()
        }
        save("unknown", command)
    }

    @Test fun verifyLiveClaimAfterKill(): Unit = runBlocking {
        optIn()
        val saved = JSONObject(fixture.readText())
        assertNotEquals(saved.getInt("pid"), Process.myPid())
        assertEquals(saved.getInt("boot"), android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT))
        assertTrue("Check the live claim before its real lease can expire",
            android.os.SystemClock.elapsedRealtime() - saved.getLong("startedElapsed") < 120_000L)
        val (activity, model) = open()
        waitFor("restored in-flight command") { model.state.value.ready && model.state.value.needsRecovery }
        val before = records().map { it.operationId }.toSet()
        instrumentation.runOnMainSync { assertTrue(activity.findViewById<View>(R.id.pairing_refresh_button).performClick()) }
        waitFor("live claim check completes") { !model.state.value.busy }
        assertTrue(model.state.value.needsRecovery)
        val command = records().single { it.operationId == saved.getString("operation") }
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, command.state)
        assertEquals(before, records().map { it.operationId }.toSet())
        instrumentation.runOnMainSync { assertFalse(activity.findViewById<View>(R.id.pairing_submit_button).isEnabled) }
        save("liveclaim", command)
    }

    @Test fun verifyOfflineAfterRestart(): Unit = runBlocking {
        optIn()
        val saved = JSONObject(fixture.readText())
        assertNotEquals(saved.getInt("pid"), Process.myPid())
        val (activity, model) = open()
        waitFor("restored unknown journal") { model.state.value.ready && model.state.value.needsRecovery }
        val before = records().map { it.operationId }.toSet()
        assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, records().single { it.operationId == saved.getString("operation") }.state)
        instrumentation.runOnMainSync {
            assertFalse(activity.findViewById<View>(R.id.pairing_submit_button).isEnabled)
            assertTrue(activity.findViewById<View>(R.id.pairing_refresh_button).performClick())
        }
        waitFor("offline recovery finishes") { !model.state.value.busy }
        assertTrue(model.state.value.needsRecovery)
        assertEquals(before, records().map { it.operationId }.toSet())
        save("offline", records().single { it.operationId == saved.getString("operation") })
    }

    @Test fun recoverOriginalAfterReconnect(): Unit = runBlocking {
        optIn()
        val saved = JSONObject(fixture.readText())
        val (activity, model) = open()
        waitFor("restored recovery gate") { model.state.value.ready && model.state.value.needsRecovery }
        val before = records().map { it.operationId }.toSet()
        instrumentation.runOnMainSync { assertTrue(activity.findViewById<View>(R.id.pairing_refresh_button).performClick()) }
        waitFor("original command confirmed") { !model.state.value.busy && !model.state.value.needsRecovery }
        val command = records().single { it.operationId == saved.getString("operation") }
        assertEquals(OnlineCommandState.CONFIRMED, command.state)
        assertEquals(CacheState.APPLIED, command.cacheState)
        assertEquals(before, records().map { it.operationId }.toSet())
        waitFor("original pending row restored") { model.state.value.requests.any { it.id == saved.getString("request") } }
        assertEquals(PairingRequestStatus.PENDING, model.state.value.requests.single { it.id == saved.getString("request") }.status)
        val request = serverRequests(saved.getString("elderToken")).single()
        assertEquals(saved.getString("request"), request.id)
        assertEquals(PairingRequestStatus.PENDING, request.status)
        assertNull(request.relationshipId)
        save("recovered", command)
    }
}
