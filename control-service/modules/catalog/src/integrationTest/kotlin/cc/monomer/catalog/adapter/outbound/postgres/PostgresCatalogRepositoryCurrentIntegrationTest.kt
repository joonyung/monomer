@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogListRefreshJobId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogRelationshipKind
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionState
import cc.monomer.catalog.api.ConnectorCatalogSelection
import cc.monomer.catalog.api.ConnectorCatalogSelectionState
import cc.monomer.catalog.api.CatalogStableObjectIdentityCodec
import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobState
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskState
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.application.CATALOG_INGEST_JOB_TASK_PROJECTION_LIMIT
import cc.monomer.catalog.domain.CatalogListOption
import cc.monomer.catalog.domain.CatalogListOptionRead
import cc.monomer.catalog.domain.CatalogListChildKind
import cc.monomer.catalog.domain.CatalogListRefreshJob
import cc.monomer.catalog.domain.CatalogListRefreshJobState
import cc.monomer.catalog.domain.CatalogListRefreshTarget
import cc.monomer.catalog.domain.CatalogMetadataObservation
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ExecutionRunId
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.UndeclaredThrowableException
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@ExtendWith(TargetCatalogPostgres::class)
class PostgresCatalogRepositoryCurrentIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var transactions: TransactionTemplate
    private lateinit var readTransactions: TransactionTemplate
    private lateinit var selections: PostgresCatalogSelectionRepository
    private lateinit var ingest: PostgresCatalogIngestRepository
    private lateinit var listRefresh: PostgresCatalogListRefreshRepository
    private lateinit var connectorState: PostgresCatalogConnectorStateRepository
    private lateinit var activity: PostgresCatalogActivityReadPort
    private lateinit var currentObjects: CatalogCurrentObjectsReadPort
    private var workspaceId: WorkspaceId = WorkspaceId(Uuid.generateV7())
    private var connectorId: ConnectorId = ConnectorId(Uuid.generateV7())
    private var requestedBy: PrincipalId = PrincipalId(Uuid.generateV7())
    private val observedAt = Instant.parse("2026-08-18T00:00:00Z")

    private val projectedFields = java.util.Collections.synchronizedList(mutableListOf<cc.monomer.catalog.api.CatalogSearchFields>())
    private val deletedAddresses = java.util.Collections.synchronizedList(mutableListOf<cc.monomer.catalog.api.CatalogSearchAddress>())
    private var rejectProjection = false
    private val searchProjection = object : cc.monomer.catalog.api.CatalogSearchProjectionPort {
        override fun upsert(fields: cc.monomer.catalog.api.CatalogSearchFields) {
            check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            check(!rejectProjection) { "Search projection failed" }
            projectedFields.add(fields)
        }
        override fun deleteSubtree(workspaceId: WorkspaceId, address: cc.monomer.catalog.api.CatalogSearchAddress) {
            check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            check(!rejectProjection) { "Search projection failed" }
            deletedAddresses.add(address)
        }
    }

    @BeforeEach
    fun setUp() {
        projectedFields.clear()
        deletedAddresses.clear()
        rejectProjection = false
        dataSource = TargetCatalogPostgres.dataSource
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        readTransactions = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
        }
        selections = PostgresCatalogSelectionRepository(dataSource, ObjectMapper(), searchProjection)
        ingest = PostgresCatalogIngestRepository(dataSource, ObjectMapper(), searchProjection, selections)
        listRefresh = PostgresCatalogListRefreshRepository(dataSource, selections)
        connectorState = PostgresCatalogConnectorStateRepository(dataSource, searchProjection)
        val activityTransactionManager = DataSourceTransactionManager(dataSource)
        activity = PostgresCatalogActivityReadPort(
            dataSource,
            SpringCatalogTransactionPort(
                dataSource,
                TransactionTemplate(activityTransactionManager).apply { timeout = 5 },
                TransactionTemplate(activityTransactionManager).apply {
                    isReadOnly = true
                    isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                    timeout = 5
                },
                Dispatchers.IO,
                CatalogTransactionTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(1)),
            ),
        )
        currentObjects = PostgresCatalogCurrentObjectsReadPort(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE catalog.selection_options, catalog.selection_option_lists, catalog.list_refresh_jobs, " +
                        "catalog.current_objects, catalog.ingest_tasks, catalog.ingest_jobs, catalog.selections, " +
                        "catalog.connector_catalog_selections CASCADE",
                )
            }
        }
        workspaceId = WorkspaceId(Uuid.generateV7())
        connectorId = ConnectorId(Uuid.generateV7())
        requestedBy = PrincipalId(Uuid.generateV7())
    }

    @Test
    fun `owner reads require the owner transaction while overview activity opens its read transaction`() = runBlocking<Unit> {
        val job = newJob().also { tx { ingest.insertIngestJob(it) } }

        val outside = assertFailsWith<IllegalStateException> { ingest.findIngestJob(job.id) }
        assertTrue(outside.message.orEmpty().contains("owner transaction"))
        assertFailsWith<IllegalStateException> { ingest.findUnownedRunningIngestJobs(10) }
        assertEquals(job, read { ingest.findIngestJob(job.id) })
        assertEquals(
            listOf(job.id.value),
            activity.readActivity(workspaceId, false, true, null, null, null, null, null, 10, emptySet()).map { it.id },
        )
    }

    @Test
    fun `catalog jobs keep the agent app, web jobs keep none, and activity filters by app`() = runBlocking<Unit> {
        val claude = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
        val codex = cc.monomer.identity.api.AgentClientId("monomer-native")
        fun job(entry: CatalogIngestEntry, surface: cc.monomer.identity.api.CredentialSurface, client: cc.monomer.identity.api.AgentClientId?) =
            newJob(entry = entry).copy(credentialSurface = surface, agentClientId = client).also { tx { ingest.insertIngestJob(it) } }
        val claudeJob = job(CatalogIngestEntry.Connector(connectorId), cc.monomer.identity.api.CredentialSurface.AGENT, claude)
        val codexJob = job(CatalogIngestEntry.Catalog(connectorId, "a"), cc.monomer.identity.api.CredentialSurface.AGENT, codex)
        val webJob = job(CatalogIngestEntry.Catalog(connectorId, "b"), cc.monomer.identity.api.CredentialSurface.WEB, null)
        assertEquals(claude, read { ingest.findIngestJob(claudeJob.id) }?.agentClientId)
        assertEquals(null, read { ingest.findIngestJob(webJob.id) }?.agentClientId)
        val byApp = activity.readActivity(workspaceId, false, true, null, null, null, cc.monomer.identity.api.CredentialSurface.AGENT, codex, 10, emptySet())
        assertEquals(listOf(codexJob.id.value), byApp.map { it.id })
        assertEquals(codex, byApp.single().agentClientId)
        assertEquals(2, activity.readActivity(workspaceId, false, true, null, null, null, cc.monomer.identity.api.CredentialSurface.AGENT, null, 10, emptySet()).size)
        assertTrue(activity.readRecentAgentClients(workspaceId, false, 500).isEmpty())
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE catalog.ingest_jobs SET state = 'COMPLETED', terminal_at = now() WHERE workspace_id = '${workspaceId.value}'") }
        }
        assertEquals(setOf(claude, codex), activity.readRecentAgentClients(workspaceId, false, 500).map { it.clientId }.toSet())
        assertTrue(activity.readRecentAgentClients(workspaceId, true, 500).isEmpty())
        val listJob = listJob(CatalogListRefreshTarget.Connector(CatalogListChildKind.CATALOG))
            .copy(credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT, agentClientId = claude)
        insertListJob(listJob)
        assertEquals(claude, read { listRefresh.findListRefreshJob(listJob.id) }?.agentClientId)
        assertEquals(listOf(listJob.id.value), activity.readActivity(workspaceId, true, true, null, null, null, null, claude, 10, emptySet()).map { it.id })
        assertFailsWith<IllegalArgumentException> { webJob.copy(agentClientId = claude) }
    }

    @Test
    fun `search selection includes unobserved catalogs and excludes other workspaces`() {
        val selection = ConnectorCatalogSelectionState(workspaceId, connectorId,
            ConnectorCatalogSelection(setOf("한글/warehouse~")), requestedBy, observedAt)
        tx { selections.saveConnectorCatalogSelection(selection) }
        val reads = PostgresCatalogSearchReadPort(dataSource)
        val address = cc.monomer.catalog.api.CatalogSearchAddress(connectorId, "한글/warehouse~", null, null)
        assertEquals(listOf("한글/warehouse~"), projectedFields.single().primaryNames)
        assertEquals(projectedFields.toList(), tx { reads.inventory() })
        assertEquals(listOf(address), tx { reads.readCurrentListings(workspaceId, setOf(address)) }.map { it.address })
        assertTrue(tx { reads.readCurrentListings(WorkspaceId(Uuid.generateV7()), setOf(address)) }.isEmpty())
        tx { selections.saveConnectorCatalogSelection(selection.copy(selection = ConnectorCatalogSelection(emptySet()))) }
        assertEquals(listOf(address), deletedAddresses.toList())
        assertTrue(tx { reads.inventory() }.isEmpty())
        assertTrue(tx { reads.readCurrentListings(workspaceId, setOf(address)) }.isEmpty())
    }

    @Test
    fun `search projection failure rolls back source selection and relation completion`() {
        rejectProjection = true
        assertFailsWith<IllegalStateException> {
            tx { selections.saveConnectorCatalogSelection(ConnectorCatalogSelectionState(workspaceId, connectorId,
                ConnectorCatalogSelection(setOf("warehouse")), requestedBy, observedAt)) }
        }
        assertEquals(null, read { selections.currentConnectorCatalogSelection(workspaceId, connectorId) })
        rejectProjection = false
        val job = admitAndClaim()
        val entry = read { ingest.findIngestTasks(job.id) }.single()
        val schema = insertTask(job, CatalogIngestTaskTarget.Schema(null, "analytics"), entry.id)
        tx { ingest.applyContainer(schema, listOf(CatalogMetadataObservation.Schema(null, "analytics")),
            emptyList(), "runner-a", observedAt) }
        val relation = insertTask(job, CatalogIngestTaskTarget.Relation(null, "analytics", "orders", RelationKind.TABLE), schema.id)
        rejectProjection = true
        assertFailsWith<IllegalStateException> {
            tx { ingest.completeRelationTask(relation,
                listOf(CatalogMetadataObservation.Relation(null, "analytics", "orders", RelationKind.TABLE, "sensitive SQL",
                    null, null, null,
                )),
                emptyList(), emptyList(), "runner-a", observedAt) }
        }
        assertEquals(CatalogIngestTaskState.RUNNING, read { ingest.findIngestTask(relation.id) }?.state)
        assertTrue(tx { PostgresCatalogSearchReadPort(dataSource).inventory() }.none { it.kind == CatalogObjectKind.RELATION })
    }

    @Test
    fun `claim creates the entry task and metadata rows retain current object identity`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()

        assertEquals(CatalogIngestTaskTarget.Connector, entryTask.target)
        assertEquals(CatalogIngestTaskState.RUNNING, entryTask.state)

        val catalogTask = insertTask(claimed, CatalogIngestTaskTarget.Catalog("warehouse"), entryTask.id)
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            catalogTask.id,
        )
        val relationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.applyContainer(
                catalogTask,
                listOf(CatalogMetadataObservation.Catalog("warehouse")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                relationTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
        }

        val current = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(current)
        assertTrue(current.objects.all { it.contentDigest.value.startsWith("sha256:") })
        assertEquals(3, current.objects.size)
    }

    @Test
    fun `current catalog schema and relation compound addresses are unique`() {
        insertCurrentObject(CatalogObjectKind.CATALOG, "warehouse", null, null, null)
        assertFailsWith<SQLException> {
            insertCurrentObject(CatalogObjectKind.CATALOG, "warehouse", null, null, null)
        }

        insertCurrentObject(CatalogObjectKind.SCHEMA, null, "analytics", null, null)
        assertFailsWith<SQLException> {
            insertCurrentObject(CatalogObjectKind.SCHEMA, null, "analytics", null, null)
        }

        insertCurrentObject(CatalogObjectKind.RELATION, null, "analytics", "orders", RelationKind.TABLE)
        assertFailsWith<SQLException> {
            insertCurrentObject(CatalogObjectKind.RELATION, null, "analytics", "orders", RelationKind.VIEW)
        }
    }

    @Test
    fun `current Relation search filters and continues from the owner cursor`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val catalogTask = insertTask(claimed, CatalogIngestTaskTarget.Catalog("warehouse"), entryTask.id)
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            catalogTask.id,
        )
        val relationTasks = listOf("z_orders", "organizations", "orders").map { relationName ->
            insertTask(
                claimed,
                CatalogIngestTaskTarget.Relation(
                    "warehouse",
                    "analytics",
                    relationName,
                    RelationKind.TABLE,
                ),
                schemaTask.id,
            )
        }
        tx {
            ingest.applyContainer(
                catalogTask,
                listOf(CatalogMetadataObservation.Catalog("warehouse")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            relationTasks.forEach { relationTask ->
                val relationName = (relationTask.target as CatalogIngestTaskTarget.Relation).name
                ingest.completeRelationTask(
                    relationTask,
                    listOf(
                        CatalogMetadataObservation.Relation(
                            "warehouse",
                            "analytics",
                            relationName,
                            RelationKind.TABLE,
                            null,
                            null, null, null,
                        ),
                    ),
                    emptyList(),
                    emptyList(),
                    "runner-a",
                    observedAt,
                )
            }
        }

        val firstPage = tx { currentObjects.searchCurrentRelations(workspaceId, "ORDERS", null, 1) }
        assertEquals(listOf("warehouse.analytics.orders"), firstPage.items.map { it.qualifiedName })
        val cursor = assertNotNull(firstPage.nextCursor)

        val secondPage = tx { currentObjects.searchCurrentRelations(workspaceId, "orders", cursor, 1) }
        assertEquals(listOf("warehouse.analytics.z_orders"), secondPage.items.map { it.qualifiedName })
        assertEquals(null, secondPage.nextCursor)
    }

    @Test
    fun `current rows preserve relation column and primary or foreign key metadata`() = verifyRelationMetadata("warehouse")

    @Test
    fun `two-depth current rows preserve null catalog identities and PK FK metadata across child removal`() = verifyRelationMetadata(null)

    private fun verifyRelationMetadata(catalogName: String?) {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()

        val catalogTask = catalogName?.let { insertTask(claimed, CatalogIngestTaskTarget.Catalog(it), entryTask.id) }
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema(catalogName, "analytics"),
            catalogTask?.id ?: entryTask.id,
        )
        val relationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation(catalogName, "analytics", "orders", RelationKind.VIEW),
            schemaTask.id,
        )
        tx {
            if (catalogTask != null) {
                ingest.applyContainer(
                    catalogTask,
                    listOf(CatalogMetadataObservation.Catalog(requireNotNull(catalogName))),
                    emptyList(),
                    "runner-a",
                    observedAt,
                )
            }
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema(catalogName, "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                relationTask,
                listOf(CatalogMetadataObservation.Relation(
                    catalogName,
                    "analytics",
                    "orders",
                    RelationKind.VIEW,
                    "SELECT * FROM raw_orders",
                    null, null, null,
                )),
                listOf(
                    CatalogMetadataObservation.Column(
                        catalogName,
                        "analytics",
                        "orders",
                        "id",
                        1,
                        "integer",
                        false,
                        "nextval('orders_id_seq'::regclass)",
                    ),
                    CatalogMetadataObservation.Column(
                        catalogName,
                        "analytics",
                        "orders",
                        "customer_id",
                        2,
                        "integer",
                        true,
                        null,
                    ),
                ),
                listOf(
                    CatalogMetadataObservation.RelationshipMember(
                    catalogName,
                    "analytics",
                    "orders",
                    "orders_pkey",
                    cc.monomer.catalog.api.CatalogRelationshipKind.PRIMARY_KEY,
                    1,
                    "id",
                    null,
                    null,
                    null,
                    null,
                    ),
                    CatalogMetadataObservation.RelationshipMember(
                    catalogName,
                    "analytics",
                    "orders",
                    "orders_pkey",
                    cc.monomer.catalog.api.CatalogRelationshipKind.PRIMARY_KEY,
                    2,
                    "customer_id",
                    null,
                    null,
                    null,
                    null,
                    ),
                    CatalogMetadataObservation.RelationshipMember(
                    catalogName,
                    "analytics",
                    "orders",
                    "orders_customer_fkey",
                    cc.monomer.catalog.api.CatalogRelationshipKind.FOREIGN_KEY,
                    1,
                    "customer_id",
                    catalogName,
                    "analytics",
                    "customers",
                    "id",
                    ),
                ),
                "runner-a",
                observedAt,
            )
        }

        val current = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(current)
        val relation = current.objects.single { it.kind == cc.monomer.catalog.api.CatalogObjectKind.RELATION }
        val projection = projectedFields.single { it.kind == CatalogObjectKind.RELATION }
        assertEquals(listOf("orders"), projection.primaryNames)
        assertEquals(listOfNotNull(catalogName, "analytics", "VIEW", "id", "customer_id"), projection.structuralNames)
        assertEquals(projection, tx { PostgresCatalogSearchReadPort(dataSource).inventory() }.single { it.kind == CatalogObjectKind.RELATION })
        val listing = tx { PostgresCatalogSearchReadPort(dataSource).readCurrentListings(workspaceId, setOf(projection.address)) }.single()
        assertEquals(RelationKind.VIEW, listing.relationKind)
        assertEquals(observedAt, listing.updatedAt)
        assertEquals(catalogName, relation.address.catalogName)
        assertEquals(
            CatalogStableObjectIdentityCodec.derive(connectorId, CatalogObjectKind.RELATION, catalogName, "analytics", "orders", null, null).objectId,
            relation.id,
        )
        assertTrue(current.objects.all { it.address.catalogName == catalogName })
        val relationObjectId = relation.id
        val relationIdentityDigest = relation.identityDigest
        assertEquals(RelationKind.VIEW, relation.relationKind)
        assertEquals("SELECT * FROM raw_orders", relation.definition)

        val columns = current.objects.filter { it.kind == cc.monomer.catalog.api.CatalogObjectKind.COLUMN }
            .sortedBy { it.ordinal }
        assertEquals(listOf(1, 2), columns.map { it.ordinal })
        assertEquals(listOf(false, true), columns.map { it.nullable })
        assertEquals("nextval('orders_id_seq'::regclass)", columns.first().defaultExpression)
        assertEquals(null, columns.last().defaultExpression)

        val primaryKeys = current.objects.filter { it.constraintName == "orders_pkey" }
            .sortedBy { it.memberOrdinal }
        assertEquals(2, primaryKeys.size)
        assertEquals(CatalogRelationshipKind.PRIMARY_KEY, primaryKeys.first().relationshipKind)
        assertEquals(listOf(1, 2), primaryKeys.map { it.memberOrdinal })
        assertEquals(listOf("id", "customer_id"), primaryKeys.map { it.sourceColumn })

        val foreignKey = current.objects.single { it.constraintName == "orders_customer_fkey" }
        assertEquals(CatalogRelationshipKind.FOREIGN_KEY, foreignKey.relationshipKind)
        assertEquals("customer_id", foreignKey.sourceColumn)
        assertEquals(catalogName, foreignKey.referencedCatalog)
        assertEquals("analytics", foreignKey.referencedSchema)
        assertEquals("customers", foreignKey.referencedRelation)
        assertEquals("id", foreignKey.referencedColumn)

        tx {
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema(catalogName, "analytics")),
                listOf(CatalogIngestTaskTarget.Relation(catalogName, "analytics", "orders", RelationKind.TABLE)),
                "runner-a",
                observedAt.plusSeconds(1),
            )
        }
        val afterKindDiscovery = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(afterKindDiscovery)
        assertEquals(
            RelationKind.VIEW,
            afterKindDiscovery.objects.single { it.kind == CatalogObjectKind.RELATION }.relationKind,
        )

        val secondRelationTask = CatalogIngestTask.start(
            claimed,
            schemaTask.id,
            CatalogIngestTaskTarget.Relation(catalogName, "analytics", "orders", RelationKind.TABLE),
            observedAt.plusSeconds(1),
        )
        tx {
            checkNotNull(ingest.findOrInsertOwnedIngestTask(secondRelationTask, "runner-a"))
            ingest.completeRelationTask(
                secondRelationTask,
                listOf(
                    CatalogMetadataObservation.Relation(
                        catalogName,
                        "analytics",
                        "orders",
                        RelationKind.TABLE,
                        null,
                        null, null, null,
                    ),
                ),
                listOf(
                CatalogMetadataObservation.Column(
                    catalogName,
                    "analytics",
                    "orders",
                    "id",
                    1,
                    "integer",
                    false,
                    "nextval('orders_id_seq'::regclass)",
                ),
                ),
                emptyList(),
                "runner-a",
                observedAt.plusSeconds(1),
            )
        }

        val afterRemoval = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(afterRemoval)
        val currentRelations = afterRemoval.objects.filter { it.kind == CatalogObjectKind.RELATION }
        assertEquals(1, currentRelations.size)
        assertEquals(RelationKind.TABLE, currentRelations.single().relationKind)
        assertEquals(relationObjectId, currentRelations.single().id)
        assertEquals(relationIdentityDigest, currentRelations.single().identityDigest)
        assertEquals(null, currentRelations.single().definition)
        assertEquals(
            listOf("id"),
            afterRemoval.objects.filter { it.kind == CatalogObjectKind.COLUMN }.map { it.address.memberName },
        )
        assertTrue(afterRemoval.objects.none { it.kind == CatalogObjectKind.RELATIONSHIP })
    }

    @Test
    fun `complete relation absence deletes rows and reappearance retains the same identity`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            entryTask.id,
        )
        val firstRelationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                firstRelationTask,
                listOf(CatalogMetadataObservation.Relation(
                    "warehouse",
                    "analytics",
                    "orders",
                    RelationKind.TABLE,
                    null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
        }
        val initial = checkNotNull(tx { currentObjects.readCurrent(workspaceId, connectorId) })
        val relationId = initial.objects.single { it.kind == CatalogObjectKind.RELATION }.id

        val missingRelationTask = CatalogIngestTask.start(
            claimed,
            schemaTask.id,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.UNKNOWN),
            observedAt.plusSeconds(1),
        )
        tx {
            checkNotNull(ingest.findOrInsertOwnedIngestTask(missingRelationTask, "runner-a"))
            ingest.completeRelationTask(
                missingRelationTask,
                emptyList(),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt.plusSeconds(1),
            )
        }

        assertEquals(1, countCurrentObjects("warehouse"))
        assertTrue(checkNotNull(tx { currentObjects.readCurrent(workspaceId, connectorId) })
            .objects.none { it.kind == CatalogObjectKind.RELATION })
        assertEquals(CatalogIngestTaskState.SUCCEEDED, read { ingest.findIngestTask(missingRelationTask.id) }?.state)
        assertTrue(deletedAddresses.any { it.relationName == "orders" && it.schemaName == "analytics" })
        assertTrue(tx { PostgresCatalogSearchReadPort(dataSource).inventory() }.none { it.kind == CatalogObjectKind.RELATION })

        val reappearedRelationTask = CatalogIngestTask.start(
            claimed,
            schemaTask.id,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.VIEW),
            observedAt.plusSeconds(2),
        )
        tx {
            checkNotNull(ingest.findOrInsertOwnedIngestTask(reappearedRelationTask, "runner-a"))
            ingest.completeRelationTask(
                reappearedRelationTask,
                listOf(CatalogMetadataObservation.Relation(
                    "warehouse",
                    "analytics",
                    "orders",
                    RelationKind.VIEW,
                    null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt.plusSeconds(2),
            )
        }

        val current = checkNotNull(tx { currentObjects.readCurrent(workspaceId, connectorId) })
        assertEquals(relationId, current.objects.single { it.kind == CatalogObjectKind.RELATION }.id)
    }

    @Test
    fun `relation apply rolls back all rows and task completion when column persistence fails`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val relationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            entryTask.id,
        )

        val failure = assertFailsWith<UndeclaredThrowableException> {
            tx {
                ingest.completeRelationTask(
                    relationTask,
                    listOf(CatalogMetadataObservation.Relation(
                        "warehouse",
                        "analytics",
                        "orders",
                        RelationKind.TABLE,
                        null,
                        null, null, null,
                    )),
                    listOf(CatalogMetadataObservation.Column(
                        "warehouse",
                        "analytics",
                        "orders",
                        "id",
                        0,
                        "integer",
                        false,
                        null,
                    )),
                    emptyList(),
                    "runner-a",
                    observedAt,
                )
            }
        }

        assertTrue(failure.undeclaredThrowable is SQLException)
        assertEquals(
            null,
            transactions.execute { currentObjects.readCurrent(workspaceId, connectorId) },
        )
        assertEquals(
            CatalogIngestTaskState.RUNNING,
            read { ingest.findIngestTask(relationTask.id) }?.state,
        )
    }

    @Test
    fun `relation child observations without a relation are rejected before mutation`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val catalogTask = insertTask(claimed, CatalogIngestTaskTarget.Catalog("warehouse"), entryTask.id)
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            catalogTask.id,
        )
        val seededRelationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.applyContainer(
                catalogTask,
                listOf(CatalogMetadataObservation.Catalog("warehouse")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                seededRelationTask,
                listOf(CatalogMetadataObservation.Relation(
                    "warehouse",
                    "analytics",
                    "orders",
                    RelationKind.TABLE,
                    null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
        }
        val invalidRelationTask = CatalogIngestTask.start(
            claimed,
            schemaTask.id,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.VIEW),
            observedAt.plusSeconds(1),
        )
        tx { checkNotNull(ingest.findOrInsertOwnedIngestTask(invalidRelationTask, "runner-a")) }

        assertFailsWith<IllegalStateException> {
            tx {
                ingest.completeRelationTask(
                    invalidRelationTask,
                    emptyList(),
                    listOf(CatalogMetadataObservation.Column(
                        "warehouse",
                        "analytics",
                        "orders",
                        "id",
                        1,
                        "integer",
                        false,
                        null,
                    )),
                    emptyList(),
                    "runner-a",
                    observedAt,
                )
            }
        }

        val current = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(current)
        assertEquals(
            RelationKind.TABLE,
            current.objects.single { it.kind == CatalogObjectKind.RELATION }.relationKind,
        )
        assertEquals(CatalogIngestTaskState.RUNNING, read { ingest.findIngestTask(invalidRelationTask.id) }?.state)
    }

    @Test
    fun `container removal does not lock or remove relations outside committed selection`() {
        val selection = CatalogSelection(
            selectedSchemas = setOf(
                CatalogSelectedSchema(
                    schemaName = "analytics",
                    relations = CatalogRelationSelection.SelectedRelations(setOf("orders")),
                ),
            ),
        )
        val claimed = admitAndClaim(selection, CatalogIngestEntry.Catalog(connectorId, "warehouse"))
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            entryTask.id,
        )
        val ordersTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        val customersTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "customers", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.completeRelationTask(
                ordersTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                customersTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", "customers", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                listOf(CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE)),
                "runner-a",
                observedAt.plusSeconds(1),
            )
        }

        val current = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(current)
        assertTrue(current.objects.any { it.address.objectName == "orders" })
        assertTrue(current.objects.any { it.address.objectName == "customers" })
    }

    @Test
    fun `two-depth removal deletes a stale selected relation and preserves unselected rows`() {
        val selection = CatalogSelection(
            selectedSchemas = setOf(
                CatalogSelectedSchema(
                    schemaName = "analytics",
                    relations = CatalogRelationSelection.SelectedRelations(setOf("orders")),
                ),
            ),
        )
        val claimed = admitAndClaim(selection, CatalogIngestEntry.Connector(connectorId))
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema(null, "analytics"),
            entryTask.id,
        )
        val ordersTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation(null, "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        val customersTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation(null, "analytics", "customers", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.completeRelationTask(
                ordersTask,
                listOf(CatalogMetadataObservation.Relation(null, "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                customersTask,
                listOf(CatalogMetadataObservation.Relation(null, "analytics", "customers", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema(null, "analytics")),
                emptyList(),
                "runner-a",
                observedAt.plusSeconds(1),
            )
        }

        val current = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(current)
        assertTrue(current.objects.none { it.address.objectName == "orders" })
        assertTrue(current.objects.any { it.address.objectName == "customers" })
    }

    @Test
    fun `removing a selected schema removes all descendants while an unselected sibling remains`() {
        val selection = CatalogSelection(
            selectedSchemas = setOf(
                CatalogSelectedSchema(
                    schemaName = "analytics",
                    relations = CatalogRelationSelection.AllRelations,
                ),
            ),
        )
        val claimed = admitAndClaim(selection, CatalogIngestEntry.Catalog(connectorId, "warehouse"))
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val analyticsTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            entryTask.id,
        )
        val analyticsRelationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            analyticsTask.id,
        )
        val salesTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "sales"),
            entryTask.id,
        )
        val salesRelationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "sales", "customers", RelationKind.TABLE),
            salesTask.id,
        )
        tx {
            ingest.applyContainer(
                analyticsTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                analyticsRelationTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                listOf(CatalogMetadataObservation.Column(
                    "warehouse",
                    "analytics",
                    "orders",
                    "id",
                    1,
                    "integer",
                    false,
                    null,
                )),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                salesTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "sales")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                salesRelationTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "sales", "customers", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                entryTask,
                emptyList(),
                listOf(CatalogIngestTaskTarget.Schema("warehouse", "sales")),
                "runner-a",
                observedAt.plusSeconds(1),
            )
        }

        val current = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(current)
        assertTrue(current.objects.none { it.address.schemaName == "analytics" })
        assertTrue(current.objects.any {
            it.address.schemaName == "sales" && it.address.objectName == "customers"
        })
    }

    @Test
    fun `two-depth removal deletes a selected null-catalog schema subtree and preserves an unselected sibling`() {
        val selection = CatalogSelection(
            selectedSchemas = setOf(
                CatalogSelectedSchema(
                    schemaName = "analytics",
                    relations = CatalogRelationSelection.AllRelations,
                ),
            ),
        )
        val claimed = admitAndClaim(selection, CatalogIngestEntry.Connector(connectorId))
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val analyticsTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema(null, "analytics"),
            entryTask.id,
        )
        val analyticsRelationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation(null, "analytics", "orders", RelationKind.TABLE),
            analyticsTask.id,
        )
        val salesTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema(null, "sales"),
            entryTask.id,
        )
        val salesRelationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation(null, "sales", "customers", RelationKind.TABLE),
            salesTask.id,
        )
        tx {
            ingest.applyContainer(
                analyticsTask,
                listOf(CatalogMetadataObservation.Schema(null, "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                analyticsRelationTask,
                listOf(CatalogMetadataObservation.Relation(null, "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                listOf(CatalogMetadataObservation.Column(
                    null,
                    "analytics",
                    "orders",
                    "id",
                    1,
                    "integer",
                    false,
                    null,
                )),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                salesTask,
                listOf(CatalogMetadataObservation.Schema(null, "sales")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                salesRelationTask,
                listOf(CatalogMetadataObservation.Relation(null, "sales", "customers", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                entryTask,
                emptyList(),
                listOf(CatalogIngestTaskTarget.Schema(null, "sales")),
                "runner-a",
                observedAt.plusSeconds(1),
            )
        }

        val current = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(current)
        assertTrue(current.objects.none { it.address.schemaName == "analytics" })
        assertTrue(current.objects.any {
            it.address.schemaName == "sales" && it.address.objectName == "customers"
        })
    }

    @Test
    fun `current read transaction does not block an existing relation update`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val catalogTask = insertTask(claimed, CatalogIngestTaskTarget.Catalog("warehouse"), entryTask.id)
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            catalogTask.id,
        )
        val seededRelationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.applyContainer(
                catalogTask,
                listOf(CatalogMetadataObservation.Catalog("warehouse")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                seededRelationTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
        }
        val updateTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.VIEW),
            entryTask.id,
        )
        val readReady = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val readFailure = AtomicReference<Throwable?>()
        val writeFinished = CountDownLatch(1)
        val writeFailure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit {
                try {
                    transactions.execute {
                        checkNotNull(currentObjects.readCurrent(workspaceId, connectorId))
                        readReady.countDown()
                        check(releaseRead.await(5, SECONDS)) { "reader release timed out" }
                    }
                } catch (failure: Throwable) {
                    readFailure.set(failure)
                    readReady.countDown()
                } finally {
                    readFinished.countDown()
                }
            }
            assertTrue(readReady.await(5, SECONDS), "current read did not materialize")
            readFailure.get()?.let { throw it }

            pool.submit {
                try {
                    transactions.execute {
                        ingest.completeRelationTask(
                            updateTask,
                            listOf(CatalogMetadataObservation.Relation(
                                "warehouse",
                                "analytics",
                                "orders",
                                RelationKind.TABLE,
                                null,
                                null, null, null,
                            )),
                            emptyList(),
                            emptyList(),
                            "runner-a",
                            observedAt.plusSeconds(1),
                        )
                    }
                } catch (failure: Throwable) {
                    writeFailure.set(failure)
                } finally {
                    writeFinished.countDown()
                }
            }
            assertTrue(writeFinished.await(2, SECONDS), "current-object writer waited for browse read")
            writeFailure.get()?.let { throw it }
        } finally {
            releaseRead.countDown()
            assertTrue(readFinished.await(5, SECONDS), "browse read did not finish")
            pool.shutdownNow()
            pool.awaitTermination(5, SECONDS)
        }
    }

    @Test
    fun `distinct current relation tasks do not convoy on the ingest job row`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val catalogTask = insertTask(claimed, CatalogIngestTaskTarget.Catalog("warehouse"), entryTask.id)
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            catalogTask.id,
        )
        val seededOrdersTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        val seededCustomersTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "customers", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.applyContainer(
                catalogTask,
                listOf(CatalogMetadataObservation.Catalog("warehouse")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                seededOrdersTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                seededCustomersTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", "customers", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
        }
        val ordersTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.VIEW),
            entryTask.id,
        )
        val customersTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "customers", RelationKind.VIEW),
            entryTask.id,
        )
        val firstApplied = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>()
        val secondFinished = CountDownLatch(1)
        val secondFailure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit {
                try {
                    transactions.execute {
                        ingest.completeRelationTask(
                            ordersTask,
                            listOf(CatalogMetadataObservation.Relation(
                                "warehouse",
                                "analytics",
                                "orders",
                                RelationKind.TABLE,
                                null,
                                null, null, null,
                            )),
                            emptyList(),
                            emptyList(),
                            "runner-a",
                            observedAt.plusSeconds(1),
                        )
                        firstApplied.countDown()
                        check(releaseFirst.await(5, SECONDS)) { "first transaction release timed out" }
                    }
                } catch (failure: Throwable) {
                    firstFailure.set(failure)
                    firstApplied.countDown()
                } finally {
                    firstFinished.countDown()
                }
            }
            assertTrue(firstApplied.await(5, SECONDS), "first relation apply did not start")
            firstFailure.get()?.let { throw it }

            pool.submit {
                try {
                    transactions.execute {
                        ingest.completeRelationTask(
                            customersTask,
                            listOf(CatalogMetadataObservation.Relation(
                                "warehouse",
                                "analytics",
                                "customers",
                                RelationKind.TABLE,
                                null,
                                null, null, null,
                            )),
                            emptyList(),
                            emptyList(),
                            "runner-a",
                            observedAt.plusSeconds(1),
                        )
                    }
                } catch (failure: Throwable) {
                    secondFailure.set(failure)
                } finally {
                    secondFinished.countDown()
                }
            }
            assertTrue(secondFinished.await(2, SECONDS), "distinct relation tasks convoyed on the job row")
            secondFailure.get()?.let { throw it }
        } finally {
            releaseFirst.countDown()
            assertTrue(firstFinished.await(5, SECONDS), "first relation apply did not finish")
            pool.shutdownNow()
            pool.awaitTermination(5, SECONDS)
        }
    }

    @Test
    fun `active run link waits for a cancel holding the job and then refuses the link`() {
        val claimed = admitAndClaim()
        val task = read { ingest.findIngestTasks(claimed.id) }.single()
        val cancelLocked = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val cancel = pool.submit<Unit> {
                tx {
                    val locked = checkNotNull(ingest.lockIngestJob(claimed.id))
                    check(ingest.updateIngestJob(locked.requestCancellation()))
                    cancelLocked.countDown()
                    check(releaseCancel.await(5, SECONDS)) { "cancel release timed out" }
                }
            }
            assertTrue(cancelLocked.await(5, SECONDS), "cancel did not lock the job")
            val link = pool.submit<Boolean> {
                tx { ingest.setActiveRun(task.id, ExecutionRunId.generate(), "runner-a") }
            }
            awaitRowLockWaiterOrDone(link)
            releaseCancel.countDown()
            cancel.get(5, SECONDS)

            assertFalse(link.get(5, SECONDS))
            assertEquals(null, read { ingest.findIngestTasks(claimed.id) }.single().activeRunId)
        } finally {
            releaseCancel.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, SECONDS)
        }
    }

    @Test
    fun `cancel waiting on an active run link observes the linked run`() {
        val claimed = admitAndClaim()
        val task = read { ingest.findIngestTasks(claimed.id) }.single()
        val runId = ExecutionRunId.generate()
        val linked = CountDownLatch(1)
        val releaseLink = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val link = pool.submit<Boolean> {
                tx {
                    ingest.setActiveRun(task.id, runId, "runner-a").also {
                        linked.countDown()
                        check(releaseLink.await(5, SECONDS)) { "link release timed out" }
                    }
                }
            }
            assertTrue(linked.await(5, SECONDS), "active run link did not finish its update")
            val cancelObservation = pool.submit<ExecutionRunId?> {
                transactions.execute {
                    checkNotNull(ingest.lockIngestJob(claimed.id))
                    ingest.findIngestTasks(claimed.id).single().activeRunId
                }
            }
            awaitRowLockWaiterOrDone(cancelObservation)
            releaseLink.countDown()

            assertTrue(link.get(5, SECONDS))
            assertEquals(runId, cancelObservation.get(5, SECONDS))
        } finally {
            releaseLink.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, SECONDS)
        }
    }

    private fun awaitRowLockWaiterOrDone(work: java.util.concurrent.Future<*>) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (!work.isDone) {
            val waiting = dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    ).use { result ->
                        result.next()
                        result.getInt(1)
                    }
                }
            }
            if (waiting > 0) return
            check(System.nanoTime() < deadline) { "concurrent Catalog work neither waited on a row lock nor finished" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `connector lock serializes Catalog option replacement with membership writes`() {
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>()
        val secondLocked = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val secondFailure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit {
                try {
                    transactions.execute {
                        listRefresh.lockConnector(workspaceId, connectorId)
                        firstLocked.countDown()
                        check(releaseFirst.await(5, SECONDS)) { "first connector transaction release timed out" }
                    }
                } catch (failure: Throwable) {
                    firstFailure.set(failure)
                    firstLocked.countDown()
                } finally {
                    firstFinished.countDown()
                }
            }
            assertTrue(firstLocked.await(5, SECONDS), "first connector lock was not acquired")
            firstFailure.get()?.let { throw it }

            pool.submit {
                try {
                    transactions.execute {
                        selections.lockConnector(workspaceId, connectorId)
                        secondLocked.countDown()
                    }
                } catch (failure: Throwable) {
                    secondFailure.set(failure)
                } finally {
                    secondFinished.countDown()
                }
            }
            assertFalse(secondLocked.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertTrue(secondLocked.await(5, SECONDS), "second connector lock did not resume")
            assertTrue(secondFinished.await(5, SECONDS), "second connector transaction did not finish")
            secondFailure.get()?.let { throw it }
        } finally {
            releaseFirst.countDown()
            assertTrue(firstFinished.await(5, SECONDS), "first connector transaction did not finish")
            pool.shutdownNow()
            pool.awaitTermination(5, SECONDS)
        }
    }

    @Test
    fun `concurrent admissions keep one active job per workspace and entry while terminal history stays reusable`() {
        val sameEntry = CatalogIngestEntry.Connector(connectorId)
        val first = newJob(entry = sameEntry)
        val second = newJob(entry = sameEntry)
        val start = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val firstSucceeded = AtomicBoolean(false)
        val secondSucceeded = AtomicBoolean(false)
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit {
                try {
                    check(start.await(5, SECONDS)) { "first admission start timed out" }
                    transactions.execute {
                        ingest.insertIngestJob(first)
                        firstSucceeded.set(true)
                    }
                } catch (failure: Throwable) {
                    firstFailure.set(failure)
                } finally {
                    firstFinished.countDown()
                }
            }
            pool.submit {
                try {
                    check(start.await(5, SECONDS)) { "second admission start timed out" }
                    transactions.execute {
                        ingest.insertIngestJob(second)
                        secondSucceeded.set(true)
                    }
                } catch (failure: Throwable) {
                    secondFailure.set(failure)
                } finally {
                    secondFinished.countDown()
                }
            }
            start.countDown()
            assertTrue(firstFinished.await(5, SECONDS), "first admission did not finish")
            assertTrue(secondFinished.await(5, SECONDS), "second admission did not finish")
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, SECONDS)
        }

        assertTrue(firstSucceeded.get() xor secondSucceeded.get(), "exactly one active admission must commit")
        assertTrue(
            (firstFailure.get() == null) xor (secondFailure.get() == null),
            "the losing admission must fail on the active unique index",
        )
        val winner = if (firstSucceeded.get()) first else second
        assertEquals(winner.id, read { ingest.findActiveIngestJob(workspaceId, sameEntry.key) }?.id)

        val otherEntry = newJob(entry = CatalogIngestEntry.Catalog(connectorId, "warehouse"))
        val otherWorkspace = WorkspaceId.generate()
        val otherWorkspaceJob = newJob(workspaceId = otherWorkspace, entry = sameEntry)
        tx {
            ingest.insertIngestJob(otherEntry)
            ingest.insertIngestJob(otherWorkspaceJob)
        }
        assertEquals(otherEntry.id, read { ingest.findActiveIngestJob(workspaceId, otherEntry.entry.key) }?.id)
        assertEquals(otherWorkspaceJob.id, read { ingest.findActiveIngestJob(otherWorkspace, sameEntry.key) }?.id)

        tx { check(ingest.updateIngestJob(winner.complete(observedAt.plusSeconds(1)))) }
        assertEquals(null, read { ingest.findActiveIngestJob(workspaceId, sameEntry.key) })

        val terminalizers: List<(CatalogIngestJob) -> CatalogIngestJob> = listOf(
            { job -> job.complete(observedAt.plusSeconds(2)) },
            { job -> job.completeWithErrors(observedAt.plusSeconds(3)) },
            { job ->
                job.claim("runner-a", observedAt.plusSeconds(4))
                    .requestCancellation()
                    .cancel(observedAt.plusSeconds(5))
            },
        )
        val terminalizedJobs = terminalizers.map { terminalize ->
            val admitted = newJob(entry = sameEntry)
            tx { ingest.insertIngestJob(admitted) }
            assertEquals(admitted.id, read { ingest.findActiveIngestJob(workspaceId, sameEntry.key) }?.id)
            val terminalJob = terminalize(admitted)
            tx { check(ingest.updateIngestJob(terminalJob)) }
            assertEquals(null, read { ingest.findActiveIngestJob(workspaceId, sameEntry.key) })
            terminalJob
        }

        val history = read { ingest.findIngestJobPage(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entryKey = sameEntry.key,
            page = 1,
            pageSize = 10,
        ) }
        assertEquals(4, history.totalCount)
        assertEquals(
            setOf(winner.id) + terminalizedJobs.map { it.id }.toSet(),
            history.jobs.map { it.job.id }.toSet(),
        )
    }

    @Test
    fun `legacy admission key cutover preserves history and the active entry constraint`() {
        val claimed = newJob().claim("cutover-fixture", observedAt)
        val history = claimed.complete(observedAt.plusSeconds(1))
        val runningTask = CatalogIngestTask.start(
            job = claimed,
            parentTaskId = null,
            target = CatalogIngestTaskTarget.Connector,
            at = observedAt,
        )
        val historyTask = runningTask.succeed(observedAt.plusSeconds(1))

        tx {
            ingest.insertIngestJob(claimed)
            checkNotNull(ingest.findOrInsertOwnedIngestTask(runningTask, "cutover-fixture"))
            check(ingest.updateOwnedIngestTask(historyTask, "cutover-fixture"))
            check(ingest.updateOwnedIngestJob(history, "cutover-fixture"))
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE catalog.ingest_jobs ADD COLUMN admission_idempotency_key TEXT NOT NULL DEFAULT 'legacy-admission'",
                )
                statement.execute("ALTER TABLE catalog.ingest_jobs ALTER COLUMN admission_idempotency_key DROP DEFAULT")
                statement.execute(
                    "ALTER TABLE catalog.ingest_jobs ADD CONSTRAINT ingest_jobs_legacy_admission_key_not_blank CHECK (btrim(admission_idempotency_key) <> '')",
                )
                statement.execute(
                    "ALTER TABLE catalog.ingest_jobs ADD CONSTRAINT ingest_jobs_legacy_admission_key_unique UNIQUE (workspace_id, admission_idempotency_key)",
                )
            }
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(cutoverScript())
            }
        }

        assertEquals(history, read { ingest.findIngestJob(history.id) })
        assertEquals(historyTask, read { ingest.findIngestTask(historyTask.id) })

        val fresh = newJob()
        tx { ingest.insertIngestJob(fresh) }
        assertEquals(fresh, read { ingest.findIngestJob(fresh.id) })
        assertFailsWith<Exception> {
            tx { ingest.insertIngestJob(newJob()) }
        }
    }

    @Test
    fun `empty selected relation scope completes without touching unselected current Relations`() {
        insertCurrentObject(
            CatalogObjectKind.RELATION,
            "warehouse",
            "analytics",
            "orders",
            RelationKind.TABLE,
        )
        val selection = CatalogSelection(
            setOf(
                CatalogSelectedSchema(
                    "analytics",
                    CatalogRelationSelection.SelectedRelations(emptySet()),
                ),
            ),
        )
        val claimed = admitAndClaim(selection, CatalogIngestEntry.Catalog(connectorId, "warehouse"))
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            entryTask.id,
        )

        tx {
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt.plusSeconds(1),
            )
        }

        assertEquals(1, countCurrentRelations("warehouse", "analytics"))
    }

    @Test
    fun `runner discovery returns unowned and stale jobs without reopening terminal work`() {
        val job = admit()

        assertEquals(listOf(job.id), read { ingest.findUnownedRunningIngestJobs(10) }.map { it.id })
        assertTrue(read { ingest.findStaleIngestJobs(observedAt.plusSeconds(1), 10) }.isEmpty())

        val claimed = checkNotNull(tx { ingest.claimIngestJob(job.id, "runner-a", observedAt) })
        assertTrue(read { ingest.findUnownedRunningIngestJobs(10) }.isEmpty())
        assertEquals(
            listOf(claimed.id),
            read { ingest.findStaleIngestJobs(observedAt.plusSeconds(1), 10) }.map { it.id },
        )

        tx {
            ingest.failStaleIngestJob(claimed.id, observedAt.plusSeconds(120), Duration.ofSeconds(60))
        }
        assertTrue(read { ingest.findStaleIngestJobs(observedAt.plusSeconds(180), 10) }.isEmpty())
    }

    @Test
    fun `entry history aggregates progress and pages failures from durable task rows`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val refreshedTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            entryTask.id,
        )
        val failedTasks = (1..12).map { index ->
            insertTask(
                claimed,
                CatalogIngestTaskTarget.Relation(
                    "warehouse",
                    "analytics",
                    "failed_relation_${index.toString().padStart(2, '0')}",
                    RelationKind.TABLE,
                ),
                entryTask.id,
            )
        }
        tx {
            assertTrue(ingest.updateOwnedIngestTask(refreshedTask.succeed(observedAt.plusSeconds(1)), "runner-a"))
            failedTasks.forEachIndexed { index, task ->
                assertTrue(
                    ingest.updateOwnedIngestTask(
                        task.fail(
                            code = "WAREHOUSE_QUERY_FAILED",
                            message = "Metadata query failed.",
                            at = observedAt.plusSeconds(index.toLong() + 2),
                            runId = null,
                        ),
                        "runner-a",
                    ),
                )
            }
        }

        val history = read { ingest.findIngestJobPage(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entryKey = CatalogIngestEntry.Connector(connectorId).key,
            page = 1,
            pageSize = 10,
        ) }
        assertEquals(1, history.totalCount)
        assertEquals(1, history.jobs.single().refreshedRelationCount)
        assertEquals(1, history.jobs.single().runningTaskCount)
        assertEquals(12, history.jobs.single().errorCount)
        val keysetHistory = read { ingest.findIngestJobsAfter(
            workspaceId,
            connectorId,
            CatalogIngestEntry.Connector(connectorId).key,
            null,
            null,
            2,
        ) }
        assertEquals(listOf(claimed.id), keysetHistory.map { it.job.id })
        assertEquals(12, keysetHistory.single().errorCount)

        val summary = checkNotNull(read { ingest.findIngestJobSummary(claimed.id) })
        assertEquals(1, summary.refreshedRelationCount)
        assertEquals(1, summary.runningTaskCount)
        assertEquals(12, summary.errorCount)
        assertEquals(listOf(entryTask.id), read { ingest.findRunningIngestTasks(claimed.id) }.map { it.id })

        val firstErrors = read { ingest.findIngestErrorPage(claimed.id, 1, 10) }
        val secondErrors = read { ingest.findIngestErrorPage(claimed.id, 2, 10) }
        assertEquals(12, firstErrors.totalCount)
        assertEquals(10, firstErrors.errors.size)
        assertEquals(2, secondErrors.errors.size)
        assertEquals(1, firstErrors.page)
        assertEquals(2, secondErrors.page)
        assertTrue(read { ingest.findIngestErrorPage(claimed.id, Int.MAX_VALUE, 200) }.errors.isEmpty())
        assertTrue(read { ingest.findIngestJobPage(workspaceId, connectorId, CatalogIngestEntry.Connector(connectorId).key, Int.MAX_VALUE, 200) }.jobs.isEmpty())

        assertTrue((firstErrors.errors + secondErrors.errors).all { it.safeMessage == "Metadata query failed." })
        val firstKeysetErrors = read { ingest.findIngestErrorsAfter(claimed.id, null, null, 10) }
        val lastFirstKeysetError = firstKeysetErrors.last()
        val secondKeysetErrors = read { ingest.findIngestErrorsAfter(
            claimed.id,
            lastFirstKeysetError.terminalAt,
            lastFirstKeysetError.id,
            10,
        ) }
        assertEquals(firstErrors.errors + secondErrors.errors, firstKeysetErrors + secondKeysetErrors)
    }

    @Test
    fun `connector summaries count selection and tables and report the latest and last good refresh`() {
        val entry = CatalogIngestEntry.Catalog(connectorId, "warehouse")
        tx {
            selections.saveConnectorCatalogSelection(
                ConnectorCatalogSelectionState(workspaceId, connectorId, ConnectorCatalogSelection(setOf("warehouse", "raw")), requestedBy, observedAt),
            )
        }
        saveCatalogSelection("warehouse")
        insertCurrentObject(CatalogObjectKind.SCHEMA, "warehouse", "analytics", null, null)
        insertCurrentObject(CatalogObjectKind.RELATION, "warehouse", "analytics", "orders", RelationKind.TABLE)
        insertCurrentObject(CatalogObjectKind.RELATION, "warehouse", "analytics", "invoices", RelationKind.VIEW)

        val succeeded = runEntryJob(entry, observedAt.plusSeconds(10), entryFailureCode = null)
        val failed = runEntryJob(entry, observedAt.plusSeconds(60), entryFailureCode = "AUTHENTICATION_FAILED")
        val otherWorkspaceConnector = ConnectorId(Uuid.generateV7())
        tx {
            selections.saveSelection(
                CatalogSelectionState(WorkspaceId(Uuid.generateV7()), otherWorkspaceConnector,
                    CatalogIngestEntry.Connector(otherWorkspaceConnector),
                    CatalogSelection(setOf(CatalogSelectedSchema("public", CatalogRelationSelection.AllRelations))),
                    requestedBy, observedAt),
            )
        }

        val summaries = tx { PostgresCatalogConnectorSummaryReadPort(dataSource).readConnectorSummaries(workspaceId) }

        val summary = summaries.single()
        assertEquals(connectorId, summary.connectorId)
        assertEquals(2, summary.selectedCatalogCount)
        assertEquals(1, summary.selectedSchemaCount)
        assertEquals(2, summary.tableCount)
        val lastRefresh = checkNotNull(summary.lastRefresh)
        assertEquals(failed.id, lastRefresh.jobId)
        assertEquals(cc.monomer.catalog.api.CatalogRefreshState.COMPLETED_WITH_ERRORS, lastRefresh.state)
        assertEquals("warehouse", lastRefresh.catalogName)
        assertEquals(1, lastRefresh.errorCount)
        assertEquals("AUTHENTICATION_FAILED", lastRefresh.entryFailureCode)
        assertEquals(observedAt.plusSeconds(61), lastRefresh.finishedAt)
        assertEquals(observedAt.plusSeconds(11), summary.lastSuccessfulRefreshAt)
        assertTrue(succeeded.id != failed.id)
    }

    @Test
    fun `relation column names are read in ordinal order for the requested relations only`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val schemaTask = insertTask(claimed, CatalogIngestTaskTarget.Schema(null, "analytics"), entryTask.id)
        val relationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation(null, "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema(null, "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                relationTask,
                listOf(CatalogMetadataObservation.Relation(null, "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                listOf(
                    CatalogMetadataObservation.Column(null, "analytics", "orders", "order_id", 1, "bigint", false, null),
                    CatalogMetadataObservation.Column(null, "analytics", "orders", "customer_id", 2, "bigint", false, null),
                ),
                emptyList(),
                "runner-a",
                observedAt,
            )
        }
        val reads = PostgresCatalogSearchReadPort(dataSource)
        val orders = cc.monomer.catalog.api.CatalogSearchAddress(connectorId, null, "analytics", "orders")
        val missing = cc.monomer.catalog.api.CatalogSearchAddress(connectorId, null, "analytics", "missing")

        val columns = tx { reads.readRelationColumnNames(workspaceId, setOf(orders, missing)) }

        assertEquals(mapOf(orders to listOf("order_id", "customer_id")), columns)
    }

    private fun runEntryJob(entry: CatalogIngestEntry, requestedAt: Instant, entryFailureCode: String?): CatalogIngestJob {
        val job = CatalogIngestJob.request(
            id = CatalogIngestJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = CatalogSelection(setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations))),
            entry = entry,
            requestedBy = requestedBy,
            requestedAt = requestedAt,
            credentialSurface = null,
            agentClientId = null,
        )
        tx { ingest.insertIngestJob(job) }
        val claimed = checkNotNull(tx { ingest.claimIngestJob(job.id, "runner-a", requestedAt) })
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val finishedAt = requestedAt.plusSeconds(1)
        tx {
            val terminalTask = if (entryFailureCode == null) entryTask.succeed(finishedAt)
                else entryTask.fail(entryFailureCode, "Sign-in was rejected.", finishedAt, null)
            assertTrue(ingest.updateOwnedIngestTask(terminalTask, "runner-a"))
            val terminalJob = if (entryFailureCode == null) claimed.complete(finishedAt) else claimed.completeWithErrors(finishedAt)
            assertTrue(ingest.updateOwnedIngestJob(terminalJob, "runner-a"))
        }
        return claimed
    }

    @Test
    fun `running task projection stays bounded for wide refresh fanout`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val tasks = (1..(CATALOG_INGEST_JOB_TASK_PROJECTION_LIMIT + 1)).map { index ->
            CatalogIngestTask.start(
                claimed,
                entryTask.id,
                CatalogIngestTaskTarget.Relation(
                    "warehouse",
                    "analytics",
                    "running_relation_${index.toString().padStart(3, '0')}",
                    RelationKind.TABLE,
                ),
                observedAt,
            )
        }
        tx { tasks.forEach { task -> checkNotNull(ingest.findOrInsertOwnedIngestTask(task, "runner-a")) } }

        val running = read { ingest.findRunningIngestTasks(claimed.id) }
        assertEquals(CATALOG_INGEST_JOB_TASK_PROJECTION_LIMIT, running.size)
        assertTrue(running.all { it.state == CatalogIngestTaskState.RUNNING })
    }

    @Test
    fun `failed and cancelled tasks preserve last observations and successful sibling history`() {
        val initialJob = admitAndClaim()
        val initialEntry = read { ingest.findIngestTasks(initialJob.id) }.single()
        val initialSchema = insertTask(
            initialJob, CatalogIngestTaskTarget.Schema("warehouse", "analytics"), initialEntry.id,
        )
        tx {
            ingest.applyContainer(
                initialSchema,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(), "runner-a", observedAt,
            )
        }
        listOf("failed_orders", "cancelled_orders", "refreshed_orders").forEach { name ->
            val task = insertTask(
                initialJob,
                CatalogIngestTaskTarget.Relation("warehouse", "analytics", name, RelationKind.TABLE),
                initialSchema.id,
            )
            tx {
                ingest.completeRelationTask(
                    task,
                    listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", name, RelationKind.TABLE, null,
                        null, null, null,
                    )),
                    emptyList(), emptyList(), "runner-a", observedAt,
                )
            }
        }
        tx {
            check(ingest.updateOwnedIngestTask(initialSchema.succeed(observedAt), "runner-a"))
            check(ingest.updateOwnedIngestTask(initialEntry.succeed(observedAt), "runner-a"))
            check(ingest.updateOwnedIngestJob(initialJob.complete(observedAt), "runner-a"))
        }
        val before = checkNotNull(tx { currentObjects.readCurrent(workspaceId, connectorId) })
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val failedTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "failed_orders", RelationKind.TABLE),
            entryTask.id,
        )
        val cancelledTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "cancelled_orders", RelationKind.TABLE),
            entryTask.id,
        )
        val refreshedTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "refreshed_orders", RelationKind.TABLE),
            entryTask.id,
        )
        tx {
            ingest.completeRelationTask(
                refreshedTask,
                listOf(CatalogMetadataObservation.Relation(
                    "warehouse", "analytics", "refreshed_orders", RelationKind.VIEW, "select 1",
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt.plusSeconds(1),
            )
            check(ingest.updateOwnedIngestTask(
                failedTask.fail("QUERY_FAILED", "Metadata query failed", observedAt.plusSeconds(2), null),
                "runner-a",
            ))
            check(ingest.updateIngestJob(claimed.requestCancellation()))
        }
        assertFailsWith<IllegalArgumentException> {
            tx {
                ingest.completeRelationTask(
                    cancelledTask, emptyList(), emptyList(), emptyList(), "runner-a", observedAt.plusSeconds(3),
                )
            }
        }
        tx {
            check(ingest.updateOwnedIngestTask(
                cancelledTask.cancel("Ingestion cancelled", observedAt.plusSeconds(3)), "runner-a",
            ))
            check(ingest.updateOwnedIngestTask(
                entryTask.cancel("Ingestion cancelled", observedAt.plusSeconds(3)), "runner-a",
            ))
            check(ingest.updateOwnedIngestJob(claimed.cancel(observedAt.plusSeconds(3)), "runner-a"))
        }

        val after = checkNotNull(tx { currentObjects.readCurrent(workspaceId, connectorId) })
        listOf("failed_orders", "cancelled_orders").forEach { name ->
            assertEquals(
                before.objects.single { it.address.objectName == name },
                after.objects.single { it.address.objectName == name },
            )
        }
        val refreshed = after.objects.single { it.address.objectName == "refreshed_orders" }
        assertEquals(RelationKind.VIEW, refreshed.relationKind)
        assertEquals("select 1", refreshed.definition)
        assertEquals(observedAt.plusSeconds(1), refreshed.observedAt)
        assertEquals(4, countCurrentObjects("warehouse"))
        assertEquals(CatalogIngestTaskState.SUCCEEDED, read { ingest.findIngestTask(refreshedTask.id) }?.state)
        assertEquals(CatalogIngestTaskState.CANCELLED, read { ingest.findIngestTask(cancelledTask.id) }?.state)
        assertEquals("QUERY_FAILED", read { ingest.findIngestTask(failedTask.id) }?.failureCode)
        assertEquals(CatalogIngestJobState.CANCELLED, read { ingest.findIngestJob(claimed.id) }?.state)
        assertEquals(listOf(failedTask.id), read { ingest.findIngestErrorPage(claimed.id, 1, 10) }.errors.map { it.id })
    }

    @Test
    fun `stale heartbeat fails only running tasks and preserves current rows`() {
        val claimed = admitAndClaim()
        val entryTask = read { ingest.findIngestTasks(claimed.id) }.single()
        val catalogTask = insertTask(claimed, CatalogIngestTaskTarget.Catalog("warehouse"), entryTask.id)
        val schemaTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            catalogTask.id,
        )
        val relationTask = insertTask(
            claimed,
            CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", RelationKind.TABLE),
            schemaTask.id,
        )
        tx {
            ingest.applyContainer(
                catalogTask,
                listOf(CatalogMetadataObservation.Catalog("warehouse")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.applyContainer(
                schemaTask,
                listOf(CatalogMetadataObservation.Schema("warehouse", "analytics")),
                emptyList(),
                "runner-a",
                observedAt,
            )
            ingest.completeRelationTask(
                relationTask,
                listOf(CatalogMetadataObservation.Relation("warehouse", "analytics", "orders", RelationKind.TABLE, null,
                    null, null, null,
                )),
                emptyList(),
                emptyList(),
                "runner-a",
                observedAt,
            )
            check(ingest.updateOwnedIngestTask(entryTask.succeed(observedAt.plusSeconds(1)), "runner-a"))
        }

        val stale = tx {
            ingest.failStaleIngestJob(
                claimed.id,
                observedAt.plusSeconds(120),
                Duration.ofSeconds(60),
            )
        }

        assertNotNull(stale)
        assertEquals(CatalogIngestJobState.COMPLETED_WITH_ERRORS, stale.state)
        val tasks = read { ingest.findIngestTasks(claimed.id) }
        assertEquals(
            CatalogIngestTaskState.SUCCEEDED,
            tasks.single { it.target == CatalogIngestTaskTarget.Connector }.state,
        )
        val runningSchemaTask = tasks.single {
            it.target == CatalogIngestTaskTarget.Schema("warehouse", "analytics")
        }
        assertEquals(CatalogIngestTaskState.FAILED, runningSchemaTask.state)
        assertEquals("RUNNER_HEARTBEAT_EXPIRED", runningSchemaTask.failureCode)

        val current = tx { currentObjects.readCurrent(workspaceId, connectorId) }
        assertNotNull(current)
        assertTrue(current.objects.all { it.contentDigest.value.startsWith("sha256:") })
    }

    @Test
    fun `same target has one active list refresh and terminal cancellation clears active run`() {
        val target = CatalogListRefreshTarget.Schema("warehouse", "analytics")
        val first = listJob(target)
        insertListJob(first)

        assertFailsWith<Exception> {
            insertListJob(listJob(target))
        }
        assertEquals(
            first.id,
            read { listRefresh.findActiveListRefreshJob(workspaceId, connectorId, target.key) }?.id,
        )

        val claimed = claimListJob(first)
        val runId = ExecutionRunId.generate()
        assertTrue(tx { listRefresh.setActiveListRefreshRun(claimed.id, runId, "runner-a") })
        val cancelled = checkNotNull(tx {
            listRefresh.cancelListRefreshJob(claimed.id, "runner-a", observedAt.plusSeconds(2))
        })

        assertEquals(CatalogListRefreshJobState.CANCELLED, cancelled.state)
        assertEquals(null, cancelled.activeRunId)
        assertEquals(null, read { listRefresh.findActiveListRefreshJob(workspaceId, connectorId, target.key) })
        assertEquals(CatalogListRefreshJobState.CANCELLED, read { listRefresh.findListRefreshJob(first.id) }?.state)
    }

    @Test
    fun `successful list replacement records fetched time and does not mutate current objects`() {
        val target = CatalogListRefreshTarget.Schema("warehouse", "analytics")
        val job = claimListJob(listJob(target).also(::insertListJob))
        val fetchedAt = observedAt.plusSeconds(10)

        assertTrue(tx {
            listRefresh.completeListRefreshWithOptions(
                job,
                listOf(
                    CatalogListOption("orders", RelationKind.TABLE, true),
                    CatalogListOption("recent_orders", RelationKind.VIEW, true),
                ),
                fetchedAt,
                "runner-a",
            )
        })

        val page = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, null) }
        assertEquals(fetchedAt, page.fetchedAt)
        assertEquals(listOf("orders", "recent_orders"), page.options.map { it.name })
        assertEquals(CatalogListRefreshJobState.COMPLETED, page.latestJob?.state)
        assertEquals(null, transactions.execute { currentObjects.readCurrent(workspaceId, connectorId) })
    }

    @Test
    fun `list option search matches underscore and percent literally`() {
        val target = CatalogListRefreshTarget.Schema("warehouse", "analytics")
        val job = claimListJob(listJob(target).also(::insertListJob))
        assertTrue(tx {
            listRefresh.completeListRefreshWithOptions(
                job,
                listOf(
                    CatalogListOption("order_items", RelationKind.TABLE, true),
                    CatalogListOption("orderxitems", RelationKind.TABLE, true),
                    CatalogListOption("growth_100%", RelationKind.VIEW, true),
                    CatalogListOption("growth_1000", RelationKind.VIEW, true),
                ),
                observedAt.plusSeconds(10),
                "runner-a",
            )
        })

        val underscore = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, "order_") }
        assertEquals(listOf("order_items"), underscore.options.map { it.name })
        assertEquals(1, underscore.totalCount)
        val percent = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, "100%") }
        assertEquals(listOf("growth_100%"), percent.options.map { it.name })
        assertEquals(1, percent.totalCount)
    }

    @Test
    fun `connector and catalog selections coexist and read by exact entry`() {
        val connectorEntry = CatalogIngestEntry.Connector(connectorId)
        val warehouseEntry = CatalogIngestEntry.Catalog(connectorId, "warehouse")
        val financeEntry = CatalogIngestEntry.Catalog(connectorId, "finance")
        val connectorSelection = CatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entry = connectorEntry,
            selection = CatalogSelection(emptySet()),
            committedBy = requestedBy,
            committedAt = observedAt,
        )
        val warehouseSelection = CatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entry = warehouseEntry,
            selection = CatalogSelection(
                setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations)),
            ),
            committedBy = requestedBy,
            committedAt = observedAt,
        )
        val financeSelection = CatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entry = financeEntry,
            selection = CatalogSelection(emptySet()),
            committedBy = requestedBy,
            committedAt = observedAt,
        )

        val connectorCatalogSelection = ConnectorCatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = ConnectorCatalogSelection(setOf("warehouse", "finance")),
            committedBy = requestedBy,
            committedAt = observedAt,
        )
        tx {
            selections.saveConnectorCatalogSelection(connectorCatalogSelection)
            selections.saveSelection(connectorSelection)
            selections.saveSelection(warehouseSelection)
            selections.saveSelection(financeSelection)
        }

        assertEquals(
            connectorCatalogSelection,
            read { selections.currentConnectorCatalogSelection(workspaceId, connectorId) },
        )
        assertTrue(read { selections.isConnectorCatalogSelected(workspaceId, connectorId, "warehouse") })
        assertTrue(read { selections.isConnectorCatalogSelected(workspaceId, connectorId, "finance") })
        assertFalse(read { selections.isConnectorCatalogSelected(workspaceId, connectorId, "missing") })
        assertEquals(connectorSelection, read { selections.currentSelection(workspaceId, connectorId, connectorEntry) })
        assertEquals(warehouseSelection, read { selections.currentSelection(workspaceId, connectorId, warehouseEntry) })
        assertEquals(financeSelection, read { selections.currentSelection(workspaceId, connectorId, financeEntry) })
        assertEquals(
            null,
            read { selections.currentSelection(
                workspaceId,
                connectorId,
                CatalogIngestEntry.Catalog(connectorId, "missing"),
            ) },
        )
    }

    @Test
    fun `Catalog deselection deletes coverage and removes the complete current subtree while preserving history`() {
        val entry = CatalogIngestEntry.Catalog(connectorId, "warehouse")
        val connectorSelection = ConnectorCatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = ConnectorCatalogSelection(setOf("warehouse")),
            committedBy = requestedBy,
            committedAt = observedAt,
        )
        val coverage = CatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entry = entry,
            selection = CatalogSelection(
                setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations)),
            ),
            committedBy = requestedBy,
            committedAt = observedAt,
        )
        val history = CatalogIngestJob.request(
            id = CatalogIngestJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = coverage.selection,
            entry = entry,
            requestedBy = requestedBy,
            requestedAt = observedAt,
            credentialSurface = null,
            agentClientId = null,
        ).complete(observedAt.plusSeconds(1))
        tx {
            selections.saveConnectorCatalogSelection(connectorSelection)
            selections.saveSelection(coverage)
            ingest.insertIngestJob(history.copy(state = CatalogIngestJobState.RUNNING, terminalAt = null))
            check(ingest.updateIngestJob(history))
        }
        insertCurrentObject(CatalogObjectKind.CATALOG, "warehouse", null, null, null)
        insertCurrentObject(CatalogObjectKind.SCHEMA, "warehouse", "analytics", null, null)
        insertCurrentObject(CatalogObjectKind.RELATION, "warehouse", "analytics", "orders", RelationKind.TABLE)

        tx {
            selections.saveConnectorCatalogSelection(
                connectorSelection.copy(
                    selection = ConnectorCatalogSelection(emptySet()),
                    committedAt = observedAt.plusSeconds(2),
                ),
            )
            selections.deleteCatalogSelection(workspaceId, connectorId, "warehouse")
            ingest.removeCurrentCatalogSubtree(
                workspaceId,
                connectorId,
                "warehouse",
            )
        }

        assertEquals(
            ConnectorCatalogSelection(emptySet()),
            read { selections.currentConnectorCatalogSelection(workspaceId, connectorId) }?.selection,
        )
        assertEquals(null, read { selections.currentSelection(workspaceId, connectorId, entry) })
        assertEquals(null, transactions.execute { currentObjects.readCurrent(workspaceId, connectorId) })
        assertEquals(history, read { ingest.findIngestJob(history.id) })
        assertEquals(0, countCurrentObjects("warehouse"))
    }

    @Test
    fun `Catalog deselection rolls back selection and row deletion when owner transaction fails`() {
        val entry = CatalogIngestEntry.Catalog(connectorId, "warehouse")
        val connectorSelection = ConnectorCatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = ConnectorCatalogSelection(setOf("warehouse")),
            committedBy = requestedBy,
            committedAt = observedAt,
        )
        val coverage = CatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            entry = entry,
            selection = CatalogSelection(
                setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations)),
            ),
            committedBy = requestedBy,
            committedAt = observedAt,
        )
        tx {
            selections.saveConnectorCatalogSelection(connectorSelection)
            selections.saveSelection(coverage)
        }
        insertCurrentObject(CatalogObjectKind.CATALOG, "warehouse", null, null, null)
        insertCurrentObject(CatalogObjectKind.SCHEMA, "warehouse", "analytics", null, null)
        insertCurrentObject(CatalogObjectKind.RELATION, "warehouse", "analytics", "orders", RelationKind.TABLE)

        assertFailsWith<IllegalStateException> {
            tx {
                selections.saveConnectorCatalogSelection(
                    connectorSelection.copy(
                        selection = ConnectorCatalogSelection(emptySet()),
                        committedAt = observedAt.plusSeconds(1),
                    ),
                )
                selections.deleteCatalogSelection(workspaceId, connectorId, "warehouse")
                ingest.removeCurrentCatalogSubtree(
                    workspaceId,
                    connectorId,
                    "warehouse",
                )
                error("abort owner transaction")
            }
        }

        assertEquals(
            connectorSelection,
            read { selections.currentConnectorCatalogSelection(workspaceId, connectorId) },
        )
        assertEquals(coverage, read { selections.currentSelection(workspaceId, connectorId, entry) })
        assertEquals(3, countCurrentObjects("warehouse"))
    }

    @Test
    fun `catalog list refresh replaces Catalog options with the latest complete result`() {
        val target = CatalogListRefreshTarget.Connector(CatalogListChildKind.CATALOG)
        saveCatalogSelection("warehouse")
        saveCatalogSelection("finance")

        val first = claimListJob(listJob(target).also(::insertListJob))
        tx {
            check(
                listRefresh.completeListRefreshWithOptions(
                    first,
                    listOf(
                        CatalogListOption("finance", null, true),
                        CatalogListOption("sales", null, true),
                        CatalogListOption("warehouse", null, true),
                    ),
                    observedAt.plusSeconds(1),
                    "runner-a",
                ),
            )
        }

        val second = claimListJob(listJob(target).also(::insertListJob))
        tx {
            check(
                listRefresh.completeListRefreshWithOptions(
                    second,
                    listOf(CatalogListOption("sales", null, true)),
                    observedAt.plusSeconds(2),
                    "runner-a",
                ),
            )
        }

        val page = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, null) }
        assertEquals(listOf("sales"), page.options.map { it.name })
        assertEquals(listOf(true), page.options.map { it.available })
        assertTrue(read { selections.hasCatalogDiscoveryOption(workspaceId, connectorId, "sales") })
        assertFalse(read { selections.hasCatalogDiscoveryOption(workspaceId, connectorId, "warehouse") })
        assertEquals(
            listOf("sales"),
            read { listRefresh.findListOptionsAfter(workspaceId, connectorId, target, null, 2) }.options.map { it.name },
        )
        assertEquals(
            CatalogListOptionRead(observedAt.plusSeconds(2), CatalogListOption("sales", null, true)),
            tx { listRefresh.findListOption(workspaceId, connectorId, target, "sales") },
        )
        assertEquals(
            CatalogListOptionRead(observedAt.plusSeconds(2), null),
            tx { listRefresh.findListOption(workspaceId, connectorId, target, "warehouse") },
        )
        assertEquals(
            CatalogListOptionRead(null, null),
            tx { listRefresh.findListOption(workspaceId, connectorId, CatalogListRefreshTarget.Catalog("finance"), "sales") },
        )
    }

    @Test
    fun `per-Catalog coverage selection does not mutate the Catalog option cache`() {
        val target = CatalogListRefreshTarget.Connector(CatalogListChildKind.CATALOG)
        saveCatalogSelection("warehouse")
        val first = claimListJob(listJob(target).also(::insertListJob))
        tx {
            check(
                listRefresh.completeListRefreshWithOptions(
                    first,
                    listOf(CatalogListOption("warehouse", null, true)),
                    observedAt.plusSeconds(1),
                    "runner-a",
                ),
            )
        }
        saveEmptyCatalogSelection("warehouse")

        assertEquals(
            listOf(CatalogListOption("warehouse", null, true)),
            read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, null) }.options,
        )
    }

    @Test
    fun `failed and cancelled refreshes preserve the previous option cache`() {
        val target = CatalogListRefreshTarget.Schema("warehouse", "analytics")
        val initial = claimListJob(listJob(target).also(::insertListJob))
        tx {
            check(
                listRefresh.completeListRefreshWithOptions(
                    initial,
                    listOf(CatalogListOption("orders", RelationKind.TABLE, true)),
                    observedAt.plusSeconds(1),
                    "runner-a",
                ),
            )
        }
        val beforeFailure = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, null) }

        val failedJob = claimListJob(listJob(target).also(::insertListJob))
        val failed = checkNotNull(tx {
            listRefresh.failListRefreshJob(
                failedJob.id,
                "runner-a",
                "WAREHOUSE_QUERY_FAILED",
                "Warehouse query failed",
                observedAt.plusSeconds(2),
            )
        })
        assertEquals(CatalogListRefreshJobState.COMPLETED_WITH_ERRORS, failed.state)
        assertEquals(null, failed.activeRunId)
        val afterFailure = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, null) }
        assertEquals(beforeFailure.fetchedAt, afterFailure.fetchedAt)
        assertEquals(beforeFailure.options, afterFailure.options)

        val cancelledJob = claimListJob(listJob(target).also(::insertListJob))
        val runId = ExecutionRunId.generate()
        assertTrue(tx { listRefresh.setActiveListRefreshRun(cancelledJob.id, runId, "runner-a") })
        val requested = cancelledJob.requestCancellation()
        assertTrue(tx { listRefresh.updateListRefreshJob(requested) })
        val cancelled = checkNotNull(tx {
            listRefresh.cancelListRefreshJob(cancelledJob.id, "runner-a", observedAt.plusSeconds(4))
        })
        assertEquals(CatalogListRefreshJobState.CANCELLED, cancelled.state)
        assertEquals(null, cancelled.activeRunId)
        val afterCancellation = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, null) }
        assertEquals(beforeFailure.fetchedAt, afterCancellation.fetchedAt)
        assertEquals(beforeFailure.options, afterCancellation.options)

        val firstHistoryPage = read { listRefresh.findListRefreshJobsAfter(
            workspaceId,
            connectorId,
            target.key,
            null,
            null,
            2,
        ) }
        val historyBoundary = firstHistoryPage.last()
        val secondHistoryPage = read { listRefresh.findListRefreshJobsAfter(
            workspaceId,
            connectorId,
            target.key,
            historyBoundary.requestedAt,
            historyBoundary.id,
            2,
        ) }
        assertEquals(
            setOf(initial.id, failedJob.id, cancelledJob.id),
            (firstHistoryPage + secondHistoryPage).map { it.id }.toSet(),
        )
    }

    @Test
    fun `disappeared selected option remains unavailable and save removes it after deselection`() {
        val target = CatalogListRefreshTarget.Schema("warehouse", "analytics")
        saveSelection(CatalogRelationSelection.SelectedRelations(setOf("events")))
        val first = claimListJob(listJob(target).also(::insertListJob))
        tx {
            check(
                listRefresh.completeListRefreshWithOptions(
                    first,
                    listOf(
                        CatalogListOption("events", RelationKind.TABLE, true),
                        CatalogListOption("orders", RelationKind.TABLE, true),
                    ),
                    observedAt.plusSeconds(1),
                    "runner-a",
                ),
            )
        }

        val second = claimListJob(listJob(target).also(::insertListJob))
        tx {
            check(
                listRefresh.completeListRefreshWithOptions(
                    second,
                    listOf(CatalogListOption("customers", RelationKind.TABLE, true)),
                    observedAt.plusSeconds(2),
                    "runner-a",
                ),
            )
        }
        val stale = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, null) }
        assertEquals(listOf("customers", "events"), stale.options.map { it.name })
        assertEquals(listOf(true, false), stale.options.map { it.available })

        saveSelection(CatalogRelationSelection.SelectedRelations(setOf("customers")))
        val cleaned = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 50, null) }
        assertEquals(listOf("customers"), cleaned.options.map { it.name })
    }

    @Test
    fun `relation option page and search remain bounded for 12480 options`() {
        val target = CatalogListRefreshTarget.Schema("warehouse", "analytics")
        val job = claimListJob(listJob(target).also(::insertListJob))
        val options = (0 until 12_480).map { index ->
            CatalogListOption("relation_${index.toString().padStart(5, '0')}", RelationKind.TABLE, true)
        }
        tx {
            check(listRefresh.completeListRefreshWithOptions(job, options, observedAt.plusSeconds(1), "runner-a"))
        }

        val firstPage = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 25, null) }
        assertEquals(12_480, firstPage.totalCount)
        assertEquals(25, firstPage.options.size)
        assertEquals("relation_00000", firstPage.options.first().name)
        assertEquals("relation_00024", firstPage.options.last().name)
        val secondPage = read { listRefresh.findListOptions(workspaceId, connectorId, target, 2, 25, null) }
        assertEquals(2, secondPage.page)
        assertEquals("relation_00025", secondPage.options.first().name)
        assertEquals("relation_00049", secondPage.options.last().name)
        assertTrue(read { listRefresh.findListOptions(workspaceId, connectorId, target, Int.MAX_VALUE, 200, null) }.options.isEmpty())


        val search = read { listRefresh.findListOptions(workspaceId, connectorId, target, 1, 20, "relation_124") }
        assertEquals(80, search.totalCount)
        assertEquals(20, search.options.size)
        assertEquals("relation_12400", search.options.first().name)
        assertEquals("relation_12419", search.options.last().name)
    }

    @Test
    fun `option search returns an exact name on the first bounded page`() {
        val target = CatalogListRefreshTarget.Schema("warehouse", "analytics")
        val job = claimListJob(listJob(target).also(::insertListJob))
        val options = buildList {
            repeat(100) { index ->
                add(CatalogListOption("a_selected_catalog_${index.toString().padStart(3, '0')}", RelationKind.TABLE, true))
            }
            add(CatalogListOption("selected_catalog", RelationKind.TABLE, true))
        }
        tx {
            check(listRefresh.completeListRefreshWithOptions(job, options, observedAt.plusSeconds(1), "runner-a"))
        }

        val search = read { listRefresh.findListOptions(
            workspaceId,
            connectorId,
            target,
            1,
            50,
            "selected_catalog",
        ) }

        assertEquals(101, search.totalCount)
        assertEquals(50, search.options.size)
        assertEquals("selected_catalog", search.options.first().name)
    }

    @Test
    fun `connector state removal clears current state of one connector and keeps job history`() {
        val deletedConnector = connectorId
        val otherConnector = ConnectorId(Uuid.generateV7())
        connectorId = otherConnector
        saveCatalogSelection("warehouse")
        insertCurrentObject(CatalogObjectKind.CATALOG, "warehouse", null, null, null)
        connectorId = deletedConnector
        saveCatalogSelection("warehouse")
        tx { selections.saveConnectorCatalogSelection(ConnectorCatalogSelectionState(workspaceId, connectorId,
            ConnectorCatalogSelection(setOf("warehouse")), requestedBy, observedAt)) }
        insertCurrentObject(CatalogObjectKind.CATALOG, "warehouse", null, null, null)
        insertCurrentObject(CatalogObjectKind.SCHEMA, "warehouse", "analytics", null, null)

        val listJob = claimListJob(listJob(CatalogListRefreshTarget.Schema("warehouse", "analytics")).also(::insertListJob))
        assertTrue(tx { connectorState.hasActiveConnectorWork(workspaceId, connectorId) })
        tx { check(listRefresh.completeListRefreshWithOptions(listJob,
            listOf(CatalogListOption("orders", RelationKind.TABLE, true)), observedAt.plusSeconds(1), "runner-a")) }
        assertFalse(tx { connectorState.hasActiveConnectorWork(workspaceId, connectorId) })
        val ingestJob = admit()
        assertTrue(tx { connectorState.hasActiveConnectorWork(workspaceId, connectorId) })
        assertFalse(tx { connectorState.hasActiveConnectorWork(WorkspaceId(Uuid.generateV7()), connectorId) })
        tx { check(ingest.updateIngestJob(ingestJob.complete(observedAt.plusSeconds(2)))) }
        assertFalse(tx { connectorState.hasActiveConnectorWork(workspaceId, connectorId) })

        tx { connectorState.removeConnectorState(workspaceId, connectorId) }

        for (table in listOf("selection_options", "selection_option_lists", "selections", "connector_catalog_selections", "current_objects")) {
            assertEquals(0, countConnectorRows(table, deletedConnector), table)
        }
        assertEquals(1, countConnectorRows("selections", otherConnector))
        assertEquals(1, countConnectorRows("current_objects", otherConnector))
        assertEquals(1, countConnectorRows("ingest_jobs", deletedConnector))
        assertEquals(1, countConnectorRows("list_refresh_jobs", deletedConnector))
        assertEquals(cc.monomer.catalog.api.CatalogSearchAddress(deletedConnector, null, null, null), deletedAddresses.last())
        assertFailsWith<IllegalStateException> { connectorState.removeConnectorState(workspaceId, connectorId) }
    }

    private fun countConnectorRows(table: String, connector: ConnectorId): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM catalog.$table WHERE workspace_id = ? AND connector_id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(workspaceId.value.toString()))
                statement.setObject(2, UUID.fromString(connector.value.toString()))
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun insertTask(
        job: CatalogIngestJob,
        target: CatalogIngestTaskTarget,
        parentTaskId: cc.monomer.catalog.api.CatalogIngestTaskId,
    ): CatalogIngestTask = CatalogIngestTask.start(
        job,
        parentTaskId,
        target,
        observedAt,
    ).also { task -> check(tx { ingest.findOrInsertOwnedIngestTask(task, "runner-a") } == task) }

    private fun listJob(target: CatalogListRefreshTarget): CatalogListRefreshJob =
        CatalogListRefreshJob.request(
            id = CatalogListRefreshJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connectorId,
            target = target,
            requestedBy = requestedBy,
            requestedAt = observedAt,
            credentialSurface = null,
            agentClientId = null,
        )

    private fun insertListJob(job: CatalogListRefreshJob) {
        tx { listRefresh.insertListRefreshJob(job) }
    }

    private fun claimListJob(job: CatalogListRefreshJob): CatalogListRefreshJob =
        checkNotNull(tx { listRefresh.claimListRefreshJob(job.id, "runner-a", observedAt) })

    private fun saveSelection(relations: CatalogRelationSelection) {
        tx {
            selections.saveSelection(
                cc.monomer.catalog.api.CatalogSelectionState(
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    entry = CatalogIngestEntry.Catalog(connectorId, "warehouse"),
                    selection = CatalogSelection(
                        setOf(CatalogSelectedSchema("analytics", relations)),
                    ),
                    committedBy = requestedBy,
                    committedAt = observedAt,
                ),
            )
        }
    }

    private fun saveCatalogSelection(catalogName: String) {
        tx {
            selections.saveSelection(
                cc.monomer.catalog.api.CatalogSelectionState(
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    entry = CatalogIngestEntry.Catalog(connectorId, catalogName),
                    selection = CatalogSelection(
                        setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations)),
                    ),
                    committedBy = requestedBy,
                    committedAt = observedAt,
                ),
            )
        }
    }

    private fun saveEmptyCatalogSelection(catalogName: String) {
        tx {
            selections.saveSelection(
                cc.monomer.catalog.api.CatalogSelectionState(
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    entry = CatalogIngestEntry.Catalog(connectorId, catalogName),
                    selection = CatalogSelection(emptySet()),
                    committedBy = requestedBy,
                    committedAt = observedAt,
                ),
            )
        }
    }

    private fun admitAndClaim(
        selection: CatalogSelection = CatalogSelection(
            setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations)),
        ),
        entry: CatalogIngestEntry = CatalogIngestEntry.Connector(connectorId),
    ): CatalogIngestJob {
        val job = admit(selection, entry)
        return checkNotNull(tx { ingest.claimIngestJob(job.id, "runner-a", observedAt) })
    }

    private fun admit(
        selection: CatalogSelection = CatalogSelection(
            setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations)),
        ),
        entry: CatalogIngestEntry = CatalogIngestEntry.Connector(connectorId),
    ): CatalogIngestJob {
        val job = CatalogIngestJob.request(
            id = CatalogIngestJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = selection,
            entry = entry,
            requestedBy = requestedBy,
            requestedAt = observedAt,
            credentialSurface = null,
            agentClientId = null,
        )
        tx { ingest.insertIngestJob(job) }
        assertTrue(read { ingest.findIngestTasks(job.id) }.isEmpty())
        return job
    }

    private fun newJob(
        workspaceId: WorkspaceId = this.workspaceId,
        entry: CatalogIngestEntry = CatalogIngestEntry.Connector(connectorId),
    ): CatalogIngestJob = CatalogIngestJob.request(
        id = CatalogIngestJobId.generate(),
        workspaceId = workspaceId,
        connectorId = connectorId,
        selection = CatalogSelection(
            setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations)),
        ),
        entry = entry,
        requestedBy = requestedBy,
        requestedAt = observedAt,
        credentialSurface = null,
        agentClientId = null,
    )

    private fun cutoverScript(): String {
        val candidates = listOf(
            java.nio.file.Path.of("testkit/catalog-active-job-admission/cutover.sql"),
            java.nio.file.Path.of("../testkit/catalog-active-job-admission/cutover.sql"),
            java.nio.file.Path.of("../../../testkit/catalog-active-job-admission/cutover.sql"),
        )
        return candidates.firstOrNull { java.nio.file.Files.isRegularFile(it) }
            ?.let(java.nio.file.Files::readString)
            ?: error("Catalog admission cutover script is missing")
    }

    private fun insertCurrentObject(
        objectKind: CatalogObjectKind,
        catalogName: String?,
        schemaName: String?,
        objectName: String?,
        relationKind: RelationKind?,
    ) {
        val identity = CatalogStableObjectIdentityCodec.derive(
            connectorId = connectorId,
            objectKind = objectKind,
            catalogName = catalogName,
            schemaName = schemaName,
            objectName = objectName,
            memberName = null,
            relationshipKindName = null,
        )
        val objectId = UUID.fromString(identity.objectId.value.toString())
        val digest = identity.identityDigest.value
        val qualifiedName = listOfNotNull(catalogName, schemaName, objectName).joinToString(".")
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO catalog.current_objects (
                        workspace_id,
                        connector_id,
                        object_id,
                        identity_digest,
                        object_kind,
                        relation_kind,
                        catalog_name,
                        schema_name,
                        object_name,
                        qualified_name,
                        content_digest,
                        observed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(workspaceId.value.toString()))
                statement.setObject(2, UUID.fromString(connectorId.value.toString()))
                statement.setObject(3, objectId)
                statement.setString(4, digest)
                statement.setString(5, objectKind.name)
                statement.setString(6, relationKind?.name)
                statement.setString(7, catalogName)
                statement.setString(8, schemaName)
                statement.setString(9, objectName)
                statement.setString(10, qualifiedName)
                statement.setString(11, digest)
                statement.setTimestamp(12, Timestamp.from(observedAt))
                statement.executeUpdate()
            }
        }
    }

    private fun countCurrentObjects(catalogName: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    SELECT count(*)
                      FROM catalog.current_objects
                     WHERE workspace_id = ? AND connector_id = ?
                       AND catalog_name = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(workspaceId.value.toString()))
                statement.setObject(2, UUID.fromString(connectorId.value.toString()))
                statement.setString(3, catalogName)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun countCurrentRelations(catalogName: String, schemaName: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    SELECT count(*)
                      FROM catalog.current_objects
                     WHERE workspace_id = ? AND connector_id = ?
                       AND catalog_name = ? AND schema_name = ?
                       AND object_kind = 'RELATION'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(workspaceId.value.toString()))
                statement.setObject(2, UUID.fromString(connectorId.value.toString()))
                statement.setString(3, catalogName)
                statement.setString(4, schemaName)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun <T> tx(block: () -> T): T = checkNotNull(transactions.execute { block() })

    private fun <T> read(block: () -> T): T = checkNotNull(readTransactions.execute { ReadValue(block()) }).value

    private data class ReadValue<T>(val value: T)
}

private object TargetCatalogPostgres : org.junit.jupiter.api.extension.BeforeAllCallback,
    org.junit.jupiter.api.extension.AfterAllCallback {
    private const val IMAGE = "postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
    private val postgres = PostgreSQLContainer(
        DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"),
    )
        .withDatabaseName("catalog_authority_gate_ephemeral")
        .withUsername("catalog_authority_gate")
        .withPassword("catalog_authority_gate_test_only")
    private var activeDataSource: DataSource? = null

    val dataSource: DataSource
        get() = checkNotNull(activeDataSource) { "Disposable Catalog PostgreSQL is not running" }

    override fun beforeAll(context: org.junit.jupiter.api.extension.ExtensionContext) {
        postgres.start()
        activeDataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    checkNotNull(javaClass.classLoader.getResource("db/catalog/schema.sql")).readText(),
                )
            }
        }
    }

    override fun afterAll(context: org.junit.jupiter.api.extension.ExtensionContext) {
        activeDataSource = null
        postgres.stop()
    }
}
