@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.lineage

import cc.monomer.authority.api.CurrentResourceFocusConePort
import cc.monomer.authority.api.CurrentResourceFocusConeResolution
import cc.monomer.authority.api.LineageEdge
import cc.monomer.authority.api.ResourceKind
import cc.monomer.authority.api.ResourceRef
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentRelationPageCursor
import cc.monomer.catalog.api.CatalogCurrentRelationSummary
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogRelationshipKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.MetricPageCursor
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticModelPageCursor

class LineageGraphQueryService(
    private val focusConePort: CurrentResourceFocusConePort,
    private val connectorReadUseCase: ConnectorWorkspaceReadUseCase,
    private val catalogCurrentObjectsReadPort: CatalogCurrentObjectsReadPort,
    private val semanticCurrentReadUseCase: SemanticCurrentReadUseCase,
    private val dashboardOwnerPort: DashboardOwnerPort,
    private val controlAdmissionFacade: ControlAdmissionFacade,
    private val consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
) {
    suspend fun readDefaultFocus(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): LineageDefaultFocusResult = consistentSnapshotTransactionRunner.run {
        when (
            controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(workspaceId, credential, expectedOAuthResource, cc.monomer.identity.api.WorkspaceRole.EXPLORER),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> Unit
            is ControlAdmissionDecision.Denied -> return@run LineageDefaultFocusResult.Unavailable
        }

        val semanticCandidates = listOfNotNull(
            semanticCurrentReadUseCase.findLatestModel(workspaceId)?.let { model ->
                DefaultFocusCandidate(
                    resource = model.toLineageResource(),
                    updatedAt = model.updatedAt,
                    kindRank = 0,
                )
            },
            semanticCurrentReadUseCase.findLatestMetric(workspaceId)?.let { metric ->
                DefaultFocusCandidate(
                    resource = metric.toLineageResource(),
                    updatedAt = metric.updatedAt,
                    kindRank = 1,
                )
            },
        )
        val semanticFocus = semanticCandidates.maxWithOrNull(defaultFocusComparator)?.resource
        if (semanticFocus != null) {
            return@run LineageDefaultFocusResult.Found(semanticFocus)
        }

        val relation = catalogCurrentObjectsReadPort.searchCurrentRelations(workspaceId, "", null, 1)
            .items
            .singleOrNull()
            ?: return@run LineageDefaultFocusResult.Empty
        val connector = connectorReadUseCase.findByWorkspaceAndId(workspaceId, relation.connectorId)
            ?: return@run LineageDefaultFocusResult.Unavailable
        LineageDefaultFocusResult.Found(relation.toLineageResource(connector))
    }

    suspend fun listResources(
        workspaceId: WorkspaceId,
        kind: ResourceKind,
        query: String,
        cursor: LineageResourcePageCursor?,
        pageSize: Int,
        credential: PresentedCredential,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): LineageResourceInventoryResult = consistentSnapshotTransactionRunner.run {
        require(pageSize in 1..MAXIMUM_LINEAGE_RESOURCE_PAGE_SIZE) {
            "page size must be between 1 and $MAXIMUM_LINEAGE_RESOURCE_PAGE_SIZE"
        }
        when (
            controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(workspaceId, credential, expectedOAuthResource, cc.monomer.identity.api.WorkspaceRole.EXPLORER),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> Unit
            is ControlAdmissionDecision.Denied -> return@run LineageResourceInventoryResult.Unavailable
        }

        val normalizedQuery = query.trim().lowercase()
        if (cursor != null && (cursor.kind != kind || cursor.normalizedQuery != normalizedQuery)) {
            return@run LineageResourceInventoryResult.InvalidCursor
        }
        val page = when (kind) {
            ResourceKind.RELATION -> searchRelations(workspaceId, normalizedQuery, cursor, pageSize)
            ResourceKind.SEMANTIC_MODEL -> searchModels(workspaceId, normalizedQuery, cursor, pageSize)
            ResourceKind.METRIC -> searchMetrics(workspaceId, normalizedQuery, cursor, pageSize)
        } ?: return@run LineageResourceInventoryResult.Unavailable
        LineageResourceInventoryResult.Found(page)
    }

    suspend fun read(
        workspaceId: WorkspaceId,
        focus: ResourceRef,
        credential: PresentedCredential,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): LineageGraphQueryResult = consistentSnapshotTransactionRunner.run {
        when (
            controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(workspaceId, credential, expectedOAuthResource, cc.monomer.identity.api.WorkspaceRole.EXPLORER),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> Unit
            is ControlAdmissionDecision.Denied -> return@run LineageGraphQueryResult.Unavailable
        }

        val cone = when (val resolution = focusConePort.readCurrentFocusCone(workspaceId, focus)) {
            is CurrentResourceFocusConeResolution.Resolved -> resolution.cone
            is CurrentResourceFocusConeResolution.Denied -> return@run LineageGraphQueryResult.InvalidLineage(resolution.reason)
        }
        val coneRefs = (listOf(cone.focus) + cone.ancestors + cone.descendants).toSet()
        val displayRefs = coneRefs + cone.siblings
        val currentResources = readRequestLocalResources(workspaceId, displayRefs, cone.focus)
        val metadata = readMetadata(displayRefs, currentResources)
            ?: return@run LineageGraphQueryResult.Unavailable
        val entityPeers = readEntityPeers(focus, coneRefs, currentResources.models)
            ?: return@run LineageGraphQueryResult.Unavailable
        val detail = readFocusDetail(cone.focus, currentResources)
            ?: return@run LineageGraphQueryResult.Unavailable

        LineageGraphQueryResult.Found(
            LineageGraph(
                workspaceId = workspaceId,
                focus = checkNotNull(metadata[cone.focus]),
                ancestors = cone.ancestors.map(metadata::getValue),
                descendants = cone.descendants.map(metadata::getValue),
                siblings = cone.siblings.map(metadata::getValue),
                missingDefinitions = cone.missingDefinitions.map { missing ->
                    LineageResource(missing.resourceRef, missing.name, missing.name, "Missing definition", null, true)
                },
                edges = cone.edges,
                siblingEdges = cone.siblingEdges,
                entityPeers = entityPeers,
                detail = detail,
                dashboardUsages = readDashboardUsages(workspaceId, cone.focus, cone.descendants, currentResources.metrics),
            ),
        )
    }

    private suspend fun readDashboardUsages(
        workspaceId: WorkspaceId,
        focus: ResourceRef,
        descendants: List<ResourceRef>,
        metrics: List<MetricView>,
    ): List<LineageDashboardUsage> {
        val affectedMetricIds = (listOf(focus) + descendants).filter { it.kind == ResourceKind.METRIC }.map { it.id }.toSet()
        if (affectedMetricIds.isEmpty()) return emptyList()
        val affectedMetricsByName = metrics.filter { it.metricId.value in affectedMetricIds }.associate { it.definition.name.value to it.metricId.value }
        return dashboardOwnerPort.readCurrentSemanticReferences(workspaceId).mapNotNull { dashboard ->
            val matchingQueries = dashboard.queryMetricNames.mapValues { (_, names) -> names.mapNotNull(affectedMetricsByName::get) }
                .filterValues { it.isNotEmpty() }
            if (matchingQueries.isEmpty()) return@mapNotNull null
            LineageDashboardUsage(
                dashboardId = dashboard.dashboardId.value,
                title = dashboard.title,
                queryNames = matchingQueries.keys.sorted(),
                metricIds = matchingQueries.values.flatten().distinct().sortedBy { it.toString() },
            )
        }.sortedWith(compareBy({ it.title }, { it.dashboardId.toString() }))
    }

    private fun readMetadata(
        refs: Set<ResourceRef>,
        currentResources: RequestLocalResources,
    ): Map<ResourceRef, LineageResource>? {
        val metadata = linkedMapOf<ResourceRef, LineageResource>()
        val relationRefs = refs.filterTo(linkedSetOf()) { it.kind == ResourceKind.RELATION }
        if (relationRefs.isNotEmpty()) {
            val relationsByRef = currentResources.catalogObjectSets
                .flatMap { objectSet ->
                    val connector = currentResources.connectors
                        .singleOrNull { it.connectorId == objectSet.connectorId }
                        ?.toLineageConnector()
                        ?: return@flatMap emptyList()
                    objectSet.objects
                        .asSequence()
                        .filter { it.kind == CatalogObjectKind.RELATION }
                        .map { relation ->
                            ResourceRef(ResourceKind.RELATION, relation.id.value) to LineageResource(
                                ref = ResourceRef(ResourceKind.RELATION, relation.id.value),
                                label = relation.qualifiedName,
                                address = relation.qualifiedName,
                                description = null,
                                connector = connector,
                                missing = false,
                            )
                        }
                        .toList()
                }
                .groupBy({ it.first }, { it.second })
            relationRefs.forEach { ref ->
                val candidates = relationsByRef[ref].orEmpty()
                if (candidates.size != 1) return null
                metadata[ref] = candidates.single()
            }
        }

        currentResources.models.forEach { model ->
            val ref = ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value)
            if (ref in refs) {
                metadata[ref] = LineageResource(
                    ref = ref,
                    label = model.definition.label ?: model.definition.name.value,
                    address = model.definition.name.value,
                    description = model.definition.description,
                    connector = null,
                    missing = false,
                )
            }
        }
        currentResources.metrics.forEach { metric ->
            val ref = ResourceRef(ResourceKind.METRIC, metric.metricId.value)
            if (ref in refs) {
                metadata[ref] = LineageResource(
                    ref = ref,
                    label = metric.definition.label ?: metric.definition.name.value,
                    address = metric.definition.name.value,
                    description = metric.definition.description,
                    connector = null,
                    missing = false,
                )
            }
        }
        return metadata.takeIf { it.keys.containsAll(refs) }
    }

    private fun readFocusDetail(
        focus: ResourceRef,
        currentResources: RequestLocalResources,
    ): LineageResourceDetail? = when (focus.kind) {
        ResourceKind.RELATION -> readRelationFocusDetail(focus, currentResources.catalogObjectSets)
        ResourceKind.SEMANTIC_MODEL -> readSemanticModelFocusDetail(focus, currentResources.models)
        ResourceKind.METRIC -> readMetricFocusDetail(focus, currentResources.metrics)
    }

    private fun readRelationFocusDetail(
        focus: ResourceRef,
        currentObjectSets: List<CatalogCurrentObjectSet>,
    ): LineageResourceDetail? {
        val candidates = currentObjectSets.flatMap { objectSet ->
            objectSet.objects.filter { currentObject ->
                currentObject.kind == CatalogObjectKind.RELATION && currentObject.id.value == focus.id
            }
        }
        val relation = candidates.singleOrNull() ?: return null
        val relationObjectSet = currentObjectSets.singleOrNull { objectSet ->
            relation in objectSet.objects
        } ?: return null
        val columns = relationObjectSet.objects
            .filter { currentObject ->
                currentObject.kind == CatalogObjectKind.COLUMN && currentObject.parentId == relation.id
            }
            .sortedWith(compareBy({ it.ordinal }, { it.address.memberName }))
        val relationships = relationObjectSet.objects
            .filter { currentObject ->
                currentObject.kind == CatalogObjectKind.RELATIONSHIP && currentObject.parentId == relation.id
            }
            .sortedWith(compareBy({ it.sourceColumn }, { it.memberOrdinal }))
        val columnNames = columns.mapNotNull { it.address.memberName }.toSet()
        if (columnNames.size != columns.size || relationships.any { it.sourceColumn !in columnNames }) {
            return null
        }
        val relationDetail = LineageRelationDetail(
            columns = columns.map { column ->
                val columnRelationships = relationships.filter { relationship ->
                    relationship.sourceColumn == column.address.memberName
                }
                LineageRelationColumn(
                    name = column.address.memberName ?: return null,
                    nativeType = column.nativeType ?: return null,
                    nullable = column.nullable ?: return null,
                    hasDefault = column.defaultExpression != null,
                    primaryKey = columnRelationships.any {
                        it.relationshipKind == CatalogRelationshipKind.PRIMARY_KEY
                    },
                    foreignKey = columnRelationships.any {
                        it.relationshipKind == CatalogRelationshipKind.FOREIGN_KEY
                    },
                )
            },
        )
        return LineageResourceDetail(
            relation = relationDetail,
            semanticModel = null,
            metric = null,
        )
    }

    private fun readSemanticModelFocusDetail(
        focus: ResourceRef,
        models: List<SemanticModelView>,
    ): LineageResourceDetail? {
        val model = models.singleOrNull { it.semanticModelId.value == focus.id }
            ?: return null
        val definition = model.definition
        return LineageResourceDetail(
            relation = null,
            semanticModel = LineageSemanticModelDetail(
                entities = definition.entities
                    .sortedBy { it.name.value }
                    .map { entity ->
                        LineageSemanticEntity(
                            name = entity.name.value,
                            label = entity.label,
                            description = entity.description,
                            type = entity.type,
                        )
                    },
                dimensions = definition.dimensions
                    .sortedBy { it.name.value }
                    .map { dimension ->
                        LineageSemanticDimension(
                            name = dimension.name.value,
                            label = dimension.label,
                            description = dimension.description,
                            type = dimension.type,
                            granularity = dimension.granularity,
                        )
                    },
                measures = definition.measures
                    .sortedBy { it.name.value }
                    .map { measure ->
                        LineageSemanticMeasure(
                            name = measure.name.value,
                            label = measure.label,
                            description = measure.description,
                            aggregation = measure.aggregation,
                        )
                    },
            ),
            metric = null,
        )
    }

    private fun readMetricFocusDetail(
        focus: ResourceRef,
        metrics: List<MetricView>,
    ): LineageResourceDetail? {
        val metric = metrics.singleOrNull { it.metricId.value == focus.id }
            ?: return null
        val definition = metric.definition
        val inputs = when (definition) {
            is SemanticMetricDefinition.Simple -> listOf(
                LineageMetricInput(
                    kind = LineageMetricInputKind.MEASURE,
                    name = definition.measure.value,
                    semanticModelName = definition.semanticModelName.value,
                ),
            )

            is SemanticMetricDefinition.Ratio -> listOf(
                definition.numerator.toLineageMetricInput(),
                definition.denominator.toLineageMetricInput(),
            )

            is SemanticMetricDefinition.Derived -> definition.inputMetrics.map { input ->
                input.toLineageMetricInput()
            }

            is SemanticMetricDefinition.Cumulative -> listOf(
                definition.inputMetric.toLineageMetricInput(),
            )

            is SemanticMetricDefinition.Conversion -> listOf(
                definition.baseMetric.toLineageMetricInput(),
                definition.conversionMetric.toLineageMetricInput(),
            )
        }
        return LineageResourceDetail(
            relation = null,
            semanticModel = null,
            metric = LineageMetricDetail(
                type = definition.type,
                inputs = inputs,
                timeGranularity = definition.timeGranularity,
            ),
        )
    }

    private fun cc.monomer.semantic.api.SemanticMetricInput.toLineageMetricInput(): LineageMetricInput = LineageMetricInput(
        kind = LineageMetricInputKind.METRIC,
        name = metricName.value,
        semanticModelName = null,
    )

    private suspend fun searchRelations(
        workspaceId: WorkspaceId,
        normalizedQuery: String,
        cursor: LineageResourcePageCursor?,
        pageSize: Int,
    ): LineageResourcePage? {
        val ownerPage = catalogCurrentObjectsReadPort.searchCurrentRelations(
            workspaceId = workspaceId,
            query = normalizedQuery,
            cursor = (cursor as? LineageResourcePageCursor.Relation)?.ownerCursor,
            pageSize = pageSize,
        )
        val connectorIds = ownerPage.items.mapTo(linkedSetOf(), CatalogCurrentRelationSummary::connectorId)
        val connectorsById = connectorReadUseCase.findByWorkspaceAndIds(workspaceId, connectorIds)
            .associateBy(CurrentConnectorRead::connectorId)
        val items = ownerPage.items.map { relation ->
            val connector = connectorsById[relation.connectorId] ?: return null
            relation.toLineageResource(connector)
        }
        return LineageResourcePage(
            items = items,
            nextCursor = ownerPage.nextCursor?.let { next ->
                LineageResourcePageCursor.Relation(normalizedQuery, next)
            },
        )
    }

    private suspend fun searchModels(
        workspaceId: WorkspaceId,
        normalizedQuery: String,
        cursor: LineageResourcePageCursor?,
        pageSize: Int,
    ): LineageResourcePage {
        val ownerPage = semanticCurrentReadUseCase.searchModels(
            workspaceId = workspaceId,
            query = normalizedQuery,
            cursor = (cursor as? LineageResourcePageCursor.SemanticModel)?.ownerCursor,
            pageSize = pageSize,
        )
        return LineageResourcePage(
            items = ownerPage.items.map(SemanticModelView::toLineageResource),
            nextCursor = ownerPage.nextCursor?.let { next ->
                LineageResourcePageCursor.SemanticModel(normalizedQuery, next)
            },
        )
    }

    private suspend fun searchMetrics(
        workspaceId: WorkspaceId,
        normalizedQuery: String,
        cursor: LineageResourcePageCursor?,
        pageSize: Int,
    ): LineageResourcePage {
        val ownerPage = semanticCurrentReadUseCase.searchMetrics(
            workspaceId = workspaceId,
            query = normalizedQuery,
            cursor = (cursor as? LineageResourcePageCursor.Metric)?.ownerCursor,
            pageSize = pageSize,
        )
        return LineageResourcePage(
            items = ownerPage.items.map(MetricView::toLineageResource),
            nextCursor = ownerPage.nextCursor?.let { next ->
                LineageResourcePageCursor.Metric(normalizedQuery, next)
            },
        )
    }

    private fun readEntityPeers(
        focus: ResourceRef,
        coneRefs: Set<ResourceRef>,
        models: List<SemanticModelView>,
    ): List<LineageEntityPeer>? {
        if (focus.kind != ResourceKind.SEMANTIC_MODEL) return emptyList()
        val focusedModel = models.singleOrNull { it.semanticModelId.value == focus.id } ?: return null
        val focusedEntities = focusedModel.definition.entities.map { it.name.value }.toSet()
        return models.asSequence()
            .filterNot { it.semanticModelId.value == focus.id }
            .mapNotNull { model ->
                val sharedEntities = model.definition.entities.map { it.name.value }
                    .filter { it in focusedEntities }
                    .distinct()
                    .sorted()
                if (sharedEntities.isEmpty()) return@mapNotNull null
                val ref = ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value)
                LineageEntityPeer(
                    resource = LineageResource(
                        ref = ref,
                        label = model.definition.label ?: model.definition.name.value,
                        address = model.definition.name.value,
                        description = model.definition.description,
                        connector = null,
                        missing = false,
                    ),
                    sharedEntities = sharedEntities,
                    alreadyInCone = ref in coneRefs,
                )
            }
            .sortedWith(compareBy({ it.resource.label }, { it.resource.ref.id.toString() }))
            .toList()
    }

    private suspend fun readRequestLocalResources(
        workspaceId: WorkspaceId,
        refs: Set<ResourceRef>,
        focus: ResourceRef,
    ): RequestLocalResources {
        val requiredKinds = refs.mapTo(linkedSetOf(), ResourceRef::kind)
        val connectors = if (ResourceKind.RELATION in requiredKinds) {
            connectorReadUseCase.listByWorkspace(workspaceId)
        } else {
            emptyList()
        }
        val catalogObjectSets = if (connectors.isNotEmpty()) {
            connectors.mapNotNull { connector ->
                catalogCurrentObjectsReadPort.readCurrent(workspaceId, connector.connectorId)
            }
        } else {
            emptyList()
        }
        val models = if (ResourceKind.SEMANTIC_MODEL in requiredKinds || focus.kind == ResourceKind.SEMANTIC_MODEL) {
            semanticCurrentReadUseCase.findModels(workspaceId)
        } else {
            emptyList()
        }
        val metrics = if (ResourceKind.METRIC in requiredKinds) {
            semanticCurrentReadUseCase.findMetrics(workspaceId)
        } else {
            emptyList()
        }
        return RequestLocalResources(connectors, catalogObjectSets, models, metrics)
    }
}

