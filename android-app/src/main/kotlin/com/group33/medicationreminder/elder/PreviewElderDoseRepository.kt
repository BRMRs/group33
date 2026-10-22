package com.group33.medicationreminder.elder

import com.group33.medicationreminder.core.data.contract.Cached
import com.group33.medicationreminder.core.data.contract.DoseEventRepository
import com.group33.medicationreminder.core.data.contract.DoseEventState
import com.group33.medicationreminder.core.data.contract.LocalActionClassification
import com.group33.medicationreminder.core.data.contract.LocalActionReceipt
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.medicationreminder.core.data.contract.RetryQueued
import com.group33.medicationreminder.core.data.contract.SyncState
import com.group33.shared.contract.DoseActionKind
import com.group33.shared.contract.OccurrenceKey
import com.group33.shared.model.DoseStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-process preview recorder for Elder Taken / Remind later UI (#14 boundary).
 * Success means local preview state only — not Room atomic write or server acceptance.
 */
class PreviewElderDoseRepository(
    private val failNextRecord: () -> Boolean = { false },
) : DoseEventRepository {
    private val statuses = ConcurrentHashMap<OccurrenceKey, DoseStatus>()
    private val receipts = ConcurrentHashMap<String, LocalActionReceipt>()
    private val revisions = MutableStateFlow(0)

    fun statusOf(occurrence: OccurrenceKey): DoseStatus? = statuses[occurrence]

    override fun observeEvents(elderId: String, range: LocalDateRange): Flow<Cached<List<DoseEventState>>> =
        revisions.map {
            Cached(emptyList(), hasLocalData = false, lastSuccessfulSyncAtEpochMillis = null, isStale = true,
                access = com.group33.medicationreminder.core.data.contract.AccessState.UNKNOWN)
        }

    override fun observeLocalAction(actionId: String): Flow<LocalActionReceipt?> =
        revisions.map { receipts[actionId] }

    override suspend fun recordAction(
        occurrence: OccurrenceKey,
        kind: DoseActionKind,
    ): Outcome<LocalActionReceipt> {
        if (failNextRecord()) {
            return Outcome.Failure(RepositoryError.Local(LocalFailureCode.STORAGE_UNAVAILABLE))
        }
        val recordedAt = System.currentTimeMillis()
        val actionId = UUID.randomUUID().toString()
        val classification = when (kind) {
            DoseActionKind.TAKEN -> LocalActionClassification.TIMELY_TAKEN
            DoseActionKind.REMIND_LATER -> LocalActionClassification.REMIND_LATER
        }
        val status = when (kind) {
            DoseActionKind.TAKEN -> DoseStatus.TAKEN
            DoseActionKind.REMIND_LATER -> DoseStatus.SNOOZED
        }
        val receipt = LocalActionReceipt(
            actionId = actionId,
            occurrence = occurrence,
            kind = kind,
            classification = classification,
            recordedAtEpochMillis = recordedAt,
            syncState = SyncState.PENDING_SYNC,
        )
        statuses[occurrence] = status
        receipts[actionId] = receipt
        revisions.value = revisions.value + 1
        return Outcome.Success(receipt)
    }

    override suspend fun retryAction(actionId: String): Outcome<RetryQueued> =
        Outcome.Success(RetryQueued(actionId))
}
