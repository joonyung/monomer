@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.uuid.Uuid

interface CatalogActivityReadPort {
    suspend fun readActivity(
        workspaceId: WorkspaceId,
        refresh: Boolean,
        live: Boolean,
        before: Instant?,
        beforeId: Uuid?,
        actorId: PrincipalId?,
        credentialSurface: CredentialSurface?,
        agentClientId: cc.monomer.identity.api.AgentClientId?,
        limit: Int,
        watchedIds: Set<Uuid>,
    ): List<CatalogActivity>

    /** Agent apps in the latest [scanLimit] finished list refresh or ingest jobs of the Workspace. */
    suspend fun readRecentAgentClients(workspaceId: WorkspaceId, refresh: Boolean, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient>
}

data class CatalogActivity(
    val id: Uuid,
    val connectorId: ConnectorId,
    val catalogName: String?,
    val schemaName: String?,
    val state: String,
    val startedAt: Instant,
    val terminalAt: Instant?,
    val actorId: PrincipalId,
    val credentialSurface: CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    /** The failure code of a job that completed with errors: the refresh failure, or an ingest's first failed task, entry first. */
    val failureCode: String?,
)
