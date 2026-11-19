@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.api

import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Typed owner seam for REST and Agent Workspace adapters. */
interface DashboardOwnerPort {
    suspend fun delete(command: DeleteDashboardCommand): DashboardOwnerOutcome<Unit>
    suspend fun listHistory(command: ListDashboardHistoryCommand): DashboardOwnerOutcome<DashboardOwnerHistoryPage>
    suspend fun readHistory(command: ReadDashboardHistoryCommand): DashboardOwnerOutcome<DashboardOwnerDefinitionChange>
    suspend fun restore(command: RestoreDashboardCommand): DashboardOwnerOutcome<DashboardOwnerDashboard>
    suspend fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<DashboardCurrentSemanticReferences>
    suspend fun create(command: CreateDashboardCommand): DashboardOwnerOutcome<DashboardOwnerDashboard>
    suspend fun read(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardOwnerOutcome<DashboardOwnerDashboard>
    suspend fun listDashboards(
        workspaceId: WorkspaceId,
        cursor: DashboardOwnerDashboardPageCursor?,
        pageSize: Int,
    ): DashboardOwnerOutcome<DashboardOwnerDashboardPage>
    suspend fun startDraft(command: StartDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft>
    suspend fun readDraft(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardOwnerOutcome<DashboardOwnerDraft>
    suspend fun readDraftStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardOwnerOutcome<DashboardOwnerDraftStatus>
    suspend fun listDraftQueryRuns(command: ListDraftQueryRunsCommand): DashboardOwnerOutcome<DashboardOwnerQueryRunPage>
    suspend fun replaceDraftDocument(command: ReplaceDraftDocumentCommand): DashboardOwnerOutcome<DashboardOwnerDraft>
    suspend fun applyDraft(command: ApplyDraftCommand): DashboardOwnerOutcome<DashboardOwnerDashboard>
    suspend fun discardDraft(command: DiscardDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft>
    suspend fun refreshDraft(command: RefreshDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft>
    suspend fun readDashboardDraftRun(command: ReadDashboardDraftRunCommand): DashboardOwnerOutcome<DashboardOwnerQueryRun>
    suspend fun readDashboardDraftResult(command: ReadDashboardDraftResultCommand): DashboardOwnerResultLookupOutcome
    suspend fun cancelDashboardDraftRun(command: CancelDashboardDraftRunCommand): DashboardOwnerOutcome<DashboardOwnerDraft>

    suspend fun openSession(command: OpenDashboardSessionCommand): DashboardOwnerOutcome<DashboardOwnerSession>
    suspend fun readSession(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
    ): DashboardOwnerOutcome<DashboardOwnerSession>
    suspend fun listSessions(command: ListDashboardSessionsCommand): DashboardOwnerOutcome<DashboardOwnerSessionPage>
    suspend fun resolveSession(command: ResolveDashboardSessionCommand): DashboardOwnerOutcome<DashboardOwnerSessionResolution>
    suspend fun resolvePublicSession(command: ResolvePublicDashboardSessionCommand): DashboardOwnerOutcome<DashboardOwnerSessionResolution>
    suspend fun readInitialSessionResults(command: ReadInitialDashboardSessionResultsCommand): DashboardOwnerOutcome<DashboardOwnerInitialSessionResults>
    suspend fun refreshSession(command: RefreshDashboardSessionCommand): DashboardOwnerOutcome<DashboardOwnerSessionResolution>
    suspend fun listSessionQueryRuns(command: ListDashboardSessionQueryRunsCommand): DashboardOwnerOutcome<DashboardOwnerQueryRunPage>
    suspend fun readSessionQueryRun(command: ReadDashboardSessionQueryRunCommand): DashboardOwnerOutcome<DashboardOwnerQueryRun>
    suspend fun readSessionResult(command: ReadDashboardSessionResultCommand): DashboardOwnerResultLookupOutcome
}

data class DashboardCurrentSemanticReferences(
    val dashboardId: DashboardId,
    val title: String,
    val queryMetricNames: Map<String, Set<String>>,
)

data class DashboardOwnerCommandMetadata(
    val actorId: String,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val idempotencyKey: String,
    val requestedAt: Instant,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
) {
    init {
        require(actorId.isNotBlank())
        require(idempotencyKey.isNotBlank())
    }
}

data class CreateDashboardCommand(
    val workspaceId: WorkspaceId,
    val definition: JsonObject,
    val metadata: DashboardOwnerCommandMetadata,
)

data class StartDraftCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val parameterBindings: Map<String, JsonElement>,
    val metadata: DashboardOwnerCommandMetadata,
)

data class ReplaceDraftDocumentCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val expectedRevision: Long,
    val definition: JsonObject,
    val parameterBindings: Map<String, JsonElement>,
    val variables: Map<String, JsonElement>,
    val metadata: DashboardOwnerCommandMetadata,
)

data class ListDraftQueryRunsCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val cursor: DashboardOwnerQueryRunPageCursor?,
    val pageSize: Int,
)

data class ApplyDraftCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val expectedRevision: Long,
    val metadata: DashboardOwnerCommandMetadata,
)

data class DiscardDraftCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val expectedRevision: Long,
    val metadata: DashboardOwnerCommandMetadata,
)

