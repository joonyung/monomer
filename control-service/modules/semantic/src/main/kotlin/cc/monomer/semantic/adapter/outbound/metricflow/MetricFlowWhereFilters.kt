package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilter
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticMetricFilter
import cc.monomer.semantic.api.SemanticMetricFilterOperator
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

private fun SemanticMetricFilter.toMetricFlowFilter(
    manifest: SemanticMetricFlowManifest,
    exactNames: Map<SemanticGroupBy, String>?,
): String =
    when (this) {
        is SemanticMetricFilter.Predicate -> {
            val dimension = dimension.metricFlowDimensionName(manifest, exactNames = exactNames).toDimensionTemplate()
            when (operator) {
                SemanticMetricFilterOperator.IS_NULL -> "$dimension IS NULL"
                SemanticMetricFilterOperator.IS_NOT_NULL -> "$dimension IS NOT NULL"
                SemanticMetricFilterOperator.BETWEEN ->
                    "$dimension BETWEEN ${values[0].sqlLiteral(manifest.dialect)} AND ${values[1].sqlLiteral(manifest.dialect)}"
                SemanticMetricFilterOperator.IN,
                SemanticMetricFilterOperator.NOT_IN,
                -> "$dimension ${operator.sqlOperator()} (${values.joinToString(", ") { it.sqlLiteral(manifest.dialect) }})"
                else -> "$dimension ${operator.sqlOperator()} ${operator.sqlLiteralValue(values.single()).sqlLiteral(manifest.dialect)}"
            }
        }
        is SemanticMetricFilter.And -> filters.joinToString(" AND ", "(", ")") {
            it.toMetricFlowFilter(manifest, exactNames)
        }
        is SemanticMetricFilter.Or -> filters.joinToString(" OR ", "(", ")") {
            it.toMetricFlowFilter(manifest, exactNames)
        }
        is SemanticMetricFilter.Not -> "NOT (${filter.toMetricFlowFilter(manifest, exactNames)})"
    }

internal fun List<SemanticMetricFilter>.toMetricFlowFilterIntersection(
    manifest: SemanticMetricFlowManifest,
    exactNames: Map<SemanticGroupBy, String>?,
): WhereFilterIntersection? =
    takeIf { it.isNotEmpty() }?.let { filters ->
        WhereFilterIntersection(filters.map { WhereFilter(it.toMetricFlowFilter(manifest, exactNames)) })
    }

internal fun SemanticQueryFilter.toMetricFlowFilter(
    manifest: SemanticMetricFlowManifest,
    exactNames: Map<SemanticGroupBy, String>,
): String {
    val dimensionTemplate = this.dimension.metricFlowDimensionName(manifest, exactNames = exactNames).toDimensionTemplate()
    val dimension = if (this is SemanticQueryFilter.Date && values.isNotEmpty()) {
        "CAST($dimensionTemplate AS DATE)"
    } else dimensionTemplate
    val literals = when (this) {
        is SemanticQueryFilter.Text -> values.map { it.sqlLiteral(manifest.dialect) }
        is SemanticQueryFilter.Number -> values.map { it.toPlainString() }
        is SemanticQueryFilter.Boolean -> values.map { if (it) "TRUE" else "FALSE" }
        is SemanticQueryFilter.Date -> values.map { "DATE '${it}'" }
        is SemanticQueryFilter.Timestamp -> values.map { it.wallClockLiteral(manifest) }
    }
    val operator = when (this) {
        is SemanticQueryFilter.Text -> operator.name
        is SemanticQueryFilter.Number -> operator.name
        is SemanticQueryFilter.Boolean -> operator.name
        is SemanticQueryFilter.Date -> operator.name
        is SemanticQueryFilter.Timestamp -> operator.name
    }
    return when (operator) {
        "IS_NULL" -> if (this is SemanticQueryFilter.Text) {
            "($dimension IS NULL OR $dimension = ${"".sqlLiteral(manifest.dialect)})"
        } else "$dimension IS NULL"
        "IS_NOT_NULL" -> if (this is SemanticQueryFilter.Text) {
            "($dimension IS NOT NULL AND $dimension <> ${"".sqlLiteral(manifest.dialect)})"
        } else "$dimension IS NOT NULL"
        "IN" -> "$dimension IN (${literals.joinToString(", ")})"
        "NOT_IN" -> "($dimension NOT IN (${literals.joinToString(", ")}) OR $dimension IS NULL)"
        "EQUALS" -> "$dimension = ${literals.single()}"
        "NOT_EQUALS" -> "($dimension <> ${literals.single()} OR $dimension IS NULL)"
        "GREATER_THAN" -> "$dimension > ${literals.single()}"
        "GREATER_THAN_OR_EQUAL" -> "$dimension >= ${literals.single()}"
        "LESS_THAN" -> "$dimension < ${literals.single()}"
        "LESS_THAN_OR_EQUAL" -> "$dimension <= ${literals.single()}"
        else -> error("unsupported query filter operator: $operator")
    }
}

/** Timestamp values compare as wall-clock times in the Reporting time zone, typed without a time zone. */
private fun Instant.wallClockLiteral(manifest: SemanticMetricFlowManifest): String {
    val wallClock = WALL_CLOCK_LITERAL.format(atZone(manifest.reportingTimeZone).toLocalDateTime())
    return when (manifest.dialect) {
        SemanticSqlDialect.BIGQUERY -> "DATETIME '$wallClock'"
        SemanticSqlDialect.SNOWFLAKE -> "'$wallClock'::TIMESTAMP_NTZ"
        SemanticSqlDialect.DATABRICKS -> "TIMESTAMP_NTZ '$wallClock'"
        SemanticSqlDialect.POSTGRESQL, SemanticSqlDialect.REDSHIFT, SemanticSqlDialect.TRINO, SemanticSqlDialect.DUCKDB ->
            "TIMESTAMP '$wallClock'"
    }
}

