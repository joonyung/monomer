@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore

import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.connector.application.ManagedCredentialReadPort
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.semantic.api.SemanticPartitionFilter
import cc.monomer.semantic.api.SemanticPartitionCheck
import cc.monomer.execution.api.RawSqlRunSpecFactory
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreRunUseCase
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.explore.application.ExploreRunRepository
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.semantic.application.SemanticRenderInputs
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.explore.api.StartExploreRunOutcome
import cc.monomer.explore.api.StartSemanticExploreRunCommand
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticDefinitionIssue
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticUnsupportedCapability
import cc.monomer.semantic.application.SemanticQueryUseCase
import java.time.Clock

class ExploreSemanticLaunchService(
    private val connectorExecutionAccessUseCase: ConnectorExecutionAccessUseCase,
    private val connectorRuntimeBindingProjectionPort: ConnectorRuntimeBindingProjectionPort,
    private val semanticQueryUseCase: SemanticQueryUseCase,
    managedCredentialReadPort: ManagedCredentialReadPort,
    executionRunPort: ExecutionRunPort,
    private val exploreRunUseCase: ExploreRunUseCase,
    private val repository: ExploreRunRepository,
    rawSqlRunSpecFactory: RawSqlRunSpecFactory,
    private val controlAdmissionFacade: ControlAdmissionFacade,
    private val consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
    clock: Clock,
    runtimeProperties: ControlRuntimeProperties,
) {
    private val preflight = ExploreLaunchPreflight(
        managedCredentialReadPort,
        executionRunPort,
        rawSqlRunSpecFactory,
        clock,
        runtimeProperties,
    )

    suspend fun start(command: LaunchSemanticExploreRunCommand): ExploreSemanticLaunchOutcome {
        val read = consistentSnapshotTransactionRunner.run { readInputs(command) }
        val inputs = when (read) {
            is LaunchInputs.Read -> read
            is LaunchInputs.Rejected -> return read.outcome
        }
        val prepared = when (val preparation = prepare(command, inputs)) {
            is LaunchPreparation.Prepared -> preparation
            is LaunchPreparation.Rejected -> return preparation.outcome
        }
        if (prepared.partitionCheck.validate) {
            val credentialDocument = preflight.readCredentialDocument(prepared.connector, prepared.draft.executionDeadline)
                ?: return ExploreSemanticLaunchOutcome.ExecutionUnavailable("CREDENTIAL_UNAVAILABLE")
            when (val validation = preflight.validate(prepared.draft, prepared.runtimeBinding, credentialDocument)) {
                is ExploreSqlValidation.Rejected -> return if (validation.code == "PARTITION_FILTER_REQUIRED") {
                    ExploreSemanticLaunchOutcome.ValidationRejected(
                        validation.code,
                        "BigQuery needs a filter on the partition column of a table this query reads.",
                        prepared.partitionCheck.partitionFilters.take(16),
                    )
                } else {
                    ExploreSemanticLaunchOutcome.ValidationRejected(validation.code, validation.message, emptyList())
                }
                is ExploreSqlValidation.Unavailable -> return ExploreSemanticLaunchOutcome.ExecutionUnavailable(validation.failureCode)
                ExploreSqlValidation.Accepted -> Unit
            }
        }
        val admittedCommand = when (val decision = consistentSnapshotTransactionRunner.run {
            controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(command.workspaceId, command.credential, command.expectedOAuthResource, WorkspaceRole.EXPLORER),
            )
        }) {
            is ControlAdmissionDecision.Denied -> return ExploreSemanticLaunchOutcome.AdmissionDenied(decision)
            is ControlAdmissionDecision.Admitted -> prepared.command.copy(
                ownerPrincipalId = decision.context.actor.id,
                credentialSurface = decision.context.credentialSurface,
                agentClientId = decision.context.agentClientId,
            )
        }
        return when (val outcome = exploreRunUseCase.start(admittedCommand)) {
            is StartExploreRunOutcome.Started -> ExploreSemanticLaunchOutcome.Started(outcome.run, prepared.renderedSql)
            is StartExploreRunOutcome.Existing -> ExploreSemanticLaunchOutcome.Existing(outcome.run)
            StartExploreRunOutcome.IdempotencyConflict -> ExploreSemanticLaunchOutcome.IdempotencyConflict
            StartExploreRunOutcome.Conflict -> ExploreSemanticLaunchOutcome.Conflict
            StartExploreRunOutcome.Backpressured -> ExploreSemanticLaunchOutcome.ExecutionUnavailable("EXECUTION_BACKPRESSURED")
        }
    }

    private suspend fun readInputs(command: LaunchSemanticExploreRunCommand): LaunchInputs {
        val authorization = when (
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
            is ControlAdmissionDecision.Denied ->
                return LaunchInputs.Rejected(ExploreSemanticLaunchOutcome.AdmissionDenied(decision))
        }
        val existing = repository.findByAdmissionKey(command.workspaceId, command.idempotencyKey)
        if (existing != null) {
            val semantic = existing.kind as? ExploreRun.Kind.Semantic
            return LaunchInputs.Rejected(
                if (existing.ownerPrincipalId == authorization.actor.id &&
                    existing.connectorBinding.connectorId == command.connectorId &&
                    existing.requestDigest == command.requestDigest && semantic?.query == command.query
                ) {
                    ExploreSemanticLaunchOutcome.Existing(existing.asView())
                } else ExploreSemanticLaunchOutcome.IdempotencyConflict,
            )
        }
        val renderInputs = semanticQueryUseCase.readRenderInputs(
            SemanticRenderRequest(command.workspaceId, command.connectorId, command.query),
        )
        val connector = connectorExecutionAccessUseCase.readCurrentForExecution(command.workspaceId, command.connectorId)
        return LaunchInputs.Read(authorization, renderInputs, connector)
    }

    private suspend fun prepare(command: LaunchSemanticExploreRunCommand, inputs: LaunchInputs.Read): LaunchPreparation {
        val render = when (val result = semanticQueryUseCase.renderFromInputs(inputs.renderInputs)) {
            is SemanticRenderResult.Rendered -> result.render
            is SemanticRenderResult.Invalid ->
                return LaunchPreparation.Rejected(ExploreSemanticLaunchOutcome.Invalid(result.reasons, result.definitionIssues))
            is SemanticRenderResult.Unsupported ->
                return LaunchPreparation.Rejected(ExploreSemanticLaunchOutcome.Unsupported(result.capabilities))
            is SemanticRenderResult.MetricNotFound ->
                return LaunchPreparation.Rejected(ExploreSemanticLaunchOutcome.MetricNotFound(result.name))
            SemanticRenderResult.NotFoundOrOutsideWorkspace ->
                return LaunchPreparation.Rejected(ExploreSemanticLaunchOutcome.ConnectorNotFound)
        }
        if (render.workspaceId != command.workspaceId) {
            return LaunchPreparation.Rejected(ExploreSemanticLaunchOutcome.NotFound)
        }
        val connector = when (val access = inputs.connector) {
            is ConnectorExecutionAccessResult.Granted -> access.binding
            ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace ->
                return LaunchPreparation.Rejected(ExploreSemanticLaunchOutcome.ConnectorNotFound)
        }
        val authorization = inputs.authorization
        val draft = preflight.draft(command.workspaceId, render.sql, command.query.rowLimit.toULong())
        val runtimeBinding = connectorRuntimeBindingProjectionPort.project(connector)
        val executionReservation = preflight.reservation(draft, runtimeBinding)
        return LaunchPreparation.Prepared(
            draft = draft,
            renderedSql = render.sql,
            connector = connector,
            runtimeBinding = runtimeBinding,
            partitionCheck = render.partitionCheck,
            command = StartSemanticExploreRunCommand(
                runId = draft.runId,
                idempotencyKey = command.idempotencyKey,
                ownerPrincipalId = authorization.actor.id,
                credentialSurface = authorization.credentialSurface,
                agentClientId = authorization.agentClientId,
                connectorBinding = ExploreConnectorBinding(
                    workspaceId = command.workspaceId,
                    connectorId = connector.connectorId,
                    connectorType = runtimeBinding.connectorType,
                ),
                requestDigest = command.requestDigest,
                query = command.query,
                executionReservation = executionReservation,
                requestedAt = draft.requestedAt,
                outputColumnDisplays = render.outputColumns.map { cc.monomer.semantic.api.SemanticOutputColumnDisplay(it.name, it.display) },
                reportingTimeZone = render.reportingTimeZone,
            ),
        )
    }

    private sealed interface LaunchInputs {
        data class Read(
            val authorization: IdentityRoleEvidence,
            val renderInputs: SemanticRenderInputs,
            val connector: ConnectorExecutionAccessResult,
        ) : LaunchInputs
        data class Rejected(val outcome: ExploreSemanticLaunchOutcome) : LaunchInputs
    }

    private sealed interface LaunchPreparation {
        data class Prepared(
            val draft: ExploreLaunchDraft,
            val command: StartSemanticExploreRunCommand,
            val renderedSql: String,
            val connector: cc.monomer.connector.api.ConnectorExecutionBinding,
            val runtimeBinding: cc.monomer.execution.api.ConnectorRuntimeBindingSpec,
            val partitionCheck: SemanticPartitionCheck,
        ) : LaunchPreparation
        data class Rejected(val outcome: ExploreSemanticLaunchOutcome) : LaunchPreparation
    }
}

