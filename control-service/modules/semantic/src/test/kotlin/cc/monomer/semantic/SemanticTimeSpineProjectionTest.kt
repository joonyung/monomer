@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeSpine
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import cc.monomer.semantic.api.SemanticTimeSpinePrimaryColumn
import cc.monomer.semantic.api.SemanticTimeSpineRelation
import cc.monomer.semantic.application.SemanticMetricFlowTimeSpine
import cc.monomer.semantic.application.projectCurrentTimeSpines
import cc.monomer.semantic.application.resolveQuery
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class SemanticTimeSpineProjectionTest {
    @Test
    fun `projects resolved spines for the selected workspace connector and omits dangling relations`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val catalog = catalog(workspaceId, connectorId, "time_spine_day", "time_spine_hour")
        val configuration = CurrentTimeSpineConfiguration(
            workspaceId = workspaceId,
            connectorId = connectorId,
            definition = SemanticTimeSpineDefinition(
                schemaVersion = 1u,
                timeSpines = listOf(
                    timeSpine(null, "time_spine_day", "date_utc", SemanticTimeGranularity.DAY),
                    timeSpine("warehouse", "time_spine_hour", "timestamp_utc", SemanticTimeGranularity.HOUR),
                    timeSpine(null, "missing_time_spine", "date_utc", SemanticTimeGranularity.MONTH),
                ),
            ),
        )

        assertEquals(
            listOf(
                SemanticMetricFlowTimeSpine(
                    physicalAddress = CatalogPhysicalAddress("warehouse", "analytics", "time_spine_day", null),
                    primaryColumnName = "date_utc",
                    granularity = SemanticTimeGranularity.DAY,
                ),
                SemanticMetricFlowTimeSpine(
                    physicalAddress = CatalogPhysicalAddress("warehouse", "analytics", "time_spine_hour", null),
                    primaryColumnName = "timestamp_utc",
                    granularity = SemanticTimeGranularity.HOUR,
                ),
            ),
            projectCurrentTimeSpines(
                timeSpineConfigurations = listOf(configuration),
                workspaceId = workspaceId,
                connectorId = connectorId,
                catalog = catalog,
            ),
        )
    }

    @Test
    fun `does not project a configuration belonging to another workspace or connector`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val catalog = catalog(workspaceId, connectorId, "time_spine_day")
        val configuration = CurrentTimeSpineConfiguration(
            workspaceId = WorkspaceId.generate(),
            connectorId = ConnectorId.generate(),
            definition = SemanticTimeSpineDefinition(
                schemaVersion = 1u,
                timeSpines = listOf(timeSpine(null, "time_spine_day", "date_utc", SemanticTimeGranularity.DAY)),
            ),
        )

        assertEquals(
            emptyList(),
            projectCurrentTimeSpines(
                timeSpineConfigurations = listOf(configuration),
                workspaceId = workspaceId,
                connectorId = connectorId,
                catalog = catalog,
            ),
        )
    }

    @Test
    fun `two depth spine projects null catalog and rejects database qualified names`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val threeDepth = catalog(workspaceId, connectorId, "time_spine_day")
        val canonical = threeDepth.copy(
            objects = threeDepth.objects.filter { it.kind != CatalogObjectKind.CATALOG }.map { current ->
                current.copy(
                    parentId = current.parentId.takeUnless { current.kind == CatalogObjectKind.SCHEMA },
                    address = current.address.copy(catalogName = null),
                    qualifiedName = current.qualifiedName.removePrefix("warehouse."),
                )
            },
        )
        listOf(null, "warehouse", "foreign_database").forEach { database ->
            val configuration = CurrentTimeSpineConfiguration(
                workspaceId, connectorId,
                SemanticTimeSpineDefinition(
                    schemaVersion = 1u,
                    timeSpines = listOf(timeSpine(database, "time_spine_day", "date_utc", SemanticTimeGranularity.DAY)),
                ),
            )
            assertEquals(
                if (database == null) listOf(
                    SemanticMetricFlowTimeSpine(
                        CatalogPhysicalAddress(null, "analytics", "time_spine_day", null),
                        "date_utc", SemanticTimeGranularity.DAY,
                    ),
                ) else emptyList(),
                projectCurrentTimeSpines(listOf(configuration), workspaceId, connectorId, canonical),
            )
        }
    }

    @Test
    fun `render closure partition check includes candidate models and every configured time spine`() {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val orders = catalog(workspace, connector, "orders", "customers", "time_spine_day").let { set ->
            set.copy(objects = set.objects.map { relation ->
                if (relation.kind == CatalogObjectKind.RELATION && relation.address.objectName != "orders")
                    relation.copy(partitionKeys = listOf("snapshot_day"), requiresPartitionFilter = true) else relation
            })
        }
        fun model(name: String) = cc.monomer.semantic.domain.CurrentSemanticModel(
            cc.monomer.semantic.api.SemanticModelId.generate(), workspace,
            cc.monomer.semantic.api.SemanticModelDefinition(
                3u, cc.monomer.semantic.api.SemanticElementName(name), null, null,
                listOf(cc.monomer.semantic.api.SemanticSourceRelation(connector, null, "analytics", name)), null,
                listOf(cc.monomer.semantic.api.SemanticEntityDefinition(cc.monomer.semantic.api.SemanticElementName("id"), cc.monomer.semantic.api.SemanticEntityKind.PRIMARY, "id", null, null)),
                emptyList(), emptyList(),
            ), OBSERVED_AT, OBSERVED_AT,
        )
        val selected = model("orders")
        val candidate = model("customers")
        val closure = cc.monomer.semantic.application.SemanticMetricClosure.Collected(emptyList(), emptyMap(), mapOf(selected.definition.name to selected))
        val configured = CurrentTimeSpineConfiguration(workspace, connector, SemanticTimeSpineDefinition(1u,
            listOf(timeSpine(null, "time_spine_day", "date_utc", SemanticTimeGranularity.DAY))))
        val projection = kotlin.test.assertIs<cc.monomer.semantic.application.SemanticClosureProjection.Projected>(
            cc.monomer.semantic.application.projectMetricClosure(workspace, connector, closure, orders,
                cc.monomer.semantic.api.SemanticSqlDialect.BIGQUERY, listOf(configured), mapOf(selected.definition.name to selected, candidate.definition.name to candidate),
                UTC_REPORTING_TIME_ZONE),
        )
        assertEquals(listOf("customers", "time_spine_day"), projection.partitionFilters.map { it.relation.name })
        assertEquals(listOf(cc.monomer.semantic.api.SemanticPartitionFilterKind.SEMANTIC_MODEL, cc.monomer.semantic.api.SemanticPartitionFilterKind.TIME_SPINE), projection.partitionFilters.map { it.kind })
        val accepted = object : cc.monomer.semantic.application.SemanticMetricFlowPort {
            override fun validate(manifest: cc.monomer.semantic.application.SemanticMetricFlowManifest) = cc.monomer.semantic.application.SemanticMetricFlowValidationResult.Valid
            override fun render(manifest: cc.monomer.semantic.application.SemanticMetricFlowManifest, querySpec: cc.monomer.semantic.api.SemanticQuerySpec, metricIds: List<cc.monomer.semantic.api.MetricId>) = error("unused")
            override fun listGroupBys(manifest: cc.monomer.semantic.application.SemanticMetricFlowManifest, metricIds: List<cc.monomer.semantic.api.MetricId>) = error("unused")
        }
        val resolution = kotlin.test.assertIs<cc.monomer.semantic.application.SemanticQueryResolution.Resolved>(
            accepted.resolveQuery(projection),
        )
        assertEquals(true, resolution.partitionCheck.validate)
        assertEquals(projection.partitionFilters, resolution.partitionCheck.partitionFilters)
    }

    private fun timeSpine(
        catalog: String?,
        relation: String,
        column: String,
        granularity: SemanticTimeGranularity,
    ): SemanticTimeSpine = SemanticTimeSpine(
        relation = SemanticTimeSpineRelation(catalog, "analytics", relation),
        primaryColumn = SemanticTimeSpinePrimaryColumn(column, granularity),
    )

    private fun catalog(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        vararg relationNames: String,
    ): CatalogCurrentObjectSet {
        val catalogId = objectId(1)
        val schemaId = objectId(2)
        val objects = buildList {
            add(
                CatalogCurrentObject(
                    id = catalogId,
                    identityDigest = digest(1),
                    parentId = null,
                    kind = CatalogObjectKind.CATALOG,
                    relationKind = null,
                    address = CatalogPhysicalAddress("warehouse", null, null, null),
                    qualifiedName = "warehouse",
                    nativeType = null,
                    contentDigest = digest(1),
                    definition = null,
                    ordinal = null,
                    nullable = null,
                    defaultExpression = null,
                    relationshipKind = null,
                    memberOrdinal = null,
                    constraintName = null,
                    sourceColumn = null,
                    referencedCatalog = null,
                    referencedSchema = null,
                    referencedRelation = null,
                    referencedColumn = null,
                    observedAt = OBSERVED_AT,
                    partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
                ),
            )
            add(
                CatalogCurrentObject(
                    id = schemaId,
                    identityDigest = digest(2),
                    parentId = catalogId,
                    kind = CatalogObjectKind.SCHEMA,
                    relationKind = null,
                    address = CatalogPhysicalAddress("warehouse", "analytics", null, null),
                    qualifiedName = "warehouse.analytics",
                    nativeType = null,
                    contentDigest = digest(2),
                    definition = null,
                    ordinal = null,
                    nullable = null,
                    defaultExpression = null,
                    relationshipKind = null,
                    memberOrdinal = null,
                    constraintName = null,
                    sourceColumn = null,
                    referencedCatalog = null,
                    referencedSchema = null,
                    referencedRelation = null,
                    referencedColumn = null,
                    observedAt = OBSERVED_AT,
                    partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
                ),
            )
            relationNames.forEachIndexed { index, relationName ->
                val suffix = index + 3
                add(
                    CatalogCurrentObject(
                        id = objectId(suffix),
                        identityDigest = digest(suffix),
                        parentId = schemaId,
                        kind = CatalogObjectKind.RELATION,
                        relationKind = RelationKind.TABLE,
                        address = CatalogPhysicalAddress("warehouse", "analytics", relationName, null),
                        qualifiedName = "warehouse.analytics.$relationName",
                        nativeType = null,
                        contentDigest = digest(suffix),
                        definition = "create table $relationName",
                        ordinal = null,
                        nullable = null,
                        defaultExpression = null,
                        relationshipKind = null,
                        memberOrdinal = null,
                        constraintName = null,
                        sourceColumn = null,
                        referencedCatalog = null,
                        referencedSchema = null,
                        referencedRelation = null,
                        referencedColumn = null,
                        observedAt = OBSERVED_AT,
                        partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
                    ),
                )
            }
        }
        return CatalogCurrentObjectSet(workspaceId, connectorId, objects)
    }

    private fun objectId(suffix: Int): CatalogObjectId = CatalogObjectId(
        Uuid.parse("0197d83f-2c9e-8a3b-8c1d-${suffix.toString().padStart(12, '0')}"),
    )

    private fun digest(suffix: Int): CatalogDigest = CatalogDigest(
        "sha256:" + suffix.toString().padStart(64, '0'),
    )

    private companion object {
        val OBSERVED_AT: Instant = Instant.parse("2026-08-19T00:00:00Z")
    }
}
