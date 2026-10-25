package com.group33.medicationreminder.caregiver.photo

import androidx.lifecycle.ViewModelStore
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.MedicationPhotoView
import java.io.ByteArrayInputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverPhotoViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val scope = RepositoryScope("test", "installation", "caregiver")
    private val photo = MedicationPhotoView("photo-a", "elder-a", "image/png", 1)
    private fun cached(photos: List<MedicationPhotoView> = listOf(photo), access: AccessState = AccessState.AUTHORIZED) =
        Cached(photos, true, 123L, false, access)
    private val handle = object : PhotoContent {
        override val contentType = "image/png"
        override fun openStream() = ByteArrayInputStream(byteArrayOf()) // Never decoded by this slice.
    }
    private inner class Repo : MedicationPhotoRepository {
        val cache = MutableStateFlow(cached())
        var failObservation = false
        var failAfterEmission = false
        var observations = 0
        val loads = mutableListOf<String>()
        var response: suspend () -> Outcome<PhotoContent> = { Outcome.Success(handle) }
        override fun observePhotos(elderId: String): Flow<Cached<List<MedicationPhotoView>>> = flow {
            observations++
            if (failObservation) error("private adapter diagnostic")
            cache.collect {
                emit(it)
                if (failAfterEmission) error("local observation failed after snapshot")
            }
        }
        override suspend fun loadContent(photoId: String): Outcome<PhotoContent> {
            loads += photoId
            return response()
        }
        override suspend fun refresh(elderId: String): Outcome<SyncSummary> = error("Not a server refresh")
        override suspend fun submit(source: PhotoSource, command: CommandContext): OnlineCommandResult<MedicationPhotoView> =
            error("Viewing must not submit")
    }
    private fun model(repo: Repo): CaregiverPhotoViewModel = CaregiverPhotoViewModel().also {
        store.put("photos", it)
        it.selectContext(repo, scope, "elder-a")
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun `loading metadata and empty cache do not claim viewed content`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = model(repo)
        assertTrue(vm.state.value.loading)
        runCurrent()
        assertEquals(listOf(photo), vm.state.value.photos)
        assertNull(vm.state.value.content)
        assertEquals(PhotoContentStatus.NONE, vm.state.value.contentStatus)
        assertEquals(123L, vm.state.value.lastSuccessfulSyncAtEpochMillis)
        repo.cache.value = cached(emptyList())
        runCurrent()
        assertTrue(vm.state.value.hasLocalData)
        assertTrue(vm.state.value.photos.isEmpty())
        assertTrue(repo.loads.isEmpty())
    }

    @Test fun `content failure retains selection and explicit retry loads same id`() = runTest(dispatcher) {
        val repo = Repo()
        val error = RepositoryError.Local(LocalFailureCode.OFFLINE)
        repo.response = { Outcome.Failure(error) }
        val vm = model(repo)
        runCurrent()
        vm.selectPhoto(photo.id)
        assertEquals(PhotoContentStatus.LOADING, vm.state.value.contentStatus)
        runCurrent()
        assertEquals(photo.id, vm.state.value.selectedPhotoId)
        assertEquals(error, vm.state.value.contentError)
        repo.response = { Outcome.Success(handle) }
        vm.retryContent()
        runCurrent()
        assertSame(handle, vm.state.value.content)
        assertEquals(PhotoContentStatus.AVAILABLE, vm.state.value.contentStatus)
        assertEquals(listOf(photo.id, photo.id), repo.loads)
    }

    @Test fun `duplicate clicks do not duplicate pending content requests`() = runTest(dispatcher) {
        val repo = Repo()
        lateinit var completion: Continuation<Outcome<PhotoContent>>
        repo.response = { suspendCoroutine { completion = it } }
        val vm = model(repo)
        runCurrent()
        vm.selectPhoto(photo.id)
        vm.selectPhoto(photo.id)
        runCurrent()
        assertEquals(listOf(photo.id), repo.loads)
        completion.resume(Outcome.Success(handle))
        runCurrent()
        assertSame(handle, vm.state.value.content)
    }

    @Test fun `late noncooperative result cannot cross account or elder switch`() = runTest(dispatcher) {
        val repo = Repo()
        lateinit var completion: Continuation<Outcome<PhotoContent>>
        repo.response = { suspendCoroutine { completion = it } }
        val vm = model(repo)
        runCurrent()
        vm.selectPhoto(photo.id)
        runCurrent()
        val next = Repo().apply { cache.value = cached(emptyList()) }
        vm.selectContext(next, scope.copy(userId = "other"), "elder-b")
        assertNull(vm.state.value.content)
        completion.resume(Outcome.Success(handle))
        runCurrent()
        assertEquals("elder-b", vm.state.value.elderId)
        assertTrue(vm.state.value.photos.isEmpty())
        assertNull(vm.state.value.content)
    }

    @Test fun `revocation clears content and stale authorized emissions cannot restore access`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = model(repo)
        runCurrent()
        vm.selectPhoto(photo.id)
        runCurrent()
        repo.cache.value = cached(access = AccessState.REVOKED)
        runCurrent()
        assertNull(vm.state.value.content)
        repo.cache.value = cached()
        runCurrent()
        vm.selectPhoto(photo.id)
        assertEquals(AccessState.REVOKED, vm.state.value.access)
        assertEquals(1, repo.loads.size)
    }

    @Test fun `observation failure after revocation preserves denial and blocks retry`() = runTest(dispatcher) {
        val repo = Repo().apply {
            cache.value = cached(access = AccessState.REVOKED)
            failAfterEmission = true
        }
        val vm = model(repo)
        runCurrent()
        assertEquals(AccessState.REVOKED, vm.state.value.access)
        assertNotNull(vm.state.value.observationError)
        assertTrue(vm.state.value.photos.isEmpty())
        assertNull(vm.state.value.content)
        vm.retryObservation()
        runCurrent()
        assertEquals(1, repo.observations)
        assertEquals(AccessState.REVOKED, vm.state.value.access)
        vm.selectContext(repo, scope, "elder-a")
        runCurrent()
        assertEquals(1, repo.observations)
        assertEquals(AccessState.REVOKED, vm.state.value.access)
    }

    @Test fun `unknown access and foreign metadata cannot request content`() = runTest(dispatcher) {
        val repo = Repo().apply { cache.value = cached(access = AccessState.UNKNOWN) }
        val vm = model(repo)
        runCurrent()
        vm.selectPhoto(photo.id)
        repo.cache.value = cached(listOf(photo.copy(elderId = "foreign")))
        runCurrent()
        vm.selectPhoto(photo.id)
        assertTrue(repo.loads.isEmpty())
        assertTrue(vm.state.value.photos.isEmpty())
        assertEquals(RepositoryError.Local(LocalFailureCode.INVALID_INPUT), vm.state.value.observationError)
    }

    @Test fun `failed observation retries locally without invoking refresh or upload`() = runTest(dispatcher) {
        val repo = Repo().apply { failObservation = true }
        val vm = model(repo)
        runCurrent()
        assertNotNull(vm.state.value.observationError)
        repo.failObservation = false
        vm.retryObservation()
        runCurrent()
        assertNull(vm.state.value.observationError)
        assertEquals(listOf(photo), vm.state.value.photos)
        assertEquals(2, repo.observations)
    }

    @Test fun `older photo result cannot replace a newer selection`() = runTest(dispatcher) {
        val repo = Repo().apply { cache.value = cached(listOf(photo, photo.copy(id = "photo-b"))) }
        lateinit var old: Continuation<Outcome<PhotoContent>>
        repo.response = { suspendCoroutine { old = it } }
        val vm = model(repo)
        runCurrent()
        vm.selectPhoto(photo.id)
        runCurrent()
        val failure = RepositoryError.Local(LocalFailureCode.OFFLINE)
        repo.response = { Outcome.Failure(failure) }
        vm.selectPhoto("photo-b")
        runCurrent()
        old.resume(Outcome.Success(handle))
        runCurrent()
        assertEquals("photo-b", vm.state.value.selectedPhotoId)
        assertNull(vm.state.value.content)
        assertEquals(failure, vm.state.value.contentError)
    }

    @Test fun `content exception maps to typed failure and absent cache is not an empty synced list`() = runTest(dispatcher) {
        val repo = Repo().apply { cache.value = cached().copy(hasLocalData = false, isStale = true) }
        val vm = model(repo)
        runCurrent()
        vm.selectPhoto(photo.id)
        assertFalse(vm.state.value.hasLocalData)
        assertTrue(vm.state.value.isStale)
        assertTrue(repo.loads.isEmpty())
        repo.cache.value = cached()
        repo.response = { error("private path or server detail") }
        runCurrent()
        vm.selectPhoto(photo.id)
        runCurrent()
        assertEquals(PhotoContentStatus.FAILED, vm.state.value.contentStatus)
        assertEquals(RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE), vm.state.value.contentError)
    }

    @Test fun `logout and removal of selected photo invalidate pending results`() = runTest(dispatcher) {
        for (logout in listOf(false, true)) {
            val repo = Repo()
            lateinit var completion: Continuation<Outcome<PhotoContent>>
            repo.response = { suspendCoroutine { completion = it } }
            val vm = model(repo)
            runCurrent()
            vm.selectPhoto(photo.id)
            runCurrent()
            if (logout) vm.clearContext() else repo.cache.value = cached(emptyList())
            runCurrent()
            completion.resume(Outcome.Success(handle))
            runCurrent()
            assertNull(vm.state.value.content)
            assertNull(vm.state.value.selectedPhotoId)
        }
    }
}
