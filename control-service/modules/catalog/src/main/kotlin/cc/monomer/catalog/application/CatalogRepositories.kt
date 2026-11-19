@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.application

import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogIngestTaskId
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogListRefreshJobId
import cc.monomer.catalog.api.ConnectorCatalogSelectionState
import cc.monomer.catalog.api.CatalogSelectionState
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobSummary
import cc.monomer.catalog.domain.CatalogIngestJobPage
import cc.monomer.catalog.domain.CatalogIngestErrorPage
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogMetadataObservation
import cc.monomer.catalog.domain.CatalogListRefreshJob
import cc.monomer.catalog.domain.CatalogListRefreshTarget
import cc.monomer.catalog.domain.CatalogListOption
import cc.monomer.catalog.domain.CatalogListOptionPage
import cc.monomer.catalog.domain.CatalogListOptionRead
import cc.monomer.catalog.domain.CatalogQueryParameter
import java.time.Duration
import java.time.Instant

/** Catalog selections. Mutations run inside the Catalog owner transaction under the connector lock. */
interface CatalogSelectionRepository {
    fun lockConnector(workspaceId: WorkspaceId, connectorId: ConnectorId)

    fun currentConnectorCatalogSelection(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorCatalogSelectionState?

    fun saveConnectorCatalogSelection(selection: ConnectorCatalogSelectionState)

    fun isConnectorCatalogSelected(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String,
    ): Boolean

    fun hasCatalogDiscoveryOption(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String,
    ): Boolean

    fun deleteCatalogSelection(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String,
    )

    fun currentSelection(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): CatalogSelectionState?

    fun saveSelection(selection: CatalogSelectionState)
}

/** Ingest jobs, their task tree, and the current objects they write, inside the Catalog owner transaction. */
interface CatalogIngestRepository {
    fun removeCurrentCatalogSubtree(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String,
    )

    fun findIngestJob(id: CatalogIngestJobId): CatalogIngestJob?

    fun findIngestJobSummary(id: CatalogIngestJobId): CatalogIngestJobSummary?

    fun findActiveIngestJob(workspaceId: WorkspaceId, entryKey: String): CatalogIngestJob?

    fun findIngestJobPage(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        entryKey: String,
        page: Int,
        pageSize: Int,
    ): CatalogIngestJobPage

    fun findIngestJobsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        entryKey: String,
        requestedBefore: Instant?,
        jobIdBefore: CatalogIngestJobId?,
        limit: Int,
    ): List<CatalogIngestJobSummary>

    fun findIngestErrorPage(jobId: CatalogIngestJobId, page: Int, pageSize: Int): CatalogIngestErrorPage

    fun findIngestErrorsAfter(
        jobId: CatalogIngestJobId,
        terminalAfter: Instant?,
        taskIdAfter: CatalogIngestTaskId?,
        limit: Int,
    ): List<CatalogIngestTask>

    fun findUnownedRunningIngestJobs(maximumJobs: Int): List<CatalogIngestJob>

    fun findStaleIngestJobs(heartbeatBefore: Instant, maximumJobs: Int): List<CatalogIngestJob>

    fun insertIngestJob(job: CatalogIngestJob)

    /** Non-runner writes are used only for admission and a cancellation request. */
    fun updateIngestJob(job: CatalogIngestJob): Boolean

    /** Runner writes are conditional on the current state and runner instance. */
    fun updateOwnedIngestJob(job: CatalogIngestJob, runnerInstanceId: String): Boolean

    /** Claims an unowned RUNNING job and creates its entry RUNNING task atomically. */
    fun claimIngestJob(
        jobId: CatalogIngestJobId,
        runnerInstanceId: String,
        heartbeatAt: Instant,
    ): CatalogIngestJob?

    fun heartbeatIngestJob(jobId: CatalogIngestJobId, runnerInstanceId: String, heartbeatAt: Instant): Boolean

    fun lockIngestJob(jobId: CatalogIngestJobId): CatalogIngestJob?

    /** Closes stale work as RUNNER_HEARTBEAT_EXPIRED and never resumes it. */
    fun failStaleIngestJob(
        jobId: CatalogIngestJobId,
        now: Instant,
        staleAfter: Duration,
    ): CatalogIngestJob?

    fun findIngestTask(id: CatalogIngestTaskId): CatalogIngestTask?

    fun findIngestTask(jobId: CatalogIngestJobId, targetKey: String): CatalogIngestTask?

    fun findIngestTasks(jobId: CatalogIngestJobId): List<CatalogIngestTask>

    fun findRunningIngestTasks(jobId: CatalogIngestJobId): List<CatalogIngestTask>

    /** Returns or inserts a child task only while the expected runner owns a RUNNING job. */
    fun findOrInsertOwnedIngestTask(task: CatalogIngestTask, runnerInstanceId: String): CatalogIngestTask?

    fun updateOwnedIngestTask(task: CatalogIngestTask, runnerInstanceId: String): Boolean

    fun setActiveRun(taskId: CatalogIngestTaskId, runId: ExecutionRunId, runnerInstanceId: String): Boolean

    /** Applies a container's self observation and child-list result in one owner transaction. */
    fun applyContainer(
        task: CatalogIngestTask,
        selfObservations: List<CatalogMetadataObservation>,
        children: List<CatalogIngestTaskTarget>,
        runnerInstanceId: String,
        observedAt: Instant,
    )

