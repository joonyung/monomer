package cc.monomer.dashboard.api

import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.OperationsAccess
import java.time.Instant
import java.time.LocalDate

interface DashboardOperationsQueries {
    suspend fun journeyActivity(access: OperationsAccess, workspaceIds: Set<String>, from: Instant, to: Instant): OperationsDashboardJourneyActivity
}
data class OperationsDashboardActorMilestone(val workspaceId: String, val actorId: String, val firstSucceededQueryRunAt: Instant?, val firstCreatedChangeAt: Instant?, val firstAgentAt: Instant?, val firstAgentQueryRunAt: Instant?)
data class OperationsDashboardWeeklyQueryRuns(val workspaceId: String, val weekStart: LocalDate, val actorId: String, val credentialSurface: CredentialSurface?, val agentClientId: AgentClientId?, val runs: Long, val succeededRuns: Long)
data class OperationsDashboardWeeklyDefinitionChanges(val workspaceId: String, val weekStart: LocalDate, val actorId: String, val credentialSurface: CredentialSurface?, val agentClientId: AgentClientId?, val changes: Long)
data class OperationsDashboardJourneyActivity(
    val firstDashboardCreatedAtByWorkspace: Map<String, Instant>,
    val actorMilestones: List<OperationsDashboardActorMilestone>,
    val weeklyQueryRuns: List<OperationsDashboardWeeklyQueryRuns>,
    val weeklyDefinitionChanges: List<OperationsDashboardWeeklyDefinitionChanges>,
)
