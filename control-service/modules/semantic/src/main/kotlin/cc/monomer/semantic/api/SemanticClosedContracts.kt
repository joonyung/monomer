@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.api

import cc.monomer.connector.api.ConnectorId
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.text.Normalizer

@JvmInline
value class SemanticElementName(val value: String) {
    init {
        require(value.matches(lowerSnakeCase)) { "semantic element name must be lower snake case" }
    }

    override fun toString(): String = value
}

data class SemanticSourceRelation(
    val connectorId: ConnectorId,
    val catalog: String?,
    val schema: String,
    val relation: String,
) {
    init {
        require(schema.isCanonicalSemanticAddressPart()) { "semantic source schema must be a canonical identifier" }
        require(relation.isCanonicalSemanticAddressPart()) { "semantic source relation must be a canonical identifier" }
        require(catalog == null || catalog.isCanonicalSemanticAddressPart()) {
            "semantic source catalog must be a canonical identifier"
        }
    }
}

enum class SemanticTimeGranularity {
    NANOSECOND,
    MICROSECOND,
    MILLISECOND,
    SECOND,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    QUARTER,
    YEAR,
}

data class SemanticTimeSpineRelation(
    val catalog: String?,
    val schema: String,
    val relation: String,
) {
    init {
        require(schema.isCanonicalSemanticAddressPart()) {
            "semantic time spine schema must be a canonical identifier"
        }
        require(relation.isCanonicalSemanticAddressPart()) {
            "semantic time spine relation must be a canonical identifier"
        }
        require(catalog == null || catalog.isCanonicalSemanticAddressPart()) {
            "semantic time spine catalog must be a canonical identifier"
        }
    }
}

data class SemanticTimeSpinePrimaryColumn(
    val name: String,
    val granularity: SemanticTimeGranularity,
) {
    init {
        require(name.isCanonicalSemanticAddressPart()) {
            "semantic time spine primary column must be a canonical identifier"
        }
    }
}

data class SemanticTimeSpine(
    val relation: SemanticTimeSpineRelation,
    val primaryColumn: SemanticTimeSpinePrimaryColumn,
)

data class SemanticTimeSpineDefinition(
    val schemaVersion: UInt,
    val timeSpines: List<SemanticTimeSpine>,
) {
    init {
        require(schemaVersion == 1u) { "semantic time spine schemaVersion must be 1" }
        require(timeSpines.isNotEmpty()) { "semantic time spine definition must be non-empty" }
        require(timeSpines.map { it.primaryColumn.granularity }.toSet().size == timeSpines.size) {
            "semantic time spine granularities must be unique"
        }
    }
}

enum class SemanticSqlDialect {
    POSTGRESQL,
    BIGQUERY,
    SNOWFLAKE,
    DATABRICKS,
    REDSHIFT,
    TRINO,
    DUCKDB,
    ;

    companion object {
        fun requireForConnectorType(connectorType: String): SemanticSqlDialect =
            when (connectorType.lowercase()) {
                "postgresql" -> POSTGRESQL
                "bigquery" -> BIGQUERY
                "snowflake" -> SNOWFLAKE
                "databricks" -> DATABRICKS
                "redshift" -> REDSHIFT
                "trino" -> TRINO
                "duckdb" -> DUCKDB
                else -> throw IllegalArgumentException("unsupported connector type for Semantic SQL")
            }
    }
}

enum class SemanticEntityKind {
    PRIMARY,
    UNIQUE,
    FOREIGN,
    NATURAL,
}

enum class SemanticDimensionKind {
    CATEGORICAL,
    TIME,
}

/** What a time dimension's values mean: a calendar date, a local wall-clock time, or an absolute instant. */
enum class SemanticTimeKind {
    DATE,
    LOCAL,
    INSTANT,
}

enum class SemanticMeasureAggregation {
    SUM,
    COUNT,
    COUNT_DISTINCT,
    AVERAGE,
    MIN,
    MAX,
    MEDIAN,
    SUM_BOOLEAN,
}