    /** Applies all Relation observations and completes that Relation task atomically. */
    fun completeRelationTask(
        task: CatalogIngestTask,
        relationObservations: List<CatalogMetadataObservation.Relation>,
        columnObservations: List<CatalogMetadataObservation.Column>,
        relationshipObservations: List<CatalogMetadataObservation.RelationshipMember>,
        runnerInstanceId: String,
        observedAt: Instant,
    )
}

/** List refresh jobs and the selection option cache they replace, inside the Catalog owner transaction. */
interface CatalogListRefreshRepository {
    fun lockConnector(workspaceId: WorkspaceId, connectorId: ConnectorId)

    fun findListRefreshJob(id: CatalogListRefreshJobId): CatalogListRefreshJob?

    fun findActiveListRefreshJob(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        targetKey: String,
    ): CatalogListRefreshJob?

    fun findListRefreshJobsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        targetKey: String,
        requestedBefore: Instant?,
        jobIdBefore: CatalogListRefreshJobId?,
        limit: Int,
    ): List<CatalogListRefreshJob>

    fun findUnownedRunningListRefreshJobs(maximumJobs: Int): List<CatalogListRefreshJob>

    fun findStaleListRefreshJobs(heartbeatBefore: Instant, maximumJobs: Int): List<CatalogListRefreshJob>

    fun insertListRefreshJob(job: CatalogListRefreshJob)

    fun updateListRefreshJob(job: CatalogListRefreshJob): Boolean

    fun claimListRefreshJob(
        jobId: CatalogListRefreshJobId,
        runnerInstanceId: String,
        heartbeatAt: Instant,
    ): CatalogListRefreshJob?

    fun heartbeatListRefreshJob(
        jobId: CatalogListRefreshJobId,
        runnerInstanceId: String,
        heartbeatAt: Instant,
    ): Boolean

    fun lockListRefreshJob(jobId: CatalogListRefreshJobId): CatalogListRefreshJob?

    fun failStaleListRefreshJob(
        jobId: CatalogListRefreshJobId,
        now: Instant,
        staleAfter: Duration,
    ): CatalogListRefreshJob?

    fun setActiveListRefreshRun(
        jobId: CatalogListRefreshJobId,
        runId: ExecutionRunId,
        runnerInstanceId: String,
    ): Boolean

    fun completeListRefreshWithOptions(
        job: CatalogListRefreshJob,
        options: List<CatalogListOption>,
        fetchedAt: Instant,
        runnerInstanceId: String,
    ): Boolean

    fun failListRefreshJob(
        jobId: CatalogListRefreshJobId,
        runnerInstanceId: String,
        failureCode: String,
        safeMessage: String,
        terminalAt: Instant,
    ): CatalogListRefreshJob?

    fun cancelListRefreshJob(
        jobId: CatalogListRefreshJobId,
        runnerInstanceId: String?,
        terminalAt: Instant,
    ): CatalogListRefreshJob?

    fun findListOptions(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        target: CatalogListRefreshTarget,
        page: Int,
        pageSize: Int,
        query: String?,
    ): CatalogListOptionPage

    fun findListOptionsAfter(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        target: CatalogListRefreshTarget,
        afterName: String?,
        limit: Int,
    ): CatalogListOptionPage

    fun findListOption(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        target: CatalogListRefreshTarget,
        name: String,
    ): CatalogListOptionRead
}

/** Removal of a deleted Connector's current Catalog state, inside the Catalog owner transaction. */
interface CatalogConnectorStateRepository {
    fun lockConnector(workspaceId: WorkspaceId, connectorId: ConnectorId)

    fun hasActiveConnectorWork(workspaceId: WorkspaceId, connectorId: ConnectorId): Boolean

    fun removeConnectorState(workspaceId: WorkspaceId, connectorId: ConnectorId)
}

const val CATALOG_INGEST_JOB_TASK_PROJECTION_LIMIT = 250

interface CatalogTransactionPort {
    suspend fun <T> inTransaction(block: () -> T): T

    /** Runs owner reads on one read-only snapshot; it joins an active owner transaction instead. */
    suspend fun <T> inReadOnlyTransaction(block: () -> T): T
}

/** Catalog asks the application execution adapter to run one SQL query inline. */
interface CatalogInlineQueryPort {
    suspend fun execute(
        command: CatalogInlineQueryCommand,
        onRunAdmittedInOwnerTransaction: (ExecutionRunId) -> Unit,
        onBatch: suspend (CatalogInlineArrowBatch) -> Unit,
    ): CatalogInlineQueryResult
}

data class CatalogInlineQueryCommand(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val connectorBinding: ConnectorRuntimeBindingSpec,
    val executionReservationKey: String,
    val sql: String,
    val parameters: List<CatalogQueryParameter>,
) {
    init { require(executionReservationKey.isNotBlank()) }
}


data class CatalogInlineArrowBatch(
    val sequence: ULong,
    val arrowIpc: ByteArray,
    val sha256: String,
) {
    init {
        require(sequence > 0uL)
        require(arrowIpc.isNotEmpty())
        require(sha256.matches(Regex("^[0-9a-f]{64}$")))
    }
}

sealed interface CatalogInlineQueryResult {
    val runId: ExecutionRunId

    data class Succeeded(override val runId: ExecutionRunId) : CatalogInlineQueryResult

    data class Failed(
        override val runId: ExecutionRunId,
        val code: String,
        val safeMessage: String,
    ) : CatalogInlineQueryResult

    data class Cancelled(override val runId: ExecutionRunId) : CatalogInlineQueryResult
}
