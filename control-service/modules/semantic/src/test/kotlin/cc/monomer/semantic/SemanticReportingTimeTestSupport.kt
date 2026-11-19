package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspaceReportingTimeZoneQuery
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.application.SemanticMetricFlowModel
import cc.monomer.semantic.application.SemanticMetricFlowTimeDimension
import cc.monomer.semantic.application.SemanticTimeDimensionResolution
import cc.monomer.semantic.application.plainColumnIdentifier
import cc.monomer.semantic.application.resolveTimeDimension
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

internal val UTC_REPORTING_TIME_ZONE: ZoneId = ZoneId.of("UTC")

/** The Workspace Reporting time zone owner, answering one fixed zone and counting reads. */
internal class FixedReportingTimeZone(private val zone: ZoneId) : WorkspaceReportingTimeZoneQuery {
    val reads = AtomicInteger()

    override suspend fun reportingTimeZone(workspaceId: WorkspaceId): ZoneId {
        reads.incrementAndGet()
        return zone
    }
}

/** Time dimensions resolved as when no Catalog column type is known: a declaration, otherwise local. */
internal fun uncataloguedTimeDimensions(
    definition: SemanticModelDefinition,
    dialect: SemanticSqlDialect,
): Map<SemanticElementName, SemanticMetricFlowTimeDimension> =
    definition.dimensions.filter { it.type == SemanticDimensionKind.TIME }.associate { dimension ->
        val resolution = resolveTimeDimension(dimension, plainColumnIdentifier(dimension.expression, dialect), null, dialect)
        dimension.name to (resolution as SemanticTimeDimensionResolution.Resolved).timeDimension
    }

internal fun uncataloguedMetricFlowModel(
    definition: SemanticModelDefinition,
    address: CatalogPhysicalAddress,
    dialect: SemanticSqlDialect,
): SemanticMetricFlowModel = SemanticMetricFlowModel(definition, address, uncataloguedTimeDimensions(definition, dialect))

/**
 * The shared `analytics` Catalog fixture plus typed columns. Relations other than `orders` and `calendar`
 * are added under the same schema.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun catalogWithTypedColumns(
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    columnsByRelation: Map<String, List<Pair<String, String>>>,
): CatalogCurrentObjectSet {
    val base = catalogObjects(workspaceId, connectorId, Instant.parse("2026-10-12T00:00:00Z"))
    val schema = base.objects.single { it.kind == CatalogObjectKind.SCHEMA }
    val template = base.objects.first { it.kind == CatalogObjectKind.RELATION }
    var suffix = 100
    fun nextId() = (suffix++).let { value ->
        CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-${value.toString().padStart(12, '0')}")) to
            CatalogDigest("sha256:" + value.toString().padStart(64, '0'))
    }
    val added = mutableListOf<CatalogCurrentObject>()
    columnsByRelation.forEach { (relationName, columns) ->
        val relation = base.objects.singleOrNull { it.kind == CatalogObjectKind.RELATION && it.address.objectName == relationName }
            ?: nextId().let { (id, digest) ->
                template.copy(
                    id = id, identityDigest = digest, contentDigest = digest, parentId = schema.id,
                    address = template.address.copy(objectName = relationName), qualifiedName = "analytics.$relationName",
                    definition = "create table $relationName",
                ).also(added::add)
            }
        columns.forEachIndexed { index, (name, type) ->
            val (id, digest) = nextId()
            added += relation.copy(
                id = id, identityDigest = digest, contentDigest = digest, parentId = relation.id,
                kind = CatalogObjectKind.COLUMN, relationKind = null,
                address = relation.address.copy(memberName = name), qualifiedName = "${relation.qualifiedName}.$name",
                nativeType = type, definition = null, ordinal = index + 1, nullable = true,
            )
        }
    }
    return base.copy(objects = (base.objects + added).sortedBy { it.identityDigest.value })
}

internal class FixedCatalogRead(private val catalog: CatalogCurrentObjectSet?) : CatalogCurrentObjectsReadPort {
    override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet? =
        catalog?.takeIf { it.workspaceId == workspaceId && it.connectorId == connectorId }

    override fun searchCurrentRelations(
        workspaceId: WorkspaceId,
        query: String,
        cursor: cc.monomer.catalog.api.CatalogCurrentRelationPageCursor?,
        pageSize: Int,
    ): cc.monomer.catalog.api.CatalogCurrentRelationPage = error("unused")
}