data class SemanticEntityDefinition(
    val name: SemanticElementName,
    val type: SemanticEntityKind,
    val expression: String,
    val label: String?,
    val description: String?,
) {
    init {
        require(expression.isNotBlank()) { "semantic entity expression must not be blank" }
        require(expression.none { it == '\u0000' }) { "semantic entity expression must not contain NUL" }
        requireOptionalText(label, "semantic entity label")
        requireOptionalText(description, "semantic entity description")
    }
}

data class SemanticDimensionDefinition(
    val name: SemanticElementName,
    val type: SemanticDimensionKind,
    val expression: String,
    val granularity: SemanticTimeGranularity?,
    val label: String?,
    val description: String?,
    val isPartition: Boolean,
    val timeKind: SemanticTimeKind?,
) {
    init {
        require(expression.isNotBlank()) { "semantic dimension expression must not be blank" }
        require(expression.none { it == '\u0000' }) { "semantic dimension expression must not contain NUL" }
        if (type == SemanticDimensionKind.TIME) {
            require(granularity != null) { "semantic time dimension granularity is required" }
        } else {
            require(granularity == null) { "categorical semantic dimension must not have granularity" }
            require(timeKind == null) { "categorical semantic dimension must not have timeKind" }
        }
        requireOptionalText(label, "semantic dimension label")
        requireOptionalText(description, "semantic dimension description")
    }
}

enum class SemanticNonAdditiveWindowChoice {
    MIN,
    MAX,
}

data class SemanticNonAdditiveDimension(
    val name: SemanticElementName,
    val windowChoice: SemanticNonAdditiveWindowChoice,
    val windowGroupings: List<SemanticElementName>?,
) {
    init {
        require(windowGroupings == null || windowGroupings.toSet().size == windowGroupings.size) {
            "semantic non-additive window groupings must be unique"
        }
    }
}

data class SemanticMeasureDefinition(
    val name: SemanticElementName,
    val aggregation: SemanticMeasureAggregation,
    val expression: String,
    val aggregationTimeDimension: SemanticElementName?,
    val nonAdditiveDimension: SemanticNonAdditiveDimension?,
    val label: String?,
    val description: String?,
) {
    init {
        require(expression.isNotBlank()) { "semantic measure expression must not be blank" }
        require(expression.none { it == '\u0000' }) { "semantic measure expression must not contain NUL" }
        requireOptionalText(label, "semantic measure label")
        requireOptionalText(description, "semantic measure description")
    }
}

data class SemanticModelDefinition(
    val schemaVersion: UInt,
    val name: SemanticElementName,
    val label: String?,
    val description: String?,
    val sourceRelations: List<SemanticSourceRelation>,
    val aggregationTimeDimension: SemanticElementName?,
    val entities: List<SemanticEntityDefinition>,
    val dimensions: List<SemanticDimensionDefinition>,
    val measures: List<SemanticMeasureDefinition>,
) {
    init {
        require(schemaVersion == 3u) { "semantic model schemaVersion must be 3" }
        require(sourceRelations.isNotEmpty()) { "semantic model must define at least one source relation" }
        require(sourceRelations.map(SemanticSourceRelation::connectorId).toSet().size == sourceRelations.size) {
            "semantic model source relation connector ids must be unique"
        }
        require(entities.isNotEmpty()) { "semantic model must define at least one entity" }
        require(entities.count { it.type == SemanticEntityKind.PRIMARY } == 1) {
            "semantic model must define exactly one primary entity"
        }
        val names = buildList {
            addAll(entities.map(SemanticEntityDefinition::name))
            addAll(dimensions.map(SemanticDimensionDefinition::name))
            addAll(measures.map(SemanticMeasureDefinition::name))
        }
        require(names.toSet().size == names.size) { "semantic model elements must have unique names" }
        val timeDimensions = dimensions
            .filter { it.type == SemanticDimensionKind.TIME }
            .map(SemanticDimensionDefinition::name)
            .toSet()
        val entityNames = entities.map(SemanticEntityDefinition::name).toSet()
        require(aggregationTimeDimension == null || aggregationTimeDimension in timeDimensions) {
            "semantic model aggregation time dimension must reference a time dimension"
        }
        measures.forEach { measure ->
            require(measure.aggregationTimeDimension == null || measure.aggregationTimeDimension in timeDimensions) {
                "semantic measure aggregation time dimension must reference a time dimension"
            }
            measure.nonAdditiveDimension?.let { nonAdditive ->
                require(nonAdditive.name in timeDimensions) {
                    "semantic non-additive dimension must reference a time dimension"
                }
                require(nonAdditive.windowGroupings.orEmpty().all { it in entityNames }) {
                    "semantic non-additive window groupings must reference entities"
                }
            }
        }
        requireOptionalText(label, "semantic model label")
        requireOptionalText(description, "semantic model description")
    }

    fun sourceRelationFor(connectorId: ConnectorId): SemanticSourceRelation? =
        sourceRelations.firstOrNull { it.connectorId == connectorId }
}