private data class RequestLocalResources(
    val connectors: List<CurrentConnectorRead>,
    val catalogObjectSets: List<CatalogCurrentObjectSet>,
    val models: List<SemanticModelView>,
    val metrics: List<MetricView>,
)

sealed interface LineageGraphQueryResult {
    data class InvalidLineage(val reason: cc.monomer.authority.api.CurrentResourceFocusConeDenial) : LineageGraphQueryResult
    data class Found(val graph: LineageGraph) : LineageGraphQueryResult
    data object Unavailable : LineageGraphQueryResult
}

sealed interface LineageResourceInventoryResult {
    data class Found(val page: LineageResourcePage) : LineageResourceInventoryResult
    data object InvalidCursor : LineageResourceInventoryResult
    data object Unavailable : LineageResourceInventoryResult
}

sealed interface LineageDefaultFocusResult {
    data class Found(val resource: LineageResource) : LineageDefaultFocusResult
    data object Empty : LineageDefaultFocusResult
    data object Unavailable : LineageDefaultFocusResult
}

data class LineageResourcePage(
    val items: List<LineageResource>,
    val nextCursor: LineageResourcePageCursor?,
)

sealed interface LineageResourcePageCursor {
    val kind: ResourceKind
    val normalizedQuery: String

    data class Relation(
        override val normalizedQuery: String,
        val ownerCursor: CatalogCurrentRelationPageCursor,
    ) : LineageResourcePageCursor {
        override val kind: ResourceKind = ResourceKind.RELATION
    }

