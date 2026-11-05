package com.group33.medicationreminder.elder

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM regression for #19 review: small-width / large-font entry must remain reachable via scroll.
 * Does not replace device TalkBack or API 35 evidence.
 */
class ElderEntryAccessibilityLayoutTest {
    @Test
    fun `role entry layout is nested-scrollable so large font cannot trap the elder button`() {
        val moduleDir = checkNotNull(System.getProperty("group33.androidModuleDir")) {
            "group33.androidModuleDir system property is required"
        }
        val xml = File(moduleDir, "src/main/res/layout/activity_main.xml").readText()
        assertTrue(xml.contains("NestedScrollView"))
        assertTrue(xml.contains("@+id/elder_button"))
        assertTrue(xml.contains("@+id/main_scroll"))
        assertTrue(xml.contains("fillViewport"))
    }

    @Test
    fun `due status chip uses emphasis size token of at least 24sp`() {
        val moduleDir = checkNotNull(System.getProperty("group33.androidModuleDir"))
        val dimens = File(moduleDir, "src/main/res/values/dimens.xml").readText()
        val layout = File(moduleDir, "src/main/res/layout/activity_elder_home.xml").readText()
        assertTrue(dimens.contains("name=\"group33_elder_emphasis_text\">24sp"))
        assertTrue(layout.contains("@dimen/group33_elder_emphasis_text"))
        assertTrue(layout.contains("@+id/due_status_chip"))
    }
}
