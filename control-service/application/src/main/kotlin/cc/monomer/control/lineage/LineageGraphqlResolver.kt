package cc.monomer.control.lineage

import cc.monomer.authority.api.ResourceKind
import cc.monomer.authority.api.ResourceRef
import cc.monomer.catalog.api.CatalogCurrentRelationPageCursor
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.auth.inbound.graphql.InvalidCanonicalGraphqlInputException
import cc.monomer.control.auth.inbound.graphql.parseCanonicalUuid
import cc.monomer.control.auth.inbound.graphql.parseCanonicalWorkspaceId
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricPageCursor
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticModelPageCursor
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Controller

@Controller
class LineageGraphqlResolver(
    private val lineageGraphQueryService: LineageGraphQueryService,
) {
    @QueryMapping
    suspend fun lineageDefaultFocus(
        @Argument workspaceId: String,
    ): LineageResourceView? {
        val authentication = ReactiveSecurityContextHolder.getContext()
            .map { context -> checkNotNull(context.authentication) }
            .awaitSingle() as? JwtAuthentication
            ?: throw LineageResourceUnavailableException(null)
        return when (
            val result = lineageGraphQueryService.readDefaultFocus(
                workspaceId = parseCanonicalWorkspaceId(workspaceId),
                credential = authentication.presentedCredential,
                expectedOAuthResource = null,
            )
        ) {
            is LineageDefaultFocusResult.Found -> LineageResourceView.from(result.resource)
            LineageDefaultFocusResult.Empty -> null
            LineageDefaultFocusResult.Unavailable -> throw LineageResourceUnavailableException(null)
        }
    }

    @QueryMapping
    suspend fun lineageResources(
        @Argument workspaceId: String,
        @Argument kind: String,
        @Argument query: String?,
        @Argument cursor: String?,
        @Argument pageSize: Int,
    ): LineageResourcePageView {
        if (pageSize !in 1..MAXIMUM_LINEAGE_RESOURCE_PAGE_SIZE) {
            throw InvalidCanonicalGraphqlInputException(
                "pageSize must be between 1 and $MAXIMUM_LINEAGE_RESOURCE_PAGE_SIZE",
            )
        }
        val normalizedQuery = query?.trim().orEmpty()
        if (normalizedQuery.length > MAXIMUM_LINEAGE_RESOURCE_QUERY_LENGTH) {
            throw InvalidCanonicalGraphqlInputException(
                "query must be at most $MAXIMUM_LINEAGE_RESOURCE_QUERY_LENGTH characters",
            )
        }
        val resourceKind = parseResourceKind(kind)
        val decodedCursor = cursor?.let { raw -> decodeResourceCursor(raw, resourceKind, normalizedQuery.lowercase()) }
        val authentication = ReactiveSecurityContextHolder.getContext()
            .map { context -> checkNotNull(context.authentication) }
            .awaitSingle() as? JwtAuthentication
            ?: throw LineageResourceUnavailableException(null)
        return when (
            val result = lineageGraphQueryService.listResources(
                workspaceId = parseCanonicalWorkspaceId(workspaceId),
                kind = resourceKind,
                query = normalizedQuery,
                cursor = decodedCursor,
                pageSize = pageSize,
                credential = authentication.presentedCredential,
                expectedOAuthResource = null,
            )
        ) {
            is LineageResourceInventoryResult.Found -> LineageResourcePageView.from(result.page)
            LineageResourceInventoryResult.InvalidCursor -> throw InvalidCanonicalGraphqlInputException("cursor is invalid")
            LineageResourceInventoryResult.Unavailable -> throw LineageResourceUnavailableException(null)
        }
    }

    @QueryMapping
    suspend fun lineageFocusCone(
        @Argument workspaceId: String,
        @Argument focus: LineageResourceRefInput,
    ): LineageFocusConeView {
        val authentication = ReactiveSecurityContextHolder.getContext()
            .map { context -> checkNotNull(context.authentication) }
            .awaitSingle() as? JwtAuthentication
            ?: throw LineageResourceUnavailableException(null)
        return when (
            val result = lineageGraphQueryService.read(
                workspaceId = parseCanonicalWorkspaceId(workspaceId),
                focus = focus.toAuthorityResourceRef(),
                credential = authentication.presentedCredential,
                expectedOAuthResource = null,
            )
        ) {
            is LineageGraphQueryResult.Found -> LineageFocusConeView.from(result.graph)
            LineageGraphQueryResult.Unavailable -> throw LineageResourceUnavailableException(null)
            is LineageGraphQueryResult.InvalidLineage -> throw LineageResourceUnavailableException(result.reason)
        }
    }
}

