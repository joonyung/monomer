@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.catalog.api.RelationKind
import cc.monomer.semantic.application.resolveCatalogRelation
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.matchesCurrentAddress
import cc.monomer.connector.api.ConnectorId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SemanticCatalogResolutionTest {
    @Test
    fun `omitted catalog resolves one exact schema and relation`() {
        val relation = relation("warehouse", "analytics", "orders", 1)

        assertEquals(
            relation,
            resolveCatalogRelation(listOf(relation), null, "analytics", "orders"),
        )
    }

    @Test
    fun `omitted catalog rejects zero and ambiguous matches`() {
        val first = relation("warehouse_a", "analytics", "orders", 2)
        val second = relation("warehouse_b", "analytics", "orders", 3)

        assertNull(resolveCatalogRelation(emptyList(), null, "analytics", "orders"))
        assertNull(resolveCatalogRelation(listOf(first, second), null, "analytics", "orders"))
        assertNull(resolveCatalogRelation(listOf(first), "warehouse_b", "analytics", "orders"))
    }

    @Test
    fun `two depth source and spine resolve canonical null and reject database qualified definitions`() {
        val relation = relation(null, "analytics", "orders", 4)
        val connector = ConnectorId.generate()
        assertEquals(relation, resolveCatalogRelation(listOf(relation), null, "analytics", "orders"))
        assertEquals(true, SemanticSourceRelation(connector, null, "analytics", "orders").matchesCurrentAddress(relation.address))
        listOf("warehouse", "foreign_database").forEach { database ->
            assertNull(resolveCatalogRelation(listOf(relation), database, "analytics", "orders"))
            assertEquals(false, SemanticSourceRelation(connector, database, "analytics", "orders").matchesCurrentAddress(relation.address))
        }
    }

    private fun relation(catalog: String?, schema: String, name: String, suffix: Int): CatalogCurrentObject {
        val objectId = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-${suffix.toString().padStart(12, '0')}"))
        val parentId = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-${(suffix + 10).toString().padStart(12, '0')}"))
        val digest = CatalogDigest("sha256:" + suffix.toString().repeat(64).take(64))
        return CatalogCurrentObject(
            id = objectId,
            identityDigest = digest,
            parentId = parentId,
            kind = CatalogObjectKind.RELATION,
            relationKind = RelationKind.TABLE,
            address = CatalogPhysicalAddress(catalog, schema, name, null),
            qualifiedName = "$catalog.$schema.$name",
            nativeType = null,
            contentDigest = digest,
            definition = "create table $name",
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
            observedAt = Instant.parse("2026-08-19T00:00:00Z"),
            partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
        )
    }
}
