package cc.monomer.semantic.application

import cc.monomer.semantic.api.referencedModelNames
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticResultColumnKey
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.api.SemanticDimensionKind
import java.time.ZoneId
import java.time.ZoneOffset

/** How a value of an instant time dimension carries its moment. */
enum class SemanticInstantStorage {
    ZONED,
    UTC_NAIVE,
}

/** The SQL a time dimension reads: one plain column of its relation, or any other expression. */
sealed interface SemanticTimeOperand {
    data class Column(val name: String, val quoted: Boolean, val timestampPrecision: Int?) : SemanticTimeOperand {
        init {
            require(name.isNotEmpty())
            require(timestampPrecision == null || timestampPrecision in 0..12)
        }
    }

    data object Expression : SemanticTimeOperand
}

/** One time dimension's kind resolved on the request's connector and Catalog. */
data class SemanticMetricFlowTimeDimension(
    val kind: SemanticTimeKind,
    val instantStorage: SemanticInstantStorage?,
    val operand: SemanticTimeOperand,
) {
    init {
        require((kind == SemanticTimeKind.INSTANT) == (instantStorage != null)) {
            "only instant time dimensions have an instant storage"
        }
        require(instantStorage != SemanticInstantStorage.UTC_NAIVE || operand is SemanticTimeOperand.Column) {
            "only a plain column can store UTC instants without a time zone"
        }
    }
}

/** The request-local physical relation paired with one current model definition. */
data class SemanticMetricFlowModel(
    val definition: SemanticModelDefinition,
    val physicalAddress: CatalogPhysicalAddress,
    val timeDimensions: Map<SemanticElementName, SemanticMetricFlowTimeDimension>,
) {
    init {
        require(physicalAddress.objectName != null) {
            "MetricFlow model relation must have an object name"
        }
        require(physicalAddress.memberName == null) {
            "MetricFlow model relation must not have a member name"
        }
        require(
            timeDimensions.keys ==
                definition.dimensions.filter { it.type == SemanticDimensionKind.TIME }.map { it.name }.toSet(),
        ) {
            "MetricFlow model must resolve exactly its time dimensions"
        }
    }
}

data class SemanticMetricFlowTimeSpine(
    val physicalAddress: CatalogPhysicalAddress,
    val primaryColumnName: String,
    val granularity: SemanticTimeGranularity,
) {
    init {
        require(physicalAddress.objectName != null) {
            "MetricFlow time spine relation must have an object name"
        }
        require(physicalAddress.memberName == null) {
            "MetricFlow time spine relation must not have a member name"
        }
    }
}

/**
 * Current definitions plus Catalog addresses resolved for one request. Render and group-by requests
 * always name metrics; a model check may validate the connector's models without any metric.
 */
data class SemanticMetricFlowManifest(
    val connectorId: ConnectorId,
    val dialect: SemanticSqlDialect,
    val models: Map<SemanticElementName, SemanticMetricFlowModel>,
    val metrics: Map<MetricId, SemanticMetricDefinition>,
    val timeSpines: List<SemanticMetricFlowTimeSpine>,
    val reportingTimeZone: ZoneId,
) {
    init {
        require(reportingTimeZone !is ZoneOffset && reportingTimeZone.id.matches(REGION_ZONE_ID)) {
            "MetricFlow reporting time zone must be a region time zone"
        }
        require(models.keys == models.values.map { it.definition.name }.toSet()) {
            "MetricFlow model keys must match model names"
        }
        require(metrics.values.map { it.name }.toSet().size == metrics.size) {
            "MetricFlow metric names must be unique"
        }
        require(metrics.values.all { metric ->
            metric.referencedModelNames().all { modelName -> modelName in models }
        }) {
            "MetricFlow manifest metrics must reference current models"
        }
        models.values.forEach { model ->
            require(model.definition.sourceRelationFor(connectorId) != null) {
                "MetricFlow model must define the selected connector"
            }
        }
    }
}

private val REGION_ZONE_ID = Regex("[A-Za-z][A-Za-z0-9_+\\-]*(?:/[A-Za-z0-9_+\\-]+)*")

internal fun SemanticTimeGranularity.isDayOrCoarser(): Boolean = this >= SemanticTimeGranularity.DAY

