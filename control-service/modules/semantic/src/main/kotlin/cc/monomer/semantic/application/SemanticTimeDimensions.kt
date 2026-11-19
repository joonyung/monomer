package cc.monomer.semantic.application

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeKind
import java.util.Locale

/** The temporal class of a known Catalog column type. */
internal enum class SemanticCatalogTimeClass {
    DATE,
    NAIVE,
    ZONED,
}

internal data class SemanticCatalogTimeType(val timeClass: SemanticCatalogTimeClass, val precision: Int?)

/** A time dimension's kind on one connector, or a declaration its known Catalog type contradicts. */
internal sealed interface SemanticTimeDimensionResolution {
    data class Resolved(
        val timeDimension: SemanticMetricFlowTimeDimension,
        val declarationNeeded: Boolean,
    ) : SemanticTimeDimensionResolution

    data class Mismatch(val declared: SemanticTimeKind, val nativeType: String) : SemanticTimeDimensionResolution
}

/** Resolves every time dimension of [definition] read from [relation], in definition order. */
internal fun timeDimensionResolutions(
    definition: SemanticModelDefinition,
    relation: CatalogCurrentObject,
    catalog: CatalogCurrentObjectSet,
    dialect: SemanticSqlDialect,
): Map<SemanticElementName, SemanticTimeDimensionResolution> =
    definition.dimensions.filter { it.type == SemanticDimensionKind.TIME }.associateTo(linkedMapOf()) { dimension ->
        val identifier = plainColumnIdentifier(dimension.expression, dialect)
        val column = identifier?.let { catalogColumn(catalog, relation, it, dialect) }
        dimension.name to resolveTimeDimension(dimension, identifier, column, dialect)
    }

/**
 * A declaration wins over Catalog inference; a known Catalog type it contradicts is a mismatch. Without
 * either, the dimension reads as local wall-clock time and needs a declaration.
 */
internal fun resolveTimeDimension(
    dimension: SemanticDimensionDefinition,
    identifier: ColumnIdentifier?,
    column: CatalogCurrentObject?,
    dialect: SemanticSqlDialect,
): SemanticTimeDimensionResolution {
    val nativeType = column?.nativeType
    val catalogType = nativeType?.let { catalogTimeType(it, dialect) }
    val declared = dimension.timeKind
    if (declared != null && catalogType != null && !declared.agreesWith(catalogType.timeClass)) {
        return SemanticTimeDimensionResolution.Mismatch(declared, requireNotNull(nativeType))
    }
    val kind = declared ?: catalogType?.timeClass?.inferredKind() ?: SemanticTimeKind.LOCAL
    val operand = identifier?.let { SemanticTimeOperand.Column(it.name, it.quoted, catalogType?.precision) }
        ?: SemanticTimeOperand.Expression
    val storage = when {
        kind != SemanticTimeKind.INSTANT -> null
        operand is SemanticTimeOperand.Column && catalogType?.timeClass == SemanticCatalogTimeClass.NAIVE ->
            SemanticInstantStorage.UTC_NAIVE
        else -> SemanticInstantStorage.ZONED
    }
    return SemanticTimeDimensionResolution.Resolved(
        SemanticMetricFlowTimeDimension(kind, storage, operand),
        declarationNeeded = declared == null && catalogType == null,
    )
}

