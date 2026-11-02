package com.group33.medicationreminder.caregiver.plan

import androidx.lifecycle.ViewModelStore
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanBrowserViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val scope = RepositoryScope("test", "installation", "caregiver")
    private val today = LocalDate.parse("2024-02-27")
    private val plan = ReminderPlanView("plan", "elder", "med", 1, listOf("08:00"),
        "2024-02-27", "Asia/Shanghai", true, 1, 1)
    private val medication = MedicationView("med", "elder", "Demo", "one", true, photoId = "photo")
    private fun <T> cached(value: T) = Cached(value, true, 10L, false, AccessState.AUTHORIZED)
    private inner class Repository : ReminderPlanRepository {
        val plans = MutableStateFlow(cached(listOf(plan)))
        val meds = MutableStateFlow(cached(listOf(medication)))
        val previews = MutableStateFlow(cached(emptyList<PlanPreview>()))
        var fail = false
        var silentPreview = false
        var range: LocalDateRange? = null
        override fun observePlans(elderId: String): Flow<Cached<List<ReminderPlanView>>> =
            plans.map { if (fail) error("offline") else it }
        override fun observeMedications(elderId: String) = meds
        override fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>> {
            this.range = range
            return if (silentPreview) flow { kotlinx.coroutines.awaitCancellation() } else previews
        }
        override suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse> = error("Read must never write")
        override suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse> = error("Read must never write")
        override suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView> = error("Read must never write")
    }
    private fun model(repo: Repository): PlanBrowserViewModel = PlanBrowserViewModel().also {
        store.put("browser", it)
        it.bind(repo, scope, "elder", today)
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun `seven inclusive dates cross leap month and year`() {
        assertEquals(LocalDateRange("2024-02-27", "2024-03-04"), PlanBrowserViewModel.sevenDayRange(today))
        assertEquals(LocalDateRange("2026-12-29", "2027-01-04"), PlanBrowserViewModel.sevenDayRange(LocalDate.parse("2026-12-29")))
    }
    @Test fun `details preserve confirmed fields and photo reference without end date`() = runTest(dispatcher) {
        val repo = Repository(); val vm = model(repo)
        assertEquals(PlanBrowserMessage.LOADING, vm.state.value.message)
        runCurrent(); vm.selectPlan("plan")
        assertEquals(plan, vm.state.value.details?.plan)
        assertEquals("photo", vm.state.value.details?.medication?.photoId)
        assertEquals(PlanBrowserViewModel.sevenDayRange(today), repo.range)
    }
    @Test fun `empty is distinct from absent local data`() = runTest(dispatcher) {
        val repo = Repository(); val vm = model(repo); runCurrent()
        repo.plans.value = cached(emptyList()); runCurrent()
        assertEquals(PlanBrowserMessage.EMPTY, vm.state.value.message)
        repo.plans.value = repo.plans.value.copy(hasLocalData = false); runCurrent()
        assertEquals(PlanBrowserMessage.NO_LOCAL_DATA, vm.state.value.message)
    }
    @Test fun `observation failure retains stale data and retry recovers`() = runTest(dispatcher) {
        val repo = Repository(); val vm = model(repo); runCurrent()
        repo.fail = true; repo.plans.value = repo.plans.value.copy(isStale = true); runCurrent()
        assertEquals(listOf(plan), vm.state.value.items)
        assertTrue(vm.state.value.observationFailed); assertTrue(vm.state.value.isStale)
        repo.fail = false; repo.plans.value = cached(listOf(plan)); vm.retryObservation(); runCurrent()
        assertFalse(vm.state.value.observationFailed); assertFalse(vm.state.value.isStale)
    }
    @Test fun `revocation removes details and cannot be retried or rebound`() = runTest(dispatcher) {
        val repo = Repository(); val vm = model(repo); runCurrent(); vm.selectPlan("plan")
        repo.meds.value = repo.meds.value.copy(access = AccessState.REVOKED); runCurrent()
        assertNull(vm.state.value.details); assertTrue(vm.state.value.items.isEmpty())
        repo.meds.value = cached(listOf(medication)); vm.retryObservation()
        vm.bind(repo, scope, "elder", today); runCurrent()
        assertEquals(PlanBrowserMessage.REVOKED, vm.state.value.message)
    }
    @Test fun `revocation is immediate while another collection never emits`() = runTest(dispatcher) {
        val repo = Repository(); val vm = model(repo); runCurrent()
        repo.silentPreview = true; vm.retryObservation(); runCurrent()
        repo.plans.value = repo.plans.value.copy(access = AccessState.REVOKED); runCurrent()
        assertEquals(PlanBrowserMessage.REVOKED, vm.state.value.message)
        assertTrue(vm.state.value.items.isEmpty())
    }
    @Test fun `switch clears old scope immediately and ignores previous repository updates`() = runTest(dispatcher) {
        val repo = Repository(); val vm = model(repo); runCurrent(); vm.selectPlan("plan")
        val next = Repository().also { it.plans.value = cached(emptyList()); it.meds.value = cached(emptyList()) }
        vm.bind(next, scope.copy(userId = "other"), "other-elder", today)
        assertTrue(vm.state.value.items.isEmpty()); assertNull(vm.state.value.details)
        repo.plans.value = cached(listOf(plan.copy(revision = 2))); runCurrent()
        assertTrue(vm.state.value.items.isEmpty())
        vm.clearContext(); runCurrent()
        assertEquals(PlanBrowserMessage.SELECT_ELDER, vm.state.value.message)
    }
    @Test fun `preview preserves repository instant and excludes mismatched revision`() = runTest(dispatcher) {
        val repo = Repository(); val vm = model(repo)
        val candidate = PlanPreview(OccurrenceKey("plan", "2024-03-04", "08:00"), 1, "Asia/Shanghai", 1709510400000)
        repo.previews.value = cached(listOf(candidate)); runCurrent()
        assertEquals(listOf(candidate), vm.state.value.candidates)
        repo.plans.value = cached(listOf(plan.copy(revision = 2))); runCurrent()
        assertTrue(vm.state.value.candidates.isEmpty())
    }
    @Test fun `mis scoped records and out of range preview fail without exposure`() = runTest(dispatcher) {
        val repo = Repository().also { it.plans.value = cached(listOf(plan.copy(elderId = "wrong"))) }
        val vm = model(repo); runCurrent()
        assertEquals(PlanBrowserMessage.UNAVAILABLE, vm.state.value.message)
        repo.plans.value = cached(listOf(plan))
        repo.previews.value = cached(listOf(PlanPreview(OccurrenceKey("plan", "2024-03-05", "08:00"), 1, "Asia/Shanghai", 1)))
        vm.retryObservation(); runCurrent()
        assertTrue(vm.state.value.candidates.isEmpty()); assertTrue(vm.state.value.observationFailed)
    }
}