data class LineageResourceRefInput(val kind: String, val id: String)

private fun LineageResourceRefInput.toAuthorityResourceRef(): ResourceRef {
    return ResourceRef(parseResourceKind(kind), parseCanonicalUuid(id, "resource id"))
}

private fun parseResourceKind(value: String): ResourceKind =
    runCatching { ResourceKind.valueOf(value) }.getOrElse {
        throw InvalidCanonicalGraphqlInputException(
            "resource kind must be RELATION, SEMANTIC_MODEL, or METRIC",
        )
    }

data class LineageResourcePageView(
    val items: List<LineageResourceView>,
    val nextCursor: String?,
) {
    companion object {
        fun from(page: LineageResourcePage): LineageResourcePageView = LineageResourcePageView(
            items = page.items.map(LineageResourceView::from),
            nextCursor = page.nextCursor?.let(::encodeResourceCursor),
        )
    }
}

data class LineageFocusConeView(
    val workspaceId: String,
    val focus: LineageResourceView,
    val ancestors: List<LineageResourceView>,
    val descendants: List<LineageResourceView>,
    val siblings: List<LineageResourceView>,
    val edges: List<LineageEdgeView>,
    val siblingEdges: List<LineageEdgeView>,
    val entityPeers: List<LineageEntityPeerView>,
    val detail: LineageResourceDetailView,
    val missingDefinitions: List<LineageResourceView>,
    val dashboardUsages: List<LineageDashboardUsageView>,
) {
    companion object {
        fun from(graph: LineageGraph): LineageFocusConeView = LineageFocusConeView(
            dashboardUsages = graph.dashboardUsages.map { LineageDashboardUsageView(it.dashboardId.toString(), it.title, it.queryNames, it.metricIds.map { id -> id.toString() }) },
            workspaceId = graph.workspaceId.toString(),
            focus = LineageResourceView.from(graph.focus),
            ancestors = graph.ancestors.map(LineageResourceView::from),
            descendants = graph.descendants.map(LineageResourceView::from),
            siblings = graph.siblings.map(LineageResourceView::from),
            edges = graph.edges.map(LineageEdgeView::from),
            siblingEdges = graph.siblingEdges.map(LineageEdgeView::from),
            entityPeers = graph.entityPeers.map(LineageEntityPeerView::from),
            detail = LineageResourceDetailView.from(graph.detail),
            missingDefinitions = graph.missingDefinitions.map(LineageResourceView::from),
        )
    }
}

data class LineageDashboardUsageView(val dashboardId: String, val title: String, val queryNames: List<String>, val metricIds: List<String>)

data class LineageResourceDetailView(
    val relation: LineageRelationDetailView?,
    val semanticModel: LineageSemanticModelDetailView?,
    val metric: LineageMetricDetailView?,
) {
    companion object {
        fun from(detail: LineageResourceDetail) = LineageResourceDetailView(
            relation = detail.relation?.let(LineageRelationDetailView::from),
            semanticModel = detail.semanticModel?.let(LineageSemanticModelDetailView::from),
            metric = detail.metric?.let(LineageMetricDetailView::from),
        )
    }
}

data class LineageRelationDetailView(
    val columns: List<LineageRelationColumnView>,
) {
    companion object {
        fun from(detail: LineageRelationDetail) = LineageRelationDetailView(
            columns = detail.columns.map(LineageRelationColumnView::from),
        )
    }
}

data class LineageRelationColumnView(
    val name: String,
    val nativeType: String,
    val nullable: Boolean,
    val hasDefault: Boolean,
    val primaryKey: Boolean,
    val foreignKey: Boolean,
) {
    companion object {
        fun from(column: LineageRelationColumn) = LineageRelationColumnView(
            name = column.name,
            nativeType = column.nativeType,
            nullable = column.nullable,
            hasDefault = column.hasDefault,
            primaryKey = column.primaryKey,
            foreignKey = column.foreignKey,
        )
    }
}

