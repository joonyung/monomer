package cc.monomer.semantic.api

import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.time.ZoneId

/** One unsaved definition checked against the current saved definitions with the draft substituted by name. */
sealed interface SemanticCheckDraft {
    data class Model(
        val semanticModelId: SemanticModelId?,
        val definition: SemanticModelDefinition,
    ) : SemanticCheckDraft

    data class Metric(
        val metricId: MetricId?,
        val definition: SemanticMetricDefinition,
        val connectorId: ConnectorId?,
    ) : SemanticCheckDraft
}

data class SemanticCheckRequest(
    val workspaceId: WorkspaceId,
    val draft: SemanticCheckDraft,
)

enum class SemanticCheckProblemCode {
    NAME_CONFLICT,
    NAME_IMMUTABLE,
    CONNECTOR_NOT_FOUND,
    METRIC_MODEL_NOT_FOUND,
    METRIC_DEPENDENCY_NOT_FOUND,
    SOURCE_RELATION_NOT_FOUND,
    CATALOG_RELATION_NOT_FOUND,
    METRICFLOW_MANIFEST_INVALID,
    QUERY_UNRENDERABLE,
    TIME_SPINE_UNAVAILABLE,
    RELATIONSHIP_JOIN_UNSUPPORTED,
    UNSUPPORTED_DIALECT,
    PARTITION_COLUMN_NOT_MODELED,
    TIME_KIND_UNDECLARED,
    TIME_KIND_MISMATCH,
    TIME_SPINE_COLUMN_TYPE_INVALID,
}

/**
 * `field` is a location inside the draft definition only when the owner knows it for certain;
 * `definition` is another saved definition the finding is about, absent when it concerns the draft
 * itself or no single definition; `detail` is the engine's readable finding when one exists.
 */
data class SemanticCheckProblem(
    val code: SemanticCheckProblemCode,
    val field: String?,
    val connectorId: ConnectorId?,
    val definition: SemanticDefinitionReference?,
    val detail: String?,
)

data class SemanticCheckPreview(
    val connectorId: ConnectorId,
    val dialect: SemanticSqlDialect,
    val sql: String,
    val groupBys: List<SemanticGroupBy>,
)

sealed interface SemanticCheckResult {
    data class Checked(
        val runsOn: List<ConnectorId>,
        val preview: SemanticCheckPreview?,
        val problems: List<SemanticCheckProblem>,
        val partitionFilters: List<SemanticPartitionFilter>,
    ) : SemanticCheckResult

    data object NotFoundOrOutsideWorkspace : SemanticCheckResult
}

data class SemanticCheckInputs(
    val request: SemanticCheckRequest,
    val models: List<SemanticModelView>,
    val metrics: List<MetricView>,
    val connectorDialects: Map<ConnectorId, SemanticSqlDialect?>,
    val catalogs: Map<ConnectorId, CatalogCurrentObjectSet?>,
    val timeSpineConfigurations: List<SemanticTimeSpineConfigurationView>,
    val reportingTimeZone: ZoneId,
)

/** Resolves a draft like the query path does, without contacting a warehouse or persisting anything. */
interface SemanticDefinitionCheckUseCase {
    suspend fun readInputs(request: SemanticCheckRequest): SemanticCheckInputs
    suspend fun checkFromInputs(inputs: SemanticCheckInputs): SemanticCheckResult
    suspend fun check(request: SemanticCheckRequest): SemanticCheckResult
}
