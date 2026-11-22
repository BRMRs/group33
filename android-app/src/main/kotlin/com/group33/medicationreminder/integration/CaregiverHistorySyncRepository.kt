package com.group33.medicationreminder.integration

import com.group33.medicationreminder.core.data.contract.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** C1 composition adapter: a valid history subscription coordinates one explicit range sync.
 * Results still come exclusively from the bound event Repository, never the refresh receipt.
 * It is owned by one trusted context and is not a production Repository registration.
 */
internal class CaregiverHistorySyncRepository(
    private val events: DoseEventRepository,
    private val dashboard: CaregiverDashboardRepository,
    private val elderId: String,
    private val isCurrent: () -> Boolean,
) : DoseEventRepository by events {
    override fun observeEvents(elderId: String, range: LocalDateRange) = channelFlow {
        if (elderId != this@CaregiverHistorySyncRepository.elderId || !isCurrent()) return@channelFlow
        var denied = false
        var firstCacheReceived = false
        var refresh: Job? = null
        // Room's first cache value can arrive asynchronously. Decide whether to refresh only
        // after that value, while keeping this same subscription for later changes/revocation.
        // The Host cancels this flow on range/context change.
        launch {
            events.observeEvents(elderId, range).collect { cached ->
                if (isCurrent()) {
                    if (cached.access == AccessState.REVOKED) {
                        denied = true
                        refresh?.cancel()
                    }
                    send(cached)
                    if (!firstCacheReceived) {
                        firstCacheReceived = true
                        if (!denied && isCurrent()) refresh = launch {
                            if (!denied && isCurrent()) dashboard.refresh(elderId, range)
                        }
                    }
                }
            }
        }
    }
}
