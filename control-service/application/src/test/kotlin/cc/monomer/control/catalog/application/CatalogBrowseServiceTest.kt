@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.application

import cc.monomer.catalog.application.CatalogBrowseService
import cc.monomer.catalog.application.SchemaSummary
import cc.monomer.catalog.application.RelationSummary
import cc.monomer.catalog.application.CatalogNotFoundException
import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.application.CatalogBrowseCatalog
import cc.monomer.catalog.application.CatalogBrowseReadPort
import cc.monomer.catalog.application.CatalogBrowseRelation
import cc.monomer.catalog.application.CatalogBrowseSchema
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class CatalogBrowseServiceTest {
    private val workspaceId = WorkspaceId(Uuid.generateV7())
    private val connectorId = ConnectorId.generate()
    private val observedAt = Instant.parse("2026-08-19T02:00:00Z")
    private val schemaId = objectId(1)
    private val relationId = objectId(2)
    private val current = CatalogCurrentObjectSet(
        workspaceId = workspaceId,
        connectorId = connectorId,
        objects = listOf(
            currentObject(
                id = schemaId,
                parentId = null,
                kind = CatalogObjectKind.SCHEMA,
                relationKind = null,
                address = CatalogPhysicalAddress("warehouse", "analytics", null, null),
                digestCharacter = 'a',
            ),
            currentObject(
                id = relationId,
                parentId = schemaId,
                kind = CatalogObjectKind.RELATION,
                relationKind = RelationKind.TABLE,
                address = CatalogPhysicalAddress("warehouse", "analytics", "orders", null),
                digestCharacter = 'b',
            ),
            currentObject(
                id = objectId(3),
                parentId = objectId(5),
                kind = CatalogObjectKind.RELATION,
                relationKind = RelationKind.VIEW,
                address = CatalogPhysicalAddress("lake", "analytics", "zeta", null),
                digestCharacter = 'c',
            ),
            currentObject(
                id = objectId(5),
                parentId = null,
                kind = CatalogObjectKind.SCHEMA,
                relationKind = null,
                address = CatalogPhysicalAddress("lake", "analytics", null, null),
                digestCharacter = 'd',
            ),
            currentObject(
                id = objectId(4),
                parentId = objectId(5),
                kind = CatalogObjectKind.RELATION,
                relationKind = RelationKind.TABLE,
                address = CatalogPhysicalAddress("lake", "analytics", "alpha", null),
                digestCharacter = 'e',
            ),
        ),
    )
    private val service = CatalogBrowseService(
        browseReadPort = InMemoryCatalogBrowseReadPort(current),
        selectedSchemaNames = { _, _, entry ->
            if (entry == cc.monomer.catalog.api.CatalogIngestEntry.Catalog(connectorId, "warehouse")) setOf("analytics", "billing")
            else emptySet()
        },
        transactionPort = object : CatalogTransactionPort {
            override suspend fun <T> inTransaction(block: () -> T): T = error("browse only reads")
            override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = block()
        },
    )

    @Test
    fun `three-depth browse uses an exact catalog and its observed time`() = runTest {
        val schemas = service.listSchemas(workspaceId.toString(), connectorId.toString(), "warehouse", null, null, null)
        val relations = service.listRelations(
            workspaceId.toString(),
            connectorId.toString(),
            "warehouse",
            "analytics",
            null,
            null,
            null,
        )

        assertEquals(listOf("analytics", "billing"), schemas.schemas.map(SchemaSummary::name))
        assertEquals(listOf(observedAt.toEpochMilli(), null), schemas.schemas.map(SchemaSummary::lastRefreshedAtEpochMs))
        assertEquals(listOf("orders"), relations.relations.map(RelationSummary::name))
        assertEquals(observedAt.toEpochMilli(), relations.relations.single().lastRefreshedAtEpochMs)
    }

    @Test
    fun `selected schemas that were never refreshed are listed without a refresh time and follow the query`() = runTest {
        val matching = service.listSchemas(workspaceId.toString(), connectorId.toString(), "warehouse", null, null, "bill")
        val otherCatalog = service.listSchemas(workspaceId.toString(), connectorId.toString(), "raw", null, null, null)

        assertEquals(listOf("billing"), matching.schemas.map(SchemaSummary::name))
        assertEquals(1, matching.totalCount)
        assertEquals(null, matching.schemas.single().lastRefreshedAtEpochMs)
        assertEquals("warehouse", matching.schemas.single().catalogName)
        assertEquals(emptyList(), otherCatalog.schemas)
    }

    @Test
    fun `human relation pages sort by name like the schema and keyset lists`() = runTest {
        val allCatalogs = service.listRelations(workspaceId.toString(), connectorId.toString(), null, "analytics", null, null, null)
        val lake = service.listRelations(workspaceId.toString(), connectorId.toString(), "lake", "analytics", 1, 1, null)

        assertEquals(listOf("alpha", "orders", "zeta"), allCatalogs.relations.map(RelationSummary::name))
        assertEquals(listOf("alpha"), lake.relations.map(RelationSummary::name))
        assertEquals(2, lake.totalCount)
    }

    @Test
    fun `a page beyond the end is empty even when the offset exceeds Int range`() = runTest {
        val beyond = service.listRelations(
            workspaceId.toString(), connectorId.toString(), null, "analytics", 3, Int.MAX_VALUE, null,
        )

        assertEquals(emptyList(), beyond.relations)
        assertEquals(3, beyond.totalCount)
    }

    @Test
    fun `null catalog cannot resolve three-depth rows through exact reads`() = runTest {
        assertEquals(emptyList(), service.listSchemasAfter(workspaceId.toString(), connectorId.toString(), null, null, 10))
        assertEquals(emptyList(), service.listRelationsAfter(workspaceId.toString(), connectorId.toString(), null, "analytics", null, 10))
        kotlin.test.assertFailsWith<CatalogNotFoundException> {
            service.getRelation(workspaceId.toString(), connectorId.toString(), null, "analytics", "orders")
        }
    }

    private fun currentObject(
        id: CatalogObjectId,
        parentId: CatalogObjectId?,
        kind: CatalogObjectKind,
        relationKind: RelationKind?,
        address: CatalogPhysicalAddress,
        digestCharacter: Char,
    ) = CatalogCurrentObject(
        id = id,
        identityDigest = CatalogDigest("sha256:${digestCharacter.toString().repeat(64)}"),
        parentId = parentId,
        kind = kind,
        relationKind = relationKind,
        address = address,
        qualifiedName = listOfNotNull(address.catalogName, address.schemaName, address.objectName).joinToString("."),
        nativeType = null,
        contentDigest = CatalogDigest("sha256:${"f".repeat(64)}"),
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
        observedAt = observedAt,
        partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
    )

    private fun objectId(value: Int) = CatalogObjectId(
        Uuid.parse("00000000-0000-8000-8000-${value.toString().padStart(12, '0')}"),
    )
}

