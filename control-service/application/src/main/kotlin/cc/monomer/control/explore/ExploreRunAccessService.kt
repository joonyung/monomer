@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore

import cc.monomer.control.observability.addCorrelationId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.execution.api.ExecutionRunQueryPort
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.explore.api.CancelExploreRunCommand
import cc.monomer.explore.api.CancelExploreRunOutcome
import cc.monomer.explore.api.ExploreResolvedResult
import cc.monomer.explore.api.ExploreResultArtifact
import cc.monomer.explore.api.ExploreResultCsv
import cc.monomer.explore.api.ExploreResultCsvReadOutcome
import cc.monomer.explore.api.ExploreResultDownloadLink
import cc.monomer.explore.api.ExploreResultDownloadLinkReadOutcome
import cc.monomer.explore.api.ExploreResultArtifactReadOutcome
import cc.monomer.explore.api.ExploreResultReadOutcome
import cc.monomer.explore.api.ExploreResultProjection
import cc.monomer.explore.api.ExploreResultProjectionReadOutcome
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunPage
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunUseCase
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.explore.api.ReadExploreResultCommand
import cc.monomer.explore.api.ReadExploreResultArtifactCommand
import cc.monomer.explore.api.ListExploreRunsCommand
import cc.monomer.explore.application.ExploreCancellationHandler
import cc.monomer.explore.application.ExploreRunRepository
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class ExploreRunAccessService(
    private val repository: ExploreRunRepository,
    private val exploreRunUseCase: ExploreRunUseCase,
    private val executionRunQueryPort: ExecutionRunQueryPort,
    private val cancellationHandler: ExploreCancellationHandler,
    private val controlAdmissionFacade: ControlAdmissionFacade,
    private val consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
    private val clock: Clock,
    private val blockingDispatcher: CoroutineDispatcher,
) {
    suspend fun readSnapshot(command: ReadExploreRunCommand): ExploreRunSnapshotOutcome {
        val run = when (val access = admitStoredRunRead(command.workspaceId, command.runId, command.credential, command.expectedOAuthResource)) {
            is StoredRunRead.Admitted -> access.run
            is StoredRunRead.Denied -> return ExploreRunSnapshotOutcome.AdmissionDenied(access.decision)
            StoredRunRead.NotFound -> return ExploreRunSnapshotOutcome.NotFound
        }
        val view = run.asView()
        return readRecordedSql(view)
            ?.let { sql -> ExploreRunSnapshotOutcome.Observed(view, sql) }
            ?: ExploreRunSnapshotOutcome.TemporarilyUnavailable
    }

    /**
     * Recorded SQL for the runs of one admitted listing, read in one batch. Admission writes the
     * Explore run, its ExecutionRun, and the append-only dispatch intent in one transaction, so a run
     * whose SQL cannot be verified is a data invariant violation: it is logged and left out of the
     * listing, and its exact read reports unavailable. A failing read fails the listing.
     */
    suspend fun readRecordedHistory(runs: List<ExploreRunView>): List<ExploreRunRecord> {
        if (runs.isEmpty()) return emptyList()
        val workspaceId = runs.first().workspaceId
        require(runs.all { run -> run.workspaceId == workspaceId })
        val outcome = executionRunQueryPort.readSqlBatch(
            ExecutionRunQueryPort.ReadSqlBatchCommand(
                workspaceId = workspaceId,
                runs = runs.map { run ->
                    ExecutionRunQueryPort.RunOwnerBinding(
                        runId = ExecutionRunId(run.id.value),
                        owner = ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id.value)),
                    )
                },
            ),
        )
        val sqlByRunId = outcome.recorded.associate { recorded -> recorded.runId to recorded.sql }
        outcome.unavailableRunIds.forEach { runId ->
            logger.atError()
                .addKeyValue("event", "explore_run_sql_history_unavailable")
                .addKeyValue("workspace_id", workspaceId.toString())
                .addCorrelationId("run_id", runId.toString())
                .log("explore_run_sql_history_unavailable")
        }
        return runs.mapNotNull { run -> sqlByRunId[ExecutionRunId(run.id.value)]?.let { sql -> ExploreRunRecord(run, sql) } }
    }

    // Callers pass only a run view returned by an admitted read, list, start, or cancel outcome.
    suspend fun readRecordedSql(run: ExploreRunView): String? =
        when (
            val input = executionRunQueryPort.readSql(
                ExecutionRunQueryPort.ReadSqlCommand(
                    runId = ExecutionRunId(run.id.value),
                    workspaceId = run.workspaceId,
                    owner = ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id.value)),
                ),
            )
        ) {
            is ExecutionRunQueryPort.ReadSqlOutcome.Available -> input.sql
            ExecutionRunQueryPort.ReadSqlOutcome.NotFound,
            ExecutionRunQueryPort.ReadSqlOutcome.BindingMismatch,
            ExecutionRunQueryPort.ReadSqlOutcome.Unavailable,
            -> null
        }

    suspend fun read(command: ReadExploreRunCommand): ExploreRunReadOutcome {
        val run = when (val access = admitStoredRunRead(command.workspaceId, command.runId, command.credential, command.expectedOAuthResource)) {
            is StoredRunRead.Admitted -> access.run
            is StoredRunRead.Denied -> return ExploreRunReadOutcome.AdmissionDenied(access.decision)
            StoredRunRead.NotFound -> return ExploreRunReadOutcome.NotFound
        }
        if (run.state != ExploreRunState.SUCCEEDED) {
            return ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.None)
        }
        return when (
            val result = exploreRunUseCase.readVisibleResult(
                ReadExploreResultCommand(
                    runId = command.runId,
                    ownerPrincipalId = run.ownerPrincipalId,
                    previewRowLimit = HUMAN_RUN_PREVIEW_ROW_LIMIT,
                ),
            )
        ) {
            is ExploreResultReadOutcome.Visible ->
                ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.Available(result.result))
            ExploreResultReadOutcome.NotFound -> ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.None)
            ExploreResultReadOutcome.RetentionExpired ->
                ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.Expired)
            ExploreResultReadOutcome.PreviewUnavailable ->
                ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.PreviewUnavailable)
            ExploreResultReadOutcome.AccessDenied -> ExploreRunReadOutcome.AccessDenied
            ExploreResultReadOutcome.TemporarilyUnavailable -> ExploreRunReadOutcome.TemporarilyUnavailable
        }
    }

    suspend fun listRuns(command: ListExploreRunsRequest): ExploreRunListOutcome {
        val membership = when (
            val decision = controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(
                    workspaceId = command.workspaceId,
                    credential = command.credential,
                    expectedOAuthResource = command.expectedOAuthResource,
                    requiredRole = WorkspaceRole.EXPLORER,
                ),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> decision.context
            is ControlAdmissionDecision.Denied -> return ExploreRunListOutcome.AdmissionDenied(decision)
        }
        return ExploreRunListOutcome.Observed(
            exploreRunUseCase.list(
                ListExploreRunsCommand(
                    workspaceId = command.workspaceId,
                    ownerPrincipalId = membership.actor.id,
                    after = command.after,
                    limit = command.limit,
                ),
            ),
        )
    }

    suspend fun readResult(
        command: ReadExploreResultProjectionRequest,
    ): ExploreResultProjectionAccessOutcome {
        val run = when (val access = admitStoredRunRead(command.workspaceId, command.runId, command.credential, command.expectedOAuthResource)) {
            is StoredRunRead.Admitted -> access.run
            is StoredRunRead.Denied -> return ExploreResultProjectionAccessOutcome.AdmissionDenied(access.decision)
            StoredRunRead.NotFound -> return ExploreResultProjectionAccessOutcome.NotFound
        }
        return when (
            val result = exploreRunUseCase.readVisibleResultProjection(
                ReadExploreResultCommand(
                    runId = command.runId,
                    ownerPrincipalId = run.ownerPrincipalId,
                    previewRowLimit = AGENT_RESULT_PREVIEW_ROW_LIMIT,
                ),
            )
        ) {
            is ExploreResultProjectionReadOutcome.Visible ->
                ExploreResultProjectionAccessOutcome.Observed(result.projection)
            ExploreResultProjectionReadOutcome.NotFound -> ExploreResultProjectionAccessOutcome.NotFound
            ExploreResultProjectionReadOutcome.AccessDenied -> ExploreResultProjectionAccessOutcome.AccessDenied
            ExploreResultProjectionReadOutcome.RetentionExpired ->
                ExploreResultProjectionAccessOutcome.RetentionExpired
            ExploreResultProjectionReadOutcome.TemporarilyUnavailable ->
                ExploreResultProjectionAccessOutcome.TemporarilyUnavailable
            ExploreResultProjectionReadOutcome.PreviewUnavailable ->
                ExploreResultProjectionAccessOutcome.PreviewUnavailable
        }
    }

    suspend fun readArtifact(
        command: ReadExploreResultArtifactRequest,
    ): ExploreRunArtifactReadOutcome {
        val run = when (val access = admitArtifactRead(command)) {
            is StoredRunRead.Admitted -> access.run
            is StoredRunRead.Denied -> return ExploreRunArtifactReadOutcome.AdmissionDenied(access.decision)
            StoredRunRead.NotFound -> return ExploreRunArtifactReadOutcome.NotFound
        }
        return when (
            val result = exploreRunUseCase.readVisibleResultArtifact(
                ReadExploreResultArtifactCommand(
                    runId = command.runId,
                    ownerPrincipalId = run.ownerPrincipalId,
                ),
            )
        ) {
            is ExploreResultArtifactReadOutcome.Visible -> ExploreRunArtifactReadOutcome.Visible(result.artifact)
            ExploreResultArtifactReadOutcome.NotFound -> ExploreRunArtifactReadOutcome.NotFound
            ExploreResultArtifactReadOutcome.AccessDenied -> ExploreRunArtifactReadOutcome.AccessDenied
            ExploreResultArtifactReadOutcome.RetentionExpired -> ExploreRunArtifactReadOutcome.RetentionExpired
            ExploreResultArtifactReadOutcome.TemporarilyUnavailable ->
                ExploreRunArtifactReadOutcome.TemporarilyUnavailable
            ExploreResultArtifactReadOutcome.ArtifactUnavailable -> ExploreRunArtifactReadOutcome.ArtifactUnavailable
        }
    }

    suspend fun readCsv(
        command: ReadExploreResultArtifactRequest,
    ): ExploreRunCsvReadOutcome {
        val run = when (val access = admitArtifactRead(command)) {
            is StoredRunRead.Admitted -> access.run
            is StoredRunRead.Denied -> return ExploreRunCsvReadOutcome.AdmissionDenied(access.decision)
            StoredRunRead.NotFound -> return ExploreRunCsvReadOutcome.NotFound
        }
        return when (
            val result = exploreRunUseCase.readVisibleResultCsv(
                ReadExploreResultArtifactCommand(
                    runId = command.runId,
                    ownerPrincipalId = run.ownerPrincipalId,
                ),
            )
        ) {
            is ExploreResultCsvReadOutcome.Visible -> ExploreRunCsvReadOutcome.Visible(result.csv)
            ExploreResultCsvReadOutcome.NotFound -> ExploreRunCsvReadOutcome.NotFound
            ExploreResultCsvReadOutcome.AccessDenied -> ExploreRunCsvReadOutcome.AccessDenied
            ExploreResultCsvReadOutcome.RetentionExpired -> ExploreRunCsvReadOutcome.RetentionExpired
            ExploreResultCsvReadOutcome.TemporarilyUnavailable ->
                ExploreRunCsvReadOutcome.TemporarilyUnavailable
            ExploreResultCsvReadOutcome.UnsupportedSchema -> ExploreRunCsvReadOutcome.UnsupportedSchema
            ExploreResultCsvReadOutcome.ArtifactUnavailable -> ExploreRunCsvReadOutcome.ArtifactUnavailable
        }
    }

    suspend fun readDownloadLink(
        command: ReadExploreResultArtifactRequest,
    ): ExploreRunDownloadLinkReadOutcome {
        val run = when (val access = admitArtifactRead(command)) {
            is StoredRunRead.Admitted -> access.run
            is StoredRunRead.Denied -> return ExploreRunDownloadLinkReadOutcome.AdmissionDenied(access.decision)
            StoredRunRead.NotFound -> return ExploreRunDownloadLinkReadOutcome.NotFound
        }
        return when (
            val result = exploreRunUseCase.readVisibleResultDownloadLink(
                ReadExploreResultArtifactCommand(
                    runId = command.runId,
                    ownerPrincipalId = run.ownerPrincipalId,
                ),
            )
        ) {
            is ExploreResultDownloadLinkReadOutcome.Visible -> ExploreRunDownloadLinkReadOutcome.Visible(result.link)
            ExploreResultDownloadLinkReadOutcome.NotFound -> ExploreRunDownloadLinkReadOutcome.NotFound
            ExploreResultDownloadLinkReadOutcome.AccessDenied -> ExploreRunDownloadLinkReadOutcome.AccessDenied
            ExploreResultDownloadLinkReadOutcome.RetentionExpired -> ExploreRunDownloadLinkReadOutcome.RetentionExpired
            ExploreResultDownloadLinkReadOutcome.TemporarilyUnavailable ->
                ExploreRunDownloadLinkReadOutcome.TemporarilyUnavailable
            ExploreResultDownloadLinkReadOutcome.ArtifactUnavailable -> ExploreRunDownloadLinkReadOutcome.ArtifactUnavailable
        }
    }

    suspend fun cancel(command: CancelExploreRunRequest): ExploreRunCancelOutcome {
        val membership = when (
            val decision = controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(
                    workspaceId = command.workspaceId,
                    credential = command.credential,
                    expectedOAuthResource = command.expectedOAuthResource,
                    requiredRole = WorkspaceRole.EXPLORER,
                ),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> decision.context
            is ControlAdmissionDecision.Denied -> return ExploreRunCancelOutcome.AdmissionDenied(decision)
        }
        val run = findRun(command.runId)
            ?: return ExploreRunCancelOutcome.NotFound
        if (run.workspaceId != command.workspaceId) return ExploreRunCancelOutcome.NotFound
        return when (
            cancellationHandler.cancel(
                CancelExploreRunCommand(
                    workspaceId = command.workspaceId,
                    runId = command.runId,
                    ownerPrincipalId = membership.actor.id,
                    reasonCode = command.reasonCode,
                    requestedAt = clock.instant(),
                ),
            )
        ) {
            CancelExploreRunOutcome.Accepted -> ExploreRunCancelOutcome.Accepted(currentRunView(run))
            CancelExploreRunOutcome.AlreadyTerminal -> ExploreRunCancelOutcome.AlreadyTerminal(currentRunView(run))
            CancelExploreRunOutcome.AccessDenied -> ExploreRunCancelOutcome.AccessDenied
            CancelExploreRunOutcome.NotFound -> ExploreRunCancelOutcome.NotFound
            CancelExploreRunOutcome.ConcurrentConflict -> ExploreRunCancelOutcome.ConcurrentConflict
        }
    }

    // The run can settle between the pre-lock read and the cancellation; answer with what was stored after it.
    private suspend fun currentRunView(beforeCancellation: ExploreRun) =
        (findRun(beforeCancellation.id) ?: beforeCancellation).asView()

    private suspend fun findRun(runId: ExploreRunId): ExploreRun? =
        withContext(blockingDispatcher) { repository.findRun(runId) }

    private suspend fun admitArtifactRead(command: ReadExploreResultArtifactRequest): StoredRunRead =
        admitStoredRunRead(
            command.workspaceId,
            command.runId,
            command.credential,
            command.expectedOAuthResource,
        )

    private suspend fun admitStoredRunRead(
        workspaceId: WorkspaceId,
        runId: ExploreRunId,
        credential: PresentedCredential,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): StoredRunRead {
        val admission = WorkspaceRoleAdmissionRequest(workspaceId, credential, expectedOAuthResource, WorkspaceRole.EXPLORER)
        val decision = consistentSnapshotTransactionRunner.run { controlAdmissionFacade.authorizeWorkspaceRole(admission) }
        if (decision is ControlAdmissionDecision.Denied) return StoredRunRead.Denied(decision)
        val run = findRun(runId)?.takeIf { it.workspaceId == workspaceId }
            ?: return StoredRunRead.NotFound
        return StoredRunRead.Admitted(run)
    }

    private sealed interface StoredRunRead {
        data class Admitted(val run: ExploreRun) : StoredRunRead
        data class Denied(val decision: ControlAdmissionDecision.Denied) : StoredRunRead
        data object NotFound : StoredRunRead
    }
}