    data class SemanticModel(
        override val normalizedQuery: String,
        val ownerCursor: SemanticModelPageCursor,
    ) : LineageResourcePageCursor {
        override val kind: ResourceKind = ResourceKind.SEMANTIC_MODEL
    }

    data class Metric(
        override val normalizedQuery: String,
        val ownerCursor: MetricPageCursor,
    ) : LineageResourcePageCursor {
        override val kind: ResourceKind = ResourceKind.METRIC
    }
}

data class LineageGraph(
    val workspaceId: WorkspaceId,
    val focus: LineageResource,
    val ancestors: List<LineageResource>,
    val descendants: List<LineageResource>,
    val siblings: List<LineageResource>,
    val edges: List<LineageEdge>,
    val siblingEdges: List<LineageEdge>,
    val entityPeers: List<LineageEntityPeer>,
    val detail: LineageResourceDetail,
    val missingDefinitions: List<LineageResource>,
    val dashboardUsages: List<LineageDashboardUsage>,
)

data class LineageDashboardUsage(
    val dashboardId: kotlin.uuid.Uuid,
    val title: String,
    val queryNames: List<String>,
    val metricIds: List<kotlin.uuid.Uuid>,
)

data class LineageResource(
    val ref: ResourceRef,
    val label: String,
    val address: String,
    val description: String?,
    val connector: LineageConnector?,
    val missing: Boolean,
) {
    init {
        require(label.isNotBlank())
        require(address.isNotBlank())
        require((ref.kind == ResourceKind.RELATION) == (connector != null)) {
            "connector metadata must exist exactly for Relation resources"
        }
    }
}

