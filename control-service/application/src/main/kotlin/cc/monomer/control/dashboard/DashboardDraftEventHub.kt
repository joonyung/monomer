package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.DashboardId
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription

data class DashboardDraftInvalidation(
    val dashboardId: DashboardId,
    val reason: String,
    val observedAt: Instant,
)

class DashboardDraftEventHub(
    private val clock: Clock,
    private val notifications: PostgresDashboardNotifications,
) {
    fun subscribe(dashboardId: DashboardId): Flow<DashboardDraftInvalidation> =
        notifications.draftChanges
            .onSubscription { emit(null) }
            .filter { it == null || it.first == dashboardId }
            .map { DashboardDraftInvalidation(dashboardId, it?.second ?: "RECONNECTED", clock.instant()) }

    fun emit(dashboardId: DashboardId, reason: String) {
        notifications.publishDraft(dashboardId, reason)
    }
}
