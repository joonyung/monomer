@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.execution.api.*
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.pages.application.PagesDirectoryUseCase
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.audit.AuditActorContext
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

class DashboardDependencyCleanupAdapter(
    private val cancellation: OwnerExecutionRunCancellationPort,
    private val executionRuns: ExecutionRunQueryPort,
    private val discard: OwnerExecutionRunDiscardPort,
    private val pages: PagesDirectoryUseCase,
    private val drafts: DashboardDraftEventHub,
    private val sessions: DashboardSessionEventHub,
) : DashboardDependencyCleanupPort {
    override fun discardRunsInCallerTransaction(workspaceId: WorkspaceId, runs: List<DashboardDeletionRun>, reason: String, at: Instant): DashboardRunDiscardOutcome {
        for (run in runs) {
            val owner = ExecutionOwnerReference(ExecutionOwnerType.DASHBOARD, ExecutionOwnerId(run.queryRunId.value))
            if (run.running) {
                val outcome = cancellation.requestCancellationInOwnerTransaction(
                    OwnerExecutionRunCancellationPort.RequestCancellationCommand(run.executionRunId, owner, workspaceId, reason, at),
                )
                if (outcome == OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict) {
                    val current = runBlocking { executionRuns.get(run.executionRunId) }
                    if (current == null || current.workspaceId != workspaceId || current.owner != owner || !current.state.isPhysicalTerminal()) {
                        return DashboardRunDiscardOutcome.RETRYABLE_CONFLICT
                    }
                }
            }
            when (discard.discardInOwnerTransaction(OwnerExecutionRunDiscardPort.DiscardCommand(run.executionRunId, owner, workspaceId, at))) {
                is OwnerExecutionRunDiscardPort.DiscardOutcome.Applied,
                is OwnerExecutionRunDiscardPort.DiscardOutcome.Duplicate,
                OwnerExecutionRunDiscardPort.DiscardOutcome.NotFound -> Unit
                OwnerExecutionRunDiscardPort.DiscardOutcome.Conflict -> return DashboardRunDiscardOutcome.RETRYABLE_CONFLICT
            }
        }
        return DashboardRunDiscardOutcome.COMPLETED
    }

    override fun deletePagesInCallerTransaction(command: DeleteDashboardCommand) {
        val actorId = Uuid.parse(command.metadata.actorId)
        pages.deleteDashboardPagesInCallerTransaction(PagesDirectoryUseCase.DeleteDashboardPagesCommand(
            command.workspaceId.value, command.dashboardId.value,
            PageActor.create(if (command.metadata.credentialSurface == CredentialSurface.AGENT) PageActorKind.AGENT else PageActorKind.USER, actorId.toString()),
            AuditActorContext(command.metadata.organizationId.value, actorId, actorId.toString()),
            command.metadata.requestedAt,
        ))
    }

    override fun invalidateAfterCommit(workspaceId: WorkspaceId, dashboardId: DashboardId, draftExisted: Boolean, sessionIds: List<DashboardSessionId>) {
        // PostgreSQL delivers these existing invalidations only when the owner transaction commits.
        if (draftExisted) drafts.emit(dashboardId, "DASHBOARD_DELETED")
        sessionIds.forEach { sessions.emit(it, "DASHBOARD_DELETED") }
    }
}
