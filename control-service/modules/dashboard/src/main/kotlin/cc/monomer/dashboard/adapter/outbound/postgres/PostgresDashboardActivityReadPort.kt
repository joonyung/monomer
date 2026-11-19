@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardActivityReadPort
import cc.monomer.identity.api.WorkspaceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class PostgresDashboardActivityReadPort(
    dataSource: DataSource,
    private val transaction: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
) : DashboardActivityReadPort {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    init {
        val definition = requireNotNull(transaction as? TransactionDefinition)
        require(definition.isReadOnly && definition.timeout == 5)
    }

    private suspend fun <T> read(block: () -> T): T = withContext(blockingDispatcher) {
        requireNotNull(transaction.execute {
            jdbc.requireOwnerTransaction()
            jdbc.withConnection { connection ->
                connection.createStatement().use { it.execute("SET LOCAL statement_timeout = '5s'") }
            }
            TransactionValue(block())
        }).value
    }

    private data class TransactionValue<T>(val value: T)

    override suspend fun readChanges(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: String?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int, includeDraft: Boolean,
    ): List<cc.monomer.dashboard.api.DashboardChange> = read {
        require(limit in 1..101 && (before == null) == (beforeId == null))
        val clauses = mutableListOf("TRUE")
        if (before != null) clauses += "(occurred_at, activity_id) < (?, ?)"
        if (actorId != null) clauses += "actor_id = ?"
        if (credentialSurface != null) clauses += "credential_surface = ?"
        if (agentClientId != null) clauses += "agent_client_id = ?"
        jdbc.queryMany(
            """
            WITH latest AS (
                SELECT change_id AS activity_id, dashboard_id,
                    NOT EXISTS (SELECT 1 FROM dashboard.dashboards current WHERE current.workspace_id = changes.workspace_id AND current.dashboard_id = changes.dashboard_id) AS deleted,
                    title, occurred_at, actor_id::text AS actor_id, credential_surface, agent_client_id, action AS change_kind
                FROM dashboard.definition_changes changes WHERE workspace_id = ?
                UNION ALL
                SELECT draft.dashboard_id AS activity_id, draft.dashboard_id, FALSE AS deleted, draft.definition -> 'meta' ->> 'title', draft.last_edited_at,
                    draft.last_edited_by, draft.last_edit_surface, draft.last_edit_agent_client_id,
                    CASE WHEN draft.definition = current.definition THEN 'DRAFT_DISCARDED' ELSE 'DRAFT_UPDATED' END
                FROM dashboard.drafts draft JOIN dashboard.dashboards current USING (workspace_id, dashboard_id)
                WHERE draft.workspace_id = ? AND ? AND draft.last_edited_at > current.updated_at
            )
            SELECT * FROM latest WHERE ${clauses.joinToString(" AND ")}
            ORDER BY occurred_at DESC, activity_id DESC LIMIT ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, workspaceId.value)
                statement.setBoolean(3, includeDraft)
                var index = 4
                if (before != null) { statement.setInstant(index++, before); statement.setUuid(index++, beforeId!!) }
                if (actorId != null) statement.setString(index++, actorId)
                if (credentialSurface != null) statement.setString(index++, credentialSurface.name)
                if (agentClientId != null) statement.setString(index++, agentClientId.value)
                statement.setInt(index, limit)
            },
        ) { row -> cc.monomer.dashboard.api.DashboardChange(
            row.getUuid("activity_id"), row.getUuid("dashboard_id"), row.getBoolean("deleted"), row.getString("title"), row.getInstant("occurred_at"), row.getString("actor_id"),
            row.getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
            row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
            cc.monomer.dashboard.api.DashboardChangeKind.valueOf(row.getString("change_kind")),
        ) }
    }

    override suspend fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient> = read {
        require(scanLimit in 1..cc.monomer.identity.api.RecentAgentClient.MAX_SCAN)
        jdbc.queryMany(
            """
            SELECT agent_client_id, max(seen_at) AS last_seen_at FROM (
                (SELECT agent_client_id, occurred_at AS seen_at FROM dashboard.definition_changes
                  WHERE workspace_id = ? ORDER BY occurred_at DESC, change_id DESC LIMIT ?)
                UNION ALL
                (SELECT last_edit_agent_client_id, last_edited_at FROM dashboard.drafts
                  WHERE workspace_id = ? AND last_edited_at IS NOT NULL ORDER BY last_edited_at DESC, dashboard_id DESC LIMIT ?)
                UNION ALL
                (SELECT agent_client_id, updated_at FROM dashboard.query_runs
                  WHERE workspace_id = ? AND state <> 'RUNNING' ORDER BY updated_at DESC, query_run_id DESC LIMIT ?)
            ) recent
            WHERE agent_client_id IS NOT NULL
            GROUP BY agent_client_id
            """.trimIndent(),
            { statement ->
                var index = 1
                repeat(3) {
                    statement.setUuid(index++, workspaceId.value)
                    statement.setInt(index++, scanLimit)
                }
            },
        ) { row -> cc.monomer.identity.api.RecentAgentClient(
            cc.monomer.identity.api.AgentClientId(row.getString("agent_client_id")),
            row.getInstant("last_seen_at"),
        ) }
    }

    override suspend fun readSessions(
        workspaceId: WorkspaceId, live: Boolean, before: Instant?, beforeId: Uuid?, actorId: String?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int, watchedSessionIds: Set<Uuid>,
    ): List<cc.monomer.dashboard.api.DashboardSessionActivity> = read {
        require(limit in 1..101 && watchedSessionIds.size <= 100 && (before == null) == (beforeId == null))
        val watched = watchedSessionIds.isNotEmpty()
        // A Session's state comes from the latest run of each query; an older run of the same query is superseded.
        val latestRunning = "SELECT 1 FROM dashboard.query_runs other WHERE other.workspace_id = run.workspace_id " +
            "AND other.viewer_session_id = run.viewer_session_id AND other.state = 'RUNNING' AND NOT EXISTS (" +
            "SELECT 1 FROM dashboard.query_runs newer WHERE newer.workspace_id = other.workspace_id " +
            "AND newer.viewer_session_id = other.viewer_session_id AND newer.query_name = other.query_name " +
            "AND (newer.created_at, newer.query_run_id) > (other.created_at, other.query_run_id))"
        // A recent Session is found at its representative run: its latest-updated run, when no latest run is running.
        val chosen = when {
            watched -> "SELECT run.viewer_session_id AS session_id FROM dashboard.query_runs run " +
                "WHERE run.workspace_id = ? AND run.viewer_session_id = ANY (?) GROUP BY run.viewer_session_id"
            live -> "SELECT run.viewer_session_id AS session_id FROM dashboard.query_runs run " +
                "WHERE run.workspace_id = ? AND run.state = 'RUNNING' AND run.viewer_session_id IS NOT NULL " +
                "AND NOT EXISTS (SELECT 1 FROM dashboard.query_runs newer WHERE newer.workspace_id = run.workspace_id " +
                "AND newer.viewer_session_id = run.viewer_session_id AND newer.query_name = run.query_name " +
                "AND (newer.created_at, newer.query_run_id) > (run.created_at, run.query_run_id)) GROUP BY run.viewer_session_id"
            else -> "SELECT run.viewer_session_id AS session_id FROM dashboard.query_runs run " +
                "WHERE run.workspace_id = ? AND run.viewer_session_id IS NOT NULL AND run.state <> 'RUNNING' " +
                "AND NOT EXISTS (SELECT 1 FROM dashboard.query_runs other WHERE other.workspace_id = run.workspace_id " +
                "AND other.viewer_session_id = run.viewer_session_id " +
                "AND (other.updated_at, other.query_run_id) > (run.updated_at, run.query_run_id)) " +
                "AND NOT EXISTS ($latestRunning)" +
                (if (before != null) " AND (run.updated_at, run.viewer_session_id) < (?, ?)" else "") +
                openerFilter(actorId, credentialSurface, agentClientId) +
                " ORDER BY run.updated_at DESC, run.viewer_session_id DESC LIMIT ?"
        }
        val key = if (live) "members.started_at" else "span.updated_at"
        val outer = mutableListOf("TRUE")
        if (!watched && live) {
            outer += "members.running_count > 0"
            if (before != null) outer += "(members.started_at, span.session_id) < (?, ?)"
            if (actorId != null) outer += "span.actor_id = ?"
            if (credentialSurface != null) outer += "span.credential_surface = ?"
            if (agentClientId != null) outer += "span.agent_client_id = ?"
        }
        jdbc.queryMany(
            """
            WITH chosen AS ($chosen),
            span AS (
                SELECT run.viewer_session_id AS session_id, max(run.updated_at) AS updated_at,
                    (array_agg(run.actor_id ORDER BY run.created_at, run.query_run_id))[1] AS actor_id,
                    (array_agg(run.credential_surface ORDER BY run.created_at, run.query_run_id))[1] AS credential_surface,
                    (array_agg(run.agent_client_id ORDER BY run.created_at, run.query_run_id))[1] AS agent_client_id
                FROM dashboard.query_runs run JOIN chosen ON chosen.session_id = run.viewer_session_id
                WHERE run.workspace_id = ?
                GROUP BY run.viewer_session_id
            ),
            latest AS (
                SELECT DISTINCT ON (run.viewer_session_id, run.query_name)
                    run.viewer_session_id AS session_id, run.state, run.failure_code, run.created_at, run.updated_at, run.query_run_id
                FROM dashboard.query_runs run JOIN chosen ON chosen.session_id = run.viewer_session_id
                WHERE run.workspace_id = ?
                ORDER BY run.viewer_session_id, run.query_name, run.created_at DESC, run.query_run_id DESC
            ),
            members AS (
                SELECT session_id, count(*)::int AS query_count,
                    count(*) FILTER (WHERE state = 'RUNNING')::int AS running_count,
                    count(*) FILTER (WHERE state = 'FAILED')::int AS failed_count,
                    count(*) FILTER (WHERE state = 'CANCELLED')::int AS cancelled_count,
                    coalesce(min(created_at) FILTER (WHERE state = 'RUNNING'), min(created_at)) AS started_at,
                    (array_agg(failure_code ORDER BY updated_at, query_run_id) FILTER (WHERE state = 'FAILED'))[1] AS failure_code
                FROM latest GROUP BY session_id
            )
            SELECT span.session_id, session.dashboard_id, dashboard.definition -> 'meta' ->> 'title' AS title,
                members.started_at, span.updated_at, span.actor_id, span.credential_surface, span.agent_client_id,
                members.query_count, members.running_count, members.failed_count, members.cancelled_count, members.failure_code,
                EXISTS (SELECT 1 FROM dashboard.schedule_occurrences occurrence
                    WHERE occurrence.workspace_id = session.workspace_id AND occurrence.session_id = session.session_id) AS scheduled,
                EXISTS (SELECT 1 FROM dashboard.command_receipts receipt
                    WHERE receipt.workspace_id = session.workspace_id AND receipt.session_id = session.session_id
                    AND receipt.command_kind = 'RESOLVE_PUBLIC_SESSION') AS public_reader
            FROM span
            JOIN members ON members.session_id = span.session_id
            JOIN dashboard.sessions session ON session.workspace_id = ? AND session.session_id = span.session_id
            JOIN dashboard.dashboards dashboard ON dashboard.workspace_id = session.workspace_id AND dashboard.dashboard_id = session.dashboard_id
            WHERE ${outer.joinToString(" AND ")}
            ORDER BY $key DESC, span.session_id DESC
            ${if (!watched && live) "LIMIT ?" else ""}
            """.trimIndent(),
            { statement ->
                var index = 1
                statement.setUuid(index++, workspaceId.value)
                when {
                    watched -> statement.setArray(index++, statement.connection.createArrayOf("uuid", watchedSessionIds.map { it.toJavaUuid() }.toTypedArray()))
                    live -> Unit
                    else -> {
                        if (before != null) { statement.setInstant(index++, before); statement.setUuid(index++, beforeId!!) }
                        if (actorId != null) statement.setString(index++, actorId)
                        if (credentialSurface != null) statement.setString(index++, credentialSurface.name)
                        if (agentClientId != null) statement.setString(index++, agentClientId.value)
                        statement.setInt(index++, limit)
                    }
                }
                repeat(3) { statement.setUuid(index++, workspaceId.value) }
                if (!watched && live) {
                    if (before != null) { statement.setInstant(index++, before); statement.setUuid(index++, beforeId!!) }
                    if (actorId != null) statement.setString(index++, actorId)
                    if (credentialSurface != null) statement.setString(index++, credentialSurface.name)
                    if (agentClientId != null) statement.setString(index++, agentClientId.value)
                    statement.setInt(index, limit)
                }
            },
        ) { row ->
            val actor = row.getString("actor_id")
            cc.monomer.dashboard.api.DashboardSessionActivity(
                sessionId = row.getUuid("session_id"), dashboardId = row.getUuid("dashboard_id"), title = row.getString("title"),
                origin = when {
                    actor != null -> cc.monomer.dashboard.api.DashboardSessionOrigin.MEMBER
                    row.getBoolean("scheduled") -> cc.monomer.dashboard.api.DashboardSessionOrigin.SCHEDULE
                    row.getBoolean("public_reader") -> cc.monomer.dashboard.api.DashboardSessionOrigin.PUBLIC_READER
                    else -> cc.monomer.dashboard.api.DashboardSessionOrigin.UNRECORDED
                },
                queryCount = row.getInt("query_count"), runningCount = row.getInt("running_count"),
                failedCount = row.getInt("failed_count"), cancelledCount = row.getInt("cancelled_count"),
                startedAt = row.getInstant("started_at"), updatedAt = row.getInstant("updated_at"), actorId = actor,
                credentialSurface = row.getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
                agentClientId = row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
                failureCode = row.getString("failure_code"),
            )
        }
    }

    /**
     * A Session reads its surface and agent app from its first run, the one that opened it, so the recent filters match
     * the row's label even when a later run came through another path.
     */
    private fun openerFilter(actorId: String?, credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?): String {
        if (actorId == null && credentialSurface == null && agentClientId == null) return ""
        val conditions = listOfNotNull(
            "opener.actor_id = ?".takeIf { actorId != null },
            "opener.credential_surface = ?".takeIf { credentialSurface != null },
            "opener.agent_client_id = ?".takeIf { agentClientId != null },
        )
        return " AND EXISTS (SELECT 1 FROM (SELECT first.actor_id, first.credential_surface, first.agent_client_id FROM dashboard.query_runs first " +
            "WHERE first.workspace_id = run.workspace_id AND first.viewer_session_id = run.viewer_session_id " +
            "ORDER BY first.created_at, first.query_run_id LIMIT 1) opener WHERE ${conditions.joinToString(" AND ")})"
    }

    override suspend fun readDraftRuns(
        workspaceId: WorkspaceId, live: Boolean, before: Instant?, beforeId: Uuid?, actorId: String?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int, watchedIds: Set<Uuid>,
    ): List<cc.monomer.dashboard.api.DashboardRunActivity> = read {
        require(limit in 1..101 && watchedIds.size <= 100 && (before == null) == (beforeId == null))
        val time = if (live) "run.created_at" else "run.updated_at"
        val clauses = mutableListOf(
            "run.workspace_id = ?",
            "run.viewer_session_id IS NULL",
            "EXISTS (SELECT 1 FROM dashboard.drafts draft WHERE draft.workspace_id = run.workspace_id AND draft.dashboard_id = run.dashboard_id)",
        )
        if (watchedIds.isNotEmpty()) clauses += "run.query_run_id = ANY (?)"
        else {
            clauses += if (live) "run.state = 'RUNNING'" else "run.state <> 'RUNNING'"
            if (before != null) clauses += "($time, run.query_run_id) < (?, ?)"
        }
        if (actorId != null) clauses += "run.actor_id = ?"
        if (credentialSurface != null) clauses += "run.credential_surface = ?"
        if (agentClientId != null) clauses += "run.agent_client_id = ?"
        jdbc.queryMany(
            "SELECT run.query_run_id, run.dashboard_id, dashboard.definition -> 'meta' ->> 'title' AS title, " +
                "run.query_name, run.state, run.created_at, run.updated_at, run.actor_id, run.credential_surface, run.agent_client_id, run.connector_id, run.failure_code " +
                "FROM dashboard.query_runs run JOIN dashboard.dashboards dashboard ON dashboard.workspace_id = run.workspace_id AND dashboard.dashboard_id = run.dashboard_id " +
                "WHERE " + clauses.joinToString(" AND ") + " ORDER BY $time DESC, run.query_run_id DESC LIMIT ?",
            { statement ->
                var index = 1
                statement.setUuid(index++, workspaceId.value)
                if (watchedIds.isNotEmpty()) statement.setArray(index++, statement.connection.createArrayOf("uuid", watchedIds.map { it.toJavaUuid() }.toTypedArray()))
                else if (before != null) { statement.setInstant(index++, before); statement.setUuid(index++, beforeId!!) }
                if (actorId != null) statement.setString(index++, actorId)
                if (credentialSurface != null) statement.setString(index++, credentialSurface.name)
                if (agentClientId != null) statement.setString(index++, agentClientId.value)
                statement.setInt(index, limit)
            },
        ) { row -> cc.monomer.dashboard.api.DashboardRunActivity(
            row.getUuid("query_run_id"), row.getUuid("dashboard_id"), row.getString("title"), row.getString("query_name"),
            row.getString("state"), row.getInstant("created_at"), row.getInstant("updated_at"), row.getString("actor_id"),
            row.getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
            row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
            cc.monomer.connector.api.ConnectorId(row.getUuid("connector_id")), row.getString("failure_code"),
        ) }
    }
}
