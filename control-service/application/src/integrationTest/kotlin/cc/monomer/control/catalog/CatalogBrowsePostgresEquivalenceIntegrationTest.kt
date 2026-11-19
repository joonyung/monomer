@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog

import cc.monomer.catalog.adapter.outbound.postgres.CatalogTransactionTimeouts
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogCurrentObjectsReadPort
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogIngestRepository
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSelectionRepository
import cc.monomer.catalog.adapter.outbound.postgres.SpringCatalogTransactionPort
import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogRelationshipKind
import cc.monomer.catalog.api.CatalogSearchAddress
import cc.monomer.catalog.api.CatalogSearchFields
import cc.monomer.catalog.api.CatalogSearchProjectionPort
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogMetadataObservation
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.catalog.application.CatalogBrowseService
import cc.monomer.catalog.application.CatalogBrowseUseCase
import cc.monomer.catalog.application.CatalogNotFoundException
import cc.monomer.catalog.application.CatalogSummary
import cc.monomer.catalog.application.ColumnInfo
import cc.monomer.catalog.application.ForeignKeyInfo
import cc.monomer.catalog.application.PrimaryKeyInfo
import cc.monomer.catalog.application.RelationDetail
import cc.monomer.catalog.application.RelationListPage
import cc.monomer.catalog.application.RelationSummary
import cc.monomer.catalog.application.SchemaListPage
import cc.monomer.catalog.application.SchemaSummary
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Browse reads only the listing it serves. The pre-narrowing in-memory browse is kept below as an
 * oracle, and every listing must return exactly what it returned for the same PostgreSQL rows.
 */
class CatalogBrowsePostgresEquivalenceIntegrationTest {
    @Test
    fun `catalog lists match the whole-connector browse`() = runBlocking {
        connectors.forEach { connector ->
            compare("catalogs $connector") { browse -> browse.listCatalogs(workspace, connector) }
        }
        assertNonTrivial(minimumNonEmpty = 1, requiresDetail = false)
    }

    @Test
    fun `schema pages match the whole-connector browse`() = runBlocking {
        connectors.forEach { connector ->
            catalogArguments.forEach { catalogName ->
                pages.forEach { (page, pageSize) ->
                    queries.forEach { query ->
                        compare("schemas $connector $catalogName $page $pageSize '$query'") { browse ->
                            browse.listSchemas(workspace, connector, catalogName, page, pageSize, query)
                        }
                    }
                }
            }
        }
        assertNonTrivial(minimumNonEmpty = 15, requiresDetail = false)
    }

    @Test
    fun `schema keyset pages match the whole-connector browse`() = runBlocking {
        connectors.forEach { connector ->
            catalogArguments.forEach { catalogName ->
                (listOf(null, "", "zzz") + schemaNames).forEach { afterName ->
                    limits.forEach { limit ->
                        compare("schemas after $connector $catalogName '$afterName' $limit") { browse ->
                            browse.listSchemasAfter(workspace, connector, catalogName, afterName, limit)
                        }
                    }
                }
            }
        }
        assertNonTrivial(minimumNonEmpty = 30, requiresDetail = false)
    }

    @Test
    fun `relation pages and column counts match the whole-connector browse`() = runBlocking {
        connectors.forEach { connector ->
            catalogArguments.forEach { catalogName ->
                (schemaNames + "missing").forEach { schemaName ->
                    pages.forEach { (page, pageSize) ->
                        queries.forEach { query ->
                            compare("relations $connector $catalogName $schemaName $page $pageSize '$query'") { browse ->
                                browse.listRelations(workspace, connector, catalogName, schemaName, page, pageSize, query)
                            }
                        }
                    }
                }
            }
        }
        assertNonTrivial(minimumNonEmpty = 60, requiresDetail = true)
    }

