@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.authority.api.*
import cc.monomer.catalog.api.*
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CurrentDefinitionResourceAuthorityTest {
    private val workspace = WorkspaceId.generate()
    private val connectorA = ConnectorId.generate()
    private val connectorB = ConnectorId.generate()
    private val semantic = mockk<SemanticCurrentReadUseCase>()
    private val catalog = mockk<CatalogCurrentObjectsReadPort>()
    private val authority = CurrentDefinitionResourceAuthority(semantic, catalog)
    private val models = mutableListOf<SemanticModelView>()
    private val metrics = mutableListOf<MetricView>()
    private val relations = linkedMapOf<ConnectorId, MutableList<CatalogCurrentObject>>()
    private val now = Instant.parse("2026-09-06T00:00:00Z")

    init {
        every { semantic.findModelsInCurrentTransaction(workspace) } answers { models.toList() }
        every { semantic.findMetricsInCurrentTransaction(workspace) } answers { metrics.toList() }
        listOf(connectorA, connectorB).forEach { connector ->
            every { catalog.readCurrent(workspace, connector) } answers {
                val selected = relations[connector].orEmpty()
                mockk { every { objects } returns selected }
            }
        }
        every { catalog.searchCurrentRelations(workspace, "", any(), 100) } answers {
            val cursor = thirdArg<CatalogCurrentRelationPageCursor?>()
            val inventory = relations.flatMap { (connector, objects) ->
                objects.map { CatalogCurrentRelationSummary(it.id, connector, it.id.toString()) }
            }.sortedBy { it.qualifiedName }
            val remaining = inventory.filter { cursor == null || it.qualifiedName > cursor.normalizedQualifiedName }
            val page = remaining.take(100)
            CatalogCurrentRelationPage(page, if (remaining.size > 100) {
                page.last().let { CatalogCurrentRelationPageCursor(it.qualifiedName, it.connectorId, it.id) }
            } else null)
        }
    }

    @Test
    fun `complete Catalog inventory includes a focus beyond the first hundred relations`() {
        val inventory = (1..105).map { relation(it, connectorA, "relation_$it") }
        val focus = ref(inventory.last())
        val resolved = assertIs<CurrentResourceFocusConeResolution.Resolved>(authority.readCurrentFocusCone(workspace, focus))
        assertEquals(focus, resolved.cone.focus)
        assertEquals(emptyList(), resolved.cone.ancestors)
        verify(exactly = 1) { catalog.searchCurrentRelations(workspace, "", null, 100) }
        verify(exactly = 1) { catalog.searchCurrentRelations(workspace, "", isNull(inverse = true), 100) }
        verify(exactly = 0) { catalog.readCurrent(workspace, connectorA) }
    }

    @Test
    fun `reverse lineage reads all owner definitions and ignores unrelated unresolved definitions`() {
        val relation = relation(1, connectorA)
        val model = model("orders", connectorA)
        repeat(105) { metric("revenue_$it", "orders") }
        metric("unrelated", "missing_model")
        val cone = assertIs<CurrentResourceFocusConeResolution.Resolved>(authority.readCurrentFocusCone(workspace, ref(relation))).cone
        assertEquals(106, cone.descendants.size)
        assertTrue(ref(model) in cone.descendants)
        assertTrue(ref(metrics[104]) in cone.descendants)
    }

    @Test
    fun `a dangling metric input is displayed without rejecting healthy cones`() {
        val relation = relation(1, connectorA)
        model("orders", connectorA)
        val base = metric("base", "orders")
        val total = derived("total", "base", "missing")
        val cone = assertIs<CurrentResourceFocusConeResolution.Resolved>(authority.readCurrentFocusCone(workspace, ref(relation))).cone
        assertTrue(ref(base) in cone.descendants)
        assertTrue(ref(total) in cone.descendants)
        val missing = cone.missingDefinitions.single()
        assertEquals("missing", missing.name)
        assertEquals(ResourceKind.METRIC, missing.resourceRef.kind)
        assertTrue(LineageEdge(ref(total), missing.resourceRef) in cone.edges)
        assertEquals(
            missing.resourceRef,
            assertIs<CurrentResourceFocusConeResolution.Resolved>(authority.readCurrentFocusCone(workspace, ref(base))).cone.missingDefinitions.single().resourceRef,
        )
        val replacement = metric("missing", "orders")
        val recovered = assertIs<CurrentResourceFocusConeResolution.Resolved>(authority.readCurrentFocusCone(workspace, ref(total))).cone
        assertTrue(recovered.missingDefinitions.isEmpty())
        assertTrue(LineageEdge(ref(total), ref(replacement)) in recovered.edges)
    }

    @Test
    fun `a missing semantic model is displayed as a named definition node`() {
        val orphan = metric("revenue", "deleted_orders")
        val cone = assertIs<CurrentResourceFocusConeResolution.Resolved>(authority.readCurrentFocusCone(workspace, ref(orphan))).cone
        assertEquals(emptyList(), cone.ancestors)
        val missing = cone.missingDefinitions.single()
        assertEquals("deleted_orders", missing.name)
        assertEquals(ResourceKind.SEMANTIC_MODEL, missing.resourceRef.kind)
        assertEquals(listOf(LineageEdge(ref(orphan), missing.resourceRef)), cone.edges)
        assertEquals(CurrentResourceFocusConeDenial.RESOURCE_MISSING,
            assertIs<CurrentResourceFocusConeResolution.Denied>(authority.readCurrentFocusCone(workspace, missing.resourceRef)).reason)
    }

    @Test
    fun `ambiguous declared source fails the related cone but does not poison an unrelated relation`() {
        val orders = relation(1, connectorA)
        relation(2, connectorA, "orders", "another_catalog")
        val unrelated = relation(3, connectorA, "independent")
        model("orders", connectorA, catalogName = null)
        assertEquals(
            CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE,
            assertIs<CurrentResourceFocusConeResolution.Denied>(authority.readCurrentFocusCone(workspace, ref(orders))).reason,
        )
        assertIs<CurrentResourceFocusConeResolution.Resolved>(authority.readCurrentFocusCone(workspace, ref(unrelated)))
    }

    private fun relation(index: Int, connector: ConnectorId, name: String = "orders", catalogName: String = "warehouse"): CatalogCurrentObject {
        val objectId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-${index.toString().padStart(12, '0')}"))
        val current = mockk<CatalogCurrentObject> {
            every { id } returns objectId
            every { kind } returns CatalogObjectKind.RELATION
            every { address } returns CatalogPhysicalAddress(catalogName, "analytics", name, null)
        }
        relations.getOrPut(connector) { mutableListOf() } += current
        return current
    }

    private fun model(name: String, connector: ConnectorId, catalogName: String? = "warehouse", extra: SemanticSourceRelation? = null): SemanticModelView {
        val sources = listOfNotNull(SemanticSourceRelation(connector, catalogName, "analytics", "orders"), extra)
        val definition = mockk<SemanticModelDefinition> {
            every { this@mockk.name } returns SemanticElementName(name)
            every { sourceRelations } returns sources
        }
        return SemanticModelView(SemanticModelId.generate(), workspace, definition, now, now,
            emptyList(),
        ).also(models::add)
    }

    private fun metric(name: String, model: String): MetricView = MetricView(
        MetricId.generate(), workspace,
        SemanticMetricDefinition.Simple(3u, SemanticElementName(name), null, null, null, emptyList(), SemanticElementName(model), SemanticElementName("revenue"), display = null),
        now, now,
        emptyList(),
    ).also(metrics::add)

    private fun derived(name: String, vararg inputs: String): MetricView = MetricView(
        MetricId.generate(), workspace,
        SemanticMetricDefinition.Derived(3u, SemanticElementName(name), null, null, null, emptyList(), inputs.first(), inputs.map(::input), display = null),
        now, now,
        emptyList(),
    ).also(metrics::add)

    private fun input(name: String) = SemanticMetricInput(SemanticElementName(name), null, null, null, null)
    private fun ref(relation: CatalogCurrentObject) = ResourceRef(ResourceKind.RELATION, relation.id.value)
    private fun ref(model: SemanticModelView) = ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value)
    private fun ref(metric: MetricView) = ResourceRef(ResourceKind.METRIC, metric.metricId.value)
}
