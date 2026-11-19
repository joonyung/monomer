package cc.monomer.dashboard.application

import cc.monomer.dashboard.api.DashboardOwnerDashboard
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.dashboard.api.DashboardSeedDefinitionUseCase
import cc.monomer.dashboard.api.SeedDashboardDefinitionCommand

class DashboardSeedDefinitionService(private val dashboards: DashboardUseCase) : DashboardSeedDefinitionUseCase {
    override suspend fun seed(command: SeedDashboardDefinitionCommand): DashboardOwnerOutcome<DashboardOwnerDashboard> {
        val result = try {
            dashboards.create(DashboardUseCase.CreateCommand(
                workspaceId = command.workspaceId,
                document = command.definition,
                actorId = command.receiptActorId,
                recordedActorId = null,
                idempotencyKey = command.idempotencyKey,
                createdAt = command.createdAt,
                credentialSurface = null,
                agentClientId = null,
            ))
        } catch (error: InvalidDashboardDefinitionException) {
            return DashboardOwnerOutcome.Rejected("INVALID_DASHBOARD_DEFINITION", error.message)
        }
        return when (result) {
            is DashboardResult.Found -> result.dashboard.let {
                DashboardOwnerOutcome.Success(DashboardOwnerDashboard(
                    it.id, it.workspaceId, it.definition.document, it.createdAt, it.updatedAt,
                    it.updatedBy, it.credentialSurface, it.agentClientId,
                ))
            }
            DashboardResult.NotFound -> DashboardOwnerOutcome.NotFound
            DashboardResult.Conflict -> DashboardOwnerOutcome.Conflict(null)
            DashboardResult.IdempotencyConflict -> DashboardOwnerOutcome.Rejected("IDEMPOTENCY_CONFLICT", null)
        }
    }
}
