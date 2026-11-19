package cc.monomer.agent.api

import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
data class AgentPartitionRelation(val catalog: String?, val schema: String, val name: String)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
data class AgentPartitionFilter(
    val kind: String,
    val connectorId: Uuid,
    val relation: AgentPartitionRelation,
    val partitionKeys: List<String>,
    val semanticModelName: String?,
    val dimensions: List<String>?,
    val timeRangeApplies: Boolean?,
) {
    init {
        require(kind in setOf("SEMANTIC_MODEL", "TIME_SPINE"))
        require(if (kind == "SEMANTIC_MODEL") semanticModelName != null && dimensions != null && timeRangeApplies != null
            else semanticModelName == null && dimensions == null && timeRangeApplies == null)
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
data class AgentSourcePartitionInfo(
    val semanticModelName: String,
    val connectorId: Uuid,
    val relation: AgentPartitionRelation,
    val requiresPartitionFilter: Boolean?,
    val partitionKeys: List<String>,
    val dimensions: List<String>,
    val timeRangeApplies: Boolean,
)
