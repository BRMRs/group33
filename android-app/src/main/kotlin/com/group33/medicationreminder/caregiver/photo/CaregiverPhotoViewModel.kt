package com.group33.medicationreminder.caregiver.photo

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.MedicationPhotoView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PhotoContentStatus { NONE, LOADING, AVAILABLE, FAILED }

data class CaregiverPhotoState(
    val scope: RepositoryScope? = null,
    val elderId: String? = null,
    val photos: List<MedicationPhotoView> = emptyList(),
    val access: AccessState = AccessState.UNKNOWN,
    val loading: Boolean = false,
    val hasLocalData: Boolean = false,
    val isStale: Boolean = true,
    val lastSuccessfulSyncAtEpochMillis: Long? = null,
    val observationError: RepositoryError? = null,
    val selectedPhotoId: String? = null,
    val contentStatus: PhotoContentStatus = PhotoContentStatus.NONE,
    val content: PhotoContent? = null,
    val contentError: RepositoryError? = null,
)

/** Local caller-state slice only. The trusted host supplies a scope-bound repository.
 * AVAILABLE means a content handle, not successfully decoded/viewed bytes or plan readiness.
 * The eventual renderer owns/closes each stream and must report its own decode failures.
 */
class CaregiverPhotoViewModel : ViewModel() {
    private data class Selection(val repository: MedicationPhotoRepository, val scope: RepositoryScope, val elderId: String)
    private val mutableState = MutableStateFlow(CaregiverPhotoState())
    val state = mutableState.asStateFlow()
    private var selection: Selection? = null
    private var generation = Any()
    private var contentGeneration = Any()
    private var observation: Job? = null
    private var load: Job? = null
    private val revoked = mutableSetOf<Pair<RepositoryScope, String>>()

    @MainThread
    fun selectContext(repository: MedicationPhotoRepository, scope: RepositoryScope, elderId: String) {
        require(elderId.isNotBlank())
        clearContext()
        selection = Selection(repository, scope, elderId)
        val denied = (scope to elderId) in revoked
        mutableState.value = CaregiverPhotoState(scope, elderId,
            access = if (denied) AccessState.REVOKED else AccessState.UNKNOWN, loading = !denied)
        if (!denied) observe()
    }

    @MainThread
    fun clearContext() {
        generation = Any()
        observation?.cancel()
        invalidateContent()
        selection = null
        mutableState.value = CaregiverPhotoState()
    }

    /** Reopens failed local observation; does not claim a server refresh. */
    @MainThread
    fun retryObservation() {
        val current = selection ?: return
        if ((current.scope to current.elderId) in revoked ||
            state.value.observationError == null || state.value.access == AccessState.REVOKED) return
        generation = Any()
        observation?.cancel()
        mutableState.value = state.value.copy(loading = true, observationError = null)
        observe()
    }

    private fun observe() {
        val current = selection ?: return
        val token = generation
        observation = viewModelScope.launch {
            try {
                current.repository.observePhotos(current.elderId).collect { cached ->
                    if (token !== generation) return@collect
                    if (cached.access == AccessState.REVOKED) revoked += current.scope to current.elderId
                    val access = if ((current.scope to current.elderId) in revoked) AccessState.REVOKED else cached.access
                    val invalid = cached.value.any { it.elderId != current.elderId || it.id.isBlank() } ||
                        cached.value.map { it.id }.distinct().size != cached.value.size
                    val photos = if (access == AccessState.AUTHORIZED && cached.hasLocalData && !invalid) cached.value.toList() else emptyList()
                    val old = state.value
                    val retain = old.selectedPhotoId != null && photos.any { it.id == old.selectedPhotoId }
                    if (!retain) invalidateContent()
                    mutableState.value = old.copy(photos = photos, access = access, loading = false,
                        hasLocalData = cached.hasLocalData && access == AccessState.AUTHORIZED && !invalid,
                        isStale = cached.isStale, lastSuccessfulSyncAtEpochMillis = cached.lastSuccessfulSyncAtEpochMillis,
                        observationError = if (invalid) RepositoryError.Local(LocalFailureCode.INVALID_INPUT) else null,
                        selectedPhotoId = if (retain) old.selectedPhotoId else null,
                        contentStatus = if (retain) old.contentStatus else PhotoContentStatus.NONE,
                        content = if (retain) old.content else null, contentError = if (retain) old.contentError else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (token === generation) {
                    invalidateContent()
                    // A storage/observation failure cannot erase an established revocation.
                    val access = if ((current.scope to current.elderId) in revoked) AccessState.REVOKED else AccessState.UNKNOWN
                    mutableState.value = state.value.copy(loading = false, access = access,
                        photos = emptyList(), hasLocalData = false, selectedPhotoId = null,
                        content = null, contentStatus = PhotoContentStatus.NONE, contentError = null,
                        observationError = RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE))
                }
            }
        }
    }

    @MainThread
    fun selectPhoto(photoId: String) {
        val current = selection ?: return
        if (state.value.access != AccessState.AUTHORIZED || state.value.photos.none { it.id == photoId }) return
        if (state.value.selectedPhotoId == photoId && state.value.contentStatus in
            setOf(PhotoContentStatus.LOADING, PhotoContentStatus.AVAILABLE)) return
        invalidateContent()
        val token = contentGeneration
        mutableState.value = state.value.copy(selectedPhotoId = photoId, contentStatus = PhotoContentStatus.LOADING,
            content = null, contentError = null)
        load = viewModelScope.launch {
            val result = try { current.repository.loadContent(photoId)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { Outcome.Failure(RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE)) }
            if (token !== contentGeneration) return@launch
            mutableState.value = when (result) {
                is Outcome.Success -> state.value.copy(contentStatus = PhotoContentStatus.AVAILABLE, content = result.value)
                is Outcome.Failure -> state.value.copy(contentStatus = PhotoContentStatus.FAILED, contentError = result.error)
            }
        }
    }

    @MainThread
    fun retryContent() {
        if (state.value.contentStatus == PhotoContentStatus.FAILED) state.value.selectedPhotoId?.let(::selectPhoto)
    }

    private fun invalidateContent() {
        contentGeneration = Any()
        load?.cancel()
    }

    override fun onCleared() { clearContext() }
}