data class SemanticElementReference(
    val semanticModelName: SemanticElementName,
    val name: SemanticElementName,
    val granularity: SemanticTimeGranularity?,
) {
    constructor(
        semanticModelName: SemanticElementName,
        name: SemanticElementName,
    ) : this(semanticModelName, name, null)
}

enum class SemanticMetricType {
    SIMPLE,
    RATIO,
    DERIVED,
    CUMULATIVE,
    CONVERSION,
}

enum class SemanticMetricFilterOperator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
    IN,
    NOT_IN,
    BETWEEN,
    IS_NULL,
    IS_NOT_NULL,
    LIKE,
    NOT_LIKE,
    CONTAINS,
    STARTS_WITH,
}

sealed interface SemanticMetricFilter {
    data class Predicate(
        val dimension: SemanticElementReference,
        val operator: SemanticMetricFilterOperator,
        val values: List<String>,
    ) : SemanticMetricFilter {
        init {
            require(values.size <= maximumMetricFilterValues) { "metric filter has too many values" }
            require(values.all { it.none { character -> character == '\u0000' } }) {
                "metric filter values must be NUL-free"
            }
            when (operator) {
                SemanticMetricFilterOperator.IS_NULL,
                SemanticMetricFilterOperator.IS_NOT_NULL,
                -> require(values.isEmpty()) { "null metric filter operators do not accept values" }
                SemanticMetricFilterOperator.BETWEEN ->
                    require(values.size == 2) { "between metric filter requires two values" }
                SemanticMetricFilterOperator.IN,
                SemanticMetricFilterOperator.NOT_IN,
                -> require(values.isNotEmpty()) { "set metric filter requires values" }
                else -> require(values.size == 1) { "scalar metric filter requires one value" }
            }
        }
    }

    data class And(val filters: List<SemanticMetricFilter>) : SemanticMetricFilter {
        init { require(filters.isNotEmpty()) { "and metric filter requires children" } }
    }

    data class Or(val filters: List<SemanticMetricFilter>) : SemanticMetricFilter {
        init { require(filters.isNotEmpty()) { "or metric filter requires children" } }
    }

    data class Not(val filter: SemanticMetricFilter) : SemanticMetricFilter
}

enum class SemanticMetricWindowUnit {
    DAY,
    WEEK,
    MONTH,
    QUARTER,
    YEAR,
}

data class SemanticMetricWindow(val amount: Int, val unit: SemanticMetricWindowUnit) {
    init { require(amount > 0) { "metric window amount must be positive" } }

    override fun toString(): String =
        amount.toString() + " " + unit.name.lowercase() + if (amount == 1) "" else "s"

    companion object {
        private val pattern = Regex("^(\\d+)\\s+(day|days|week|weeks|month|months|quarter|quarters|year|years)$")

        fun parse(value: String): SemanticMetricWindow {
            val match = pattern.matchEntire(value)
                ?: throw IllegalArgumentException("metric window must be '<positive integer> <unit>'")
            val amount = match.groupValues[1].toIntOrNull()
                ?: throw IllegalArgumentException("metric window amount is invalid")
            val unit = when (match.groupValues[2].removeSuffix("s")) {
                "day" -> SemanticMetricWindowUnit.DAY
                "week" -> SemanticMetricWindowUnit.WEEK
                "month" -> SemanticMetricWindowUnit.MONTH
                "quarter" -> SemanticMetricWindowUnit.QUARTER
                "year" -> SemanticMetricWindowUnit.YEAR
                else -> error("unknown metric window unit")
            }
            return SemanticMetricWindow(amount, unit)
        }
    }
}

