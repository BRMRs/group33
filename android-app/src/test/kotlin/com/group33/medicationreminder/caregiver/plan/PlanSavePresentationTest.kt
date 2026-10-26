package com.group33.medicationreminder.caregiver.plan

import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.CacheState
import com.group33.medicationreminder.core.data.contract.OnlineCommandState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class PlanSavePresentationTest {
    private val caches = listOf(null, CacheState.APPLIED, CacheState.REFRESH_REQUIRED)

    @Test
    fun unknownOutcomeNeverOffersResubmissionRegardlessOfCache() {
        caches.forEach { cache ->
            val presentation = PlanSavePresentations.forCommand(OnlineCommandState.AWAITING_CONFIRMATION, cache)
            assertEquals(PlanDisplayAction.CHECK_RESULT, presentation.action)
            assertNotEquals(R.string.c2_plan_saved_title, presentation.title)
        }
        assertEquals(PlanDisplayAction.NONE, PlanSavePresentations.submitting().action)
        assertNull(PlanSavePresentations.submitting().actionLabel)
    }

    @Test
    fun cacheProblemsCannotTurnConfirmedSaveIntoFailureOrResubmission() {
        val presentations = caches.map { PlanSavePresentations.forCommand(OnlineCommandState.CONFIRMED, it) }
        presentations.forEach {
            assertEquals(R.string.c2_plan_saved_title, it.title)
            assertTrue(it.action in setOf(PlanDisplayAction.NONE, PlanDisplayAction.REFRESH_CACHE))
        }
        assertEquals(3, presentations.map { it.description }.toSet().size)
        assertEquals(PlanDisplayAction.REFRESH_CACHE, presentations.last().action)
        assertEquals(PlanDisplayAction.NONE, presentations.first().action)
    }

    @Test
    fun confirmedCacheBranchesBindTheirExactExplanationAndActionLabel() {
        val applied = PlanSavePresentations.forCommand(OnlineCommandState.CONFIRMED, CacheState.APPLIED)
        assertEquals(R.string.c2_plan_saved_description, applied.description)
        assertEquals(PlanDisplayAction.NONE, applied.action)
        assertNull(applied.actionLabel)

        val pending = PlanSavePresentations.forCommand(OnlineCommandState.CONFIRMED, CacheState.REFRESH_REQUIRED)
        assertEquals(R.string.c2_plan_cache_pending_description, pending.description)
        assertEquals(PlanDisplayAction.REFRESH_CACHE, pending.action)
        assertEquals(R.string.c2_plan_refresh_cache, pending.actionLabel)

        val unknown = PlanSavePresentations.forCommand(OnlineCommandState.CONFIRMED)
        assertEquals(R.string.c2_plan_cache_unknown_description, unknown.description)
        assertEquals(PlanDisplayAction.NONE, unknown.action)
        assertNull(unknown.actionLabel)
    }

    @Test
    fun rejectionRequiresReviewWhileUnsentRequestCanOfferRetry() {
        caches.forEach {
            assertEquals(PlanDisplayAction.REVIEW_INPUT,
                PlanSavePresentations.forCommand(OnlineCommandState.REJECTED, it).action)
            assertEquals(PlanDisplayAction.RETRY_UNSENT,
                PlanSavePresentations.forCommand(OnlineCommandState.NOT_SUBMITTED, it).action)
        }
    }

    @Test
    fun everyStateAndActionHasReadableTextInBothLocales() {
        val presentations = listOf(PlanSavePresentations.editing(), PlanSavePresentations.submitting()) +
            OnlineCommandState.entries.flatMap { state -> caches.map { PlanSavePresentations.forCommand(state, it) } }
        for (locale in listOf("values", "values-zh")) {
            val resources = strings(locale).mapKeys { R.string::class.java.getField(it.key).getInt(null) }
            presentations.forEach {
                assertFalse(resources[it.title].isNullOrBlank())
                assertFalse(resources[it.description].isNullOrBlank())
                if (it.action == PlanDisplayAction.NONE) assertNull(it.actionLabel)
                else assertFalse(resources[it.actionLabel].isNullOrBlank())
            }
            // Status must be distinguishable in text, without relying on color or icons.
            val titles = OnlineCommandState.entries.map { resources[PlanSavePresentations.forCommand(it).title] }
            assertEquals(OnlineCommandState.entries.size, titles.toSet().size)
        }
    }

    @Test
    fun translationsHaveMatchingKeysAndUsableOrderedTimeZonePlaceholders() {
        val english = strings("values")
        val chinese = strings("values-zh")
        assertEquals(english.keys, chinese.keys)
        val placeholders = Regex("%[0-9]+\\\$s")
        english.forEach { (key, value) ->
            assertTrue(value.isNotBlank())
            assertFalse(chinese[key].isNullOrBlank())
            assertEquals(key, placeholders.findAll(value).map { it.value }.toList(),
                placeholders.findAll(chinese.getValue(key)).map { it.value }.toList())
        }
        for (resources in listOf(english, chinese)) {
            val text = String.format(Locale.ROOT, resources.getValue("c2_plan_zone_comparison"),
                "Europe/London", "Asia/Shanghai", "America/New_York")
            listOf("Europe/London", "Asia/Shanghai", "America/New_York").forEach { assertTrue(text.contains(it)) }
            assertFalse(text.contains("%"))
        }
    }

    private fun strings(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val module = checkNotNull(System.getProperty("group33.androidModuleDir"))
        val nodes = factory.newDocumentBuilder()
            .parse(File(module, "src/main/res/$locale/c2_plan_strings.xml")).getElementsByTagName("string")
        val entries = (0 until nodes.length).map {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        assertEquals("Duplicate resource keys in $locale", entries.size, entries.map { it.first }.toSet().size)
        return entries.toMap()
    }
}
