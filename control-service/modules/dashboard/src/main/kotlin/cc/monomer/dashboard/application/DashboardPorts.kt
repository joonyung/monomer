@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.connector.api.ConnectorId
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardListItem
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardBindValue
import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant

interface DashboardTransactionPort {
    suspend fun <T> inTransaction(block: () -> T): T
}

interface DashboardRepository {
    fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<cc.monomer.dashboard.api.DashboardCurrentSemanticReferences>
    fun find(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard?
    fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard?
    fun list(workspaceId: WorkspaceId): List<cc.monomer.dashboard.domain.DashboardListEntry>
    fun findPage(
        workspaceId: WorkspaceId,
        cursor: DashboardPageCursor?,
        limit: Int,
    ): List<DashboardListItem>
    fun insert(dashboard: Dashboard): Boolean
    fun applyCurrent(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        definition: DashboardDefinition,
        updatedAt: Instant,
        updatedBy: String?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        agentClientId: cc.monomer.identity.api.AgentClientId?,
    ): Boolean
}

interface DashboardDraftRepository {
    fun findDraft(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft?
    fun findDraftForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft?

    /** Reads the revision, targets with their run states, and ready results from one statement snapshot. */
    fun findDraftStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraftStatus?
    fun insert(draft: DashboardDraft): Boolean
    fun update(expectedUpdatedAt: Instant, draft: DashboardDraft): Boolean

    /** Persists a mutation only when the existing authoring revision matches. */
    fun updateAtAuthoringRevision(expectedRevision: Long, draft: DashboardDraft): Boolean {
        val current = findDraft(draft.workspaceId, draft.dashboardId) ?: return false
        if (current.authoringRevision != expectedRevision) return false
        return update(current.updatedAt, draft)
    }
}

data class DashboardDraftStatus(
    val authoringRevision: Long,
    val targetQueryRuns: Map<String, DashboardDraftTargetQueryRun>,
    val readyResults: Map<String, cc.monomer.dashboard.domain.DashboardDraftReadyResult>,
)

data class DashboardDraftTargetQueryRun(
    val queryRunId: QueryRunId,
    val state: cc.monomer.dashboard.domain.QueryRunState,
)

interface DashboardSessionRepository {
    fun find(workspaceId: WorkspaceId, sessionId: DashboardSessionId): DashboardSession?
    fun find(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
    ): DashboardSession?
    fun findInitial(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId, deadline: Instant): DashboardSession?
    fun findPage(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        cursor: DashboardSessionPageCursor?,
        limit: Int,
    ): List<DashboardSessionSummary>
    fun insert(session: DashboardSession): Boolean
    fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): DashboardSession?

}

interface QueryRunRepository {
    fun find(queryRunId: QueryRunId): QueryRun?
    fun find(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        viewerSessionId: DashboardSessionId?,
        queryRunId: QueryRunId,
    ): QueryRun?
    fun findEquivalent(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        viewerSessionId: DashboardSessionId?,
        queryName: String,
        connectorId: ConnectorId,
        resolvedQuery: DashboardResolvedQuery,
        renderedSql: String,
        outputColumnNames: List<String>,
        allowedStates: Set<cc.monomer.dashboard.domain.QueryRunState>,
    ): QueryRun?

    /** Answers each lookup exactly as [findEquivalent] would, in lookup order, within one deadline-bounded read. */
    fun findEquivalentRuns(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        viewerSessionId: DashboardSessionId?,
        lookups: List<EquivalentQueryRunLookup>,
        allowedStates: Set<cc.monomer.dashboard.domain.QueryRunState>,
        deadline: Instant,
    ): List<QueryRun?>

    fun findRecentDraftRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, limit: Int): List<QueryRun>

    fun findDraftPage(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        cursor: DashboardQueryRunPageCursor?,
        limit: Int,
    ): List<QueryRun>

    fun findSessionPage(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        cursor: DashboardQueryRunPageCursor?,
        limit: Int,
    ): List<QueryRun>

    fun findRunningSessionRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): List<QueryRun>

    fun insert(queryRun: QueryRun): Boolean
    fun update(expectedUpdatedAt: Instant, queryRun: QueryRun): Boolean
}

data class EquivalentQueryRunLookup(
    val queryName: String,
    val connectorId: ConnectorId,
    val resolvedQuery: DashboardResolvedQuery,
    val renderedSql: String,
    val outputColumnNames: List<String>,
)

/** Plans both named query types through the same current execution admission. */
interface DashboardQueryPlanningPort {
    suspend fun admitPageReader(
        workspaceId: WorkspaceId, dashboardId: DashboardId, pageId: kotlin.uuid.Uuid,
        credential: PresentedCredential?, expectedOAuthResource: ExpectedOAuthResource?,
    ): cc.monomer.dashboard.api.DashboardOwnerOutcome<cc.monomer.identity.api.IdentityCredentialEvidence?>

    suspend fun resolveBatch(commands: List<ResolveQueryCommand>): List<DashboardQueryResolution>
    suspend fun resolve(command: ResolveQueryCommand): DashboardQueryResolution

    data class ResolveQueryCommand(
        val workspaceId: WorkspaceId,
        val query: DashboardNamedQuery,
        val resolvedQuery: DashboardResolvedQuery,
        val credential: PresentedCredential?,
        val expectedOAuthResource: ExpectedOAuthResource?,
        val deadline: Instant?,
        val dashboardId: DashboardId,
        val readerEvidence: cc.monomer.identity.api.IdentityCredentialEvidence?,
    )
}