enum class SemanticMetricPeriodAggregation {
    FIRST,
    LAST,
    AVERAGE,
}

enum class SemanticConversionCalculation {
    CONVERSIONS,
    CONVERSION_RATE,
}

data class SemanticConstantProperty(
    val baseProperty: SemanticElementName,
    val conversionProperty: SemanticElementName,
)

data class SemanticMetricInput(
    val metricName: SemanticElementName,
    val filter: SemanticMetricFilter?,
    val alias: SemanticElementName?,
    val offsetWindow: SemanticMetricWindow?,
    val offsetToGrain: SemanticTimeGranularity?,
)

sealed interface SemanticMetricDefinition {
    val schemaVersion: UInt
    val name: SemanticElementName
    val label: String?
    val description: String?
    val timeGranularity: SemanticTimeGranularity?
    val filters: List<SemanticMetricFilter>
    val display: MetricDisplay?
    val type: SemanticMetricType

    data class Simple(
        override val schemaVersion: UInt,
        override val name: SemanticElementName,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: SemanticTimeGranularity?,
        override val filters: List<SemanticMetricFilter>,
        val semanticModelName: SemanticElementName,
        val measure: SemanticElementName,
        override val display: MetricDisplay?,
    ) : SemanticMetricDefinition {
        override val type: SemanticMetricType = SemanticMetricType.SIMPLE
        init { requireMetricCommon(schemaVersion, label, description, filters) }
    }

    data class Ratio(
        override val schemaVersion: UInt,
        override val name: SemanticElementName,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: SemanticTimeGranularity?,
        override val filters: List<SemanticMetricFilter>,
        val numerator: SemanticMetricInput,
        val denominator: SemanticMetricInput,
        override val display: MetricDisplay?,
    ) : SemanticMetricDefinition {
        override val type: SemanticMetricType = SemanticMetricType.RATIO
        init {
            requireMetricCommon(schemaVersion, label, description, filters)
            require(!sameEffectiveInput(numerator, denominator)) {
                "ratio metric numerator and denominator must not have the same effective input"
            }
        }
    }

    data class Derived(
        override val schemaVersion: UInt,
        override val name: SemanticElementName,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: SemanticTimeGranularity?,
        override val filters: List<SemanticMetricFilter>,
        val expression: String,
        val inputMetrics: List<SemanticMetricInput>,
        override val display: MetricDisplay?,
    ) : SemanticMetricDefinition {
        override val type: SemanticMetricType = SemanticMetricType.DERIVED
        init {
            requireMetricCommon(schemaVersion, label, description, filters)
            require(expression.isNotBlank()) { "derived metric expression must not be blank" }
            require(expression.none { it == '\u0000' }) { "derived metric expression must not contain NUL" }
            require(inputMetrics.isNotEmpty()) { "derived metric must define input metrics" }
            val aliases = inputMetrics.map { it.alias ?: it.metricName }
            require(aliases.toSet().size == aliases.size) { "derived metric input aliases must be unique" }
        }
    }

    data class Cumulative(
        override val schemaVersion: UInt,
        override val name: SemanticElementName,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: SemanticTimeGranularity?,
        override val filters: List<SemanticMetricFilter>,
        val inputMetric: SemanticMetricInput,
        val window: SemanticMetricWindow?,
        val grainToDate: SemanticTimeGranularity?,
        val periodAggregation: SemanticMetricPeriodAggregation?,
        override val display: MetricDisplay?,
    ) : SemanticMetricDefinition {
        override val type: SemanticMetricType = SemanticMetricType.CUMULATIVE
        init {
            requireMetricCommon(schemaVersion, label, description, filters)
            require(window == null || grainToDate == null) { "cumulative metric window and grainToDate are exclusive" }
        }
    }

