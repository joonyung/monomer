@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.application.ingest

import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogListRefreshJobId
import cc.monomer.catalog.api.hasCatalogLevel
import cc.monomer.catalog.application.CatalogInlineArrowBatch
import cc.monomer.catalog.application.CatalogInlineQueryCommand
import cc.monomer.catalog.application.CatalogInlineQueryPort
import cc.monomer.catalog.application.CatalogInlineQueryResult
import cc.monomer.catalog.domain.CatalogListChildKind
import cc.monomer.catalog.domain.CatalogListOption
import cc.monomer.catalog.domain.CatalogListOptionPage
import cc.monomer.catalog.domain.CatalogListRefreshJob
import cc.monomer.catalog.domain.CatalogListRefreshJobState
import cc.monomer.catalog.domain.CatalogListRefreshTarget
import cc.monomer.catalog.application.CatalogListRefreshRepository
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.ingestion.CatalogMetadataParser
import cc.monomer.catalog.domain.ingestion.CatalogQueryKind
import cc.monomer.catalog.domain.ingestion.CatalogQueryRow
import cc.monomer.catalog.domain.ingestion.CatalogSqlGenerator
import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.WorkspaceId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.MDC
import org.slf4j.LoggerFactory

/** Catalog-owned application boundary for lazily fetching selection names. */
class CatalogListRefreshApplicationService(
    private val repository: CatalogListRefreshRepository,
    private val transactionPort: CatalogTransactionPort,
    private val connectorExecutionAccessUseCase: ConnectorExecutionAccessUseCase,
    private val connectorAccessInOwnerTransaction: ConnectorExecutionAccessInOwnerTransactionPort,
    private val connectorRuntimeBindingProjection: ConnectorRuntimeBindingProjectionPort,
    private val createInlineQueryPort: (ConnectorRuntimeBindingSpec, WorkspaceId, ExecutionOwnerReference, CatalogTransactionPort) -> CatalogInlineQueryPort,
    private val ownerExecutionRunCancellation: OwnerExecutionRunCancellationPort,
    private val clock: Clock,
    private val runnerScope: kotlinx.coroutines.CoroutineScope,
    private val runnerInstanceId: String,
    private val heartbeatInterval: Duration,
    private val decodeBatch: suspend (CatalogInlineArrowBatch) -> List<CatalogQueryRow>,
    private val failureSqlStates: (Throwable) -> String,
) : CatalogListRefreshApplicationPort {
    init {
        require(runnerInstanceId.isNotBlank())
        require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero)
    }

    override suspend fun startListRefresh(
        command: StartCatalogListRefreshCommand,
    ): CatalogCommandResult<CatalogListRefreshJobView> {
        val connectorType = readConnectorType(command.authorization.workspaceId, command.connectorId)
            ?: return CatalogCommandResult.NotFoundOrOutsideWorkspace
        val target = runCatching { resolveTarget(command.parent, connectorType) }.getOrNull()
            ?: return CatalogCommandResult.Rejected(listOf("list refresh target does not match connector depth"))
        val now = clock.instant()
        val admission = transaction {
            repository.lockConnector(command.authorization.workspaceId, command.connectorId)
            if (connectorAccessInOwnerTransaction.readCurrentForExecutionInOwnerTransaction(
                    command.authorization.workspaceId,
                    command.connectorId,
                ) !is ConnectorExecutionAccessResult.Granted
            ) {
                return@transaction ListRefreshAdmission.ConnectorNotFound
            }
            repository.findActiveListRefreshJob(
                command.authorization.workspaceId,
                command.connectorId,
                target.key,
            )?.let { return@transaction ListRefreshAdmission.Existing(it) }
            val job = CatalogListRefreshJob.request(
                id = CatalogListRefreshJobId.generate(),
                workspaceId = command.authorization.workspaceId,
                connectorId = command.connectorId,
                target = target,
                requestedBy = command.authorization.actor.id,
                requestedAt = now,
                credentialSurface = command.authorization.credentialSurface,
                agentClientId = command.authorization.agentClientId,
            )
            repository.insertListRefreshJob(job)
            ListRefreshAdmission.Admitted(job)
        }
        val job = when (admission) {
            ListRefreshAdmission.ConnectorNotFound -> return CatalogCommandResult.NotFoundOrOutsideWorkspace
            is ListRefreshAdmission.Existing -> admission.job
            is ListRefreshAdmission.Admitted -> {
                runnerScope.launch(MDCContext(MDC.get("request_id")?.let { mapOf("request_id" to it) } ?: emptyMap())) {
                    runClaimedListRefresh(admission.job)
                }
                admission.job
            }
        }
        return CatalogCommandResult.Accepted(CatalogListRefreshJobView(job))
    }

    override suspend fun findListOptions(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        page: Int,
        pageSize: Int,
        query: String?,
    ): CatalogListOptionPageView? {
        val connectorType = readConnectorType(workspaceId, connectorId) ?: return null
        val target = runCatching { resolveTarget(parent, connectorType) }.getOrNull() ?: return null
        return CatalogListOptionPageView(
            readOnlyTransaction { repository.findListOptions(workspaceId, connectorId, target, page, pageSize, query) },
        )
    }

    override suspend fun findListOptionsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        afterName: String?,
        limit: Int,
    ): CatalogListOptionPageView? {
        val connectorType = readConnectorType(workspaceId, connectorId) ?: return null
        val target = runCatching { resolveTarget(parent, connectorType) }.getOrNull() ?: return null
        return CatalogListOptionPageView(
            readOnlyTransaction { repository.findListOptionsAfter(workspaceId, connectorId, target, afterName, limit) },
        )
    }

    override suspend fun findListOption(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        name: String,
    ): CatalogListOptionReadView? {
        val connectorType = readConnectorType(workspaceId, connectorId) ?: return null
        val target = runCatching { resolveTarget(parent, connectorType) }.getOrNull() ?: return null
        val read = readOnlyTransaction { repository.findListOption(workspaceId, connectorId, target, name) }
        return CatalogListOptionReadView(fetchedAt = read.fetchedAt, option = read.option)
    }

    override suspend fun findListRefreshJob(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        jobId: CatalogListRefreshJobId,
    ): CatalogListRefreshJobView? {
        return readOnlyTransaction { repository.findListRefreshJob(jobId) }?.takeIf { job ->
            val target = job.target
            job.workspaceId == workspaceId && job.connectorId == connectorId && when (parent) {
                CatalogListRefreshParent.Connector -> target is CatalogListRefreshTarget.Connector
                is CatalogListRefreshParent.Catalog ->
                    target is CatalogListRefreshTarget.Catalog && target.name == parent.name
                is CatalogListRefreshParent.Schema ->
                    target is CatalogListRefreshTarget.Schema &&
                        target.catalog == parent.catalogName && target.name == parent.name
            }
        }?.let(::CatalogListRefreshJobView)
    }

    override suspend fun findListRefreshJobsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        cursor: CatalogListRefreshHistoryCursor?,
        limit: Int,
    ): List<CatalogListRefreshJobView>? {
        val connectorType = readConnectorType(workspaceId, connectorId) ?: return null
        val target = runCatching { resolveTarget(parent, connectorType) }.getOrNull() ?: return null
        return readOnlyTransaction {
            repository.findListRefreshJobsAfter(
                workspaceId,
                connectorId,
                target.key,
                cursor?.requestedAt,
                cursor?.jobId,
                limit,
            )
        }.map(::CatalogListRefreshJobView)
    }

    override suspend fun cancelListRefresh(
        command: CancelCatalogListRefreshCommand,
    ): CatalogCommandResult<CatalogListRefreshJobView> {
        val cancellation = try {
            transaction {
                val current = repository.lockListRefreshJob(command.jobId)
                    ?: return@transaction ListRefreshCancellation.NotFound
                if (
                    current.workspaceId != command.authorization.workspaceId ||
                    current.connectorId != command.connectorId
                ) return@transaction ListRefreshCancellation.NotFound
                val requestedAt = clock.instant()
                when (current.state) {
                    CatalogListRefreshJobState.RUNNING -> {
                        if (current.runnerInstanceId == null) {
                            val cancelled = repository.cancelListRefreshJob(current.id, null, requestedAt)
                                ?: return@transaction ListRefreshCancellation.NotFound
                            ListRefreshCancellation.Accepted(cancelled)
                        } else {
                            val requested = current.requestCancellation()
                            check(repository.updateListRefreshJob(requested))
                            current.activeRunId?.let { runId ->
                                requireRunCancellation(
                                    ownerExecutionRunCancellation.requestCancellationInOwnerTransaction(
                                        OwnerExecutionRunCancellationPort.RequestCancellationCommand(
                                            runId = runId,
                                            owner = ExecutionOwnerReference(
                                                type = ExecutionOwnerType.CATALOG,
                                                id = ExecutionOwnerId(current.id.value),
                                            ),
                                            workspaceId = current.workspaceId,
                                            reasonCode = command.reasonCode,
                                            requestedAt = requestedAt,
                                        ),
                                    ),
                                )
                            }
                            ListRefreshCancellation.Accepted(requested)
                        }
                    }
                    CatalogListRefreshJobState.CANCEL_REQUESTED ->
                        ListRefreshCancellation.Accepted(current)
                    CatalogListRefreshJobState.COMPLETED,
                    CatalogListRefreshJobState.COMPLETED_WITH_ERRORS,
                    CatalogListRefreshJobState.CANCELLED,
                    -> ListRefreshCancellation.Terminal
                }
            }
        } catch (_: CatalogRunCancellationConflictException) {
            return CatalogCommandResult.Conflict
        }
        return when (cancellation) {
            ListRefreshCancellation.NotFound -> CatalogCommandResult.NotFoundOrOutsideWorkspace
            ListRefreshCancellation.Terminal -> CatalogCommandResult.Conflict
            is ListRefreshCancellation.Accepted ->
                CatalogCommandResult.Accepted(CatalogListRefreshJobView(cancellation.job))
        }
    }

    /** Called by the shared Catalog lifecycle for a discovered unowned list-refresh job. */
    suspend fun runClaimedListRefresh(job: CatalogListRefreshJob) {
        val claimed = transaction {
            repository.claimListRefreshJob(job.id, runnerInstanceId, clock.instant())
        } ?: return
        coroutineScope {
            val heartbeatJob = launch {
                while (isActive) {
                    delay(heartbeatInterval.toMillis())
                    if (isActive) heartbeat(claimed.id)
                }
            }
            try {
                runClaimed(claimed)
            } finally {
                heartbeatJob.cancelAndJoin()
            }
        }
    }

    private suspend fun heartbeat(jobId: CatalogListRefreshJobId) {
        try {
            transaction {
                repository.heartbeatListRefreshJob(jobId, runnerInstanceId, clock.instant())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            catalogListRefreshLogger.atWarn()
                .addKeyValue("event", "catalog_list_refresh_heartbeat_failed")
                .addKeyValue("catalog_job_id", jobId.toString())
                .setCause(failure)
                .addKeyValue("failure_types", failure.causeTypeChain())
                .addKeyValue("sql_states", failureSqlStates(failure))
                .log("catalog_list_refresh_heartbeat_failed")
        }
    }

    fun launchClaimedListRefresh(job: CatalogListRefreshJob): Job =
        runnerScope.launch(MDCContext(emptyMap())) { runClaimedListRefresh(job) }

    suspend fun terminalizeStaleListRefresh(
        jobId: CatalogListRefreshJobId,
        staleAfter: Duration,
    ) {
        transaction {
            repository.failStaleListRefreshJob(jobId, clock.instant(), staleAfter)
        }
    }

    private suspend fun runClaimed(job: CatalogListRefreshJob) {
        val binding = captureConnectorBinding(job)
        if (binding == null) {
            failOrCancel(job.id, "CONNECTOR_UNAVAILABLE", "Connector is no longer available")
            return
        }
        val plan = runCatching {
            CatalogSqlGenerator.forTarget(
                ConnectorProductType.fromStorageValue(binding.connectorType.name),
                binding.catalogSqlProperties(),
                job.target.asIngestTarget(),
                CatalogQueryKind.CHILD_LIST,
            )
        }.getOrElse {
            failOrCancel(job.id, "CATALOG_LIST_QUERY_UNSUPPORTED", "Catalog list query is not supported")
            return
        }
        val rows = mutableListOf<CatalogQueryRow>()
        val owner = ExecutionOwnerReference(
            type = ExecutionOwnerType.CATALOG,
            id = ExecutionOwnerId(job.id.value),
        )
        val inlineQuery: CatalogInlineQueryPort = createInlineQueryPort(binding, job.workspaceId, owner, transactionPort)
        try {
            val result = inlineQuery.execute(
                command = CatalogInlineQueryCommand(
                    workspaceId = job.workspaceId,
                    connectorId = job.connectorId,
                    connectorBinding = binding,
                    executionReservationKey = "catalog-list-refresh:${job.id}",
                    sql = plan.sql,
                    parameters = plan.parameters,
                ),
                onRunAdmittedInOwnerTransaction = { runId ->
                    if (!repository.setActiveListRefreshRun(job.id, runId, runnerInstanceId)) {
                        throw ListRefreshCancellationException()
                    }
                },
                onBatch = { batch -> rows += decodeBatch(batch) },
            )
            when (result) {
                is CatalogInlineQueryResult.Succeeded -> {
                    val options = parseOptions(
                        job.target.childKind,
                        CatalogMetadataParser.canonicalRows(
                            ConnectorProductType.fromStorageValue(binding.connectorType.name),
                            binding.catalogSqlProperties(),
                            plan.kind,
                            rows,
                        ),
                    )
                    transaction {
                        repository.lockConnector(job.workspaceId, job.connectorId)
                        val current = repository.lockListRefreshJob(job.id) ?: return@transaction
                        when (current.state) {
                            CatalogListRefreshJobState.CANCEL_REQUESTED ->
                                repository.cancelListRefreshJob(job.id, runnerInstanceId, clock.instant())
                            CatalogListRefreshJobState.RUNNING ->
                                repository.completeListRefreshWithOptions(
                                    job = job,
                                    options = options,
                                    fetchedAt = clock.instant(),
                                    runnerInstanceId = runnerInstanceId,
                                )
                            CatalogListRefreshJobState.COMPLETED,
                            CatalogListRefreshJobState.COMPLETED_WITH_ERRORS,
                            CatalogListRefreshJobState.CANCELLED,
                            -> Unit
                        }
                    }
                }
                is CatalogInlineQueryResult.Cancelled -> failOrCancel(
                    job.id,
                    "EXECUTION_CANCELLED",
                    "Catalog list query was cancelled by Execution",
                )
                is CatalogInlineQueryResult.Failed -> failOrCancel(
                    job.id,
                    result.code,
                    result.safeMessage,
                )
            }
        } catch (cancelled: ListRefreshCancellationException) {
            cancel(job.id)
        } catch (cancelled: CancellationException) {
            if (!cancelIfRequested(job.id)) throw cancelled
        } catch (failure: Throwable) {
            catalogListRefreshLogger.atError()
                .addKeyValue("event", "catalog_list_refresh_failed")
                .addKeyValue("catalog_job_id", job.id.toString())
                .setCause(failure)
                .addKeyValue("failure_types", failure.causeTypeChain())
                .addKeyValue("sql_states", failureSqlStates(failure))
                .log("catalog_list_refresh_failed")
            failOrCancel(job.id, "CATALOG_LIST_REFRESH_FAILED", "Catalog list refresh failed")
        }
    }

    private suspend fun captureConnectorBinding(job: CatalogListRefreshJob): ConnectorRuntimeBindingSpec? = try {
        when (
            val access = connectorExecutionAccessUseCase.readCurrentForExecution(
                job.workspaceId,
                job.connectorId,
            )
        ) {
            ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace -> null
            is ConnectorExecutionAccessResult.Granted ->
                runCatching { connectorRuntimeBindingProjection.project(access.binding) }.getOrNull()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun readConnectorType(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorProductType? = when (
        val access = connectorExecutionAccessUseCase.readCurrentForExecution(workspaceId, connectorId)
    ) {
        ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace -> null
        is ConnectorExecutionAccessResult.Granted ->
            runCatching { ConnectorProductType.fromStorageValue(access.binding.connectorType) }.getOrNull()
    }

    private fun resolveTarget(
        parent: CatalogListRefreshParent,
        connectorType: ConnectorProductType,
    ): CatalogListRefreshTarget? = when (parent) {
        CatalogListRefreshParent.Connector -> CatalogListRefreshTarget.Connector(
            if (connectorType.hasCatalogLevel) CatalogListChildKind.CATALOG else CatalogListChildKind.SCHEMA,
        )
        is CatalogListRefreshParent.Catalog ->
            if (connectorType.hasCatalogLevel) CatalogListRefreshTarget.Catalog(parent.name) else null
        is CatalogListRefreshParent.Schema -> when {
            !connectorType.hasCatalogLevel && parent.catalogName == null ->
                CatalogListRefreshTarget.Schema(null, parent.name)
            connectorType.hasCatalogLevel && parent.catalogName != null ->
                CatalogListRefreshTarget.Schema(parent.catalogName, parent.name)
            else -> null
        }
    }

    private fun parseOptions(
        childKind: CatalogListChildKind,
        rows: List<CatalogQueryRow>,
    ): List<CatalogListOption> = when (childKind) {
        CatalogListChildKind.CATALOG -> CatalogMetadataParser.catalogs(rows.asSequence()).map {
            CatalogListOption(it.name, null, true)
        }
        CatalogListChildKind.SCHEMA -> CatalogMetadataParser.schemas(rows.asSequence()).map {
            CatalogListOption(it.schema, null, true)
        }
        CatalogListChildKind.RELATION -> CatalogMetadataParser.relations(rows.asSequence()).map {
            CatalogListOption(it.name, it.relationKind, true)
        }
    }

    private suspend fun failOrCancel(jobId: CatalogListRefreshJobId, code: String, message: String) {
        transaction {
            val current = repository.lockListRefreshJob(jobId) ?: return@transaction
            when (current.state) {
                CatalogListRefreshJobState.CANCEL_REQUESTED ->
                    repository.cancelListRefreshJob(jobId, runnerInstanceId, clock.instant())
                CatalogListRefreshJobState.RUNNING ->
                    repository.failListRefreshJob(jobId, runnerInstanceId, code, message, clock.instant())
                CatalogListRefreshJobState.COMPLETED,
                CatalogListRefreshJobState.COMPLETED_WITH_ERRORS,
                CatalogListRefreshJobState.CANCELLED,
                -> Unit
            }
        }
    }

    private suspend fun cancel(jobId: CatalogListRefreshJobId) {
        transaction { repository.cancelListRefreshJob(jobId, runnerInstanceId, clock.instant()) }
    }

    private suspend fun cancelIfRequested(jobId: CatalogListRefreshJobId): Boolean = transaction {
        val current = repository.lockListRefreshJob(jobId) ?: return@transaction false
        if (current.state != CatalogListRefreshJobState.CANCEL_REQUESTED) return@transaction false
        repository.cancelListRefreshJob(jobId, runnerInstanceId, clock.instant())
        true
    }

    private suspend fun <T> transaction(block: () -> T): T = transactionPort.inTransaction(block)

    private suspend fun <T> readOnlyTransaction(block: () -> T): T = transactionPort.inReadOnlyTransaction(block)

    private fun CatalogListRefreshTarget.asIngestTarget(): CatalogIngestTaskTarget = when (this) {
        is CatalogListRefreshTarget.Connector -> CatalogIngestTaskTarget.Connector
        is CatalogListRefreshTarget.Catalog -> CatalogIngestTaskTarget.Catalog(name)
        is CatalogListRefreshTarget.Schema -> CatalogIngestTaskTarget.Schema(catalog, name)
    }

    private sealed interface ListRefreshAdmission {
        data class Admitted(val job: CatalogListRefreshJob) : ListRefreshAdmission
        data class Existing(val job: CatalogListRefreshJob) : ListRefreshAdmission
        data object ConnectorNotFound : ListRefreshAdmission
    }

    private sealed interface ListRefreshCancellation {
        data object NotFound : ListRefreshCancellation
        data object Terminal : ListRefreshCancellation
        data class Accepted(val job: CatalogListRefreshJob) : ListRefreshCancellation
    }

    private class ListRefreshCancellationException : RuntimeException()
}

interface CatalogListRefreshApplicationPort {
    suspend fun startListRefresh(
        command: StartCatalogListRefreshCommand,
    ): CatalogCommandResult<CatalogListRefreshJobView>

    suspend fun findListOptions(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        page: Int,
        pageSize: Int,
        query: String?,
    ): CatalogListOptionPageView?

    suspend fun findListOptionsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        afterName: String?,
        limit: Int,
    ): CatalogListOptionPageView?

    suspend fun findListOption(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        name: String,
    ): CatalogListOptionReadView?

    suspend fun findListRefreshJob(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        jobId: CatalogListRefreshJobId,
    ): CatalogListRefreshJobView?

    suspend fun findListRefreshJobsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
        cursor: CatalogListRefreshHistoryCursor?,
        limit: Int,
    ): List<CatalogListRefreshJobView>?

    suspend fun cancelListRefresh(
        command: CancelCatalogListRefreshCommand,
    ): CatalogCommandResult<CatalogListRefreshJobView>
}

data class CatalogListRefreshHistoryCursor(
    val requestedAt: Instant,
    val jobId: CatalogListRefreshJobId,
)

data class StartCatalogListRefreshCommand(
    val authorization: AuthorizedExecutionContext,
    val connectorId: ConnectorId,
    val parent: CatalogListRefreshParent,
)

data class CancelCatalogListRefreshCommand(
    val authorization: AuthorizedExecutionContext,
    val connectorId: ConnectorId,
    val jobId: CatalogListRefreshJobId,
    val reasonCode: String,
) {
    init { require(reasonCode.isNotBlank()) }
}

sealed interface CatalogListRefreshParent {
    data object Connector : CatalogListRefreshParent
    data class Catalog(val name: String) : CatalogListRefreshParent
    data class Schema(val catalogName: String?, val name: String) : CatalogListRefreshParent
}

data class CatalogListRefreshJobView(val job: CatalogListRefreshJob)

data class CatalogListOptionPageView(val page: CatalogListOptionPage)

data class CatalogListOptionReadView(
    val fetchedAt: Instant?,
    val option: CatalogListOption?,
)

private val catalogListRefreshLogger = LoggerFactory.getLogger(CatalogListRefreshApplicationService::class.java)
