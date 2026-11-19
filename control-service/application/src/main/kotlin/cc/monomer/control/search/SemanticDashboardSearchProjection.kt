package cc.monomer.control.search

import cc.monomer.dashboard.api.DashboardSearchFields
import cc.monomer.dashboard.api.DashboardSearchProjection
import cc.monomer.semantic.api.MetricSearchFields
import cc.monomer.semantic.api.SemanticModelSearchFields
import cc.monomer.semantic.api.SemanticSearchProjection
import cc.monomer.search.api.SearchDocument
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchResourceKind
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class SemanticDashboardSearchProjection(private val searchIndex: SearchIndex) : SemanticSearchProjection, DashboardSearchProjection {
    override fun deleteModel(workspaceId: cc.monomer.identity.api.WorkspaceId, name: String) =
        searchIndex.deleteSubtree(UUID.fromString(workspaceId.toString()), "workspace/semantic-models/${segment(name)}")

    override fun deleteMetric(workspaceId: cc.monomer.identity.api.WorkspaceId, name: String) =
        searchIndex.deleteSubtree(UUID.fromString(workspaceId.toString()), "workspace/metrics/${segment(name)}")

    override fun upsertModel(fields: SemanticModelSearchFields) = searchIndex.upsert(SearchDocument(
        UUID.fromString(fields.workspaceId.toString()), "workspace/semantic-models/${segment(fields.name)}",
        SearchResourceKind.SEMANTIC_MODEL, fields.primaryNames, fields.descriptions, fields.structuralNames,
    ))

    override fun upsertMetric(fields: MetricSearchFields) = searchIndex.upsert(SearchDocument(
        UUID.fromString(fields.workspaceId.toString()), "workspace/metrics/${segment(fields.name)}",
        SearchResourceKind.METRIC, fields.primaryNames, fields.descriptions, fields.structuralNames,
    ))

    override fun delete(workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId) =
        searchIndex.deleteSubtree(UUID.fromString(workspaceId.toString()), "workspace/dashboards/$dashboardId")

    override fun upsert(fields: DashboardSearchFields) = searchIndex.upsert(SearchDocument(
        UUID.fromString(fields.workspaceId.toString()), "workspace/dashboards/${fields.dashboardId}",
        SearchResourceKind.DASHBOARD, fields.primaryNames, fields.descriptions, fields.structuralNames,
    ))

    private fun segment(value: String): String = value.replace("~", "~0").replace("/", "~1")
}
