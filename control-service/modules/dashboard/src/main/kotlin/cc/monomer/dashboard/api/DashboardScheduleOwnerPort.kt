package cc.monomer.dashboard.api

import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

interface DashboardScheduleOwnerPort {
    suspend fun tick(): DashboardScheduleTickResult
    suspend fun preview(document: JsonObject, after: Instant): List<DashboardSchedulePreviewView>
    suspend fun listSchedules(workspaceId: WorkspaceId, dashboardId: DashboardId): List<DashboardScheduleStatus>
    suspend fun listOccurrences(workspaceId: WorkspaceId, dashboardId: DashboardId, before: DashboardScheduleOccurrenceCursor?, limit: Int): DashboardScheduleOccurrencePage
    suspend fun readOccurrence(workspaceId: WorkspaceId, dashboardId: DashboardId, scheduleKey: String, scheduledAt: Instant): DashboardScheduleOccurrenceView?
}

data class DashboardScheduleStatus(val scheduleKey: String, val nextScheduledAt: Instant)
data class DashboardSchedulePreviewView(val scheduleKey: String, val scheduledAt: Instant, val localTime: String, val parameters: Map<String, JsonElement>)
data class DashboardScheduleOccurrenceCursor(val scheduledAt: Instant, val scheduleKey: String)
data class DashboardScheduleOccurrencePage(val items: List<DashboardScheduleOccurrenceView>, val nextCursor: DashboardScheduleOccurrenceCursor?)
data class DashboardScheduleOccurrenceView(
    val scheduleKey: String,
    val scheduledAt: Instant,
    val decidedAt: Instant,
    val outcome: String,
    val reason: String?,
    val definition: JsonObject,
    val parameters: Map<String, JsonElement>?,
    val sessionId: DashboardSessionId?,
    val initialQueryRuns: Map<String, QueryRunId>,
)
data class DashboardScheduleTickResult(val candidates: Int, val processed: Int, val failed: Int, val truncated: Boolean, val oldestCandidateDelayMillis: Long)
