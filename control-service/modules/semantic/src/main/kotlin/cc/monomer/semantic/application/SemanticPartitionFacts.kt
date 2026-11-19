package cc.monomer.semantic.application

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.*
import java.util.Locale

internal fun sourcePartitionInfo(
    definition: SemanticModelDefinition,
    connectorId: ConnectorId,
    catalog: CatalogCurrentObjectSet?,
): SemanticSourcePartitionInfo? {
    val source = definition.sourceRelationFor(connectorId) ?: return null
    val relation = catalog?.let { resolveModelSource(definition, connectorId, it) }
    val partitionKeys = relation?.partitionKeys.orEmpty()
    val normalizedKeys = partitionKeys.map(::normalizePartitionIdentifier).toSet().let { keys ->
        if (keys.any { it == "_partitiontime" || it == "_partitiondate" }) keys + setOf("_partitiontime", "_partitiondate") else keys
    }
    val dimensions = definition.dimensions.filter { dimension ->
        partitionExpressionIdentifiers(dimension.expression).any { it in normalizedKeys }
    }.map { it.name }
    val timeRangeApplies = definition.measures.any { measure ->
        (measure.aggregationTimeDimension ?: definition.aggregationTimeDimension) in dimensions
    }
    return SemanticSourcePartitionInfo(
        definition.name, connectorId,
        SemanticPartitionRelation(relation?.address?.catalogName ?: source.catalog, source.schema, source.relation),
        relation?.requiresPartitionFilter,
        partitionKeys,
        dimensions,
        timeRangeApplies,
    )
}

internal fun SemanticSourcePartitionInfo.requiredFilter(modelName: SemanticElementName): SemanticPartitionFilter? =
    takeIf { requiresPartitionFilter == true }?.let {
        SemanticPartitionFilter(SemanticPartitionFilterKind.SEMANTIC_MODEL, connectorId, relation, partitionKeys, modelName, dimensions, timeRangeApplies)
    }

internal fun CatalogCurrentObject.timeSpinePartitionFilter(connectorId: ConnectorId): SemanticPartitionFilter? =
    takeIf { requiresPartitionFilter == true }?.let {
        SemanticPartitionFilter(
            SemanticPartitionFilterKind.TIME_SPINE, connectorId,
            SemanticPartitionRelation(address.catalogName, requireNotNull(address.schemaName), requireNotNull(address.objectName)),
            partitionKeys.orEmpty(), null, null, null,
        )
    }

private fun normalizePartitionIdentifier(value: String): String = value.removeSurrounding("`").lowercase(Locale.ROOT)

internal fun partitionExpressionIdentifiers(expression: String): List<String> {
    val identifiers = mutableListOf<String>()
    var start = 0
    while (start < expression.length) {
        val first = expression[start]
        val end = when {
            first == '`' -> expression.indexOf('`', start + 1).takeIf { it > start + 1 }?.plus(1)
            first.isIdentifierStart() -> identifierEnd(expression, start)
            first == '\'' || first == '"' -> quotedLiteralEnd(expression, start, first)
            expression.startsWith("--", start) -> lineCommentEnd(expression, start)
            expression.startsWith("/*", start) -> expression.indexOf("*/", start + 2).takeIf { it >= 0 }?.plus(2)
            else -> null
        }
        if (end == null) {
            start++
            continue
        }
        if (first == '`') {
            identifiers += normalizePartitionIdentifier(expression.substring(start, end)).split('.')
        } else if (first.isIdentifierStart() && !isFollowedByOpeningParenthesis(expression, end)) {
            identifiers += normalizePartitionIdentifier(expression.substring(start, end))
        }
        start = end
    }
    return identifiers
}

private fun Char.isIdentifierStart(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this == '_'

private fun identifierEnd(expression: String, start: Int): Int {
    var index = start + 1
    while (index < expression.length && (expression[index].isIdentifierStart() || expression[index] in '0'..'9')) index++
    return index
}

private fun quotedLiteralEnd(expression: String, start: Int, quote: Char): Int? {
    var index = start + 1
    var lastDoubledQuoteEnd: Int? = null
    while (index < expression.length) {
        if (expression[index] != quote) {
            index++
            continue
        }
        val runStart = index
        while (index < expression.length && expression[index] == quote) index++
        if ((index - runStart) % 2 == 1) return index
        lastDoubledQuoteEnd = index - 1
    }
    // Without an odd quote run, the literal Q(QQ|[^Q])*Q can only close on the first quote of its last doubled pair.
    return lastDoubledQuoteEnd
}

private fun lineCommentEnd(expression: String, start: Int): Int {
    var index = start + 2
    while (index < expression.length && expression[index] != '\r' && expression[index] != '\n') index++
    return index
}

private fun isFollowedByOpeningParenthesis(expression: String, end: Int): Boolean {
    var index = end
    while (index < expression.length && expression[index].isWhitespace()) index++
    return index < expression.length && expression[index] == '('
}