    data class Conversion(
        override val schemaVersion: UInt,
        override val name: SemanticElementName,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: SemanticTimeGranularity?,
        override val filters: List<SemanticMetricFilter>,
        val entity: SemanticElementName,
        val calculation: SemanticConversionCalculation,
        val baseMetric: SemanticMetricInput,
        val conversionMetric: SemanticMetricInput,
        val window: SemanticMetricWindow?,
        val constantProperties: List<SemanticConstantProperty>?,
        override val display: MetricDisplay?,
    ) : SemanticMetricDefinition {
        override val type: SemanticMetricType = SemanticMetricType.CONVERSION
        init {
            requireMetricCommon(schemaVersion, label, description, filters)
            require(!sameEffectiveInput(baseMetric, conversionMetric)) {
                "conversion metric base and conversion inputs must not have the same effective input"
            }
        }
    }
}

sealed interface SemanticGroupBy {
    data class Dimension(
        val semanticModelName: SemanticElementName,
        val name: SemanticElementName,
        val granularity: SemanticTimeGranularity?,
    ) : SemanticGroupBy

    data class Entity(
        val semanticModelName: SemanticElementName,
        val name: SemanticElementName,
    ) : SemanticGroupBy

    data class MetricTime(
        val granularity: SemanticTimeGranularity,
    ) : SemanticGroupBy
}

sealed interface SemanticTimeRangeEndpoint {
    data class Date(val value: LocalDate) : SemanticTimeRangeEndpoint
    data class Timestamp(val value: Instant) : SemanticTimeRangeEndpoint
}

data class SemanticMetricTimeRange(
    val start: SemanticTimeRangeEndpoint,
    val end: SemanticTimeRangeEndpoint,
) {
    init {
        when {
            start is SemanticTimeRangeEndpoint.Date && end is SemanticTimeRangeEndpoint.Date ->
                require(start.value <= end.value) { "semantic date range start must not follow end" }
            start is SemanticTimeRangeEndpoint.Timestamp && end is SemanticTimeRangeEndpoint.Timestamp ->
                require(start.value <= end.value) { "semantic timestamp range start must not follow end" }
            else -> throw IllegalArgumentException("semantic time range endpoints must have the same type")
        }
    }
}

sealed interface SemanticQueryFilter {
    val dimension: SemanticElementReference

    data class Text(
        override val dimension: SemanticElementReference,
        val operator: SemanticTextFilterOperator,
        val values: List<String>,
    ) : SemanticQueryFilter {
        init {
            requireQueryFilterValueCardinality(values, operator.valueCardinality)
            require(values.all { it.codePointCount(0, it.length) in 1..256 }) {
                "Text filter values must contain 1 to 256 characters."
            }
            require(values.all { '\u0000' !in it }) { "Text filter values must not contain NUL." }
        }
    }

    data class Number(
        override val dimension: SemanticElementReference,
        val operator: SemanticNumberFilterOperator,
        val values: List<BigDecimal>,
    ) : SemanticQueryFilter {
        init {
            requireQueryFilterValueCardinality(values, operator.valueCardinality)
            values.forEach { value ->
                require(value.scale() in 0..18 && value.precision() <= 38) {
                    "semantic number filter values must use DECIMAL(38,18) bounds"
                }
            }
        }
    }

    data class Boolean(
        override val dimension: SemanticElementReference,
        val operator: SemanticBooleanFilterOperator,
        val values: List<kotlin.Boolean>,
    ) : SemanticQueryFilter {
        init { requireQueryFilterValueCardinality(values, operator.valueCardinality) }
    }

    data class Date(
        override val dimension: SemanticElementReference,
        val operator: SemanticDateFilterOperator,
        val values: List<LocalDate>,
    ) : SemanticQueryFilter {
        init { requireQueryFilterValueCardinality(values, operator.valueCardinality) }
    }

    data class Timestamp(
        override val dimension: SemanticElementReference,
        val operator: SemanticTimestampFilterOperator,
        val values: List<Instant>,
    ) : SemanticQueryFilter {
        init { requireQueryFilterValueCardinality(values, operator.valueCardinality) }
    }
}

