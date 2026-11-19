package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.DashboardSessionId
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription

data class DashboardSessionInvalidation(
    val sessionId: DashboardSessionId,
    val reason: String,
    val observedAt: Instant,
)

class DashboardSessionEventHub(
    private val clock: Clock,
    private val notifications: PostgresDashboardNotifications,
) {
    fun subscribe(sessionId: DashboardSessionId): Flow<DashboardSessionInvalidation> =
        notifications.sessionChanges
            .onSubscription { emit(null) }
            .filter { it == null || it.first == sessionId }
            .map { DashboardSessionInvalidation(sessionId, it?.second ?: "RECONNECTED", clock.instant()) }

    fun emit(sessionId: DashboardSessionId, reason: String) {
        notifications.publishSession(sessionId, reason)
    }
}
