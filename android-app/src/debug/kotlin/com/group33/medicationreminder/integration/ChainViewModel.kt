package com.group33.medicationreminder.integration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ViewedPhoto(val metadata: MedicationPhotoView, val bitmap: Bitmap,
                                val scope: RepositoryScope, val generation: Long, val elderId: String)
internal data class ChainUi(
    val session: SessionView = SessionView(AuthReadiness.RESTORING, null),
    val requests: List<PairingRequestView> = emptyList(), val relationships: List<CareRelationshipView> = emptyList(),
    val context: CaregiverContextState = CaregiverContextState.SignedOut,
    val photos: List<MedicationPhotoView> = emptyList(), val plans: List<ReminderPlanView> = emptyList(),
    val medications: List<MedicationView> = emptyList(), val viewed: ViewedPhoto? = null,
    val code: PairingCodeView? = null, val busy: Boolean = false, val message: Int = R.string.chain_ready,
    val stale: Boolean = false,
)

/** Screen logic consumes existing Repository interfaces. No UI-owned identity, HTTP or DAO. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChainViewModel(private val services: ChainServices) : ViewModel() {
    private val mutable = MutableStateFlow(ChainUi())
    val state = mutable.asStateFlow()
    private val selected = MutableStateFlow<String?>(null)
    private var collection: Job? = null
    private var photoJob: Job? = null
    private var actionJob: Job? = null
    private var account = services.trusted.value.let { it?.scope to it?.generation }

    init {
        viewModelScope.launch {
            combine(services.auth.observeSession(), services.trusted, services.pairing.observeRelationships(),
                services.pairing.observeRequests(), selected) { session, trusted, relationships, requests, choice ->
                val nextAccount = trusted?.scope to trusted?.generation
                if (nextAccount != account) {
                    account = nextAccount; selected.value = null; collection?.cancel(); photoJob?.cancel()
                    actionJob?.cancel()
                    mutable.value = ChainUi(session = session)
                }
                val safeRelationships = relationships.value.takeIf { relationships.access == AccessState.AUTHORIZED } ?: emptyList()
                // Auto-select only a single server-authorized relationship; multiple Elders require an explicit choice.
                val active = safeRelationships.filter { it.status == CareRelationshipStatus.ACTIVE && it.caregiverId == session.user?.id }
                val selection = choice ?: active.singleOrNull()?.id
                val context = CaregiverContextResolver.resolve(session, trusted, relationships, selection)
                val old = mutable.value.context as? CaregiverContextState.Active
                val now = context as? CaregiverContextState.Active
                if (old?.context != now?.context) {
                    photoJob?.cancel()
                    mutable.update { it.copy(viewed = null, photos = emptyList(), plans = emptyList(), medications = emptyList()) }
                }
                mutable.update { it.copy(session = session, context = context,
                    relationships = safeRelationships,
                    requests = requests.value.takeIf { requests.access == AccessState.AUTHORIZED } ?: emptyList()) }
                Triple(session, trusted, now?.context?.elderId)
            }.distinctUntilChanged().collectLatest { (session, trusted, caregiverElder) ->
                collection?.cancel()
                val elder = if (session.user?.role == UserRole.ELDER) session.user.id else caregiverElder
                if (elder != null && trusted != null) {
                    collection = viewModelScope.launch {
                        combine(services.photos.observePhotos(elder), services.plans.observePlans(elder),
                            services.plans.observeMedications(elder)) { photos, plans, meds -> Triple(photos, plans, meds) }
                            .collect { (photos, plans, meds) ->
                                if (services.trusted.value != trusted) return@collect
                                mutable.update { it.copy(
                                    photos = photos.value.takeIf { photos.access == AccessState.AUTHORIZED } ?: emptyList(),
                                    plans = plans.value.takeIf { plans.access == AccessState.AUTHORIZED } ?: emptyList(),
                                    medications = meds.value.takeIf { meds.access == AccessState.AUTHORIZED } ?: emptyList(),
                                    stale = photos.isStale || plans.isStale || meds.isStale,
                                    viewed = it.viewed.takeIf { photos.access == AccessState.AUTHORIZED },
                                ) }
                            }
                    }
                    refreshBusiness(elder)
                }
            }
        }
        viewModelScope.launch {
            services.auth.observeSession().distinctUntilChanged().collectLatest {
                if (it.readiness == AuthReadiness.READY) services.pairing.refresh()
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val trusted = services.trusted.value
        mutable.update { it.copy(busy = true) }
        actionJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (trusted == services.trusted.value) mutable.update { it.copy(message = R.string.chain_failed) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun login(role: UserRole, name: String) = action {
        val result = services.auth.login(role, name)
        mutable.update { it.copy(message = if (result is Outcome.Success) R.string.chain_signed_in else
            if ((result as? Outcome.Failure)?.error.let { e -> (e as? RepositoryError.Remote)?.error?.code } == "INSTALLATION_ROLE_CONFLICT")
                R.string.chain_fixed_role else R.string.chain_failed) }
    }
    fun logout() {
        actionJob?.cancel(); photoJob?.cancel(); collection?.cancel()
        viewModelScope.launch { services.signOut(); mutable.value = ChainUi() }
    }
    fun select(relationshipId: String) { selected.value = relationshipId }
    fun refresh() = action {
        val result = services.pairing.refresh()
        if (result is Outcome.Failure) { mutable.update { it.copy(message = R.string.chain_failed, stale = true) }; return@action }
        val user = mutable.value.session.user
        val elder = if (user?.role == UserRole.ELDER) user.id else (mutable.value.context as? CaregiverContextState.Active)?.context?.elderId
        if (elder != null) refreshBusiness(elder)
    }
    private suspend fun refreshBusiness(elder: String) {
        val result = services.photos.refresh(elder)
        mutable.update { it.copy(message = if (result is Outcome.Success) R.string.chain_refreshed else R.string.chain_failed,
            stale = result is Outcome.Failure) }
    }
    fun issue() = action {
        val command = (services.commands.begin(OnlineCommandKind.ISSUE_PAIRING_CODE, null) as? Outcome.Success)?.value ?: return@action
        when (val result = services.pairing.issueCode(command)) {
            is OnlineCommandResult.Confirmed -> mutable.update { it.copy(code = result.value, message = R.string.chain_code_ready) }
            else -> mutable.update { it.copy(message = R.string.chain_pending) }
        }
    }
    fun request(code: String) = action {
        val command = (services.commands.begin(OnlineCommandKind.REQUEST_PAIRING, null) as? Outcome.Success)?.value ?: return@action
        val result = services.pairing.requestPairing(code, command)
        mutable.update { it.copy(message = if (result is OnlineCommandResult.Confirmed) R.string.chain_waiting else R.string.chain_failed) }
    }
    fun respond(id: String, accept: Boolean) = action {
        val command = (services.commands.begin(OnlineCommandKind.RESPOND_PAIRING, id) as? Outcome.Success)?.value ?: return@action
        val result = services.pairing.respond(id, accept, command)
        mutable.update { it.copy(message = if (result is OnlineCommandResult.Confirmed) R.string.chain_refreshed else R.string.chain_pending) }
    }
    fun revoke(id: String) = action {
        val command = (services.commands.begin(OnlineCommandKind.REVOKE_RELATIONSHIP, id) as? Outcome.Success)?.value ?: return@action
        services.pairing.revoke(id, command)
    }
    fun recover() = action {
        services.commands.observeCommands().first().filter {
            it.state == OnlineCommandState.AWAITING_CONFIRMATION || it.state == OnlineCommandState.CONFIRMED && it.cacheState == CacheState.REFRESH_REQUIRED
        }.forEach { services.commands.resume(it.operationId) }
        services.pairing.refresh()
        mutable.update { it.copy(message = R.string.chain_refreshed) }
    }
    fun viewPhoto(id: String) {
        photoJob?.cancel()
        val context = (mutable.value.context as? CaregiverContextState.Active)?.context ?: return
        val metadata = mutable.value.photos.firstOrNull { it.id == id && it.elderId == context.elderId } ?: return
        mutable.update { it.copy(viewed = null) }
        photoJob = viewModelScope.launch {
            try {
            val content = (services.photos.loadContent(id) as? Outcome.Success)?.value ?: return@launch
            val bitmap = withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                content.openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 1280 || bounds.outHeight / inSampleSize > 1280) inSampleSize *= 2
                }
                content.openStream().use { BitmapFactory.decodeStream(it, null, options) }
            } ?: return@launch
            val current = (mutable.value.context as? CaregiverContextState.Active)?.context
            if (current != null && context.isCurrentFor(current) && services.trusted.value ==
                TrustedSessionContext(context.scope, context.sessionGeneration)) mutable.update {
                it.copy(viewed = ViewedPhoto(metadata, bitmap, context.scope, context.sessionGeneration, context.elderId))
            } else bitmap.recycle()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutable.update { it.copy(viewed = null, message = R.string.chain_failed) } }
        }
    }
}
