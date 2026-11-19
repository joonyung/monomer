@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardScheduleOccurrenceCursor
import cc.monomer.dashboard.api.DashboardScheduleOccurrenceView
import cc.monomer.dashboard.api.DashboardScheduleStatus
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardScheduleCandidate
import cc.monomer.dashboard.application.DashboardScheduleRepository
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

class PostgresDashboardScheduleRepository(
    dataSource: DataSource,
) : DashboardScheduleRepository {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun databaseNow(): Instant = jdbc.databaseNow()

    override fun configureTransactionTimeouts() {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        jdbc.queryOne("SELECT set_config('lock_timeout', '2000ms', true), set_config('statement_timeout', '5000ms', true)", {}, { true })
    }

    override fun lockCurrent(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? =
        jdbc.lockCurrentDashboard(workspaceId, dashboardId)

    override fun lockCursor(candidate: DashboardScheduleCandidate): Instant? = jdbc.queryOne(
        "SELECT next_scheduled_at FROM dashboard.schedule_cursors WHERE workspace_id = ? AND dashboard_id = ? AND schedule_key = ? FOR UPDATE",
        { bindSchedule(it, candidate) }, { it.getInstant("next_scheduled_at") },
    )

    override fun dueCandidates(afterWorkspace: WorkspaceId?, limit: Int): List<DashboardScheduleCandidate> = jdbc.queryMany(
        """WITH due AS (
            SELECT DISTINCT ON (workspace_id) workspace_id, dashboard_id, schedule_key, next_scheduled_at
            FROM dashboard.schedule_cursors WHERE next_scheduled_at <= statement_timestamp()
            ORDER BY workspace_id, next_scheduled_at, dashboard_id, schedule_key
        ) SELECT due.*, current.definition, current.created_at, current.updated_at, current.updated_by, current.credential_surface, current.agent_client_id
          FROM due JOIN dashboard.dashboards current USING (workspace_id, dashboard_id)
          ORDER BY CASE WHEN due.workspace_id > ?::uuid THEN 0 ELSE 1 END, due.workspace_id LIMIT ?""",
        { it.setNullableUuid(1, afterWorkspace?.value); it.setInt(2, limit) },
        { row -> DashboardScheduleCandidate(WorkspaceId(row.getUuid("workspace_id")), DashboardId(row.getUuid("dashboard_id")), row.getString("schedule_key"), row.getInstant("next_scheduled_at"), readDashboard(row)) },
    )

    override fun listSchedules(workspaceId: WorkspaceId, dashboardId: DashboardId): List<DashboardScheduleStatus> =
        jdbc.listScheduleCursors(workspaceId, dashboardId)

    override fun previousInitialRuns(candidate: DashboardScheduleCandidate): Set<QueryRunId> = jdbc.queryMany(
        "SELECT initial_query_runs FROM dashboard.schedule_occurrences WHERE workspace_id = ? AND dashboard_id = ? AND schedule_key = ? AND outcome = 'ACCEPTED' ORDER BY scheduled_at DESC LIMIT 1",
        { bindSchedule(it, candidate) },
        { decodeIdMap(it.getString("initial_query_runs"), ::QueryRunId).values.toSet() },
    ).flatten().toSet()

    override fun insertOccurrence(candidate: DashboardScheduleCandidate, occurrence: DashboardScheduleOccurrenceView) {
        check(jdbc.update("""INSERT INTO dashboard.schedule_occurrences
            (workspace_id, dashboard_id, schedule_key, scheduled_at, decided_at, outcome, reason, definition, parameters, session_id, initial_query_runs)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb)""", {
            bindSchedule(it, candidate)
            it.setInstant(4, occurrence.scheduledAt); it.setInstant(5, occurrence.decidedAt)
            it.setString(6, occurrence.outcome); it.setString(7, occurrence.reason)
            it.setString(8, occurrence.definition.toString()); it.setString(9, occurrence.parameters?.let(::JsonObject)?.toString())
            it.setNullableUuid(10, occurrence.sessionId?.value); it.setString(11, idMapJson(occurrence.initialQueryRuns).toString())
        }) == 1)
    }

    override fun advance(candidate: DashboardScheduleCandidate, nextScheduledAt: Instant) {
        check(jdbc.update("UPDATE dashboard.schedule_cursors SET next_scheduled_at = ? WHERE workspace_id = ? AND dashboard_id = ? AND schedule_key = ? AND next_scheduled_at = ?", {
            it.setInstant(1, nextScheduledAt); it.setUuid(2, candidate.workspaceId.value); it.setUuid(3, candidate.dashboardId.value)
            it.setString(4, candidate.scheduleKey); it.setInstant(5, candidate.scheduledAt)
        }) == 1)
    }

    override fun listOccurrences(workspaceId: WorkspaceId, dashboardId: DashboardId, before: DashboardScheduleOccurrenceCursor?, limit: Int): List<DashboardScheduleOccurrenceView> = jdbc.queryMany(
        "SELECT * FROM dashboard.schedule_occurrences WHERE workspace_id = ? AND dashboard_id = ?" +
            (if (before != null) " AND (scheduled_at, schedule_key) < (?, ?)" else "") + " ORDER BY scheduled_at DESC, schedule_key DESC LIMIT ?",
        {
            it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value)
            if (before != null) { it.setInstant(3, before.scheduledAt); it.setString(4, before.scheduleKey) }
            it.setInt(if (before == null) 3 else 5, limit)
        }, ::readScheduleOccurrence,
    )

    override fun readOccurrence(workspaceId: WorkspaceId, dashboardId: DashboardId, scheduleKey: String, scheduledAt: Instant): DashboardScheduleOccurrenceView? = jdbc.queryOne(
        "SELECT * FROM dashboard.schedule_occurrences WHERE workspace_id = ? AND dashboard_id = ? AND schedule_key = ? AND scheduled_at = ?",
        { it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value); it.setString(3, scheduleKey); it.setInstant(4, scheduledAt) }, ::readScheduleOccurrence,
    )

    private fun readScheduleOccurrence(row: ResultSet) = DashboardScheduleOccurrenceView(
        row.getString("schedule_key"), row.getInstant("scheduled_at"), row.getInstant("decided_at"), row.getString("outcome"), row.getString("reason"),
        dashboardRowJson.parseToJsonElement(row.getString("definition")).jsonObject,
        row.getString("parameters")?.let { dashboardRowJson.parseToJsonElement(it).jsonObject },
        row.getNullableUuid("session_id")?.let(::DashboardSessionId), decodeIdMap(row.getString("initial_query_runs"), ::QueryRunId),
    )

    private fun bindSchedule(statement: PreparedStatement, candidate: DashboardScheduleCandidate) {
        statement.setUuid(1, candidate.workspaceId.value); statement.setUuid(2, candidate.dashboardId.value); statement.setString(3, candidate.scheduleKey)
    }
}