data class LineageConnector(
    val id: ConnectorId,
    val displayName: String,
    val type: ConnectorProductType,
) {
    init {
        require(displayName.isNotBlank())
    }
}

private fun CurrentConnectorRead.toLineageConnector(): LineageConnector = LineageConnector(
    id = connectorId,
    displayName = displayName,
    type = connectorType,
)

private fun CatalogCurrentRelationSummary.toLineageResource(
    connector: CurrentConnectorRead,
): LineageResource = LineageResource(
    ref = ResourceRef(ResourceKind.RELATION, id.value),
    label = qualifiedName,
    address = qualifiedName,
    description = null,
    connector = connector.toLineageConnector(),
    missing = false,
)

private fun SemanticModelView.toLineageResource(): LineageResource = LineageResource(
    ref = ResourceRef(ResourceKind.SEMANTIC_MODEL, semanticModelId.value),
    label = definition.label ?: definition.name.value,
    address = definition.name.value,
    description = definition.description,
    connector = null,
    missing = false,
)

private fun MetricView.toLineageResource(): LineageResource = LineageResource(
    ref = ResourceRef(ResourceKind.METRIC, metricId.value),
    label = definition.label ?: definition.name.value,
    address = definition.name.value,
    description = definition.description,
    connector = null,
    missing = false,
)

data class LineageEntityPeer(
    val resource: LineageResource,
    val sharedEntities: List<String>,
    val alreadyInCone: Boolean,
) {
    init {
        require(sharedEntities.isNotEmpty())
        require(sharedEntities == sharedEntities.distinct().sorted())
    }
}

private data class DefaultFocusCandidate(
    val resource: LineageResource,
    val updatedAt: java.time.Instant,
    val kindRank: Int,
)

private val defaultFocusComparator = compareBy<DefaultFocusCandidate>(
    DefaultFocusCandidate::updatedAt,
    DefaultFocusCandidate::kindRank,
    { it.resource.ref.id.toString() },
)

private const val MAXIMUM_LINEAGE_RESOURCE_PAGE_SIZE = 100