    @Test
    fun `relation keyset pages and column counts match the whole-connector browse`() = runBlocking {
        connectors.forEach { connector ->
            catalogArguments.forEach { catalogName ->
                (schemaNames + "missing").forEach { schemaName ->
                    (listOf(null, "", "zzz") + keysetRelationNames).forEach { afterName ->
                        limits.forEach { limit ->
                            compare("relations after $connector $catalogName $schemaName '$afterName' $limit") { browse ->
                                browse.listRelationsAfter(workspace, connector, catalogName, schemaName, afterName, limit)
                            }
                        }
                    }
                }
            }
        }
        assertNonTrivial(minimumNonEmpty = 70, requiresDetail = true)
    }

    @Test
    fun `relation details match the whole-connector browse`() = runBlocking {
        connectors.forEach { connector ->
            catalogArguments.forEach { catalogName ->
                (schemaNames + "missing").forEach { schemaName ->
                    (relationNames + "missing").forEach { relationName ->
                        compare("relation $connector $catalogName $schemaName $relationName") { browse ->
                            browse.getRelation(workspace, connector, catalogName, schemaName, relationName)
                        }
                    }
                }
            }
        }
        assertNonTrivial(minimumNonEmpty = 20, requiresDetail = true)
    }

    private var comparisons = 0
    private var nonEmptyComparisons = 0
    private var detailedComparisons = 0

    private suspend fun compare(label: String, read: suspend (CatalogBrowseUseCase) -> Any) {
        val expected = runCatching { read(oracle) }
        val actual = runCatching { read(narrowed) }
        assertEquals(expected.exceptionOrNull()?.let { it::class to it.message }, actual.exceptionOrNull()?.let { it::class to it.message }, label)
        assertEquals(expected.getOrNull(), actual.getOrNull(), label)
        val value = expected.getOrNull()
        comparisons += 1
        if (value is List<*> && value.isNotEmpty() || value is SchemaListPage && value.schemas.isNotEmpty() ||
            value is RelationListPage && value.relations.isNotEmpty() || value is RelationDetail
        ) {
            nonEmptyComparisons += 1
        }
        val relations = (value as? RelationListPage)?.relations ?: (value as? List<*>)?.filterIsInstance<RelationSummary>().orEmpty()
        if (relations.any { it.columnCount > 0 } ||
            value is RelationDetail && value.foreignKeys.isNotEmpty() && value.primaryKey != null
        ) {
            detailedComparisons += 1
        }
    }

    private fun assertNonTrivial(minimumNonEmpty: Int, requiresDetail: Boolean) {
        assertTrue(nonEmptyComparisons >= minimumNonEmpty, "only $nonEmptyComparisons of $comparisons listings were non-empty")
        assertTrue(!requiresDetail || detailedComparisons > 0, "no listing exercised column counts or keys")
    }

