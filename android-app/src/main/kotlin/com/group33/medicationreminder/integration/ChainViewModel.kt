package com.group33.medicationreminder.integration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import com.group33.medicationreminder.caregiver.photo.*
import com.group33.medicationreminder.caregiver.plan.*
import java.time.Clock
import java.time.LocalDate
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
    val photo: CaregiverPhotoState = CaregiverPhotoState(),
    val browser: PlanBrowserState = PlanBrowserState(), val viewed: ViewedPhoto? = null,
    val photoDecodeFailed: Boolean = false, val editorPhotoId: String? = null,
    val signOut: SignOutStatus = SignOutStatus.IDLE,
    val hasPendingPairing: Boolean = false,
    val code: PairingCodeView? = null, val busy: Boolean = false, val message: Int = R.string.chain_ready,
)

/** Screen logic consumes existing Repository interfaces. No UI-owned identity, HTTP or DAO. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChainViewModel(private val services: ChainServices,
                              private val clock: Clock = Clock.systemDefaultZone(),
                              private val decode: suspend (PhotoContent) -> Bitmap = ::decodePhotoContent) : ViewModel() {
    private val mutable = MutableStateFlow(ChainUi())
    val state = mutable.asStateFlow()
    private val selected = MutableStateFlow<String?>(null)
    private data class Target(val trusted: TrustedSessionContext, val elderId: String,
                              val caregiver: CaregiverElderContext?)
    private data class Features(val photos: CaregiverPhotoViewModel, val browser: PlanBrowserViewModel)
    // The host retains original feature ViewModels, not parallel collection/selection state machines.
    private val featureStore = ViewModelStore()
    private val featureProvider = ViewModelProvider(featureStore, ViewModelProvider.NewInstanceFactory())
    private var target: Target? = null
    private var lastCaregiverTarget: Target? = null
    private var features: Features? = null
    private var collection: Job? = null
    private var refreshJob: Job? = null
    private var decodedContent: PhotoContent? = null
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
                    lastCaregiverTarget = null
                    bindFeatures(null)
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
                    mutable.update { it.copy(viewed = null, photo = CaregiverPhotoState(), browser = PlanBrowserState(), photoDecodeFailed = false, editorPhotoId = null) }
                }
                mutable.update { it.copy(session = session, context = context,
                    hasPendingPairing = trusted != null && trusted.scope in pendingScopes,
                    relationships = safeRelationships,
                    requests = requests.value.takeIf { requests.access == AccessState.AUTHORIZED } ?: emptyList()) }
                Triple(session, trusted, now?.context)
            }.distinctUntilChanged().collect { (session, trusted, caregiverContext) ->
                val elder = if (session.user?.role == UserRole.ELDER) session.user.id else caregiverContext?.elderId
                bindFeatures(if (session.readiness == AuthReadiness.READY && elder != null && trusted != null)
                    Target(trusted, elder, caregiverContext) else null)
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
        actionJob?.cancel()
        bindFeatures(null)
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
            if (reportStatus) mutable.update { it.copy(message = R.string.chain_failed) }
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
        if (state.signOut != SignOutStatus.IDLE || services.trusted.value != trusted ||
            state.session.readiness != AuthReadiness.READY ||
            state.session.user?.id != trusted.scope.userId) return false
        return if (state.session.user.role == UserRole.ELDER) state.session.user.id == elder
        else caregiverContext != null && (state.context as? CaregiverContextState.Active)?.context
            ?.let(caregiverContext::isCurrentFor) == true
    }
    private suspend fun refreshBusiness(elder: String, trusted: TrustedSessionContext,
                                        caregiverContext: CaregiverElderContext?, reportStatus: Boolean = false) {
        val today = LocalDate.now(clock)
        if (isCurrentCollection(mutable.value, trusted, elder, caregiverContext) &&
            features?.browser?.state?.value?.range != PlanBrowserViewModel.sevenDayRange(today)) {
            features?.browser?.bind(services.plans, trusted.scope, elder, today)
        }
        val result = services.photos.refresh(elder)
        if (isCurrentCollection(mutable.value, trusted, elder, caregiverContext)) {
            if (features?.photos?.state?.value?.observationError != null) features?.photos?.retryObservation()
            if (features?.browser?.state?.value?.observationFailed == true) features?.browser?.retryObservation()
        }
        if (reportStatus) mutable.update {
            if (!isCurrentCollection(it, trusted, elder, caregiverContext)) return@update it
            it.copy(message = if (result is Outcome.Success) R.string.chain_refreshed else R.string.chain_failed)
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
        }) }
    }
    private fun bindFeatures(next: Target?) {
        if (target == next) return
        val previous = target ?: lastCaregiverTarget
        collection?.cancel(); refreshJob?.cancel(); photoJob?.cancel()
        features?.photos?.clearContext(); features?.browser?.clearContext()
        target = next; features = null; decodedContent = null
        if (next?.caregiver != null) lastCaregiverTarget = next
        mutable.update { it.copy(photo = CaregiverPhotoState(), browser = PlanBrowserState(),
            viewed = null, photoDecodeFailed = false, editorPhotoId = null) }
        if (next == null) return
        // Relationship/session identity is part of retention: a newly accepted relationship may
        // legitimately restore access, while a revoked original feature instance stays latched.
        val key = listOf(next.trusted.scope.environmentId, next.trusted.scope.installationId,
            next.trusted.scope.userId, next.trusted.generation.toString(), next.elderId,
            next.caregiver?.relationshipId.orEmpty()).joinToString("") { "${it.length}:$it" }
        val bound = Features(featureProvider["photos:$key", CaregiverPhotoViewModel::class.java],
            featureProvider["browser:$key", PlanBrowserViewModel::class.java])
        features = bound
        val replacementRelationship = previous?.caregiver?.let { old ->
            next.caregiver?.let { current -> old.elderId == current.elderId &&
                old.relationshipId != current.relationshipId && old.scope == current.scope &&
                old.sessionGeneration == current.sessionGeneration
            } == true
        } == true
        bound.photos.selectContext(services.photos, next.trusted.scope, next.elderId, replacementRelationship)
        bound.browser.bind(services.plans, next.trusted.scope, next.elderId, LocalDate.now(clock), replacementRelationship)
        collection = viewModelScope.launch {
            combine(bound.photos.state, bound.browser.state) { photo, browser -> photo to browser }
                .collect { (photo, browser) ->
                    if (target != next || !isCurrentCollection(mutable.value, next.trusted, next.elderId, next.caregiver)) return@collect
                    mutable.update { it.copy(photo = photo, browser = browser) }
                    val content = photo.content.takeIf { photo.contentStatus == PhotoContentStatus.AVAILABLE }
                    if (content !== decodedContent) {
                        decodedContent = content; photoJob?.cancel()
                        mutable.update { it.copy(viewed = null, photoDecodeFailed = false, editorPhotoId = null) }
                        val metadata = photo.photos.firstOrNull { it.id == photo.selectedPhotoId }
                        if (content != null && metadata != null && next.caregiver != null)
                            decodePhoto(next, bound, metadata, content)
                    }
                }
        }
        refreshJob = viewModelScope.launch { refreshBusiness(next.elderId, next.trusted, next.caregiver) }
    }

    fun viewPhoto(id: String) {
        mutable.update { it.copy(editorPhotoId = null) }
        if (mutable.value.context is CaregiverContextState.Active) features?.photos?.selectPhoto(id)
    }
    fun createPlanForViewedPhoto() {
        val viewed = mutable.value.viewed ?: return
        if (mutable.value.context is CaregiverContextState.Active)
            mutable.update { it.copy(editorPhotoId = viewed.metadata.id) }
    }
    fun retryPhotoContent() { features?.photos?.retryContent() }
    fun retryPhotoDecode() {
        if (!mutable.value.photoDecodeFailed) return
        val current = target ?: return
        val bound = features ?: return
        val photo = bound.photos.state.value
        val content = photo.content.takeIf { photo.contentStatus == PhotoContentStatus.AVAILABLE } ?: return
        val metadata = photo.photos.firstOrNull { it.id == photo.selectedPhotoId } ?: return
        if (current.caregiver == null || !isCurrentCollection(mutable.value, current.trusted,
                current.elderId, current.caregiver)) return
        mutable.update { it.copy(photoDecodeFailed = false) }
        photoJob?.cancel()
        decodePhoto(current, bound, metadata, content)
    }
    fun retryPhotoObservation() { features?.photos?.retryObservation() }
    fun retryPlanObservation() { features?.browser?.retryObservation() }
    fun selectPlan(id: String?) { features?.browser?.selectPlan(id) }

    /** #55 owns content retrieval. This bridge only decodes its actual private bytes. */
    private fun decodePhoto(next: Target, bound: Features, metadata: MedicationPhotoView, content: PhotoContent) {
        photoJob = viewModelScope.launch {
            try {
                val bitmap = decode(content)
                if (isActive && target == next && decodedContent === content &&
                    bound.photos.state.value.selectedPhotoId == metadata.id &&
                    isCurrentCollection(mutable.value, next.trusted, next.elderId, next.caregiver)) {
                    mutable.update { it.copy(viewed = ViewedPhoto(metadata, bitmap, next.trusted.scope,
                        next.trusted.generation, next.elderId)) }
                } else bitmap.recycle()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (isActive && target == next && decodedContent === content &&
                    bound.photos.state.value.selectedPhotoId == metadata.id &&
                    isCurrentCollection(mutable.value, next.trusted, next.elderId, next.caregiver)) {
                    mutable.update { it.copy(viewed = null, photoDecodeFailed = true) }
                }
            }
        }
    }

    override fun onCleared() {
        bindFeatures(null)
        featureStore.clear()
    }
}

/** Bounded private-byte decoding stays separate from the original feature's retrieval state. */
private suspend fun decodePhotoContent(content: PhotoContent): Bitmap = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    content.openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (bounds.outWidth / inSampleSize > 1280 || bounds.outHeight / inSampleSize > 1280) inSampleSize *= 2
    }
    content.openStream().use { BitmapFactory.decodeStream(it, null, options) } ?: error("Invalid photo")
}
