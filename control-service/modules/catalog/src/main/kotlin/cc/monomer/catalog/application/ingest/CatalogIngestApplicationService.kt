@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.application.ingest

import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogConflictReason
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogIngestStarted
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionState
import cc.monomer.catalog.api.CatalogSelectionView
import cc.monomer.catalog.api.ConnectorCatalogSelection
import cc.monomer.catalog.api.ConnectorCatalogSelectionState
import cc.monomer.catalog.api.ConnectorCatalogSelectionView
import cc.monomer.catalog.api.SaveConnectorCatalogSelectionCommand
import cc.monomer.catalog.api.SaveCatalogSelectionCommand
import cc.monomer.catalog.api.StartCatalogIngestCommand
import cc.monomer.catalog.api.hasCatalogLevel
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestErrorPage
import cc.monomer.catalog.domain.CatalogIngestJobPage
import cc.monomer.catalog.domain.CatalogIngestJobSummary
import cc.monomer.catalog.domain.CatalogIngestJobState
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskState
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogIngestTaskTree
import cc.monomer.catalog.application.CatalogIngestRepository
import cc.monomer.catalog.application.CatalogSelectionRepository
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.MDC

/**
 * Control-side Catalog admission and runtime lifecycle.
 *
 * Catalog owns the durable job/task/current-row records. This composition root owns connector
 * authorization and the runner scope. Catalog's job and task rows
 * are the only durable lifecycle projection for ingestion.
 */
