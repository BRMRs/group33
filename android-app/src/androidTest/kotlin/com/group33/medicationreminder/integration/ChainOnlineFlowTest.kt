package com.group33.medicationreminder.integration

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.BuildConfig
import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.SharedLoginActivity
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.elder.ElderHomeActivity
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.group33.medicationreminder.R
import com.group33.medicationreminder.caregiver.plan.PlanEditorView
import com.group33.medicationreminder.core.data.contract.*
import com.group33.medicationreminder.elder.photo.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in two-installation causal sequence against the local real service; never a public service. */
class ChainOnlineFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DebugChainApplication
    private val repository get() = app.repository
    private val s get() = repository.services
    private val bridge get() = File(app.noBackupFilesDir, "chain-bridge.json")
    private fun read() = JSONObject(bridge.readText())
    private fun write(data: JSONObject) { bridge.writeText(data.toString()) }
    private fun screenshot(activity: Activity, name: String) {
        instrumentation.runOnMainSync {
            val root = descendants(activity.window.decorView).firstOrNull { it is androidx.core.widget.NestedScrollView || it is ScrollView }
            when (root) {
                is androidx.core.widget.NestedScrollView -> root.fullScroll(View.FOCUS_DOWN)
                is ScrollView -> root.fullScroll(View.FOCUS_DOWN)
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.noBackupFilesDir, "chain-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private suspend fun foreground(type: Class<out Activity>): Activity {
        var result: Activity? = null
        waitUntil { instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { type.isInstance(it) }
        }; result != null }
        return checkNotNull(result)
    }
    private suspend fun launch(): Activity {
        val intent = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.startActivitySync(intent)
        val session = withTimeout(10_000) { s.auth.observeSession().first { it.readiness != AuthReadiness.RESTORING } }
        val activity = foreground(when (session.user?.role) {
            UserRole.ELDER -> ElderHomeActivity::class.java
            UserRole.CAREGIVER -> CaregiverHomeActivity::class.java
            null -> SharedLoginActivity::class.java
        })
        val choice = app.languageSettings.observeChoice().first()
        if (choice != LanguageChoice.FOLLOW_SYSTEM) instrumentation.runOnMainSync {
            assertEquals("Actual Activity locale must match the saved preference",
                if (choice == LanguageChoice.SIMPLIFIED_CHINESE) "zh" else "en",
                androidx.core.os.ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language)
        }
        return activity
    }
    private fun nav(activity: Activity, id: Int) = instrumentation.runOnMainSync {
        activity.findViewById<View>(id).performClick()
    }
    private suspend fun click(activity: Activity, resource: Int) = waitUntil {
        var clicked = false
        instrumentation.runOnMainSync {
            descendants(activity.window.decorView).filterIsInstance<Button>()
                .firstOrNull { it.text == activity.getString(resource) && it.isShown && it.isEnabled }
                ?.let { clicked = it.performClick() }
        }
        clicked
    }
    private suspend fun ready() = withTimeout(15_000) { s.auth.observeSession().first { it.readiness == AuthReadiness.READY } }
    private suspend fun waitUntil(check: suspend () -> Boolean) = withTimeout(20_000) { while (!check()) delay(50) }
    private fun <T> success(outcome: Outcome<T>): T {
        assertTrue("Expected successful repository outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    @Test fun configureLanguage(): Unit = runBlocking<Unit> {
        val tag = InstrumentationRegistry.getArguments().getString("chainLanguage")
        assumeTrue("Locale setup is explicit", tag != null)
        val choice = if (tag!!.startsWith("zh")) LanguageChoice.SIMPLIFIED_CHINESE else LanguageChoice.ENGLISH
        success(app.languageSettings.setChoice(choice))
        instrumentation.runOnMainSync { Group33Application.applyLanguage(choice, app) }
    }

    @Test fun realOnlinePhase(): Unit = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("chainPhase")
        assumeTrue("Run using the documented local two-emulator harness", phase != null)
        when (phase) {
            "elder-login" -> {
                val entry = launch()
                assertTrue(entry is SharedLoginActivity)
                click(entry, R.string.chain_login_elder)
                val session = ready()
                val activity = foreground(ElderHomeActivity::class.java)
                waitUntil { s.pairing.observeRelationships().first().access == AccessState.AUTHORIZED }
                click(activity, R.string.chain_issue)
                var code: String? = null
                waitUntil {
                    instrumentation.runOnMainSync {
                        code = descendants(activity.window.decorView).filterIsInstance<TextView>()
                            .mapNotNull { Regex("[0-9]{8}").find(it.text)?.value }.firstOrNull()
                    }
                    code != null
                }
                write(JSONObject().put("code", code).put("elderId", session.user!!.id).put("elderName", session.user.displayName))
            }
            "caregiver-request" -> {
                val b = read(); val entry = launch()
                assertTrue(entry is SharedLoginActivity)
                click(entry, R.string.chain_login_caregiver)
                val session = ready()
                val activity = foreground(CaregiverHomeActivity::class.java)
                nav(activity, R.id.nav_settings)
                instrumentation.runOnMainSync {
                    descendants(activity.window.decorView).filterIsInstance<EditText>()
                        .single { it.hint == activity.getString(R.string.chain_enter_code) }.setText(b.getString("code"))
                }
                click(activity, R.string.chain_pair)
                val request = withTimeout(20_000) { s.pairing.observeRequests().first { row ->
                    row.access == AccessState.AUTHORIZED && row.value.any { it.status == PairingRequestStatus.PENDING }
                } }.value.single { it.status == PairingRequestStatus.PENDING }
                assertEquals(b.getString("elderId"), request.elderId)
                val relationships = s.pairing.observeRelationships().first { it.access == AccessState.AUTHORIZED }
                assertTrue(relationships.value.none { it.status == CareRelationshipStatus.ACTIVE })
                assertFalse(CaregiverContextResolver.resolve(session, s.trusted.value, relationships, null) is CaregiverContextState.Active)
                assertTrue(s.photos.loadContent("unknown-photo") is Outcome.Failure)
                assertEquals(AuthReadiness.READY, s.auth.observeSession().first().readiness)
                write(b.put("requestId", request.id).put("caregiverId", session.user!!.id))
            }
            "elder-accept" -> {
                val b = read(); ready(); val activity = launch()
                success(s.pairing.refresh())
                waitUntil { s.pairing.observeRequests().first().value.any { it.id == b.getString("requestId") } }
                instrumentation.waitForIdleSync()
                click(activity, R.string.chain_accept)
                val relationship = withTimeout(20_000) { s.pairing.observeRelationships().first { r ->
                    r.access == AccessState.AUTHORIZED && r.value.any { it.status == CareRelationshipStatus.ACTIVE }
                } }.value.single { it.status == CareRelationshipStatus.ACTIVE }
                assertEquals(b.getString("caregiverId"), relationship.caregiverId)
                write(b.put("relationshipId", relationship.id))
            }
            "elder-photo" -> {
                val b = read(); ready(); val activity = launch()
                val monitor = instrumentation.addMonitor(ElderPhotoActivity::class.java.name, null, false)
                click(activity, R.string.photo_title)
                val photoActivity = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull(photoActivity)
                val source = File(app.cacheDir, "elder-camera/fictional-source.png").apply { parentFile!!.mkdirs() }
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                val bytes = source.readBytes()
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.elderphotos", source)
                lateinit var model: ElderPhotoSubmitViewModel
                instrumentation.runOnMainSync {
                    model = ViewModelProvider(photoActivity!! as ElderPhotoActivity)[ElderPhotoSubmitViewModel::class.java]
                    model.select(uri.toString())
                }
                withTimeout(10_000) { model.state.first { it.stage == PhotoStage.PREVIEW } }
                instrumentation.runOnMainSync { photoActivity!!.findViewById<View>(R.id.photo_use).performClick() }
                withTimeout(20_000) { model.state.first { it.stage == PhotoStage.CONFIRMED } }
                val record = s.commands.observeCommands().first().single { it.commandKind == OnlineCommandKind.SUBMIT_PHOTO }
                assertEquals(OnlineCommandState.CONFIRMED, record.state)
                assertEquals(CacheState.APPLIED, record.cacheState)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                write(b.put("photoId", record.resultRef!!.id).put("photoHash", hash).put("photoOperation", record.operationId))
                instrumentation.runOnMainSync { photoActivity!!.finish() }
                instrumentation.removeMonitor(monitor)
            }
            "caregiver-plan-ui" -> {
                val b = read(); ready(); val activity = launch()
                success(s.pairing.refresh()); success(s.photos.refresh(b.getString("elderId")))
                val relationships = s.pairing.observeRelationships().first { it.access == AccessState.AUTHORIZED }
                val active = CaregiverContextResolver.resolve(s.auth.observeSession().first(), s.trusted.value,
                    relationships, b.getString("relationshipId")) as CaregiverContextState.Active
                assertEquals(b.getString("elderId"), active.context.elderId)
                val content = success(s.photos.loadContent(b.getString("photoId")))
                val hash = content.openStream().use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
                    .joinToString("") { "%02x".format(it) }
                assertEquals(b.getString("photoHash"), hash)
                waitUntil {
                    var exists = false
                    instrumentation.runOnMainSync { exists = descendants(activity.window.decorView).filterIsInstance<Button>()
                        .any { it.text == activity.getString(R.string.chain_inspect_number, 1) } }
                    exists
                }
                instrumentation.runOnMainSync { descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.chain_inspect_number, 1) }.performClick() }
                click(activity, R.string.browse_create_plan)
                waitUntil {
                    var bound = false
                    instrumentation.runOnMainSync {
                        val editor = descendants(activity.window.decorView).filterIsInstance<PlanEditorView>().single()
                        bound = editor.isShown && descendants(editor).filterIsInstance<EditText>().all { it.isEnabled }
                    }; bound
                }
                instrumentation.runOnMainSync {
                    val editor = descendants(activity.window.decorView).filterIsInstance<PlanEditorView>().single()
                    val fields = descendants(editor).filterIsInstance<EditText>()
                    fields[0].setText("Fictional medicine"); fields[1].setText("One demo unit")
                    descendants(editor).filterIsInstance<CheckBox>().filter { it.visibility == View.VISIBLE }.forEach { it.isChecked = true }
                    descendants(editor).filterIsInstance<Button>().filterNot { it is CheckBox }.single().performClick()
                }
                val plans = withTimeout(20_000) { s.plans.observePlans(b.getString("elderId")).first { it.access == AccessState.AUTHORIZED && it.value.isNotEmpty() } }
                assertEquals(1, plans.value.size)
                val record = s.commands.observeCommands().first().single { it.commandKind == OnlineCommandKind.CONFIRM_PLAN }
                assertEquals(OnlineCommandState.CONFIRMED, record.state)
                success(s.commands.resume(record.operationId))
                assertEquals(1, s.plans.observePlans(b.getString("elderId")).first().value.size)
                write(b.put("planId", plans.value.single().id).put("planOperation", record.operationId)
                    .put("caregiverPid", android.os.Process.myPid()))
                screenshot(activity, "caregiver-plan")
            }
            "caregiver-restart" -> {
                val b = read(); val session = ready()
                assertNotEquals(b.getInt("caregiverPid"), android.os.Process.myPid())
                assertEquals(b.getString("caregiverId"), session.user!!.id)
                // No Activity or refresh yet: these are real Room rows recovered after force-stop.
                val saved = s.plans.observePlans(b.getString("elderId")).first()
                assertTrue(saved.hasLocalData); assertEquals(b.getString("planId"), saved.value.single().id)
                val record = s.commands.observeCommands().first().single { it.operationId == b.getString("planOperation") }
                assertEquals(OnlineCommandState.CONFIRMED, record.state)
                launch()
                success(s.pairing.refresh()); success(s.photos.refresh(b.getString("elderId")))
            }
            "caregiver-browser" -> {
                val b = read(); ready()
                var activity = launch()
                var host: ChainViewModel? = null
                waitUntil {
                    instrumentation.runOnMainSync { host = featureModel(activity as CaregiverHomeActivity) }
                    host!!.state.value.browser.items.any { it.id == b.getString("planId") }
                }
                instrumentation.runOnMainSync {
                    descendants(activity.window.decorView).filterIsInstance<Button>()
                        .single { it.text.contains("Fictional medicine") }.performClick()
                }
                waitUntil { host!!.state.value.browser.details?.plan?.id == b.getString("planId") }
                instrumentation.runOnMainSync {
                    assertEquals(activity.getString(R.string.browse_preview_unavailable),
                        activity.findViewById<TextView>(R.id.plan_preview_status).text.toString())
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<PlanEditorView>().none { it.isShown })
                    activity.recreate()
                }
                val previous = activity
                waitUntil {
                    activity = foreground(CaregiverHomeActivity::class.java)
                    var selected = false
                    instrumentation.runOnMainSync {
                        host = featureModel(activity as CaregiverHomeActivity)
                        selected = activity !== previous && host!!.state.value.browser.details?.plan?.id == b.getString("planId") &&
                            activity.findViewById<TextView>(R.id.plan_details_heading)?.isShown == true
                    }; selected
                }
                waitUntil { !host!!.state.value.busy && host!!.state.value.browser.plans?.isStale == false }
                instrumentation.waitForIdleSync()
                screenshot(activity, "caregiver-browser")
                click(activity, R.string.browse_plan_photo)
                waitUntil { host!!.state.value.viewed?.metadata?.id == b.getString("photoId") }
                instrumentation.runOnMainSync {
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<ImageView>().any { it.isShown && it.drawable != null })
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<PlanEditorView>().none { it.isShown })
                }
                screenshot(activity, "caregiver-photo")
            }
            "caregiver-navigation" -> {
                val b = read(); ready()
                var activity = launch()
                assertTrue(activity is CaregiverHomeActivity)
                val originalScope = s.scope.value
                waitUntil { var matches = false
                    instrumentation.runOnMainSync { matches = activity.findViewById<TextView>(R.id.current_elder_value).text.toString() == b.getString("elderName") }
                    matches
                }
                nav(activity, R.id.nav_records)
                instrumentation.runOnMainSync {
                    assertEquals(activity.getString(R.string.caregiver_records_title), activity.findViewById<TextView>(R.id.page_title).text.toString())
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<PlanEditorView>().none { it.isShown })
                }
                nav(activity, R.id.nav_settings)
                val wasChinese = androidx.core.os.ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language == "zh"
                click(activity, if (wasChinese) R.string.language_chinese else R.string.language_english)
                waitUntil {
                    activity = foreground(CaregiverHomeActivity::class.java)
                    var retained = false
                    instrumentation.runOnMainSync {
                        retained = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation).selectedItemId == R.id.nav_settings &&
                            activity.findViewById<Button>(R.id.language_button).text == activity.getString(if (wasChinese) R.string.language_english else R.string.language_chinese)
                    }; retained
                }
                assertEquals(originalScope, s.scope.value)
                click(activity, if (wasChinese) R.string.language_english else R.string.language_chinese)
                waitUntil {
                    activity = foreground(CaregiverHomeActivity::class.java)
                    var restored = false
                    instrumentation.runOnMainSync { restored = activity.findViewById<Button>(R.id.language_button).text ==
                        activity.getString(if (wasChinese) R.string.language_chinese else R.string.language_english) }
                    restored
                }
                instrumentation.runOnMainSync { (activity as CaregiverHomeActivity).onBackPressedDispatcher.onBackPressed() }
                instrumentation.runOnMainSync {
                    assertEquals(R.id.nav_plans, activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation).selectedItemId)
                    assertEquals(b.getString("elderName"), activity.findViewById<TextView>(R.id.current_elder_value).text.toString())
                }
            }
            "elder-sees-plan" -> {
                val b = read(); assertEquals(b.getString("elderId"), ready().user!!.id)
                val activity = launch()
                val plans = withTimeout(20_000) { s.plans.observePlans(b.getString("elderId")).first { it.access == AccessState.AUTHORIZED && it.value.isNotEmpty() } }
                assertEquals(b.getString("planId"), plans.value.single().id)
                waitUntil { var shown = false
                    instrumentation.runOnMainSync { shown = descendants(activity.window.decorView).filterIsInstance<TextView>()
                        .any { it.text.contains("Fictional medicine") && it.text.contains("One demo unit") && it.text.contains("08:00") }
                        assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().none {
                            it.text == activity.getString(R.string.chain_refresh) || it.text == activity.getString(R.string.chain_recover)
                        })
                        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().none {
                            it.isShown && it.text == activity.getString(R.string.chain_refreshed)
                        })
                    }
                    shown
                }
                screenshot(activity, "elder-plan")
            }
            "caregiver-lost-reply" -> {
                val b = read()
                val transport = object : ChainHttp(BuildConfig.DEBUG_SERVER_URL) {
                    override suspend fun call(method: String, path: String, token: String?, operationId: String?,
                        json: String?, photo: File?, photoType: String?): Reply {
                        val actual = super.call(method, path, token, operationId, json, photo, photoType)
                        // The real service has committed; only delivery to this client is lost.
                        if (path.endsWith("/reminder-plan-confirmations") && actual.status == 201)
                            throw IOException("Test response delivery failure")
                        return actual
                    }
                }
                val fault = DebugChainRepository(app, BuildConfig.DEBUG_SERVER_URL, http = transport)
                try {
                    withTimeout(10_000) { fault.observeSession().first { it.readiness == AuthReadiness.READY } }
                    success(fault.refresh()); success(fault.refresh(b.getString("elderId")))
                    val before = fault.observePlans(b.getString("elderId")).first().value.size
                    val command = success(fault.begin(OnlineCommandKind.CONFIRM_PLAN, b.getString("elderId")))
                    val draft = ConfirmReminderPlanRequest(b.getString("photoId"), MedicationText("Recovery fixture", "One test unit"),
                        ReminderPlanDraft(listOf("09:00"), java.time.LocalDate.now().toString(), "Asia/Shanghai"),
                        TimeZoneAcknowledgement("Asia/Shanghai", true, "Asia/Shanghai", null))
                    val result = fault.confirm(b.getString("elderId"), draft, command)
                    assertTrue(result is OnlineCommandResult.AwaitingConfirmation)
                    assertEquals(command.operationId, result.operationId)
                    assertEquals(OnlineCommandState.AWAITING_CONFIRMATION,
                        fault.observeCommands().first().single { it.operationId == command.operationId }.state)
                    write(b.put("unknownOperation", command.operationId).put("expectedPlans", before + 1)
                        .put("unknownPid", android.os.Process.myPid()))
                } finally { fault.close() }
            }
            "caregiver-recover" -> {
                val b = read(); ready()
                assertNotEquals(b.getInt("unknownPid"), android.os.Process.myPid())
                val original = b.getString("unknownOperation")
                val before = s.commands.observeCommands().first().single { it.operationId == original }
                assertEquals(OnlineCommandState.AWAITING_CONFIRMATION, before.state)
                val recovered = success(s.commands.resume(original))
                assertEquals(original, recovered.operationId)
                assertEquals(OnlineCommandState.CONFIRMED, recovered.state)
                assertEquals(CacheState.APPLIED, recovered.cacheState)
                val plans = s.plans.observePlans(b.getString("elderId")).first()
                assertEquals(b.getInt("expectedPlans"), plans.value.size)
                success(s.commands.resume(original))
                success(s.photos.refresh(b.getString("elderId")))
                assertEquals(b.getInt("expectedPlans"), s.plans.observePlans(b.getString("elderId")).first().value.size)
            }
            "caregiver-stale-401" -> {
                val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val once = AtomicBoolean()
                val transport = object : ChainHttp(BuildConfig.DEBUG_SERVER_URL) {
                    override suspend fun call(method: String, path: String, token: String?, operationId: String?,
                        json: String?, photo: File?, photoType: String?): Reply {
                        val actual = super.call(method, path, token, operationId, json, photo, photoType)
                        if (path == "/api/v1/pairing-requests" && once.compareAndSet(false, true)) {
                            held.complete(Unit); release.await()
                            val id = "debug-stale-response"
                            val body = ContractProtocol.json.encodeToString(com.group33.shared.model.ApiError.serializer(),
                                com.group33.shared.model.ApiError("UNAUTHORIZED", "Test stale reply", false, id))
                            return Reply(401, body.toByteArray(), "application/json", null, id)
                        }
                        return actual
                    }
                }
                val fault = DebugChainRepository(app, BuildConfig.DEBUG_SERVER_URL, http = transport)
                try {
                    withTimeout(10_000) { fault.observeSession().first { it.readiness == AuthReadiness.READY } }
                    val old = fault.services.trusted.value
                    val oldRead = async { fault.refresh() }
                    withTimeout(10_000) { held.await() }
                    success(fault.login(UserRole.CAREGIVER, "Demo Caregiver"))
                    val latest = fault.services.trusted.value
                    assertNotEquals(old, latest)
                    release.complete(Unit)
                    assertTrue(withTimeout(10_000) { oldRead.await() } is Outcome.Failure)
                    assertEquals(AuthReadiness.READY, fault.observeSession().first().readiness)
                    assertEquals(latest, fault.services.trusted.value)
                } finally { release.complete(Unit); fault.close() }
            }
            "caregiver-read-failure" -> {
                val b = read(); val fail = AtomicBoolean()
                val transport = object : ChainHttp(BuildConfig.DEBUG_SERVER_URL) {
                    override suspend fun call(method: String, path: String, token: String?, operationId: String?,
                        json: String?, photo: File?, photoType: String?): Reply {
                        val actual = super.call(method, path, token, operationId, json, photo, photoType)
                        if (path.endsWith("/reminder-plans") && fail.get()) {
                            val id = "debug-unavailable-response"
                            val body = ContractProtocol.json.encodeToString(com.group33.shared.model.ApiError.serializer(),
                                com.group33.shared.model.ApiError("SERVICE_UNAVAILABLE", "Test read failure", true, id))
                            return Reply(503, body.toByteArray(), "application/json", null, id)
                        }
                        return actual
                    }
                }
                val fault = DebugChainRepository(app, BuildConfig.DEBUG_SERVER_URL, http = transport)
                try {
                    withTimeout(10_000) { fault.observeSession().first { it.readiness == AuthReadiness.READY } }
                    success(fault.refresh()); success(fault.refresh(b.getString("elderId")))
                    val before = fault.observePlans(b.getString("elderId")).first()
                    assertFalse(before.isStale)
                    fail.set(true)
                    assertTrue(fault.refresh(b.getString("elderId")) is Outcome.Failure)
                    val after = fault.observePlans(b.getString("elderId")).first()
                    assertTrue(after.hasLocalData); assertTrue(after.isStale)
                    assertEquals(before.value, after.value)
                    assertEquals(before.lastSuccessfulSyncAtEpochMillis, after.lastSuccessfulSyncAtEpochMillis)
                    assertEquals(AuthReadiness.READY, fault.observeSession().first().readiness)
                } finally { fault.close() }
            }
            "elder-revoke" -> {
                val b = read(); ready(); val activity = launch()
                success(s.pairing.refresh()); instrumentation.waitForIdleSync()
                click(activity, R.string.chain_revoke)
                withTimeout(20_000) { s.pairing.observeRelationships().first { rows ->
                    rows.access == AccessState.AUTHORIZED && rows.value.any { it.id == b.getString("relationshipId") && it.status == CareRelationshipStatus.REVOKED }
                } }
            }
            "caregiver-denied" -> {
                val b = read(); ready(); val activity = launch(); success(s.pairing.refresh())
                assertTrue(s.photos.loadContent(b.getString("photoId")) is Outcome.Failure)
                assertTrue(s.photos.refresh(b.getString("elderId")) is Outcome.Failure)
                assertEquals(AuthReadiness.READY, s.auth.observeSession().first().readiness)
                waitUntil { var cleared = false
                    instrumentation.runOnMainSync { cleared = descendants(activity.window.decorView).filterIsInstance<PlanEditorView>().none { it.isShown } &&
                        descendants(activity.window.decorView).filterIsInstance<TextView>().none { it.text.contains("Fictional medicine") } &&
                        activity.findViewById<TextView>(R.id.current_elder_value).text == activity.getString(R.string.caregiver_no_current_elder) }
                    cleared
                }
            }
            else -> error("Unknown integration phase")
        }
    }
}
