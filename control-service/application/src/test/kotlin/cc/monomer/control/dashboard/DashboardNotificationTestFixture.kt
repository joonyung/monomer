package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow

internal class DashboardNotificationTestFixture {
    val sessions = MutableSharedFlow<Pair<DashboardSessionId, String>?>(extraBufferCapacity = 256)
    val drafts = MutableSharedFlow<Pair<DashboardId, String>?>(extraBufferCapacity = 256)
    val notifications = mockk<PostgresDashboardNotifications>(relaxed = true) {
        every { sessionChanges } returns sessions
        every { draftChanges } returns drafts
    }
}

fun publishSessionSettlementForTest(hub: DashboardSessionEventHub, sessionId: DashboardSessionId, reason: String) {
    emitDashboardSessionInvalidationAfterCommit(hub, sessionId, reason)
}

fun publishDraftSettlementForTest(hub: DashboardDraftEventHub, dashboardId: DashboardId, reason: String) {
    emitDashboardDraftInvalidationAfterCommit(hub, dashboardId, reason)
}