private val WALL_CLOCK_LITERAL: DateTimeFormatter = DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ISO_LOCAL_DATE)
    .appendLiteral(' ')
    .appendPattern("HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
    .toFormatter(Locale.ROOT)

private fun SemanticMetricFilterOperator.sqlOperator(): String = when (this) {
    SemanticMetricFilterOperator.EQ -> "="
    SemanticMetricFilterOperator.NE -> "<>"
    SemanticMetricFilterOperator.GT -> ">"
    SemanticMetricFilterOperator.GTE -> ">="
    SemanticMetricFilterOperator.LT -> "<"
    SemanticMetricFilterOperator.LTE -> "<="
    SemanticMetricFilterOperator.IN -> "IN"
    SemanticMetricFilterOperator.NOT_IN -> "NOT IN"
    SemanticMetricFilterOperator.BETWEEN -> "BETWEEN"
    SemanticMetricFilterOperator.IS_NULL -> "IS NULL"
    SemanticMetricFilterOperator.IS_NOT_NULL -> "IS NOT NULL"
    SemanticMetricFilterOperator.LIKE -> "LIKE"
    SemanticMetricFilterOperator.NOT_LIKE -> "NOT LIKE"
    SemanticMetricFilterOperator.CONTAINS -> "LIKE"
    SemanticMetricFilterOperator.STARTS_WITH -> "LIKE"
}

private fun SemanticMetricFilterOperator.sqlLiteralValue(value: String): String = when (this) {
    SemanticMetricFilterOperator.CONTAINS -> "%$value%"
    SemanticMetricFilterOperator.STARTS_WITH -> "$value%"
    else -> value
}

private fun String.sqlLiteral(dialect: SemanticSqlDialect): String {
    val chunks = mutableListOf<String>()
    var start = 0
    for (index in 1 until length) {
        if (substring(index - 1, index + 1) in WHERE_TEMPLATE_DELIMITERS) {
            chunks += substring(start, index)
            start = index
        }
    }
    if (chunks.isEmpty()) return escapedSqlLiteral(dialect)
    chunks += substring(start)
    // The where-template scanner also scans quoted SQL; delimiters must be separated before it runs.
    return chunks.joinToString(" || ", "(", ")") { it.escapedSqlLiteral(dialect) }
}

private val WHERE_TEMPLATE_DELIMITERS = setOf("{{", "}}", "{%", "%}", "{#", "#}")

private fun String.escapedSqlLiteral(dialect: SemanticSqlDialect): String =
    when (dialect) {
        SemanticSqlDialect.POSTGRESQL ->
            if ('\\' in this) "E'${replace("\\", "\\\\").replace("'", "''")}'" else "'${replace("'", "''")}'"
        SemanticSqlDialect.TRINO, SemanticSqlDialect.DUCKDB -> "'${replace("'", "''")}'"
        SemanticSqlDialect.REDSHIFT,
        SemanticSqlDialect.SNOWFLAKE,
        -> "'${replace("\\", "\\\\").replace("'", "''")}'"
        SemanticSqlDialect.BIGQUERY,
        SemanticSqlDialect.DATABRICKS,
        -> "'${replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")}'"
    }

private fun cc.monomer.semantic.api.SemanticElementReference.metricFlowDimensionName(
    manifest: SemanticMetricFlowManifest,
    exactNames: Map<SemanticGroupBy, String>?,
): String {
    if (exactNames != null) return requireNotNull(exactNames[toGroupBy(manifest)]) {
        "semantic filter selector has no unique engine path"
    }
    val model = manifest.models[semanticModelName]?.definition
        ?: throw IllegalArgumentException("referenced semantic model is missing")
    val primaryEntity = model.entities.singleOrNull { it.type == SemanticEntityKind.PRIMARY }
        ?: throw IllegalArgumentException("semantic model must define exactly one primary entity")
    val dimension = model.dimensions.singleOrNull { it.name == name }
        ?: throw IllegalArgumentException("referenced semantic dimension is missing")
    val effectiveGranularity = granularity ?: dimension.granularity
    return listOfNotNull(
        primaryEntity.name.value,
        name.value,
        effectiveGranularity?.name?.lowercase(),
    ).joinToString("__")
}

internal fun cc.monomer.semantic.api.SemanticElementReference.toGroupBy(
    manifest: SemanticMetricFlowManifest,
): SemanticGroupBy.Dimension = SemanticGroupBy.Dimension(
    semanticModelName,
    name,
    granularity ?: manifest.models.getValue(semanticModelName).definition.dimensions
        .single { it.name == name }.granularity,
)

private fun String.toDimensionTemplate(): String {
    val entityLinks = cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName
        .fromName(this, emptyList()).entityLinkNames
    if (entityLinks.size <= 1) return "{{ Dimension('$this') }}"
    val entityPath = entityLinks.dropLast(1)
    val dimensionName = removePrefix(entityPath.joinToString("__", postfix = "__"))
    return "{{ Dimension('$dimensionName', entity_path=[${entityPath.joinToString(", ") { "'$it'" }}]) }}"
}
