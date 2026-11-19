@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogIngestTaskId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogRelationshipKind
import cc.monomer.catalog.api.CatalogSearchAddress
import cc.monomer.catalog.api.CatalogSearchFields
import cc.monomer.catalog.api.CatalogSearchProjectionPort
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogStableObjectIdentityCodec
import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.application.CATALOG_INGEST_JOB_TASK_PROJECTION_LIMIT
import cc.monomer.catalog.domain.CatalogIngestErrorPage
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobPage
import cc.monomer.catalog.domain.CatalogIngestJobState
import cc.monomer.catalog.domain.CatalogIngestJobSummary
import cc.monomer.catalog.application.CatalogIngestRepository
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskState
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogMetadataObservation
import cc.monomer.catalog.application.CatalogSelectionRepository
import cc.monomer.catalog.domain.digestObservation
import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** PostgreSQL owner for Catalog ingest jobs, their task tree, and the current objects they write. */
class PostgresCatalogIngestRepository(
    dataSource: DataSource,
    objectMapper: ObjectMapper,
    private val searchProjection: CatalogSearchProjectionPort,
    private val selectionRepository: CatalogSelectionRepository,
) : CatalogIngestRepository {
    private val jdbc = CatalogOwnerJdbc(dataSource)
    private val selectionCodec = CatalogSelectionJsonCodec(objectMapper)

    override fun removeCurrentCatalogSubtree(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String,
    ) {
        jdbc.requireOwnerTransaction()
        jdbc.queryMany(
            """
                SELECT * FROM catalog.current_objects
                 WHERE workspace_id = ? AND connector_id = ?
                   AND catalog_name = ?
                 ORDER BY object_kind, schema_name, object_name, member_name
                 FOR UPDATE
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, catalogName)
            },
        ) { it.toCurrentObjectRow() }.forEach { row ->
            deleteCurrentSubtree(workspaceId, connectorId, row)
        }
    }

    override fun findIngestJob(id: CatalogIngestJobId): CatalogIngestJob? = jdbc.queryOne(
        "SELECT * FROM catalog.ingest_jobs WHERE job_id = ?",
        { it.setObject(1, id.value.toJavaUuid()) },
    ) { it.toJob() }

    override fun findActiveIngestJob(
        workspaceId: WorkspaceId,
        entryKey: String,
    ): CatalogIngestJob? = jdbc.queryOne(
        """
        SELECT * FROM catalog.ingest_jobs
         WHERE workspace_id = ? AND entry_key = ?
           AND state IN ('RUNNING', 'CANCEL_REQUESTED')
         ORDER BY requested_at
         LIMIT 1
        """.trimIndent(),
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setString(2, entryKey)
        },
        ) { it.toJob() }

    override fun findIngestJobPage(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        entryKey: String,
        page: Int,
        pageSize: Int,
    ): CatalogIngestJobPage {
        require(page >= 1)
        require(pageSize in 1..200)
        val totalCount = jdbc.queryOne(
            """
            SELECT count(*) AS total
              FROM catalog.ingest_jobs
             WHERE workspace_id = ? AND connector_id = ? AND entry_key = ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, entryKey)
            },
        ) { it.getInt("total") } ?: 0
        val jobs = jdbc.queryMany(
            """
            SELECT jobs.*,
                   count(tasks.task_id) FILTER (
                     WHERE tasks.target_kind = 'RELATION' AND tasks.state = 'SUCCEEDED'
                   ) AS refreshed_relation_count,
                   count(tasks.task_id) FILTER (WHERE tasks.state = 'RUNNING') AS running_task_count,
                   count(tasks.task_id) FILTER (WHERE tasks.state = 'FAILED') AS error_count
              FROM catalog.ingest_jobs jobs
              LEFT JOIN catalog.ingest_tasks tasks ON tasks.job_id = jobs.job_id
             WHERE jobs.workspace_id = ? AND jobs.connector_id = ? AND jobs.entry_key = ?
             GROUP BY jobs.job_id
             ORDER BY jobs.requested_at DESC, jobs.job_id DESC
             LIMIT ? OFFSET ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, entryKey)
                statement.setInt(4, pageSize)
                statement.setLong(5, (page - 1).toLong() * pageSize)
            },
        ) { result ->
            CatalogIngestJobSummary(
                job = result.toJob(),
                refreshedRelationCount = result.getInt("refreshed_relation_count"),
                runningTaskCount = result.getInt("running_task_count"),
                errorCount = result.getInt("error_count"),
            )
        }
        return CatalogIngestJobPage(jobs, totalCount, page, pageSize)
    }

    override fun findIngestJobsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        entryKey: String,
        requestedBefore: Instant?,
        jobIdBefore: CatalogIngestJobId?,
        limit: Int,
    ): List<CatalogIngestJobSummary> {
        require((requestedBefore == null) == (jobIdBefore == null))
        require(limit > 0)
        val boundaryClause = if (requestedBefore == null) "" else "AND (jobs.requested_at, jobs.job_id) < (?, ?)"
        return jdbc.queryMany(
            """
            SELECT jobs.*,
                   count(tasks.task_id) FILTER (
                     WHERE tasks.target_kind = 'RELATION' AND tasks.state = 'SUCCEEDED'
                   ) AS refreshed_relation_count,
                   count(tasks.task_id) FILTER (WHERE tasks.state = 'RUNNING') AS running_task_count,
                   count(tasks.task_id) FILTER (WHERE tasks.state = 'FAILED') AS error_count
              FROM catalog.ingest_jobs jobs
              LEFT JOIN catalog.ingest_tasks tasks ON tasks.job_id = jobs.job_id
             WHERE jobs.workspace_id = ? AND jobs.connector_id = ? AND jobs.entry_key = ?
               $boundaryClause
             GROUP BY jobs.job_id
             ORDER BY jobs.requested_at DESC, jobs.job_id DESC
             LIMIT ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setString(3, entryKey)
                if (requestedBefore == null) {
                    statement.setInt(4, limit)
                } else {
                    statement.setTimestamp(4, Timestamp.from(requestedBefore))
                    statement.setObject(5, requireNotNull(jobIdBefore).value.toJavaUuid())
                    statement.setInt(6, limit)
                }
            },
        ) { result ->
            CatalogIngestJobSummary(
                job = result.toJob(),
                refreshedRelationCount = result.getInt("refreshed_relation_count"),
                runningTaskCount = result.getInt("running_task_count"),
                errorCount = result.getInt("error_count"),
            )
        }
    }

    override fun findIngestJobSummary(id: CatalogIngestJobId): CatalogIngestJobSummary? = jdbc.queryOne(
        """
        SELECT jobs.*,
               count(tasks.task_id) FILTER (
                 WHERE tasks.target_kind = 'RELATION' AND tasks.state = 'SUCCEEDED'
               ) AS refreshed_relation_count,
               count(tasks.task_id) FILTER (WHERE tasks.state = 'RUNNING') AS running_task_count,
               count(tasks.task_id) FILTER (WHERE tasks.state = 'FAILED') AS error_count
          FROM catalog.ingest_jobs jobs
          LEFT JOIN catalog.ingest_tasks tasks ON tasks.job_id = jobs.job_id
         WHERE jobs.job_id = ?
         GROUP BY jobs.job_id
        """.trimIndent(),
        { it.setObject(1, id.value.toJavaUuid()) },
    ) { result ->
        CatalogIngestJobSummary(
            job = result.toJob(),
            refreshedRelationCount = result.getInt("refreshed_relation_count"),
            runningTaskCount = result.getInt("running_task_count"),
            errorCount = result.getInt("error_count"),
        )
    }

    override fun findIngestErrorPage(
        jobId: CatalogIngestJobId,
        page: Int,
        pageSize: Int,
    ): CatalogIngestErrorPage {
        require(page >= 1)
        require(pageSize in 1..200)
        val totalCount = jdbc.queryOne(
            "SELECT count(*) AS total FROM catalog.ingest_tasks WHERE job_id = ? AND state = 'FAILED'",
            { it.setObject(1, jobId.value.toJavaUuid()) },
        ) { it.getInt("total") } ?: 0
        val errors = jdbc.queryMany(
            """
            SELECT * FROM catalog.ingest_tasks
             WHERE job_id = ? AND state = 'FAILED'
             ORDER BY terminal_at, task_id
             LIMIT ? OFFSET ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, jobId.value.toJavaUuid())
                statement.setInt(2, pageSize)
                statement.setLong(3, (page - 1).toLong() * pageSize)
            },
        ) { it.toTask() }
        return CatalogIngestErrorPage(errors, totalCount, page, pageSize)
    }

    override fun findIngestErrorsAfter(
        jobId: CatalogIngestJobId,
        terminalAfter: Instant?,
        taskIdAfter: CatalogIngestTaskId?,
        limit: Int,
    ): List<CatalogIngestTask> {
        require((terminalAfter == null) == (taskIdAfter == null))
        require(limit > 0)
        val boundaryClause = if (terminalAfter == null) "" else "AND (terminal_at, task_id) > (?, ?)"
        return jdbc.queryMany(
            """
            SELECT * FROM catalog.ingest_tasks
             WHERE job_id = ? AND state = 'FAILED'
               $boundaryClause
             ORDER BY terminal_at, task_id
             LIMIT ?
            """.trimIndent(),
            { statement ->
                statement.setObject(1, jobId.value.toJavaUuid())
                if (terminalAfter == null) {
                    statement.setInt(2, limit)
                } else {
                    statement.setTimestamp(2, Timestamp.from(terminalAfter))
                    statement.setObject(3, requireNotNull(taskIdAfter).value.toJavaUuid())
                    statement.setInt(4, limit)
                }
            },
        ) { it.toTask() }
    }

    override fun findUnownedRunningIngestJobs(maximumJobs: Int): List<CatalogIngestJob> {
        require(maximumJobs > 0)
        return jdbc.queryMany(
            """
            SELECT * FROM catalog.ingest_jobs
             WHERE state = 'RUNNING' AND runner_instance_id IS NULL
             ORDER BY requested_at, job_id
             LIMIT ?
            """.trimIndent(),
            { statement -> statement.setInt(1, maximumJobs) },
        ) { it.toJob() }
    }

    override fun findStaleIngestJobs(
        heartbeatBefore: Instant,
        maximumJobs: Int,
    ): List<CatalogIngestJob> {
        require(maximumJobs > 0)
        return jdbc.queryMany(
            """
            SELECT * FROM catalog.ingest_jobs
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
        ) { it.toJob() }
    }

    override fun insertIngestJob(job: CatalogIngestJob) {
        jdbc.requireOwnerTransaction()
        jdbc.update(
            """
            INSERT INTO catalog.ingest_jobs
                (job_id, workspace_id, connector_id, selection,
                 entry_key, entry_kind, entry_catalog_name,
                 requested_by, requested_at, state, runner_instance_id, heartbeat_at, terminal_at, credential_surface, agent_client_id)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, job.id.value.toJavaUuid())
            statement.setObject(2, job.workspaceId.value.toJavaUuid())
            statement.setObject(3, job.connectorId.value.toJavaUuid())
            statement.setString(4, selectionCodec.encodeSelection(job.selection))
            statement.setString(5, job.entry.key)
            statement.setString(6, job.entry.kindName())
            statement.setString(7, job.entry.catalogName())
            statement.setObject(8, job.requestedBy.value.toJavaUuid())
            statement.setTimestamp(9, Timestamp.from(job.requestedAt))
            statement.setString(10, job.state.name)
            statement.setString(11, job.runnerInstanceId)
            statement.setInstant(12, job.heartbeatAt)
            statement.setInstant(13, job.terminalAt)
            statement.setString(14, job.credentialSurface?.name)
            statement.setString(15, job.agentClientId?.value)
        }
    }

    /** Non-runner command update, used for admission and an actor's cancellation request. */
    override fun updateIngestJob(job: CatalogIngestJob): Boolean {
        jdbc.requireOwnerTransaction()
        return updateJob(job, ownership = null) > 0
    }

    /** Every runner-owned mutation carries both state and instance ownership in SQL. */
    override fun updateOwnedIngestJob(job: CatalogIngestJob, runnerInstanceId: String): Boolean {
        jdbc.requireOwnerTransaction()
        return updateJob(job, runnerInstanceId) > 0
    }

    override fun claimIngestJob(
        jobId: CatalogIngestJobId,
        runnerInstanceId: String,
        heartbeatAt: Instant,
    ): CatalogIngestJob? {
        jdbc.requireOwnerTransaction()
        require(runnerInstanceId.isNotBlank())
        val current = lockIngestJob(jobId) ?: return null
        if (current.state != CatalogIngestJobState.RUNNING || current.runnerInstanceId != null) return null
        val claimed = current.claim(runnerInstanceId, heartbeatAt)
        check(updateJob(claimed, ownership = null) > 0) { "Catalog job claim was lost" }
        insertIngestTask(
            CatalogIngestTask.start(
                job = claimed,
                parentTaskId = null,
                target = claimed.entry.taskTarget(),
                at = heartbeatAt,
            ),
        )
        return claimed
    }

    override fun heartbeatIngestJob(
        jobId: CatalogIngestJobId,
        runnerInstanceId: String,
        heartbeatAt: Instant,
    ): Boolean {
        jdbc.requireOwnerTransaction()
        return jdbc.update(
            """
            UPDATE catalog.ingest_jobs
               SET heartbeat_at = ?
             WHERE job_id = ? AND state IN ('RUNNING', 'CANCEL_REQUESTED') AND runner_instance_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setTimestamp(1, Timestamp.from(heartbeatAt))
            statement.setObject(2, jobId.value.toJavaUuid())
            statement.setString(3, runnerInstanceId)
        } > 0
    }

    override fun lockIngestJob(jobId: CatalogIngestJobId): CatalogIngestJob? {
        jdbc.requireOwnerTransaction()
        return jdbc.queryOne(
            "SELECT * FROM catalog.ingest_jobs WHERE job_id = ? FOR UPDATE",
            { it.setObject(1, jobId.value.toJavaUuid()) },
        ) { it.toJob() }
    }

    override fun failStaleIngestJob(
        jobId: CatalogIngestJobId,
        now: Instant,
        staleAfter: Duration,
    ): CatalogIngestJob? {
        jdbc.requireOwnerTransaction()
        require(!staleAfter.isNegative && !staleAfter.isZero)
        val current = lockIngestJob(jobId) ?: return null
        val heartbeat = current.heartbeatAt ?: return current
        if (current.state != CatalogIngestJobState.RUNNING && current.state != CatalogIngestJobState.CANCEL_REQUESTED) {
            return current
        }
        if (current.runnerInstanceId == null || heartbeat.plus(staleAfter).isAfter(now)) {
            return current
        }
        val code = "RUNNER_HEARTBEAT_EXPIRED"
        val message = "Catalog runner heartbeat expired"
        val completed = current.completeWithErrors(now)
        check(updateJob(completed, ownership = current.runnerInstanceId) > 0) {
            "stale Catalog runner ownership changed while terminalizing job"
        }
        jdbc.update(
            """
            UPDATE catalog.ingest_tasks
               SET state = 'FAILED', failure_code = ?, safe_message = ?,
                   failure_run_id = active_run_id, terminal_at = ?, active_run_id = NULL
             WHERE job_id = ? AND state = 'RUNNING'
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, code)
            statement.setString(2, message)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setObject(4, jobId.value.toJavaUuid())
        }
        return completed
    }

    override fun findIngestTask(id: CatalogIngestTaskId): CatalogIngestTask? = jdbc.queryOne(
        "SELECT * FROM catalog.ingest_tasks WHERE task_id = ?",
        { it.setObject(1, id.value.toJavaUuid()) },
    ) { it.toTask() }

    override fun findIngestTask(jobId: CatalogIngestJobId, targetKey: String): CatalogIngestTask? = jdbc.queryOne(
        "SELECT * FROM catalog.ingest_tasks WHERE job_id = ? AND target_key = ?",
        { statement ->
            statement.setObject(1, jobId.value.toJavaUuid())
            statement.setString(2, targetKey)
        },
    ) { it.toTask() }

    override fun findIngestTasks(jobId: CatalogIngestJobId): List<CatalogIngestTask> = jdbc.queryMany(
        "SELECT * FROM catalog.ingest_tasks WHERE job_id = ? ORDER BY created_at, task_id",
        { it.setObject(1, jobId.value.toJavaUuid()) },
    ) { it.toTask() }

    override fun findRunningIngestTasks(jobId: CatalogIngestJobId): List<CatalogIngestTask> = jdbc.queryMany(
        "SELECT * FROM catalog.ingest_tasks WHERE job_id = ? AND state = 'RUNNING' ORDER BY created_at, task_id LIMIT ?",
        { statement ->
            statement.setObject(1, jobId.value.toJavaUuid())
            statement.setInt(2, CATALOG_INGEST_JOB_TASK_PROJECTION_LIMIT)
        },
    ) { it.toTask() }

    private fun insertIngestTask(task: CatalogIngestTask) {
        jdbc.requireOwnerTransaction()
        jdbc.update(
            """
            INSERT INTO catalog.ingest_tasks
                (task_id, job_id, parent_task_id, workspace_id, target_kind, catalog_name,
                 schema_name, relation_name, relation_kind, target_key, state, active_run_id,
                 failure_code, safe_message, failure_run_id, created_at, started_at, terminal_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, task.id.value.toJavaUuid())
            statement.setObject(2, task.jobId.value.toJavaUuid())
            statement.setObject(3, task.parentTaskId?.value?.toJavaUuid())
            statement.setObject(4, task.workspaceId.value.toJavaUuid())
            statement.setString(5, task.target.kindName())
            statement.setString(6, task.target.catalog)
            statement.setString(7, task.target.schema)
            statement.setString(8, task.target.relation)
            statement.setString(9, task.target.relationKindName())
            statement.setString(10, task.target.key)
            statement.setString(11, task.state.name)
            statement.setObject(12, task.activeRunId?.value?.toJavaUuid())
            statement.setString(13, task.failureCode)
            statement.setString(14, task.safeMessage)
            statement.setObject(15, task.failureRunId?.value?.toJavaUuid())
            statement.setTimestamp(16, Timestamp.from(task.createdAt))
            statement.setTimestamp(17, Timestamp.from(task.startedAt))
            statement.setInstant(18, task.terminalAt)
        }
    }

    override fun findOrInsertOwnedIngestTask(
        task: CatalogIngestTask,
        runnerInstanceId: String,
    ): CatalogIngestTask? {
        jdbc.requireOwnerTransaction()
        val job = findSharedOwnedRunningJob(task.jobId, runnerInstanceId) ?: return null
        check(job.workspaceId == task.workspaceId) { "Catalog task workspace does not match its job" }
        findIngestTask(task.jobId, task.target.key)?.let { return it }
        insertIngestTask(task)
        return task
    }

    override fun updateOwnedIngestTask(task: CatalogIngestTask, runnerInstanceId: String): Boolean {
        jdbc.requireOwnerTransaction()
        return jdbc.update(
            """
            UPDATE catalog.ingest_tasks AS tasks
               SET state = ?, active_run_id = ?, failure_code = ?, safe_message = ?,
                   failure_run_id = ?, started_at = ?, terminal_at = ?
             WHERE tasks.task_id = ?
               AND tasks.state = 'RUNNING'
               AND EXISTS (
                   SELECT 1 FROM catalog.ingest_jobs jobs
                    WHERE jobs.job_id = tasks.job_id
                      AND jobs.state IN ('RUNNING', 'CANCEL_REQUESTED')
                      AND jobs.runner_instance_id = ?
               )
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, task.state.name)
            statement.setObject(2, task.activeRunId?.value?.toJavaUuid())
            statement.setString(3, task.failureCode)
            statement.setString(4, task.safeMessage)
            statement.setObject(5, task.failureRunId?.value?.toJavaUuid())
            statement.setTimestamp(6, Timestamp.from(task.startedAt))
            statement.setInstant(7, task.terminalAt)
            statement.setObject(8, task.id.value.toJavaUuid())
            statement.setString(9, runnerInstanceId)
        } > 0
    }

    override fun setActiveRun(
        taskId: CatalogIngestTaskId,
        runId: ExecutionRunId,
        runnerInstanceId: String,
    ): Boolean {
        jdbc.requireOwnerTransaction()
        val jobId = jdbc.queryOne(
            "SELECT job_id FROM catalog.ingest_tasks WHERE task_id = ?",
            { it.setObject(1, taskId.value.toJavaUuid()) },
        ) { CatalogIngestJobId(it.uuid("job_id")) } ?: return false
        findSharedOwnedRunningJob(jobId, runnerInstanceId) ?: return false
        return jdbc.update(
            "UPDATE catalog.ingest_tasks SET active_run_id = ? WHERE task_id = ? AND state = 'RUNNING'",
        ) { statement ->
            statement.setObject(1, runId.value.toJavaUuid())
            statement.setObject(2, taskId.value.toJavaUuid())
        } > 0
    }

    override fun applyContainer(
        task: CatalogIngestTask,
        selfObservations: List<CatalogMetadataObservation>,
        children: List<CatalogIngestTaskTarget>,
        runnerInstanceId: String,
        observedAt: Instant,
    ) {
        jdbc.requireOwnerTransaction()
        val job = requireSharedOwnedRunningJob(task.jobId, runnerInstanceId)
        val lockedTask = requireRunningTask(task)
        check(task.target !is CatalogIngestTaskTarget.Relation) {
            "Relation tasks must use completeRelationTask"
        }
        validateContainerObservations(task.target, selfObservations)
        val selfKind = containerObjectKind(task.target)
        if (selfKind != null) {
            applyCompleteObservations(
                job = job,
                task = task,
                objectKind = selfKind,
                observations = selfObservations,
                observedAt = observedAt,
            )
        } else {
            check(selfObservations.isEmpty()) {
                "Connector tasks cannot apply self observations"
            }
        }

        val childKind = childObjectKind(task.target)
        check(children.all { isDirectChild(task.target, it) }) {
            "Catalog child list contains a target outside its parent"
        }
        val observed = children.map { child ->
            PhysicalKey(
                kind = childKind,
                catalogName = child.catalog,
                schemaName = child.schema,
                objectName = child.relation,
                relationKind = (child as? CatalogIngestTaskTarget.Relation)?.kind,
            )
        }.toSet()
        if (childKind == CatalogObjectKind.RELATION) {
            removeUnobservedRelationsByAddress(
                job = job,
                task = task,
                observedAddresses = observed.map {
                    RelationPhysicalAddress(
                        catalogName = it.catalogName,
                        schemaName = checkNotNull(it.schemaName),
                        relationName = checkNotNull(it.objectName),
                    )
                }.toSet(),
            )
        } else {
            removeUnobserved(
                job = job,
                task = task,
                objectKind = childKind,
                observedObjectIds = observed.map { physicalKeyObjectId(job.connectorId, it) }.toSet(),
            )
        }
        check(lockedTask.state == CatalogIngestTaskState.RUNNING)
    }

    override fun completeRelationTask(
        task: CatalogIngestTask,
        relationObservations: List<CatalogMetadataObservation.Relation>,
        columnObservations: List<CatalogMetadataObservation.Column>,
        relationshipObservations: List<CatalogMetadataObservation.RelationshipMember>,
        runnerInstanceId: String,
        observedAt: Instant,
    ) {
        jdbc.requireOwnerTransaction()
        val job = requireSharedOwnedRunningJob(task.jobId, runnerInstanceId)
        val lockedTask = requireRunningTask(task)
        val target = task.target as? CatalogIngestTaskTarget.Relation
            ?: error("Only Relation tasks can be completed by completeRelationTask")
        validateRelationObservations(target, relationObservations, columnObservations, relationshipObservations)

        val allObservations = buildList {
            addAll(relationObservations)
            addAll(columnObservations)
            addAll(relationshipObservations)
        }
        val observedRelationObjectIds = relationObservations
            .map { currentObjectRow(job.connectorId, it).objectId }
            .toSet()
        removeUnobserved(
            job = job,
            task = task,
            objectKind = CatalogObjectKind.RELATION,
            observedObjectIds = observedRelationObjectIds,
        )
        allObservations.sortedBy { it.objectKindOrder() }.forEach { observation ->
            upsertObservation(
                job = job,
                observation = observation,
                observedAt = observedAt,
            )
        }
        CatalogObjectKind.entries
            .filter { it == CatalogObjectKind.COLUMN || it == CatalogObjectKind.RELATIONSHIP }
            .forEach { objectKind ->
                val observedObjectIds = allObservations
                    .filter { it.objectKind() == objectKind }
                    .map { currentObjectRow(job.connectorId, it).objectId }
                    .toSet()
                removeUnobserved(
                    job = job,
                    task = task,
                    objectKind = objectKind,
                    observedObjectIds = observedObjectIds,
                )
            }
        relationObservations.forEach { observation ->
            val row = currentObjectRow(job.connectorId, observation)
            searchProjection.upsert(CatalogSearchFields(job.workspaceId, row.searchAddress(job.connectorId),
                CatalogObjectKind.RELATION, listOf(checkNotNull(row.objectName)),
                listOfNotNull(row.catalogName, row.schemaName, row.relationKind?.name) + columnObservations.map { it.name }))
        }
        val succeeded = lockedTask.succeed(observedAt)
        check(updateOwnedIngestTask(succeeded, runnerInstanceId)) {
            "Catalog Relation task completion was lost"
        }
    }

    private fun updateJob(job: CatalogIngestJob, ownership: String?): Int {
        val sql = if (ownership == null) {
            """
            UPDATE catalog.ingest_jobs SET state = ?, runner_instance_id = ?, heartbeat_at = ?, terminal_at = ?
             WHERE job_id = ? AND state IN ('RUNNING', 'CANCEL_REQUESTED')
            """.trimIndent()
        } else {
            """
            UPDATE catalog.ingest_jobs SET state = ?, runner_instance_id = ?, heartbeat_at = ?, terminal_at = ?
             WHERE job_id = ? AND state IN ('RUNNING', 'CANCEL_REQUESTED') AND runner_instance_id = ?
            """.trimIndent()
        }
        return jdbc.update(sql) { statement ->
            statement.setString(1, job.state.name)
            statement.setString(2, job.runnerInstanceId)
            statement.setInstant(3, job.heartbeatAt)
            statement.setInstant(4, job.terminalAt)
            statement.setObject(5, job.id.value.toJavaUuid())
            if (ownership != null) statement.setString(6, ownership)
        }
    }

    private fun requireSharedOwnedRunningJob(
        jobId: CatalogIngestJobId,
        runnerInstanceId: String,
    ): CatalogIngestJob = requireNotNull(findSharedOwnedRunningJob(jobId, runnerInstanceId)) {
        "Catalog job is not RUNNING under the expected runner"
    }

    private fun findSharedOwnedRunningJob(
        jobId: CatalogIngestJobId,
        runnerInstanceId: String,
    ): CatalogIngestJob? = jdbc.queryOne(
        """
        SELECT * FROM catalog.ingest_jobs
         WHERE job_id = ? AND state = 'RUNNING' AND runner_instance_id = ?
         FOR SHARE
        """.trimIndent(),
        { statement ->
            statement.setObject(1, jobId.value.toJavaUuid())
            statement.setString(2, runnerInstanceId)
        },
    ) { it.toJob() }

    private fun requireRunningTask(task: CatalogIngestTask): CatalogIngestTask {
        val lockedTask = requireNotNull(lockTask(task.id)) { "Catalog task is missing" }
        check(lockedTask.state == CatalogIngestTaskState.RUNNING) { "Catalog task is not running" }
        check(lockedTask.jobId == task.jobId) { "Catalog task job does not match" }
        return lockedTask
    }

    private fun lockTask(taskId: CatalogIngestTaskId): CatalogIngestTask? = jdbc.queryOne(
        "SELECT * FROM catalog.ingest_tasks WHERE task_id = ? FOR UPDATE",
        { it.setObject(1, taskId.value.toJavaUuid()) },
    ) { it.toTask() }

    private fun applyCompleteObservations(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        objectKind: CatalogObjectKind,
        observations: List<CatalogMetadataObservation>,
        observedAt: Instant,
    ) {
        check(observations.all { it.objectKind() == objectKind }) {
            "Catalog observations do not match the container object kind"
        }
        observations.sortedBy { it.objectKindOrder() }.forEach { observation ->
            upsertObservation(job, observation, observedAt)
        }
        val observedObjectIds = observations
            .map { currentObjectRow(job.connectorId, it).objectId }
            .toSet()
        removeUnobserved(job, task, objectKind, observedObjectIds)
    }

    private fun removeUnobserved(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        objectKind: CatalogObjectKind,
        observedObjectIds: Set<UUID>,
    ) {
        removeUnobservedMatching(
            job = job,
            task = task,
            objectKind = objectKind,
        ) { row -> row.objectId in observedObjectIds }
    }

    private fun removeUnobservedRelationsByAddress(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        observedAddresses: Set<RelationPhysicalAddress>,
    ) {
        removeUnobservedMatching(
            job = job,
            task = task,
            objectKind = CatalogObjectKind.RELATION,
        ) { row ->
            RelationPhysicalAddress(
                catalogName = row.catalogName,
                schemaName = checkNotNull(row.schemaName),
                relationName = checkNotNull(row.objectName),
            ) in observedAddresses
        }
    }

    private fun removeUnobservedMatching(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        objectKind: CatalogObjectKind,
        isObserved: (CurrentObjectRow) -> Boolean,
    ) {
        val scope = removalScope(task.target, objectKind, job.selection, job.entry)
        val candidates = jdbc.queryMany(
            """
            SELECT * FROM catalog.current_objects
             WHERE workspace_id = ? AND connector_id = ?
               AND object_kind = ?
               AND ${scope.sql}
             FOR UPDATE
            """.trimIndent(),
            { statement ->
                statement.setObject(1, job.workspaceId.value.toJavaUuid())
                statement.setObject(2, job.connectorId.value.toJavaUuid())
                statement.setString(3, objectKind.name)
                bindValues(statement, 4, scope.values)
            },
        ) { it.toCurrentObjectRow() }
        candidates.filterNot(isObserved)
            .forEach { row -> deleteCurrentSubtree(job.workspaceId, job.connectorId, row) }
    }

    private fun physicalKeyObjectId(connectorId: ConnectorId, key: PhysicalKey): UUID =
        CatalogStableObjectIdentityCodec.derive(
            connectorId = connectorId,
            objectKind = key.kind,
            catalogName = key.catalogName,
            schemaName = key.schemaName,
            objectName = key.objectName,
            memberName = null,
            relationshipKindName = null,
        ).objectId.value.toJavaUuid()

    private fun containerObjectKind(target: CatalogIngestTaskTarget): CatalogObjectKind? = when (target) {
        CatalogIngestTaskTarget.Connector -> null
        is CatalogIngestTaskTarget.Catalog -> CatalogObjectKind.CATALOG
        is CatalogIngestTaskTarget.Schema -> CatalogObjectKind.SCHEMA
        is CatalogIngestTaskTarget.Relation -> error("Relation has no container self observation")
    }

    private fun childObjectKind(target: CatalogIngestTaskTarget): CatalogObjectKind = when (target) {
        CatalogIngestTaskTarget.Connector,
        is CatalogIngestTaskTarget.Catalog,
        -> CatalogObjectKind.SCHEMA
        is CatalogIngestTaskTarget.Schema -> CatalogObjectKind.RELATION
        is CatalogIngestTaskTarget.Relation -> error("Relation cannot have child list")
    }

    private fun validateContainerObservations(
        target: CatalogIngestTaskTarget,
        observations: List<CatalogMetadataObservation>,
    ) {
        when (target) {
            CatalogIngestTaskTarget.Connector -> check(observations.isEmpty())
            is CatalogIngestTaskTarget.Catalog -> check(observations.all {
                it is CatalogMetadataObservation.Catalog && it.name == target.name
            })
            is CatalogIngestTaskTarget.Schema -> check(observations.all {
                it is CatalogMetadataObservation.Schema &&
                    it.catalog == target.catalogName && it.schema == target.name
            })
            is CatalogIngestTaskTarget.Relation -> error("Relation has no container observations")
        }
    }

    private fun validateRelationObservations(
        target: CatalogIngestTaskTarget.Relation,
        relationObservations: List<CatalogMetadataObservation.Relation>,
        columnObservations: List<CatalogMetadataObservation.Column>,
        relationshipObservations: List<CatalogMetadataObservation.RelationshipMember>,
    ) {
        check(relationObservations.size <= 1) {
            "Relation task must produce at most one Relation observation"
        }
        check(
            relationObservations.isNotEmpty() ||
                (columnObservations.isEmpty() && relationshipObservations.isEmpty()),
        ) {
            "Relation child observations require a Relation observation"
        }
        check(relationObservations.all {
            it.catalog == target.catalogName &&
                it.schema == target.schemaName &&
                it.name == target.name
        }) { "Relation observations do not match the task target" }
        check(columnObservations.all {
            it.catalog == target.catalogName && it.schema == target.schemaName && it.relation == target.name
        }) { "Column observations do not match the task target" }
        check(relationshipObservations.all {
            it.catalog == target.catalogName &&
                it.schema == target.schemaName &&
                it.sourceRelation == target.name
        }) { "Relationship observations do not match the task target" }
    }

    private fun isDirectChild(parent: CatalogIngestTaskTarget, child: CatalogIngestTaskTarget): Boolean = when (parent) {
        CatalogIngestTaskTarget.Connector -> child is CatalogIngestTaskTarget.Schema
        is CatalogIngestTaskTarget.Catalog -> child is CatalogIngestTaskTarget.Schema &&
            child.catalogName == parent.name
        is CatalogIngestTaskTarget.Schema -> child is CatalogIngestTaskTarget.Relation &&
            child.catalogName == parent.catalogName && child.schemaName == parent.name
        is CatalogIngestTaskTarget.Relation -> false
    }

    private data class SqlScope(val sql: String, val values: List<Any?>)

    private fun removalScope(
        target: CatalogIngestTaskTarget,
        objectKind: CatalogObjectKind,
        selection: CatalogSelection,
        entry: CatalogIngestEntry,
    ): SqlScope {
        val parts = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        when (target) {
            CatalogIngestTaskTarget.Connector -> Unit
            is CatalogIngestTaskTarget.Catalog -> {
                parts += "catalog_name IS NOT DISTINCT FROM ?"
                values += target.name
            }
            is CatalogIngestTaskTarget.Schema -> {
                parts += "catalog_name IS NOT DISTINCT FROM ?"
                values += target.catalogName
                parts += "schema_name = ?"
                values += target.name
            }
            is CatalogIngestTaskTarget.Relation -> {
                parts += "catalog_name IS NOT DISTINCT FROM ?"
                values += target.catalogName
                parts += "schema_name = ?"
                values += target.schemaName
                parts += "object_name = ?"
                values += target.name
            }
        }
        val selectionCatalogName = when (target) {
            CatalogIngestTaskTarget.Connector -> entry.catalogName()
            is CatalogIngestTaskTarget.Catalog -> target.name
            is CatalogIngestTaskTarget.Schema -> target.catalogName
            is CatalogIngestTaskTarget.Relation -> target.catalogName
        }
        val selectionScope = selectionScope(objectKind, selection, selectionCatalogName)
        if (selectionScope.sql != "TRUE") {
            parts += selectionScope.sql
            values.addAll(selectionScope.values)
        }
        return SqlScope(parts.joinToString(" AND ").ifBlank { "TRUE" }, values)
    }

    private fun selectionScope(
        objectKind: CatalogObjectKind,
        selection: CatalogSelection,
        catalogName: String?,
    ): SqlScope = when (objectKind) {
        CatalogObjectKind.CATALOG -> SqlScope("TRUE", emptyList())
        CatalogObjectKind.SCHEMA -> {
            if (selection.selectedSchemas.isEmpty()) return SqlScope("FALSE", emptyList())
            val clauses = selection.selectedSchemas.map { selectedSchema ->
                SqlScope(
                    "catalog_name IS NOT DISTINCT FROM ? AND schema_name = ?",
                    listOf(catalogName, selectedSchema.schemaName),
                )
            }
            SqlScope(clauses.joinToString(" OR ", prefix = "(", postfix = ")") { it.sql }, clauses.flatMap { it.values })
        }
        CatalogObjectKind.RELATION,
        CatalogObjectKind.COLUMN,
        CatalogObjectKind.RELATIONSHIP,
        -> {
            if (selection.selectedSchemas.isEmpty()) return SqlScope("FALSE", emptyList())
            val clauses = selection.selectedSchemas.map { selectedSchema ->
                val conditions = mutableListOf("catalog_name IS NOT DISTINCT FROM ?", "schema_name = ?")
                val values = mutableListOf<Any?>(catalogName, selectedSchema.schemaName)
                when (val relations = selectedSchema.relations) {
                    CatalogRelationSelection.AllRelations -> Unit
                    is CatalogRelationSelection.SelectedRelations -> {
                        if (relations.relationNames.isEmpty()) {
                            return@map SqlScope("FALSE", emptyList())
                        }
                        conditions += "object_name IN (${relations.relationNames.joinToString(",") { "?" }})"
                        values.addAll(relations.relationNames.sorted())
                    }
                }
                SqlScope(conditions.joinToString(" AND "), values)
            }
            SqlScope(clauses.joinToString(" OR ", prefix = "(", postfix = ")") { it.sql }, clauses.flatMap { it.values })
        }
    }

    private fun upsertObservation(
        job: CatalogIngestJob,
        observation: CatalogMetadataObservation,
        observedAt: Instant,
    ) {
        val row = currentObjectRow(job.connectorId, observation)
        jdbc.update(
            """
            INSERT INTO catalog.current_objects
                (workspace_id, connector_id, object_id, identity_digest, parent_object_id,
                 object_kind, relation_kind, catalog_name, schema_name, object_name, member_name,
                 qualified_name, native_type, relation_definition, column_ordinal, column_nullable,
                 column_default_expression, relationship_kind, relationship_member_ordinal,
                 relationship_constraint_name, relationship_source_column,
                 relationship_referenced_catalog, relationship_referenced_schema,
                 relationship_referenced_relation, relationship_referenced_column, content_digest,
                 observed_at, relation_partition_keys, relation_clustering_keys, relation_requires_partition_filter)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (workspace_id, connector_id, object_id) DO UPDATE SET
                identity_digest = EXCLUDED.identity_digest,
                parent_object_id = EXCLUDED.parent_object_id,
                object_kind = EXCLUDED.object_kind,
                relation_kind = EXCLUDED.relation_kind,
                catalog_name = EXCLUDED.catalog_name,
                schema_name = EXCLUDED.schema_name,
                object_name = EXCLUDED.object_name,
                member_name = EXCLUDED.member_name,
                qualified_name = EXCLUDED.qualified_name,
                native_type = EXCLUDED.native_type,
                relation_definition = EXCLUDED.relation_definition,
                relation_partition_keys = EXCLUDED.relation_partition_keys,
                relation_clustering_keys = EXCLUDED.relation_clustering_keys,
                relation_requires_partition_filter = EXCLUDED.relation_requires_partition_filter,
                column_ordinal = EXCLUDED.column_ordinal,
                column_nullable = EXCLUDED.column_nullable,
                column_default_expression = EXCLUDED.column_default_expression,
                relationship_kind = EXCLUDED.relationship_kind,
                relationship_member_ordinal = EXCLUDED.relationship_member_ordinal,
                relationship_constraint_name = EXCLUDED.relationship_constraint_name,
                relationship_source_column = EXCLUDED.relationship_source_column,
                relationship_referenced_catalog = EXCLUDED.relationship_referenced_catalog,
                relationship_referenced_schema = EXCLUDED.relationship_referenced_schema,
                relationship_referenced_relation = EXCLUDED.relationship_referenced_relation,
                relationship_referenced_column = EXCLUDED.relationship_referenced_column,
                content_digest = EXCLUDED.content_digest,
                observed_at = EXCLUDED.observed_at
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, job.workspaceId.value.toJavaUuid())
            statement.setObject(2, job.connectorId.value.toJavaUuid())
            statement.setObject(3, row.objectId)
            statement.setString(4, row.identityDigest)
            statement.setObject(5, row.parentObjectId)
            statement.setString(6, row.objectKind.name)
            statement.setString(7, row.relationKind?.name)
            statement.setString(8, row.catalogName)
            statement.setString(9, row.schemaName)
            statement.setString(10, row.objectName)
            statement.setString(11, row.memberName)
            statement.setString(12, row.qualifiedName)
            statement.setString(13, row.nativeType)
            statement.setString(14, row.definition)
            statement.setObject(15, row.ordinal)
            statement.setObject(16, row.nullable)
            statement.setString(17, row.defaultExpression)
            statement.setString(18, row.relationshipKind?.name)
            statement.setObject(19, row.memberOrdinal)
            statement.setString(20, row.constraintName)
            statement.setString(21, row.sourceColumn)
            statement.setString(22, row.referencedCatalog)
            statement.setString(23, row.referencedSchema)
            statement.setString(24, row.referencedRelation)
            statement.setString(25, row.referencedColumn)
            statement.setString(26, row.contentDigest)
            statement.setTimestamp(27, Timestamp.from(observedAt))
            statement.setArray(28, row.partitionKeys?.let { statement.connection.createArrayOf("text", it.toTypedArray()) })
            statement.setArray(29, row.clusteringKeys?.let { statement.connection.createArrayOf("text", it.toTypedArray()) })
            statement.setObject(30, row.requiresPartitionFilter)
        }
        when (row.objectKind) {
            CatalogObjectKind.CATALOG -> if (selectionRepository.isConnectorCatalogSelected(job.workspaceId, job.connectorId, checkNotNull(row.catalogName))) {
                searchProjection.projectSelectedCatalog(job.workspaceId, job.connectorId, row.catalogName)
            }
            CatalogObjectKind.SCHEMA -> searchProjection.upsert(CatalogSearchFields(job.workspaceId,
                row.searchAddress(job.connectorId), row.objectKind, listOf(checkNotNull(row.schemaName)), listOfNotNull(row.catalogName)))
            else -> Unit
        }
    }

    private fun CurrentObjectRow.searchAddress(connectorId: ConnectorId) =
        CatalogSearchAddress(connectorId, catalogName, schemaName, objectName)

    private fun deleteCurrentSubtree(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        row: CurrentObjectRow,
    ) {
        val deleted = jdbc.update(
            """
            DELETE FROM catalog.current_objects
             WHERE workspace_id = ? AND connector_id = ? AND object_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            statement.setObject(3, row.objectId)
        }
        if (deleted == 0) return
        if (row.objectKind in setOf(CatalogObjectKind.CATALOG, CatalogObjectKind.SCHEMA, CatalogObjectKind.RELATION)) {
            searchProjection.deleteSubtree(workspaceId, row.searchAddress(connectorId))
            if (row.objectKind == CatalogObjectKind.CATALOG && selectionRepository.isConnectorCatalogSelected(workspaceId, connectorId, checkNotNull(row.catalogName))) {
                searchProjection.projectSelectedCatalog(workspaceId, connectorId, row.catalogName)
            }
        }
        jdbc.queryMany(
            """
            SELECT * FROM catalog.current_objects
             WHERE workspace_id = ? AND connector_id = ? AND parent_object_id = ?
             FOR UPDATE
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setObject(3, row.objectId)
            },
        ) { it.toCurrentObjectRow() }.forEach { child -> deleteCurrentSubtree(workspaceId, connectorId, child) }
    }

    private fun currentObjectRow(
        connectorId: ConnectorId,
        observation: CatalogMetadataObservation,
    ): CurrentObjectRow {
        val catalog = observation.catalog
        val schema = observation.schema.takeUnless(String::isBlank)
        val objectName = when (observation) {
            is CatalogMetadataObservation.Catalog,
            is CatalogMetadataObservation.Schema,
            -> null
            is CatalogMetadataObservation.Relation -> observation.name
            is CatalogMetadataObservation.Column -> observation.relation
            is CatalogMetadataObservation.RelationshipMember -> observation.sourceRelation
        }
        val memberName = when (observation) {
            is CatalogMetadataObservation.Column -> observation.name
            is CatalogMetadataObservation.RelationshipMember -> observation.memberIdentityName()
            else -> null
        }
        val objectKind = when (observation) {
            is CatalogMetadataObservation.Catalog -> CatalogObjectKind.CATALOG
            is CatalogMetadataObservation.Schema -> CatalogObjectKind.SCHEMA
            is CatalogMetadataObservation.Relation -> CatalogObjectKind.RELATION
            is CatalogMetadataObservation.Column -> CatalogObjectKind.COLUMN
            is CatalogMetadataObservation.RelationshipMember -> CatalogObjectKind.RELATIONSHIP
        }
        val relationKind = (observation as? CatalogMetadataObservation.Relation)?.relationKind
        val relationshipKind = (observation as? CatalogMetadataObservation.RelationshipMember)?.relationshipKind?.name
        val identity = CatalogStableObjectIdentityCodec.derive(
            connectorId = connectorId,
            objectKind = objectKind,
            catalogName = catalog,
            schemaName = schema,
            objectName = objectName,
            memberName = memberName,
            relationshipKindName = relationshipKind,
        )
        val parentObjectId = when (objectKind) {
            CatalogObjectKind.CATALOG -> null
            CatalogObjectKind.SCHEMA -> catalog?.let {
                CatalogStableObjectIdentityCodec.derive(
                    connectorId, CatalogObjectKind.CATALOG, it, null, null, null, null,
                ).objectId.value.toJavaUuid()
            }
            CatalogObjectKind.RELATION -> CatalogStableObjectIdentityCodec.derive(
                connectorId, CatalogObjectKind.SCHEMA, catalog, requireNotNull(schema), null, null, null,
            ).objectId.value.toJavaUuid()
            CatalogObjectKind.COLUMN,
            CatalogObjectKind.RELATIONSHIP,
            -> {
                CatalogStableObjectIdentityCodec.derive(
                    connectorId,
                    CatalogObjectKind.RELATION,
                    catalog,
                    requireNotNull(schema),
                    requireNotNull(objectName),
                    null,
                    null,
                ).objectId.value.toJavaUuid()
            }
        }
        return CurrentObjectRow(
            objectId = identity.objectId.value.toJavaUuid(),
            identityDigest = identity.identityDigest.value,
            parentObjectId = parentObjectId,
            objectKind = objectKind,
            relationKind = relationKind,
            catalogName = catalog,
            schemaName = schema,
            objectName = objectName,
            memberName = memberName,
            qualifiedName = listOfNotNull(catalog, schema, objectName, memberName).joinToString("."),
            nativeType = (observation as? CatalogMetadataObservation.Column)?.nativeType,
            definition = (observation as? CatalogMetadataObservation.Relation)?.definition,
            partitionKeys = (observation as? CatalogMetadataObservation.Relation)?.partitionKeys,
            clusteringKeys = (observation as? CatalogMetadataObservation.Relation)?.clusteringKeys,
            requiresPartitionFilter = (observation as? CatalogMetadataObservation.Relation)?.requiresPartitionFilter,
            ordinal = (observation as? CatalogMetadataObservation.Column)?.ordinal,
            nullable = (observation as? CatalogMetadataObservation.Column)?.nullable,
            defaultExpression = (observation as? CatalogMetadataObservation.Column)?.defaultExpression,
            relationshipKind = (observation as? CatalogMetadataObservation.RelationshipMember)?.relationshipKind,
            memberOrdinal = (observation as? CatalogMetadataObservation.RelationshipMember)?.memberOrdinal,
            constraintName = (observation as? CatalogMetadataObservation.RelationshipMember)?.constraintName,
            sourceColumn = (observation as? CatalogMetadataObservation.RelationshipMember)?.sourceColumn,
            referencedCatalog = (observation as? CatalogMetadataObservation.RelationshipMember)?.referencedCatalog,
            referencedSchema = (observation as? CatalogMetadataObservation.RelationshipMember)?.referencedSchema,
            referencedRelation = (observation as? CatalogMetadataObservation.RelationshipMember)?.referencedRelation,
            referencedColumn = (observation as? CatalogMetadataObservation.RelationshipMember)?.referencedColumn,
            contentDigest = digestObservation(observation).value,
        )
    }

    private fun ResultSet.toCurrentObjectRow(): CurrentObjectRow = CurrentObjectRow(
        objectId = getObject("object_id", UUID::class.java),
        identityDigest = getString("identity_digest"),
        parentObjectId = getObject("parent_object_id", UUID::class.java),
        objectKind = CatalogObjectKind.valueOf(getString("object_kind")),
        relationKind = getString("relation_kind")?.let(RelationKind::valueOf),
        catalogName = getString("catalog_name"),
        schemaName = getString("schema_name"),
        objectName = getString("object_name"),
        memberName = getString("member_name"),
        qualifiedName = getString("qualified_name"),
        nativeType = getString("native_type"),
        definition = getString("relation_definition"),
        partitionKeys = (getArray("relation_partition_keys")?.array as? Array<*>)?.map { it as String },
        clusteringKeys = (getArray("relation_clustering_keys")?.array as? Array<*>)?.map { it as String },
        requiresPartitionFilter = getBoolean("relation_requires_partition_filter").let { if (wasNull()) null else it },
        ordinal = getInt("column_ordinal").let { if (wasNull()) null else it },
        nullable = getBoolean("column_nullable").let { if (wasNull()) null else it },
        defaultExpression = getString("column_default_expression"),
        relationshipKind = getString("relationship_kind")?.let(CatalogRelationshipKind::valueOf),
        memberOrdinal = getInt("relationship_member_ordinal").let { if (wasNull()) null else it },
        constraintName = getString("relationship_constraint_name"),
        sourceColumn = getString("relationship_source_column"),
        referencedCatalog = getString("relationship_referenced_catalog"),
        referencedSchema = getString("relationship_referenced_schema"),
        referencedRelation = getString("relationship_referenced_relation"),
        referencedColumn = getString("relationship_referenced_column"),
        contentDigest = getString("content_digest"),
    )

    private fun ResultSet.toJob(): CatalogIngestJob {
        val connectorId = ConnectorId(uuid("connector_id"))
        return CatalogIngestJob(
            id = CatalogIngestJobId(uuid("job_id")),
            workspaceId = WorkspaceId(uuid("workspace_id")),
            connectorId = connectorId,
            selection = selectionCodec.decodeSelection(getString("selection")),
            entry = when (getString("entry_kind")) {
                "CONNECTOR" -> CatalogIngestEntry.Connector(connectorId)
                "CATALOG" -> CatalogIngestEntry.Catalog(connectorId, getString("entry_catalog_name"))
                else -> error("Unknown Catalog entry kind")
            },
            requestedBy = PrincipalId(uuid("requested_by")),
            credentialSurface = getString("credential_surface")?.let(CredentialSurface::valueOf),
            agentClientId = getString("agent_client_id")?.let { AgentClientId(it) },
            requestedAt = timestamp("requested_at"),
            state = CatalogIngestJobState.valueOf(getString("state")),
            runnerInstanceId = getString("runner_instance_id"),
            heartbeatAt = getTimestamp("heartbeat_at")?.toInstant(),
            terminalAt = getTimestamp("terminal_at")?.toInstant(),
        )
    }

    private fun ResultSet.toTask(): CatalogIngestTask {
        val target = when (getString("target_kind")) {
            "CONNECTOR" -> CatalogIngestTaskTarget.Connector
            "CATALOG" -> CatalogIngestTaskTarget.Catalog(getString("catalog_name"))
            "SCHEMA" -> CatalogIngestTaskTarget.Schema(getString("catalog_name"), getString("schema_name"))
            "RELATION" -> CatalogIngestTaskTarget.Relation(
                getString("catalog_name"),
                getString("schema_name"),
                getString("relation_name"),
                RelationKind.valueOf(getString("relation_kind")),
            )
            else -> error("Unknown Catalog task target kind")
        }
        return CatalogIngestTask(
            id = CatalogIngestTaskId(uuid("task_id")),
            jobId = CatalogIngestJobId(uuid("job_id")),
            parentTaskId = getObject("parent_task_id", UUID::class.java)?.toKotlinUuid()?.let(::CatalogIngestTaskId),
            workspaceId = WorkspaceId(uuid("workspace_id")),
            target = target,
            state = CatalogIngestTaskState.valueOf(getString("state")),
            activeRunId = getObject("active_run_id", UUID::class.java)?.toKotlinUuid()?.let(::ExecutionRunId),
            failureCode = getString("failure_code"),
            safeMessage = getString("safe_message"),
            failureRunId = getObject("failure_run_id", UUID::class.java)?.toKotlinUuid()?.let(::ExecutionRunId),
            createdAt = timestamp("created_at"),
            startedAt = timestamp("started_at"),
            terminalAt = getTimestamp("terminal_at")?.toInstant(),
        )
    }

    private data class CurrentObjectRow(
        val objectId: UUID,
        val identityDigest: String,
        val parentObjectId: UUID?,
        val objectKind: CatalogObjectKind,
        val relationKind: RelationKind?,
        val catalogName: String?,
        val schemaName: String?,
        val objectName: String?,
        val memberName: String?,
        val qualifiedName: String,
        val nativeType: String?,
        val definition: String?,
        val partitionKeys: List<String>?,
        val clusteringKeys: List<String>?,
        val requiresPartitionFilter: Boolean?,
        val ordinal: Int?,
        val nullable: Boolean?,
        val defaultExpression: String?,
        val relationshipKind: CatalogRelationshipKind?,
        val memberOrdinal: Int?,
        val constraintName: String?,
        val sourceColumn: String?,
        val referencedCatalog: String?,
        val referencedSchema: String?,
        val referencedRelation: String?,
        val referencedColumn: String?,
        val contentDigest: String,
    )

    private data class PhysicalKey(
        val kind: CatalogObjectKind,
        val catalogName: String?,
        val schemaName: String?,
        val objectName: String?,
        val relationKind: RelationKind?,
    )

    private data class RelationPhysicalAddress(
        val catalogName: String?,
        val schemaName: String,
        val relationName: String,
    )
}