enum class SemanticTextFilterOperator(override val valueCardinality: SemanticFilterValueCardinality) : SemanticQueryFilterOperator {
    EQUALS(SemanticFilterValueCardinality.ONE),
    NOT_EQUALS(SemanticFilterValueCardinality.ONE),
    IN(SemanticFilterValueCardinality.MANY),
    NOT_IN(SemanticFilterValueCardinality.MANY),
    IS_NULL(SemanticFilterValueCardinality.NONE),
    IS_NOT_NULL(SemanticFilterValueCardinality.NONE),
}

enum class SemanticNumberFilterOperator(override val valueCardinality: SemanticFilterValueCardinality) : SemanticQueryFilterOperator {
    EQUALS(SemanticFilterValueCardinality.ONE),
    NOT_EQUALS(SemanticFilterValueCardinality.ONE),
    IN(SemanticFilterValueCardinality.MANY),
    NOT_IN(SemanticFilterValueCardinality.MANY),
    GREATER_THAN(SemanticFilterValueCardinality.ONE),
    GREATER_THAN_OR_EQUAL(SemanticFilterValueCardinality.ONE),
    LESS_THAN(SemanticFilterValueCardinality.ONE),
    LESS_THAN_OR_EQUAL(SemanticFilterValueCardinality.ONE),
    IS_NULL(SemanticFilterValueCardinality.NONE),
    IS_NOT_NULL(SemanticFilterValueCardinality.NONE),
}

enum class SemanticBooleanFilterOperator(override val valueCardinality: SemanticFilterValueCardinality) : SemanticQueryFilterOperator {
    EQUALS(SemanticFilterValueCardinality.ONE),
    NOT_EQUALS(SemanticFilterValueCardinality.ONE),
    IS_NULL(SemanticFilterValueCardinality.NONE),
    IS_NOT_NULL(SemanticFilterValueCardinality.NONE),
}

enum class SemanticDateFilterOperator(override val valueCardinality: SemanticFilterValueCardinality) : SemanticQueryFilterOperator {
    EQUALS(SemanticFilterValueCardinality.ONE),
    NOT_EQUALS(SemanticFilterValueCardinality.ONE),
    IN(SemanticFilterValueCardinality.MANY),
    NOT_IN(SemanticFilterValueCardinality.MANY),
    GREATER_THAN(SemanticFilterValueCardinality.ONE),
    GREATER_THAN_OR_EQUAL(SemanticFilterValueCardinality.ONE),
    LESS_THAN(SemanticFilterValueCardinality.ONE),
    LESS_THAN_OR_EQUAL(SemanticFilterValueCardinality.ONE),
    IS_NULL(SemanticFilterValueCardinality.NONE),
    IS_NOT_NULL(SemanticFilterValueCardinality.NONE),
}

enum class SemanticTimestampFilterOperator(override val valueCardinality: SemanticFilterValueCardinality) : SemanticQueryFilterOperator {
    EQUALS(SemanticFilterValueCardinality.ONE),
    NOT_EQUALS(SemanticFilterValueCardinality.ONE),
    IN(SemanticFilterValueCardinality.MANY),
    NOT_IN(SemanticFilterValueCardinality.MANY),
    GREATER_THAN(SemanticFilterValueCardinality.ONE),
    GREATER_THAN_OR_EQUAL(SemanticFilterValueCardinality.ONE),
    LESS_THAN(SemanticFilterValueCardinality.ONE),
    LESS_THAN_OR_EQUAL(SemanticFilterValueCardinality.ONE),
    IS_NULL(SemanticFilterValueCardinality.NONE),
    IS_NOT_NULL(SemanticFilterValueCardinality.NONE),
}

interface SemanticQueryFilterOperator {
    val valueCardinality: SemanticFilterValueCardinality
}

enum class SemanticFilterValueCardinality {
    NONE,
    ONE,
    MANY,
}

sealed interface SemanticQueryOrderTarget {
    data class Metric(val metricName: SemanticElementName) : SemanticQueryOrderTarget
    data class GroupBy(val groupBy: SemanticGroupBy) : SemanticQueryOrderTarget
}