private class InMemoryCatalogBrowseReadPort(private val current: CatalogCurrentObjectSet) : CatalogBrowseReadPort {
    override fun readCatalogs(workspaceId: WorkspaceId, connectorId: ConnectorId) =
        current.objects.filter { it.kind == CatalogObjectKind.CATALOG }
            .map { CatalogBrowseCatalog(requireNotNull(it.address.catalogName), it.observedAt) }

    override fun readSchemas(workspaceId: WorkspaceId, connectorId: ConnectorId, catalogName: String?) =
        current.objects.filter {
            it.kind == CatalogObjectKind.SCHEMA && (catalogName == null || it.address.catalogName == catalogName)
        }.map { CatalogBrowseSchema(it.address.catalogName, requireNotNull(it.address.schemaName), it.observedAt) }

    override fun readRelations(workspaceId: WorkspaceId, connectorId: ConnectorId, catalogName: String?, schemaName: String) =
        current.objects.filter {
            it.kind == CatalogObjectKind.RELATION &&
                (catalogName == null || it.address.catalogName == catalogName) &&
                it.address.schemaName == schemaName
        }.map {
            CatalogBrowseRelation(
                it.id, it.address.catalogName, schemaName, requireNotNull(it.address.objectName),
                requireNotNull(it.relationKind), it.observedAt,
            )
        }

    override fun countColumns(workspaceId: WorkspaceId, connectorId: ConnectorId, relations: List<CatalogBrowseRelation>) =
        relations.associate { relation ->
            relation.id to current.objects.count { it.kind == CatalogObjectKind.COLUMN && it.parentId == relation.id }
        }

    override fun readRelationSubtree(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String?,
        schemaName: String,
        relationName: String,
    ) = current.objects.filter {
        it.kind != CatalogObjectKind.CATALOG && it.kind != CatalogObjectKind.SCHEMA &&
            it.address.catalogName == catalogName &&
            it.address.schemaName == schemaName &&
            it.address.objectName == relationName
    }
}