data class ExploreRunRecord(val run: ExploreRunView, val sql: String) {
    override fun toString(): String = "ExploreRunRecord(run=$run, sql=[REDACTED])"
}

data class ReadExploreRunCommand(
    val workspaceId: WorkspaceId,
    val runId: ExploreRunId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
)

data class ListExploreRunsRequest(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val after: ExploreRunPageBoundary?,
    val limit: Int,
) {
    init {
        require(limit in 1..cc.monomer.explore.api.MAX_EXPLORE_RUN_PAGE_SIZE)
    }
}

data class ReadExploreResultProjectionRequest(
    val workspaceId: WorkspaceId,
    val runId: ExploreRunId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
)

data class ReadExploreResultArtifactRequest(
    val workspaceId: WorkspaceId,
    val runId: ExploreRunId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
)

data class CancelExploreRunRequest(
    val workspaceId: WorkspaceId,
    val runId: ExploreRunId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val reasonCode: String,
) {
    init {
        require(reasonCode.isNotBlank())
    }
}

sealed interface ExploreRunReadOutcome {
    /** The run is always readable; its result state says whether a preview exists. */
    data class Observed(
        val run: ExploreRunView,
        val result: ExploreRunResultRead,
    ) : ExploreRunReadOutcome

    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreRunReadOutcome
    data object NotFound : ExploreRunReadOutcome
    data object AccessDenied : ExploreRunReadOutcome
    data object TemporarilyUnavailable : ExploreRunReadOutcome
}