    private companion object {
        private const val IMAGE = "postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
        private const val RUNNER = "browse-fixture-runner"
        private val postgres = PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("catalog_browse_equivalence")
        private val baseTime = Instant.parse("2026-10-01T00:00:00Z")
        private val requestedBy = PrincipalId(Uuid.generateV7())
        private val workspaceId = WorkspaceId(Uuid.generateV7())
        private val otherWorkspaceId = WorkspaceId(Uuid.generateV7())
        private val twoDepthConnector = ConnectorId(Uuid.generateV7())
        private val threeDepthConnector = ConnectorId(Uuid.generateV7())
        private val workspace = workspaceId.toString()
        private val connectors = listOf(twoDepthConnector, threeDepthConnector, ConnectorId(Uuid.generateV7())).map(ConnectorId::toString)
        private val catalogArguments = listOf(null, "warehouse", "lake", "Archive", "nope")
        private val schemaNames = listOf("analytics", "Sales", "raw", "Ünicode", "zzz_selected")
        private val relationNames = listOf(
            "orders", "Orders_archive", "order_items", "ORDERS2", "_tmp", "a%b", "a_b", "aB", "zeta", "ältere",
            "émission", "Émission", "한글테이블", "customer", "Customer_dim", "events", "empty_view",
        )
        private val keysetRelationNames = listOf("Orders_archive", "a_b", "orders", "émission")
        private val pages = listOf<Pair<Int?, Int?>>(null to null, 1 to 1, 2 to 3, 3 to 2, 4 to Int.MAX_VALUE, 0 to 1)
        private val queries = listOf(null, "  ", "ord", " Ord ", "_", "%", "Ü", "é", "zzz")
        private val limits = listOf(1, 2, 101, Int.MAX_VALUE)
        private val selectedSchemaNames: (WorkspaceId, ConnectorId, CatalogIngestEntry) -> Set<String> = { workspace, connector, entry ->
            when {
                workspace != workspaceId -> emptySet()
                connector == twoDepthConnector && entry == CatalogIngestEntry.Connector(twoDepthConnector) ->
                    setOf("analytics", "billing", "zzz_selected", "Sales")
                connector == threeDepthConnector && entry == CatalogIngestEntry.Catalog(threeDepthConnector, "warehouse") ->
                    setOf("analytics", "billing")
                connector == threeDepthConnector && entry == CatalogIngestEntry.Catalog(threeDepthConnector, "Archive") ->
                    setOf("raw")
                else -> emptySet()
            }
        }
        private lateinit var oracle: CatalogBrowseUseCase
        private lateinit var narrowed: CatalogBrowseUseCase

        @JvmStatic
        @BeforeAll
        fun startDatabase() {
            postgres.start()
            val dataSource = PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(checkNotNull(javaClass.classLoader.getResource("db/catalog/schema.sql")).readText())
                }
            }
            val transactionPort = transactionPort(dataSource)
            val currentObjects = PostgresCatalogCurrentObjectsReadPort(dataSource)
            Fixture(dataSource, transactionPort).write()
            oracle = InMemoryCatalogBrowseOracle(currentObjects, selectedSchemaNames, transactionPort)
            narrowed = CatalogBrowseService(currentObjects, selectedSchemaNames, transactionPort)
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() {
            postgres.stop()
        }

