package cc.monomer.agent.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSearchScopeTest {
    private val connector = "workspace/connectors/0198ef28-1000-7000-8000-000000000001"

    @Test
    fun `admits supported collection and catalog ancestor scopes`() {
        val supported = listOf(
            "workspace", "workspace/connectors", "workspace/semantic-models", "workspace/metrics",
            "workspace/dashboards", "workspace/pages", connector, "$connector/catalog", "$connector/catalog/catalogs",
            "$connector/catalog/catalogs/warehouse", "$connector/catalog/schemas",
            "$connector/catalog/schemas/a~1b", "$connector/catalog/schemas/a~1b/relations",
            "$connector/catalog/catalogs/warehouse/schemas", "$connector/catalog/catalogs/warehouse/schemas/sales",
            "$connector/catalog/catalogs/warehouse/schemas/sales/relations",
        )
        supported.forEach { assertTrue(AgentSearchScope.supports(AgentResourcePath.parse(it)), it) }
    }

    @Test
    fun `rejects exact leaves jobs selections and unsupported collections`() {
        val unsupported = listOf(
            "workspace/explore/runs", "workspace/pages/tree", "workspace/pages/0198ef28-1000-7000-8000-000000000001", "workspace/metrics/orders", "workspace/connectors/not-an-id",
            "$connector/catalog/selection", "$connector/catalog/catalogs/warehouse/selection",
            "$connector/catalog/ingestions", "$connector/catalog/schemas/sales/relations/orders",
            "$connector/catalog/catalogs/warehouse/schemas/sales/relations/orders",
            "$connector/catalog/catalogs/warehouse/schemas/sales/extra",
            "organization", "organization/connectors", "organization/members",
            "organization/connectors/0198ef28-1000-7000-8000-000000000001",
        )
        unsupported.forEach { assertFalse(AgentSearchScope.supports(AgentResourcePath.parse(it)), it) }
    }
}
