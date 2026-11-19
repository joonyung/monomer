package cc.monomer.dashboard.api

import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Creates a Monomer-authored seed Dashboard. The caller has already authorized [SeedDashboardDefinitionCommand.receiptActorId];
 * the idempotency receipt is keyed by that actor while the current row and definition change record no attribution.
 */
fun interface DashboardSeedDefinitionUseCase {
    suspend fun seed(command: SeedDashboardDefinitionCommand): DashboardOwnerOutcome<DashboardOwnerDashboard>
}

data class SeedDashboardDefinitionCommand(
    val workspaceId: WorkspaceId,
    val definition: JsonObject,
    val receiptActorId: String,
    val idempotencyKey: String,
    val createdAt: Instant,
) {
    init {
        require(receiptActorId.isNotBlank())
        require(idempotencyKey.isNotBlank())
    }
}