data class LaunchSemanticExploreRunCommand(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val idempotencyKey: String,
    val connectorId: ConnectorId,
    val requestDigest: ExploreDigest,
    val query: SemanticQuerySpec,
)

sealed interface ExploreSemanticLaunchOutcome {
    /** [sql] is the SQL this admission rendered and recorded. */
    data class Started(val run: ExploreRunView, val sql: String) : ExploreSemanticLaunchOutcome

    /**
     * A replay of an earlier admission. Its recorded SQL came from the earlier render, which current
     * definitions may no longer reproduce, so callers read the recorded SQL instead.
     */
    data class Existing(val run: ExploreRunView) : ExploreSemanticLaunchOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreSemanticLaunchOutcome
    data class Invalid(
        val reasons: List<SemanticInvalidReason>,
        val definitionIssues: List<SemanticDefinitionIssue>,
    ) : ExploreSemanticLaunchOutcome
    data class ValidationRejected(val code: String, val message: String, val partitionFilters: List<SemanticPartitionFilter>) : ExploreSemanticLaunchOutcome
    data class ExecutionUnavailable(override val failureCode: String) : ExploreSemanticLaunchOutcome, ExploreExecutionUnavailability
    data class Unsupported(val capabilities: Set<SemanticUnsupportedCapability>) : ExploreSemanticLaunchOutcome
    data object ConnectorNotFound : ExploreSemanticLaunchOutcome
    data class MetricNotFound(val name: cc.monomer.semantic.api.SemanticElementName) : ExploreSemanticLaunchOutcome
    data object NotFound : ExploreSemanticLaunchOutcome
    data object IdempotencyConflict : ExploreSemanticLaunchOutcome
    data object Conflict : ExploreSemanticLaunchOutcome
}

