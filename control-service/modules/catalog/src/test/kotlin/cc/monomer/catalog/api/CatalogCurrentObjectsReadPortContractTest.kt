@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class CatalogCurrentObjectsReadPortContractTest {
    @Test
    fun `current objects accept deterministic IETF UUID version eight identities`() {
        val objectId = CatalogObjectId(Uuid.parse("00000000-0000-8000-8000-000000000001"))

        assertEquals("00000000-0000-8000-8000-000000000001", objectId.toString())
    }

    @Test
    fun `current object set requires canonical object order and complete structural topology`() {
        val schema = currentObject(
            id = objectId(1),
            identityDigest = digest('a'),
            parentId = null,
            kind = CatalogObjectKind.SCHEMA,
            address = CatalogPhysicalAddress("warehouse", "analytics", null, null),
            nativeType = null,
        )
        val relation = currentObject(
            id = objectId(2),
            identityDigest = digest('b'),
            parentId = schema.id,
            kind = CatalogObjectKind.RELATION,
            address = CatalogPhysicalAddress("warehouse", "analytics", "orders", null),
            nativeType = null,
        )
        val column = currentObject(
            id = objectId(3),
            identityDigest = digest('c'),
            parentId = relation.id,
            kind = CatalogObjectKind.COLUMN,
            address = CatalogPhysicalAddress("warehouse", "analytics", "orders", "created_at"),
            nativeType = "TIMESTAMP",
        )

        val set = currentObjectSet(listOf(schema, relation, column))

        assertEquals(listOf(schema, relation, column), set.objects)
        assertFailsWith<IllegalArgumentException> {
            currentObjectSet(listOf(relation, schema, column))
        }
        assertFailsWith<IllegalArgumentException> {
            currentObjectSet(listOf(relation))
        }
    }

    @Test
    fun `current object set preserves stable object identity and content`() {
        val schema = currentObject(
            id = objectId(1),
            identityDigest = digest('a'),
            parentId = null,
            kind = CatalogObjectKind.SCHEMA,
            address = CatalogPhysicalAddress("warehouse", "analytics", null, null),
            nativeType = null,
        )
        val otherRevision = schema.copy(
            id = objectId(2),
            identityDigest = digest('b'),
        )

        val set = CatalogCurrentObjectSet(
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorId = ConnectorId(Uuid.generateV7()),
            objects = listOf(schema, otherRevision),
        )

        assertEquals(listOf(schema.id, otherRevision.id), set.objects.map { it.id })
    }

    @Test
    fun `current object address and native type are closed by object kind`() {
        assertFailsWith<IllegalArgumentException> {
            currentObject(
                id = objectId(1),
                identityDigest = digest('a'),
                parentId = null,
                kind = CatalogObjectKind.SCHEMA,
                address = CatalogPhysicalAddress("warehouse", "analytics", "orders", null),
                nativeType = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            currentObject(
                id = objectId(2),
                identityDigest = digest('b'),
                parentId = objectId(1),
                kind = CatalogObjectKind.COLUMN,
                address = CatalogPhysicalAddress("warehouse", "analytics", "orders", "created_at"),
                nativeType = null,
            )
        }
    }

    @Test
    fun `current objects retain relation column and relationship metadata`() {
        val schema = currentObject(
            id = objectId(1),
            identityDigest = digest('a'),
            parentId = null,
            kind = CatalogObjectKind.SCHEMA,
            address = CatalogPhysicalAddress("warehouse", "analytics", null, null),
            nativeType = null,
        )
        val relation = currentObject(
            id = objectId(2),
            identityDigest = digest('b'),
            parentId = schema.id,
            kind = CatalogObjectKind.RELATION,
            address = CatalogPhysicalAddress("warehouse", "analytics", "orders", null),
            nativeType = null,
            definition = "SELECT * FROM raw_orders",
        )
        val column = currentObject(
            id = objectId(3),
            identityDigest = digest('c'),
            parentId = relation.id,
            kind = CatalogObjectKind.COLUMN,
            address = CatalogPhysicalAddress("warehouse", "analytics", "orders", "id"),
            nativeType = "INTEGER",
            ordinal = 2,
            nullable = false,
            defaultExpression = "nextval('orders_id_seq'::regclass)",
        )
        val primaryKey = currentObject(
            id = objectId(4),
            identityDigest = digest('d'),
            parentId = relation.id,
            kind = CatalogObjectKind.RELATIONSHIP,
            address = CatalogPhysicalAddress("warehouse", "analytics", "orders", "orders_pkey:1"),
            nativeType = null,
            relationshipKind = CatalogRelationshipKind.PRIMARY_KEY,
            memberOrdinal = 1,
            constraintName = "orders_pkey",
            sourceColumn = "id",
        )
        val foreignKey = currentObject(
            id = objectId(5),
            identityDigest = digest('e'),
            parentId = relation.id,
            kind = CatalogObjectKind.RELATIONSHIP,
            address = CatalogPhysicalAddress("warehouse", "analytics", "orders", "orders_customer_fkey:1"),
            nativeType = null,
            relationshipKind = CatalogRelationshipKind.FOREIGN_KEY,
            memberOrdinal = 1,
            constraintName = "orders_customer_fkey",
            sourceColumn = "customer_id",
            referencedCatalog = "warehouse",
            referencedSchema = "analytics",
            referencedRelation = "customers",
            referencedColumn = "id",
        )

        val objects = currentObjectSet(listOf(schema, relation, column, primaryKey, foreignKey)).objects

        assertEquals("SELECT * FROM raw_orders", objects.single { it.kind == CatalogObjectKind.RELATION }.definition)
        assertEquals(
            Triple(2, false, "nextval('orders_id_seq'::regclass)"),
            objects.single { it.kind == CatalogObjectKind.COLUMN }
                .let { Triple(it.ordinal, it.nullable, it.defaultExpression) },
        )
        assertEquals(
            CatalogRelationshipKind.PRIMARY_KEY,
            objects.single { it.constraintName == "orders_pkey" }.relationshipKind,
        )
        val readForeignKey = objects.single { it.constraintName == "orders_customer_fkey" }
        assertEquals("customer_id", readForeignKey.sourceColumn)
        assertEquals(Triple("analytics", "customers", "id"), Triple(readForeignKey.referencedSchema, readForeignKey.referencedRelation, readForeignKey.referencedColumn))
    }

    @Test
    fun `two-depth current topology does not need a placeholder catalog`() {
        val schema = currentObject(
            id = objectId(1),
            identityDigest = digest('a'),
            parentId = null,
            kind = CatalogObjectKind.SCHEMA,
            address = CatalogPhysicalAddress(null, "analytics", null, null),
            nativeType = null,
        )
        val relation = currentObject(
            id = objectId(2),
            identityDigest = digest('b'),
            parentId = schema.id,
            kind = CatalogObjectKind.RELATION,
            address = CatalogPhysicalAddress(null, "analytics", "orders", null),
            nativeType = null,
        )

        assertEquals(listOf(schema, relation), currentObjectSet(listOf(schema, relation)).objects)
    }

    private fun currentObjectSet(objects: List<CatalogCurrentObject>): CatalogCurrentObjectSet =
        CatalogCurrentObjectSet(
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorId = ConnectorId(Uuid.generateV7()),
            objects = objects,
        )

    private fun currentObject(
        id: CatalogObjectId,
        identityDigest: CatalogDigest,
        parentId: CatalogObjectId?,
        kind: CatalogObjectKind,
        address: CatalogPhysicalAddress,
        nativeType: String?,
        definition: String? = null,
        ordinal: Int? = kind.takeIf { it == CatalogObjectKind.COLUMN }?.let { 1 },
        nullable: Boolean? = kind.takeIf { it == CatalogObjectKind.COLUMN }?.let { false },
        defaultExpression: String? = null,
        relationshipKind: CatalogRelationshipKind? = null,
        memberOrdinal: Int? = null,
        constraintName: String? = null,
        sourceColumn: String? = null,
        referencedCatalog: String? = null,
        referencedSchema: String? = null,
        referencedRelation: String? = null,
        referencedColumn: String? = null,
    ): CatalogCurrentObject =
        CatalogCurrentObject(
            id = id,
            identityDigest = identityDigest,
            parentId = parentId,
            kind = kind,
            relationKind = kind.takeIf { it == CatalogObjectKind.RELATION }?.let { RelationKind.TABLE },
            address = address,
            qualifiedName = "audit.${identityDigest.value}",
            nativeType = nativeType,
            contentDigest = digest('e'),
            definition = definition,
            ordinal = ordinal,
            nullable = nullable,
            defaultExpression = defaultExpression,
            relationshipKind = relationshipKind,
            memberOrdinal = memberOrdinal,
            constraintName = constraintName,
            sourceColumn = sourceColumn,
            referencedCatalog = referencedCatalog,
            referencedSchema = referencedSchema,
            referencedRelation = referencedRelation,
            referencedColumn = referencedColumn,
            observedAt = Instant.EPOCH,
            partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
        )

    private fun objectId(value: Int): CatalogObjectId =
        CatalogObjectId(
            Uuid.parse("00000000-0000-8000-8000-${value.toString().padStart(12, '0')}"),
        )

    private fun digest(character: Char): CatalogDigest =
        CatalogDigest("sha256:" + character.toString().repeat(64))
}
