@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.application.ingest

import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobState
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskState
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.application.CatalogInlineArrowBatch
import cc.monomer.catalog.application.CatalogInlineQueryCommand
import cc.monomer.catalog.application.CatalogInlineQueryPort
import cc.monomer.catalog.application.CatalogInlineQueryResult
import cc.monomer.catalog.domain.CatalogMetadataObservation
import cc.monomer.catalog.application.CatalogIngestRepository
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.catalog.domain.ingestion.CatalogMetadataParser
import cc.monomer.catalog.domain.ingestion.CatalogQueryKind
import cc.monomer.catalog.domain.ingestion.CatalogQueryRow
import cc.monomer.catalog.domain.ingestion.CatalogSqlGenerator
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.identity.api.WorkspaceId
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Catalog-owned traversal. Each resource is a durable task and each sibling is an
 * ordinary coroutine inside a supervisor scope. Execution only sees the one SQL statement built
 * here and returns inline Arrow batches to this process.
 */
class CatalogIngestCascadeRunner(
    private val repository: CatalogIngestRepository,
    private val transactionPort: CatalogTransactionPort,
    private val createInlineQueryPort: (ConnectorRuntimeBindingSpec, WorkspaceId, ExecutionOwnerReference, CatalogTransactionPort) -> CatalogInlineQueryPort,
    private val clock: Clock,
    private val heartbeatInterval: Duration,
    private val decodeBatch: suspend (CatalogInlineArrowBatch) -> List<CatalogQueryRow>,
    private val failureSqlStates: (Throwable) -> String,
) {
    private val inlineQueryPermits = Semaphore(MAXIMUM_CONCURRENT_INLINE_QUERIES)

    init {
        require(!heartbeatInterval.isZero && !heartbeatInterval.isNegative)
    }

    suspend fun run(
        job: CatalogIngestJob,
        connectorBinding: ConnectorRuntimeBindingSpec,
        instanceId: String,
    ): CatalogIngestJob {
        val claimed = job.takeIf {
            it.state == CatalogIngestJobState.RUNNING &&
                it.runnerInstanceId == instanceId &&
                it.heartbeatAt != null
        } ?: return job
        val connectorType = ConnectorProductType.valueOf(connectorBinding.connectorType.name)
        val owner = ExecutionOwnerReference(
            type = ExecutionOwnerType.CATALOG,
            id = ExecutionOwnerId(claimed.id.value),
        )
        val inlineQuery = createInlineQueryPort(connectorBinding, claimed.workspaceId, owner, transactionPort)
        val entryTask = readOnlyTransaction { repository.findIngestTask(claimed.id, claimed.entry.taskTargetKey()) }
            ?: return terminalizeRunnerFailure(claimed, instanceId, "ENTRY_TASK_MISSING", "Catalog entry task is missing")
        return coroutineScope {
            val heartbeatJob = launch {
                while (isActive) {
                    delay(heartbeatInterval.toMillis())
                    if (isActive) heartbeat(claimed.id, instanceId)
                }
            }
            try {
                processTask(claimed, entryTask, connectorType, connectorBinding, instanceId, inlineQuery)
                finalizeJob(claimed.id, instanceId)
            } finally {
                heartbeatJob.cancelAndJoin()
            }
        }
    }

    private suspend fun processTask(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        connectorType: ConnectorProductType,
        connectorBinding: ConnectorRuntimeBindingSpec,
        instanceId: String,
        inlineQuery: CatalogInlineQueryPort,
    ) {
        if (isCancellationRequested(job.id)) {
            cancelTask(task, instanceId)
            return
        }
        try {
            when (val target = task.target) {
                CatalogIngestTaskTarget.Connector,
                is CatalogIngestTaskTarget.Catalog,
                is CatalogIngestTaskTarget.Schema,
                -> processContainer(job, task, target, connectorType, connectorBinding, instanceId, inlineQuery)
                is CatalogIngestTaskTarget.Relation -> processRelation(job, task, target, connectorType, connectorBinding, instanceId, inlineQuery)
            }
            if (isCancellationRequested(job.id)) {
                cancelTask(task, instanceId)
            } else if (task.target !is CatalogIngestTaskTarget.Relation) {
                succeedTask(task, instanceId)
            }
        } catch (failure: CatalogQueryFailure) {
            if (isCancellationRequested(job.id)) {
                cancelTask(task, instanceId)
            } else {
                failTask(task, instanceId, failure.code, failure.message, failure.runId)
            }
        } catch (cancelled: CatalogQueryCancelled) {
            if (isCancellationRequested(job.id)) {
                cancelTask(task, instanceId)
            } else {
                failTask(
                    task,
                    instanceId,
                    "EXECUTION_CANCELLED",
                    "Catalog query was cancelled by Execution",
                    cancelled.runId,
                )
            }
        } catch (cancellation: CancellationException) {
            if (isCancellationRequested(job.id)) {
                cancelTask(task, instanceId)
            } else {
                throw cancellation
            }
        } catch (failure: Throwable) {
            if (isCancellationRequested(job.id)) {
                cancelTask(task, instanceId)
            } else {
                catalogIngestLogger.atError()
                    .addKeyValue("event", "catalog_task_failed")
                    .addKeyValue("catalog_job_id", job.id.toString())
                    .addKeyValue("catalog_task_id", task.id.toString())
                    .addKeyValue("target_kind", task.target.kindName())
                    .setCause(failure)
                    .addKeyValue("failure_types", failure.causeTypeChain())
                    .addKeyValue("sql_states", failureSqlStates(failure))
                    .log("catalog_task_failed")
                failTask(
                    task,
                    instanceId,
                    "CATALOG_INGEST_FAILED",
                    "Catalog metadata load failed",
                    null,
                )
            }
        }
    }

    private suspend fun processContainer(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        target: CatalogIngestTaskTarget,
        connectorType: ConnectorProductType,
        connectorBinding: ConnectorRuntimeBindingSpec,
        instanceId: String,
        inlineQuery: CatalogInlineQueryPort,
    ) {
        val selfObservations = if (target is CatalogIngestTaskTarget.Connector) {
            emptyList()
        } else {
            readMetadataQuery(
                job,
                task,
                connectorType,
                connectorBinding,
                CatalogQueryKind.SELF_METADATA,
                instanceId,
                inlineQuery,
            )
        }
        if (isCancellationRequested(job.id)) return
        val children = runChildListQuery(job, task, target, connectorType, connectorBinding, instanceId, inlineQuery)
        if (isCancellationRequested(job.id)) return
        transaction {
            repository.applyContainer(task, selfObservations, children, instanceId, clock.instant())
        }
        val childCreationFailures = supervisorScope {
            children.map { child ->
                async {
                    val childTask = try {
                        createChildTask(job, task, child, instanceId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        return@async failure
                    }
                    childTask?.let { processTask(job, it, connectorType, connectorBinding, instanceId, inlineQuery) }
                    null
                }
            }.awaitAll().filterNotNull()
        }
        childCreationFailures.firstOrNull()?.let { failure -> throw failure }
    }

    private suspend fun createChildTask(
        job: CatalogIngestJob,
        parentTask: CatalogIngestTask,
        target: CatalogIngestTaskTarget,
        instanceId: String,
    ): CatalogIngestTask? = transaction {
        repository.findOrInsertOwnedIngestTask(
            CatalogIngestTask.start(
                job = job,
                parentTaskId = parentTask.id,
                target = target,
                at = clock.instant(),
            ),
            instanceId,
        )
    }

    private suspend fun processRelation(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        target: CatalogIngestTaskTarget.Relation,
        connectorType: ConnectorProductType,
        connectorBinding: ConnectorRuntimeBindingSpec,
        instanceId: String,
        inlineQuery: CatalogInlineQueryPort,
    ) {
        var relationObservations = emptyList<CatalogMetadataObservation.Relation>()
        var columnObservations = emptyList<CatalogMetadataObservation.Column>()
        var relationshipObservations = emptyList<CatalogMetadataObservation.RelationshipMember>()
        CatalogSqlGenerator.relationPlan(connectorType, connectorBinding.catalogSqlProperties(), target).forEach { plan ->
            if (isCancellationRequested(job.id)) return
            when (plan.kind) {
                CatalogQueryKind.RELATION_METADATA -> {
                    relationObservations = readMetadataQuery(
                        job,
                        task,
                        connectorType,
                        connectorBinding,
                        plan.kind,
                        instanceId,
                        inlineQuery,
                    ).filterIsInstance<CatalogMetadataObservation.Relation>()
                }
                CatalogQueryKind.RELATION_COLUMNS -> {
                    columnObservations = readMetadataQuery(
                        job,
                        task,
                        connectorType,
                        connectorBinding,
                        plan.kind,
                        instanceId,
                        inlineQuery,
                    ).filterIsInstance<CatalogMetadataObservation.Column>()
                }
                CatalogQueryKind.RELATIONSHIPS, CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS -> {
                    relationshipObservations += readMetadataQuery(
                        job,
                        task,
                        connectorType,
                        connectorBinding,
                        plan.kind,
                        instanceId,
                        inlineQuery,
                    ).filterIsInstance<CatalogMetadataObservation.RelationshipMember>()
                }
                else -> error("Relation plan contains unsupported query kind: ${plan.kind}")
            }
        }
        if (isCancellationRequested(job.id)) return
        transaction {
            repository.completeRelationTask(
                task = task,
                relationObservations = relationObservations,
                columnObservations = columnObservations,
                relationshipObservations = relationshipObservations,
                runnerInstanceId = instanceId,
                observedAt = clock.instant(),
            )
        }
    }

    private suspend fun readMetadataQuery(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        connectorType: ConnectorProductType,
        connectorBinding: ConnectorRuntimeBindingSpec,
        kind: CatalogQueryKind,
        instanceId: String,
        inlineQuery: CatalogInlineQueryPort,
    ): List<CatalogMetadataObservation> {
        val plan = CatalogSqlGenerator.forTarget(
            connectorType,
            connectorBinding.catalogSqlProperties(),
            task.target,
            kind,
        )
        val rows = executeQuery(job, task, connectorBinding, plan.kind, plan.sql, plan.parameters, instanceId, inlineQuery)
        if (isCancellationRequested(job.id)) return emptyList()
        val observations = when (kind) {
            CatalogQueryKind.SELF_METADATA -> when (task.target) {
                CatalogIngestTaskTarget.Connector,
                is CatalogIngestTaskTarget.Catalog,
                -> CatalogMetadataParser.catalogs(rows.asSequence())
                is CatalogIngestTaskTarget.Schema -> CatalogMetadataParser.schemas(rows.asSequence())
                is CatalogIngestTaskTarget.Relation -> CatalogMetadataParser.relations(rows.asSequence())
            }
            CatalogQueryKind.RELATION_METADATA -> CatalogMetadataParser.relations(rows.asSequence())
            CatalogQueryKind.RELATION_COLUMNS -> CatalogMetadataParser.columns(rows.asSequence())
            CatalogQueryKind.RELATIONSHIPS, CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS -> CatalogMetadataParser.relationships(rows.asSequence())
            CatalogQueryKind.CHILD_LIST -> error("child list is not metadata")
        }
        return observations
    }

    private suspend fun runChildListQuery(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        target: CatalogIngestTaskTarget,
        connectorType: ConnectorProductType,
        connectorBinding: ConnectorRuntimeBindingSpec,
        instanceId: String,
        inlineQuery: CatalogInlineQueryPort,
    ): List<CatalogIngestTaskTarget> {
        val plan = CatalogSqlGenerator.forTarget(
            connectorType,
            connectorBinding.catalogSqlProperties(),
            target,
            CatalogQueryKind.CHILD_LIST,
        )
        val rows = executeQuery(job, task, connectorBinding, plan.kind, plan.sql, plan.parameters, instanceId, inlineQuery)
        return when (target) {
            CatalogIngestTaskTarget.Connector,
            is CatalogIngestTaskTarget.Catalog,
            -> CatalogMetadataParser.schemas(rows.asSequence()).map {
                CatalogIngestTaskTarget.Schema(it.catalog, it.schema)
            }.filter {
                job.selection.includesSchema(it.name)
            }
            is CatalogIngestTaskTarget.Schema -> CatalogMetadataParser.relations(rows.asSequence()).map {
                CatalogIngestTaskTarget.Relation(it.catalog, it.schema, it.name, it.relationKind)
            }.filter {
                job.selection.includesRelation(it.schemaName, it.name)
            }
            is CatalogIngestTaskTarget.Relation -> error("Relation cannot have child list")
        }
    }

    private suspend fun executeQuery(
        job: CatalogIngestJob,
        task: CatalogIngestTask,
        connectorBinding: ConnectorRuntimeBindingSpec,
        kind: CatalogQueryKind,
        sql: String,
        parameters: List<cc.monomer.catalog.domain.CatalogQueryParameter>,
        instanceId: String,
        inlineQuery: CatalogInlineQueryPort,
    ): List<CatalogQueryRow> {
        val rows = mutableListOf<CatalogQueryRow>()
        val result = inlineQueryPermits.withPermit {
            inlineQuery.execute(
                command = cc.monomer.catalog.application.CatalogInlineQueryCommand(
                    workspaceId = job.workspaceId,
                    connectorId = job.connectorId,
                    connectorBinding = connectorBinding,
                    executionReservationKey = "ingest-task:${task.id}:${sqlIdentity(sql)}",
                    sql = sql,
                    parameters = parameters,
                ),
                onRunAdmittedInOwnerTransaction = { runId ->
                    check(repository.setActiveRun(task.id, runId, instanceId)) {
                        "Catalog task no longer belongs to runner"
                    }
                },
                onBatch = { batch ->
                    rows += decodeBatch(batch)
                },
            )
        }
        when (result) {
            is CatalogInlineQueryResult.Succeeded -> return CatalogMetadataParser.canonicalRows(
                ConnectorProductType.valueOf(connectorBinding.connectorType.name),
                connectorBinding.catalogSqlProperties(),
                kind,
                rows,
            )
            is CatalogInlineQueryResult.Failed ->
                throw CatalogQueryFailure(result.code, result.safeMessage, result.runId)
            is CatalogInlineQueryResult.Cancelled -> throw CatalogQueryCancelled(result.runId)
        }
    }

    private suspend fun heartbeat(jobId: cc.monomer.catalog.api.CatalogIngestJobId, instanceId: String) {
        try {
            transaction {
                repository.heartbeatIngestJob(jobId, instanceId, clock.instant())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            catalogIngestLogger.atWarn()
                .addKeyValue("event", "catalog_ingest_heartbeat_failed")
                .addKeyValue("catalog_job_id", jobId.toString())
                .setCause(failure)
                .addKeyValue("failure_types", failure.causeTypeChain())
                .addKeyValue("sql_states", failureSqlStates(failure))
                .log("catalog_ingest_heartbeat_failed")
        }
    }

    private suspend fun isCancellationRequested(jobId: cc.monomer.catalog.api.CatalogIngestJobId): Boolean =
        readOnlyTransaction { repository.findIngestJob(jobId) }?.state == CatalogIngestJobState.CANCEL_REQUESTED

    private suspend fun succeedTask(task: CatalogIngestTask, instanceId: String) {
        transaction {
            val current = repository.findIngestTask(task.id) ?: return@transaction
            if (current.state != CatalogIngestTaskState.RUNNING) return@transaction
            check(repository.updateOwnedIngestTask(current.succeed(clock.instant()), instanceId))
        }
    }

    private suspend fun failTask(
        task: CatalogIngestTask,
        instanceId: String,
        code: String,
        message: String,
        runId: ExecutionRunId?,
    ) {
        transaction {
            val current = repository.findIngestTask(task.id) ?: return@transaction
            if (current.state != CatalogIngestTaskState.RUNNING) return@transaction
            val failure = if (message.isBlank()) "Catalog metadata load failed" else message
            check(repository.updateOwnedIngestTask(current.fail(code, failure, clock.instant(), runId), instanceId))
        }
    }

    private suspend fun cancelTask(task: CatalogIngestTask, instanceId: String) {
        transaction {
            val current = repository.findIngestTask(task.id) ?: return@transaction
            if (current.state != CatalogIngestTaskState.RUNNING) return@transaction
            check(repository.updateOwnedIngestTask(current.cancel("Catalog ingestion cancelled", clock.instant()), instanceId))
        }
    }

    private suspend fun finalizeJob(
        jobId: cc.monomer.catalog.api.CatalogIngestJobId,
        instanceId: String,
    ): CatalogIngestJob {
        return transaction {
            val current = repository.lockIngestJob(jobId) ?: error("Catalog job is missing")
            if (
                current.runnerInstanceId != instanceId ||
                (current.state != CatalogIngestJobState.RUNNING &&
                    current.state != CatalogIngestJobState.CANCEL_REQUESTED)
            ) {
                return@transaction current
            }
            val tasks = repository.findIngestTasks(jobId)
            if (tasks.any { it.state == CatalogIngestTaskState.RUNNING }) return@transaction current
            val final = when {
                current.state == CatalogIngestJobState.CANCEL_REQUESTED ||
                    tasks.any { it.state == CatalogIngestTaskState.CANCELLED } -> current.cancel(clock.instant())
                tasks.any { it.state == CatalogIngestTaskState.FAILED } -> current.completeWithErrors(clock.instant())
                else -> current.complete(clock.instant())
            }
            check(repository.updateOwnedIngestJob(final, instanceId)) { "Catalog job ownership was lost" }
            final
        }
    }

    private suspend fun terminalizeRunnerFailure(
        job: CatalogIngestJob,
        instanceId: String,
        code: String,
        message: String,
    ): CatalogIngestJob {
        return transaction {
            val current = repository.lockIngestJob(job.id) ?: return@transaction job
            val task = repository.findIngestTask(job.id, job.entry.taskTargetKey())
            if (task != null && task.state == CatalogIngestTaskState.RUNNING) {
                check(repository.updateOwnedIngestTask(task.fail(code, message, clock.instant(), null), instanceId))
            }
            val final = current.completeWithErrors(clock.instant())
            check(repository.updateOwnedIngestJob(final, instanceId))
            final
        }
    }

    private suspend fun <T> transaction(block: () -> T): T = transactionPort.inTransaction(block)

    private suspend fun <T> readOnlyTransaction(block: () -> T): T = transactionPort.inReadOnlyTransaction(block)

    private fun CatalogIngestEntry.taskTargetKey(): String = when (this) {
        is CatalogIngestEntry.Connector -> CatalogIngestTaskTarget.Connector.key
        is CatalogIngestEntry.Catalog -> CatalogIngestTaskTarget.Catalog(name).key
    }

    private fun CatalogIngestTaskTarget.kindName(): String = when (this) {
        CatalogIngestTaskTarget.Connector -> "CONNECTOR"
        is CatalogIngestTaskTarget.Catalog -> "CATALOG"
        is CatalogIngestTaskTarget.Schema -> "SCHEMA"
        is CatalogIngestTaskTarget.Relation -> "RELATION"
    }

    private data class CatalogQueryFailure(
        val code: String,
        override val message: String,
        val runId: ExecutionRunId,
    ) : RuntimeException(message)

    private data class CatalogQueryCancelled(val runId: ExecutionRunId) : CancellationException()

    private companion object {
        const val MAXIMUM_CONCURRENT_INLINE_QUERIES: Int = 8
        val catalogIngestLogger = LoggerFactory.getLogger(CatalogIngestCascadeRunner::class.java)
    }

    private fun sqlIdentity(sql: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(sql.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun ConnectorRuntimeBindingSpec.catalogSqlProperties(): Map<String, String> =
    properties.mapValues { (_, property) ->
        when (property) {
            is ConnectorRuntimePropertyValue.Text -> property.value
            is ConnectorRuntimePropertyValue.Integer -> property.value.toString()
        }
    }
