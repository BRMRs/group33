package com.group33.medicationreminder.caregiver

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.Cached
import com.group33.medicationreminder.core.data.contract.CaregiverElderContext
import com.group33.medicationreminder.core.data.contract.DoseEventRepository
import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.medicationreminder.core.data.contract.LocalActionReceipt
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import com.group33.medicationreminder.core.data.contract.RetryQueued
import com.group33.medicationreminder.core.data.contract.ServerAcceptedLateTakenReport
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.contract.OccurrenceGenerationEvidence
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.contract.TimeResolutionPolicy
import com.group33.shared.model.DoseStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaregiverHistoryHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = RepositoryScope("test-cloud", "test-installation", "test-caregiver")
    private val range = LocalDateRange("2026-10-01", "2026-10-30")

    @Test fun trustedContextAndExplicitRangeReachReadOnlyRepository() = withHost { activity, host, repository ->
        val trusted = context(generation = 7, relationship = "relationship-a", elder = "elder-a", name = "Elder A")
        onMain { host.bind(activity, repository, trusted, range, activity.supportFragmentManager) }
        awaitIdle()
        onMain {
            assertEquals(listOf("elder-a" to range), repository.observations)
            assertEquals(activity.getString(R.string.c3_elder, "Elder A"), host.findViewById<TextView>(R.id.history_elder).text.toString())
            assertEquals(0, repository.commandCalls)
        }
    }

    @Test fun sameIdentityRebindKeepsRevocationButNewSessionCanObserveAgain() = withHost { activity, host, repository ->
        val trusted = context(generation = 7, relationship = "relationship-a", elder = "elder-a", name = "Elder A")
        onMain { host.bind(activity, repository, trusted, range, activity.supportFragmentManager) }
        awaitIdle()
        onMain { repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED) }
        awaitIdle()
        onMain {
            host.bind(activity, repository, trusted.copy(elderDisplayName = "Updated name"),
                LocalDateRange("2026-10-02", "2026-10-30"), activity.supportFragmentManager)
            assertEquals("Revoked same-identity rebinding must not start a new read", 1, repository.observations.size)
            repository.updates.value = repository.updates.value.copy(access = AccessState.AUTHORIZED)
            host.bind(activity, repository, trusted.copy(sessionGeneration = 8), range, activity.supportFragmentManager)
        }
        awaitIdle()
        onMain {
            assertEquals("A new trusted session identity gets a new local observation", 2, repository.observations.size)
            assertEquals("elder-a" to range, repository.observations.last())
        }
    }

    @Test fun identicalBindPreservesAppliedDateRangeAndSelectedDetails() = withHost { activity, host, repository ->
        val trusted = context()
        onMain { host.bind(activity, repository, trusted, range, activity.supportFragmentManager) }
        awaitIdle()
        val applied = LocalDateRange("2026-10-20", "2026-10-21")
        onMain {
            host.findViewById<EditText>(R.id.history_from).setText(applied.fromDate)
            host.findViewById<EditText>(R.id.history_through).setText(applied.throughDate)
            host.findViewById<View>(R.id.history_apply).performClick()
        }
        awaitIdle()
        onMain {
            val list = host.findViewById<ListView>(R.id.history_list)
            assertEquals(3, list.adapter.count)
            list.performItemClick(list.adapter.getView(1, null, list), 1, list.adapter.getItemId(1))
        }
        awaitIdle()
        onMain {
            val list = host.findViewById<ListView>(R.id.history_list)
            val detailPosition = (list.headerViewsCount until list.adapter.count)
                .first { list.adapter.getItemViewType(it) == 1 }
            list.setSelectionFromTop(detailPosition, 0)
        }
        awaitIdle()
        onMain {
            host.bind(activity, repository, trusted, range, activity.supportFragmentManager)
        }
        awaitIdle()
        onMain {
            assertEquals(2, repository.observations.size)
            assertEquals(applied.fromDate, host.findViewById<EditText>(R.id.history_from).text.toString())
            assertEquals(applied.throughDate, host.findViewById<EditText>(R.id.history_through).text.toString())
            val list = host.findViewById<ListView>(R.id.history_list)
            assertEquals("The selected detail remains in the history adapter", 4, list.adapter.count)
            val detailPosition = (list.headerViewsCount until list.adapter.count)
                .first { list.adapter.getItemViewType(it) == 1 }
            assertEquals(activity.getString(R.string.c3_history_instructions, "Instructions 21"),
                list.adapter.getView(detailPosition, null, list).findViewById<TextView>(R.id.history_instructions).text.toString())
        }
    }

    @Test fun clearAndRepositoryRebindDoNotBypassRevocationButNewRelationshipAndSessionDo() = withHost { activity, host, repository ->
        val trusted = context()
        val replacementRepository = Repository()
        onMain { host.bind(activity, repository, trusted, range, activity.supportFragmentManager) }
        awaitIdle()
        onMain { repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED) }
        awaitIdle()
        onMain {
            assertEquals(activity.getString(R.string.c3_revoked), host.findViewById<TextView>(R.id.history_state).text.toString())
            assertEquals("", host.findViewById<TextView>(R.id.history_elder).text.toString())
            host.bind(activity, repository, null, range, activity.supportFragmentManager)
            assertEquals("", host.findViewById<TextView>(R.id.history_elder).text.toString())
            host.bind(activity, repository, trusted, range, activity.supportFragmentManager)
            assertEquals(activity.getString(R.string.c3_revoked), host.findViewById<TextView>(R.id.history_state).text.toString())
            host.bind(activity, replacementRepository, trusted, range, activity.supportFragmentManager)
            assertEquals(activity.getString(R.string.c3_revoked), host.findViewById<TextView>(R.id.history_state).text.toString())
            assertEquals(0, replacementRepository.observations.size)
            host.bind(activity, replacementRepository, trusted.copy(relationshipId = "relationship-b"), range,
                activity.supportFragmentManager)
        }
        awaitIdle()
        onMain {
            assertEquals(1, replacementRepository.observations.size)
            assertEquals("elder-a" to range, replacementRepository.observations.single())
            assertEquals(activity.getString(R.string.c3_elder, "Elder A"), host.findViewById<TextView>(R.id.history_elder).text.toString())
            replacementRepository.updates.value = replacementRepository.updates.value.copy(access = AccessState.REVOKED)
        }
        awaitIdle()
        onMain {
            assertEquals(activity.getString(R.string.c3_revoked), host.findViewById<TextView>(R.id.history_state).text.toString())
            replacementRepository.updates.value = replacementRepository.updates.value.copy(access = AccessState.AUTHORIZED)
            host.bind(activity, replacementRepository, trusted.copy(relationshipId = "relationship-b", sessionGeneration = 8),
                range, activity.supportFragmentManager)
        }
        awaitIdle()
        onMain {
            assertEquals(2, replacementRepository.observations.size)
            assertEquals(activity.getString(R.string.c3_elder, "Elder A"), host.findViewById<TextView>(R.id.history_elder).text.toString())
            assertEquals(0, repository.commandCalls + replacementRepository.commandCalls)
        }
    }

    @Test fun nullContextClearsPrivateUiAndOldSourceUpdatesCannotRestoreIt() = withHost { activity, host, repository ->
        onMain { host.bind(activity, repository, context(), range, activity.supportFragmentManager) }
        awaitIdle()
        onMain {
            host.bind(activity, repository, null, range, activity.supportFragmentManager)
            assertEquals("", host.findViewById<TextView>(R.id.history_elder).text.toString())
            assertEquals(activity.getString(R.string.c3_select_elder), host.findViewById<TextView>(R.id.history_state).text.toString())
            repository.updates.value = repository.updates.value.copy(lastSuccessfulSyncAtEpochMillis = 456L)
        }
        awaitIdle()
        onMain {
            assertEquals("", host.findViewById<TextView>(R.id.history_elder).text.toString())
            assertEquals(0, repository.commandCalls)
        }
    }

    @Test fun detachingClearsHostAndOldSourceUpdatesDoNotRender() = withHost { activity, host, repository ->
        onMain { host.bind(activity, repository, context(), range, activity.supportFragmentManager) }
        awaitIdle()
        onMain {
            activity.container.removeView(host)
            repository.updates.value = repository.updates.value.copy(access = AccessState.REVOKED)
        }
        awaitIdle()
        onMain {
            assertEquals("", host.findViewById<TextView>(R.id.history_elder).text.toString())
            activity.container.addView(host)
            host.bind(activity, repository, context(generation = 8), range, activity.supportFragmentManager)
        }
        awaitIdle()
        onMain {
            assertEquals("A detached host recreates its model when rebound", 2, repository.observations.size)
        }
    }

    private fun context(
        generation: Long = 7,
        relationship: String = "relationship-a",
        elder: String = "elder-a",
        name: String = "Elder A",
    ) = CaregiverElderContext(scope, generation, relationship, elder, name)

    private fun withHost(block: (CaregiverHistoryTestActivity, CaregiverHistoryHost, Repository) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CaregiverHistoryTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaregiverHistoryTestActivity
        val repository = Repository()
        lateinit var host: CaregiverHistoryHost
        onMain {
            host = CaregiverHistoryHost(activity)
            activity.container.addView(host)
        }
        awaitIdle()
        try { block(activity, host, repository) } finally {
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun awaitIdle() {
        instrumentation.waitForIdleSync()
    }

    private class Repository : DoseEventRepository {
        val updates = MutableStateFlow(Cached(listOf(event(20), event(21)), true, 123L, false, AccessState.AUTHORIZED))
        val observations = mutableListOf<Pair<String, LocalDateRange>>()
        var commandCalls = 0
        override fun observeEvents(elderId: String, range: LocalDateRange): kotlinx.coroutines.flow.Flow<Cached<List<DoseEventState>>> {
            observations += elderId to range
            return updates.asStateFlow().map { cached -> cached.copy(value = cached.value.filter {
                it.occurrence.localDate >= range.fromDate && it.occurrence.localDate <= range.throughDate
            }) }
        }
        override fun observeLocalAction(actionId: String): kotlinx.coroutines.flow.Flow<LocalActionReceipt?> { commandCalls++; error("Not used by history") }
        override suspend fun recordAction(occurrence: OccurrenceKey, kind: DoseActionKind): Outcome<LocalActionReceipt> { commandCalls++; error("Not used by history") }
        override suspend fun retryAction(actionId: String): Outcome<RetryQueued> { commandCalls++; error("Not used by history") }

        companion object {
            private fun event(day: Int): DoseEventState {
                val occurrence = OccurrenceKey("test-plan", "2026-10-$day", "08:00")
                val evidence = OccurrenceGenerationEvidence("test-generation-$day", occurrence, 1, day.toLong(),
                    "Asia/Shanghai", day.toLong(), 28_800, TimeResolutionPolicy.P0_TIME_V1, "test-opaque", 1L)
                return DoseEventState(occurrence, "test-event-$day", evidence, "test-medication",
                    MedicationText("Frozen medicine $day", "Frozen dosage $day", "Instructions $day"),
                    7_200_000L + day, DoseStatus.TAKEN, DoseStatus.TAKEN, emptyList(), emptyList<ServerAcceptedLateTakenReport>())
            }
        }
    }
}
