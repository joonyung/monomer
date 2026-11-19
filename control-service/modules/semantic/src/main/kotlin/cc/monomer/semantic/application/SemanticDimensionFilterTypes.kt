package cc.monomer.semantic.application

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticCategoricalFilterKind
import cc.monomer.semantic.api.SemanticDimensionFilterType
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import java.util.Locale

internal fun dimensionFilterTypes(
    workspaceId: WorkspaceId,
    catalog: CatalogCurrentObjectSet?,
    manifest: SemanticMetricFlowManifest,
): List<SemanticDimensionFilterType> {
    if (catalog == null || catalog.workspaceId != workspaceId || catalog.connectorId != manifest.connectorId) return emptyList()
    return manifest.models.flatMap { (modelName, model) ->
        val relation = catalog.objects.singleOrNull {
            it.kind == CatalogObjectKind.RELATION && it.address == model.physicalAddress
        } ?: return@flatMap emptyList()
        model.definition.dimensions.mapNotNull { dimension ->
            if (dimension.type != SemanticDimensionKind.CATEGORICAL) return@mapNotNull null
            val identifier = plainColumnIdentifier(dimension.expression, manifest.dialect) ?: return@mapNotNull null
            val column = catalogColumn(catalog, relation, identifier, manifest.dialect) ?: return@mapNotNull null
            scalarComparisonKind(requireNotNull(column.nativeType))?.let { kind ->
                SemanticDimensionFilterType(SemanticElementReference(modelName, dimension.name), kind)
            }
        }
    }
}

/**
 * A time dimension compares as DATE when defined at day or coarser grain and as TIMESTAMP when finer;
 * a categorical dimension compares as its known Catalog scalar kind.
 */
internal fun SemanticQuerySpec.filterTypeMismatches(
    dimensionFilterTypes: List<SemanticDimensionFilterType>,
    manifest: SemanticMetricFlowManifest,
): List<SemanticInvalidReason> {
    val knownKinds = dimensionFilterTypes.associate { it.dimension to it.kind }
    return filters.mapIndexedNotNull { index, filter ->
        val timeDimension = manifest.models[filter.dimension.semanticModelName]?.definition?.dimensions
            ?.singleOrNull { it.name == filter.dimension.name && it.type == SemanticDimensionKind.TIME }
        val matches = if (timeDimension != null) {
            filter.matchesTimeGrain(requireNotNull(timeDimension.granularity))
        } else {
            val expected = knownKinds[filter.dimension] ?: return@mapIndexedNotNull null
            filter.comparisonKind() == expected
        }
        if (matches) null else
            SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[$index].kind")
    }
}

private fun SemanticQueryFilter.matchesTimeGrain(granularity: SemanticTimeGranularity): Boolean = when (this) {
    is SemanticQueryFilter.Date -> granularity.isDayOrCoarser()
    is SemanticQueryFilter.Timestamp -> !granularity.isDayOrCoarser()
    is SemanticQueryFilter.Text, is SemanticQueryFilter.Number, is SemanticQueryFilter.Boolean -> false
}

private fun SemanticQueryFilter.comparisonKind(): SemanticCategoricalFilterKind? = when (this) {
    is SemanticQueryFilter.Text -> SemanticCategoricalFilterKind.TEXT
    is SemanticQueryFilter.Number -> SemanticCategoricalFilterKind.NUMBER
    is SemanticQueryFilter.Boolean -> SemanticCategoricalFilterKind.BOOLEAN
    is SemanticQueryFilter.Date, is SemanticQueryFilter.Timestamp -> null
}

internal fun scalarComparisonKind(nativeType: String): SemanticCategoricalFilterKind? {
    val normalized = nativeType.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val scalarType = SCALAR_TYPE.matchEntire(normalized) ?: return null
    val scalar = scalarType.groupValues[1]
    if (scalarType.groupValues[2].isNotEmpty() && scalar !in PARAMETERIZED_SCALAR_TYPES) return null
    return when (scalar) {
        "TEXT", "CHAR", "CHARACTER", "CHARACTER VARYING", "VARCHAR", "NCHAR", "NVARCHAR", "STRING", "BPCHAR" ->
            SemanticCategoricalFilterKind.TEXT
        "SMALLINT", "INTEGER", "INT", "INT2", "INT4", "INT8", "BIGINT", "TINYINT", "BYTEINT", "SHORT", "LONG", "INT64",
        "REAL", "FLOAT", "FLOAT4", "FLOAT8", "FLOAT64", "DOUBLE", "DOUBLE PRECISION", "DECIMAL", "NUMERIC", "BIGNUMERIC", "NUMBER", "FIXED" ->
            SemanticCategoricalFilterKind.NUMBER
        "BOOLEAN", "BOOL" -> SemanticCategoricalFilterKind.BOOLEAN
        else -> null
    }
}