        private fun transactionPort(dataSource: DataSource): SpringCatalogTransactionPort {
            val manager = DataSourceTransactionManager(dataSource)
            return SpringCatalogTransactionPort(
                dataSource,
                TransactionTemplate(manager).apply {
                    isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
                    timeout = 30
                },
                TransactionTemplate(manager).apply {
                    isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                    isReadOnly = true
                    timeout = 30
                },
                Dispatchers.IO,
                CatalogTransactionTimeouts(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5)),
            )
        }
    }

    private class Fixture(dataSource: DataSource, private val transactionPort: CatalogTransactionPort) {
        private val searchProjection = object : CatalogSearchProjectionPort {
            override fun upsert(fields: CatalogSearchFields) = Unit
            override fun deleteSubtree(workspaceId: WorkspaceId, address: CatalogSearchAddress) = Unit
        }
        private val selections = PostgresCatalogSelectionRepository(dataSource, ObjectMapper(), searchProjection)
        private val ingest = PostgresCatalogIngestRepository(dataSource, ObjectMapper(), searchProjection, selections)
        private var tick = 0L

        fun write() = runBlocking {
            ingestEntry(workspaceId, twoDepthConnector, null, mapOf(
                "analytics" to analyticsRelations(null),
                "Sales" to listOf(table("orders", 3), table("Customer_dim", 2)),
                "staging_ü" to listOf(table("_tmp", 0)),
                "Ünicode" to listOf(table("émission", 1), table("Émission", 2)),
                "한글스키마" to listOf(table("한글테이블", 2)),
            ))
            ingestEntry(workspaceId, threeDepthConnector, "warehouse", mapOf(
                "analytics" to analyticsRelations("warehouse"),
                "billing" to listOf(table("events", 2)),
            ))
            ingestEntry(workspaceId, threeDepthConnector, "lake", mapOf(
                "analytics" to listOf(table("orders", 1), table("aB", 2), table("a_b", 1), table("zeta", 0)),
                "raw" to listOf(table("events", 4), table("ORDERS2", 1)),
            ))
            ingestEntry(workspaceId, threeDepthConnector, "Archive", mapOf("Sales" to listOf(table("orders", 2))))
            ingestEntry(otherWorkspaceId, twoDepthConnector, null, mapOf(
                "analytics" to listOf(table("orders", 9), table("leaked", 1)),
                "leaked_schema" to listOf(table("orders", 1)),
            ))
        }

        private fun analyticsRelations(catalog: String?): List<RelationFixture> = listOf(
            RelationFixture("orders", RelationKind.TABLE, columns = listOf(
                column("id", 1), column("customer_id", 2), column("Total", 3, nullable = true, default = "0"),
                column("created_at", 4), column("status", 5, nullable = true),
            ), keys = { relation ->
                listOf(
                    key(relation, "orders_pk", CatalogRelationshipKind.PRIMARY_KEY, 1, "id"),
                    key(relation, "orders_alt_pk", CatalogRelationshipKind.PRIMARY_KEY, 2, "created_at"),
                    key(relation, "orders_alt_pk", CatalogRelationshipKind.PRIMARY_KEY, 1, "status"),
                    key(relation, "orders_customer_fk", CatalogRelationshipKind.FOREIGN_KEY, 1, "customer_id",
                        reference(catalog, "analytics", "customer", "id")),
                    key(relation, "orders_composite_fk", CatalogRelationshipKind.FOREIGN_KEY, 2, "status",
                        reference(catalog, "analytics", "order_items", "line")),
                    key(relation, "orders_composite_fk", CatalogRelationshipKind.FOREIGN_KEY, 1, "id",
                        reference(catalog, "analytics", "order_items", "order_id")),
                    key(relation, "a_cross_catalog_fk", CatalogRelationshipKind.FOREIGN_KEY, 1, "customer_id",
                        reference(if (catalog == null) null else "lake", "analytics", "orders", "id")),
                )
            }),
            table("Orders_archive", 2),
            RelationFixture("order_items", RelationKind.TABLE, columns = listOf(
                column("order_id", 1), column("line", 2), column("sku", 2), column("qty", 7, nullable = true),
            ), keys = { relation ->
                listOf(
                    key(relation, "order_items_pk", CatalogRelationshipKind.PRIMARY_KEY, 2, "line"),
                    key(relation, "order_items_pk", CatalogRelationshipKind.PRIMARY_KEY, 1, "order_id"),
                )
            }),
            table("ORDERS2", 1),
            table("_tmp", 0),
            table("a%b", 1),
            table("a_b", 2),
            table("aB", 1),
            RelationFixture("zeta", RelationKind.VIEW, definition = "SELECT * FROM orders WHERE status = 'open'",
                columns = listOf(column("id", 1))),
            RelationFixture("ältere", RelationKind.MATERIALIZED_VIEW, definition = "SELECT 1 AS one",
                columns = listOf(column("one", 1)), partitionKeys = listOf("one"), clusteringKeys = listOf("one", "two"),
                requiresPartitionFilter = true),
            table("émission", 3),
            table("한글테이블", 1),
            RelationFixture("customer", RelationKind.EXTERNAL, columns = listOf(column("id", 1), column("name", 2)),
                keys = { relation -> listOf(key(relation, "customer_pk", CatalogRelationshipKind.PRIMARY_KEY, 1, "id")) }),
            table("Customer_dim", 4),
            RelationFixture("empty_view", RelationKind.VIEW, definition = null, columns = emptyList()),
        )

        private suspend fun ingestEntry(
            workspaceId: WorkspaceId,
            connectorId: ConnectorId,
            catalogName: String?,
            schemas: Map<String, List<RelationFixture>>,
        ) {
            val entry = if (catalogName == null) CatalogIngestEntry.Connector(connectorId) else CatalogIngestEntry.Catalog(connectorId, catalogName)
            val requested = CatalogIngestJob.request(
                id = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
                workspaceId = workspaceId,
                connectorId = connectorId,
                selection = CatalogSelection(schemas.keys.map { CatalogSelectedSchema(it, CatalogRelationSelection.AllRelations) }.toSet()),
                entry = entry,
                requestedBy = requestedBy,
                requestedAt = baseTime,
                credentialSurface = null,
                agentClientId = null,
            )
            transactionPort.inTransaction { ingest.insertIngestJob(requested) }
            val job = checkNotNull(transactionPort.inTransaction { ingest.claimIngestJob(requested.id, RUNNER, baseTime) })
            val entryTask = transactionPort.inTransaction { ingest.findIngestTasks(job.id) }.single()
            transactionPort.inTransaction {
                ingest.applyContainer(
                    entryTask,
                    listOfNotNull(catalogName?.let(CatalogMetadataObservation::Catalog)),
                    schemas.keys.map { CatalogIngestTaskTarget.Schema(catalogName, it) },
                    RUNNER,
                    nextObservedAt(),
                )
            }
            schemas.forEach { (schemaName, relations) ->
                val schemaTask = startTask(job, entryTask, CatalogIngestTaskTarget.Schema(catalogName, schemaName))
                transactionPort.inTransaction {
                    ingest.applyContainer(
                        schemaTask,
                        listOf(CatalogMetadataObservation.Schema(catalogName, schemaName)),
                        relations.map { CatalogIngestTaskTarget.Relation(catalogName, schemaName, it.name, it.kind) },
                        RUNNER,
                        nextObservedAt(),
                    )
                }
                relations.forEach { relation ->
                    val target = CatalogIngestTaskTarget.Relation(catalogName, schemaName, relation.name, relation.kind)
                    val relationTask = startTask(job, schemaTask, target)
                    transactionPort.inTransaction {
                        ingest.completeRelationTask(
                            relationTask,
                            listOf(CatalogMetadataObservation.Relation(catalogName, schemaName, relation.name, relation.kind,
                                relation.definition, relation.partitionKeys, relation.clusteringKeys, relation.requiresPartitionFilter)),
                            relation.columns.map { column ->
                                CatalogMetadataObservation.Column(catalogName, schemaName, relation.name, column.name,
                                    column.ordinal, column.nativeType, column.nullable, column.defaultExpression)
                            },
                            relation.keys(RelationAddress(catalogName, schemaName, relation.name)),
                            RUNNER,
                            nextObservedAt(),
                        )
                    }
                }
            }
        }

        private suspend fun startTask(job: CatalogIngestJob, parent: CatalogIngestTask, target: CatalogIngestTaskTarget): CatalogIngestTask {
            val task = CatalogIngestTask.start(job, parent.id, target, baseTime)
            check(transactionPort.inTransaction { ingest.findOrInsertOwnedIngestTask(task, RUNNER) } == task)
            return task
        }

        private fun table(name: String, columnCount: Int) =
            RelationFixture(name, RelationKind.TABLE, columns = (1..columnCount).map { column("c$it", it) })

        private fun column(name: String, ordinal: Int, nullable: Boolean = false, default: String? = null) =
            ColumnFixture(name, ordinal, if (ordinal % 2 == 0) "text" else "bigint", nullable, default)

        private fun reference(catalog: String?, schema: String, relation: String, column: String) =
            Reference(catalog, schema, relation, column)

        private fun key(
            relation: RelationAddress,
            constraint: String,
            kind: CatalogRelationshipKind,
            ordinal: Int,
            sourceColumn: String,
            reference: Reference? = null,
        ) = CatalogMetadataObservation.RelationshipMember(
            relation.catalog, relation.schema, relation.relation, constraint, kind, ordinal, sourceColumn,
            reference?.catalog, reference?.schema, reference?.relation, reference?.column,
        )

        private fun nextObservedAt(): Instant = baseTime.plusMillis(++tick * 1_013)
    }

    private data class RelationFixture(
        val name: String,
        val kind: RelationKind,
        val definition: String? = null,
        val columns: List<ColumnFixture>,
        val partitionKeys: List<String>? = null,
        val clusteringKeys: List<String>? = null,
        val requiresPartitionFilter: Boolean? = null,
        val keys: (RelationAddress) -> List<CatalogMetadataObservation.RelationshipMember> = { emptyList() },
    )

    private data class ColumnFixture(
        val name: String,
        val ordinal: Int,
        val nativeType: String,
        val nullable: Boolean,
        val defaultExpression: String?,
    )

    private data class RelationAddress(val catalog: String?, val schema: String, val relation: String)

    private data class Reference(val catalog: String?, val schema: String, val relation: String, val column: String)
}

