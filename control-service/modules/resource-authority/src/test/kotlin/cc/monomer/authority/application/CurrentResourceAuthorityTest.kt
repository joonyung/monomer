@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.authority.application

import cc.monomer.authority.api.CurrentResourceFocusConeDenial
import cc.monomer.authority.api.CurrentResourceFocusConeResolution
import cc.monomer.authority.api.LineageEdge
import cc.monomer.authority.api.ResourceKind
import cc.monomer.authority.api.ResourceRef
import cc.monomer.authority.domain.CurrentResourceGraphSnapshot
import cc.monomer.authority.domain.CurrentResourceNode
import cc.monomer.identity.api.WorkspaceId
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CurrentResourceAuthorityTest {
    private val workspaceId = WorkspaceId(Uuid.generateV7())
    private val relation = ref(ResourceKind.RELATION)
    private val model = ref(ResourceKind.SEMANTIC_MODEL)
    private val metric = ref(ResourceKind.METRIC)
    private val focus = ResourceAuthorityFocusConeResolver()

    @Test
    fun `focus cone returns ancestors descendants and immediate siblings`() {
        val downstream = ref(ResourceKind.METRIC)
        val sibling = ref(ResourceKind.SEMANTIC_MODEL)
        val snapshot = snapshot(
            listOf(relation, model, metric, sibling, downstream),
            listOf(LineageEdge(model, relation), LineageEdge(metric, model), LineageEdge(sibling, relation), LineageEdge(downstream, metric)),
        )
        val cone = assertInstanceOf(CurrentResourceFocusConeResolution.Resolved::class.java, focus.resolve(workspaceId, model, snapshot)).cone
        assertEquals(listOf(relation), cone.ancestors)
        assertEquals(listOf(metric, downstream).sorted(), cone.descendants)
        assertEquals(listOf(sibling), cone.siblings)
        assertEquals(listOf(LineageEdge(sibling, relation)), cone.siblingEdges)
        assertEquals(3, cone.edges.size)
    }

    @Test
    fun `focus denies missing duplicate foreign and dangling nodes`() {
        assertFocusDenial(CurrentResourceFocusConeDenial.RESOURCE_MISSING, snapshot(emptyList(), emptyList()))
        assertFocusDenial(CurrentResourceFocusConeDenial.DUPLICATE_RESOURCE_OWNERSHIP, snapshot(listOf(model, model), emptyList()))
        assertFocusDenial(
            CurrentResourceFocusConeDenial.CROSS_WORKSPACE_RESOURCE,
            CurrentResourceGraphSnapshot(workspaceId, listOf(CurrentResourceNode(model, WorkspaceId(Uuid.generateV7()))), emptyList(), emptySet()),
        )
        assertFocusDenial(CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE, snapshot(listOf(model), listOf(LineageEdge(model, relation))))
    }

    @Test
    fun `focus denies cycles and unresolved ancestors descendants and siblings`() {
        val sibling = ref(ResourceKind.SEMANTIC_MODEL)
        val snapshot = snapshot(
            listOf(model, relation, metric, sibling),
            listOf(LineageEdge(model, relation), LineageEdge(metric, model), LineageEdge(sibling, relation)),
        )
        for (unresolved in listOf(model, relation, metric, sibling)) {
            assertFocusDenial(CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE, snapshot.copy(unresolvedResourceRefs = setOf(unresolved)))
        }
        assertFocusDenial(CurrentResourceFocusConeDenial.LINEAGE_CYCLE, snapshot.copy(edges = snapshot.edges + LineageEdge(model, metric)))
    }

    @Test
    fun `unrelated unresolved definition does not deny focus but ambiguous candidate does`() {
        val unrelated = ref(ResourceKind.METRIC)
        val snapshot = snapshot(listOf(model, relation, unrelated), listOf(LineageEdge(model, relation)))
            .copy(unresolvedResourceRefs = setOf(unrelated))
        assertInstanceOf(CurrentResourceFocusConeResolution.Resolved::class.java, focus.resolve(workspaceId, model, snapshot))
        assertFocusDenial(CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE, snapshot.copy(edges = snapshot.edges + LineageEdge(unrelated, model)))
    }

    @Test
    fun `missing definition parents are displayed for ancestors descendants and siblings`() {
        val missing = ref(ResourceKind.METRIC)
        val downstream = ref(ResourceKind.METRIC)
        val sibling = ref(ResourceKind.METRIC)
        val snapshot = snapshot(listOf(relation, model, metric, downstream, sibling), listOf(
            LineageEdge(model, relation), LineageEdge(metric, model), LineageEdge(downstream, metric),
            LineageEdge(downstream, missing), LineageEdge(sibling, model), LineageEdge(sibling, missing),
        )).let { it.copy(nodes = it.nodes + CurrentResourceNode(missing, workspaceId, "deleted_metric")) }
        val cone = assertInstanceOf(CurrentResourceFocusConeResolution.Resolved::class.java,
            focus.resolve(workspaceId, metric, snapshot)).cone
        assertEquals(listOf(relation, model).sorted(), cone.ancestors)
        assertEquals(listOf(downstream), cone.descendants)
        assertEquals(listOf(sibling), cone.siblings)
        assertEquals("deleted_metric", cone.missingDefinitions.single().name)
        assertEquals(missing, cone.missingDefinitions.single().resourceRef)
        assertEquals(true, LineageEdge(downstream, missing) in cone.edges)
        assertEquals(true, LineageEdge(sibling, missing) in cone.siblingEdges)
        assertEquals(CurrentResourceFocusConeResolution.Denied(CurrentResourceFocusConeDenial.RESOURCE_MISSING),
            focus.resolve(workspaceId, missing, snapshot))
    }

    @Test
    fun `displayed missing definitions still enforce unique workspace ownership`() {
        val missing = ref(ResourceKind.METRIC)
        val snapshot = snapshot(listOf(model, metric), listOf(LineageEdge(metric, model), LineageEdge(metric, missing)))
        val foreign = CurrentResourceNode(missing, WorkspaceId(Uuid.generateV7()), "foreign_metric")
        assertFocusDenial(CurrentResourceFocusConeDenial.CROSS_WORKSPACE_LINEAGE, snapshot.copy(nodes=snapshot.nodes + foreign))
        val node = CurrentResourceNode(missing, workspaceId, "deleted_metric")
        assertFocusDenial(CurrentResourceFocusConeDenial.DUPLICATE_RESOURCE_OWNERSHIP, snapshot.copy(nodes=snapshot.nodes + node + node))
    }

    private fun ref(kind: ResourceKind): ResourceRef = ResourceRef(kind, Uuid.generateV7())

    private fun snapshot(refs: List<ResourceRef>, edges: List<LineageEdge>): CurrentResourceGraphSnapshot =
        CurrentResourceGraphSnapshot(workspaceId, refs.map { CurrentResourceNode(it, workspaceId) }, edges, emptySet())

    private fun assertFocusDenial(reason: CurrentResourceFocusConeDenial, snapshot: CurrentResourceGraphSnapshot) {
        assertEquals(CurrentResourceFocusConeResolution.Denied(reason), focus.resolve(workspaceId, model, snapshot))
    }
}
