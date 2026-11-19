@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.agent.api.*
import cc.monomer.catalog.api.*
import cc.monomer.connector.api.*
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.agent.AgentSearchAdapter
import cc.monomer.dashboard.api.*
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.search.adapter.outbound.postgres.PostgresSearchIndex
import cc.monomer.search.api.SearchIndex
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticElementName
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SearchApplicationComposition {
    @Bean fun searchIndex(productControlDataSource: DataSource): SearchIndex = PostgresSearchIndex(productControlDataSource)

    @Bean fun agentSearchPort(
        searchIndex: SearchIndex,
        consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
        controlAdmissionFacade: ControlAdmissionFacade,
        connectorSearchReadPort: ConnectorSearchReadPort,
        catalogSearchReadPort: CatalogSearchReadPort,
        semanticCurrentReadUseCase: SemanticCurrentReadUseCase,
        dashboardOwnerPort: DashboardOwnerPort,
        agentPagesOwnerPort: AgentPagesOwnerPort,
    ): AgentSearchPort {
        val current = SearchCurrentListings(connectorSearchReadPort, catalogSearchReadPort, semanticCurrentReadUseCase, dashboardOwnerPort)
        return AgentSearchAdapter(
            searchIndex, consistentSnapshotTransactionRunner,
            admitted = { caller, request ->
                val decision = controlAdmissionFacade.authorizeWorkspaceRole(WorkspaceRoleAdmissionRequest(
                    caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource,
                    AgentWorkspaceAdmissionIntent.Search(request.path, request.kinds).requiredRole,
                ))
                decision is ControlAdmissionDecision.Admitted && decision.context.actor.id.toString() == caller.actorPrincipalId
            },
            scopeExists = current::scopeExists,
            currentListings = current::read,
            pagesOwner = agentPagesOwnerPort,
        )
    }

    @Bean fun catalogTableSearchUseCase(
        searchIndex: SearchIndex,
        consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
        connectorSearchReadPort: ConnectorSearchReadPort,
        catalogSearchReadPort: CatalogSearchReadPort,
    ): CatalogTableSearchUseCase = CatalogTableSearchService(
        searchIndex, consistentSnapshotTransactionRunner, connectorSearchReadPort, catalogSearchReadPort,
    )

    @Bean fun workspaceResourceSearchUseCase(
        searchIndex: SearchIndex,
        consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
        catalogTableSearchUseCase: CatalogTableSearchUseCase,
        semanticCurrentReadUseCase: SemanticCurrentReadUseCase,
        dashboardOwnerPort: DashboardOwnerPort,
    ): WorkspaceResourceSearchUseCase = WorkspaceResourceSearchService(
        searchIndex, consistentSnapshotTransactionRunner, catalogTableSearchUseCase, semanticCurrentReadUseCase, dashboardOwnerPort,
    )
}

