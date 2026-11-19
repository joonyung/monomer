package cc.monomer.search.adapter.outbound.postgres

import cc.monomer.search.api.SearchDocument
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import cc.monomer.search.api.SearchText
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresSearchIndex(private val dataSource: DataSource) : SearchIndex {
    override fun upsert(document: SearchDocument) = connection { connection ->
        connection.prepareStatement(
            """
            INSERT INTO search.documents(workspace_id, resource_path, resource_kind, primary_names, search_vector)
            VALUES (?, ?, ?, ?, setweight(to_tsvector('simple', ?), 'A') ||
                setweight(to_tsvector('simple', ?), 'B') || setweight(to_tsvector('simple', ?), 'C'))
            ON CONFLICT (workspace_id, resource_path) DO UPDATE SET
                resource_kind = EXCLUDED.resource_kind, primary_names = EXCLUDED.primary_names,
                search_vector = EXCLUDED.search_vector
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, document.workspaceId)
            statement.setString(2, document.path)
            statement.setString(3, document.kind.name)
            val names = connection.createArrayOf("text", document.primaryNames.map(SearchText::normalizedName).toTypedArray())
            try {
                statement.setArray(4, names)
                statement.setString(5, SearchText.document(document.primaryNames))
                statement.setString(6, SearchText.document(document.descriptions))
                statement.setString(7, SearchText.document(document.structuralNames))
                statement.executeUpdate()
            } finally { names.free() }
        }
        Unit
    }

    override fun deleteSubtree(workspaceId: UUID, path: String) = connection { connection ->
        connection.prepareStatement(
            "DELETE FROM search.documents WHERE workspace_id = ? AND (resource_path = ? OR starts_with(resource_path, ?))",
        ).use { statement ->
            statement.setObject(1, workspaceId)
            statement.setString(2, path)
            statement.setString(3, "$path/")
            statement.executeUpdate()
        }
        Unit
    }

    override fun candidates(
        workspaceId: UUID,
        scope: String,
        kinds: Set<SearchResourceKind>,
        query: String,
        match: SearchMatch,
        limit: Int,
        offset: Int,
    ): List<String> = connection { connection ->
        require(kinds.isNotEmpty() && limit > 0 && offset >= 0)
        connection.prepareStatement(
            """
            WITH input AS (SELECT to_tsquery('simple', ?) AS query, ?::text AS exact_name)
            SELECT resource_path FROM search.documents, input
            WHERE workspace_id = ? AND (resource_path = ? OR starts_with(resource_path, ?))
              AND resource_kind = ANY(?)
              AND (primary_names @> ARRAY[input.exact_name] OR search_vector @@ input.query)
            ORDER BY (primary_names @> ARRAY[input.exact_name]) DESC,
                ts_rank(search_vector, input.query) DESC, resource_path COLLATE "C" ASC
            LIMIT ? OFFSET ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, SearchText.query(query, match))
            statement.setString(2, SearchText.normalizedName(query))
            statement.setObject(3, workspaceId)
            statement.setString(4, scope)
            statement.setString(5, "$scope/")
            val kindNames = connection.createArrayOf("text", kinds.map { it.name }.toTypedArray())
            try {
                statement.setArray(6, kindNames)
                statement.setInt(7, limit)
                statement.setInt(8, offset)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            } finally { kindNames.free() }
        }
    }

    private fun <T> connection(block: (Connection) -> T): T {
        check(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.hasResource(dataSource)) {
            "Search requires the source transaction bound to the exact DataSource"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        try { return block(connection) } finally { DataSourceUtils.releaseConnection(connection, dataSource) }
    }
}