/** Classifies a Catalog native type by the warehouse's own date and timestamp type names. */
internal fun catalogTimeType(nativeType: String, dialect: SemanticSqlDialect): SemanticCatalogTimeType? {
    val normalized = nativeType.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    if (normalized == "DATE") return SemanticCatalogTimeType(SemanticCatalogTimeClass.DATE, null)
    return when (dialect) {
        SemanticSqlDialect.POSTGRESQL, SemanticSqlDialect.REDSHIFT -> when {
            normalized == "TIMESTAMPTZ" -> SemanticCatalogTimeType(SemanticCatalogTimeClass.ZONED, null)
            else -> STANDARD_TIMESTAMP.matchEntire(normalized)?.let { match ->
                SemanticCatalogTimeType(
                    if (match.groupValues[2] == " WITH TIME ZONE") SemanticCatalogTimeClass.ZONED else SemanticCatalogTimeClass.NAIVE,
                    match.groupValues[1].toIntOrNull(),
                )
            }
        }
        SemanticSqlDialect.TRINO -> STANDARD_TIMESTAMP.matchEntire(normalized)
            ?.let { match ->
                SemanticCatalogTimeType(
                    if (match.groupValues[2] == " WITH TIME ZONE") SemanticCatalogTimeClass.ZONED else SemanticCatalogTimeClass.NAIVE,
                    match.groupValues[1].toIntOrNull() ?: TRINO_DEFAULT_TIMESTAMP_PRECISION,
                )
            }
        SemanticSqlDialect.SNOWFLAKE -> SNOWFLAKE_TIMESTAMP.matchEntire(normalized)?.let { match ->
            SemanticCatalogTimeType(
                if (match.groupValues[1] == "NTZ") SemanticCatalogTimeClass.NAIVE else SemanticCatalogTimeClass.ZONED,
                match.groupValues[2].toIntOrNull(),
            )
        } ?: SemanticCatalogTimeType(SemanticCatalogTimeClass.NAIVE, null).takeIf { normalized == "DATETIME" }
        SemanticSqlDialect.BIGQUERY -> when (normalized) {
            "DATETIME" -> SemanticCatalogTimeType(SemanticCatalogTimeClass.NAIVE, null)
            "TIMESTAMP" -> SemanticCatalogTimeType(SemanticCatalogTimeClass.ZONED, null)
            else -> null
        }
        SemanticSqlDialect.DATABRICKS -> when (normalized) {
            "TIMESTAMP_NTZ" -> SemanticCatalogTimeType(SemanticCatalogTimeClass.NAIVE, null)
            "TIMESTAMP", "TIMESTAMP_LTZ" -> SemanticCatalogTimeType(SemanticCatalogTimeClass.ZONED, null)
            else -> null
        }
        SemanticSqlDialect.DUCKDB -> when (normalized) {
            "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE", "DATETIME", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_US", "TIMESTAMP_NS" ->
                SemanticCatalogTimeType(SemanticCatalogTimeClass.NAIVE, null)
            "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> SemanticCatalogTimeType(SemanticCatalogTimeClass.ZONED, null)
            else -> null
        }
    }
}

/** A time spine column must hold calendar dates or wall-clock times, never values with a time zone. */
internal fun timeSpineColumnTypeAllowed(nativeType: String, dialect: SemanticSqlDialect): Boolean {
    val catalogType = catalogTimeType(nativeType, dialect)
    return when {
        catalogType != null -> catalogType.timeClass != SemanticCatalogTimeClass.ZONED
        else -> scalarComparisonKind(nativeType) == null
    }
}

private fun SemanticTimeKind.agreesWith(timeClass: SemanticCatalogTimeClass): Boolean = when (this) {
    SemanticTimeKind.DATE -> timeClass == SemanticCatalogTimeClass.DATE
    SemanticTimeKind.LOCAL -> timeClass == SemanticCatalogTimeClass.NAIVE
    SemanticTimeKind.INSTANT -> timeClass != SemanticCatalogTimeClass.DATE
}

private fun SemanticCatalogTimeClass.inferredKind(): SemanticTimeKind = when (this) {
    SemanticCatalogTimeClass.DATE -> SemanticTimeKind.DATE
    SemanticCatalogTimeClass.NAIVE -> SemanticTimeKind.LOCAL
    SemanticCatalogTimeClass.ZONED -> SemanticTimeKind.INSTANT
}

private val STANDARD_TIMESTAMP = Regex("TIMESTAMP(?: ?\\( ?([0-9]{1,2}) ?\\))?( WITH TIME ZONE| WITHOUT TIME ZONE)?")
private val SNOWFLAKE_TIMESTAMP = Regex("TIMESTAMP_(NTZ|LTZ|TZ)(?: ?\\( ?([0-9]) ?\\))?")
private const val TRINO_DEFAULT_TIMESTAMP_PRECISION = 3
