package cc.monomer.control.semantic.inbound.rest

import cc.monomer.semantic.api.SemanticPartitionFilter
import cc.monomer.semantic.api.SemanticSourcePartitionInfo

internal fun SemanticPartitionFilter.toHttpValue(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "kind" to kind.name,
    "connectorId" to connectorId.toString(),
    "relation" to linkedMapOf("catalog" to relation.catalog, "schema" to relation.schema, "name" to relation.name),
    "partitionKeys" to partitionKeys,
).apply {
    semanticModelName?.let { put("semanticModelName", it.value) }
    dimensions?.let { put("dimensions", it.map { name -> name.value }) }
    timeRangeApplies?.let { put("timeRangeApplies", it) }
}

internal fun SemanticSourcePartitionInfo.toHttpValue(): Map<String, Any?> = linkedMapOf(
    "semanticModelName" to semanticModelName.value,
    "connectorId" to connectorId.toString(),
    "relation" to linkedMapOf("catalog" to relation.catalog, "schema" to relation.schema, "name" to relation.name),
    "requiresPartitionFilter" to requiresPartitionFilter,
    "partitionKeys" to partitionKeys,
    "dimensions" to dimensions.map { it.value },
    "timeRangeApplies" to timeRangeApplies,
)
