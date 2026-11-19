@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class CatalogIngestJobId(val value: Uuid) {
    init { value.requireUuidV7("catalog ingest job id") }

    companion object {
        fun generate(): CatalogIngestJobId = CatalogIngestJobId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class CatalogIngestTaskId(val value: Uuid) {
    init { value.requireUuidV7("catalog ingest task id") }

    companion object {
        fun generate(): CatalogIngestTaskId = CatalogIngestTaskId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class CatalogListRefreshJobId(val value: Uuid) {
    init { value.requireUuidV7("catalog list refresh job id") }

    companion object {
        fun generate(): CatalogListRefreshJobId = CatalogListRefreshJobId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

data class CatalogDigest(val value: String) {
    init { require(value.matches(Regex("^sha256:[0-9a-f]{64}$"))) }
}

enum class CatalogObjectKind { CATALOG, SCHEMA, RELATION, COLUMN, RELATIONSHIP }

data class CatalogSelection(
    val selectedSchemas: Set<CatalogSelectedSchema>,
) {
    init {
        val addresses = selectedSchemas.map { it.schemaName }
        require(addresses.size == addresses.toSet().size)
    }

    fun includesSchema(schema: String): Boolean =
        selectedSchemas.any { it.schemaName == schema }

    fun includesRelation(schema: String, relation: String): Boolean =
        selectedSchemas.firstOrNull { it.schemaName == schema }
            ?.relations
            ?.includes(relation)
            ?: false
}

/** The complete Catalog membership selected for a three-depth Connector. */
data class ConnectorCatalogSelection(
    val selectedCatalogNames: Set<String>,
) {
    init {
        require(selectedCatalogNames.all(String::isCanonicalCatalogIdentifier)) {
            "selected Catalog names must be canonical"
        }
    }
}

/** The one committed Connector-level Catalog selection. It has no revision history. */
data class ConnectorCatalogSelectionState(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val selection: ConnectorCatalogSelection,
    val committedBy: PrincipalId,
    val committedAt: Instant,
) {
    fun toView(): ConnectorCatalogSelectionView = ConnectorCatalogSelectionView(
        workspaceId = workspaceId,
        connectorId = connectorId,
        selection = selection,
        committedAt = committedAt,
    )
}

data class ConnectorCatalogSelectionView(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val selection: ConnectorCatalogSelection,
    val committedAt: Instant?,
)

data class SaveConnectorCatalogSelectionCommand(
    val authorization: AuthorizedMutationContext,
    val connectorId: ConnectorId,
    val selection: ConnectorCatalogSelection,
)

data class CatalogSelectedSchema(
    val schemaName: String,
    val relations: CatalogRelationSelection,
) {
    init { require(schemaName.isCanonicalCatalogIdentifier()) }
}

sealed interface CatalogRelationSelection {
    fun includes(relationName: String): Boolean

    data object AllRelations : CatalogRelationSelection {
        override fun includes(relationName: String): Boolean = true
    }

    data class SelectedRelations(val relationNames: Set<String>) : CatalogRelationSelection {
        init {
            require(relationNames.all(String::isCanonicalCatalogIdentifier))
        }

        override fun includes(relationName: String): Boolean = relationName in relationNames
    }

}

/** The one committed selection used by the next admission. It has no revision history. */
data class CatalogSelectionState(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val entry: CatalogIngestEntry,
    val selection: CatalogSelection,
    val committedBy: PrincipalId,
    val committedAt: Instant,
) {
    init { require(entry.connectorId == connectorId) }

    fun toView(): CatalogSelectionView =
        CatalogSelectionView(workspaceId, connectorId, entry, selection, committedAt)
}

data class CatalogSelectionView(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val entry: CatalogIngestEntry,
    val selection: CatalogSelection,
    val committedAt: Instant?,
) {
    init { require(entry.connectorId == connectorId) }
}

data class SaveCatalogSelectionCommand(
    val authorization: AuthorizedMutationContext,
    val connectorId: ConnectorId,
    val entry: CatalogIngestEntry,
    val selection: CatalogSelection,
) {
    init { require(entry.connectorId == connectorId) }
}

data class StartCatalogIngestCommand(
    val authorization: AuthorizedExecutionContext,
    val connectorId: ConnectorId,
    val entry: CatalogIngestEntry,
) {
    init { require(entry.connectorId == connectorId) }
}

data class CatalogIngestStarted(
    val jobId: CatalogIngestJobId,
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val entry: CatalogIngestEntry,
)

sealed interface CatalogIngestEntry {
    val connectorId: ConnectorId
    val key: String

    data class Connector(override val connectorId: ConnectorId) : CatalogIngestEntry {
        override val key: String = "connector:${connectorId.value}"
    }

    data class Catalog(
        override val connectorId: ConnectorId,
        val name: String,
    ) : CatalogIngestEntry {
        init { require(name.isCanonicalCatalogIdentifier()) }
        override val key: String = "catalog:${connectorId.value}:$name"
    }
}

sealed interface CatalogCommandResult<out T> {
    data class Accepted<T>(val value: T) : CatalogCommandResult<T>
    data class Rejected(val failures: List<String>) : CatalogCommandResult<Nothing>
    data object Conflict : CatalogCommandResult<Nothing>
    data class ConflictWithReason(val reason: CatalogConflictReason) : CatalogCommandResult<Nothing>
    data object NotFoundOrOutsideWorkspace : CatalogCommandResult<Nothing>
    data object InvalidAuthorization : CatalogCommandResult<Nothing>
}

enum class CatalogConflictReason {
    ACTIVE_INGESTION,
    INVALID_CANDIDATE,
}

private fun Uuid.requireUuidV7(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    val bytes = toByteArray()
    require(bytes[8].toInt().and(0xc0) == 0x80) { "$label must use the IETF UUID variant" }
    require(bytes[6].toInt().and(0xf0) == 0x70) { "$label must be UUIDv7" }
}
