@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.publicapi

import cc.monomer.agent.api.*
import cc.monomer.connector.api.ConnectorProductType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class AgentManagementJsonTest {
    @Test
    fun `all three lineage detail projections serialize within public contract`() {
        val workspace = Uuid.generateV7()
        val connector = AgentLineageConnector(Uuid.generateV7(), "Warehouse", ConnectorProductType.POSTGRESQL)
        val details = listOf(
            Triple(AgentManagementResourceKind.RELATION, "relations", AgentLineageDetail.Relation(listOf(AgentLineageColumn("id", "BIGINT", false, true, true, false)))),
            Triple(AgentManagementResourceKind.SEMANTIC_MODEL, "semantic-models", AgentLineageDetail.SemanticModel(
                listOf(AgentLineageEntity("order", null, null, AgentSemanticEntityKind.PRIMARY)),
                listOf(AgentLineageDimension("ordered_at", null, null, AgentSemanticDimensionKind.TIME, AgentTimeGranularity.DAY)),
                listOf(AgentLineageMeasure("revenue", null, null, AgentSemanticAggregation.SUM)),
            )),
            Triple(AgentManagementResourceKind.METRIC, "metrics", AgentLineageDetail.Metric(AgentMetricType.SIMPLE, listOf(AgentLineageMetricInput(AgentLineageMetricInputKind.MEASURE, "revenue", "orders")), null)),
        )
        for ((kind, collection, detail) in details) {
            val ref = AgentManagementResource(kind, Uuid.generateV7())
            val resource = AgentLineageResource(ref, "Orders", "orders", null, connector.takeIf { kind == AgentManagementResourceKind.RELATION }, false)
            val body = AgentLineageResourceBody(workspace, resource, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), detail, emptyList(), listOf(AgentLineageDashboardUsage(Uuid.generateV7(), "Commerce overview", listOf("revenue"), listOf(Uuid.generateV7()))))
            val response = AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_contract"), AgentResourceEnvelope.LineageResource(AgentResourcePath.parse("workspace/lineage/$collection/${ref.id}"), body, emptyList()))
            assertEquals(response, PublicControlContractValidator().agentResource(response))
        }
    }
}
