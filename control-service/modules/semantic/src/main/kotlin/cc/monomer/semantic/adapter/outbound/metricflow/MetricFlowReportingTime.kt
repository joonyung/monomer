package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.application.SemanticInstantStorage
import cc.monomer.semantic.application.SemanticMetricFlowTimeDimension
import cc.monomer.semantic.application.SemanticTimeOperand
import cc.monomer.semantic.application.isDayOrCoarser
import java.time.ZoneId
import java.util.Locale

/**
 * The dimension expression the engine reads. An instant dimension becomes a date in the Reporting time
 * zone when defined at day or coarser grain, and that zone's wall-clock time when finer; every other
 * kind is read unchanged.
 */
internal fun reportingTimeExpression(
    expression: String,
    granularity: SemanticTimeGranularity,
    timeDimension: SemanticMetricFlowTimeDimension,
    dialect: SemanticSqlDialect,
    reportingTimeZone: ZoneId,
): String {
    val storage = timeDimension.instantStorage ?: return expression
    val operand = timeDimension.operand
    val value = when (operand) {
        is SemanticTimeOperand.Column -> quotedIdentifier(operand, dialect)
        SemanticTimeOperand.Expression -> "(${expression.trim()})"
    }
    val zone = "'${reportingTimeZone.id}'"
    return if (granularity.isDayOrCoarser()) {
        reportingDate(value, storage, dialect, zone)
    } else {
        val precision = (operand as? SemanticTimeOperand.Column)?.timestampPrecision ?: TRINO_EXPRESSION_TIMESTAMP_PRECISION
        reportingWallClock(value, storage, dialect, zone, precision)
    }
}

private fun reportingDate(value: String, storage: SemanticInstantStorage, dialect: SemanticSqlDialect, zone: String): String =
    when (dialect) {
        SemanticSqlDialect.POSTGRESQL -> when (storage) {
            SemanticInstantStorage.ZONED -> "CAST(($value AT TIME ZONE $zone) AS DATE)"
            SemanticInstantStorage.UTC_NAIVE -> "CAST((($value AT TIME ZONE 'UTC') AT TIME ZONE $zone) AS DATE)"
        }
        SemanticSqlDialect.REDSHIFT -> when (storage) {
            SemanticInstantStorage.ZONED -> "CAST(($value AT TIME ZONE $zone) AS DATE)"
            SemanticInstantStorage.UTC_NAIVE -> "CAST(CONVERT_TIMEZONE('UTC', $zone, $value) AS DATE)"
        }
        SemanticSqlDialect.SNOWFLAKE -> when (storage) {
            SemanticInstantStorage.ZONED -> "CAST(CONVERT_TIMEZONE($zone, $value) AS DATE)"
            SemanticInstantStorage.UTC_NAIVE -> "CAST(CONVERT_TIMEZONE('UTC', $zone, $value) AS DATE)"
        }
        SemanticSqlDialect.BIGQUERY -> when (storage) {
            SemanticInstantStorage.ZONED -> "DATE($value, $zone)"
            SemanticInstantStorage.UTC_NAIVE -> "DATE(TIMESTAMP($value, 'UTC'), $zone)"
        }
        SemanticSqlDialect.DATABRICKS -> "CAST(${databricksWallClock(value, storage, zone)} AS DATE)"
        SemanticSqlDialect.TRINO -> when (storage) {
            SemanticInstantStorage.ZONED -> "CAST($value AT TIME ZONE $zone AS date)"
            SemanticInstantStorage.UTC_NAIVE -> "CAST(with_timezone($value, 'UTC') AT TIME ZONE $zone AS date)"
        }
        SemanticSqlDialect.DUCKDB -> "CAST(${duckDbWallClock(value, storage, zone)} AS DATE)"
    }

