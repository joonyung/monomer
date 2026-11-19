@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class CatalogObjectId(val value: Uuid) {
    init {
        value.requireUuidV8("catalog object id")
    }

    override fun toString(): String = value.toString()
}

data class CatalogPhysicalAddress(
    val catalogName: String?,
    val schemaName: String?,
    val objectName: String?,
    val memberName: String?,
) {
    init {
        require(catalogName == null || catalogName.isCanonicalCatalogIdentifier())
        require(schemaName == null || schemaName.isCanonicalCatalogIdentifier())
        require(objectName == null || objectName.isCanonicalCatalogIdentifier())
        require(memberName == null || memberName.isCanonicalCatalogIdentifier())
    }

    fun requireShapeFor(kind: CatalogObjectKind) {
        when (kind) {
            CatalogObjectKind.CATALOG ->
                require(catalogName != null && schemaName == null && objectName == null && memberName == null)
            CatalogObjectKind.SCHEMA ->
                require(schemaName != null && objectName == null && memberName == null)
            CatalogObjectKind.RELATION ->
                require(objectName != null && memberName == null)
            CatalogObjectKind.COLUMN,
            CatalogObjectKind.RELATIONSHIP,
            -> require(objectName != null && memberName != null)
        }
    }
}

data class CatalogCurrentObject(
    val id: CatalogObjectId,
    val identityDigest: CatalogDigest,
    val parentId: CatalogObjectId?,
    val kind: CatalogObjectKind,
    val relationKind: RelationKind?,
    val address: CatalogPhysicalAddress,
    val qualifiedName: String,
    val nativeType: String?,
    val contentDigest: CatalogDigest,
    val definition: String?,
    val ordinal: Int?,
    val nullable: Boolean?,
    val defaultExpression: String?,
    val relationshipKind: CatalogRelationshipKind?,
    val memberOrdinal: Int?,
    val constraintName: String?,
    val sourceColumn: String?,
    val referencedCatalog: String?,
    val referencedSchema: String?,
    val referencedRelation: String?,
    val referencedColumn: String?,
    val observedAt: Instant,
    val partitionKeys: List<String>?,
    val clusteringKeys: List<String>?,
    val requiresPartitionFilter: Boolean?,
) {
    init {
        address.requireShapeFor(kind)
        require((kind == CatalogObjectKind.RELATION) == (relationKind != null))
        require(qualifiedName.isNotBlank())
        when (kind) {
            CatalogObjectKind.CATALOG -> require(parentId == null)
            CatalogObjectKind.SCHEMA -> Unit
            CatalogObjectKind.RELATION,
            CatalogObjectKind.COLUMN,
            CatalogObjectKind.RELATIONSHIP,
            -> require(parentId != null)
        }
        require(kind == CatalogObjectKind.RELATION || (partitionKeys == null && clusteringKeys == null && requiresPartitionFilter == null))
        if (kind == CatalogObjectKind.COLUMN) {
            require(!nativeType.isNullOrBlank())
            require(ordinal != null && ordinal > 0)
            require(nullable != null)
        } else {
            require(nativeType == null)
            require(ordinal == null)
            require(nullable == null)
            require(defaultExpression == null)
        }
        if (kind == CatalogObjectKind.RELATION) {
            require(relationshipKind == null)
            require(memberOrdinal == null)
            require(sourceColumn == null)
            require(referencedCatalog == null)
            require(referencedSchema == null)
            require(referencedRelation == null)
            require(referencedColumn == null)
        } else {
            require(definition == null)
        }
        if (kind == CatalogObjectKind.RELATIONSHIP) {
            require(relationshipKind != null)
            require(memberOrdinal != null && memberOrdinal > 0)
            require(constraintName?.isCanonicalCatalogIdentifier() == true)
            require(sourceColumn?.isCanonicalCatalogIdentifier() == true)
            require(address.memberName == "$constraintName:$memberOrdinal")
            when (relationshipKind) {
                CatalogRelationshipKind.PRIMARY_KEY -> {
                    require(referencedCatalog == null)
                    require(referencedSchema == null)
                    require(referencedRelation == null)
                    require(referencedColumn == null)
                }

                CatalogRelationshipKind.FOREIGN_KEY -> {
                    require(referencedSchema?.isCanonicalCatalogIdentifier() == true)
                    require(referencedRelation?.isCanonicalCatalogIdentifier() == true)
                    require(referencedColumn?.isCanonicalCatalogIdentifier() == true)
                    require(referencedCatalog == null || referencedCatalog.isCanonicalCatalogIdentifier())
                }
            }
        } else {
            require(relationshipKind == null)
            require(memberOrdinal == null)
            require(constraintName == null)
            require(sourceColumn == null)
            require(referencedCatalog == null)
            require(referencedSchema == null)
            require(referencedRelation == null)
            require(referencedColumn == null)
        }
    }
}