/** The one current Catalog column of [relation] that [identifier] names on [dialect]. */
internal fun catalogColumn(
    catalog: CatalogCurrentObjectSet,
    relation: CatalogCurrentObject,
    identifier: ColumnIdentifier,
    dialect: SemanticSqlDialect,
): CatalogCurrentObject? = catalog.objects.singleOrNull {
    it.kind == CatalogObjectKind.COLUMN && it.parentId == relation.id &&
        it.address.catalogName == relation.address.catalogName &&
        it.address.schemaName == relation.address.schemaName &&
        it.address.objectName == relation.address.objectName &&
        identifier.matches(requireNotNull(it.address.memberName), dialect)
}

internal data class ColumnIdentifier(val name: String, val quoted: Boolean) {
    fun matches(columnName: String, dialect: SemanticSqlDialect): Boolean = when (dialect) {
        SemanticSqlDialect.POSTGRESQL, SemanticSqlDialect.REDSHIFT ->
            columnName == if (quoted) name else name.lowercase(Locale.ROOT)
        SemanticSqlDialect.SNOWFLAKE -> columnName == if (quoted) name else name.uppercase(Locale.ROOT)
        SemanticSqlDialect.BIGQUERY, SemanticSqlDialect.DATABRICKS, SemanticSqlDialect.TRINO, SemanticSqlDialect.DUCKDB -> columnName.equals(name, ignoreCase = true)
    }
}

internal fun plainColumnIdentifier(expression: String, dialect: SemanticSqlDialect): ColumnIdentifier? {
    val trimmed = expression.trim()
    if (UNQUOTED_IDENTIFIER.matches(trimmed)) {
        if (trimmed.uppercase(Locale.ROOT) in BARE_SQL_VALUES) return null
        return ColumnIdentifier(trimmed, false)
    }
    val quote = if (dialect == SemanticSqlDialect.BIGQUERY || dialect == SemanticSqlDialect.DATABRICKS) '`' else '"'
    if (trimmed.length < 3 || trimmed.first() != quote || trimmed.last() != quote) return null
    val name = StringBuilder()
    var position = 1
    while (position < trimmed.lastIndex) {
        val character = trimmed[position]
        if (dialect == SemanticSqlDialect.BIGQUERY && character == '\\') {
            if (position + 1 >= trimmed.lastIndex || trimmed[position + 1] !in charArrayOf('`', '\\')) return null
            name.append(trimmed[position + 1])
            position += 2
        } else if (character == quote) {
            if (dialect == SemanticSqlDialect.BIGQUERY || position + 1 >= trimmed.lastIndex || trimmed[position + 1] != quote) return null
            name.append(quote)
            position += 2
        } else {
            name.append(character)
            position++
        }
    }
    val columnName = name.toString().takeIf(String::isNotEmpty) ?: return null
    if (dialect == SemanticSqlDialect.REDSHIFT && columnName.any { it in 'A'..'Z' }) return null
    return ColumnIdentifier(columnName, true)
}

private val UNQUOTED_IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_$]*")
private val SCALAR_TYPE = Regex("([A-Z][A-Z0-9]*(?: [A-Z]+)*)(\\s*\\(\\s*[0-9]+\\s*(?:,\\s*[0-9]+\\s*)?\\))?")
private val PARAMETERIZED_SCALAR_TYPES = setOf("CHAR", "CHARACTER", "CHARACTER VARYING", "VARCHAR", "NCHAR", "NVARCHAR", "STRING", "DECIMAL", "NUMERIC", "BIGNUMERIC", "NUMBER", "FLOAT")
private val BARE_SQL_VALUES = setOf("NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP", "CURRENT_USER", "SESSION_USER", "SYSTEM_USER", "USER")