private fun reportingWallClock(
    value: String,
    storage: SemanticInstantStorage,
    dialect: SemanticSqlDialect,
    zone: String,
    trinoPrecision: Int,
): String = when (dialect) {
    SemanticSqlDialect.POSTGRESQL -> when (storage) {
        SemanticInstantStorage.ZONED -> "($value AT TIME ZONE $zone)"
        SemanticInstantStorage.UTC_NAIVE -> "(($value AT TIME ZONE 'UTC') AT TIME ZONE $zone)"
    }
    SemanticSqlDialect.REDSHIFT -> when (storage) {
        SemanticInstantStorage.ZONED -> "($value AT TIME ZONE $zone)"
        SemanticInstantStorage.UTC_NAIVE -> "CONVERT_TIMEZONE('UTC', $zone, $value)"
    }
    SemanticSqlDialect.SNOWFLAKE -> when (storage) {
        SemanticInstantStorage.ZONED -> "CONVERT_TIMEZONE($zone, $value)::TIMESTAMP_NTZ"
        SemanticInstantStorage.UTC_NAIVE -> "CONVERT_TIMEZONE('UTC', $zone, $value)"
    }
    SemanticSqlDialect.BIGQUERY -> when (storage) {
        SemanticInstantStorage.ZONED -> "DATETIME($value, $zone)"
        SemanticInstantStorage.UTC_NAIVE -> "DATETIME(TIMESTAMP($value, 'UTC'), $zone)"
    }
    SemanticSqlDialect.DATABRICKS -> databricksWallClock(value, storage, zone)
    SemanticSqlDialect.TRINO -> when (storage) {
        SemanticInstantStorage.ZONED -> "CAST($value AT TIME ZONE $zone AS timestamp($trinoPrecision))"
        SemanticInstantStorage.UTC_NAIVE ->
            "CAST(with_timezone($value, 'UTC') AT TIME ZONE $zone AS timestamp($trinoPrecision))"
    }
    SemanticSqlDialect.DUCKDB -> duckDbWallClock(value, storage, zone)
}

/**
 * Databricks candidate expressions pending live confirmation. Casting a session-local TIMESTAMP to
 * TIMESTAMP_NTZ passes through the session time zone, so the zoned branch rebuilds the UTC wall clock
 * from epoch microseconds before converting it.
 */
private fun databricksWallClock(value: String, storage: SemanticInstantStorage, zone: String): String = when (storage) {
    SemanticInstantStorage.ZONED ->
        "convert_timezone('UTC', $zone, timestampadd(MICROSECOND, unix_micros($value), TIMESTAMP_NTZ'1970-01-01 00:00:00'))"
    SemanticInstantStorage.UTC_NAIVE -> "convert_timezone('UTC', $zone, $value)"
}

/**
 * DuckDB's two-argument timezone() turns a TIMESTAMP WITH TIME ZONE into the zone's wall clock and reads a
 * TIMESTAMP as the zone's wall clock, so neither direction passes through the session TimeZone.
 */
private fun duckDbWallClock(value: String, storage: SemanticInstantStorage, zone: String): String = when (storage) {
    SemanticInstantStorage.ZONED -> "timezone($zone, $value)"
    SemanticInstantStorage.UTC_NAIVE -> "timezone($zone, timezone('UTC', $value))"
}

/** A wrapped column is opaque SQL to the engine, so it is quoted here with the warehouse's identifier rules. */
private fun quotedIdentifier(column: SemanticTimeOperand.Column, dialect: SemanticSqlDialect): String {
    val name = if (column.quoted) column.name else when (dialect) {
        SemanticSqlDialect.POSTGRESQL, SemanticSqlDialect.REDSHIFT, SemanticSqlDialect.TRINO -> column.name.lowercase(Locale.ROOT)
        SemanticSqlDialect.SNOWFLAKE -> column.name.uppercase(Locale.ROOT)
        SemanticSqlDialect.BIGQUERY, SemanticSqlDialect.DATABRICKS, SemanticSqlDialect.DUCKDB -> column.name
    }
    return when (dialect) {
        SemanticSqlDialect.POSTGRESQL, SemanticSqlDialect.REDSHIFT, SemanticSqlDialect.SNOWFLAKE, SemanticSqlDialect.TRINO,
        SemanticSqlDialect.DUCKDB,
        -> "\"${name.replace("\"", "\"\"")}\""
        SemanticSqlDialect.BIGQUERY -> "`${name.replace("\\", "\\\\").replace("`", "\\`")}`"
        SemanticSqlDialect.DATABRICKS -> "`${name.replace("`", "``")}`"
    }
}

private const val TRINO_EXPRESSION_TIMESTAMP_PRECISION = 12