data class CatalogCurrentObjectSet(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val objects: List<CatalogCurrentObject>,
) {
    init {
        require(objects.map(CatalogCurrentObject::id).toSet().size == objects.size)
        require(objects.map(CatalogCurrentObject::identityDigest).toSet().size == objects.size)
        objects.zipWithNext().forEach { (left, right) ->
            require(left.identityDigest.value.asciiCompareTo(right.identityDigest.value) < 0)
        }

        val objectsById = objects.associateBy(CatalogCurrentObject::id)
        objects.forEach { currentObject ->
            when (currentObject.kind) {
                CatalogObjectKind.CATALOG -> Unit
                CatalogObjectKind.SCHEMA -> {
                    val parent = currentObject.parentId?.let(objectsById::get)
                    require(parent == null || parent.kind == CatalogObjectKind.CATALOG)
                    if (parent != null) {
                        require(parent.address.catalogName == currentObject.address.catalogName)
                    }
                }
                CatalogObjectKind.RELATION -> {
                    val parent = objectsById[currentObject.parentId]
                    require(parent?.kind == CatalogObjectKind.SCHEMA)
                    require(parent.address.catalogName == currentObject.address.catalogName)
                    require(parent.address.schemaName == currentObject.address.schemaName)
                }
                CatalogObjectKind.COLUMN,
                CatalogObjectKind.RELATIONSHIP,
                -> {
                    val parent = objectsById[currentObject.parentId]
                    require(parent?.kind == CatalogObjectKind.RELATION)
                    require(parent.address.catalogName == currentObject.address.catalogName)
                    require(parent.address.schemaName == currentObject.address.schemaName)
                    require(parent.address.objectName == currentObject.address.objectName)
                }
            }
        }
    }
}

data class CatalogCurrentRelationSummary(
    val id: CatalogObjectId,
    val connectorId: ConnectorId,
    val qualifiedName: String,
) {
    init {
        require(qualifiedName.isNotBlank())
    }
}

data class CatalogCurrentRelationPageCursor(
    val normalizedQualifiedName: String,
    val connectorId: ConnectorId,
    val objectId: CatalogObjectId,
) {
    init {
        require(normalizedQualifiedName.isNotBlank())
    }
}

data class CatalogCurrentRelationPage(
    val items: List<CatalogCurrentRelationSummary>,
    val nextCursor: CatalogCurrentRelationPageCursor?,
)

interface CatalogCurrentObjectsReadPort {
    /** Reads current rows in the caller's Catalog owner transaction. */
    fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet?

    fun searchCurrentRelations(
        workspaceId: WorkspaceId,
        query: String,
        cursor: CatalogCurrentRelationPageCursor?,
        pageSize: Int,
    ): CatalogCurrentRelationPage
}

private fun Uuid.requireUuidV8(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    require(toByteArray()[8].toInt().and(0xc0) == 0x80) { "$label must use the IETF UUID variant" }
    require(toByteArray()[6].toInt().and(0xf0) == 0x80) { "$label must be UUIDv8" }
}

internal fun String.isCanonicalCatalogIdentifier(): Boolean =
    isNotBlank() &&
        toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_CATALOG_IDENTIFIER_BYTES &&
        this == trim() &&
        Normalizer.normalize(this, Normalizer.Form.NFC) == this &&
        none(Character::isISOControl)

private fun String.asciiCompareTo(other: String): Int {
    val left = toByteArray(StandardCharsets.US_ASCII)
    val right = other.toByteArray(StandardCharsets.US_ASCII)
    val sharedLength = minOf(left.size, right.size)
    for (index in 0 until sharedLength) {
        val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

private const val MAXIMUM_CATALOG_IDENTIFIER_BYTES: Int = 1_024
