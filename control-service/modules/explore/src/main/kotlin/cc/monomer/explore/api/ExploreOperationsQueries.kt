package cc.monomer.explore.api

import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.OperationsAccess
import java.time.Instant
import java.time.LocalDate

interface ExploreOperationsQueries {
    suspend fun overview(access: OperationsAccess, from: Instant, to: Instant): OperationsExploreOverview
    suspend fun runs(access: OperationsAccess, from: Instant, to: Instant, workspaceId: String?, kind: String?, state: String?, timeBasis: String, before: Instant?, beforeId: String?, limit: Int): List<OperationsRun>
    suspend fun run(access: OperationsAccess, id: String): OperationsRun?
    suspend fun workspaceCounts(access: OperationsAccess, ids: Set<String>, from: Instant, to: Instant): Map<String, OperationsRunCounts>
    suspend fun activeWorkspaceIds(access: OperationsAccess, from: Instant, to: Instant): Set<String>
    suspend fun journeyActivity(access: OperationsAccess, workspaceIds: Set<String>, from: Instant, to: Instant): OperationsExploreJourneyActivity
}
data class OperationsRun(val id: String, val workspaceId: String, val kind: String, val state: String, val createdAt: Instant, val terminalAt: Instant?, val failureCode: String?)
data class OperationsRunCounts(val runs: Long, val succeeded: Long, val failed: Long, val cancelled: Long, val running: Long, val sql: Long, val semantic: Long)
data class OperationsExploreDaily(val date: String, val runs: Long)
data class OperationsExploreOverview(val runs: Long, val workspacesWithExploreRuns: Long, val succeeded: Long, val failed: Long, val cancelled: Long, val running: Long, val successRate: Double?, val daily: List<OperationsExploreDaily>, val recentFailedRuns: List<OperationsRun>)
data class OperationsExploreActorMilestone(val workspaceId: String, val actorId: String, val firstSucceededAt: Instant?, val firstAgentAt: Instant?)
data class OperationsExploreWeeklyRuns(val workspaceId: String, val weekStart: LocalDate, val actorId: String, val credentialSurface: CredentialSurface?, val agentClientId: AgentClientId?, val sampleDataset: Boolean, val runs: Long, val succeededRuns: Long)
data class OperationsExploreJourneyActivity(val actorMilestones: List<OperationsExploreActorMilestone>, val weeklyRuns: List<OperationsExploreWeeklyRuns>)