/** The pre-narrowing browse, verbatim: it loads every current object of the Connector. */
private class InMemoryCatalogBrowseOracle(
    private val currentObjectsReadPort: CatalogCurrentObjectsReadPort,
    private val selectedSchemaNames: (WorkspaceId, ConnectorId, CatalogIngestEntry) -> Set<String>,
    private val transactionPort: CatalogTransactionPort,
) : CatalogBrowseUseCase {
    override suspend fun listCatalogs(workspaceId: String, connectorId: String): List<CatalogSummary> =
        readCurrent(workspaceId, connectorId)?.objects.orEmpty()
            .filter { it.kind == CatalogObjectKind.CATALOG }
            .mapNotNull { objectRow -> objectRow.address.catalogName?.let { it to objectRow.observedAt } }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { (name, observedAt) ->
            CatalogSummary(
                name = name,
                lastRefreshedAtEpochMs = observedAt.toEpochMilli(),
            )
        }

    override suspend fun listSchemas(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        page: Int?,
        pageSize: Int?,
        query: String?,
    ): SchemaListPage {
        val resolvedPage = page ?: DEFAULT_PAGE
        val resolvedPageSize = pageSize ?: DEFAULT_PAGE_SIZE
        val parsedWorkspaceId = WorkspaceId(Uuid.parse(workspaceId))
        val parsedConnectorId = ConnectorId(Uuid.parse(connectorId))
        val entry = if (catalogName == null) CatalogIngestEntry.Connector(parsedConnectorId)
            else runCatching { CatalogIngestEntry.Catalog(parsedConnectorId, catalogName) }.getOrNull()
        val (current, selected) = transactionPort.inReadOnlyTransaction {
            currentObjectsReadPort.readCurrent(parsedWorkspaceId, parsedConnectorId)?.objects.orEmpty() to
                entry?.let { selectedSchemaNames(parsedWorkspaceId, parsedConnectorId, it) }.orEmpty()
        }
        val loaded = current.filter { objectRow ->
            objectRow.kind == CatalogObjectKind.SCHEMA &&
                (catalogName == null || objectRow.address.catalogName == catalogName) &&
                queryMatches(objectRow.address.schemaName, query)
        }.map { objectRow ->
            SchemaSummary(
                catalogName = objectRow.address.catalogName,
                name = requireNotNull(objectRow.address.schemaName),
                lastRefreshedAtEpochMs = objectRow.observedAt.toEpochMilli(),
            )
        }
        val loadedNames = loaded.map(SchemaSummary::name).toSet()
        val notLoaded = selected.filter { it !in loadedNames && queryMatches(it, query) }.map { schemaName ->
            SchemaSummary(
                catalogName = catalogName,
                name = schemaName,
                lastRefreshedAtEpochMs = null,
            )
        }
        val schemas = (loaded + notLoaded).sortedWith(compareBy<SchemaSummary> { it.name }.thenBy { it.catalogName.orEmpty() })
        return SchemaListPage(
            schemas = schemas.page(resolvedPage, resolvedPageSize),
            totalCount = schemas.size,
            page = resolvedPage,
            pageSize = resolvedPageSize,
        )
    }

    override suspend fun listSchemasAfter(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        afterName: String?,
        limit: Int,
    ): List<SchemaSummary> {
        require(limit > 0)
        return readCurrent(workspaceId, connectorId)?.objects.orEmpty()
            .asSequence()
            .filter { objectRow ->
                objectRow.kind == CatalogObjectKind.SCHEMA &&
                    objectRow.address.catalogName == catalogName &&
                    (afterName == null || requireNotNull(objectRow.address.schemaName) > afterName)
            }
            .sortedBy { requireNotNull(it.address.schemaName) }
            .take(limit)
            .map { objectRow ->
                SchemaSummary(
                    catalogName = objectRow.address.catalogName,
                    name = requireNotNull(objectRow.address.schemaName),
                    lastRefreshedAtEpochMs = objectRow.observedAt.toEpochMilli(),
                )
            }
            .toList()
    }

    override suspend fun listRelations(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        page: Int?,
        pageSize: Int?,
        query: String?,
    ): RelationListPage {
        val resolvedPage = page ?: DEFAULT_PAGE
        val resolvedPageSize = pageSize ?: DEFAULT_PAGE_SIZE
        val current = readCurrent(workspaceId, connectorId)?.objects.orEmpty()
        val filtered = current.filter { objectRow ->
            objectRow.kind == CatalogObjectKind.RELATION &&
                (catalogName == null || objectRow.address.catalogName == catalogName) &&
                objectRow.address.schemaName == schemaName &&
                queryMatches(objectRow.address.objectName, query)
        }.sortedWith(
            compareBy<CatalogCurrentObject> { requireNotNull(it.address.objectName) }
                .thenBy { it.address.catalogName.orEmpty() },
        )
        return RelationListPage(
            relations = filtered.page(resolvedPage, resolvedPageSize).map { objectRow ->
                RelationSummary(
                    catalogName = objectRow.address.catalogName,
                    schemaName = requireNotNull(objectRow.address.schemaName),
                    name = requireNotNull(objectRow.address.objectName),
                    kind = requireNotNull(objectRow.relationKind).name,
                    columnCount = current.count { it.kind == CatalogObjectKind.COLUMN && it.parentId == objectRow.id },
                    lastRefreshedAtEpochMs = objectRow.observedAt.toEpochMilli(),
                )
            },
            totalCount = filtered.size,
            page = resolvedPage,
            pageSize = resolvedPageSize,
        )
    }

    override suspend fun listRelationsAfter(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        afterName: String?,
        limit: Int,
    ): List<RelationSummary> {
        require(limit > 0)
        val current = readCurrent(workspaceId, connectorId)?.objects.orEmpty()
        return current.asSequence()
            .filter { objectRow ->
                objectRow.kind == CatalogObjectKind.RELATION &&
                    objectRow.address.catalogName == catalogName &&
                    objectRow.address.schemaName == schemaName &&
                    (afterName == null || requireNotNull(objectRow.address.objectName) > afterName)
            }
            .sortedBy { requireNotNull(it.address.objectName) }
            .take(limit)
            .map { objectRow ->
                RelationSummary(
                    catalogName = objectRow.address.catalogName,
                    schemaName = requireNotNull(objectRow.address.schemaName),
                    name = requireNotNull(objectRow.address.objectName),
                    kind = requireNotNull(objectRow.relationKind).name,
                    columnCount = current.count { it.kind == CatalogObjectKind.COLUMN && it.parentId == objectRow.id },
                    lastRefreshedAtEpochMs = objectRow.observedAt.toEpochMilli(),
                )
            }
            .toList()
    }

    override suspend fun getRelation(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        relationName: String,
    ): RelationDetail {
        val objects = readCurrent(workspaceId, connectorId)?.objects.orEmpty()
        val relation = objects.firstOrNull { objectRow ->
            objectRow.kind == CatalogObjectKind.RELATION &&
                objectRow.address.catalogName == catalogName &&
                objectRow.address.schemaName == schemaName &&
                objectRow.address.objectName == relationName
        } ?: throw CatalogNotFoundException("catalog relation not found")
        val columns = objects.filter { it.kind == CatalogObjectKind.COLUMN && it.parentId == relation.id }
            .sortedBy { requireNotNull(it.ordinal) }
        val relationships = objects.filter {
            it.kind == CatalogObjectKind.RELATIONSHIP && it.parentId == relation.id
        }
        val primaryKey = relationships
            .filter { it.relationshipKind == CatalogRelationshipKind.PRIMARY_KEY }
            .groupBy { requireNotNull(it.constraintName) }
            .toSortedMap()
            .entries
            .firstOrNull()
            ?.let { (name, members) ->
                PrimaryKeyInfo(
                    name = name,
                    columns = members.sortedBy { requireNotNull(it.memberOrdinal) }
                        .map { requireNotNull(it.sourceColumn) },
                )
            }
        val foreignKeys = relationships
            .filter { it.relationshipKind == CatalogRelationshipKind.FOREIGN_KEY }
            .groupBy { requireNotNull(it.constraintName) }
            .toSortedMap()
            .map { (name, members) ->
                val first = members.minBy { requireNotNull(it.memberOrdinal) }
                ForeignKeyInfo(
                    name = name,
                    columns = members.sortedBy { requireNotNull(it.memberOrdinal) }
                        .map { requireNotNull(it.sourceColumn) },
                    referencedConnectorId = connectorId,
                    referencedCatalogName = first.referencedCatalog,
                    referencedSchemaName = requireNotNull(first.referencedSchema),
                    referencedRelationName = requireNotNull(first.referencedRelation),
                    referencedColumns = members.sortedBy { requireNotNull(it.memberOrdinal) }
                        .map { requireNotNull(it.referencedColumn) },
                )
            }
        return RelationDetail(
            resourceId = relation.id.value,
            catalogName = relation.address.catalogName,
            schemaName = requireNotNull(relation.address.schemaName),
            name = requireNotNull(relation.address.objectName),
            kind = requireNotNull(relation.relationKind).name,
            viewDefinition = relation.definition,
            columns = columns.map { column ->
                ColumnInfo(
                    name = requireNotNull(column.address.memberName),
                    nativeType = requireNotNull(column.nativeType),
                    ordinalPosition = requireNotNull(column.ordinal),
                    nullable = requireNotNull(column.nullable),
                    defaultValue = column.defaultExpression,
                )
            },
            partitionKeys = relation.partitionKeys.orEmpty(),
            clusteringKeys = relation.clusteringKeys.orEmpty(),
            requiresPartitionFilter = relation.requiresPartitionFilter,
            primaryKey = primaryKey,
            foreignKeys = foreignKeys,
        )
    }

    private suspend fun readCurrent(workspaceId: String, connectorId: String): CatalogCurrentObjectSet? =
        transactionPort.inReadOnlyTransaction {
            currentObjectsReadPort.readCurrent(
                WorkspaceId(Uuid.parse(workspaceId)),
                ConnectorId(Uuid.parse(connectorId)),
            )
        }

    private fun queryMatches(value: String?, query: String?): Boolean =
        query.isNullOrBlank() || (value?.contains(query.trim(), ignoreCase = true) == true)

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        require(page > 0)
        require(pageSize > 0)
        val from = ((page - 1).toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
        return subList(from, to)
    }

    private companion object {
        const val DEFAULT_PAGE: Int = 1
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
