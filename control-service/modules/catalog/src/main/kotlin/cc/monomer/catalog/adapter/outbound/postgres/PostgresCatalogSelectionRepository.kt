@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSearchAddress
import cc.monomer.catalog.api.CatalogSearchProjectionPort
import cc.monomer.catalog.api.CatalogSelectionState
import cc.monomer.catalog.api.ConnectorCatalogSelectionState
import cc.monomer.catalog.application.CatalogSelectionRepository
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid

/** PostgreSQL owner for Catalog selections and their unavailable option cleanup. */
class PostgresCatalogSelectionRepository(
    dataSource: DataSource,
    objectMapper: ObjectMapper,
    private val searchProjection: CatalogSearchProjectionPort,
) : CatalogSelectionRepository {
    private val jdbc = CatalogOwnerJdbc(dataSource)
    private val selectionCodec = CatalogSelectionJsonCodec(objectMapper)

    override fun lockConnector(workspaceId: WorkspaceId, connectorId: ConnectorId) {
        jdbc.lockConnector(workspaceId, connectorId)
    }

    override fun currentConnectorCatalogSelection(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorCatalogSelectionState? = jdbc.queryOne(
        """
            SELECT * FROM catalog.connector_catalog_selections
             WHERE workspace_id = ? AND connector_id = ?
        """.trimIndent(),
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
        },
    ) { result ->
        ConnectorCatalogSelectionState(
            workspaceId = WorkspaceId(result.uuid("workspace_id")),
            connectorId = ConnectorId(result.uuid("connector_id")),
            selection = selectionCodec.decodeConnectorCatalogSelection(result.getString("selected_catalog_names")),
            committedBy = PrincipalId(result.uuid("committed_by")),
            committedAt = result.timestamp("committed_at"),
        )
    }

    override fun saveConnectorCatalogSelection(selection: ConnectorCatalogSelectionState) {
        jdbc.requireOwnerTransaction()
        val previousNames = currentConnectorCatalogSelection(selection.workspaceId, selection.connectorId)
            ?.selection?.selectedCatalogNames.orEmpty()
        jdbc.update(
            """
                INSERT INTO catalog.connector_catalog_selections
                    (workspace_id, connector_id, selected_catalog_names, committed_by, committed_at)
                VALUES (?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (workspace_id, connector_id) DO UPDATE SET
                    selected_catalog_names = EXCLUDED.selected_catalog_names,
                    committed_by = EXCLUDED.committed_by,
                    committed_at = EXCLUDED.committed_at
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, selection.workspaceId.value.toJavaUuid())
            statement.setObject(2, selection.connectorId.value.toJavaUuid())
            statement.setString(3, selectionCodec.encodeConnectorCatalogSelection(selection.selection))
            statement.setObject(4, selection.committedBy.value.toJavaUuid())
            statement.setTimestamp(5, Timestamp.from(selection.committedAt))
        }
        (previousNames - selection.selection.selectedCatalogNames).forEach { name ->
            searchProjection.deleteSubtree(selection.workspaceId, CatalogSearchAddress(selection.connectorId, name, null, null))
        }
        (selection.selection.selectedCatalogNames - previousNames).forEach { name ->
            searchProjection.projectSelectedCatalog(selection.workspaceId, selection.connectorId, name)
        }
    }

    override fun isConnectorCatalogSelected(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String,
    ): Boolean = jdbc.queryOne(
        """
            SELECT EXISTS (
                SELECT 1
                 FROM catalog.connector_catalog_selections
                 WHERE workspace_id = ? AND connector_id = ?
                   AND jsonb_exists(selected_catalog_names, ?)
            ) AS selected
        """.trimIndent(),
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            statement.setString(3, catalogName)
        },
    ) { result -> result.getBoolean("selected") } ?: false

    override fun hasCatalogDiscoveryOption(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String,
    ): Boolean = jdbc.queryOne(
        """
            SELECT EXISTS (
                SELECT 1
                  FROM catalog.selection_options options
                  JOIN catalog.selection_option_lists lists
                    ON lists.workspace_id = options.workspace_id
                   AND lists.connector_id = options.connector_id
                   AND lists.target_key = options.target_key
                 WHERE options.workspace_id = ? AND options.connector_id = ?
                   AND options.target_key = 'connector'
                   AND lists.child_kind = 'CATALOG'
                   AND options.child_name = ?
                   AND options.available = TRUE
            ) AS discovered
        """.trimIndent(),
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            statement.setString(3, catalogName)
        },
    ) { result -> result.getBoolean("discovered") } ?: false

    override fun deleteCatalogSelection(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String,
    ) {
        jdbc.requireOwnerTransaction()
        jdbc.update(
            """
                DELETE FROM catalog.selections
                 WHERE workspace_id = ? AND connector_id = ? AND entry_catalog_name = ?
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            statement.setString(3, catalogName)
        }
    }

    override fun currentSelection(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): CatalogSelectionState? = jdbc.queryOne(
        """
            SELECT * FROM catalog.selections
             WHERE workspace_id = ? AND connector_id = ?
               AND entry_catalog_name IS NOT DISTINCT FROM ?
        """.trimIndent(),
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            statement.setString(3, entry.catalogName())
        },
    ) { result ->
        val persistedConnectorId = ConnectorId(result.uuid("connector_id"))
        val persistedCatalogName = result.getString("entry_catalog_name")
        val persistedEntry = when (persistedCatalogName) {
            null -> CatalogIngestEntry.Connector(persistedConnectorId)
            else -> CatalogIngestEntry.Catalog(persistedConnectorId, persistedCatalogName)
        }
        check(persistedEntry == entry) { "Catalog selection entry does not match its requested address" }
        CatalogSelectionState(
            workspaceId = WorkspaceId(result.uuid("workspace_id")),
            connectorId = persistedConnectorId,
            entry = persistedEntry,
            selection = selectionCodec.decodeSelection(result.getString("selection")),
            committedBy = PrincipalId(result.uuid("committed_by")),
            committedAt = result.timestamp("committed_at"),
        )
    }

    override fun saveSelection(selection: CatalogSelectionState) {
        jdbc.requireOwnerTransaction()
        jdbc.update(
            """
            INSERT INTO catalog.selections
                (workspace_id, connector_id, entry_catalog_name, selection, committed_by, committed_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (workspace_id, connector_id, entry_catalog_name) DO UPDATE SET
                selection = EXCLUDED.selection,
                committed_by = EXCLUDED.committed_by,
                committed_at = EXCLUDED.committed_at
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, selection.workspaceId.value.toJavaUuid())
            statement.setObject(2, selection.connectorId.value.toJavaUuid())
            statement.setString(3, selection.entry.catalogName())
            statement.setString(4, selectionCodec.encodeSelection(selection.selection))
            statement.setObject(5, selection.committedBy.value.toJavaUuid())
            statement.setTimestamp(6, Timestamp.from(selection.committedAt))
        }
        deleteUnreferencedUnavailableOptions(selection)
    }

    private fun deleteUnreferencedUnavailableOptions(selection: CatalogSelectionState) {
        val selected = selection.selection
        val clauses = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val catalogName = selection.entry.catalogName()
        selected.selectedSchemas.forEach { schema ->
            clauses += "(o.child_name = ? AND l.child_kind = 'SCHEMA' AND l.catalog_name IS NOT DISTINCT FROM ?)"
            values += schema.schemaName
            values += catalogName
            val relations = schema.relations as? CatalogRelationSelection.SelectedRelations
            relations?.relationNames?.forEach { relation ->
                clauses += "(o.child_name = ? AND l.child_kind = 'RELATION' AND l.catalog_name IS NOT DISTINCT FROM ? AND l.schema_name = ?)"
                values += relation
                values += catalogName
                values += schema.schemaName
            }
        }
        val retained = clauses.joinToString(" OR ").ifBlank { "FALSE" }
        val sql = """
            DELETE FROM catalog.selection_options o
             USING catalog.selection_option_lists l
             WHERE o.workspace_id = ? AND o.connector_id = ? AND o.available = FALSE
               AND l.workspace_id = o.workspace_id
               AND l.connector_id = o.connector_id
               AND l.target_key = o.target_key
               AND l.catalog_name IS NOT DISTINCT FROM ?
               AND NOT ($retained)
        """.trimIndent()
        jdbc.update(sql) { statement ->
            statement.setObject(1, selection.workspaceId.value.toJavaUuid())
            statement.setObject(2, selection.connectorId.value.toJavaUuid())
            statement.setString(3, catalogName)
            bindValues(statement, 4, values)
        }
    }
}
