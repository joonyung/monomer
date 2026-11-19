@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.semantic.api.SemanticPartitionFilter
import cc.monomer.semantic.api.SemanticSourcePartitionInfo

internal fun SemanticPartitionFilter.toAgent() = AgentPartitionFilter(
    kind.name, connectorId.value, AgentPartitionRelation(relation.catalog, relation.schema, relation.name),
    partitionKeys, semanticModelName?.value, dimensions?.map { it.value }, timeRangeApplies,
)

internal fun SemanticSourcePartitionInfo.toAgent() = AgentSourcePartitionInfo(
    semanticModelName.value, connectorId.value, AgentPartitionRelation(relation.catalog, relation.schema, relation.name),
    requiresPartitionFilter, partitionKeys, dimensions.map { it.value }, timeRangeApplies,
)

internal fun AgentPartitionFilter.toHttpValue(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "kind" to kind, "connectorId" to connectorId.toString(),
    "relation" to mapOf("catalog" to relation.catalog, "schema" to relation.schema, "name" to relation.name),
    "partitionKeys" to partitionKeys,
).apply {
    semanticModelName?.let { put("semanticModelName", it) }
    dimensions?.let { put("dimensions", it) }
    timeRangeApplies?.let { put("timeRangeApplies", it) }
}

internal fun AgentSourcePartitionInfo.toHttpValue(): Map<String, Any?> = mapOf(
    "semanticModelName" to semanticModelName,
    "connectorId" to connectorId.toString(),
    "relation" to mapOf("catalog" to relation.catalog, "schema" to relation.schema, "name" to relation.name),
    "requiresPartitionFilter" to requiresPartitionFilter,
    "partitionKeys" to partitionKeys, "dimensions" to dimensions, "timeRangeApplies" to timeRangeApplies,
)
