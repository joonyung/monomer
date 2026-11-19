@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogSearchAddress
import cc.monomer.catalog.api.CatalogSearchReadPort
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorSearchReadPort
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import cc.monomer.search.api.SearchText
import kotlin.uuid.toJavaUuid

data class CatalogTableSearchHit(
    val connectorId: ConnectorId,
    val connectorName: String,
    val connectorType: ConnectorProductType,
    val catalogName: String?,
    val schemaName: String,
    val tableName: String,
    val relationKind: RelationKind,
    val matchedColumns: List<String>,
)

sealed interface CatalogTableSearchOutcome {
    data class Found(val tables: List<CatalogTableSearchHit>) : CatalogTableSearchOutcome
    data object InvalidQuery : CatalogTableSearchOutcome
}

interface CatalogTableSearchUseCase {
    suspend fun search(workspaceId: WorkspaceId, query: String, limit: Int): CatalogTableSearchOutcome
}

class CatalogTableSearchService(
    private val searchIndex: SearchIndex,
    private val transactionRunner: ConsistentSnapshotTransactionRunner,
    private val connectors: ConnectorSearchReadPort,
    private val catalog: CatalogSearchReadPort,
) : CatalogTableSearchUseCase {
    override suspend fun search(workspaceId: WorkspaceId, query: String, limit: Int): CatalogTableSearchOutcome {
        require(limit in 1..MAXIMUM_TABLE_SEARCH_LIMIT)
        if (query.length > MAXIMUM_TABLE_SEARCH_QUERY_LENGTH || !SearchText.hasTokens(query)) {
            return CatalogTableSearchOutcome.InvalidQuery
        }
        return transactionRunner.run {
            val hits = linkedMapOf<CatalogSearchAddress, CatalogTableSearchHit>()
            var offset = 0
            while (hits.size < limit) {
                val candidates = searchIndex.candidates(
                    workspaceId.value.toJavaUuid(), CONNECTORS_SCOPE, setOf(SearchResourceKind.RELATION),
                    query, SearchMatch.TOKEN_PREFIX, CANDIDATE_PAGE_SIZE, offset,
                ).mapNotNull(::catalogSearchAddress).filter { it.schemaName != null && it.relationName != null }
                if (candidates.isNotEmpty()) hits += currentHits(workspaceId, candidates, query, limit - hits.size)
                if (candidates.size < CANDIDATE_PAGE_SIZE) break
                offset += CANDIDATE_PAGE_SIZE
            }
            CatalogTableSearchOutcome.Found(hits.values.toList())
        }
    }

    private fun currentHits(
        workspaceId: WorkspaceId,
        candidates: List<CatalogSearchAddress>,
        query: String,
        remaining: Int,
    ): Map<CatalogSearchAddress, CatalogTableSearchHit> {
        val listings = catalog.readCurrentListings(workspaceId, candidates.toSet())
            .filter { it.kind == CatalogObjectKind.RELATION }
            .associateBy { it.address }
        val currentConnectors = connectors.readCurrentListings(workspaceId, listings.keys.map { it.connectorId }.toSet())
            .associateBy { it.connectorId }
        val relations = candidates.filter { it in listings && it.connectorId in currentConnectors }.take(remaining)
        val columns = catalog.readRelationColumnNames(workspaceId, relations.toSet())
        return relations.associateWith { address ->
            val connector = currentConnectors.getValue(address.connectorId)
            CatalogTableSearchHit(
                connectorId = address.connectorId,
                connectorName = connector.displayName,
                connectorType = connector.connectorType,
                catalogName = address.catalogName,
                schemaName = requireNotNull(address.schemaName),
                tableName = requireNotNull(address.relationName),
                relationKind = requireNotNull(listings.getValue(address).relationKind),
                matchedColumns = columns[address].orEmpty().filter { SearchText.matchesAny(it, query, SearchMatch.TOKEN_PREFIX) },
            )
        }
    }

    companion object {
        const val MAXIMUM_TABLE_SEARCH_LIMIT: Int = 50
        const val MAXIMUM_TABLE_SEARCH_QUERY_LENGTH: Int = 200
        private const val CANDIDATE_PAGE_SIZE: Int = 100
        private const val CONNECTORS_SCOPE: String = "workspace/connectors"
    }
}