sealed interface ExploreRunResultRead {
    data class Available(val result: ExploreResolvedResult) : ExploreRunResultRead
    /** The run has not succeeded, or succeeded without a visible result. */
    data object None : ExploreRunResultRead
    data object Expired : ExploreRunResultRead
    data object PreviewUnavailable : ExploreRunResultRead
}

sealed interface ExploreRunSnapshotOutcome {
    data class Observed(val run: ExploreRunView, val sql: String) : ExploreRunSnapshotOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreRunSnapshotOutcome
    data object NotFound : ExploreRunSnapshotOutcome
    data object AccessDenied : ExploreRunSnapshotOutcome
    data object TemporarilyUnavailable : ExploreRunSnapshotOutcome
}

sealed interface ExploreRunListOutcome {
    data class Observed(val page: ExploreRunPage) : ExploreRunListOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreRunListOutcome
}

sealed interface ExploreResultProjectionAccessOutcome {
    data class Observed(val projection: ExploreResultProjection) : ExploreResultProjectionAccessOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreResultProjectionAccessOutcome
    data object NotFound : ExploreResultProjectionAccessOutcome
    data object AccessDenied : ExploreResultProjectionAccessOutcome
    data object RetentionExpired : ExploreResultProjectionAccessOutcome
    data object TemporarilyUnavailable : ExploreResultProjectionAccessOutcome
    data object PreviewUnavailable : ExploreResultProjectionAccessOutcome
}

