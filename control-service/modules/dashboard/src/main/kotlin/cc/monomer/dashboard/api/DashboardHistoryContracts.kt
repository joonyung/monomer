@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.api

import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.uuid.Uuid

data class DashboardDeletionMetadata(
    val actorId: String,
    val organizationId: cc.monomer.identity.api.OrganizationId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val requestedAt: Instant,
    val credentialSurface: CredentialSurface?,
    val agentClientId: AgentClientId?,
) {
    init { require(actorId.isNotBlank()) }
}

data class DeleteDashboardCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val metadata: DashboardDeletionMetadata,
)

data class DashboardHistoryCursor(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val occurredAt: Instant,
    val changeId: Uuid,
)

data class ListDashboardHistoryCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val cursor: DashboardHistoryCursor?,
    val pageSize: Int,
)

data class ReadDashboardHistoryCommand(val workspaceId: WorkspaceId, val dashboardId: DashboardId, val changeId: Uuid)

data class RestoreDashboardCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val changeId: Uuid,
    val metadata: DashboardOwnerCommandMetadata,
)

enum class DashboardDefinitionChangeAction { CREATED, APPLIED, DELETED }

data class DashboardOwnerDefinitionChange(
    val changeId: Uuid,
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val title: String,
    val action: DashboardDefinitionChangeAction,
    val definition: JsonObject,
    val actorId: String?,
    val credentialSurface: CredentialSurface?,
    val agentClientId: AgentClientId?,
    val occurredAt: Instant,
    val restorable: Boolean,
)

data class DashboardOwnerHistoryPage(
    val definitionId: DashboardId,
    val name: String,
    val deleted: Boolean,
    val changes: List<DashboardOwnerDefinitionChange>,
    val nextCursor: DashboardHistoryCursor?,
)

interface DashboardCurrentExistencePort {
    fun existsInCallerTransaction(workspaceId: WorkspaceId, dashboardId: DashboardId): Boolean
}

data class DashboardDeletionRun(val queryRunId: QueryRunId, val executionRunId: ExecutionRunId, val running: Boolean)

interface DashboardDependencyCleanupPort {
    fun discardRunsInCallerTransaction(workspaceId: WorkspaceId, runs: List<DashboardDeletionRun>, reason: String, at: Instant): DashboardRunDiscardOutcome
    fun deletePagesInCallerTransaction(command: DeleteDashboardCommand)
    fun invalidateAfterCommit(workspaceId: WorkspaceId, dashboardId: DashboardId, draftExisted: Boolean, sessionIds: List<DashboardSessionId>)
}

enum class DashboardRunDiscardOutcome { COMPLETED, RETRYABLE_CONFLICT }
