@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticCheckDraft
import cc.monomer.semantic.api.SemanticCheckProblem
import cc.monomer.semantic.api.SemanticCheckProblemCode
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticCheckRequest
import cc.monomer.semantic.api.SemanticCheckResult
import cc.monomer.semantic.api.SemanticDefinitionCheckUseCase
import cc.monomer.semantic.api.SemanticGroupBy
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/** Checks one unsaved model or metric like the query path would, for editors and agents before or after saving. */
@RestController
class SemanticCheckController(
    private val checkUseCase: SemanticDefinitionCheckUseCase,
    private val authorizer: SemanticWorkspaceRequestAuthorizer,
    private val consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
) {
    @PostMapping("/v2/workspaces/{workspaceId}/semantic/check")
    suspend fun check(
        @PathVariable workspaceId: String,
        @RequestBody request: SemanticCheckHttpRequest,
        exchange: ServerWebExchange,
    ): SemanticCheckHttpView {
        val workspace = workspaceId.parseResourceId(::WorkspaceId)
        val draft = request.toDomain()
        val inputs = consistentSnapshotTransactionRunner.run {
            authorizer.authorizeExplorer(exchange, workspace)
            checkUseCase.readInputs(SemanticCheckRequest(workspace, draft))
        }
        val outcome = checkUseCase.checkFromInputs(inputs)
        return when (outcome) {
            is SemanticCheckResult.Checked -> SemanticCheckHttpView.fromDomain(outcome, draft)
            SemanticCheckResult.NotFoundOrOutsideWorkspace -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = SemanticModelCheckHttpRequest::class, name = "SEMANTIC_MODEL"),
    JsonSubTypes.Type(value = MetricCheckHttpRequest::class, name = "METRIC"),
)
sealed interface SemanticCheckHttpRequest {
    fun toDomain(): SemanticCheckDraft
}

data class SemanticModelCheckHttpRequest(
    val semanticModelId: String?,
    val definition: SemanticModelDefinitionDto,
) : SemanticCheckHttpRequest {
    override fun toDomain(): SemanticCheckDraft = SemanticCheckDraft.Model(
        semanticModelId = semanticModelId?.let(::parseSemanticModelId),
        definition = definition.toDomain(),
    )
}

data class MetricCheckHttpRequest(
    val metricId: String?,
    val connectorId: String?,
    val definition: MetricDefinitionDto,
) : SemanticCheckHttpRequest {
    override fun toDomain(): SemanticCheckDraft = SemanticCheckDraft.Metric(
        metricId = metricId?.let(::parseMetricId),
        definition = definition.toDomain(),
        connectorId = connectorId?.let(::parseDefinitionConnectorId),
    )
}

data class SemanticCheckHttpView(
    val ok: Boolean,
    val runsOn: List<String>,
    val preview: SemanticCheckPreviewHttpView?,
    val problems: List<SemanticCheckProblemHttpView>,
    val partitionFilters: List<Map<String, Any?>>,
) {
    internal companion object {
        fun fromDomain(result: SemanticCheckResult.Checked, draft: SemanticCheckDraft): SemanticCheckHttpView =
            SemanticCheckHttpView(
                ok = result.problems.isEmpty() && result.runsOn.isNotEmpty(),
                runsOn = result.runsOn.map { it.value.toString() },
                preview = result.preview?.let { preview ->
                    SemanticCheckPreviewHttpView(
                        connectorId = preview.connectorId.value.toString(),
                        dialect = preview.dialect.name,
                        sql = preview.sql,
                        groupBys = preview.groupBys.map(SemanticCheckGroupByHttpView::fromDomain),
                    )
                },
                problems = result.problems.map { problem -> SemanticCheckProblemHttpView.fromDomain(problem, draft) },
                partitionFilters = result.partitionFilters.map { it.toHttpValue() },
            )
    }
}

data class SemanticCheckPreviewHttpView(
    val connectorId: String,
    val dialect: String,
    val sql: String,
    val groupBys: List<SemanticCheckGroupByHttpView>,
)

/** The query schema's group-by shapes, which omit fields a kind does not have. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SemanticCheckGroupByHttpView(
    val kind: String,
    val semanticModelName: String?,
    val name: String?,
    val granularity: String?,
) {
    internal companion object {
        fun fromDomain(groupBy: SemanticGroupBy): SemanticCheckGroupByHttpView = when (groupBy) {
            is SemanticGroupBy.Dimension -> SemanticCheckGroupByHttpView("DIMENSION", groupBy.semanticModelName.value, groupBy.name.value, groupBy.granularity?.name)
            is SemanticGroupBy.Entity -> SemanticCheckGroupByHttpView("ENTITY", groupBy.semanticModelName.value, groupBy.name.value, null)
            is SemanticGroupBy.MetricTime -> SemanticCheckGroupByHttpView("METRIC_TIME", null, null, groupBy.granularity.name)
        }
    }
}

data class SemanticCheckProblemHttpView(
    val code: String,
    val field: String?,
    val connectorId: String?,
    val definition: SemanticCheckDefinitionHttpView?,
    val message: String,
) {
    internal companion object {
        fun fromDomain(problem: SemanticCheckProblem, draft: SemanticCheckDraft): SemanticCheckProblemHttpView =
            SemanticCheckProblemHttpView(
                code = problem.code.name,
                field = problem.field,
                connectorId = problem.connectorId?.value?.toString(),
                definition = problem.definition?.let { definition ->
                    SemanticCheckDefinitionHttpView(
                        kind = when (definition) {
                            is SemanticDefinitionReference.Metric -> "METRIC"
                            is SemanticDefinitionReference.SemanticModel -> "SEMANTIC_MODEL"
                        },
                        name = definition.name.value,
                    )
                },
                message = problem.message(draft),
            )
    }
}

/** Another saved definition a problem is about. */
data class SemanticCheckDefinitionHttpView(
    val kind: String,
    val name: String,
)

/** English copy for each declared check problem; engine findings keep their own readable detail. */
internal fun SemanticCheckProblem.message(draft: SemanticCheckDraft): String {
    detail?.let { return it }
    val noun = when (draft) {
        is SemanticCheckDraft.Model -> "semantic model"
        is SemanticCheckDraft.Metric -> "metric"
    }
    return when (code) {
        SemanticCheckProblemCode.NAME_CONFLICT -> "Another $noun already uses this name. Saving creates a conflict."
        SemanticCheckProblemCode.NAME_IMMUTABLE -> "The name can't be changed after the $noun is created."
        SemanticCheckProblemCode.CONNECTOR_NOT_FOUND -> "This connector isn't in the workspace."
        SemanticCheckProblemCode.METRIC_MODEL_NOT_FOUND ->
            if (field != null) "This semantic model doesn't exist." else "A semantic model one of its inputs uses doesn't exist."
        SemanticCheckProblemCode.METRIC_DEPENDENCY_NOT_FOUND ->
            if (field != null) "This metric doesn't exist." else "A metric one of its inputs uses doesn't exist."
        SemanticCheckProblemCode.SOURCE_RELATION_NOT_FOUND -> "The semantic models it uses have no source table on a common connector."
        SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND -> "A source table isn't loaded in Catalog for this connector."
        SemanticCheckProblemCode.METRICFLOW_MANIFEST_INVALID -> "MetricFlow can't resolve these definitions."
        SemanticCheckProblemCode.QUERY_UNRENDERABLE -> "Monomer couldn't render a query for this metric."
        SemanticCheckProblemCode.TIME_SPINE_UNAVAILABLE -> "This metric needs a time spine on this connector. Set one up in Semantics under Time spines."
        SemanticCheckProblemCode.RELATIONSHIP_JOIN_UNSUPPORTED -> "This metric needs a join that can't be planned yet."
        SemanticCheckProblemCode.PARTITION_COLUMN_NOT_MODELED -> "The table needs a partition filter. Add a dimension that uses its partition column."
        SemanticCheckProblemCode.UNSUPPORTED_DIALECT -> "Semantic queries don't support this connector's SQL dialect."
        SemanticCheckProblemCode.TIME_KIND_UNDECLARED -> "Declare timeKind for this time dimension."
        SemanticCheckProblemCode.TIME_KIND_MISMATCH -> "This timeKind doesn't match the column's Catalog type."
        SemanticCheckProblemCode.TIME_SPINE_COLUMN_TYPE_INVALID ->
            "A time spine column on this connector must be a date or a timestamp without a time zone."
    }
}
