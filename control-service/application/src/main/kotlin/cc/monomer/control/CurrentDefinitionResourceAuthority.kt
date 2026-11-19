@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.authority.api.CurrentResourceFocusConePort
import cc.monomer.authority.api.CurrentResourceFocusConeResolution
import cc.monomer.authority.api.LineageEdge
import cc.monomer.authority.api.ResourceKind
import cc.monomer.authority.api.ResourceRef
import cc.monomer.authority.application.ResourceAuthorityFocusConeResolver
import cc.monomer.authority.domain.CurrentResourceGraphSnapshot
import cc.monomer.authority.domain.CurrentResourceNode
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogCurrentRelationPageCursor
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.directMetricInputs
import cc.monomer.semantic.api.referencedModelNames
import cc.monomer.semantic.api.matchesCurrentAddress

class CurrentDefinitionResourceAuthority(
    private val semanticRead: SemanticCurrentReadUseCase,
    private val catalogRead: CatalogCurrentObjectsReadPort,
) : CurrentResourceFocusConePort {
    override fun readCurrentFocusCone(
        workspaceId: WorkspaceId,
        focus: ResourceRef,
    ): CurrentResourceFocusConeResolution = ResourceAuthorityFocusConeResolver().resolve(
        workspaceId, focus, readSnapshot(workspaceId),
    )

    private fun readSnapshot(
        workspaceId: WorkspaceId,
    ): CurrentResourceGraphSnapshot {
        val models = semanticRead.findModelsInCurrentTransaction(workspaceId)
        val metrics = semanticRead.findMetricsInCurrentTransaction(workspaceId)
        val modelsByName = models.groupBy { it.definition.name }
        val metricsByName = metrics.groupBy { it.definition.name }
        val nodes = mutableListOf<CurrentResourceNode>()
        val edges = linkedSetOf<LineageEdge>()
        val unresolved = linkedSetOf<ResourceRef>()
        val connectorIds = linkedSetOf<ConnectorId>()
        var cursor: CatalogCurrentRelationPageCursor? = null
        do {
            val page = catalogRead.searchCurrentRelations(workspaceId, "", cursor, 100)
            page.items.forEach { relation ->
                nodes += CurrentResourceNode(ResourceRef(ResourceKind.RELATION, relation.id.value), workspaceId)
            }
            cursor = page.nextCursor
        } while (cursor != null)
        models.forEach { model ->
            nodes += CurrentResourceNode(ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value), model.workspaceId)
            connectorIds += model.definition.sourceRelations.map { it.connectorId }
        }
        metrics.forEach { metric ->
            nodes += CurrentResourceNode(ResourceRef(ResourceKind.METRIC, metric.metricId.value), metric.workspaceId)
        }
        val catalogSets = connectorIds.associateWith { catalogRead.readCurrent(workspaceId, it) }
        fun addReference(dependent: ResourceRef, candidates: List<ResourceRef>) {
            if (candidates.size != 1 || dependent in candidates) unresolved += dependent
            candidates.filter { it != dependent }.forEach { edges += LineageEdge(dependent, it) }
        }
        models.forEach { model ->
            val dependent = ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value)
            val sources = model.definition.sourceRelations
            if (sources.isEmpty()) unresolved += dependent
            sources.forEach { source ->
                val candidates = catalogSets[source.connectorId]?.objects.orEmpty().filter {
                    it.kind == CatalogObjectKind.RELATION && source.matchesCurrentAddress(it.address)
                }.map { ResourceRef(ResourceKind.RELATION, it.id.value) }
                addReference(dependent, candidates)
            }
        }
        fun addDefinitionReference(dependent: ResourceRef, kind: ResourceKind, name: String, candidates: List<ResourceRef>) {
            if (candidates.isNotEmpty()) {
                addReference(dependent, candidates)
                return
            }
            val identity = java.util.UUID.nameUUIDFromBytes("$workspaceId:${kind.name}:$name".toByteArray(Charsets.UTF_8))
            val missing = ResourceRef(kind, kotlin.uuid.Uuid.parse(identity.toString()))
            if (nodes.none { it.resourceRef == missing }) nodes += CurrentResourceNode(missing, workspaceId, name)
            edges += LineageEdge(dependent, missing)
        }
        metrics.forEach { metric ->
            val dependent = ResourceRef(ResourceKind.METRIC, metric.metricId.value)
            metric.definition.directMetricInputs().forEach { input ->
                addDefinitionReference(dependent, ResourceKind.METRIC, input.metricName.value, metricsByName[input.metricName].orEmpty().map {
                    ResourceRef(ResourceKind.METRIC, it.metricId.value)
                })
            }
            metric.definition.referencedModelNames().forEach { name ->
                addDefinitionReference(dependent, ResourceKind.SEMANTIC_MODEL, name.value, modelsByName[name].orEmpty().map {
                    ResourceRef(ResourceKind.SEMANTIC_MODEL, it.semanticModelId.value)
                })
            }
        }
        return CurrentResourceGraphSnapshot(workspaceId, nodes, edges.sorted(), unresolved)
    }
}
