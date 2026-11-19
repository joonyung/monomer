package cc.monomer.search.api

import java.util.UUID

enum class SearchResourceKind { CONNECTOR, CATALOG, SCHEMA, RELATION, SEMANTIC_MODEL, METRIC, DASHBOARD }

data class SearchDocument(
    val workspaceId: UUID,
    val path: String,
    val kind: SearchResourceKind,
    val primaryNames: List<String>,
    val descriptions: List<String>,
    val structuralNames: List<String>,
)

interface SearchIndex {
    fun upsert(document: SearchDocument)
    fun deleteSubtree(workspaceId: UUID, path: String)
    fun candidates(
        workspaceId: UUID,
        scope: String,
        kinds: Set<SearchResourceKind>,
        query: String,
        match: SearchMatch,
        limit: Int,
        offset: Int,
    ): List<String>
}