data class LineageSemanticModelDetailView(
    val entities: List<LineageSemanticEntityView>,
    val dimensions: List<LineageSemanticDimensionView>,
    val measures: List<LineageSemanticMeasureView>,
) {
    companion object {
        fun from(detail: LineageSemanticModelDetail) = LineageSemanticModelDetailView(
            entities = detail.entities.map(LineageSemanticEntityView::from),
            dimensions = detail.dimensions.map(LineageSemanticDimensionView::from),
            measures = detail.measures.map(LineageSemanticMeasureView::from),
        )
    }
}

data class LineageSemanticEntityView(
    val name: String,
    val type: String,
    val label: String?,
    val description: String?,
) {
    companion object {
        fun from(entity: LineageSemanticEntity) = LineageSemanticEntityView(
            name = entity.name,
            type = entity.type.name,
            label = entity.label,
            description = entity.description,
        )
    }
}

data class LineageSemanticDimensionView(
    val name: String,
    val type: String,
    val granularity: String?,
    val label: String?,
    val description: String?,
) {
    companion object {
        fun from(dimension: LineageSemanticDimension) = LineageSemanticDimensionView(
            name = dimension.name,
            type = dimension.type.name,
            granularity = dimension.granularity?.name,
            label = dimension.label,
            description = dimension.description,
        )
    }
}

data class LineageSemanticMeasureView(
    val name: String,
    val aggregation: String,
    val label: String?,
    val description: String?,
) {
    companion object {
        fun from(measure: LineageSemanticMeasure) = LineageSemanticMeasureView(
            name = measure.name,
            aggregation = measure.aggregation.name,
            label = measure.label,
            description = measure.description,
        )
    }
}

data class LineageMetricDetailView(
    val type: String,
    val inputs: List<LineageMetricInputView>,
    val timeGranularity: String?,
) {
    companion object {
        fun from(metric: LineageMetricDetail) = LineageMetricDetailView(
            type = metric.type.name,
            inputs = metric.inputs.map(LineageMetricInputView::from),
            timeGranularity = metric.timeGranularity?.name,
        )
    }
}

data class LineageMetricInputView(
    val kind: String,
    val name: String,
    val semanticModelName: String?,
) {
    companion object {
        fun from(input: LineageMetricInput) = LineageMetricInputView(
            kind = input.kind.name,
            name = input.name,
            semanticModelName = input.semanticModelName,
        )
    }
}

data class LineageResourceView(
    val resource: ResourceRefView,
    val label: String,
    val address: String,
    val description: String?,
    val connector: LineageConnectorView?,
    val missing: Boolean,
) {
    companion object {
        fun from(resource: LineageResource): LineageResourceView = LineageResourceView(
            resource = ResourceRefView(resource.ref.kind.name, resource.ref.id.toString()),
            label = resource.label,
            address = resource.address,
            description = resource.description,
            connector = resource.connector?.let(LineageConnectorView::from),
            missing = resource.missing,
        )
    }
}

data class LineageConnectorView(
    val id: String,
    val displayName: String,
    val type: String,
) {
    companion object {
        fun from(connector: LineageConnector): LineageConnectorView = LineageConnectorView(
            id = connector.id.toString(),
            displayName = connector.displayName,
            type = connector.type.name,
        )
    }
}

data class ResourceRefView(val kind: String, val id: String)

data class LineageEdgeView(val dependent: ResourceRefView, val parent: ResourceRefView) {
    companion object {
        fun from(edge: cc.monomer.authority.api.LineageEdge): LineageEdgeView = LineageEdgeView(
            dependent = ResourceRefView(edge.dependent.kind.name, edge.dependent.id.toString()),
            parent = ResourceRefView(edge.parent.kind.name, edge.parent.id.toString()),
        )
    }
}

data class LineageEntityPeerView(
    val resource: LineageResourceView,
    val sharedEntities: List<String>,
    val alreadyInCone: Boolean,
) {
    companion object {
        fun from(peer: LineageEntityPeer): LineageEntityPeerView = LineageEntityPeerView(
            resource = LineageResourceView.from(peer.resource),
            sharedEntities = peer.sharedEntities,
            alreadyInCone = peer.alreadyInCone,
        )
    }
}