enum class SemanticMetricFlowUnsupportedCapability {
    RELATIONSHIP_JOIN,
    TIME_SPINE_UNAVAILABLE,
}

data class SemanticMetricFlowInvalidReason(val code: SemanticInvalidReasonCode, val field: String?)

/** Where a blocking engine finding points, by definition name. */
sealed interface SemanticMetricFlowIssueSubject {
    data class Metric(val metricName: String) : SemanticMetricFlowIssueSubject
    data class Model(val semanticModelName: String) : SemanticMetricFlowIssueSubject
    data class ModelElement(val semanticModelName: String, val elementName: String) : SemanticMetricFlowIssueSubject
}

/** One blocking engine finding with its readable message. */
data class SemanticMetricFlowIssue(
    val message: String,
    val subject: SemanticMetricFlowIssueSubject?,
) {
    init { require(message.isNotBlank()) }
}

sealed interface SemanticMetricFlowValidationResult {
    data object Valid : SemanticMetricFlowValidationResult

    data class Invalid(
        val reasons: List<SemanticMetricFlowInvalidReason>,
        val issues: List<SemanticMetricFlowIssue>,
    ) : SemanticMetricFlowValidationResult {
        init { require(reasons.isNotEmpty()) }
    }
}

sealed interface SemanticMetricFlowOutputColumnSource {
    data class Metric(val metricId: MetricId) : SemanticMetricFlowOutputColumnSource
    data class GroupBy(val groupBy: SemanticGroupBy) : SemanticMetricFlowOutputColumnSource
}

data class SemanticMetricFlowOutputColumn(
    val source: SemanticMetricFlowOutputColumnSource,
    val name: SemanticResultColumnKey,
    val sqlName: String,
)

sealed interface SemanticMetricFlowRenderResult {
    data class Rendered(
        val dialect: SemanticSqlDialect,
        val sql: String,
        val requestedMetricIds: List<MetricId>,
        val outputColumns: List<SemanticMetricFlowOutputColumn>,
    ) : SemanticMetricFlowRenderResult {
        init {
            require(sql.isNotBlank())
            require(requestedMetricIds.isNotEmpty())
            require(requestedMetricIds.toSet().size == requestedMetricIds.size)
            require(outputColumns.isNotEmpty())
        }
    }

    data class Invalid(val reasons: List<SemanticMetricFlowInvalidReason>) : SemanticMetricFlowRenderResult {
        init { require(reasons.isNotEmpty()) }
    }

    data class Unsupported(
        val capabilities: Set<SemanticMetricFlowUnsupportedCapability>,
    ) : SemanticMetricFlowRenderResult {
        init { require(capabilities.isNotEmpty()) }
    }
}

sealed interface SemanticMetricFlowGroupByResult {
    data class Listed(
        val groupBys: List<SemanticGroupBy>,
    ) : SemanticMetricFlowGroupByResult

    data class Invalid(val reasons: List<SemanticMetricFlowInvalidReason>) : SemanticMetricFlowGroupByResult {
        init { require(reasons.isNotEmpty()) }
    }

    data class Unsupported(
        val capabilities: Set<SemanticMetricFlowUnsupportedCapability>,
    ) : SemanticMetricFlowGroupByResult {
        init { require(capabilities.isNotEmpty()) }
    }
}

/** MetricFlow is a stateless renderer; its release identity belongs to build metadata. */
interface SemanticMetricFlowPort {
    fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult
    fun listGroupBys(
        manifest: SemanticMetricFlowManifest,
        metricIds: List<MetricId>,
    ): SemanticMetricFlowGroupByResult
    fun render(
        manifest: SemanticMetricFlowManifest,
        querySpec: SemanticQuerySpec,
        metricIds: List<MetricId>,
    ): SemanticMetricFlowRenderResult
}

class SemanticMetricFlowEngineFaultException(cause: Throwable) :
    RuntimeException("MetricFlow could not compute the semantic request.", cause)

class SemanticMetricFlowUnsupportedException(cause: Throwable) :
    RuntimeException("MetricFlow does not support the semantic request.", cause)

class SemanticMetricFlowInvalidQueryException(val reason: SemanticInvalidReasonCode, cause: Throwable) :
    RuntimeException("MetricFlow rejected the semantic query.", cause)
