@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore

import cc.monomer.control.observability.addCorrelationId
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.connector.application.ManagedCredentialReadPort
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.control.ExecutionAdmissionRequest
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.execution.api.RawSqlRunSpecFactory
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreRunUseCase
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.explore.application.ExploreRunRepository
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.explore.api.StartExploreRunOutcome
import cc.monomer.explore.api.StartSqlExploreRunCommand
import cc.monomer.identity.api.ExecutionIntent
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import org.slf4j.LoggerFactory

class ExploreSqlLaunchService(
    private val connectorExecutionAccessUseCase: ConnectorExecutionAccessUseCase,
    private val connectorRuntimeBindingProjectionPort: ConnectorRuntimeBindingProjectionPort,
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

    suspend fun start(command: LaunchSqlExploreRunCommand): ExploreSqlLaunchOutcome {
        val startedNanos = System.nanoTime()
        val timing = ExploreSqlLaunchTiming()
        var outcomeName = "EXCEPTION"
        try {
            return launch(command, timing).also { outcome -> outcomeName = outcome::class.simpleName ?: "UNKNOWN" }
        } finally {
            logger.atInfo()
                .addKeyValue("event", "explore_sql_launch_timing")
                .addCorrelationId("run_id", timing.runId?.toString())
                .addKeyValue("outcome", outcomeName)
                .addKeyValue("validate_ms", timing.validateNanos?.let { it / 1_000_000 })
                .addKeyValue("admission_ms", timing.admissionNanos?.let { it / 1_000_000 })
                .addKeyValue("total_ms", (System.nanoTime() - startedNanos) / 1_000_000)
                .log("explore_sql_launch_timing")
        }
    }

    private suspend fun launch(
        command: LaunchSqlExploreRunCommand,
        timing: ExploreSqlLaunchTiming,
    ): ExploreSqlLaunchOutcome {
        val requestDigest = exploreRequestDigest("SQL", command.sql)
        val (membership, existing) = consistentSnapshotTransactionRunner.run {
            val decision = controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(
                    workspaceId = command.workspaceId,
                    credential = command.credential,
                    expectedOAuthResource = command.expectedOAuthResource,
                    requiredRole = WorkspaceRole.EXPLORER,
                ),
            )
            decision to if (decision is ControlAdmissionDecision.Admitted) {
                repository.findByAdmissionKey(command.workspaceId, command.idempotencyKey)
            } else null
        }
        val authorization = when (membership) {
            is ControlAdmissionDecision.Admitted -> membership.context
            is ControlAdmissionDecision.Denied -> return ExploreSqlLaunchOutcome.AdmissionDenied(membership)
        }
        if (existing != null) {
            return if (existing.ownerPrincipalId == authorization.actor.id &&
                existing.connectorBinding.connectorId == command.connectorId &&
                existing.kind is ExploreRun.Kind.Sql && existing.requestDigest == requestDigest
            ) {
                ExploreSqlLaunchOutcome.Existing(existing.asView(), command.sql)
            } else ExploreSqlLaunchOutcome.IdempotencyConflict
        }
        val connector = when (
            val access = connectorExecutionAccessUseCase.readCurrentForExecution(
                command.workspaceId,
                command.connectorId,
            )
        ) {
            is ConnectorExecutionAccessResult.Granted -> access.binding
            ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace ->
                return ExploreSqlLaunchOutcome.ConnectorNotFound
        }
        val runtimeBinding = connectorRuntimeBindingProjectionPort.project(connector)
        val draft = preflight.draft(command.workspaceId, command.sql, MAXIMUM_EXPLORE_ROWS)
        timing.runId = draft.executionRunId
        val credentialDocument = preflight.readCredentialDocument(connector, draft.executionDeadline)
            ?: return ExploreSqlLaunchOutcome.ExecutionUnavailable("CREDENTIAL_UNAVAILABLE")
        val validationStartedNanos = System.nanoTime()
        logger.atInfo()
            .addKeyValue("event", "explore_sql_validation_started")
            .addCorrelationId("run_id", draft.executionRunId.toString())
            .addKeyValue("workspace_id", command.workspaceId.toString())
            .addKeyValue("principal_id", authorization.actor.id.toString())
            .addKeyValue("credential_surface", authorization.credentialSurface.name)
            .addKeyValue("agent_client_id", authorization.agentClientId?.value)
            .addKeyValue("connector_type", runtimeBinding.connectorType.name)
            .addKeyValue("sql_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(command.sql.toByteArray(Charsets.UTF_8))))
            .log("explore_sql_validation_started")
        val validation = try {
            preflight.validate(draft, runtimeBinding, credentialDocument)
        } finally {
            timing.validateNanos = System.nanoTime() - validationStartedNanos
        }
        when (validation) {
            is ExploreSqlValidation.Rejected -> return ExploreSqlLaunchOutcome.SqlValidationFailed(validation.code, validation.message)
            is ExploreSqlValidation.Unavailable -> return ExploreSqlLaunchOutcome.ExecutionUnavailable(validation.failureCode)
            ExploreSqlValidation.Accepted -> Unit
        }
        val admissionStartedNanos = System.nanoTime()
        val executionAuthorization = consistentSnapshotTransactionRunner.run {
            controlAdmissionFacade.authorizeExecution(
                ExecutionAdmissionRequest(
                    workspaceId = command.workspaceId,
                    credential = command.credential,
                    expectedOAuthResource = command.expectedOAuthResource,
                    intent = ExecutionIntent.EXPLORE_RUN,
                ),
            )
        }.let { decision ->
            when (decision) {
                is ControlAdmissionDecision.Admitted -> decision.context
                is ControlAdmissionDecision.Denied -> return ExploreSqlLaunchOutcome.AdmissionDenied(decision)
            }
        }
        return when (
            val outcome = exploreRunUseCase.start(
                StartSqlExploreRunCommand(
                    runId = draft.runId,
                    idempotencyKey = command.idempotencyKey,
                    ownerPrincipalId = executionAuthorization.actor.id,
                    credentialSurface = executionAuthorization.credentialSurface,
                    agentClientId = executionAuthorization.agentClientId,
                    connectorBinding = ExploreConnectorBinding(
                        workspaceId = command.workspaceId,
                        connectorId = connector.connectorId,
                        connectorType = runtimeBinding.connectorType,
                    ),
                    requestDigest = requestDigest,
                    executionReservation = preflight.reservation(draft, runtimeBinding),
                    requestedAt = draft.requestedAt,
                ),
            ).also { timing.admissionNanos = System.nanoTime() - admissionStartedNanos }
        ) {
            // A replay matched the request digest of this exact SQL, so both runs recorded this text.
            is StartExploreRunOutcome.Started -> ExploreSqlLaunchOutcome.Started(outcome.run, command.sql)
            is StartExploreRunOutcome.Existing -> ExploreSqlLaunchOutcome.Existing(outcome.run, command.sql)
            StartExploreRunOutcome.IdempotencyConflict -> ExploreSqlLaunchOutcome.IdempotencyConflict
            StartExploreRunOutcome.Conflict -> ExploreSqlLaunchOutcome.Conflict
            StartExploreRunOutcome.Backpressured -> ExploreSqlLaunchOutcome.ExecutionUnavailable("EXECUTION_BACKPRESSURED")
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ExploreSqlLaunchService::class.java)
    }
}

private class ExploreSqlLaunchTiming {
    var runId: ExecutionRunId? = null
    var validateNanos: Long? = null
    var admissionNanos: Long? = null
}

data class LaunchSqlExploreRunCommand(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val idempotencyKey: String,
    val connectorId: ConnectorId,
    val sql: String,
)

sealed interface ExploreSqlLaunchOutcome {
    data class Started(val run: ExploreRunView, val sql: String) : ExploreSqlLaunchOutcome
    data class Existing(val run: ExploreRunView, val sql: String) : ExploreSqlLaunchOutcome
    data class AdmissionDenied(val decision: ControlAdmissionDecision.Denied) : ExploreSqlLaunchOutcome
    data object ConnectorNotFound : ExploreSqlLaunchOutcome
    data class ExecutionUnavailable(override val failureCode: String) : ExploreSqlLaunchOutcome, ExploreExecutionUnavailability
    data class SqlValidationFailed(val code: String, val message: String) : ExploreSqlLaunchOutcome
    data object IdempotencyConflict : ExploreSqlLaunchOutcome
    data object Conflict : ExploreSqlLaunchOutcome
}

private const val MAXIMUM_EXPLORE_ROWS: ULong = 10_000uL
