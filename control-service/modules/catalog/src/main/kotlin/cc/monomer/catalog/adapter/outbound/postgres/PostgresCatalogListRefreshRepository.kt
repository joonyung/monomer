@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogListRefreshJobId
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.domain.CatalogListChildKind
import cc.monomer.catalog.domain.CatalogListOption
import cc.monomer.catalog.domain.CatalogListOptionPage
import cc.monomer.catalog.domain.CatalogListOptionRead
import cc.monomer.catalog.domain.CatalogListParentKind
import cc.monomer.catalog.domain.CatalogListRefreshJob
import cc.monomer.catalog.domain.CatalogListRefreshJobState
import cc.monomer.catalog.application.CatalogListRefreshRepository
import cc.monomer.catalog.domain.CatalogListRefreshTarget
import cc.monomer.catalog.application.CatalogSelectionRepository
import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** PostgreSQL owner for Catalog list refresh jobs and the selection option cache. */
class PostgresCatalogListRefreshRepository(
    dataSource: DataSource,
    private val selectionRepository: CatalogSelectionRepository,
) : CatalogListRefreshRepository {
    private val jdbc = CatalogOwnerJdbc(dataSource)

    override fun lockConnector(workspaceId: WorkspaceId, connectorId: ConnectorId) {
        jdbc.lockConnector(workspaceId, connectorId)
    }

    override fun findListRefreshJob(id: CatalogListRefreshJobId): CatalogListRefreshJob? = jdbc.queryOne(
        "SELECT * FROM catalog.list_refresh_jobs WHERE job_id = ?",
        { it.setObject(1, id.value.toJavaUuid()) },
    ) { it.toListRefreshJob() }

    override fun findActiveListRefreshJob(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        targetKey: String,
    ): CatalogListRefreshJob? = jdbc.queryOne(
        """
        SELECT * FROM catalog.list_refresh_jobs
         WHERE workspace_id = ? AND connector_id = ? AND target_key = ?
           AND state IN ('RUNNING', 'CANCEL_REQUESTED')
         ORDER BY requested_at, job_id
         LIMIT 1
        """.trimIndent(),
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            statement.setString(3, targetKey)
        },
    ) { it.toListRefreshJob() }

    private fun findLatestListRefreshJob(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        targetKey: String,
    ): CatalogListRefreshJob? = jdbc.queryOne(
        """
        SELECT * FROM catalog.list_refresh_jobs
         WHERE workspace_id = ? AND connector_id = ? AND target_key = ?
         ORDER BY requested_at DESC, job_id DESC
         LIMIT 1
        """.trimIndent(),
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            statement.setString(3, targetKey)
        },
    ) { it.toListRefreshJob() }

    override fun findListRefreshJobsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        targetKey: String,
        requestedBefore: Instant?,
        jobIdBefore: CatalogListRefreshJobId?,
        limit: Int,
    ): List<CatalogListRefreshJob> {
        require((requestedBefore == null) == (jobIdBefore == null))
        require(limit > 0)
        val boundaryClause = if (requestedBefore == null) "" else "AND (requested_at, job_id) < (?, ?)"
        return jdbc.queryMany(
            """
            SELECT * FROM catalog.list_refresh_jobs
             WHERE workspace_id = ? AND connector_id = ? AND target_key = ?
               $boundaryClause
             ORDER BY requested_at DESC, job_id DESC
             LIMIT ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, targetKey)
                if (requestedBefore == null) {
                    statement.setInt(4, limit)
                } else {
                    statement.setTimestamp(4, Timestamp.from(requestedBefore))
                    statement.setObject(5, requireNotNull(jobIdBefore).value.toJavaUuid())
                    statement.setInt(6, limit)
                }
            },
        ) { it.toListRefreshJob() }
    }

    override fun findUnownedRunningListRefreshJobs(maximumJobs: Int): List<CatalogListRefreshJob> {
        require(maximumJobs > 0)
        return jdbc.queryMany(
            """
            SELECT * FROM catalog.list_refresh_jobs
             WHERE state = 'RUNNING' AND runner_instance_id IS NULL
             ORDER BY requested_at, job_id
             LIMIT ?
            """.trimIndent(),
            { it.setInt(1, maximumJobs) },
        ) { it.toListRefreshJob() }
    }

    override fun findStaleListRefreshJobs(
        heartbeatBefore: Instant,
        maximumJobs: Int,
    ): List<CatalogListRefreshJob> {
        require(maximumJobs > 0)
        return jdbc.queryMany(
            """
            SELECT * FROM catalog.list_refresh_jobs
             WHERE state IN ('RUNNING', 'CANCEL_REQUESTED')
               AND runner_instance_id IS NOT NULL
               AND heartbeat_at < ?
             ORDER BY heartbeat_at, job_id
             LIMIT ?
            """.trimIndent(),
            { statement ->
                statement.setTimestamp(1, Timestamp.from(heartbeatBefore))
                statement.setInt(2, maximumJobs)
            },
        ) { it.toListRefreshJob() }
    }

    override fun insertListRefreshJob(job: CatalogListRefreshJob) {
        jdbc.requireOwnerTransaction()
        jdbc.update(
            """
            INSERT INTO catalog.list_refresh_jobs
                (job_id, workspace_id, connector_id, parent_kind, catalog_name, schema_name,
                 target_key, child_kind, requested_by, requested_at, state, runner_instance_id,
                 heartbeat_at, active_run_id, failure_code, safe_message, terminal_at, credential_surface, agent_client_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            bindListRefreshJob(statement, job)
        }
    }

    override fun updateListRefreshJob(job: CatalogListRefreshJob): Boolean {
        jdbc.requireOwnerTransaction()
        return updateListRefreshJobRow(job, null) > 0
    }

    override fun claimListRefreshJob(
        jobId: CatalogListRefreshJobId,
        runnerInstanceId: String,
        heartbeatAt: Instant,
    ): CatalogListRefreshJob? {
        jdbc.requireOwnerTransaction()
        require(runnerInstanceId.isNotBlank())
        val current = lockListRefreshJob(jobId) ?: return null
        if (current.state != CatalogListRefreshJobState.RUNNING || current.runnerInstanceId != null) return null
        val claimed = current.claim(runnerInstanceId, heartbeatAt)
        check(updateListRefreshJobRow(claimed, null) > 0) { "Catalog list refresh claim was lost" }
        return claimed
    }

    override fun heartbeatListRefreshJob(
        jobId: CatalogListRefreshJobId,
        runnerInstanceId: String,
        heartbeatAt: Instant,
    ): Boolean {
        jdbc.requireOwnerTransaction()
        return jdbc.update(
            """
            UPDATE catalog.list_refresh_jobs
               SET heartbeat_at = ?
             WHERE job_id = ? AND state IN ('RUNNING', 'CANCEL_REQUESTED')
               AND runner_instance_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setTimestamp(1, Timestamp.from(heartbeatAt))
            statement.setObject(2, jobId.value.toJavaUuid())
            statement.setString(3, runnerInstanceId)
        } > 0
    }

    override fun lockListRefreshJob(jobId: CatalogListRefreshJobId): CatalogListRefreshJob? = jdbc.queryOne(
        "SELECT * FROM catalog.list_refresh_jobs WHERE job_id = ? FOR UPDATE",
        { it.setObject(1, jobId.value.toJavaUuid()) },
    ) { it.toListRefreshJob() }

    override fun failStaleListRefreshJob(
        jobId: CatalogListRefreshJobId,
        now: Instant,
        staleAfter: Duration,
    ): CatalogListRefreshJob? {
        jdbc.requireOwnerTransaction()
        require(!staleAfter.isNegative && !staleAfter.isZero)
        val current = lockListRefreshJob(jobId) ?: return null
        val heartbeat = current.heartbeatAt ?: return current
        if (current.state != CatalogListRefreshJobState.RUNNING &&
            current.state != CatalogListRefreshJobState.CANCEL_REQUESTED
        ) return current
        if (current.runnerInstanceId == null || heartbeat.plus(staleAfter).isAfter(now)) return current
        val terminal = current.completeWithErrors(
            code = "RUNNER_HEARTBEAT_EXPIRED",
            message = "Catalog list refresh runner heartbeat expired",
            at = now,
        )
        check(updateListRefreshJobRow(terminal, current.runnerInstanceId) > 0) {
            "Catalog list refresh ownership changed while terminalizing job"
        }
        return terminal
    }

    override fun setActiveListRefreshRun(
        jobId: CatalogListRefreshJobId,
        runId: ExecutionRunId,
        runnerInstanceId: String,
    ): Boolean {
        jdbc.requireOwnerTransaction()
        return jdbc.update(
            """
            UPDATE catalog.list_refresh_jobs
               SET active_run_id = ?
             WHERE job_id = ? AND state = 'RUNNING' AND runner_instance_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, runId.value.toJavaUuid())
            statement.setObject(2, jobId.value.toJavaUuid())
            statement.setString(3, runnerInstanceId)
        } > 0
    }

    override fun completeListRefreshWithOptions(
        job: CatalogListRefreshJob,
        options: List<CatalogListOption>,
        fetchedAt: Instant,
        runnerInstanceId: String,
    ): Boolean {
        jdbc.requireOwnerTransaction()
        jdbc.lockConnector(job.workspaceId, job.connectorId)
        val current = lockListRefreshJob(job.id) ?: return false
        if (current.state != CatalogListRefreshJobState.RUNNING || current.runnerInstanceId != runnerInstanceId) return false
        check(current.target == job.target) { "Catalog list refresh target changed" }
        val previous = jdbc.queryMany(
            """
            SELECT child_name, relation_kind, available
              FROM catalog.selection_options
             WHERE workspace_id = ? AND connector_id = ? AND target_key = ?
             FOR UPDATE
            """.trimIndent(),
            { statement ->
                statement.setObject(1, job.workspaceId.value.toJavaUuid())
                statement.setObject(2, job.connectorId.value.toJavaUuid())
                statement.setString(3, job.target.key)
            },
        ) { result ->
            CachedOption(
                name = result.getString("child_name"),
                relationKind = result.getString("relation_kind")?.let(RelationKind::valueOf),
                available = result.getBoolean("available"),
            )
        }
        upsertOptionList(job, fetchedAt)
        jdbc.update(
            "DELETE FROM catalog.selection_options WHERE workspace_id = ? AND connector_id = ? AND target_key = ?",
        ) { statement ->
            statement.setObject(1, job.workspaceId.value.toJavaUuid())
            statement.setObject(2, job.connectorId.value.toJavaUuid())
            statement.setString(3, job.target.key)
        }
        val canonicalOptions = options.distinctBy { it.name }.sortedBy { it.name }
        canonicalOptions.forEach { option -> insertOption(job, option.copy(available = true)) }
        if (job.target.childKind != CatalogListChildKind.CATALOG) {
            val selectedNames = selectedUnavailableOptionNames(job.workspaceId, job.connectorId, job.target)
            previous.filter { it.name in selectedNames && canonicalOptions.none { option -> option.name == it.name } }
                .forEach { option ->
                    insertOption(job, CatalogListOption(option.name, option.relationKind, available = false))
                }
        }
        val completed = current.complete(fetchedAt)
        return updateListRefreshJobRow(completed, runnerInstanceId) > 0
    }

    override fun failListRefreshJob(
        jobId: CatalogListRefreshJobId,
        runnerInstanceId: String,
        failureCode: String,
        safeMessage: String,
        terminalAt: Instant,
    ): CatalogListRefreshJob? {
        jdbc.requireOwnerTransaction()
        val current = lockListRefreshJob(jobId) ?: return null
        if (current.runnerInstanceId != runnerInstanceId ||
            (current.state != CatalogListRefreshJobState.RUNNING &&
                current.state != CatalogListRefreshJobState.CANCEL_REQUESTED)
        ) return current
        val failed = current.completeWithErrors(failureCode, safeMessage, terminalAt)
        check(updateListRefreshJobRow(failed, runnerInstanceId) > 0) { "Catalog list refresh failure was lost" }
        return failed
    }

    override fun cancelListRefreshJob(
        jobId: CatalogListRefreshJobId,
        runnerInstanceId: String?,
        terminalAt: Instant,
    ): CatalogListRefreshJob? {
        jdbc.requireOwnerTransaction()
        val current = lockListRefreshJob(jobId) ?: return null
        if (current.runnerInstanceId != runnerInstanceId) return current
        if (current.state != CatalogListRefreshJobState.RUNNING &&
            current.state != CatalogListRefreshJobState.CANCEL_REQUESTED
        ) return current
        val cancelled = current.cancel(terminalAt)
        check(updateListRefreshJobRow(cancelled, runnerInstanceId) > 0) { "Catalog list refresh cancellation was lost" }
        return cancelled
    }

    override fun findListOptions(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        target: CatalogListRefreshTarget,
        page: Int,
        pageSize: Int,
        query: String?,
    ): CatalogListOptionPage {
        require(page >= 1)
        require(pageSize in 1..200)
        val list = jdbc.queryOne(
            """
            SELECT fetched_at FROM catalog.selection_option_lists
             WHERE workspace_id = ? AND connector_id = ? AND target_key = ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, target.key)
            },
        ) { it.getTimestamp("fetched_at")?.toInstant() }
        val filter = query?.trim()?.takeIf(String::isNotEmpty)
        val where = "workspace_id = ? AND connector_id = ? AND target_key = ?" +
            if (filter == null) "" else " AND child_name ILIKE ?"
        val total = jdbc.queryOne(
            "SELECT count(*) AS total FROM catalog.selection_options WHERE $where",
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, target.key)
                if (filter != null) statement.setString(4, filter.containsLikePattern())
            },
        ) { it.getInt("total") } ?: 0
        val options = jdbc.queryMany(
            """
            SELECT child_name, relation_kind, available
              FROM catalog.selection_options
             WHERE $where
             ORDER BY ${if (filter == null) "child_name" else "CASE WHEN child_name = ? THEN 0 ELSE 1 END, child_name"}
             LIMIT ? OFFSET ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, target.key)
                var index = 4
                if (filter != null) statement.setString(index++, filter.containsLikePattern())
                if (filter != null) statement.setString(index++, filter)
                statement.setInt(index++, pageSize)
                statement.setLong(index, (page - 1).toLong() * pageSize)
            },
        ) { result ->
            CatalogListOption(
                name = result.getString("child_name"),
                relationKind = result.getString("relation_kind")?.let(RelationKind::valueOf),
                available = result.getBoolean("available"),
            )
        }
        return CatalogListOptionPage(
            target = target,
            fetchedAt = list,
            options = options,
            totalCount = total,
            page = page,
            pageSize = pageSize,
            latestJob = findLatestListRefreshJob(workspaceId, connectorId, target.key),
        )
    }

    override fun findListOptionsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        target: CatalogListRefreshTarget,
        afterName: String?,
        limit: Int,
    ): CatalogListOptionPage {
        require(limit > 0)
        val fetchedAt = jdbc.queryOne(
            """
            SELECT fetched_at FROM catalog.selection_option_lists
             WHERE workspace_id = ? AND connector_id = ? AND target_key = ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, target.key)
            },
        ) { it.getTimestamp("fetched_at")?.toInstant() }
        val afterClause = if (afterName == null) "" else "AND child_name > ?"
        val options = jdbc.queryMany(
            """
            SELECT child_name, relation_kind, available
              FROM catalog.selection_options
             WHERE workspace_id = ? AND connector_id = ? AND target_key = ?
               $afterClause
             ORDER BY child_name
             LIMIT ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, target.key)
                if (afterName == null) {
                    statement.setInt(4, limit)
                } else {
                    statement.setString(4, afterName)
                    statement.setInt(5, limit)
                }
            },
        ) { result ->
            CatalogListOption(
                name = result.getString("child_name"),
                relationKind = result.getString("relation_kind")?.let(RelationKind::valueOf),
                available = result.getBoolean("available"),
            )
        }
        return CatalogListOptionPage(
            target = target,
            fetchedAt = fetchedAt,
            options = options,
            totalCount = options.size,
            page = 1,
            pageSize = limit,
            latestJob = findLatestListRefreshJob(workspaceId, connectorId, target.key),
        )
    }

    override fun findListOption(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        target: CatalogListRefreshTarget,
        name: String,
    ): CatalogListOptionRead = jdbc.queryOne(
        """
        SELECT lists.fetched_at, options.child_name, options.relation_kind, options.available
          FROM catalog.selection_option_lists lists
          LEFT JOIN catalog.selection_options options
            ON options.workspace_id = lists.workspace_id
           AND options.connector_id = lists.connector_id
           AND options.target_key = lists.target_key
           AND options.child_name = ?
         WHERE lists.workspace_id = ? AND lists.connector_id = ? AND lists.target_key = ?
        """.trimIndent(),
        { statement ->
            statement.setString(1, name)
            statement.setObject(2, workspaceId.value.toJavaUuid())
            statement.setObject(3, connectorId.value.toJavaUuid())
            statement.setString(4, target.key)
        },
    ) { result ->
        CatalogListOptionRead(
            fetchedAt = result.timestamp("fetched_at"),
            option = result.getString("child_name")?.let { childName ->
                CatalogListOption(
                    name = childName,
                    relationKind = result.getString("relation_kind")?.let(RelationKind::valueOf),
                    available = result.getBoolean("available"),
                )
            },
        )
    } ?: CatalogListOptionRead(fetchedAt = null, option = null)

    private fun updateListRefreshJobRow(job: CatalogListRefreshJob, ownership: String?): Int {
        val sql = if (ownership == null) {
            """
            UPDATE catalog.list_refresh_jobs
               SET state = ?, runner_instance_id = ?, heartbeat_at = ?, active_run_id = ?,
                   failure_code = ?, safe_message = ?, terminal_at = ?
             WHERE job_id = ? AND state IN ('RUNNING', 'CANCEL_REQUESTED')
            """.trimIndent()
        } else {
            """
            UPDATE catalog.list_refresh_jobs
               SET state = ?, runner_instance_id = ?, heartbeat_at = ?, active_run_id = ?,
                   failure_code = ?, safe_message = ?, terminal_at = ?
             WHERE job_id = ? AND state IN ('RUNNING', 'CANCEL_REQUESTED')
               AND runner_instance_id = ?
            """.trimIndent()
        }
        return jdbc.update(sql) { statement ->
            statement.setString(1, job.state.name)
            statement.setString(2, job.runnerInstanceId)
            statement.setInstant(3, job.heartbeatAt)
            statement.setObject(4, job.activeRunId?.value?.toJavaUuid())
            statement.setString(5, job.failureCode)
            statement.setString(6, job.safeMessage)
            statement.setInstant(7, job.terminalAt)
            statement.setObject(8, job.id.value.toJavaUuid())
            if (ownership != null) statement.setString(9, ownership)
        }
    }

    private fun bindListRefreshJob(statement: PreparedStatement, job: CatalogListRefreshJob) {
        statement.setObject(1, job.id.value.toJavaUuid())
        statement.setObject(2, job.workspaceId.value.toJavaUuid())
        statement.setObject(3, job.connectorId.value.toJavaUuid())
        statement.setString(4, job.target.parentKind.name)
        statement.setString(5, job.target.catalogName)
        statement.setString(6, job.target.schemaName)
        statement.setString(7, job.target.key)
        statement.setString(8, job.target.childKind.name)
        statement.setObject(9, job.requestedBy.value.toJavaUuid())
        statement.setTimestamp(10, Timestamp.from(job.requestedAt))
        statement.setString(11, job.state.name)
        statement.setString(12, job.runnerInstanceId)
        statement.setInstant(13, job.heartbeatAt)
        statement.setObject(14, job.activeRunId?.value?.toJavaUuid())
        statement.setString(15, job.failureCode)
        statement.setString(16, job.safeMessage)
        statement.setInstant(17, job.terminalAt)
        statement.setString(18, job.credentialSurface?.name)
        statement.setString(19, job.agentClientId?.value)
    }

    private fun upsertOptionList(job: CatalogListRefreshJob, fetchedAt: Instant) {
        jdbc.update(
            """
            INSERT INTO catalog.selection_option_lists
                (workspace_id, connector_id, parent_kind, catalog_name, schema_name,
                 target_key, child_kind, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (workspace_id, connector_id, target_key) DO UPDATE SET
                parent_kind = EXCLUDED.parent_kind,
                catalog_name = EXCLUDED.catalog_name,
                schema_name = EXCLUDED.schema_name,
                child_kind = EXCLUDED.child_kind,
                fetched_at = EXCLUDED.fetched_at
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, job.workspaceId.value.toJavaUuid())
            statement.setObject(2, job.connectorId.value.toJavaUuid())
            statement.setString(3, job.target.parentKind.name)
            statement.setString(4, job.target.catalogName)
            statement.setString(5, job.target.schemaName)
            statement.setString(6, job.target.key)
            statement.setString(7, job.target.childKind.name)
            statement.setTimestamp(8, Timestamp.from(fetchedAt))
        }
    }

    private fun insertOption(job: CatalogListRefreshJob, option: CatalogListOption) {
        require((job.target.childKind == CatalogListChildKind.RELATION) == (option.relationKind != null))
        jdbc.update(
            """
            INSERT INTO catalog.selection_options
                (workspace_id, connector_id, target_key, child_name, relation_kind, available)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, job.workspaceId.value.toJavaUuid())
            statement.setObject(2, job.connectorId.value.toJavaUuid())
            statement.setString(3, job.target.key)
            statement.setString(4, option.name)
            statement.setString(5, option.relationKind?.name)
            statement.setBoolean(6, option.available)
        }
    }

    private fun selectedUnavailableOptionNames(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        target: CatalogListRefreshTarget,
    ): Set<String> {
        val targetCatalogName = target.catalogName
        val selectionEntry = when (targetCatalogName) {
            null -> CatalogIngestEntry.Connector(connectorId)
            else -> CatalogIngestEntry.Catalog(connectorId, targetCatalogName)
        }
        return when (target.childKind) {
            CatalogListChildKind.CATALOG -> error("Catalog options are replaced by each complete refresh")
            CatalogListChildKind.SCHEMA,
            CatalogListChildKind.RELATION,
            -> {
                val selection = selectionRepository.currentSelection(workspaceId, connectorId, selectionEntry)?.selection
                    ?: return emptySet()
                when (target.childKind) {
                    CatalogListChildKind.SCHEMA -> selection.selectedSchemas
                        .map { it.schemaName }
                        .toSet()
                    CatalogListChildKind.RELATION -> selection.selectedSchemas
                        .firstOrNull { it.schemaName == target.schemaName }
                        ?.relations
                        ?.let { it as? CatalogRelationSelection.SelectedRelations }
                        ?.relationNames
                        .orEmpty()
                    CatalogListChildKind.CATALOG -> error("Catalog child kind was handled above")
                }
            }
        }
    }

    private data class CachedOption(
        val name: String,
        val relationKind: RelationKind?,
        val available: Boolean,
    )

    private fun ResultSet.toListRefreshJob(): CatalogListRefreshJob {
        val target = when (CatalogListParentKind.valueOf(getString("parent_kind"))) {
            CatalogListParentKind.CONNECTOR -> CatalogListRefreshTarget.Connector(
                CatalogListChildKind.valueOf(getString("child_kind")),
            )
            CatalogListParentKind.CATALOG -> CatalogListRefreshTarget.Catalog(
                getString("catalog_name"),
            )
            CatalogListParentKind.SCHEMA -> CatalogListRefreshTarget.Schema(
                getString("catalog_name"),
                getString("schema_name"),
            )
        }
        check(target.key == getString("target_key")) { "Catalog list refresh target key does not match its address" }
        return CatalogListRefreshJob(
            id = CatalogListRefreshJobId(uuid("job_id")),
            workspaceId = WorkspaceId(uuid("workspace_id")),
            connectorId = ConnectorId(uuid("connector_id")),
            target = target,
            requestedBy = PrincipalId(uuid("requested_by")),
            credentialSurface = getString("credential_surface")?.let(CredentialSurface::valueOf),
            agentClientId = getString("agent_client_id")?.let { AgentClientId(it) },
            requestedAt = timestamp("requested_at"),
            state = CatalogListRefreshJobState.valueOf(getString("state")),
            runnerInstanceId = getString("runner_instance_id"),
            heartbeatAt = getTimestamp("heartbeat_at")?.toInstant(),
            activeRunId = getObject("active_run_id", UUID::class.java)?.toKotlinUuid()?.let(::ExecutionRunId),
            failureCode = getString("failure_code"),
            safeMessage = getString("safe_message"),
            terminalAt = getTimestamp("terminal_at")?.toInstant(),
        )
    }
}

private fun String.containsLikePattern(): String =
    "%" + replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
