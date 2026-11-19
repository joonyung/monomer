package cc.monomer.semantic.api

import cc.monomer.connector.api.ConnectorId

data class SemanticPartitionRelation(val catalog: String?, val schema: String, val name: String)

data class SemanticSourcePartitionInfo(
    val semanticModelName: SemanticElementName,
    val connectorId: ConnectorId,
    val relation: SemanticPartitionRelation,
    val requiresPartitionFilter: Boolean?,
    val partitionKeys: List<String>,
    val dimensions: List<SemanticElementName>,
    val timeRangeApplies: Boolean,
)

enum class SemanticPartitionFilterKind { SEMANTIC_MODEL, TIME_SPINE }

data class SemanticPartitionFilter(
    val kind: SemanticPartitionFilterKind,
    val connectorId: ConnectorId,
    val relation: SemanticPartitionRelation,
    val partitionKeys: List<String>,
    val semanticModelName: SemanticElementName?,
    val dimensions: List<SemanticElementName>?,
    val timeRangeApplies: Boolean?,
) {
    init {
        require(if (kind == SemanticPartitionFilterKind.SEMANTIC_MODEL) semanticModelName != null && dimensions != null && timeRangeApplies != null
            else semanticModelName == null && dimensions == null && timeRangeApplies == null)
    }
}

data class SemanticPartitionCheck(val validate: Boolean, val partitionFilters: List<SemanticPartitionFilter>)
