@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.CredentialSurface
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

@JvmInline
value class SemanticModelId(val value: Uuid) {
    init { value.requireUuidV7("semantic model id") }

    companion object {
        fun generate(): SemanticModelId = SemanticModelId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class MetricId(val value: Uuid) {
    init { value.requireUuidV7("metric id") }

    companion object {
        fun generate(): MetricId = MetricId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

data class PutSemanticModelCommand(
    val authorization: AuthorizedMutationContext,
    val idempotencyKey: String,
    val semanticModelId: SemanticModelId?,
    val definition: SemanticModelDefinition,
    val aggregateMetrics: List<SemanticAggregateMetric>,
) {
    init { require(idempotencyKey.isNotBlank()) { "semantic model idempotency key must not be blank" } }
}

data class SemanticAggregateMetric(
    val measure: SemanticElementName,
    val name: SemanticElementName,
    val label: String?,
    val description: String?,
    val timeGranularity: SemanticTimeGranularity?,
    val filters: List<SemanticMetricFilter>,
)

data class SemanticModelWriteResult(
    val model: SemanticModelView,
    val aggregateMetrics: List<MetricView>,
)

data class PutMetricCommand(
    val authorization: AuthorizedMutationContext,
    val idempotencyKey: String,
    val metricId: MetricId?,
    val definition: SemanticMetricDefinition,
) {
    init { require(idempotencyKey.isNotBlank()) { "metric idempotency key must not be blank" } }
}

data class DeleteSemanticModelCommand(
    val authorization: AuthorizedMutationContext,
    val semanticModelId: SemanticModelId,
)

data class DeleteMetricCommand(
    val authorization: AuthorizedMutationContext,
    val metricId: MetricId,
)

data class RestoreSemanticModelCommand(
    val authorization: AuthorizedMutationContext,
    val idempotencyKey: String,
    val semanticModelId: SemanticModelId,
    val changeId: Uuid,
) {
    init { require(idempotencyKey.isNotBlank()) }
}

data class RestoreMetricCommand(
    val authorization: AuthorizedMutationContext,
    val idempotencyKey: String,
    val metricId: MetricId,
    val changeId: Uuid,
) {
    init { require(idempotencyKey.isNotBlank()) }
}

enum class SemanticDefinitionKind { SEMANTIC_MODEL, METRIC }
enum class SemanticDefinitionChangeAction { CREATED, UPDATED, DELETED }

sealed interface SemanticDefinitionSnapshot {
    data class Model(val definition: SemanticModelDefinition) : SemanticDefinitionSnapshot
    data class Metric(val definition: SemanticMetricDefinition) : SemanticDefinitionSnapshot
}

data class SemanticDefinitionVersion(
    val changeId: Uuid,
    val action: SemanticDefinitionChangeAction,
    val definition: SemanticDefinitionSnapshot,
    val occurredAt: Instant,
    val actorId: PrincipalId?,
    val credentialSurface: CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val restorable: Boolean,
) {
    init { cc.monomer.identity.api.requireRecordedAgentClient(credentialSurface, agentClientId) }
}

data class SemanticHistoryBoundary(val occurredAt: Instant, val changeId: Uuid)

data class SemanticDefinitionHistory(
    val definitionId: Uuid,
    val name: String,
    val deleted: Boolean,
    val changes: List<SemanticDefinitionVersion>,
    val next: SemanticHistoryBoundary?,
)

data class SemanticModelView(
    val semanticModelId: SemanticModelId,
    val workspaceId: WorkspaceId,
    val definition: SemanticModelDefinition,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sourcePartitionInfo: List<SemanticSourcePartitionInfo>,
)

data class MetricView(
    val metricId: MetricId,
    val workspaceId: WorkspaceId,
    val definition: SemanticMetricDefinition,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sourcePartitionInfo: List<SemanticSourcePartitionInfo>,
)

data class SemanticModelListBoundary(
    val name: SemanticElementName,
)

data class SemanticModelListPage(
    val items: List<SemanticModelView>,
    val next: SemanticModelListBoundary?,
)

data class MetricListBoundary(
    val name: SemanticElementName,
)

data class MetricListPage(
    val items: List<MetricView>,
    val next: MetricListBoundary?,
)

data class MetricSourceConnector(
    val connectorId: ConnectorId,
    val displayName: String,
)

data class SemanticModelPageCursor(
    val normalizedLabel: String,
    val normalizedName: String,
    val semanticModelId: SemanticModelId,
) {
    init {
        require(normalizedLabel.isNotBlank())
        require(normalizedName.isNotBlank())
    }
}

data class SemanticModelPage(
    val items: List<SemanticModelView>,
    val nextCursor: SemanticModelPageCursor?,
)

data class MetricPageCursor(
    val normalizedLabel: String,
    val normalizedName: String,
    val metricId: MetricId,
) {
    init {
        require(normalizedLabel.isNotBlank())
        require(normalizedName.isNotBlank())
    }
}

data class MetricPage(
    val items: List<MetricView>,
    val nextCursor: MetricPageCursor?,
)

data class PutSemanticTimeSpineConfigurationCommand(
    val authorization: AuthorizedMutationContext,
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val definition: SemanticTimeSpineDefinition,
)

data class SemanticTimeSpineConfigurationView(
    val connectorId: ConnectorId,
    val definition: SemanticTimeSpineDefinition,
)

sealed interface SemanticMutationResult<out T> {
    data class Committed<T>(val value: T) : SemanticMutationResult<T>
    data class Invalid(val reasons: List<SemanticInvalidReason>) : SemanticMutationResult<Nothing>
    data class AggregateMetricNameConflict(val field: String) : SemanticMutationResult<Nothing>
    data object Conflict : SemanticMutationResult<Nothing>
    data object IdempotencyConflict : SemanticMutationResult<Nothing>
    data object NotFoundOrOutsideWorkspace : SemanticMutationResult<Nothing>
    data object InvalidAuthorization : SemanticMutationResult<Nothing>
}

interface SemanticAuthorityUseCase {
    suspend fun deleteModel(command: DeleteSemanticModelCommand): SemanticMutationResult<Unit>
    suspend fun deleteMetric(command: DeleteMetricCommand): SemanticMutationResult<Unit>
    suspend fun restoreModel(command: RestoreSemanticModelCommand): SemanticMutationResult<SemanticModelView>
    suspend fun restoreMetric(command: RestoreMetricCommand): SemanticMutationResult<MetricView>
    suspend fun putModel(command: PutSemanticModelCommand): SemanticMutationResult<SemanticModelWriteResult>
    suspend fun putMetric(command: PutMetricCommand): SemanticMutationResult<MetricView>
}

/**
 * Creates Monomer-authored seed definitions. Authorization and the idempotency receipt belong to
 * [AuthorizedMutationContext.actor]; current rows and definition changes record no actor, surface or agent app.
 * Create only: a non-null semanticModelId or metricId returns [SemanticMutationResult.InvalidAuthorization].
 */
interface SemanticSeedDefinitionUseCase {
    suspend fun seedModel(command: PutSemanticModelCommand): SemanticMutationResult<SemanticModelWriteResult>
    suspend fun seedMetric(command: PutMetricCommand): SemanticMutationResult<MetricView>
}

sealed interface SemanticTimeSpineMutationResult<out T> {
    data class Committed<T>(val value: T) : SemanticTimeSpineMutationResult<T>
    data class Invalid(val reasons: List<SemanticInvalidReason>) : SemanticTimeSpineMutationResult<Nothing> {
        init { require(reasons.isNotEmpty()) }
    }
    data object NotFoundOrOutsideWorkspace : SemanticTimeSpineMutationResult<Nothing>
    data object InvalidAuthorization : SemanticTimeSpineMutationResult<Nothing>
}

interface SemanticTimeSpineUseCase {
    suspend fun putTimeSpineConfiguration(
        command: PutSemanticTimeSpineConfigurationCommand,
    ): SemanticTimeSpineMutationResult<SemanticTimeSpineConfigurationView>
}

data class SemanticDefinitionChange(
    val changeId: Uuid,
    val action: SemanticDefinitionChangeAction,
    val currentlyDeleted: Boolean,
    val id: Uuid,
    val name: String,
    val label: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val actorId: PrincipalId?,
    val credentialSurface: CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
) {
    init {
        cc.monomer.identity.api.requireRecordedAgentClient(credentialSurface, agentClientId)
    }
}

interface SemanticCurrentReadUseCase {
    suspend fun readModelVersion(workspaceId: WorkspaceId, semanticModelId: SemanticModelId, changeId: Uuid): SemanticDefinitionVersion?
    suspend fun readMetricVersion(workspaceId: WorkspaceId, metricId: MetricId, changeId: Uuid): SemanticDefinitionVersion?
    suspend fun readModelHistory(workspaceId: WorkspaceId, semanticModelId: SemanticModelId, before: SemanticHistoryBoundary?, limit: Int): SemanticDefinitionHistory?
    suspend fun readMetricHistory(workspaceId: WorkspaceId, metricId: MetricId, before: SemanticHistoryBoundary?, limit: Int): SemanticDefinitionHistory?
    suspend fun readRecentModels(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int,
    ): List<SemanticDefinitionChange>
    suspend fun readRecentMetrics(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int,
    ): List<SemanticDefinitionChange>

    /** Agent apps in the latest [scanLimit] saved Semantic Models and the latest [scanLimit] saved Metrics of the Workspace. */
    suspend fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient>

    /** The last recorded save of one current Semantic Model: who saved it, through which surface, and when. */
    suspend fun readModelChange(workspaceId: WorkspaceId, semanticModelId: SemanticModelId): SemanticDefinitionChange?

    /** The last recorded save of one current Metric. */
    suspend fun readMetricChange(workspaceId: WorkspaceId, metricId: MetricId): SemanticDefinitionChange?

    fun findModelsInCurrentTransaction(workspaceId: WorkspaceId): List<SemanticModelView>
    fun findMetricsInCurrentTransaction(workspaceId: WorkspaceId): List<MetricView>
    suspend fun findModel(workspaceId: WorkspaceId, semanticModelId: SemanticModelId): SemanticModelView?
    suspend fun findModelByName(workspaceId: WorkspaceId, name: SemanticElementName): SemanticModelView?
    suspend fun findModels(workspaceId: WorkspaceId): List<SemanticModelView>
    suspend fun listModels(
        workspaceId: WorkspaceId,
        after: SemanticModelListBoundary?,
        limit: Int,
    ): SemanticModelListPage
    suspend fun searchModels(
        workspaceId: WorkspaceId,
        query: String,
        cursor: SemanticModelPageCursor?,
        pageSize: Int,
    ): SemanticModelPage
    suspend fun findLatestModel(workspaceId: WorkspaceId): SemanticModelView?
    suspend fun findMetric(workspaceId: WorkspaceId, metricId: MetricId): MetricView?
    suspend fun findMetricByName(workspaceId: WorkspaceId, name: SemanticElementName): MetricView?
    suspend fun findMetrics(workspaceId: WorkspaceId): List<MetricView>
    suspend fun findMetricSourceConnectors(
        workspaceId: WorkspaceId,
        metricNames: Set<SemanticElementName>,
    ): Map<SemanticElementName, List<MetricSourceConnector>>
    suspend fun listMetrics(
        workspaceId: WorkspaceId,
        after: MetricListBoundary?,
        limit: Int,
    ): MetricListPage
    suspend fun searchMetrics(
        workspaceId: WorkspaceId,
        query: String,
        cursor: MetricPageCursor?,
        pageSize: Int,
    ): MetricPage
    suspend fun findLatestMetric(workspaceId: WorkspaceId): MetricView?
    suspend fun findTimeSpineConfigurations(workspaceId: WorkspaceId): List<SemanticTimeSpineConfigurationView>
}

data class SemanticRenderRequest(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val query: SemanticQuerySpec,
)

data class SemanticGroupByOptionsRequest(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val metrics: List<SemanticElementName>,
) {
    init {
        require(metrics.isNotEmpty()) { "semantic group-by options must select at least one metric" }
        require(metrics.toSet().size == metrics.size) {
            "semantic group-by options metrics must be unique"
        }
    }
}

sealed interface SemanticGroupByOptionsResult {
    data class Listed(
        val groupBys: List<SemanticGroupBy>,
        val excludedSemanticModels: List<SemanticExcludedModel>,
        val dimensionFilterTypes: List<SemanticDimensionFilterType>,
    ) : SemanticGroupByOptionsResult
    data class Invalid(
        val reasons: List<SemanticInvalidReason>,
        val definitionIssues: List<SemanticDefinitionIssue>,
    ) : SemanticGroupByOptionsResult
    data class Unsupported(val capabilities: Set<SemanticUnsupportedCapability>) : SemanticGroupByOptionsResult
    data object NotFoundOrOutsideWorkspace : SemanticGroupByOptionsResult
}

data class SemanticDimensionFilterType(
    val dimension: SemanticElementReference,
    val kind: SemanticCategoricalFilterKind,
)

enum class SemanticCategoricalFilterKind {
    TEXT,
    NUMBER,
    BOOLEAN,
}

sealed interface SemanticRenderResult {
    data class MetricNotFound(val name: SemanticElementName) : SemanticRenderResult
    data class Rendered(val render: SemanticSqlRender) : SemanticRenderResult
    data class Invalid(
        val reasons: List<SemanticInvalidReason>,
        val definitionIssues: List<SemanticDefinitionIssue>,
    ) : SemanticRenderResult
    data class Unsupported(val capabilities: Set<SemanticUnsupportedCapability>) : SemanticRenderResult
    data object NotFoundOrOutsideWorkspace : SemanticRenderResult
}

/** A saved definition this request could not use, with a readable reason. */
data class SemanticDefinitionIssue(
    val code: SemanticInvalidReasonCode,
    val definition: SemanticDefinitionReference?,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "semantic definition issue message must not be blank" }
        require(message.length <= MAXIMUM_SEMANTIC_ISSUE_MESSAGE_LENGTH) {
            "semantic definition issue message must be at most $MAXIMUM_SEMANTIC_ISSUE_MESSAGE_LENGTH characters"
        }
    }
}

/** A saved Metric or Semantic Model, by its Workspace-unique name. */
sealed interface SemanticDefinitionReference {
    val name: SemanticElementName

    data class Metric(override val name: SemanticElementName) : SemanticDefinitionReference
    data class SemanticModel(override val name: SemanticElementName) : SemanticDefinitionReference
}

/** A candidate model left out of this request because the engine rejected it. */
data class SemanticExcludedModel(val name: SemanticElementName, val message: String) {
    init {
        require(message.isNotBlank()) { "semantic excluded model message must not be blank" }
        require(message.length <= MAXIMUM_SEMANTIC_ISSUE_MESSAGE_LENGTH) {
            "semantic excluded model message must be at most $MAXIMUM_SEMANTIC_ISSUE_MESSAGE_LENGTH characters"
        }
    }
}

const val MAXIMUM_SEMANTIC_ISSUE_MESSAGE_LENGTH: Int = 512

/** [reportingTimeZone] is the Workspace time zone the SQL converts instants to and reads time values in. */
data class SemanticSqlRender(
    val workspaceId: WorkspaceId,
    val dialect: SemanticSqlDialect,
    val sql: String,
    val reportingTimeZone: ZoneId,
    val outputColumns: List<SemanticRenderedOutputColumn>,
    val partitionCheck: SemanticPartitionCheck,
) {
    init {
        require(sql.isNotBlank()) { "semantic SQL render must not be blank" }
    }
}

sealed interface SemanticRenderedOutputColumnSource {
    data class Metric(val metricId: MetricId) : SemanticRenderedOutputColumnSource
    data class GroupBy(val groupBy: SemanticGroupBy) : SemanticRenderedOutputColumnSource
}

data class SemanticRenderedOutputColumn(
    val source: SemanticRenderedOutputColumnSource,
    val name: SemanticResultColumnKey,
    val sqlName: String,
    val display: MetricDisplay?,
)

data class SemanticInvalidReason(val code: SemanticInvalidReasonCode, val field: String?)

enum class SemanticInvalidReasonCode {
    AGGREGATE_METRIC_INVALID,
    AGGREGATE_MEASURE_NOT_FOUND,
    MODEL_NAME_IMMUTABLE,
    METRIC_NAME_IMMUTABLE,
    METRIC_MODEL_NOT_FOUND,
    METRIC_DEPENDENCY_NOT_FOUND,
    QUERY_METRIC_NOT_FOUND,
    QUERY_GROUP_BY_NOT_FOUND,
    QUERY_FILTER_UNRENDERABLE,
    METRICFLOW_MANIFEST_INVALID,
    GROUP_BY_KIND_MISMATCH,
    UNKNOWN_FILTER_TARGET,
    CATALOG_RELATION_NOT_FOUND,
    SOURCE_RELATION_NOT_FOUND,
    TIME_KIND_MISMATCH,
    TIME_SPINE_COLUMN_TYPE_INVALID,
}

enum class SemanticUnsupportedCapability {
    RELATIONSHIP_JOIN,
    UNSUPPORTED_DIALECT,
    TIME_SPINE_UNAVAILABLE,
}


private fun Uuid.requireUuidV7(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    val bytes = toByteArray()
    require(bytes[8].toInt().and(0xc0) == 0x80) { "$label must use the IETF UUID variant" }
    require(bytes[6].toInt().and(0xf0) == 0x70) { "$label must be UUIDv7" }
}
