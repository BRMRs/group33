package com.group33.medicationreminder.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import com.group33.medicationreminder.core.reminder.*
import com.group33.medicationreminder.elder.LocalElderOccurrence
import com.group33.shared.contract.*
import com.group33.shared.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real Room/session/dispatcher with fictional, injected transport responses; no cloud or SMS. */
class RuntimeActionBoundaryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = ContractProtocol.json

    private inner class Transport : ChainHttp("http://127.0.0.1:18979") {
        var registrationStatus = 201
        var actionStatus = 200
        var calls = 0
        lateinit var occurrence: LocalElderOccurrence
        override suspend fun call(method: String, path: String, token: String?, operationId: String?,
                                  json: String?, photo: File?, photoType: String?): Reply {
            if (path == "/api/v1/auth/anonymous") return reply(201,
                this@RuntimeActionBoundaryTest.json.encodeToString(AuthResponse.serializer(),
                    AuthResponse("fictional-token", "Bearer", 3600, User("fictional-elder", "Fixture", UserRole.ELDER))))
            calls++
            if (path.endsWith("/occurrence-registrations")) return reply(registrationStatus, "{}")
            check(path.endsWith("/dose-action-groups"))
            if (actionStatus != 200) return reply(actionStatus, "{}")
            val request = this@RuntimeActionBoundaryTest.json.decodeFromString(DebugDoseGroupRequest.serializer(), checkNotNull(json))
            val evidence = OccurrenceGenerationEvidence("fixture-generation", occurrence.key, 1,
                occurrence.generatedAt, "UTC", occurrence.plannedAt, 0, TimeResolutionPolicy.P0_TIME_V1,
                "fixture-snapshot", occurrence.planSnapshotAt)
            val event = DoseEventView("fixture-event", "fictional-elder", occurrence.key, 1, "fixture-medication",
                occurrence.medication, "UTC", occurrence.plannedAt, occurrence.plannedAt + 7_200_000,
                evidence, DoseStatus.TAKEN, emptyList(), request.recordedAtEpochMillis,
                takenRecordedAtEpochMillis = request.recordedAtEpochMillis)
            return reply(200, this@RuntimeActionBoundaryTest.json.encodeToString(DebugDoseGroupReceipt.serializer(),
                DebugDoseGroupReceipt(request.actionId, request.recordedAtEpochMillis, listOf(event))))
        }
        private fun reply(status: Int, body: String) = Reply(status, body.toByteArray(), "application/json", null, null)
    }

    private data class Fixture(
        val repo: DebugChainRepository,
        val adapter: DebugDoseReminders,
        val trusted: TrustedSessionContext,
        val registration: ReminderGroupWithOccurrences,
        val action: ReminderActionRow,
        val transport: Transport,
    )

    private suspend fun fixture(scopeOverride: (RepositoryScope) -> RepositoryScope = { it }): Fixture {
        val transport = Transport()
        val repo = DebugChainRepository(context, transport.baseUrl, "boundary-${UUID.randomUUID()}", transport)
        assertTrue(repo.login(UserRole.ELDER, "Fixture") is Outcome.Success)
        val trusted = withTimeout(5_000) { repo.services.trusted.first { it != null }!! }
        val now = System.currentTimeMillis()
        val registration = testRegistration("boundary-${UUID.randomUUID()}", now - 1_000,
            executionScopeId = digest(scopeOverride(trusted.scope)), state = ReminderState.RINGING)
        val key = registration.occurrences.single().let { OccurrenceKey(it.planId, it.localDate, it.localTime) }
        val occurrence = LocalElderOccurrence(key, 1, MedicationText("Fictional medicine", "One demo unit"),
            now - 1_000, "UTC", 0, now - 10_000, now - 20_000)
        transport.occurrence = occurrence
        val scope = digest(trusted.scope)
        val target = "${key.planId}/${key.localDate}/${key.localTime}"
        val cache = ChainCache.open(context)
        try {
            cache.documents().put(ChainDocument("$scope:elder-dose:$target", scope, "elder-dose", target,
                json.encodeToString(LocalElderOccurrence.serializer(), occurrence), now))
        } finally { cache.close() }
        val action = ReminderActionRow(UUID.randomUUID().toString(), registration.group.groupId,
            ReminderActionKind.TAKEN.name, now)
        return Fixture(repo, DebugDoseReminders(context, repo), trusted,
            ReminderGroupWithOccurrences(registration.group, registration.occurrences), action, transport)
    }

    private fun digest(scope: RepositoryScope) = ChainCache.digest(listOf(scope.environmentId,
        scope.installationId, scope.userId).joinToString("") { "${it.length}:$it" })

    @Test fun rejectedRegistrationIsNotServerAcknowledgement() = runBlocking {
        val f = fixture()
        f.transport.registrationStatus = 422
        assertEquals(ReminderDispatchResult.PERMANENT_FAILURE, f.adapter.dispatchRuntimeAction(f.action, f.registration))
        assertEquals(1, f.transport.calls)
    }

    @Test fun rejectedActionIsNotServerAcknowledgement() = runBlocking {
        val f = fixture()
        f.transport.actionStatus = 422
        assertEquals(ReminderDispatchResult.PERMANENT_FAILURE, f.adapter.dispatchRuntimeAction(f.action, f.registration))
        assertEquals(2, f.transport.calls)
    }

    @Test fun acceptedOriginalActionCanBeAcknowledgedAndReplayedWithoutAnotherWrite() = runBlocking {
        val f = fixture()
        assertEquals(ReminderDispatchResult.ACKNOWLEDGED, f.adapter.dispatchRuntimeAction(f.action, f.registration))
        assertEquals(2, f.transport.calls)
        assertEquals(ReminderDispatchResult.ACKNOWLEDGED, f.adapter.dispatchRuntimeAction(f.action, f.registration))
        assertEquals(2, f.transport.calls)
    }

    @Test fun unavailableServerKeepsOriginalActionRetryable() = runBlocking {
        val f = fixture()
        f.transport.actionStatus = 503
        assertEquals(ReminderDispatchResult.RETRY, f.adapter.dispatchRuntimeAction(f.action, f.registration))
        f.transport.actionStatus = 200
        assertEquals(ReminderDispatchResult.ACKNOWLEDGED, f.adapter.dispatchRuntimeAction(f.action, f.registration))
        assertEquals(4, f.transport.calls)
    }

    @Test fun sameElderInAnotherEnvironmentCannotReceiveTheOldAction() = runBlocking {
        assertScopeWaits { it.copy(environmentId = "another-environment") }
    }

    @Test fun sameElderOnAnotherInstallationCannotReceiveTheOldAction() = runBlocking {
        assertScopeWaits { it.copy(installationId = "another-installation") }
    }

    @Test fun anotherAccountDoesNotPermanentlyDiscardTheOriginalAction() = runBlocking {
        val f = fixture { it.copy(userId = "another-elder") }
        val old = f.registration.copy(group = f.registration.group.copy(elderId = "another-elder"))
        assertEquals(ReminderDispatchResult.RETRY, f.adapter.dispatchRuntimeAction(f.action, old))
        assertEquals(0, f.transport.calls)
    }

    private suspend fun assertScopeWaits(change: (RepositoryScope) -> RepositoryScope) {
        val f = fixture(change)
        assertEquals(ReminderDispatchResult.RETRY, f.adapter.dispatchRuntimeAction(f.action, f.registration))
        assertEquals(0, f.transport.calls)
        // Returning to the original execution scope can still recover this same action ID.
        val original = f.registration.copy(group = f.registration.group.copy(executionScopeId = digest(f.trusted.scope)))
        assertEquals(ReminderDispatchResult.ACKNOWLEDGED, f.adapter.dispatchRuntimeAction(f.action, original))
    }

    @Test fun unsupportedHelpRemainsExplicitlyUnacknowledged() = runBlocking {
        val f = fixture()
        assertEquals(ReminderDispatchResult.PERMANENT_FAILURE, f.adapter.dispatchRuntimeAction(
            f.action.copy(kind = ReminderActionKind.HELP.name), f.registration))
        assertEquals(0, f.transport.calls)
    }

    @Test fun snoozeAtTheLastTenMinutesDoesNotCommitAnUnsendableAction() = runBlocking {
        for (remaining in listOf(600_000L, 599_999L, 1L)) {
            val now = System.currentTimeMillis()
            val registration = testRegistration("late-snooze-${UUID.randomUUID()}", now + remaining - 7_200_000,
                state = ReminderState.RINGING)
            val dao = ReminderDatabase.open(context).reminders()
            dao.insertTestRegistration(registration)
            val id = UUID.randomUUID().toString()
            assertFalse(ReminderCoordinator(context).recordAction(registration.group.groupId, id, ReminderActionKind.SNOOZE, now))
            assertNull(dao.action(id))
            assertEquals(ReminderState.RINGING.name, dao.find(registration.group.groupId)?.state)
            assertEquals(registration.group.deadlineAtEpochMillis, dao.find(registration.group.groupId)?.deadlineAtEpochMillis)
            assertTrue(ReminderCoordinator(context).recordAction(registration.group.groupId,
                UUID.randomUUID().toString(), ReminderActionKind.TAKEN, now))
        }
    }

    @Test fun fullTenMinuteSnoozeStillWorksOneMillisecondBeforeItsBoundary() = runBlocking {
        val now = System.currentTimeMillis()
        val registration = testRegistration("valid-snooze-${UUID.randomUUID()}", now - 6_599_999,
            state = ReminderState.RINGING)
        val dao = ReminderDatabase.open(context).reminders()
        dao.insertTestRegistration(registration)
        assertTrue(ReminderCoordinator(context).recordAction(registration.group.groupId,
            UUID.randomUUID().toString(), ReminderActionKind.SNOOZE, now))
        assertEquals(now + 600_000, dao.find(registration.group.groupId)?.snoozedUntilEpochMillis)
        assertEquals(registration.group.deadlineAtEpochMillis, dao.find(registration.group.groupId)?.deadlineAtEpochMillis)
    }
}
