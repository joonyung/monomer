package cc.monomer.semantic.api

import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.OperationsAccess
import java.time.Instant
import java.time.LocalDate

interface SemanticOperationsQueries {
    suspend fun journeyActivity(access: OperationsAccess, workspaceIds: Set<String>, from: Instant, to: Instant): OperationsSemanticJourneyActivity
}
data class OperationsSemanticActorMilestone(val workspaceId: String, val actorId: String, val firstMetricCreatedChangeAt: Instant?, val firstAgentAt: Instant?)
data class OperationsSemanticWeeklyDefinitionChanges(val workspaceId: String, val weekStart: LocalDate, val actorId: String, val credentialSurface: CredentialSurface?, val agentClientId: AgentClientId?, val changes: Long)
data class OperationsSemanticJourneyActivity(
    val firstMetricCreatedAtByWorkspace: Map<String, Instant>,
    val actorMilestones: List<OperationsSemanticActorMilestone>,
    val weeklyDefinitionChanges: List<OperationsSemanticWeeklyDefinitionChanges>,
)
