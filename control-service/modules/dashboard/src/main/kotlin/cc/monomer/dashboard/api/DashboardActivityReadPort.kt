@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.uuid.Uuid

interface DashboardActivityReadPort {
    suspend fun readChanges(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: String?, credentialSurface: CredentialSurface?,
        agentClientId: AgentClientId?, limit: Int, includeDraft: Boolean,
    ): List<DashboardChange>

    /**
     * One row per Viewer Session, summarized by the latest run of each of its queries. A live Session has a running
     * latest run and is ordered by the earliest start among those running runs; a recent Session has none and is
     * ordered by its last run's update. The keyset tie-breaker is the Session id.
     */
    suspend fun readSessions(
        workspaceId: WorkspaceId, live: Boolean, before: Instant?, beforeId: Uuid?, actorId: String?,
        credentialSurface: CredentialSurface?, agentClientId: AgentClientId?, limit: Int, watchedSessionIds: Set<Uuid>,
    ): List<DashboardSessionActivity>

    /** Shared Draft runs, one row per run, for Dashboards whose shared Draft still exists. */
    suspend fun readDraftRuns(
        workspaceId: WorkspaceId, live: Boolean, before: Instant?, beforeId: Uuid?, actorId: String?,
        credentialSurface: CredentialSurface?, agentClientId: AgentClientId?, limit: Int, watchedIds: Set<Uuid>,
    ): List<DashboardRunActivity>

    /** Agent apps in the latest [scanLimit] definition changes, Draft edits, and finished runs of the Workspace. */
    suspend fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient>
}

data class DashboardChange(
    val id: Uuid, val dashboardId: Uuid, val deleted: Boolean, val title: String, val occurredAt: Instant, val actorId: String?, val credentialSurface: CredentialSurface?,
    val agentClientId: AgentClientId?, val change: DashboardChangeKind,
)

/** A Draft edited back to the applied definition, as Discard changes does, reads as [DRAFT_DISCARDED]. */
enum class DashboardChangeKind { CREATED, DRAFT_UPDATED, DRAFT_DISCARDED, APPLIED, DELETED }

/**
 * Who opened a Session: a member, a Dashboard schedule (its occurrence names the Session), an anonymous reader of a
 * public Page (a public resolution receipt names the Session), or nobody recorded.
 */
enum class DashboardSessionOrigin { MEMBER, SCHEDULE, PUBLIC_READER, UNRECORDED }

/**
 * A Session summarized by the latest run of each of its queries. [startedAt] is the earliest start among running
 * latest runs, or among all latest runs when none runs; [updatedAt] is the last update of any run of the Session;
 * [failureCode] is the earliest failure among the latest runs.
 */
data class DashboardSessionActivity(
    val sessionId: Uuid, val dashboardId: Uuid, val title: String, val origin: DashboardSessionOrigin,
    val queryCount: Int, val runningCount: Int, val failedCount: Int, val cancelledCount: Int,
    val startedAt: Instant, val updatedAt: Instant, val actorId: String?, val credentialSurface: CredentialSurface?,
    val agentClientId: AgentClientId?, val failureCode: String?,
) {
    init {
        require(queryCount > 0 && runningCount >= 0 && failedCount >= 0 && cancelledCount >= 0)
        require(runningCount + failedCount + cancelledCount <= queryCount)
        require((origin == DashboardSessionOrigin.MEMBER) == (actorId != null))
    }
}

data class DashboardRunActivity(
    val id: Uuid, val dashboardId: Uuid, val title: String, val queryName: String,
    val state: String, val startedAt: Instant, val updatedAt: Instant, val actorId: String?,
    val credentialSurface: CredentialSurface?, val agentClientId: AgentClientId?, val connectorId: ConnectorId, val failureCode: String?,
)
