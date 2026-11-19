@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticQuerySpec
import java.time.Instant
import kotlin.uuid.Uuid

interface ExploreActivityReadPort {
    suspend fun readActivity(
        workspaceId: WorkspaceId,
        live: Boolean,
        before: Instant?,
        beforeId: Uuid?,
        actorId: PrincipalId?,
        credentialSurface: CredentialSurface?,
        agentClientId: cc.monomer.identity.api.AgentClientId?,
        limit: Int,
        watchedIds: Set<Uuid>,
    ): List<ExploreActivity>

    /** Agent apps in the latest [scanLimit] finished runs of the Workspace. */
    suspend fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient>
}

data class ExploreActivity(
    val id: Uuid,
    val kind: ExploreRunKind,
    val state: ExploreRunState,
    val startedAt: Instant,
    val terminalAt: Instant?,
    val actorId: PrincipalId,
    val credentialSurface: CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val connectorId: ConnectorId,
    /** The original Semantic question for a SEMANTIC run; null for a SQL run. */
    val query: SemanticQuerySpec?,
    val failureCode: ExploreRunFailureCode?,
)
