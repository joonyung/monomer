package cc.monomer.control.agent

import cc.monomer.authority.api.ResourceKind
import cc.monomer.catalog.api.CatalogCurrentRelationPageCursor
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.lineage.LineageResourcePageCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentManagementCursorTest {
    @Test
    fun `cursor cannot be reused across organizations workspaces or collections`() {
        val scope = "organization-a:workspace-a:workspace/members"
        val cursor = AgentManagementCursor.encode(scope, listOf("member-id"))
        assertEquals(listOf("member-id"), AgentManagementCursor.decode(scope, cursor))
        listOf("organization-b:workspace-a:workspace/members", "organization-a:workspace-b:workspace/members",
            "organization-a:workspace-a:workspace/member-candidates").forEach {
            assertFailsWith<IllegalArgumentException> { AgentManagementCursor.decode(it, cursor) }
        }
    }

    @Test
    fun `lineage cursor preserves owner order including unicode and rejects another resource kind`() {
        val cursor = LineageResourcePageCursor.Relation("", CatalogCurrentRelationPageCursor(
            "catalog.고객.orders", ConnectorId.generate(), CatalogObjectId(kotlin.uuid.Uuid.parse("019e0000-0000-8000-8000-0000000000a1"))))
        val encoded = AgentManagementCursor.encodeLineage("scope", cursor)
        assertEquals(cursor, AgentManagementCursor.decodeLineage("scope", ResourceKind.RELATION, encoded))
        assertFailsWith<IllegalArgumentException> {
            AgentManagementCursor.decodeLineage("scope", ResourceKind.METRIC, encoded)
        }
    }
}