data class RefreshDraftCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val queryNames: Set<String>,
    val metadata: DashboardOwnerCommandMetadata,
)

data class ReadDashboardDraftRunCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val queryRunId: QueryRunId,
)

data class ReadDashboardDraftResultCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val queryRunId: QueryRunId,
)

data class CancelDashboardDraftRunCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val queryRunId: QueryRunId,
    val metadata: DashboardOwnerCommandMetadata,
)

/** [updatedBy] is the principal id of the person whose save produced this definition; null for historical rows and Monomer sample seeds. */
data class DashboardOwnerDashboard(
    val dashboardId: DashboardId,
    val workspaceId: WorkspaceId,
    val definition: JsonObject,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: String?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
)

data class DashboardOwnerDashboardListItem(
    val dashboardId: DashboardId,
    val workspaceId: WorkspaceId,
    val title: String?,
    val description: String?,
    val updatedAt: Instant,
)

data class DashboardOwnerDashboardPageCursor(
    val updatedAt: Instant,
    val dashboardId: DashboardId,
)

data class DashboardOwnerDashboardPage(
    val items: List<DashboardOwnerDashboardListItem>,
    val nextCursor: DashboardOwnerDashboardPageCursor?,
)

data class DashboardOwnerQueryRunPageCursor(
    val createdAt: Instant,
    val queryRunId: QueryRunId,
)

data class DashboardOwnerQueryRunPage(
    val items: List<DashboardOwnerQueryRun>,
    val nextCursor: DashboardOwnerQueryRunPageCursor?,
)

data class DashboardOwnerDraft(
    val dashboardId: DashboardId,
    val workspaceId: WorkspaceId,
    val definition: JsonObject,
    val parameterBindings: Map<String, JsonElement>,
    val variables: Map<String, JsonElement>,
    val authoringRevision: Long,
    val targetQueryRuns: Map<String, QueryRunId>,
    val readyResults: Map<String, DashboardOwnerReadyResult>,
    val recentQueryRuns: List<DashboardOwnerQueryRun>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastEditedBy: String?,
    val lastEditedAt: Instant?,
    val lastEditSurface: cc.monomer.identity.api.CredentialSurface?,
    val lastEditAgentClientId: cc.monomer.identity.api.AgentClientId?,
)

data class DashboardOwnerDraftStatus(
    val dashboardId: DashboardId,
    val authoringRevision: Long,
    val targetQueryRuns: Map<String, DashboardOwnerDraftTargetQueryRun>,
    val readyResults: Map<String, DashboardOwnerReadyResult>,
)

data class DashboardOwnerDraftTargetQueryRun(
    val queryRunId: QueryRunId,
    val state: String,
)

data class DashboardOwnerReadyResult(
    val authoringRevision: Long,
    val parameterBindings: Map<String, JsonElement>,
    val variables: Map<String, JsonElement>,
    val queryRuns: Map<String, QueryRunId>,
)

data class DashboardOwnerQueryRun(
    val queryRunId: QueryRunId,
    val dashboardId: DashboardId,
    val viewerSessionId: DashboardSessionId?,
    val queryName: String,
    val queryType: String,
    val connectorId: cc.monomer.connector.api.ConnectorId,
    val state: String,
    val resultAvailable: Boolean,
    val failureCode: String?,
    val failureMessage: String?,
    val outputColumnNames: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val actorId: String?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
    val reportingTimeZone: java.time.ZoneId?,
)

