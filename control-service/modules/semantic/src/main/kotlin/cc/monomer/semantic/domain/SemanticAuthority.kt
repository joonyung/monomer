@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.domain

import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import java.time.Instant
import kotlin.uuid.Uuid

data class CurrentSemanticModel(
    val id: SemanticModelId,
    val workspaceId: WorkspaceId,
    val definition: SemanticModelDefinition,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CurrentMetric(
    val id: MetricId,
    val workspaceId: WorkspaceId,
    val definition: SemanticMetricDefinition,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CurrentTimeSpineConfiguration(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val definition: SemanticTimeSpineDefinition,
)

enum class SemanticMutationKind {
    SEMANTIC_MODEL_CREATE,
    SEMANTIC_MODEL_UPDATE,
    METRIC_CREATE,
    METRIC_UPDATE,
}

data class SemanticMutationReceipt(
    val workspaceId: WorkspaceId,
    val idempotencyKey: String,
    val actorId: PrincipalId,
    val mutationKind: SemanticMutationKind,
    val subject: String,
    val responseIdentity: Uuid,
    val committedAt: Instant,
)
