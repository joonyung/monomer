@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogSearchAddress
import cc.monomer.catalog.api.CatalogSearchListing
import cc.monomer.catalog.api.CatalogSearchReadPort
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorSearchReadPort
import cc.monomer.connector.api.ConnectorSettingsProperties
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class CatalogTableSearchServiceTest {
    private val workspace = WorkspaceId(Uuid.generateV7())
    private val current = ConnectorId.generate()
    private val deleted = ConnectorId.generate()
    private val index = mockk<SearchIndex>()
    private val connectors = mockk<ConnectorSearchReadPort>()
    private val catalog = mockk<CatalogSearchReadPort>()
    private val snapshots = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T = action()
    }
    private val service = CatalogTableSearchService(index, snapshots, connectors, catalog)

    @Test
    fun `prefix candidates keep only current tables of current connectors with matching columns`() = runTest {
        val invoices = CatalogSearchAddress(current, null, "billing", "invoices")
        val gone = CatalogSearchAddress(current, null, "billing", "invoice_archive")
        val orphan = CatalogSearchAddress(deleted, null, "billing", "invoice_lines")
        every {
            index.candidates(any(), "workspace/connectors", setOf(SearchResourceKind.RELATION), "invo", SearchMatch.TOKEN_PREFIX, 100, 0)
        } returns listOf(invoices, gone, orphan).map(::catalogSearchPath)
        every { catalog.readCurrentListings(workspace, setOf(invoices, gone, orphan)) } returns listOf(
            CatalogSearchListing(invoices, CatalogObjectKind.RELATION, RelationKind.TABLE, Instant.EPOCH),
            CatalogSearchListing(orphan, CatalogObjectKind.RELATION, RelationKind.VIEW, Instant.EPOCH),
        )
        every { connectors.readCurrentListings(workspace, setOf(current, deleted)) } returns listOf(
            CurrentConnectorRead(
                current, workspace, ConnectorProductType.POSTGRESQL, "Production Postgres",
                ConnectorSettingsProperties.Postgresql("db.internal", 5432, "analytics", null, null), Instant.EPOCH, Instant.EPOCH,
            ),
        )
        every { catalog.readRelationColumnNames(workspace, setOf(invoices)) } returns
            mapOf(invoices to listOf("invoice_id", "amount_cents", "invoiced_at"))

        val outcome = service.search(workspace, "invo", 20)

        val hit = (outcome as CatalogTableSearchOutcome.Found).tables.single()
        assertEquals("Production Postgres", hit.connectorName)
        assertEquals("billing", hit.schemaName)
        assertEquals("invoices", hit.tableName)
        assertEquals(listOf("invoice_id", "invoiced_at"), hit.matchedColumns)
    }

    @Test
    fun `queries without letters or numbers never reach the index`() = runTest {
        assertEquals(CatalogTableSearchOutcome.InvalidQuery, service.search(workspace, "!! :*", 20))
        verify(exactly = 0) { index.candidates(any(), any(), any(), any(), any(), any(), any()) }
    }
}