enum class SemanticQueryOrderDirection {
    ASC,
    DESC,
}

data class SemanticQueryOrder(
    val target: SemanticQueryOrderTarget,
    val direction: SemanticQueryOrderDirection,
)

data class SemanticQuerySpec(
    val metrics: List<SemanticElementName>,
    val groupBys: List<SemanticGroupBy>,
    val metricTimeRange: SemanticMetricTimeRange?,
    val filters: List<SemanticQueryFilter>,
    val orderBy: List<SemanticQueryOrder>,
    val rowLimit: Int,
) {
    init {
        require(metrics.isNotEmpty()) { "semantic query must select at least one metric" }
        require(metrics.toSet().size == metrics.size) { "semantic query metrics must be unique" }
        require(groupBys.toSet().size == groupBys.size) {
            "semantic query group-bys must be unique"
        }
        require(groupBys.count { it is SemanticGroupBy.MetricTime } <= 1) {
            "semantic query may select at most one time group-by"
        }
        require(filters.size <= maximumSemanticQueryFilters) {
            "semantic query may contain at most $maximumSemanticQueryFilters filters"
        }
        require(filters.toSet().size == filters.size) { "semantic query filters must not contain duplicates" }
        val groupByReferences = groupBys.toSet()
        orderBy.forEach { order ->
            when (val target = order.target) {
                is SemanticQueryOrderTarget.Metric -> require(target.metricName in metrics)
                is SemanticQueryOrderTarget.GroupBy -> require(target.groupBy in groupByReferences)
            }
        }
        require(orderBy.map(SemanticQueryOrder::target).toSet().size == orderBy.size) {
            "semantic query order targets must be unique"
        }
        require(rowLimit in 1..maximumSemanticQueryRows) {
            "semantic query row limit must be between 1 and $maximumSemanticQueryRows"
        }
    }
}

private fun <T> requireQueryFilterValueCardinality(
    values: List<T>,
    cardinality: SemanticFilterValueCardinality,
) {
    when (cardinality) {
        SemanticFilterValueCardinality.NONE -> require(values.isEmpty()) { "This filter operator does not accept values." }
        SemanticFilterValueCardinality.ONE -> require(values.size == 1) { "This filter operator requires exactly one value." }
        SemanticFilterValueCardinality.MANY -> require(values.isNotEmpty() && values.size <= maximumSemanticFilterValues) {
            "This filter operator requires 1 to $maximumSemanticFilterValues values."
        }
    }
    require(values.distinct().size == values.size) { "Filter values must be unique." }
}

private fun requireMetricCommon(
    schemaVersion: UInt,
    label: String?,
    description: String?,
    filters: List<SemanticMetricFilter>,
) {
    require(schemaVersion == 3u) { "metric schemaVersion must be 3" }
    requireOptionalText(label, "metric label")
    requireOptionalText(description, "metric description")
    require(filters.size <= maximumMetricFilters) { "metric has too many filters" }
}

private fun requireOptionalText(value: String?, context: String) {
    require(value == null || (value.isNotBlank() && value.none { it == '\u0000' })) {
        "$context must be non-blank and NUL-free when supplied"
    }
}

fun String.isCanonicalSemanticAddressPart(): Boolean =
    isNotBlank() &&
        trim() == this &&
        Normalizer.normalize(this, Normalizer.Form.NFC) == this &&
        toByteArray(StandardCharsets.UTF_8).size <= 1_024 &&
        none { it.isISOControl() }

private fun sameEffectiveInput(left: SemanticMetricInput, right: SemanticMetricInput): Boolean =
    left.metricName == right.metricName &&
        left.filter == right.filter &&
        left.offsetWindow == right.offsetWindow &&
        left.offsetToGrain == right.offsetToGrain

private val lowerSnakeCase = Regex("^(?![a-z0-9_]*__)[a-z](?:[a-z0-9_]*[a-z0-9])?$")
private const val maximumSemanticQueryRows = 100_000
private const val maximumSemanticFilterValues = 100
private const val maximumMetricFilters = 32
private const val maximumMetricFilterValues = 100
internal const val maximumSemanticQueryFilters = 32
