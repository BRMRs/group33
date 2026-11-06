package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModelStore
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingWorkflowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = FakePairingRepository()
    private val scope = RepositoryScope("test", "installation", "caregiver")
    private val provider = PairingCommandContextProvider { kind, target ->
        CommandContext("00000000-0000-0000-0000-000000000001", scope, kind, target)
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun `eight digit code retains leading zero and unknown result is not resubmitted on refresh`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.setCode("0012345")
        model.submitCode()
        assertEquals(0, repository.requestCount)
        assertEquals(PairingNotice.InvalidCode, model.state.value.notice)

        model.setCode("00123456")
        model.submitCode()
        runCurrent()
        assertEquals("00123456", repository.lastCode)
        assertEquals(1, repository.requestCount)
        assertEquals(PairingNotice.ResultUnknown, model.state.value.notice)
        model.refresh()
        runCurrent()
        assertEquals(1, repository.requestCount)
        assertEquals("00123456", model.state.value.code)
    }

    @Test fun `server confirmed pending request stays pending and is never treated as an active relationship`() = runTest(dispatcher) {
        val pending = PairingRequestView(
            "request-1", "elder-1", User("caregiver", "Caregiver", UserRole.CAREGIVER),
            PairingRequestStatus.PENDING, 10_000L,
        )
        repository.confirmedRequest = pending
        val model = model(); runCurrent()
        model.setCode("00001234")
        model.submitCode()
        runCurrent()
        assertEquals(PairingNotice.CommandConfirmed, model.state.value.notice)
        assertEquals(listOf(pending), model.state.value.requests)
        assertEquals(PairingRequestStatus.PENDING, model.state.value.requests.single().status)
    }

    private fun model() = PairingWorkflowViewModel(repository, provider).also { store.put("pairing", it) }

    private class FakePairingRepository : PairingRepository {
        val requests = MutableStateFlow(Cached(emptyList<PairingRequestView>(), true, 1L, false, AccessState.AUTHORIZED))
        var requestCount = 0
        var lastCode: String? = null
        var confirmedRequest: PairingRequestView? = null
        override fun observeRequests() = requests
        override fun observeRelationships() = flowOf(Cached(emptyList<CareRelationshipView>(), true, 1L, false, AccessState.AUTHORIZED))
        override suspend fun issueCode(command: CommandContext) = notSubmitted<PairingCodeView>(command)
        override suspend fun cancelCode(codeId: String, command: CommandContext) = notSubmitted<Unit>(command)
        override suspend fun requestPairing(code: String, command: CommandContext): OnlineCommandResult<PairingRequestView> {
            requestCount++; lastCode = code
            confirmedRequest?.let { pending ->
                requests.value = requests.value.copy(value = listOf(pending))
                return OnlineCommandResult.Confirmed(command.operationId, pending, CacheState.APPLIED)
            }
            return OnlineCommandResult.AwaitingConfirmation(command.operationId)
        }
        override suspend fun respond(requestId: String, accept: Boolean, command: CommandContext) = notSubmitted<PairingDecisionResult>(command)
        override suspend fun cancelRequest(requestId: String, command: CommandContext) = notSubmitted<Unit>(command)
        override suspend fun revoke(relationshipId: String, command: CommandContext) = notSubmitted<CareRelationshipView>(command)
        override suspend fun refresh() = Outcome.Success(Unit)
        private fun <T> notSubmitted(command: CommandContext): OnlineCommandResult<T> =
            OnlineCommandResult.NotSubmitted(command.operationId, RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE))
    }
}
