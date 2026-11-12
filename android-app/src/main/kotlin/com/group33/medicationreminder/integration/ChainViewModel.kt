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
internal enum class SignOutStatus { IDLE, CLEARING, FAILED, COMPLETE }

internal data class ChainUi(
    val session: SessionView = SessionView(AuthReadiness.RESTORING, null),
    val requests: List<PairingRequestView> = emptyList(), val relationships: List<CareRelationshipView> = emptyList(),
    val context: CaregiverContextState = CaregiverContextState.SignedOut,
    val photos: List<MedicationPhotoView> = emptyList(), val plans: List<ReminderPlanView> = emptyList(),
    val medications: List<MedicationView> = emptyList(), val viewed: ViewedPhoto? = null,
    val signOut: SignOutStatus = SignOutStatus.IDLE,
    val hasPendingPairing: Boolean = false,
    val failedPhotoId: String? = null, val plansKnown: Boolean = false,
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
            val pendingPairing = services.commands.observeCommands().map { records ->
                records.filter { it.isPairingRecovery() }.map { it.scope }.toSet()
            }.distinctUntilChanged().onStart { emit(emptySet()) }.catch { emit(emptySet()) }
            combine(services.auth.observeSession(), services.trusted, services.pairing.observeRelationships(),
                services.pairing.observeRequests(), combine(selected, pendingPairing, ::Pair)) {
                session, trusted, relationships, requests, (choice, pendingScopes) ->
                val nextAccount = trusted?.scope to trusted?.generation
                if (nextAccount != account) {
                    account = nextAccount; selected.value = null; collection?.cancel(); photoJob?.cancel()
                    actionJob?.cancel()
                    mutable.update {
                        ChainUi(session = session, signOut = if (trusted == null) it.signOut else SignOutStatus.IDLE)
                    }
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
                    mutable.update { it.copy(viewed = null, failedPhotoId = null, photos = emptyList(), plans = emptyList(),
                        plansKnown = false, medications = emptyList()) }
                }
                mutable.update { it.copy(session = session, context = context,
                    hasPendingPairing = trusted != null && trusted.scope in pendingScopes,
                    relationships = safeRelationships,
                    requests = requests.value.takeIf { requests.access == AccessState.AUTHORIZED } ?: emptyList()) }
                Triple(session, trusted, now?.context)
            }.distinctUntilChanged().collectLatest { (session, trusted, caregiverContext) ->
                collection?.cancel()
                val elder = if (session.user?.role == UserRole.ELDER) session.user.id else caregiverContext?.elderId
                if (elder != null && trusted != null) {
                    collection = viewModelScope.launch {
                        combine(services.photos.observePhotos(elder), services.plans.observePlans(elder),
                            services.plans.observeMedications(elder)) { photos, plans, meds -> Triple(photos, plans, meds) }
                            .collect { (photos, plans, meds) ->
                                mutable.update {
                                    if (!isCurrentCollection(it, trusted, elder, caregiverContext)) return@update it
                                    it.copy(
                                    photos = photos.value.takeIf { photos.access == AccessState.AUTHORIZED } ?: emptyList(),
                                    plans = plans.value.takeIf { plans.access == AccessState.AUTHORIZED } ?: emptyList(),
                                    medications = meds.value.takeIf { meds.access == AccessState.AUTHORIZED } ?: emptyList(),
                                    plansKnown = plans.access == AccessState.AUTHORIZED && plans.hasLocalData && !plans.isStale,
                                    failedPhotoId = it.failedPhotoId?.takeIf { id -> photos.access == AccessState.AUTHORIZED &&
                                        photos.value.any { photo -> photo.id == id } },
                                    stale = photos.isStale || plans.isStale || meds.isStale,
                                    viewed = it.viewed.takeIf { photos.access == AccessState.AUTHORIZED },
                                ) }
                            }
                    }
                    refreshBusiness(elder, trusted, caregiverContext)
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
        if (mutable.value.busy || mutable.value.signOut != SignOutStatus.IDLE) return
        val trusted = services.trusted.value
        mutable.update { it.copy(busy = true) }
        actionJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (trusted == services.trusted.value) mutable.update { it.copy(message = R.string.chain_failed) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun logout() {
        if (mutable.value.signOut == SignOutStatus.CLEARING || mutable.value.signOut == SignOutStatus.COMPLETE) return
        actionJob?.cancel(); photoJob?.cancel(); collection?.cancel()
        mutable.value = ChainUi(signOut = SignOutStatus.CLEARING)
        viewModelScope.launch {
            val status = when (services.signOut()) {
                is Outcome.Success -> SignOutStatus.COMPLETE
                is Outcome.Failure -> SignOutStatus.FAILED
            }
            mutable.update { it.copy(signOut = status) }
        }
    }
    fun select(relationshipId: String) { selected.value = relationshipId }
    fun refresh(reportStatus: Boolean = true) = action {
        val result = services.pairing.refresh()
        if (result is Outcome.Failure) {
            mutable.update { it.copy(message = if (reportStatus) R.string.chain_failed else it.message, stale = true) }
            return@action
        }
        val user = mutable.value.session.user
        val elder = if (user?.role == UserRole.ELDER) user.id else (mutable.value.context as? CaregiverContextState.Active)?.context?.elderId
        val trusted = services.trusted.value
        if (elder != null && trusted != null) refreshBusiness(elder, trusted,
            (mutable.value.context as? CaregiverContextState.Active)?.context, reportStatus)
    }
    private fun isCurrentCollection(state: ChainUi, trusted: TrustedSessionContext, elder: String,
                                    caregiverContext: CaregiverElderContext?): Boolean {
        if (state.signOut != SignOutStatus.IDLE || services.trusted.value != trusted || state.session.readiness != AuthReadiness.READY ||
            state.session.user?.id != trusted.scope.userId) return false
        return if (state.session.user.role == UserRole.ELDER) state.session.user.id == elder
        else caregiverContext != null && (state.context as? CaregiverContextState.Active)?.context
            ?.let(caregiverContext::isCurrentFor) == true
    }
    private suspend fun refreshBusiness(elder: String, trusted: TrustedSessionContext,
                                        caregiverContext: CaregiverElderContext?, reportStatus: Boolean = false) {
        val result = services.photos.refresh(elder)
        mutable.update {
            if (!isCurrentCollection(it, trusted, elder, caregiverContext)) return@update it
            val message = if (result is Outcome.Success) R.string.chain_refreshed else R.string.chain_failed
            it.copy(message = if (reportStatus) message else it.message, stale = result is Outcome.Failure)
        }
    }
    private fun pairingCommand(
        kind: OnlineCommandKind,
        targetId: String?,
        confirmedMessage: Int,
        submit: suspend (CommandContext) -> OnlineCommandResult<*>,
    ) = action {
        val trusted = services.trusted.value ?: return@action
        val command = services.commands.begin(kind, targetId)
        val result = when (command) {
            is Outcome.Success -> submit(command.value)
            is Outcome.Failure -> null
        }
        currentCoroutineContext().ensureActive()
        if (trusted != services.trusted.value) return@action
        val message = when (result) {
            is OnlineCommandResult.Confirmed -> if (result.cacheState == CacheState.REFRESH_REQUIRED)
                R.string.chain_confirmed_refresh_required else confirmedMessage
            is OnlineCommandResult.AwaitingConfirmation -> R.string.chain_pending
            is OnlineCommandResult.Rejected -> R.string.chain_rejected
            is OnlineCommandResult.NotSubmitted, null -> R.string.chain_not_submitted
        }
        mutable.update {
            it.copy(code = (result as? OnlineCommandResult.Confirmed)?.value as? PairingCodeView ?: it.code,
                message = message)
        }
    }

    fun issue() = pairingCommand(OnlineCommandKind.ISSUE_PAIRING_CODE, null, R.string.chain_code_ready) {
        services.pairing.issueCode(it)
    }

    fun request(code: String) = pairingCommand(OnlineCommandKind.REQUEST_PAIRING, null, R.string.chain_waiting) {
        services.pairing.requestPairing(code, it)
    }

    fun respond(id: String, accept: Boolean) = pairingCommand(OnlineCommandKind.RESPOND_PAIRING, id,
        if (accept) R.string.chain_pairing_accepted else R.string.chain_pairing_declined) {
        services.pairing.respond(id, accept, it)
    }

    fun revoke(id: String) = pairingCommand(OnlineCommandKind.REVOKE_RELATIONSHIP, id, R.string.chain_revoked) {
        services.pairing.revoke(id, it)
    }

    private fun OnlineCommandRecord.needsRecovery() =
        state == OnlineCommandState.AWAITING_CONFIRMATION ||
            state == OnlineCommandState.CONFIRMED && cacheState == CacheState.REFRESH_REQUIRED

    private fun OnlineCommandRecord.isPairingRecovery() = needsRecovery() && commandKind in setOf(
        OnlineCommandKind.ISSUE_PAIRING_CODE, OnlineCommandKind.RESPOND_PAIRING,
        OnlineCommandKind.REVOKE_RELATIONSHIP,
    )

    fun recover() = action {
        val trusted = services.trusted.value ?: return@action
        val elder = mutable.value.session.user?.role == UserRole.ELDER
        var pending = false
        var rejected = false
        var recoveredCode: PairingCodeView? = null
        services.commands.observeCommands().first().filter {
            it.scope == trusted.scope && (if (elder) it.isPairingRecovery() else it.needsRecovery())
        }.forEach { record ->
            currentCoroutineContext().ensureActive()
            if (trusted != services.trusted.value) return@action
            val result = if (record.commandKind == OnlineCommandKind.ISSUE_PAIRING_CODE) {
                when (val recovery = services.resumePairingCode(record.operationId)) {
                    is Outcome.Failure -> recovery
                    is Outcome.Success -> {
                        recoveredCode = recovery.value.code ?: recoveredCode
                        Outcome.Success(recovery.value.record)
                    }
                }
            } else services.commands.resume(record.operationId)
            if (result is Outcome.Success && result.value.state in
                setOf(OnlineCommandState.REJECTED, OnlineCommandState.NOT_SUBMITTED)) rejected = true
            else if (result !is Outcome.Success || result.value.state != OnlineCommandState.CONFIRMED ||
                result.value.cacheState != CacheState.APPLIED) pending = true
        }
        val refreshed = services.pairing.refresh() is Outcome.Success
        currentCoroutineContext().ensureActive()
        if (trusted != services.trusted.value) return@action
        val code = recoveredCode
        val validCode = code?.takeIf { it.expiresAtEpochMillis > System.currentTimeMillis() }
        mutable.update { it.copy(code = if (code == null) it.code else validCode, message = when {
            !refreshed || rejected -> R.string.chain_failed
            pending -> R.string.chain_pending
            validCode != null -> R.string.chain_code_ready
            code != null -> R.string.chain_code_expired
            else -> R.string.chain_refreshed
        }, stale = it.stale || !refreshed) }
    }
    fun viewPhoto(id: String) {
        photoJob?.cancel()
        val context = (mutable.value.context as? CaregiverContextState.Active)?.context ?: return
        val metadata = mutable.value.photos.firstOrNull { it.id == id && it.elderId == context.elderId } ?: return
        val trusted = TrustedSessionContext(context.scope, context.sessionGeneration)
        mutable.update { it.copy(viewed = null, failedPhotoId = null) }
        photoJob = viewModelScope.launch {
            val bitmap = try {
                when (val result = services.photos.loadContent(id)) {
                    is Outcome.Failure -> null
                    is Outcome.Success -> withContext(Dispatchers.IO) {
                        val content = result.value
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        content.openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                        require(bounds.outWidth > 0 && bounds.outHeight > 0)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            while (bounds.outWidth / inSampleSize > 1280 || bounds.outHeight / inSampleSize > 1280) inSampleSize *= 2
                        }
                        content.openStream().use { BitmapFactory.decodeStream(it, null, options) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!isActive || !isCurrentCollection(mutable.value, trusted, context.elderId, context) ||
                mutable.value.photos.none { it.id == id }) {
                bitmap?.recycle()
                return@launch
            }
            mutable.update {
                if (bitmap == null) it.copy(failedPhotoId = id)
                else it.copy(viewed = ViewedPhoto(metadata, bitmap, context.scope, context.sessionGeneration, context.elderId))
            }
        }
    }
}
