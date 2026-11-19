package cc.monomer.dashboard.application

import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

interface DashboardScheduleRepository {
    fun databaseNow(): Instant
    fun dueCandidates(afterWorkspace: WorkspaceId?, limit: Int): List<DashboardScheduleCandidate>
    fun lockCurrent(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard?
    fun lockCursor(candidate: DashboardScheduleCandidate): Instant?
    fun configureTransactionTimeouts()
    fun previousInitialRuns(candidate: DashboardScheduleCandidate): Set<QueryRunId>
    fun insertOccurrence(candidate: DashboardScheduleCandidate, occurrence: DashboardScheduleOccurrenceView)
    fun advance(candidate: DashboardScheduleCandidate, nextScheduledAt: Instant)
    fun listSchedules(workspaceId: WorkspaceId, dashboardId: DashboardId): List<DashboardScheduleStatus>
    fun listOccurrences(workspaceId: WorkspaceId, dashboardId: DashboardId, before: DashboardScheduleOccurrenceCursor?, limit: Int): List<DashboardScheduleOccurrenceView>
    fun readOccurrence(workspaceId: WorkspaceId, dashboardId: DashboardId, scheduleKey: String, scheduledAt: Instant): DashboardScheduleOccurrenceView?
}

data class DashboardScheduleCandidate(val workspaceId: WorkspaceId, val dashboardId: DashboardId, val scheduleKey: String, val scheduledAt: Instant, val dashboard: Dashboard)