data class OpenDashboardSessionCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val parameterBindings: Map<String, JsonElement>,
    val metadata: DashboardOwnerCommandMetadata,
)

data class ListDashboardSessionsCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val cursor: DashboardOwnerSessionPageCursor?,
    val pageSize: Int,
)

data class ResolveDashboardSessionCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val sessionId: DashboardSessionId,
    val variables: Map<String, JsonElement>,
    val metadata: DashboardOwnerCommandMetadata,
)

data class RefreshDashboardSessionCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val sessionId: DashboardSessionId,
    val variables: Map<String, JsonElement>,
    val queryNames: Set<String>,
    val metadata: DashboardOwnerCommandMetadata,
)

data class DashboardOwnerSessionResolution(
    val variables: Map<String, JsonElement>,
    val queryRuns: Map<String, QueryRunId>,
)

data class DashboardOwnerSessionPageCursor(
    val dashboardId: DashboardId,
    val createdAt: Instant,
    val sessionId: DashboardSessionId,
)

data class DashboardOwnerSessionPage(
    val items: List<DashboardOwnerSessionSummary>,
    val nextCursor: DashboardOwnerSessionPageCursor?,
)

data class ListDashboardSessionQueryRunsCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val sessionId: DashboardSessionId,
    val cursor: DashboardOwnerQueryRunPageCursor?,
    val pageSize: Int,
)

data class ReadDashboardSessionQueryRunCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val sessionId: DashboardSessionId,
    val queryRunId: QueryRunId,
)

data class ReadDashboardSessionResultCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val sessionId: DashboardSessionId,
    val queryRunId: QueryRunId,
)

data class DashboardOwnerSessionSummary(
    val sessionId: DashboardSessionId,
    val dashboardId: DashboardId,
    val parameterBindings: Map<String, JsonElement>,
    val runningQueryRuns: List<DashboardOwnerCurrentQueryRun>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastQueryRunAt: Instant?,
)

data class DashboardOwnerCurrentQueryRun(
    val queryName: String,
    val queryRunId: QueryRunId,
    val state: String,
)

data class DashboardOwnerSession(
    val sessionId: DashboardSessionId,
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val definition: JsonObject,
    val parameterBindings: Map<String, JsonElement>,
    val initialVariables: Map<String, JsonElement>,
    val runningQueryRuns: List<DashboardOwnerQueryRun>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DashboardOwnerResultLookup(
    val queryRun: DashboardOwnerQueryRun,

    val resultManifest: ArrowResultManifest,
)

sealed interface DashboardOwnerResultLookupOutcome {
    data class Success(val value: DashboardOwnerResultLookup) : DashboardOwnerResultLookupOutcome
    data object NotFound : DashboardOwnerResultLookupOutcome
    data class Rejected(val code: String, val message: String?) : DashboardOwnerResultLookupOutcome
}

sealed interface DashboardOwnerOutcome<out T> {
    data class Success<T>(val value: T) : DashboardOwnerOutcome<T>
    data object NotFound : DashboardOwnerOutcome<Nothing>
    data class Conflict(val currentRevision: Long?) : DashboardOwnerOutcome<Nothing>
    data class Rejected(val code: String, val message: String?) : DashboardOwnerOutcome<Nothing>
}


data class ResolvePublicDashboardSessionCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val sessionId: DashboardSessionId,
    val variables: Map<String, JsonElement>,
    val idempotencyKey: String,
    val requestedAt: Instant,
    val credential: PresentedCredential?,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val actorId: String?,
    val pageId: kotlin.uuid.Uuid,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require((credential == null) == (actorId == null))
        require(credential != null || expectedOAuthResource == null)
    }
}

data class ReadInitialDashboardSessionResultsCommand(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val sessionId: DashboardSessionId,
    val remainingQueryCount: Int,
    val deadline: Instant,
) {
    init { require(remainingQueryCount in 0..64) }
}

data class DashboardOwnerInitialSessionResults(
    val requiredQueryNames: Set<String>,
    val results: Map<String, DashboardOwnerResultLookup>,
    val queriesChecked: Int,
    val budgetExhausted: Boolean,
)
