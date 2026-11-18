package com.group33.medicationreminder.integration

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.AuthResponse
import com.group33.shared.model.UserRole
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in fictional-data local service; Elder consent is HTTP, Caregiver choice is real UI. */
class PairingMultiElderOnlineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DebugChainApplication
    private val repository get() = app.repository
    private val http = ChainHttp(com.group33.medicationreminder.BuildConfig.DEBUG_SERVER_URL)
    private val json = ContractProtocol.json
    private fun operation() = UUID.randomUUID().toString()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private suspend fun waitFor(label: String, check: () -> Boolean) {
        try { withTimeout(30_000) { while (!check()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
    }
    private suspend fun pair(name: String): CareRelationshipView {
        val login = http.call("POST", "/api/v1/auth/anonymous", json = json.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(operation(), UserRole.ELDER, name)))
        assertEquals(201, login.status)
        val elder = json.decodeFromString(AuthResponse.serializer(), login.text())
        val issued = http.call("POST", "/api/v1/pairing-codes", elder.accessToken, operation(), "{}")
        assertEquals(201, issued.status)
        val code = json.decodeFromString(PairingCodeView.serializer(), issued.text())
        val command = repository.begin(OnlineCommandKind.REQUEST_PAIRING, null) as Outcome.Success
        val result = repository.requestPairing(code.code, command.value)
        assertTrue(result is OnlineCommandResult.Confirmed)
        val request = (result as OnlineCommandResult.Confirmed).value
        assertEquals(200, http.call("POST", "/api/v1/pairing-requests/${request.id}/accept",
            elder.accessToken, operation(), "{}").status)
        assertTrue(repository.refresh() is Outcome.Success)
        return repository.observeRelationships().first().value.single {
            it.elderId == elder.user.id && it.status == CareRelationshipStatus.ACTIVE
        }
    }

    @Test fun explicitSelectionAndRevocationUseRealRelationships(): Unit = runBlocking {
        assumeTrue("Dedicated local service required",
            InstrumentationRegistry.getArguments().getString("pairingMultiElder") == "true")
        assertTrue(repository.login(UserRole.CAREGIVER, "C1 multi-Elder Caregiver") is Outcome.Success)
        val suffix = operation().take(6)
        val a = pair("C1 Elder A $suffix")
        val b = pair("C1 Elder B $suffix")
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CaregiverHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaregiverHomeActivity
        lateinit var model: ChainViewModel
        instrumentation.runOnMainSync {
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_settings
            model = checkNotNull(featureModel(activity))
        }
        waitFor("multiple authorized Elders") { model.state.value.context is CaregiverContextState.SelectElder }
        val retainedButtons = mutableMapOf<String, Button>()
        suspend fun choose(relationship: CareRelationshipView) {
            waitFor("idle") { !model.state.value.busy }
            waitFor("rendered choice ${relationship.elder.displayName}") {
                var found = false
                instrumentation.runOnMainSync {
                    val labels = listOf(activity.getString(R.string.chain_select_name, relationship.elder.displayName),
                        activity.getString(R.string.chain_selected_name, relationship.elder.displayName))
                    found = descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() in labels }
                }
                found
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val label = activity.getString(R.string.chain_select_name, relationship.elder.displayName)
                val selectedLabel = activity.getString(R.string.chain_selected_name, relationship.elder.displayName)
                val button = descendants(activity.window.decorView).filterIsInstance<Button>().single {
                    it.text.toString() == label || it.text.toString() == selectedLabel
                }
                retainedButtons[relationship.id]?.let { assertSame("Selection must retain the pressed button", it, button) }
                retainedButtons[relationship.id] = button
                assertTrue(button.isEnabled)
                assertTrue(button.performClick())
            }
            waitFor("current selected relationship") {
                (model.state.value.context as? CaregiverContextState.Active)?.context?.relationshipId == relationship.id
            }
            waitFor("visible current Elder") {
                var matches = false
                instrumentation.runOnMainSync {
                    matches = activity.findViewById<TextView>(R.id.current_elder_value).text.toString() == relationship.elder.displayName
                }
                matches
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val button = retainedButtons.getValue(relationship.id)
                assertTrue(button.isSelected)
                assertEquals(activity.getString(R.string.chain_selected_name, relationship.elder.displayName), button.text.toString())
            }
        }
        val commands = repository.observeCommands().first().map { it.operationId }.toSet()
        choose(a); choose(b); choose(a)
        assertEquals(commands, repository.observeCommands().first().map { it.operationId }.toSet())
        instrumentation.runOnMainSync {
            val selected = retainedButtons.getValue(a.id)
            val parent = selected.parent as ViewGroup
            assertTrue(parent.getChildAt(parent.indexOfChild(selected) + 1).performClick())
        }
        waitFor("revoked A no longer current") { model.state.value.context is CaregiverContextState.SelectElder }
        waitFor("revocation result") { !model.state.value.busy && model.state.value.message == R.string.chain_revoked }
        assertTrue(model.state.value.photos.isEmpty())
        assertNull(model.state.value.viewed)
        waitFor("visible revocation feedback") {
            var visible = false
            instrumentation.runOnMainSync {
                val feedback = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                visible = feedback?.text?.toString() == activity.getString(R.string.chain_revoked) &&
                    feedback.getGlobalVisibleRect(android.graphics.Rect())
            }
            visible
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val labels = descendants(activity.window.decorView).filterIsInstance<Button>().map { it.text.toString() }
            assertFalse(labels.contains(activity.getString(R.string.chain_select_name, a.elder.displayName)))
            assertTrue(labels.contains(activity.getString(R.string.chain_select_name, b.elder.displayName)))
            val feedback = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            assertNotNull("Revocation must show a nearby result", feedback)
            assertEquals(activity.getString(R.string.chain_revoked), feedback.text.toString())
            assertTrue(feedback.getGlobalVisibleRect(android.graphics.Rect()))
        }
        retainedButtons.clear() // Removing a relationship legitimately rebuilds the remaining rows.
        choose(b)
        assertTrue(model.state.value.relationships.any { it.id == b.id && it.status == CareRelationshipStatus.ACTIVE })
        // Leave two active fictional Elders for the member's manual A/B selection acceptance.
        if (InstrumentationRegistry.getArguments().getString("keepManualElders") == "true") {
            val replacement = pair("C1 Elder A new $suffix")
            waitFor("replacement relationship visible") { model.state.value.relationships.any { it.id == replacement.id } }
            assertEquals(b.id, (model.state.value.context as CaregiverContextState.Active).context.relationshipId)
        }
    }
}