private fun encodeResourceCursor(cursor: LineageResourcePageCursor): String {
    val fields = when (cursor) {
        is LineageResourcePageCursor.Relation -> listOf(
            CURSOR_VERSION,
            cursor.kind.name,
            encodeCursorField(cursor.normalizedQuery),
            encodeCursorField(cursor.ownerCursor.normalizedQualifiedName),
            "",
            cursor.ownerCursor.connectorId.toString(),
            cursor.ownerCursor.objectId.toString(),
        )
        is LineageResourcePageCursor.SemanticModel -> listOf(
            CURSOR_VERSION,
            cursor.kind.name,
            encodeCursorField(cursor.normalizedQuery),
            encodeCursorField(cursor.ownerCursor.normalizedLabel),
            encodeCursorField(cursor.ownerCursor.normalizedName),
            "",
            cursor.ownerCursor.semanticModelId.toString(),
        )
        is LineageResourcePageCursor.Metric -> listOf(
            CURSOR_VERSION,
            cursor.kind.name,
            encodeCursorField(cursor.normalizedQuery),
            encodeCursorField(cursor.ownerCursor.normalizedLabel),
            encodeCursorField(cursor.ownerCursor.normalizedName),
            "",
            cursor.ownerCursor.metricId.toString(),
        )
    }
    return fields.joinToString(".")
}

private fun decodeResourceCursor(
    raw: String,
    expectedKind: ResourceKind,
    normalizedQuery: String,
): LineageResourcePageCursor {
    if (raw.isBlank() || raw.length > MAXIMUM_LINEAGE_RESOURCE_CURSOR_LENGTH) {
        throw InvalidCanonicalGraphqlInputException("cursor is invalid")
    }
    return runCatching {
        val fields = raw.split('.', limit = 7)
        require(fields.size == 7 && fields[0] == CURSOR_VERSION)
        val kind = ResourceKind.valueOf(fields[1])
        require(kind == expectedKind)
        val cursorQuery = decodeCursorField(fields[2])
        require(cursorQuery == normalizedQuery)
        val firstSortKey = decodeCursorField(fields[3])
        val secondSortKey = decodeCursorField(fields[4])
        when (kind) {
            ResourceKind.RELATION -> LineageResourcePageCursor.Relation(
                normalizedQuery = cursorQuery,
                ownerCursor = CatalogCurrentRelationPageCursor(
                    normalizedQualifiedName = firstSortKey,
                    connectorId = ConnectorId(parseCanonicalUuid(fields[5], "cursor connector id")),
                    objectId = CatalogObjectId(parseCanonicalUuid(fields[6], "cursor object id")),
                ),
            )
            ResourceKind.SEMANTIC_MODEL -> LineageResourcePageCursor.SemanticModel(
                normalizedQuery = cursorQuery,
                ownerCursor = SemanticModelPageCursor(
                    normalizedLabel = firstSortKey,
                    normalizedName = secondSortKey,
                    semanticModelId = SemanticModelId(parseCanonicalUuid(fields[6], "cursor Semantic Model id")),
                ),
            )
            ResourceKind.METRIC -> LineageResourcePageCursor.Metric(
                normalizedQuery = cursorQuery,
                ownerCursor = MetricPageCursor(
                    normalizedLabel = firstSortKey,
                    normalizedName = secondSortKey,
                    metricId = MetricId(parseCanonicalUuid(fields[6], "cursor Metric id")),
                ),
            )
        }
    }.getOrElse {
        throw InvalidCanonicalGraphqlInputException("cursor is invalid")
    }
}

private fun encodeCursorField(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeCursorField(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

class LineageResourceUnavailableException(
    val reason: cc.monomer.authority.api.CurrentResourceFocusConeDenial?,
) : RuntimeException("Lineage resource is unavailable")

private const val CURSOR_VERSION = "v1"
private const val MAXIMUM_LINEAGE_RESOURCE_PAGE_SIZE = 100
private const val MAXIMUM_LINEAGE_RESOURCE_QUERY_LENGTH = 200
private const val MAXIMUM_LINEAGE_RESOURCE_CURSOR_LENGTH = 8_192