class CatalogIngestApplicationService(
    private val selectionRepository: CatalogSelectionRepository,
    private val repository: CatalogIngestRepository,
    private val transactionPort: CatalogTransactionPort,
    private val connectorExecutionAccessUseCase: ConnectorExecutionAccessUseCase,
    private val connectorAccessInOwnerTransaction: ConnectorExecutionAccessInOwnerTransactionPort,
    private val connectorRuntimeBindingProjection: ConnectorRuntimeBindingProjectionPort,
    private val cascadeRunner: CatalogIngestCascadeRunner,
    private val ownerExecutionRunCancellation: OwnerExecutionRunCancellationPort,
    private val clock: Clock,
    private val runnerScope: CoroutineScope,
    private val runnerInstanceId: String,
) : CatalogIngestApplicationPort {
    init {
        require(runnerInstanceId.isNotBlank())
    }

    override suspend fun saveSelection(
        command: SaveCatalogSelectionCommand,
    ): CatalogCommandResult<CatalogSelectionView> {
        val access = connectorExecutionAccessUseCase.readCurrentForExecution(
            command.authorization.workspaceId,
            command.connectorId,
        )
        if (access !is ConnectorExecutionAccessResult.Granted) {
            return CatalogCommandResult.NotFoundOrOutsideWorkspace
        }
        val connectorType = runCatching {
            ConnectorProductType.fromStorageValue(access.binding.connectorType)
        }.getOrElse {
            return CatalogCommandResult.Rejected(listOf("unsupported connector engine"))
        }
        if (!entryMatchesConnectorType(command.entry, connectorType)) {
            return CatalogCommandResult.Rejected(listOf("selection entry does not match connector depth"))
        }
        val selection = CatalogSelectionState(
            workspaceId = command.authorization.workspaceId,
            connectorId = command.connectorId,
            entry = command.entry,
            selection = command.selection,
            committedBy = command.authorization.actor.id,
            committedAt = clock.instant(),
        )
        val entry = command.entry
        val outcome = transaction {
            when (entry) {
                is CatalogIngestEntry.Connector -> {
                    selectionRepository.lockConnector(command.authorization.workspaceId, command.connectorId)
                    if (connectorRemoved(command.authorization.workspaceId, command.connectorId)) {
                        return@transaction SaveSelectionResult.ConnectorNotFound
                    }
                    selectionRepository.saveSelection(selection)
                    SaveSelectionResult.Accepted
                }

                is CatalogIngestEntry.Catalog -> {
                    selectionRepository.lockConnector(command.authorization.workspaceId, command.connectorId)
                    if (connectorRemoved(command.authorization.workspaceId, command.connectorId)) {
                        return@transaction SaveSelectionResult.ConnectorNotFound
                    }
                    if (!selectionRepository.isConnectorCatalogSelected(
                            command.authorization.workspaceId,
                            command.connectorId,
                            entry.name,
                    )
                    ) {
                        SaveSelectionResult.InvalidCandidate
                    } else {
                        selectionRepository.saveSelection(selection)
                        SaveSelectionResult.Accepted
                    }
                }
            }
        }
        return when (outcome) {
            SaveSelectionResult.Accepted -> CatalogCommandResult.Accepted(selection.toView())
            SaveSelectionResult.ConnectorNotFound -> CatalogCommandResult.NotFoundOrOutsideWorkspace
            SaveSelectionResult.InvalidCandidate -> CatalogCommandResult.ConflictWithReason(
                CatalogConflictReason.INVALID_CANDIDATE,
            )
        }
    }

    override suspend fun saveConnectorCatalogSelection(
        command: SaveConnectorCatalogSelectionCommand,
    ): CatalogCommandResult<ConnectorCatalogSelectionView> {
        val access = connectorExecutionAccessUseCase.readCurrentForExecution(
            command.authorization.workspaceId,
            command.connectorId,
        )
        if (access !is ConnectorExecutionAccessResult.Granted) {
            return CatalogCommandResult.NotFoundOrOutsideWorkspace
        }
        val connectorType = runCatching {
            ConnectorProductType.fromStorageValue(access.binding.connectorType)
        }.getOrElse {
            return CatalogCommandResult.Rejected(listOf("unsupported connector engine"))
        }
        if (!connectorType.hasCatalogLevel) {
            return CatalogCommandResult.Rejected(listOf("Catalog selection is not supported for this connector"))
        }
        val now = clock.instant()
        val committed = ConnectorCatalogSelectionState(
            workspaceId = command.authorization.workspaceId,
            connectorId = command.connectorId,
            selection = command.selection,
            committedBy = command.authorization.actor.id,
            committedAt = now,
        )
        return when (
            val outcome = transaction {
                selectionRepository.lockConnector(command.authorization.workspaceId, command.connectorId)
                if (connectorRemoved(command.authorization.workspaceId, command.connectorId)) {
                    return@transaction ConnectorCatalogSelectionSaveResult.ConnectorNotFound
                }
                val previous = selectionRepository.currentConnectorCatalogSelection(
                    command.authorization.workspaceId,
                    command.connectorId,
                )
                val previousNames = previous?.selection?.selectedCatalogNames.orEmpty()
                val nextNames = command.selection.selectedCatalogNames
                val addedNames = nextNames - previousNames
                if (addedNames.any { catalogName ->
                        catalogName != configuredCatalogName(connectorType, access.binding) &&
                            !selectionRepository.hasCatalogDiscoveryOption(
                                command.authorization.workspaceId,
                                command.connectorId,
                                catalogName,
                            )
                    }
                ) {
                    return@transaction ConnectorCatalogSelectionSaveResult.InvalidCandidate
                }
                val removedNames = previousNames - nextNames
                if (removedNames.any { catalogName ->
                        repository.findActiveIngestJob(
                            command.authorization.workspaceId,
                            CatalogIngestEntry.Catalog(command.connectorId, catalogName).key,
                        ) != null
                    }
                ) {
                    return@transaction ConnectorCatalogSelectionSaveResult.ActiveIngestion
                }
                selectionRepository.saveConnectorCatalogSelection(committed)
                removedNames.forEach { catalogName ->
                    selectionRepository.deleteCatalogSelection(
                        command.authorization.workspaceId,
                        command.connectorId,
                        catalogName,
                    )
                    repository.removeCurrentCatalogSubtree(
                        command.authorization.workspaceId,
                        command.connectorId,
                        catalogName,
                    )
                }
                ConnectorCatalogSelectionSaveResult.Accepted
            }
        ) {
            ConnectorCatalogSelectionSaveResult.Accepted ->
                CatalogCommandResult.Accepted(committed.toView())
            ConnectorCatalogSelectionSaveResult.ConnectorNotFound -> CatalogCommandResult.NotFoundOrOutsideWorkspace
            ConnectorCatalogSelectionSaveResult.InvalidCandidate -> CatalogCommandResult.ConflictWithReason(
                CatalogConflictReason.INVALID_CANDIDATE,
            )
            ConnectorCatalogSelectionSaveResult.ActiveIngestion -> CatalogCommandResult.ConflictWithReason(
                CatalogConflictReason.ACTIVE_INGESTION,
            )
        }
    }

    override suspend fun currentConnectorCatalogSelection(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorCatalogSelectionView? {
        val access = connectorExecutionAccessUseCase.readCurrentForExecution(workspaceId, connectorId)
        if (access !is ConnectorExecutionAccessResult.Granted) return null
        val connectorType = runCatching {
            ConnectorProductType.fromStorageValue(access.binding.connectorType)
        }.getOrElse { return null }
        if (!connectorType.hasCatalogLevel) return null
        return readOnlyTransaction { selectionRepository.currentConnectorCatalogSelection(workspaceId, connectorId) }?.toView()
            ?: ConnectorCatalogSelectionView(
                workspaceId = workspaceId,
                connectorId = connectorId,
                selection = ConnectorCatalogSelection(emptySet()),
                committedAt = null,
            )
    }

    override suspend fun startIngest(
        command: StartCatalogIngestCommand,
    ): CatalogCommandResult<CatalogIngestStarted> {
        val access = connectorExecutionAccessUseCase.readCurrentForExecution(
            command.authorization.workspaceId,
            command.connectorId,
        )
        val connectorType = when (access) {
            ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace ->
                return CatalogCommandResult.NotFoundOrOutsideWorkspace
            is ConnectorExecutionAccessResult.Granted ->
                runCatching { ConnectorProductType.fromStorageValue(access.binding.connectorType) }
                    .getOrElse { return CatalogCommandResult.Rejected(listOf("unsupported connector engine")) }
        }
        if (!entryMatchesConnectorType(command.entry, connectorType)) {
            return CatalogCommandResult.Rejected(listOf("ingest entry does not match connector depth"))
        }
        val now = clock.instant()
        val entry = command.entry
        val admission = transaction {
            selectionRepository.lockConnector(command.authorization.workspaceId, command.connectorId)
            if (connectorRemoved(command.authorization.workspaceId, command.connectorId)) {
                return@transaction AdmissionResult.ConnectorNotFound
            }
            if (entry is CatalogIngestEntry.Catalog &&
                !selectionRepository.isConnectorCatalogSelected(
                    command.authorization.workspaceId,
                    command.connectorId,
                    entry.name,
                )
            ) {
                return@transaction AdmissionResult.CatalogNotSelected
            }
            repository.findActiveIngestJob(command.authorization.workspaceId, entry.key)?.let {
                return@transaction AdmissionResult.Existing(it)
            }
            val selection = selectionRepository.currentSelection(
                command.authorization.workspaceId,
                command.connectorId,
                entry,
            )?.takeIf {
                it.entry == entry && it.selection.selectedSchemas.isNotEmpty()
            }
                ?: return@transaction AdmissionResult.NoSelection
            val job = CatalogIngestJob.request(
                id = CatalogIngestJobId.generate(),
                workspaceId = command.authorization.workspaceId,
                connectorId = command.connectorId,
                selection = selection.selection,
                entry = entry,
                requestedBy = command.authorization.actor.id,
                requestedAt = now,
                credentialSurface = command.authorization.credentialSurface,
                agentClientId = command.authorization.agentClientId,
            )
            repository.insertIngestJob(job)
            AdmissionResult.Admitted(job)
        }
        val admitted = when (admission) {
            is AdmissionResult.Admitted -> admission.job
            is AdmissionResult.Existing -> admission.job
            AdmissionResult.NoSelection ->
                return CatalogCommandResult.Rejected(listOf("catalog selection is not configured"))
            AdmissionResult.ConnectorNotFound -> return CatalogCommandResult.NotFoundOrOutsideWorkspace
            AdmissionResult.CatalogNotSelected -> return CatalogCommandResult.ConflictWithReason(
                CatalogConflictReason.INVALID_CANDIDATE,
            )
        }
        if (admission is AdmissionResult.Admitted) {
            runnerScope.launch(MDCContext(MDC.get("request_id")?.let { mapOf("request_id" to it) } ?: emptyMap())) {
                runClaimedJob(admitted)
            }
        }
        return CatalogCommandResult.Accepted(admitted.toStarted())
    }

    override suspend fun findJob(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        jobId: CatalogIngestJobId,
    ): CatalogIngestJobView? = readOnlyTransaction { readJobView(workspaceId, jobId) }

    override suspend fun findJobForEntry(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        jobId: CatalogIngestJobId,
    ): CatalogIngestJobView? = findJob(workspaceId, jobId)?.takeIf { view ->
        view.job.connectorId == connectorId && view.job.entry == entry
    }

    override suspend fun findActive(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): CatalogActiveIngestResult {
        if (!entryMatchesCurrentConnector(workspaceId, connectorId, entry)) return CatalogActiveIngestResult.EntryNotFound
        return readOnlyTransaction {
            if (!isSelectedEntry(workspaceId, connectorId, entry)) return@readOnlyTransaction CatalogActiveIngestResult.EntryNotFound
            val job = repository.findActiveIngestJob(workspaceId, entry.key)
                ?: return@readOnlyTransaction CatalogActiveIngestResult.NoActiveIngest
            readJobView(workspaceId, job.id)?.let(CatalogActiveIngestResult::Active)
                ?: CatalogActiveIngestResult.NoActiveIngest
        }
    }

    override suspend fun findJobs(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        page: Int,
        pageSize: Int,
    ): CatalogIngestJobPage = readOnlyTransaction {
        repository.findIngestJobPage(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entryKey = entry.key,
            page = page,
            pageSize = pageSize,
        )
    }

    override suspend fun findJobsAfter(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        cursor: CatalogIngestHistoryCursor?,
        limit: Int,
    ): List<CatalogIngestJobSummary> = readOnlyTransaction {
        repository.findIngestJobsAfter(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entryKey = entry.key,
            requestedBefore = cursor?.requestedAt,
            jobIdBefore = cursor?.jobId,
            limit = limit,
        )
    }

    override suspend fun findErrors(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        jobId: CatalogIngestJobId,
        page: Int,
        pageSize: Int,
    ): CatalogIngestErrorPage? = readOnlyTransaction {
        val job = repository.findIngestJob(jobId) ?: return@readOnlyTransaction null
        if (job.workspaceId != workspaceId || job.connectorId != connectorId) return@readOnlyTransaction null
        repository.findIngestErrorPage(jobId, page, pageSize)
    }

    override suspend fun findErrorsAfter(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        jobId: CatalogIngestJobId,
        cursor: CatalogIngestFailureCursor?,
        limit: Int,
    ): List<CatalogIngestTask>? = readOnlyTransaction {
        val job = repository.findIngestJob(jobId) ?: return@readOnlyTransaction null
        if (job.workspaceId != workspaceId || job.connectorId != connectorId || job.entry != entry) {
            return@readOnlyTransaction null
        }
        repository.findIngestErrorsAfter(jobId, cursor?.terminalAt, cursor?.taskId, limit)
    }

    override suspend fun findFailure(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        jobId: CatalogIngestJobId,
        taskId: cc.monomer.catalog.api.CatalogIngestTaskId,
    ): CatalogIngestTask? = readOnlyTransaction {
        val job = repository.findIngestJob(jobId) ?: return@readOnlyTransaction null
        if (job.workspaceId != workspaceId || job.connectorId != connectorId || job.entry != entry) {
            return@readOnlyTransaction null
        }
        repository.findIngestTask(taskId)?.takeIf { task ->
            task.jobId == jobId && task.state == CatalogIngestTaskState.FAILED
        }
    }

    override suspend fun currentSelection(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): CatalogSelectionView? {
        if (!entryMatchesCurrentConnector(workspaceId, connectorId, entry)) return null
        return readOnlyTransaction {
            if (!isSelectedEntry(workspaceId, connectorId, entry)) return@readOnlyTransaction null
            selectionRepository.currentSelection(workspaceId, connectorId, entry)?.toView()
                ?: CatalogSelectionView(
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    entry = entry,
                    selection = CatalogSelection(emptySet()),
                    committedAt = null,
                )
        }
    }

    override suspend fun cancelIngest(
        command: CatalogIngestCancelCommand,
    ): CatalogCommandResult<CatalogIngestJobView> {
        val cancellation = try {
            transaction {
                val locked = repository.lockIngestJob(command.jobId)
                    ?: return@transaction CancelResult.NotFound
                if (
                    locked.workspaceId != command.authorization.workspaceId ||
                    locked.connectorId != command.connectorId
                ) {
                    return@transaction CancelResult.NotFound
                }
                val requestedAt = clock.instant()
                when (locked.state) {
                    CatalogIngestJobState.RUNNING -> {
                        val immediate = locked.runnerInstanceId == null
                        if (immediate) {
                            check(repository.updateIngestJob(locked.cancel(requestedAt)))
                        } else {
                            check(repository.updateIngestJob(locked.requestCancellation()))
                            val owner = ExecutionOwnerReference(
                                type = ExecutionOwnerType.CATALOG,
                                id = ExecutionOwnerId(locked.id.value),
                            )
                            repository.findIngestTasks(command.jobId).forEach { task ->
                                task.activeRunId?.let { runId ->
                                    requireRunCancellation(
                                        ownerExecutionRunCancellation.requestCancellationInOwnerTransaction(
                                            OwnerExecutionRunCancellationPort.RequestCancellationCommand(
                                                runId = runId,
                                                owner = owner,
                                                workspaceId = locked.workspaceId,
                                                reasonCode = command.reasonCode,
                                                requestedAt = requestedAt,
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                        CancelResult.Accepted
                    }
                    CatalogIngestJobState.CANCEL_REQUESTED -> CancelResult.Accepted
                    CatalogIngestJobState.COMPLETED,
                    CatalogIngestJobState.COMPLETED_WITH_ERRORS,
                    CatalogIngestJobState.CANCELLED,
                    -> CancelResult.AlreadyTerminal
                }
            }
        } catch (_: CatalogRunCancellationConflictException) {
            return CatalogCommandResult.Conflict
        }
        return when (cancellation) {
            CancelResult.NotFound -> CatalogCommandResult.NotFoundOrOutsideWorkspace
            CancelResult.AlreadyTerminal -> CatalogCommandResult.Conflict
            CancelResult.Accepted ->
                findJob(command.authorization.workspaceId, command.jobId)?.let { view ->
                    CatalogCommandResult.Accepted(view)
                }
                    ?: CatalogCommandResult.NotFoundOrOutsideWorkspace
        }
    }

    private sealed interface CancelResult {
        data object Accepted : CancelResult
        data object AlreadyTerminal : CancelResult
        data object NotFound : CancelResult
    }

    /** Called by the application lifecycle for a discovered unowned job. */
    suspend fun runClaimedJob(job: CatalogIngestJob) {
        val claimed = claim(job) ?: return
        val connectorBinding = captureConnectorBinding(claimed)
            ?: return failUnrunnableJob(
                claimed,
                "CONNECTOR_UNAVAILABLE",
                "Connector is no longer available",
            )
        runClaimed(claimed, connectorBinding)
    }

    /** Schedules one repository-discovered job without blocking the lifecycle poller. */
    fun launchClaimedJob(job: CatalogIngestJob): Job =
        runnerScope.launch(MDCContext(emptyMap())) { runClaimedJob(job) }

    /** Terminalizes stale jobs owned by any dead Control instance. */
    suspend fun terminalizeStale(jobId: CatalogIngestJobId, staleAfter: java.time.Duration) {
        transaction {
            repository.failStaleIngestJob(jobId, clock.instant(), staleAfter)
        }
    }

    private suspend fun claim(job: CatalogIngestJob): CatalogIngestJob? = transaction {
        repository.claimIngestJob(job.id, runnerInstanceId, clock.instant())
    }

    private suspend fun runClaimed(job: CatalogIngestJob, connectorBinding: ConnectorRuntimeBindingSpec) {
        cascadeRunner.run(job, connectorBinding, runnerInstanceId)
    }

    /** Reads and projects the current Connector exactly once after a successful runner claim. */
    private suspend fun captureConnectorBinding(job: CatalogIngestJob): ConnectorRuntimeBindingSpec? {
        val access = connectorExecutionAccessUseCase.readCurrentForExecution(
            job.workspaceId,
            job.connectorId,
        )
        return when (access) {
            ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace -> null
            is ConnectorExecutionAccessResult.Granted ->
                runCatching { connectorRuntimeBindingProjection.project(access.binding) }.getOrNull()
        }
    }

    private suspend fun failUnrunnableJob(job: CatalogIngestJob, code: String, message: String) {
        transaction {
            val current = repository.lockIngestJob(job.id) ?: return@transaction
            if (current.state != CatalogIngestJobState.RUNNING) return@transaction
            repository.findIngestTask(job.id, current.entry.taskTargetKey())?.let { task ->
                if (task.state == CatalogIngestTaskState.RUNNING) {
                    check(repository.updateOwnedIngestTask(task.fail(code, message, clock.instant(), null), runnerInstanceId))
                }
            }
            check(repository.updateOwnedIngestJob(current.completeWithErrors(clock.instant()), runnerInstanceId))
        }
    }

    private suspend fun entryMatchesCurrentConnector(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): Boolean {
        if (entry.connectorId != connectorId) return false
        val access = connectorExecutionAccessUseCase.readCurrentForExecution(workspaceId, connectorId)
        val connectorType = when (access) {
            ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace -> return false
            is ConnectorExecutionAccessResult.Granted ->
                runCatching { ConnectorProductType.fromStorageValue(access.binding.connectorType) }
                    .getOrElse { return false }
        }
        return entryMatchesConnectorType(entry, connectorType)
    }

    private fun isSelectedEntry(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): Boolean = entry !is CatalogIngestEntry.Catalog ||
        selectionRepository.isConnectorCatalogSelected(workspaceId, connectorId, entry.name)

    private fun readJobView(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        jobId: CatalogIngestJobId,
    ): CatalogIngestJobView? {
        val summary = repository.findIngestJobSummary(jobId) ?: return null
        val job = summary.job
        if (job.workspaceId != workspaceId) return null
        val tasks = repository.findRunningIngestTasks(jobId)
        return CatalogIngestJobView(
            job = job,
            refreshedRelationCount = summary.refreshedRelationCount,
            runningTaskCount = summary.runningTaskCount,
            errorCount = summary.errorCount,
            tasks = tasks,
            loadingTaskIds = CatalogIngestTaskTree.loadingTaskIds(tasks),
        )
    }

    private fun entryMatchesConnectorType(entry: CatalogIngestEntry, connectorType: ConnectorProductType): Boolean =
        (entry is CatalogIngestEntry.Catalog) == connectorType.hasCatalogLevel

    private fun configuredCatalogName(
        connectorType: ConnectorProductType,
        binding: ConnectorExecutionBinding,
    ): String? {
        val propertyName = when (connectorType) {
            ConnectorProductType.BIGQUERY -> "project_id"
            ConnectorProductType.SNOWFLAKE -> "database"
            ConnectorProductType.DATABRICKS,
            ConnectorProductType.TRINO,
            -> "catalog"
            ConnectorProductType.POSTGRESQL,
            ConnectorProductType.REDSHIFT,
            ConnectorProductType.DUCKDB,
            -> return null
        }
        return binding.properties[propertyName]
    }

    private suspend fun <T> transaction(block: () -> T): T = transactionPort.inTransaction(block)

    private suspend fun <T> readOnlyTransaction(block: () -> T): T = transactionPort.inReadOnlyTransaction(block)

    private sealed interface AdmissionResult {
        data class Admitted(val job: CatalogIngestJob) : AdmissionResult
        data class Existing(val job: CatalogIngestJob) : AdmissionResult
        data object NoSelection : AdmissionResult
        data object CatalogNotSelected : AdmissionResult
        data object ConnectorNotFound : AdmissionResult
    }

    private enum class SaveSelectionResult {
        Accepted,
        InvalidCandidate,
        ConnectorNotFound,
    }

    private enum class ConnectorCatalogSelectionSaveResult {
        Accepted,
        InvalidCandidate,
        ActiveIngestion,
        ConnectorNotFound,
    }

    private fun connectorRemoved(workspaceId: cc.monomer.identity.api.WorkspaceId, connectorId: ConnectorId): Boolean =
        connectorAccessInOwnerTransaction.readCurrentForExecutionInOwnerTransaction(workspaceId, connectorId) !is
            ConnectorExecutionAccessResult.Granted

    private fun CatalogIngestJob.toStarted() = CatalogIngestStarted(
        jobId = id,
        workspaceId = workspaceId,
        connectorId = connectorId,
        entry = entry,
    )

}

interface CatalogIngestApplicationPort {
    suspend fun saveSelection(command: SaveCatalogSelectionCommand): CatalogCommandResult<CatalogSelectionView>
    suspend fun saveConnectorCatalogSelection(
        command: SaveConnectorCatalogSelectionCommand,
    ): CatalogCommandResult<ConnectorCatalogSelectionView>

    suspend fun currentConnectorCatalogSelection(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorCatalogSelectionView?

    suspend fun startIngest(command: StartCatalogIngestCommand): CatalogCommandResult<CatalogIngestStarted>
    suspend fun cancelIngest(command: CatalogIngestCancelCommand): CatalogCommandResult<CatalogIngestJobView>
    suspend fun findJob(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        jobId: CatalogIngestJobId,
    ): CatalogIngestJobView?
    suspend fun findJobForEntry(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        jobId: CatalogIngestJobId,
    ): CatalogIngestJobView?
    suspend fun findActive(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): CatalogActiveIngestResult
    suspend fun findJobs(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        page: Int,
        pageSize: Int,
    ): CatalogIngestJobPage
    suspend fun findJobsAfter(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        cursor: CatalogIngestHistoryCursor?,
        limit: Int,
    ): List<CatalogIngestJobSummary>
    suspend fun findErrors(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        jobId: CatalogIngestJobId,
        page: Int,
        pageSize: Int,
    ): CatalogIngestErrorPage?
    suspend fun findErrorsAfter(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        jobId: CatalogIngestJobId,
        cursor: CatalogIngestFailureCursor?,
        limit: Int,
    ): List<CatalogIngestTask>?
    suspend fun findFailure(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
        jobId: CatalogIngestJobId,
        taskId: cc.monomer.catalog.api.CatalogIngestTaskId,
    ): CatalogIngestTask?
    suspend fun currentSelection(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): CatalogSelectionView?
}

data class CatalogIngestHistoryCursor(
    val requestedAt: java.time.Instant,
    val jobId: CatalogIngestJobId,
)

data class CatalogIngestFailureCursor(
    val terminalAt: java.time.Instant,
    val taskId: cc.monomer.catalog.api.CatalogIngestTaskId,
)

data class CatalogIngestCancelCommand(
    val authorization: AuthorizedExecutionContext,
    val connectorId: ConnectorId,
    val jobId: CatalogIngestJobId,
    val reasonCode: String,
) {
    init { require(reasonCode.isNotBlank()) }
}

data class CatalogIngestJobView(
    val job: CatalogIngestJob,
    val refreshedRelationCount: Int,
    val runningTaskCount: Int,
    val errorCount: Int,
    val tasks: List<CatalogIngestTask>,
    val loadingTaskIds: Set<cc.monomer.catalog.api.CatalogIngestTaskId>,
)

sealed interface CatalogActiveIngestResult {
    data class Active(val view: CatalogIngestJobView) : CatalogActiveIngestResult
    data object NoActiveIngest : CatalogActiveIngestResult
    data object EntryNotFound : CatalogActiveIngestResult
}

private fun CatalogIngestEntry.taskTargetKey(): String = when (this) {
    is CatalogIngestEntry.Connector -> CatalogIngestTaskTarget.Connector.key
    is CatalogIngestEntry.Catalog -> CatalogIngestTaskTarget.Catalog(name).key
}

internal class CatalogRunCancellationConflictException : RuntimeException()

internal fun requireRunCancellation(outcome: OwnerExecutionRunCancellationPort.CancellationOutcome) {
    when (outcome) {
        is OwnerExecutionRunCancellationPort.CancellationOutcome.Applied,
        is OwnerExecutionRunCancellationPort.CancellationOutcome.Duplicate,
        is OwnerExecutionRunCancellationPort.CancellationOutcome.AlreadyTerminal,
        -> Unit
        OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict ->
            throw CatalogRunCancellationConflictException()
    }
}