private fun CatalogIngestEntry.kindName(): String = when (this) {
    is CatalogIngestEntry.Connector -> "CONNECTOR"
    is CatalogIngestEntry.Catalog -> "CATALOG"
}

private fun CatalogIngestEntry.taskTarget(): CatalogIngestTaskTarget = when (this) {
    is CatalogIngestEntry.Connector -> CatalogIngestTaskTarget.Connector
    is CatalogIngestEntry.Catalog -> CatalogIngestTaskTarget.Catalog(name)
}

private fun CatalogIngestTaskTarget.kindName(): String = when (this) {
    CatalogIngestTaskTarget.Connector -> "CONNECTOR"
    is CatalogIngestTaskTarget.Catalog -> "CATALOG"
    is CatalogIngestTaskTarget.Schema -> "SCHEMA"
    is CatalogIngestTaskTarget.Relation -> "RELATION"
}

private fun CatalogIngestTaskTarget.relationKindName(): String? =
    (this as? CatalogIngestTaskTarget.Relation)?.kind?.name

private fun CatalogMetadataObservation.objectKindOrder(): Int = when (this) {
    is CatalogMetadataObservation.Catalog -> 0
    is CatalogMetadataObservation.Schema -> 1
    is CatalogMetadataObservation.Relation -> 2
    is CatalogMetadataObservation.Column -> 3
    is CatalogMetadataObservation.RelationshipMember -> 4
}

private fun CatalogMetadataObservation.objectKind(): CatalogObjectKind = when (this) {
    is CatalogMetadataObservation.Catalog -> CatalogObjectKind.CATALOG
    is CatalogMetadataObservation.Schema -> CatalogObjectKind.SCHEMA
    is CatalogMetadataObservation.Relation -> CatalogObjectKind.RELATION
    is CatalogMetadataObservation.Column -> CatalogObjectKind.COLUMN
    is CatalogMetadataObservation.RelationshipMember -> CatalogObjectKind.RELATIONSHIP
}

private fun CatalogMetadataObservation.RelationshipMember.memberIdentityName(): String =
    "$constraintName:$memberOrdinal"
