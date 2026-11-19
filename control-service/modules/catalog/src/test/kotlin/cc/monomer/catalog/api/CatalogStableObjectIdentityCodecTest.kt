@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class CatalogStableObjectIdentityCodecTest {
    private val connectorId = ConnectorId(Uuid.parse("00112233-4455-7788-99aa-bbccddeeff00"))

    @Test
    fun `stable object identifiers match frozen framed UUIDv8 vectors`() {
        assertEquals(
            "cf1701fc-679c-885a-a3c6-a722602a5e34",
            identity(CatalogObjectKind.SCHEMA, "Analytics", "Public", null, null, null),
        )
        assertEquals(
            "3ce4e1e7-af62-8c04-800b-608430df7e44",
            identity(CatalogObjectKind.RELATION, "Analytics", "Public", "Orders", null, null),
        )
        assertEquals(
            "2f78ed1b-7b53-80ba-bae4-4d9079304b54",
            identity(CatalogObjectKind.COLUMN, "Analytics", "Public", "Orders", "order_id", null),
        )
        assertEquals(
            "94540590-87e4-897f-ba10-526a64974989",
            identity(
                CatalogObjectKind.RELATIONSHIP,
                "Analytics",
                "Public",
                "Orders",
                "orders_customer_fkey",
                "FOREIGN_KEY",
            ),
        )
        assertEquals(
            "77a22589-2617-8828-9dd6-1d7b220706de",
            identity(CatalogObjectKind.RELATION, "analytics", "public", "orders", null, null),
        )
    }

    @Test
    fun `identity rejects NFD components and does not use a qualified name`() {
        assertFailsWith<IllegalArgumentException> {
            identity(CatalogObjectKind.RELATION, "Analytics", "Public", "Orde\u0301rs", null, null)
        }

        val tableObjectIdentity =
            CatalogStableObjectIdentityCodec.derive(
                connectorId = connectorId,
                objectKind = CatalogObjectKind.RELATION,
                catalogName = "Analytics",
                schemaName = "Public",
                objectName = "Orders",
                memberName = null,
                relationshipKindName = null,
            )
        val viewObjectIdentity =
            CatalogStableObjectIdentityCodec.derive(
                connectorId = connectorId,
                objectKind = CatalogObjectKind.RELATION,
                catalogName = "Analytics",
                schemaName = "Public",
                objectName = "Orders",
                memberName = null,
                relationshipKindName = null,
            )

        assertEquals(tableObjectIdentity.objectId, viewObjectIdentity.objectId)
        assertEquals(tableObjectIdentity.identityDigest, viewObjectIdentity.identityDigest)
    }

    @Test
    fun `CatalogObjectId accepts only derived UUIDv8 values`() {
        val derived =
            CatalogStableObjectIdentityCodec.derive(
                connectorId = connectorId,
                objectKind = CatalogObjectKind.RELATION,
                catalogName = "Analytics",
                schemaName = "Public",
                objectName = "Orders",
                memberName = null,
                relationshipKindName = null,
            ).objectId

        assertEquals("3ce4e1e7-af62-8c04-800b-608430df7e44", derived.toString())
        assertFailsWith<IllegalArgumentException> { CatalogObjectId(Uuid.generateV7()) }
        assertFailsWith<IllegalArgumentException> {
            CatalogObjectId(Uuid.parse("00000000-0000-4000-8000-000000000001"))
        }
    }

    private fun identity(
        objectKind: CatalogObjectKind,
        catalogName: String,
        schemaName: String,
        objectName: String?,
        memberName: String?,
        relationshipKindName: String?,
    ): String =
        CatalogStableObjectIdentityCodec.derive(
            connectorId = connectorId,
            objectKind = objectKind,
            catalogName = catalogName,
            schemaName = schemaName,
            objectName = objectName,
            memberName = memberName,
            relationshipKindName = relationshipKindName,
        ).objectId.toString()
}
