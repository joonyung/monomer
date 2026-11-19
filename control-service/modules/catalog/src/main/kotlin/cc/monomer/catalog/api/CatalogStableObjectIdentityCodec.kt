@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.CanonicalIdentifierCodec
import cc.monomer.identity.api.CanonicalIdentifierPart

data class CatalogStableObjectIdentity(
    val objectId: CatalogObjectId,
    val identityDigest: CatalogDigest,
)

object CatalogStableObjectIdentityCodec {
    fun derive(
        connectorId: ConnectorId,
        objectKind: CatalogObjectKind,
        catalogName: String?,
        schemaName: String?,
        objectName: String?,
        memberName: String?,
        relationshipKindName: String?,
    ): CatalogStableObjectIdentity {
        val components =
            when (objectKind) {
                CatalogObjectKind.CATALOG -> {
                    require(catalogName != null)
                    require(schemaName == null && objectName == null && memberName == null)
                    require(relationshipKindName == null)
                    listOf(requireNotNull(catalogName))
                }
                CatalogObjectKind.SCHEMA -> {
                    require(schemaName != null && objectName == null && memberName == null)
                    require(relationshipKindName == null)
                    listOfNotNull(catalogName, schemaName)
                }
                CatalogObjectKind.RELATION -> {
                    require(schemaName != null && objectName != null && memberName == null)
                    require(relationshipKindName == null)
                    listOfNotNull(catalogName, schemaName, objectName)
                }
                CatalogObjectKind.COLUMN -> {
                    require(schemaName != null && objectName != null && memberName != null)
                    require(relationshipKindName == null)
                    listOfNotNull(catalogName, schemaName, objectName, memberName)
                }
                CatalogObjectKind.RELATIONSHIP -> {
                    require(schemaName != null && objectName != null && memberName != null)
                    require(relationshipKindName in setOf("PRIMARY_KEY", "FOREIGN_KEY"))
                    listOf(
                        *listOfNotNull(catalogName, schemaName, objectName, memberName).toTypedArray(),
                        requireNotNull(relationshipKindName),
                    )
                }
            }
        components.forEach { component ->
            require(component.isCanonicalCatalogIdentifier()) {
                "catalog identity component must be a canonical catalog identifier"
            }
        }
        val parts =
            buildList {
                add(CanonicalIdentifierPart.Text("monomer:catalog:stable-object-id:v1"))
                add(CanonicalIdentifierPart.UuidPart(connectorId.value))
                add(CanonicalIdentifierPart.Text(objectKind.name))
                components.forEach { component -> add(CanonicalIdentifierPart.Text(component)) }
            }
        return CatalogStableObjectIdentity(
            objectId = CatalogObjectId(CanonicalIdentifierCodec.sha256UuidV8(parts)),
            identityDigest = CatalogDigest(CanonicalIdentifierCodec.sha256Digest(parts)),
        )
    }
}