sealed interface DashboardQueryResolution {
    data class Resolved(
        val resolvedQuery: DashboardResolvedQuery,
        val sql: String,
        val outputColumns: List<String>,
        val actorId: String?,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentClientId: cc.monomer.identity.api.AgentClientId?,
        val parameterNames: List<String>,
        val outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
        val reportingTimeZone: java.time.ZoneId?,
    ) : DashboardQueryResolution {
        init {
            require(sql.isNotBlank())
            require(outputColumns.isNotEmpty())
            require(outputColumns.toSet().size == outputColumns.size)
            require(parameterNames.size == resolvedQuery.bindings.size)
        }
    }

    data class Rejected(val code: String, val message: String?) : DashboardQueryResolution
}

/** Technical execution admission. The concrete adapter maps this to Execution Runtime's public API. */
interface DashboardExecutionAdmissionPort {
    fun lockWorkspace(workspaceId: WorkspaceId)
    fun hasUnreleasedOccupancy(workspaceId: WorkspaceId, queryRunIds: Set<QueryRunId>): Boolean
    fun admit(command: AdmissionCommand): AdmissionOutcome

    data class AdmissionCommand(
        val queryRunId: QueryRunId,
        val workspaceId: WorkspaceId,
        val connectorId: ConnectorId,
        val sql: String,
        val idempotencyKey: String,
        val resultObjectKey: String,
        val rowLimit: Int,
        val bindings: List<DashboardBindValue>,
        val validateRawSqlBeforeRun: Boolean,
        val expectedColumnNames: List<String>,
        val parameterNames: List<String>,
    ) {
        init {
            require(sql.isNotBlank())
            require(idempotencyKey.isNotBlank())
            require(resultObjectKey.isNotBlank())
            require(rowLimit > 0)
            require(parameterNames.size == bindings.size)
        }
    }

    sealed interface AdmissionOutcome {
        data object Admitted : AdmissionOutcome
        data object Existing : AdmissionOutcome
        data object Backpressured : AdmissionOutcome
        data class Rejected(val code: String) : AdmissionOutcome {
            init { require(code.isNotBlank()) }
        }
    }
}

interface DashboardExecutionCancellationPort {
    fun requestCancel(command: CancelCommand): CancellationOutcome

    data class CancelCommand(
        val queryRunId: QueryRunId,
        val workspaceId: WorkspaceId,
        val reason: String,
    )

    sealed interface CancellationOutcome {
        data object Requested : CancellationOutcome
        data object AlreadyTerminal : CancellationOutcome
        data object NotFound : CancellationOutcome
    }
}

interface DashboardResultReadPort {
    suspend fun read(reference: ArrowResultManifest): DashboardResultPayload

    data class DashboardResultPayload(
        val columns: List<Column>,
        val rows: List<List<kotlinx.serialization.json.JsonElement>>,
        val totalRowCount: ULong,
        val returnedRowCount: Int,
        val truncated: Boolean,
    ) {
        constructor(
            columns: List<Column>,
            rows: List<List<kotlinx.serialization.json.JsonElement>>,
        ) : this(
            columns = columns,
            rows = rows,
            totalRowCount = rows.size.toULong(),
            returnedRowCount = rows.size,
            truncated = false,
        )

        init {
            require(columns.map(Column::name).toSet().size == columns.size)
            require(returnedRowCount >= 0)
            require(returnedRowCount == rows.size)
            require(rows.all { it.size == columns.size })
            require(returnedRowCount.toULong() <= totalRowCount)
            require(truncated == (returnedRowCount.toULong() < totalRowCount))
            require(rows.flatten().all { cell ->
                cell is JsonNull || (cell is JsonPrimitive && (cell.isString || cell.booleanOrNull != null))
            }) {
                "dashboard result cells must be null, boolean, or canonical strings"
            }
        }
    }

    data class Column(val name: String, val logicalType: String, val nullable: Boolean, val display: cc.monomer.semantic.api.MetricDisplay?) {
        init { require(name.isNotBlank()); require(logicalType.isNotBlank()) }
    }
}

interface DashboardCommandReceiptRepository {
    /** Serializes the receipt key for the full owner transaction. */
    fun lock(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String)
    fun find(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String): CommandReceipt?
    fun insert(receipt: CommandReceipt): Boolean
}

data class CommandReceipt(
    val workspaceId: WorkspaceId,
    val actorId: String?,
    val idempotencyKey: String,
    val commandKind: String,
    val subjectId: String,
    val normalizedRequest: JsonObject,
    val dashboardId: DashboardId?,
    val sessionId: DashboardSessionId?,
    val queryRunIds: List<QueryRunId>,
    val response: CommandReceiptResponse,
    val createdAt: Instant,
) {
    init {
        require(actorId == null || actorId.isNotBlank())
        require((actorId == null) == (commandKind == "RESOLVE_PUBLIC_SESSION"))
        require(idempotencyKey.isNotBlank())
        require(commandKind.isNotBlank())
        require(subjectId.isNotBlank())
    }
}

/** Immutable typed response material retained by a technical idempotency receipt. */
sealed interface CommandReceiptResponse {
    data class DashboardCreated(val dashboard: Dashboard) : CommandReceiptResponse
    data class Draft(val draft: DashboardDraft, val queryRuns: List<QueryRun>) : CommandReceiptResponse
    data class DraftApplied(val dashboard: Dashboard) : CommandReceiptResponse
    data class Session(val session: DashboardSession, val queryRuns: List<QueryRun>) : CommandReceiptResponse
    data class SessionResolution(val variables: Map<String, kotlinx.serialization.json.JsonElement>, val queryRuns: Map<String, QueryRunId>) : CommandReceiptResponse
}

class DashboardReadDeadlineExceeded : RuntimeException("Dashboard initial result read deadline exceeded")
