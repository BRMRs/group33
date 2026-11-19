package com.group33.medicationreminder.integration

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import org.hamcrest.Matchers.equalTo
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.caregiver.plan.PlanEditorView
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.AnonymousLoginRequest
import com.group33.shared.model.AuthResponse
import com.group33.shared.model.UserRole
import java.util.UUID
import java.io.File
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
    private fun capture(name: String) {
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(app.filesDir, name).outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }
    private suspend fun chooseElder(activity: CaregiverHomeActivity, model: ChainViewModel,
                                    relationship: CareRelationshipView) {
        waitFor("idle dropdown") { !model.state.value.busy }
        waitFor("authorized dropdown option") {
            var ready = false
            instrumentation.runOnMainSync {
                val selector = activity.findViewById<MaterialAutoCompleteTextView>(R.id.current_elder_value)
                ready = selector.isEnabled && (0 until (selector.adapter?.count ?: 0)).any {
                    selector.adapter.getItem(it) == relationship.elder.displayName }
            }
            ready
        }
        instrumentation.runOnMainSync {
            activity.findViewById<MaterialAutoCompleteTextView>(R.id.current_elder_value).showDropDown()
        }
        instrumentation.waitForIdleSync()
        onData(equalTo(relationship.elder.displayName)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        if (InstrumentationRegistry.getArguments().getString("pairingPopulatedIsolation") == "true")
            capture("c1-elder-dropdown.png")
        onData(equalTo(relationship.elder.displayName)).inRoot(isPlatformPopup()).perform(click())
        waitFor("dropdown selected context") {
            (model.state.value.context as? CaregiverContextState.Active)?.context?.relationshipId == relationship.id
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(relationship.elder.displayName,
                activity.findViewById<TextView>(R.id.current_elder_value).text.toString())
        }
        assertEquals("A new selection must clear previous action feedback", R.string.chain_ready, model.state.value.message)
    }
    private suspend fun pair(name: String, withPhoto: Boolean = false): CareRelationshipView {
        val login = http.call("POST", "/api/v1/auth/anonymous", json = json.encodeToString(
            AnonymousLoginRequest.serializer(), AnonymousLoginRequest(operation(), UserRole.ELDER, name)))
        assertEquals(201, login.status)
        val elder = json.decodeFromString(AuthResponse.serializer(), login.text())
        if (withPhoto) {
            val source = File.createTempFile("c1-fictional-photo-", ".png", app.cacheDir)
            try {
                val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.BLUE)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                assertEquals(201, http.call("POST", "/api/v1/elders/${elder.user.id}/medication-photos",
                    elder.accessToken, operation(), photo = source, photoType = "image/png").status)
            } finally { source.delete() }
        }
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
        val selector = activity.findViewById<MaterialAutoCompleteTextView>(R.id.current_elder_value)
        suspend fun choose(relationship: CareRelationshipView) = chooseElder(activity, model, relationship)
        val commands = repository.observeCommands().first().map { it.operationId }.toSet()
        choose(a); choose(b); choose(a)
        assertEquals(commands, repository.observeCommands().first().map { it.operationId }.toSet())
        instrumentation.runOnMainSync {
            assertSame("The dropdown remains stable across selections", selector,
                activity.findViewById<MaterialAutoCompleteTextView>(R.id.current_elder_value))
            assertEquals(1, descendants(activity.window.decorView).filterIsInstance<Button>()
                .count { it.text.toString() == activity.getString(R.string.chain_revoke) })
            assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().single {
                it.text.toString() == activity.getString(R.string.chain_revoke) }.performClick())
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
            val labels = (0 until selector.adapter.count).map { selector.adapter.getItem(it) }
            assertFalse(labels.contains(a.elder.displayName))
            assertTrue(labels.contains(b.elder.displayName))
            assertEquals(activity.getString(R.string.caregiver_choose_elder_above),
                activity.findViewById<TextView>(R.id.current_elder_hint).text.toString())
            val feedback = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            assertNotNull("Revocation must show a nearby result", feedback)
            assertEquals(activity.getString(R.string.chain_revoked), feedback.text.toString())
            assertTrue(feedback.getGlobalVisibleRect(android.graphics.Rect()))
        }
        choose(b)
        assertTrue(model.state.value.relationships.any { it.id == b.id && it.status == CareRelationshipStatus.ACTIVE })
        // Leave two active fictional Elders for the member's manual A/B selection acceptance.
        if (InstrumentationRegistry.getArguments().getString("keepManualElders") == "true") {
            val replacement = pair("C1 Elder A new $suffix")
            waitFor("replacement relationship visible") { model.state.value.relationships.any { it.id == replacement.id } }
            assertEquals(b.id, (model.state.value.context as CaregiverContextState.Active).context.relationshipId)
        }
    }

    @Test fun switchingAndRevokingClearPopulatedPhotoAndPlanDraft(): Unit = runBlocking {
        assumeTrue("Dedicated local service and fictional images required",
            InstrumentationRegistry.getArguments().getString("pairingPopulatedIsolation") == "true")
        assertTrue(repository.login(UserRole.CAREGIVER, "C1 populated isolation Caregiver") is Outcome.Success)
        val suffix = operation().take(6)
        val prefix = if (androidx.core.os.ConfigurationCompat.getLocales(app.resources.configuration)[0]?.language == "zh")
            "测试长者" else "Test Elder"
        val a = pair("$prefix A $suffix", withPhoto = true)
        val b = pair("$prefix B $suffix", withPhoto = true)
        val plansBefore = repository.observeCommands().first().filter { it.commandKind == OnlineCommandKind.CONFIRM_PLAN }
            .map { it.operationId }.toSet()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CaregiverHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaregiverHomeActivity
        lateinit var model: ChainViewModel
        instrumentation.runOnMainSync { model = checkNotNull(featureModel(activity)) }
        waitFor("both populated relationships") { model.state.value.relationships.any { it.id == a.id } &&
            model.state.value.relationships.any { it.id == b.id } }
        fun nav(id: Int) = instrumentation.runOnMainSync {
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = id
        }
        fun editor() = descendants(activity.window.decorView).filterIsInstance<PlanEditorView>().single()
        suspend fun choose(relationship: CareRelationshipView) {
            chooseElder(activity, model, relationship)
            waitFor("selected populated Elder") {
                (model.state.value.context as? CaregiverContextState.Active)?.context?.relationshipId == relationship.id &&
                    model.state.value.photos.isNotEmpty() && model.state.value.photos.all { it.elderId == relationship.elderId }
            }
            nav(R.id.nav_plans)
            instrumentation.waitForIdleSync()
            assertNull(model.state.value.viewed)
            instrumentation.runOnMainSync {
                assertFalse(editor().isShown)
                assertTrue(descendants(editor()).filterIsInstance<EditText>().all { it.text.isEmpty() && !it.isEnabled })
                assertTrue(descendants(editor()).filterIsInstance<ImageView>().all { it.drawable == null })
            }
        }
        suspend fun openEditor(relationship: CareRelationshipView) {
            waitFor("photo choice rendered") {
                var found = false
                instrumentation.runOnMainSync {
                    found = descendants(activity.window.decorView).filterIsInstance<Button>().any {
                        it.text.toString() == activity.getString(R.string.chain_inspect_number, 1) }
                }
                found
            }
            instrumentation.runOnMainSync {
                assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().single {
                    it.text.toString() == activity.getString(R.string.chain_inspect_number, 1) }.performClick())
            }
            waitFor("decoded photo for chosen Elder") { model.state.value.viewed?.elderId == relationship.elderId }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(editor().isShown)
                val fields = descendants(editor()).filterIsInstance<EditText>()
                assertTrue(fields.all { it.isEnabled })
                assertEquals("Old Elder draft must not reappear after context changes", "", fields[0].text.toString())
                assertTrue(descendants(editor()).filterIsInstance<ImageView>().any { it.drawable != null })
                fields[0].requestRectangleOnScreen(android.graphics.Rect(0, 0, fields[0].width, fields[0].height), true)
            }
            instrumentation.waitForIdleSync()
            waitFor("editor name visibly inside scrolling content") {
                var visible = false
                instrumentation.runOnMainSync {
                    val field = descendants(editor()).filterIsInstance<EditText>()[0]
                    field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height), true)
                    val rect = android.graphics.Rect()
                    val header = android.graphics.Rect()
                    activity.findViewById<View>(R.id.current_elder_input).getGlobalVisibleRect(header)
                    val navigation = android.graphics.Rect()
                    activity.findViewById<View>(R.id.bottom_navigation).getGlobalVisibleRect(navigation)
                    visible = field.height > 0 && field.getGlobalVisibleRect(rect) &&
                        rect.height() == field.height && rect.top >= header.bottom && rect.bottom <= navigation.top
                }
                visible
            }
            capture("c1-populated-fresh.png")
            instrumentation.runOnMainSync {
                val fields = descendants(editor()).filterIsInstance<EditText>()
                fields[0].setText("Draft ${relationship.elder.displayName}")
                fields[1].setText("Fictional unsaved dosage")
            }
        }
        choose(a); openEditor(a)
        nav(R.id.nav_records)
        nav(R.id.nav_plans)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("Ordinary page navigation must retain the current draft",
                "Draft ${a.elder.displayName}", descendants(editor()).filterIsInstance<EditText>()[0].text.toString())
        }
        choose(b); openEditor(b)
        choose(a); openEditor(a)
        nav(R.id.nav_settings)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().single {
                it.text.toString() == activity.getString(R.string.chain_revoke) }.performClick())
        }
        waitFor("revoke clears populated context") { !model.state.value.busy &&
            model.state.value.context is CaregiverContextState.SelectElder }
        assertNull(model.state.value.viewed)
        assertTrue(model.state.value.photos.isEmpty())
        assertTrue(model.state.value.plans.isEmpty())
        nav(R.id.nav_plans)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertFalse(editor().isShown)
            assertTrue(descendants(editor()).filterIsInstance<EditText>().all { it.text.isEmpty() && !it.isEnabled })
            assertTrue(descendants(editor()).filterIsInstance<ImageView>().all { it.drawable == null })
        }
        choose(b); openEditor(b)
        assertEquals(plansBefore, repository.observeCommands().first().filter { it.commandKind == OnlineCommandKind.CONFIRM_PLAN }
            .map { it.operationId }.toSet())
        if (InstrumentationRegistry.getArguments().getString("keepManualElders") == "true") {
            val replacement = pair(a.elder.displayName, withPhoto = true)
            waitFor("manual photo relationship ready") { model.state.value.relationships.any { it.id == replacement.id } }
        }
    }
}