class SearchCurrentListings(
    private val connectors: ConnectorSearchReadPort,
    private val catalog: CatalogSearchReadPort,
    private val semantic: SemanticCurrentReadUseCase,
    private val dashboards: DashboardOwnerPort,
) {
    suspend fun scopeExists(caller: AgentWorkspaceCaller, path: AgentResourcePath): Boolean {
        val segments = path.value.split('/')
        if (segments.size <= 2) return true
        val connectorId = runCatching { ConnectorId(Uuid.parse(segments[2])) }.getOrNull() ?: return false
        val connector = connectors.readCurrentListings(caller.workspaceId, setOf(connectorId)).singleOrNull() ?: return false
        if (segments.size <= 4) return true
        val threeDepth = connector.connectorType.hasCatalogLevel
        val tail = segments.drop(4)
        if ((tail[0] == "catalogs") != threeDepth) return false
        if (threeDepth && tail.size == 1) return true
        val catalogName = if (threeDepth) AgentResourcePath.decodeNameSegment(tail[1]) else null
        val schemaTail = if (threeDepth) tail.drop(2) else tail
        val schemaName = if (schemaTail.size >= 2) AgentResourcePath.decodeNameSegment(schemaTail[1]) else null
        if (catalogName == null && schemaName == null) return true
        val address = CatalogSearchAddress(connectorId, catalogName, schemaName, null)
        return catalog.readCurrentListings(caller.workspaceId, setOf(address)).isNotEmpty()
    }

    suspend fun read(caller: AgentWorkspaceCaller, paths: List<AgentResourcePath>): List<AgentResourceListingItem> {
        val connectorIds = paths.filter { it.value.startsWith("workspace/connectors/") }
            .map { ConnectorId(Uuid.parse(it.value.split('/')[2])) }.toSet()
        val currentConnectors = if (connectorIds.isEmpty()) emptyMap() else
            connectors.readCurrentListings(caller.workspaceId, connectorIds).associateBy { it.connectorId }
        val catalogPaths = paths.mapNotNull { path -> catalogSearchAddress(path.value)?.let { it to path } }.toMap()
        val catalogItems = if (catalogPaths.isEmpty()) emptyList() else catalog.readCurrentListings(caller.workspaceId, catalogPaths.keys)
        val metricNames = paths.filter { it.value.startsWith("workspace/metrics/") && it.value.split('/').size == 3 }
            .map { SemanticElementName(AgentResourcePath.decodeNameSegment(it.value.substringAfterLast('/'))) }.toSet()
        val metricSourceConnectors = if (metricNames.isEmpty()) emptyMap() else
            semantic.findMetricSourceConnectors(caller.workspaceId, metricNames)
        val items = mutableListOf<AgentResourceListingItem>()
        catalogItems.forEach { item ->
            if (item.address.connectorId !in currentConnectors) return@forEach
            val path = catalogPaths.getValue(item.address)
            items += when (item.kind) {
                CatalogObjectKind.CATALOG -> AgentResourceListingItem.Catalog(path, requireNotNull(item.address.catalogName))
                CatalogObjectKind.SCHEMA -> AgentResourceListingItem.Schema(path, requireNotNull(item.address.schemaName), item.updatedAt)
                CatalogObjectKind.RELATION -> AgentResourceListingItem.Relation(path, requireNotNull(item.address.relationName),
                    AgentRelationKind.valueOf(requireNotNull(item.relationKind).name), item.updatedAt)
                else -> error("Unsupported Catalog search listing")
            }
        }
        for (path in paths) {
            val segments = path.value.split('/')
            if (segments.size != 3) continue
            val name = AgentResourcePath.decodeNameSegment(segments[2])
            when (segments[1]) {
                "connectors" -> currentConnectors[ConnectorId(Uuid.parse(name))]?.let {
                    items += AgentResourceListingItem.Connector(path, it.connectorType.name, it.displayName)
                }
                "semantic-models" -> semantic.findModelByName(caller.workspaceId, SemanticElementName(name))?.let {
                    items += AgentResourceListingItem.SemanticModel(path, it.definition.name.value)
                }
                "metrics" -> semantic.findMetricByName(caller.workspaceId, SemanticElementName(name))?.let {
                    items += AgentResourceListingItem.Metric(path, it.definition.name.value,
                        metricSourceConnectors.getValue(it.definition.name).map { connector ->
                            cc.monomer.agent.api.AgentMetricSourceConnector(connector.connectorId.value, connector.displayName)
                        })
                }
                "dashboards" -> when (val outcome = dashboards.read(caller.workspaceId, DashboardId(Uuid.parse(name)))) {
                    is DashboardOwnerOutcome.Success -> {
                        val value = outcome.value
                        val meta = value.definition["meta"]?.jsonObject
                        items += AgentResourceListingItem.Dashboard(path, meta?.get("title")?.jsonPrimitive?.contentOrNull,
                            meta?.get("description")?.jsonPrimitive?.contentOrNull, value.updatedAt)
                    }
                    DashboardOwnerOutcome.NotFound -> Unit
                    else -> error("Dashboard search listing read failed")
                }
            }
        }
        return items
    }
}