sealed interface ExploreRunArtifactReadOutcome {
    data class Visible(val artifact: ExploreResultArtifact) : ExploreRunArtifactReadOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreRunArtifactReadOutcome
    data object NotFound : ExploreRunArtifactReadOutcome
    data object AccessDenied : ExploreRunArtifactReadOutcome
    data object RetentionExpired : ExploreRunArtifactReadOutcome
    data object TemporarilyUnavailable : ExploreRunArtifactReadOutcome
    data object ArtifactUnavailable : ExploreRunArtifactReadOutcome
}

sealed interface ExploreRunCancelOutcome {
    data class Accepted(val run: ExploreRunView) : ExploreRunCancelOutcome
    data class AlreadyTerminal(val run: ExploreRunView) : ExploreRunCancelOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreRunCancelOutcome
    data object AccessDenied : ExploreRunCancelOutcome
    data object NotFound : ExploreRunCancelOutcome
    data object ConcurrentConflict : ExploreRunCancelOutcome
}

private const val HUMAN_RUN_PREVIEW_ROW_LIMIT = 1_000
private const val AGENT_RESULT_PREVIEW_ROW_LIMIT = 200
private val logger = org.slf4j.LoggerFactory.getLogger(ExploreRunAccessService::class.java)

sealed interface ExploreRunCsvReadOutcome {
    data class Visible(val csv: ExploreResultCsv) : ExploreRunCsvReadOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreRunCsvReadOutcome
    data object NotFound : ExploreRunCsvReadOutcome
    data object AccessDenied : ExploreRunCsvReadOutcome
    data object RetentionExpired : ExploreRunCsvReadOutcome
    data object TemporarilyUnavailable : ExploreRunCsvReadOutcome
    data object ArtifactUnavailable : ExploreRunCsvReadOutcome
    data object UnsupportedSchema : ExploreRunCsvReadOutcome
}

sealed interface ExploreRunDownloadLinkReadOutcome {
    data class Visible(val link: ExploreResultDownloadLink) : ExploreRunDownloadLinkReadOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreRunDownloadLinkReadOutcome
    data object NotFound : ExploreRunDownloadLinkReadOutcome
    data object AccessDenied : ExploreRunDownloadLinkReadOutcome
    data object RetentionExpired : ExploreRunDownloadLinkReadOutcome
    data object TemporarilyUnavailable : ExploreRunDownloadLinkReadOutcome
    data object ArtifactUnavailable : ExploreRunDownloadLinkReadOutcome
}
