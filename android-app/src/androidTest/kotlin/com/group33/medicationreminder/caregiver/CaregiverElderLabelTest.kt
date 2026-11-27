package com.group33.medicationreminder.caregiver

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.data.contract.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual platform accessibility-tree labels, not a spoken TalkBack acceptance. */
class CaregiverElderLabelTest {
    @Test fun englishSelectorExposesItsFieldLabel() = verify(LanguageChoice.ENGLISH)
    @Test fun chineseSelectorExposesItsFieldLabel() = verify(LanguageChoice.SIMPLIFIED_CHINESE)

    private fun verify(choice: LanguageChoice) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as Group33Application
        val original = runBlocking { app.languageSettings.observeChoice().first() }
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        fun applyLanguage(target: LanguageChoice) = instrumentation.runOnMainSync {
            runBlocking { assertTrue(app.languageSettings.setChoice(target) is Outcome.Success) }
            Group33Application.applyLanguage(target, app)
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            applyLanguage(choice)
            ActivityScenario.launch<CaregiverHomeActivity>(Intent(context, CaregiverHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                var expected = ""
                scenario.onActivity { expected = it.getString(R.string.caregiver_current_elder) }
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.uptimeMillis() + 10_000
                var found = false
                while (!found && SystemClock.uptimeMillis() < deadline) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName?.toString() == context.packageName) {
                        val nodes = root.findAccessibilityNodeInfosByViewId(
                            context.packageName + ":id/current_elder_value")
                        if (nodes.isNotEmpty()) {
                            val node = nodes.single()
                            assertEquals("The selector must expose its localized field label to accessibility services",
                                expected, node.labeledBy?.text?.toString())
                            found = true
                        }
                    }
                    if (!found) SystemClock.sleep(50)
                }
                assertTrue("The selector must be present in this app's accessibility window", found)
            }
        } finally {
            applyLanguage(original)
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }
}
