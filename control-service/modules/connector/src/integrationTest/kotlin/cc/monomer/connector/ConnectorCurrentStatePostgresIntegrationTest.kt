@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector

import cc.monomer.connector.adapter.outbound.postgres.ConnectorExecutionAccessReadTimeouts
import cc.monomer.connector.adapter.outbound.postgres.ConnectorTransactionTimeouts
import cc.monomer.connector.adapter.outbound.postgres.ManagedCredentialKeyRing
import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorMutationKeyLockPort
import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorRepository
import cc.monomer.connector.adapter.outbound.postgres.PostgresManagedCredentialStore
import cc.monomer.connector.adapter.outbound.postgres.SecureRandomCredentialVaultNonceSource
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorExecutionAccessReadPort
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.*
import kotlinx.serialization.json.*
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.application.ConnectorAuthorityService
import cc.monomer.connector.application.ConnectorExecutionAccessService
import cc.monomer.connector.api.ConnectorMutationResult
import cc.monomer.connector.application.ConnectorMutationKeyLockPort
import cc.monomer.connector.application.ConnectorRepository
import cc.monomer.connector.api.CreateConnectorCommand
import cc.monomer.connector.application.ManagedCredentialReadOutcome
import cc.monomer.connector.application.ManagedCredentialReadRequest
import cc.monomer.connector.api.ReviseConnectorCommand
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@ExtendWith(TargetConnectorPostgres::class)
class ConnectorCurrentStatePostgresIntegrationTest {
    private val instant = Instant.parse("2026-08-19T00:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private lateinit var dataSource: DataSource
    private lateinit var transactions: TransactionTemplate
    private lateinit var readTransactions: TransactionTemplate
    private lateinit var repository: PostgresConnectorRepository
    private lateinit var credentials: PostgresManagedCredentialStore
    private lateinit var service: ConnectorAuthorityService
    private lateinit var executionAccess: ConnectorExecutionAccessService
    private lateinit var keyRing: ManagedCredentialKeyRing
    private val sampleMarkers = java.util.concurrent.ConcurrentHashMap<WorkspaceId, cc.monomer.identity.api.WorkspaceSampleMarker>()
    private val sampleQuery = cc.monomer.identity.api.WorkspaceSampleQuery { sampleMarkers[it] }
    private var workspaceId = WorkspaceId(Uuid.generateV7())
    private val actorId = PrincipalId(Uuid.generateV7())
    private val organizationId = OrganizationId(Uuid.generateV7())

    private val projectedFields = java.util.Collections.synchronizedList(mutableListOf<cc.monomer.connector.api.ConnectorSearchFields>())
    private var rejectProjection = false
    private var projectionArgumentFailure = false
    private val deletedSearchConnectors = java.util.Collections.synchronizedList(mutableListOf<ConnectorId>())
    private val searchProjection = object : cc.monomer.connector.api.ConnectorSearchProjectionPort {
        override fun upsert(fields: cc.monomer.connector.api.ConnectorSearchFields) {
            check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            check(!rejectProjection) { "Search projection failed" }
            require(!projectionArgumentFailure) { "Unexpected projection argument failure" }
            projectedFields.add(fields)
        }

        override fun delete(workspaceId: WorkspaceId, connectorId: ConnectorId) {
            check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            deletedSearchConnectors.add(connectorId)
        }
    }
    private var dependentRemovalOutcome = ConnectorDependentStateRemoval.REMOVED
    private val dependentRemovals = java.util.Collections.synchronizedList(mutableListOf<ConnectorId>())
    private val dependentStateRemoval = ConnectorDependentStateRemovalPort { _, connectorId ->
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        dependentRemovals.add(connectorId)
        dependentRemovalOutcome
    }

    @BeforeEach
    fun setUp() {
        sampleMarkers.clear()
        projectedFields.clear()
        rejectProjection = false
        projectionArgumentFailure = false
        deletedSearchConnectors.clear()
        dependentRemovals.clear()
        dependentRemovalOutcome = ConnectorDependentStateRemoval.REMOVED
        dataSource = TargetConnectorPostgres.dataSource
        transactions = transactionTemplate(readOnly = false)
        readTransactions = transactionTemplate(readOnly = true)
        repository = PostgresConnectorRepository(dataSource, ObjectMapper().findAndRegisterModules())
        keyRing = ManagedCredentialKeyRing(
            activeKeyVersion = "test/active",
            keysByVersion = mapOf("test/active" to ByteArray(32) { 7 }),
        )
        credentials = PostgresManagedCredentialStore(
            dataSource = dataSource,
            keyRing = keyRing,
            nonceSource = SecureRandomCredentialVaultNonceSource(SecureRandom()),
            clock = clock,
            blockingContext = Dispatchers.IO,
            maximumCredentialDocumentBytes = 1_048_576,
            maximumCredentialFields = 16,
            maximumFieldNameBytes = 64,
            maximumFieldValueBytes = 262_144,
        )
        service = ConnectorAuthorityService(
            workspaceSampleQuery = sampleQuery,
            searchProjection = searchProjection,
            repository = repository,
            transactionPort = SpringConnectorTransactionPort(
                dataSource = dataSource,
                transactionOperations = transactions,
                blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
                timeouts = ConnectorTransactionTimeouts(
                    transaction = Duration.ofSeconds(10),
                    statement = Duration.ofSeconds(5),
                    lock = Duration.ofSeconds(2),
                ),
            ),
            mutationKeyLockPort = PostgresConnectorMutationKeyLockPort(
                dataSource = dataSource,
                queryTimeout = Duration.ofSeconds(2),
            ),
            credentialWritePort = credentials,
            dependentStateRemoval = dependentStateRemoval,
            clock = clock,
        )
        executionAccess = ConnectorExecutionAccessService(
            SpringConnectorExecutionAccessReadPort(
                dataSource = dataSource,
                transactionOperations = readTransactions,
                blockingDispatcher = Dispatchers.IO,
                queryPort = repository,
                timeouts = ConnectorExecutionAccessReadTimeouts(
                    transaction = Duration.ofSeconds(10),
                    statement = Duration.ofSeconds(5),
                ),
            ),
        )
        workspaceId = WorkspaceId(Uuid.generateV7())
        truncateOwnedTables()
    }

    @AfterEach
    fun tearDown() {
        keyRing.close()
    }

    @Test
    fun `search projection failure rolls back connector credential and receipt`() = runBlocking {
        rejectProjection = true
        assertFailsWith<RuntimeException> { service.create(createCommand("search-failure")) }
        assertEquals(0, countRows("connector.connectors"))
        assertEquals(0, countRows("connector.credential_versions"))
        assertEquals(0, countRows("connector.connector_mutation_receipts"))
    }

    @Test
    fun `create commits current connector and credential version readable through execution binding`() = runBlocking {
        val created = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-binding")),
        ).connector
        val stored = tx { checkNotNull(repository.findConnector(created.connectorId)) }

        val binding = assertIs<ConnectorExecutionAccessResult.Granted>(
            executionAccess.readCurrentForExecution(workspaceId, created.connectorId),
        ).binding
        assertEquals(created.connectorId, binding.connectorId)
        assertEquals("postgresql", binding.connectorType)
        assertEquals(stored.credentialReference, binding.credentialReference)
        assertEquals("warehouse", binding.properties.getValue("database"))

        val credential = credentials.readExact(readRequest(created.connectorId, stored.credentialReference))
        assertIs<ManagedCredentialReadOutcome.Found>(credential).use { found ->
            found.credentialMaterial.useFields { fields ->
                assertEquals("dw_query", fields.getValue("username").decodeToString())
                assertEquals("initial-secret", fields.getValue("password").decodeToString())
            }
        }
        assertEquals(1, countRows("connector.connectors"))
        assertEquals(1, countRows("connector.credential_versions"))
    }

    @Test
    fun `Snowflake private key credential is normalized for the execution driver`() = runBlocking {
        val created = assertIs<ConnectorMutationResult.Committed>(
            service.create(
                CreateConnectorCommand(
                    authorization = authorization(MutationIntent.CONNECTOR_CREATE),
                    connectorType = ConnectorProductType.SNOWFLAKE,
                    displayName = "Snowflake Warehouse",
                    properties = ConnectorSettingsProperties.Snowflake("organization-account", "TEST_WH", "TEST_DB", "FIXTURE", "TEST_ROLE"),
                    credential = ConnectorCredential.UsernamePrivateKey("service_user", "private-key-pem", null),
                    mutationIdempotencyKey = "create-snowflake-private-key",
                ),
            ),
        ).connector
        val stored = tx { checkNotNull(repository.findConnector(created.connectorId)) }

        val credential =
            credentials.readExact(
                ManagedCredentialReadRequest(
                    workspaceId = workspaceId,
                    connectorId = created.connectorId,
                    connectorType = "snowflake",
                    reference = stored.credentialReference,
                    serverDeadline = Instant.now().plusSeconds(30),
                ),
            )

        assertIs<ManagedCredentialReadOutcome.Found>(credential).use { found ->
            found.credentialMaterial.useFields { fields ->
                assertEquals(setOf("username", "private_key"), fields.keys)
                assertEquals("service_user", fields.getValue("username").decodeToString())
                assertEquals("private-key-pem", fields.getValue("private_key").decodeToString())
            }
        }
    }

    @Test
    fun `current connector batch read returns only requested workspace connectors`() = runBlocking {
        val first = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand("create-batch-first", "First Warehouse")),
        ).connector
        val second = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand("create-batch-second", "Second Warehouse")),
        ).connector
        val omitted = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand("create-batch-omitted", "Omitted Warehouse")),
        ).connector

        val found = tx {
            repository.findCurrentByWorkspaceAndIds(
                workspaceId,
                setOf(second.connectorId, ConnectorId.generate(), first.connectorId),
            )
        }

        assertEquals(setOf(first.connectorId, second.connectorId), found.map { it.connectorId }.toSet())
        assertTrue(found.none { it.connectorId == omitted.connectorId })
        assertEquals(emptyList(), tx { repository.findCurrentByWorkspaceAndIds(workspaceId, emptySet()) })
    }

    @Test
    fun `revise keeps connector type and leaves the previous immutable credential readable`() = runBlocking {
        val created = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-revise")),
        ).connector
        val before = tx { checkNotNull(repository.findConnector(created.connectorId)) }

        val revised = assertIs<ConnectorMutationResult.Committed>(
            service.revise(
                ReviseConnectorCommand(
                    authorization = authorization(MutationIntent.CONNECTOR_UPDATE),
                    connectorId = created.connectorId,
                    patch = settingsPatch("Revised Warehouse", postgresqlProperties("warehouse-revised.internal")),
                    mutationIdempotencyKey = "revise-current",
                ),
            ),
        ).connector
        service.rotateCredential(RotateConnectorCredentialCommand(authorization(MutationIntent.CONNECTOR_UPDATE),
            created.connectorId, ConnectorCredential.UsernamePassword("dw_query", "revised-secret"), "rotate-current"))
        val after = tx { checkNotNull(repository.findConnector(created.connectorId)) }

        assertEquals("postgresql", revised.connectorType)
        assertEquals("postgresql", after.connectorType.value)
        assertEquals("warehouse-revised.internal", ConnectorSettingsCodec.encode(after.properties).getValue("host").jsonPrimitive.content)
        assertNotEquals(before.credentialReference.credentialVersionId, after.credentialReference.credentialVersionId)
        assertEquals(2, countRows("connector.credential_versions"))
        assertEquals(listOf("External Warehouse", "Revised Warehouse"), projectedFields.map { it.primaryNames.single() })
        assertTrue(projectedFields.flatMap { it.structuralNames }.none { it.contains("internal") || it.contains("secret") })
        assertEquals(listOf(projectedFields.last()), tx { repository.inventory() })
        assertEquals(listOf(created.connectorId), tx { repository.readCurrentListings(workspaceId, setOf(created.connectorId)) }.map { it.connectorId })

        val oldCredential = credentials.readExact(readRequest(created.connectorId, before.credentialReference))
        assertIs<ManagedCredentialReadOutcome.Found>(oldCredential).use { found ->
            found.credentialMaterial.useFields { fields ->
                assertEquals("initial-secret", fields.getValue("password").decodeToString())
            }
        }
        val currentCredential = credentials.readExact(readRequest(created.connectorId, after.credentialReference))
        assertIs<ManagedCredentialReadOutcome.Found>(currentCredential).use { found ->
            found.credentialMaterial.useFields { fields ->
                assertEquals("revised-secret", fields.getValue("password").decodeToString())
            }
        }
    }

    @Test
    fun `database identity is enforced by persisted connector guard`() = runBlocking {
        for (type in listOf("postgresql", "redshift")) {
            val created = assertIs<ConnectorMutationResult.Committed>(
                service.create(createCommand(mutationKey = "fixed-database-$type").copy(
                    connectorType = ConnectorProductType.fromStorageValue(type),
                    properties = if (type == "postgresql") postgresqlProperties() else ConnectorSettingsProperties.Redshift("warehouse.internal", 5439, "warehouse", null, null),
                )),
            ).connector
            dataSource.connection.use { connection ->
                val failure = assertFailsWith<SQLException> {
                    connection.prepareStatement(
                        "UPDATE connector.connectors SET properties = jsonb_set(properties, '{database}', to_jsonb('other_database'::text)), " +
                            "authority_revision = authority_revision + 1 WHERE connector_id = ?",
                    ).use { statement ->
                        statement.setObject(1, java.util.UUID.fromString(created.connectorId.toString()))
                        statement.executeUpdate()
                    }
                }
                assertEquals("23514", failure.sqlState)
            }
            assertEquals("warehouse", tx { repository.findConnector(created.connectorId) }?.properties?.let { ConnectorSettingsCodec.encode(it).getValue("database").jsonPrimitive.content })
        }
    }

    @Test
    fun `database replacement rolls back credential and connector mutation`() = runBlocking {
        val created = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-fixed-database")),
        ).connector
        val before = tx { checkNotNull(repository.findConnector(created.connectorId)) }
        assertIs<ConnectorMutationResult.InvalidInput>(
            service.revise(
                ReviseConnectorCommand(
                    authorization = authorization(MutationIntent.CONNECTOR_UPDATE),
                    connectorId = created.connectorId,
                    patch = listOf(ConnectorSettingsPatch("replace", "/properties/database", JsonPrimitive("other_database"))),
                    mutationIdempotencyKey = "replace-database",
                ),
            )
        )
        assertEquals(before, tx { repository.findConnector(created.connectorId) })
        assertEquals(1, countRows("connector.credential_versions"))
        assertEquals(1, countRows("connector.connector_mutation_receipts"))
    }

    @Test
    fun `same mutation key replays while a different payload conflicts`() = runBlocking {
        val first = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-replay")),
        )
        val replay = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-replay")),
        )
        assertEquals(first, replay)
        assertEquals(1, countRows("connector.connectors"))
        assertEquals(1, countRows("connector.credential_versions"))

        val conflict = service.create(
            createCommand(
                mutationKey = "create-replay",
                displayName = "Different Warehouse",
            ),
        )
        assertEquals(ConnectorMutationResult.Conflict, conflict)
        assertEquals(1, countRows("connector.connectors"))
        assertEquals(1, countRows("connector.credential_versions"))
    }

    @Test
    fun `revise replay returns current connector after a later settings edit`() = runBlocking {
        val connectorId = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-revise-replay")),
        ).connector.connectorId
        val firstCommittedState = assertIs<ConnectorMutationResult.Committed>(
            service.revise(
                reviseCommand(
                    connectorId = connectorId,
                    mutationKey = "revise-replay-first",
                    displayName = "First State",
                    host = "warehouse-first.internal",
                ),
            ),
        ).connector
        val current = assertIs<ConnectorMutationResult.Committed>(
            service.revise(
                reviseCommand(
                    connectorId = connectorId,
                    mutationKey = "revise-replay-second",
                    displayName = "Current State",
                    host = "warehouse-current.internal",
                ),
            ),
        ).connector

        val conflict = service.revise(
            reviseCommand(
                connectorId = connectorId,
                mutationKey = "revise-replay-first",
                displayName = "Conflicting State",
                host = "warehouse-conflict.internal",
            ),
        )
        assertEquals(ConnectorMutationResult.Conflict, conflict)

        val replay = assertIs<ConnectorMutationResult.Committed>(
            service.revise(
                reviseCommand(
                    connectorId = connectorId,
                    mutationKey = "revise-replay-first",
                    displayName = "First State",
                    host = "warehouse-first.internal",
                ),
            ),
        ).connector

        assertEquals(firstCommittedState.credentialVersionId, current.credentialVersionId)
        assertEquals(current, replay)
        assertEquals(1, countRows("connector.credential_versions"))
        assertReceiptKeys(setOf("create-revise-replay", "revise-replay-first", "revise-replay-second"))
    }

    @Test
    fun `incompatible credential kinds return input rejection without changing current state`() = runBlocking {
        val incompatible = ConnectorCredential.AccessToken("synthetic-token")
        val createRejected = assertIs<ConnectorMutationResult.InvalidInput>(service.create(createCommand("kind-invalid-create").copy(credential = incompatible)))
        assertEquals("Credential kind does not match connector type", createRejected.message)
        assertEquals(0, countRows("connector.connectors"))
        val created = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("kind-create"))).connector
        val before = tx { checkNotNull(repository.findConnector(created.connectorId)) }
        val rotateRejected = assertIs<ConnectorMutationResult.InvalidInput>(service.rotateCredential(RotateConnectorCredentialCommand(
            authorization(MutationIntent.CONNECTOR_UPDATE), created.connectorId, incompatible, "kind-invalid-rotate")))
        assertEquals("Credential kind does not match connector type", rotateRejected.message)
        assertEquals(before, tx { repository.findConnector(created.connectorId) })
        assertEquals(1, countRows("connector.credential_versions"))
        assertReceiptKeys(setOf("kind-create"))
    }

    @Test
    fun `unexpected owner argument failure is not returned as invalid input after rollback`() = runBlocking {
        projectionArgumentFailure = true
        assertFailsWith<IllegalArgumentException> { service.create(createCommand("unexpected-projection-argument")) }
        assertEquals(0, countRows("connector.connectors"))
        assertEquals(0, countRows("connector.credential_versions"))
        assertEquals(0, countRows("connector.connector_mutation_receipts"))
    }

    @Test
    fun `invalid create credential returns input rejection after transaction rollback`() = runBlocking {
        assertIs<ConnectorMutationResult.InvalidInput>(
            service.create(
                createCommand("create-oversized-credential").copy(
                    credential = ConnectorCredential.UsernamePassword("dw_query", "x".repeat(300_000)),
                ),
            )
        )
        assertEquals(0, countRows("connector.connectors"))
        assertEquals(0, countRows("connector.credential_versions"))
        assertEquals(0, countRows("connector.connector_mutation_receipts"))
    }

    @Test
    fun `invalid revise input returns input rejection after transaction rollback`() = runBlocking {
        val connectorId = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-invalid-revise")),
        ).connector.connectorId

        val rejection = assertIs<ConnectorMutationResult.InvalidInput>(
            service.revise(
                ReviseConnectorCommand(
                    authorization = authorization(MutationIntent.CONNECTOR_UPDATE),
                    connectorId = connectorId,
                    patch = listOf(ConnectorSettingsPatch("replace", "/properties/host", JsonPrimitive("not a valid host"))),
                    mutationIdempotencyKey = "invalid-revise",
                ),
            )
        )
        assertEquals("Connector host is invalid", rejection.message)
        assertEquals(1, countRows("connector.credential_versions"))
        assertReceiptKeys(setOf("create-invalid-revise"))
    }

    @Test
    fun `revise locks connector identity before reading current state`() = runBlocking {
        val connectorId = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-lock-order")),
        ).connector.connectorId
        val calls = mutableListOf<String>()
        val delegateLock = PostgresConnectorMutationKeyLockPort(
            dataSource = dataSource,
            queryTimeout = Duration.ofSeconds(2),
        )
        val trackingRepository = object : ConnectorRepository by repository {
            override fun findConnector(connectorId: ConnectorId) = repository.findConnector(connectorId).also {
                calls += "connector-read"
            }
        }
        val trackingLock = object : ConnectorMutationKeyLockPort by delegateLock {
            override fun lockInOwnerTransaction(workspaceId: WorkspaceId, mutationIdempotencyKey: String) {
                delegateLock.lockInOwnerTransaction(workspaceId, mutationIdempotencyKey)
            }

            override fun lockConnectorInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId) {
                delegateLock.lockConnectorInOwnerTransaction(workspaceId, connectorId)
                calls += "connector-lock"
            }
        }
        val orderedService = ConnectorAuthorityService(
            workspaceSampleQuery = sampleQuery,
            searchProjection = searchProjection,
            repository = trackingRepository,
            transactionPort = SpringConnectorTransactionPort(
                dataSource = dataSource,
                transactionOperations = transactions,
                blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
                timeouts = ConnectorTransactionTimeouts(
                    transaction = Duration.ofSeconds(10),
                    statement = Duration.ofSeconds(5),
                    lock = Duration.ofSeconds(2),
                ),
            ),
            mutationKeyLockPort = trackingLock,
            credentialWritePort = credentials,
            dependentStateRemoval = dependentStateRemoval,
            clock = clock,
        )

        assertIs<ConnectorMutationResult.Committed>(
            orderedService.revise(
                reviseCommand(
                    connectorId = connectorId,
                    mutationKey = "revise-lock-order",
                    displayName = "Serialized State",
                    host = "warehouse-serialized.internal",
                ),
            ),
        )
        assertEquals(listOf("connector-lock", "connector-read"), calls)
    }

    @Test
    fun `concurrent revise requests serialize and both commit current state`() = runBlocking {
        val connectorId = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-concurrent-revise")),
        ).connector.connectorId
        val delegateLock = PostgresConnectorMutationKeyLockPort(
            dataSource = dataSource,
            queryTimeout = Duration.ofSeconds(2),
        )
        val bothTransactionsStarted = CyclicBarrier(2)
        val synchronizedLock = object : ConnectorMutationKeyLockPort by delegateLock {
            override fun lockInOwnerTransaction(workspaceId: WorkspaceId, mutationIdempotencyKey: String) {
                delegateLock.lockInOwnerTransaction(workspaceId, mutationIdempotencyKey)
                bothTransactionsStarted.await(5, TimeUnit.SECONDS)
            }

            override fun lockConnectorInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId) {
                delegateLock.lockConnectorInOwnerTransaction(workspaceId, connectorId)
            }
        }
        val concurrentService = ConnectorAuthorityService(
            workspaceSampleQuery = sampleQuery,
            searchProjection = searchProjection,
            repository = repository,
            transactionPort = SpringConnectorTransactionPort(
                dataSource = dataSource,
                transactionOperations = transactions,
                blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
                timeouts = ConnectorTransactionTimeouts(
                    transaction = Duration.ofSeconds(10),
                    statement = Duration.ofSeconds(5),
                    lock = Duration.ofSeconds(2),
                ),
            ),
            mutationKeyLockPort = synchronizedLock,
            credentialWritePort = credentials,
            dependentStateRemoval = dependentStateRemoval,
            clock = clock,
        )

        Executors.newFixedThreadPool(2).use { executor ->
            val results = listOf("first", "second").map { suffix ->
                executor.submit<ConnectorMutationResult> {
                    runBlocking {
                        concurrentService.revise(
                            reviseCommand(
                                connectorId = connectorId,
                                mutationKey = "concurrent-revise-$suffix",
                                displayName = "Concurrent $suffix",
                                host = "warehouse-$suffix.internal",
                            ),
                        )
                    }
                }
            }
            results.forEach { result ->
                assertIs<ConnectorMutationResult.Committed>(result.get(10, TimeUnit.SECONDS))
            }
        }
        assertEquals(1, countRows("connector.credential_versions"))
    }

    @Test
    fun `concurrent rotation and settings edit preserve both changes`() = runBlocking {
        val connectorId = assertIs<ConnectorMutationResult.Committed>(
            service.create(createCommand(mutationKey = "create-concurrent-revise")),
        ).connector.connectorId
        val delegateLock = PostgresConnectorMutationKeyLockPort(
            dataSource = dataSource,
            queryTimeout = Duration.ofSeconds(2),
        )
        val bothTransactionsStarted = CyclicBarrier(2)
        val synchronizedLock = object : ConnectorMutationKeyLockPort by delegateLock {
            override fun lockInOwnerTransaction(workspaceId: WorkspaceId, mutationIdempotencyKey: String) {
                delegateLock.lockInOwnerTransaction(workspaceId, mutationIdempotencyKey)
                bothTransactionsStarted.await(5, TimeUnit.SECONDS)
            }

            override fun lockConnectorInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId) {
                delegateLock.lockConnectorInOwnerTransaction(workspaceId, connectorId)
            }
        }
        val concurrentService = ConnectorAuthorityService(
            workspaceSampleQuery = sampleQuery,
            searchProjection = searchProjection,
            repository = repository,
            transactionPort = SpringConnectorTransactionPort(
                dataSource = dataSource,
                transactionOperations = transactions,
                blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
                timeouts = ConnectorTransactionTimeouts(
                    transaction = Duration.ofSeconds(10),
                    statement = Duration.ofSeconds(5),
                    lock = Duration.ofSeconds(2),
                ),
            ),
            mutationKeyLockPort = synchronizedLock,
            credentialWritePort = credentials,
            dependentStateRemoval = dependentStateRemoval,
            clock = clock,
        )

        Executors.newFixedThreadPool(2).use { executor ->
            val results = listOf("first", "second").map { suffix ->
                executor.submit<ConnectorMutationResult> {
                    runBlocking {
                        if (suffix == "second") concurrentService.rotateCredential(RotateConnectorCredentialCommand(
                            authorization(MutationIntent.CONNECTOR_UPDATE), connectorId,
                            ConnectorCredential.UsernamePassword("dw_query", "concurrent-secret"), "concurrent-rotate"))
                        else concurrentService.revise(
                            reviseCommand(
                                connectorId = connectorId,
                                mutationKey = "concurrent-revise-$suffix",
                                displayName = "Concurrent $suffix",
                                host = "warehouse-$suffix.internal",
                            ),
                        )
                    }
                }
            }
            results.forEach { result ->
                assertIs<ConnectorMutationResult.Committed>(result.get(10, TimeUnit.SECONDS))
            }
        }
        assertEquals(2, countRows("connector.credential_versions"))
        val current = tx { checkNotNull(repository.findConnector(connectorId)) }
        assertEquals("Concurrent first", current.displayName)
        assertEquals("warehouse-first.internal", ConnectorSettingsCodec.encode(current.properties).getValue("host").jsonPrimitive.content)
        assertIs<ManagedCredentialReadOutcome.Found>(credentials.readExact(readRequest(connectorId, current.credentialReference))).use { found ->
            found.credentialMaterial.useFields { assertEquals("concurrent-secret", it.getValue("password").decodeToString()) }
        }
        assertReceiptKeys(setOf("create-concurrent-revise", "concurrent-revise-first", "concurrent-rotate"))
    }

    @Test
    fun `rotation replay compares exact secret and preserves current settings`() = runBlocking {
        val created = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("rotation-create"))).connector
        val command = RotateConnectorCredentialCommand(authorization(MutationIntent.CONNECTOR_UPDATE), created.connectorId,
            ConnectorCredential.UsernamePassword("dw_query", "new-secret"), "rotation-key")
        val rotated = assertIs<ConnectorMutationResult.Committed>(service.rotateCredential(command)).connector
        assertEquals(created.properties, rotated.properties)
        service.revise(reviseCommand(created.connectorId, "rotation-settings", "Renamed", "new-host.internal"))
        val replay = assertIs<ConnectorMutationResult.Committed>(service.rotateCredential(command)).connector
        assertEquals("Renamed", replay.displayName)
        assertEquals(rotated.credentialVersionId, replay.credentialVersionId)
        assertEquals(ConnectorMutationResult.Conflict, service.rotateCredential(command.copy(
            credential = ConnectorCredential.UsernamePassword("dw_query", "different-secret"))))
        assertEquals(2, countRows("connector.credential_versions"))
        assertEquals(3, countRows("connector.connector_mutation_receipts"))
    }

    @Test
    fun `failed receipt insertion rolls back create and credential atomically`() = runBlocking {
        val failingRepository = object : ConnectorRepository by repository {
            override fun insertMutationReceipt(receipt: cc.monomer.connector.domain.ConnectorMutationReceipt) {
                throw IllegalArgumentException("Injected receipt failure")
            }
        }
        val failingService = serviceWith(failingRepository)
        assertFailsWith<RuntimeException> { failingService.create(createCommand("rollback-create")) }
        assertEquals(0, countRows("connector.connectors"))
        assertEquals(0, countRows("connector.credential_versions"))
        assertEquals(0, countRows("connector.connector_mutation_receipts"))
    }

    @Test
    fun `repository CAS loss rolls back new credential`() = runBlocking {
        val created = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("cas-create"))).connector
        val failingRepository = object : ConnectorRepository by repository {
            override fun updateConnector(connector: cc.monomer.connector.domain.Connector, expectedAuthorityRevision: Long) = false
        }
        val result = serviceWith(failingRepository).rotateCredential(RotateConnectorCredentialCommand(
            authorization(MutationIntent.CONNECTOR_UPDATE), created.connectorId,
            ConnectorCredential.UsernamePassword("dw_query", "lost-secret"), "cas-rotation"))
        assertEquals(ConnectorMutationResult.Conflict, result)
        assertEquals(1, countRows("connector.credential_versions"))
        assertEquals(1, countRows("connector.connector_mutation_receipts"))
    }

    @Test
    fun `settings patch validates final state atomically and preserves credential`() = runBlocking {
        val created = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("patch-create"))).connector
        val command = ReviseConnectorCommand(authorization(MutationIntent.CONNECTOR_UPDATE), created.connectorId,
            listOf(ConnectorSettingsPatch("remove", "/properties/schema", null),
                ConnectorSettingsPatch("replace", "/properties/port", JsonPrimitive(5433)),
                ConnectorSettingsPatch("replace", "/displayName", JsonPrimitive("Patched"))), "patch-valid")
        val updated = assertIs<ConnectorMutationResult.Committed>(service.revise(command)).connector
        assertEquals(created.credentialVersionId, updated.credentialVersionId)
        assertFalse("schema" in ConnectorSettingsCodec.encode(updated.properties))
        val stored = tx { checkNotNull(repository.findConnector(created.connectorId)) }
        for ((index, invalidPatch) in listOf(
            listOf(ConnectorSettingsPatch("replace", "/displayName", JsonPrimitive("Partial")),
                ConnectorSettingsPatch("replace", "/properties/port", JsonPrimitive("5433"))),
            listOf(ConnectorSettingsPatch("add", "/credential", JsonPrimitive("sensitive-value"))),
            listOf(ConnectorSettingsPatch("replace", "/properties/port", JsonPrimitive(0))),
            listOf(ConnectorSettingsPatch("replace", "/properties/does_not_exist", JsonPrimitive("value"))),
            listOf(ConnectorSettingsPatch("replace", "", buildJsonObject { put("displayName", "Incomplete") }),
                ConnectorSettingsPatch("add", "/properties", ConnectorSettingsCodec.encode(updated.properties))),
        ).withIndex()) {
            assertIs<ConnectorMutationResult.InvalidInput>(service.revise(command.copy(patch = invalidPatch,
                mutationIdempotencyKey = "patch-invalid-$index")))
            assertEquals(stored, tx { repository.findConnector(created.connectorId) })
        }
        assertEquals(1, countRows("connector.credential_versions"))
        assertEquals(2, countRows("connector.connector_mutation_receipts"))
    }

    @Test
    fun `FREE cap preserves replay conflict and existing connector mutations without rejected writes`() = runBlocking<Unit> {
        val commands = (1..10).map { createCommand("free-$it") }
        val created = commands.map { assertIs<ConnectorMutationResult.Committed>(service.create(it)).connector }
        assertEquals(ConnectorMutationResult.LimitExceeded, service.create(createCommand("free-overflow")))
        assertEquals(10, countRows("connector.connectors"))
        assertEquals(10, countRows("connector.credential_versions"))
        assertEquals(10, countRows("connector.connector_mutation_receipts"))
        assertEquals(10, projectedFields.size)
        assertEquals(created.first(), assertIs<ConnectorMutationResult.Committed>(service.create(commands.first())).connector)
        assertEquals(ConnectorMutationResult.Conflict, service.create(commands.first().copy(displayName = "Different")))
        assertEquals(ConnectorMutationResult.Conflict, service.create(commands.first().copy(
            credential = ConnectorCredential.UsernamePassword("dw_query", "different-secret"))))
        assertIs<ConnectorMutationResult.Committed>(service.revise(reviseCommand(
            created.first().connectorId, "at-cap-revise", "Renamed", "renamed.internal")))
        assertIs<ConnectorMutationResult.Committed>(service.rotateCredential(RotateConnectorCredentialCommand(
            authorization(MutationIntent.CONNECTOR_UPDATE), created.first().connectorId,
            ConnectorCredential.UsernamePassword("dw_query", "rotated-secret"), "at-cap-rotate")))
    }

    @Test
    fun `FREE concurrent creates compete for the last workspace slot`() = runBlocking {
        repeat(9) { assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("seed-$it"))) }
        val delegateLock = PostgresConnectorMutationKeyLockPort(dataSource, Duration.ofSeconds(2))
        val bothTransactionsStarted = CyclicBarrier(2)
        val synchronizedLock = object : ConnectorMutationKeyLockPort by delegateLock {
            override fun lockInOwnerTransaction(workspaceId: WorkspaceId, mutationIdempotencyKey: String) {
                delegateLock.lockInOwnerTransaction(workspaceId, mutationIdempotencyKey)
                bothTransactionsStarted.await(5, TimeUnit.SECONDS)
            }
        }
        val concurrentService = serviceWith(repository, synchronizedLock)
        val results = Executors.newFixedThreadPool(2).use { executor ->
            (1..2).map { index ->
                executor.submit<ConnectorMutationResult> {
                    runBlocking { concurrentService.create(createCommand("last-slot-$index")) }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
        }
        assertEquals(1, results.count { it is ConnectorMutationResult.Committed })
        assertEquals(1, results.count { it == ConnectorMutationResult.LimitExceeded })
        assertEquals(10L, tx { repository.countCurrentConnectors(workspaceId) })
        assertEquals(10, countRows("connector.credential_versions"))
        assertEquals(10, countRows("connector.connector_mutation_receipts"))
        assertEquals(10, projectedFields.size)
    }

    @Test
    fun `FREE count excludes deleted connectors and isolates workspaces`() = runBlocking {
        val created = (1..10).map {
            assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("full-$it"))).connector
        }
        val anotherWorkspaceId = WorkspaceId(Uuid.generateV7())
        assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("other-workspace").copy(
            authorization = authorization(MutationIntent.CONNECTOR_CREATE, targetWorkspaceId = anotherWorkspaceId))))
        assertEquals(10L, tx { repository.countCurrentConnectors(workspaceId) })
        assertEquals(1L, tx { repository.countCurrentConnectors(anotherWorkspaceId) })
        assertEquals(ConnectorDeletionResult.Deleted, service.delete(deleteCommand(created.first().connectorId)))
        assertEquals(9L, tx { repository.countCurrentConnectors(workspaceId) })
        assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("replacement")))
        assertEquals(ConnectorMutationResult.LimitExceeded, service.create(createCommand("still-full")))
    }

    @Test
    fun `nonfree plans can create above the FREE cap`() = runBlocking {
        for (plan in listOf(OrganizationPlan.PRO, OrganizationPlan.ENTERPRISE)) {
            val targetWorkspaceId = WorkspaceId(Uuid.generateV7())
            repeat(11) { index ->
                assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("$plan-$index").copy(
                    authorization = authorization(MutationIntent.CONNECTOR_CREATE, plan, targetWorkspaceId))))
            }
            assertEquals(11L, tx { repository.countCurrentConnectors(targetWorkspaceId) })
        }
    }

    @Test
    fun `failed last-slot creation leaves capacity for retry`() = runBlocking {
        repeat(9) { assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("rollback-seed-$it"))) }
        rejectProjection = true
        assertFailsWith<RuntimeException> { service.create(createCommand("last-slot-rollback")) }
        assertEquals(9L, tx { repository.countCurrentConnectors(workspaceId) })
        assertEquals(9, countRows("connector.credential_versions"))
        assertEquals(9, countRows("connector.connector_mutation_receipts"))
        assertEquals(9, projectedFields.size)
        rejectProjection = false
        assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("last-slot-rollback")))
        assertEquals(10L, tx { repository.countCurrentConnectors(workspaceId) })
    }

    @Test
    fun `delete removes the connector from reads and the limit and destroys every credential version`() = runBlocking<Unit> {
        repeat(9) { assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("delete-seed-$it", "Seed $it"))) }
        val created = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("delete-target", "Warehouse"))).connector
        assertIs<ConnectorMutationResult.Committed>(service.rotateCredential(RotateConnectorCredentialCommand(
            authorization(MutationIntent.CONNECTOR_UPDATE), created.connectorId,
            ConnectorCredential.UsernamePassword("dw_query", "rotated-secret"), "delete-rotate")))
        val reference = tx { checkNotNull(repository.findConnector(created.connectorId)).credentialReference }
        assertEquals(ConnectorMutationResult.LimitExceeded, service.create(createCommand("delete-over-limit")))

        assertEquals(ConnectorDeletionResult.Deleted, service.delete(deleteCommand(created.connectorId)))

        assertEquals(listOf(created.connectorId), dependentRemovals)
        assertEquals(listOf(created.connectorId), deletedSearchConnectors)
        assertEquals(0, countRows("connector.credential_versions WHERE connector_id = '${created.connectorId}'"))
        assertEquals(1, countRows("connector.connectors WHERE connector_id = '${created.connectorId}' AND deleted_at IS NOT NULL AND credential_version_id IS NULL"))
        assertEquals(null, readTx { repository.findConnector(created.connectorId) })
        assertEquals(null, readTx { repository.findCurrentByWorkspaceAndId(workspaceId, created.connectorId) })
        assertEquals(emptyList(), tx { repository.listCurrentByWorkspace(workspaceId) }.filter { it.displayName == "Warehouse" })
        assertEquals(9, tx { repository.listCurrentByWorkspace(workspaceId) }.size)
        assertEquals(ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace, executionAccess.readCurrentForExecution(workspaceId, created.connectorId))
        assertEquals(ManagedCredentialReadOutcome.Rejected, credentials.readExact(readRequest(created.connectorId, reference)))
        assertEquals(9L, tx { repository.countCurrentConnectors(workspaceId) })

        val replacement = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("delete-replacement", "Warehouse"))).connector
        assertEquals(listOf(replacement.connectorId), tx { repository.listCurrentByWorkspace(workspaceId) }.filter { it.displayName == "Warehouse" }.map { it.connectorId })
        assertEquals(ConnectorMutationResult.Conflict, service.create(createCommand("delete-target", "Warehouse")))
        assertEquals(ConnectorMutationResult.NotFoundOrOutsideWorkspace, service.revise(reviseCommand(created.connectorId, "delete-revise", "Again", "warehouse.internal")))
        assertEquals(ConnectorDeletionResult.NotFoundOrOutsideWorkspace, service.delete(deleteCommand(created.connectorId)))
        assertEquals(1, dependentRemovals.size)
    }

    @Test
    fun `delete keeps the connector and its credential while a refresh is active`() = runBlocking<Unit> {
        val created = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("delete-refresh"))).connector
        dependentRemovalOutcome = ConnectorDependentStateRemoval.REFRESH_ACTIVE

        assertEquals(ConnectorDeletionResult.RefreshActive, service.delete(deleteCommand(created.connectorId)))

        assertEquals(1, countRows("connector.credential_versions"))
        assertEquals(0, countRows("connector.connectors WHERE deleted_at IS NOT NULL"))
        assertTrue(deletedSearchConnectors.isEmpty())
        assertIs<ConnectorExecutionAccessResult.Granted>(executionAccess.readCurrentForExecution(workspaceId, created.connectorId))
    }

    @Test
    fun `delete requires the owner delete intent and the connector workspace`() = runBlocking<Unit> {
        val created = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("delete-authorization"))).connector
        assertEquals(ConnectorDeletionResult.InvalidAuthorization,
            service.delete(DeleteConnectorCommand(authorization(MutationIntent.CONNECTOR_UPDATE), created.connectorId)))
        assertEquals(ConnectorDeletionResult.InvalidAuthorization,
            service.delete(DeleteConnectorCommand(authorization(MutationIntent.CONNECTOR_DELETE, role = WorkspaceRole.EDITOR), created.connectorId)))
        assertEquals(ConnectorDeletionResult.NotFoundOrOutsideWorkspace,
            service.delete(DeleteConnectorCommand(authorization(MutationIntent.CONNECTOR_DELETE, targetWorkspaceId = WorkspaceId(Uuid.generateV7())), created.connectorId)))
        assertTrue(dependentRemovals.isEmpty())
        assertEquals(1, countRows("connector.credential_versions"))
    }

    @Test
    fun `schema destroys credentials only with a deleted connector and freezes deleted rows`() = runBlocking<Unit> {
        val created = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("delete-schema"))).connector
        assertFailsWith<SQLException> { executeSql("DELETE FROM connector.credential_versions WHERE connector_id = '${created.connectorId}'") }
        assertFailsWith<SQLException> {
            executeSql("UPDATE connector.connectors SET credential_version_id = NULL, authority_revision = authority_revision + 1 WHERE connector_id = '${created.connectorId}'")
        }
        assertEquals(ConnectorDeletionResult.Deleted, service.delete(deleteCommand(created.connectorId)))
        assertFailsWith<SQLException> {
            executeSql("UPDATE connector.connectors SET display_name = 'Revived', authority_revision = authority_revision + 1 WHERE connector_id = '${created.connectorId}'")
        }
    }

    @Test
    fun `sample owner path persists one live credential and exact runtime dataset document`() = runBlocking<Unit> {
        markSampleWorkspace()
        val command = CreateSampleConnectorCommand(authorization(MutationIntent.CONNECTOR_CREATE), "acme-cloud@1", "sample:acme-cloud@1:connector")
        val created = assertIs<ConnectorMutationResult.Committed>(service.createSample(command)).connector
        assertEquals(created, assertIs<ConnectorMutationResult.Committed>(service.createSample(command)).connector)
        assertEquals(created, assertIs<ConnectorMutationResult.Committed>(service.createSample(command.copy(mutationIdempotencyKey = "different-request"))).connector)
        assertEquals(1, countRows("connector.connectors"))
        assertEquals(1, countRows("connector.credential_versions"))
        assertEquals(1, countRows("connector.connector_mutation_receipts"))
        val binding = assertIs<ConnectorExecutionAccessResult.Granted>(executionAccess.readCurrentForExecution(workspaceId, created.connectorId)).binding
        assertEquals("duckdb", binding.connectorType)
        assertEquals(mapOf("sample_dataset" to "acme-cloud@1"), binding.properties)
        val projected = cc.monomer.connector.application.ConnectorRuntimeBindingProjector().project(binding)
        assertEquals(cc.monomer.execution.api.ConnectorType.DUCKDB, projected.connectorType)
        assertEquals(binding.credentialReference.credentialVersionId.value, projected.managedCredential.credentialVersion.value)
        val document = credentials.readExact(ManagedCredentialReadRequest(workspaceId, created.connectorId, "duckdb", binding.credentialReference, Instant.now().plusSeconds(30)))
        assertIs<ManagedCredentialReadOutcome.Found>(document).use { found ->
            found.credentialMaterial.useFields { fields -> assertEquals(mapOf("dataset" to "acme-cloud@1"), fields.mapValues { it.value.decodeToString() }) }
            val encoded = cc.monomer.connector.application.ManagedCredentialDocumentCodec.encode(found.credentialMaterial, 1_048_576, 16, 64, 262_144)
            assertTrue(encoded.isNotEmpty())
            assertEquals(0x4d435231, java.nio.ByteBuffer.wrap(encoded).int)
            encoded.fill(0)
        }
        val current = readTx { repository.findCurrentByWorkspaceAndId(workspaceId, created.connectorId) }!!
        assertEquals(ConnectorSample("acme-cloud@1", "Acme Cloud"), current.sample)
    }

    @Test
    fun `human and agent mutations cannot create change rotate rename or delete sample connectors`() = runBlocking<Unit> {
        markSampleWorkspace()
        val created = assertIs<ConnectorMutationResult.Committed>(service.createSample(CreateSampleConnectorCommand(
            authorization(MutationIntent.CONNECTOR_CREATE), "acme-cloud@1", "sample"))).connector
        for (surface in CredentialSurface.entries) {
            assertIs<ConnectorMutationResult.InvalidInput>(service.create(CreateConnectorCommand(authorization(MutationIntent.CONNECTOR_CREATE, surface = surface),
                ConnectorProductType.DUCKDB, "User sample", ConnectorSettingsProperties.Duckdb("acme-cloud@1"), ConnectorCredential.SampleDatasetAccess("acme-cloud@1"), "user-create-$surface")))
            assertIs<ConnectorMutationResult.InvalidInput>(service.revise(ReviseConnectorCommand(authorization(MutationIntent.CONNECTOR_UPDATE, surface = surface),
                created.connectorId, listOf(ConnectorSettingsPatch("replace", "/displayName", JsonPrimitive("Renamed"))), "rename-$surface")))
            assertIs<ConnectorMutationResult.InvalidInput>(service.revise(ReviseConnectorCommand(authorization(MutationIntent.CONNECTOR_UPDATE, surface = surface),
                created.connectorId, listOf(ConnectorSettingsPatch("replace", "/properties/sampleDataset", JsonPrimitive("acme-cloud@1"))), "settings-$surface")))
            assertIs<ConnectorMutationResult.InvalidInput>(service.rotateCredential(RotateConnectorCredentialCommand(authorization(MutationIntent.CONNECTOR_UPDATE, surface = surface),
                created.connectorId, ConnectorCredential.SampleDatasetAccess("acme-cloud@1"), "rotate-$surface")))
            assertIs<ConnectorDeletionResult.InvalidInput>(service.delete(DeleteConnectorCommand(authorization(MutationIntent.CONNECTOR_DELETE, surface = surface), created.connectorId)))
            assertIs<ConnectorMutationResult.InvalidInput>(service.create(createCommand("real-$surface").copy(authorization = authorization(MutationIntent.CONNECTOR_CREATE, surface = surface))))
        }
        assertEquals(1, countRows("connector.connectors"))
        assertEquals(1, countRows("connector.credential_versions"))
        assertEquals(1, countRows("connector.connector_mutation_receipts"))
        assertTrue(dependentRemovals.isEmpty())
        assertTrue(deletedSearchConnectors.isEmpty())
        assertFailsWith<SQLException> { executeSql("UPDATE connector.connectors SET display_name='Renamed', authority_revision=authority_revision+1 WHERE connector_id='${created.connectorId}'") }
        assertFailsWith<SQLException> { executeSql("UPDATE connector.connectors SET deleted_at=now(), credential_version_id=NULL, authority_revision=authority_revision+1 WHERE connector_id='${created.connectorId}'") }
    }

    @Test
    fun `v2 sample owner path persists one live credential and exact runtime dataset document`() = runBlocking<Unit> {
        markSampleWorkspace("acme-cloud@2")
        val command = CreateSampleConnectorCommand(authorization(MutationIntent.CONNECTOR_CREATE), "acme-cloud@2", "sample:acme-cloud@2:connector")
        val created = assertIs<ConnectorMutationResult.Committed>(service.createSample(command)).connector
        assertEquals(created, assertIs<ConnectorMutationResult.Committed>(service.createSample(command)).connector)
        assertEquals(created, assertIs<ConnectorMutationResult.Committed>(service.createSample(command.copy(mutationIdempotencyKey = "different-request"))).connector)
        assertEquals(1, countRows("connector.connectors"))
        assertEquals(1, countRows("connector.credential_versions"))
        assertEquals(1, countRows("connector.connector_mutation_receipts"))
        val binding = assertIs<ConnectorExecutionAccessResult.Granted>(executionAccess.readCurrentForExecution(workspaceId, created.connectorId)).binding
        assertEquals("duckdb", binding.connectorType)
        assertEquals(mapOf("sample_dataset" to "acme-cloud@2"), binding.properties)
        val projected = cc.monomer.connector.application.ConnectorRuntimeBindingProjector().project(binding)
        assertEquals(cc.monomer.execution.api.ConnectorType.DUCKDB, projected.connectorType)
        assertEquals(binding.credentialReference.credentialVersionId.value, projected.managedCredential.credentialVersion.value)
        val document = credentials.readExact(ManagedCredentialReadRequest(workspaceId, created.connectorId, "duckdb", binding.credentialReference, Instant.now().plusSeconds(30)))
        assertIs<ManagedCredentialReadOutcome.Found>(document).use { found ->
            found.credentialMaterial.useFields { fields -> assertEquals(mapOf("dataset" to "acme-cloud@2"), fields.mapValues { it.value.decodeToString() }) }
            val encoded = cc.monomer.connector.application.ManagedCredentialDocumentCodec.encode(found.credentialMaterial, 1_048_576, 16, 64, 262_144)
            assertTrue(encoded.isNotEmpty())
            assertEquals(0x4d435231, java.nio.ByteBuffer.wrap(encoded).int)
            encoded.fill(0)
        }
        val current = readTx { repository.findCurrentByWorkspaceAndId(workspaceId, created.connectorId) }!!
        assertEquals(ConnectorSample("acme-cloud@2", "Acme Cloud"), current.sample)
    }

    @Test
    fun `v2 human and agent mutations cannot create change rotate rename or delete sample connectors`() = runBlocking<Unit> {
        markSampleWorkspace("acme-cloud@2")
        val created = assertIs<ConnectorMutationResult.Committed>(service.createSample(CreateSampleConnectorCommand(
            authorization(MutationIntent.CONNECTOR_CREATE), "acme-cloud@2", "sample"))).connector
        for (surface in CredentialSurface.entries) {
            assertIs<ConnectorMutationResult.InvalidInput>(service.create(CreateConnectorCommand(authorization(MutationIntent.CONNECTOR_CREATE, surface = surface),
                ConnectorProductType.DUCKDB, "User sample", ConnectorSettingsProperties.Duckdb("acme-cloud@2"), ConnectorCredential.SampleDatasetAccess("acme-cloud@2"), "user-create-$surface")))
            assertIs<ConnectorMutationResult.InvalidInput>(service.revise(ReviseConnectorCommand(authorization(MutationIntent.CONNECTOR_UPDATE, surface = surface),
                created.connectorId, listOf(ConnectorSettingsPatch("replace", "/displayName", JsonPrimitive("Renamed"))), "rename-$surface")))
            assertIs<ConnectorMutationResult.InvalidInput>(service.revise(ReviseConnectorCommand(authorization(MutationIntent.CONNECTOR_UPDATE, surface = surface),
                created.connectorId, listOf(ConnectorSettingsPatch("replace", "/properties/sampleDataset", JsonPrimitive("acme-cloud@2"))), "settings-$surface")))
            assertIs<ConnectorMutationResult.InvalidInput>(service.rotateCredential(RotateConnectorCredentialCommand(authorization(MutationIntent.CONNECTOR_UPDATE, surface = surface),
                created.connectorId, ConnectorCredential.SampleDatasetAccess("acme-cloud@2"), "rotate-$surface")))
            assertIs<ConnectorDeletionResult.InvalidInput>(service.delete(DeleteConnectorCommand(authorization(MutationIntent.CONNECTOR_DELETE, surface = surface), created.connectorId)))
            assertIs<ConnectorMutationResult.InvalidInput>(service.create(createCommand("real-$surface").copy(authorization = authorization(MutationIntent.CONNECTOR_CREATE, surface = surface))))
        }
        assertEquals(1, countRows("connector.connectors"))
        assertEquals(1, countRows("connector.credential_versions"))
        assertEquals(1, countRows("connector.connector_mutation_receipts"))
        assertTrue(dependentRemovals.isEmpty())
        assertTrue(deletedSearchConnectors.isEmpty())
        assertFailsWith<SQLException> { executeSql("UPDATE connector.connectors SET display_name='Renamed', authority_revision=authority_revision+1 WHERE connector_id='${created.connectorId}'") }
        assertFailsWith<SQLException> { executeSql("UPDATE connector.connectors SET deleted_at=now(), credential_version_id=NULL, authority_revision=authority_revision+1 WHERE connector_id='${created.connectorId}'") }
    }

    @Test
    fun `sample owner creation rejects absent marker wrong initiator and unknown dataset`() = runBlocking<Unit> {
        val command = CreateSampleConnectorCommand(authorization(MutationIntent.CONNECTOR_CREATE), "acme-cloud@1", "sample")
        assertEquals(ConnectorMutationResult.InvalidAuthorization, service.createSample(command))
        sampleMarkers[workspaceId] = cc.monomer.identity.api.WorkspaceSampleMarker("acme-cloud@1", cc.monomer.identity.api.WorkspaceSampleState.PROVISIONING, PrincipalId.generate())
        assertEquals(ConnectorMutationResult.InvalidAuthorization, service.createSample(command))
        markSampleWorkspace()
        assertIs<ConnectorMutationResult.InvalidInput>(service.createSample(command.copy(dataset = "acme-cloud@3")))
        assertEquals(ConnectorMutationResult.InvalidAuthorization, service.createSample(command.copy(authorization = authorization(MutationIntent.CONNECTOR_CREATE, role = WorkspaceRole.EDITOR))))
        assertEquals(0, countRows("connector.connectors"))
        assertEquals(0, countRows("connector.credential_versions"))
    }

    @Test
    fun `sample counts toward free connector limit and concurrent provisioning uses one credential`() = runBlocking<Unit> {
        repeat(9) { assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("real-$it"))) }
        markSampleWorkspace()
        val command = CreateSampleConnectorCommand(authorization(MutationIntent.CONNECTOR_CREATE), "acme-cloud@1", "sample")
        Executors.newFixedThreadPool(2).use { executor ->
            val futures = (0..1).map { index -> executor.submit<ConnectorMutationResult> { runBlocking { service.createSample(command.copy(mutationIdempotencyKey = "sample-$index")) } } }
            val connectors = futures.map { assertIs<ConnectorMutationResult.Committed>(it.get(10, TimeUnit.SECONDS)).connector }
            assertEquals(connectors.first().connectorId, connectors.last().connectorId)
        }
        assertEquals(10, countRows("connector.connectors"))
        assertEquals(10, countRows("connector.credential_versions"))
        assertIs<ConnectorMutationResult.Committed>(service.createSample(command))
        workspaceId = WorkspaceId.generate()
        repeat(10) { assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("full-$it"))) }
        markSampleWorkspace()
        assertEquals(ConnectorMutationResult.LimitExceeded, service.createSample(command.copy(authorization = authorization(MutationIntent.CONNECTOR_CREATE))))
        assertEquals(20, countRows("connector.credential_versions"))
    }

    @Test
    fun `operations milestones keep sample separate from actual warehouse connections`() = runBlocking<Unit> {
        val externalWorkspace = workspaceId
        val external = assertIs<ConnectorMutationResult.Committed>(service.create(createCommand("external"))).connector
        service.delete(deleteCommand(external.connectorId))
        workspaceId = WorkspaceId.generate()
        markSampleWorkspace()
        assertIs<ConnectorMutationResult.Committed>(service.createSample(CreateSampleConnectorCommand(authorization(MutationIntent.CONNECTOR_CREATE), "acme-cloud@1", "sample")))
        val access = cc.monomer.identity.api.OperationsAccess::class.java.getDeclaredConstructor(String::class.java, String::class.java, Instant::class.java)
            .apply { isAccessible = true }.newInstance(actorId.toString(), "test-operations", Instant.now().plusSeconds(30))
        val readOnly = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply { isReadOnly = true; timeout = 5 }
        val queries = cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorOperationsQueries(dataSource, readOnly, Dispatchers.IO)
        val ids = setOf(externalWorkspace.toString(), workspaceId.toString())
        assertEquals(mapOf(externalWorkspace.toString() to instant), queries.firstConnectorCreatedAt(access, ids))
        assertEquals(mapOf(workspaceId.toString() to instant), queries.firstSampleConnectorCreatedAt(access, ids))
        assertEquals(mapOf(workspaceId.toString() to listOf(OperationsConnectorCount("duckdb", 1))), queries.counts(access, ids))
    }

    private fun markSampleWorkspace(dataset: String = "acme-cloud@1") {
        sampleMarkers[workspaceId] = cc.monomer.identity.api.WorkspaceSampleMarker(dataset, cc.monomer.identity.api.WorkspaceSampleState.PROVISIONING, actorId)
    }

    private fun deleteCommand(connectorId: ConnectorId) =
        DeleteConnectorCommand(authorization(MutationIntent.CONNECTOR_DELETE), connectorId)

    private fun executeSql(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { statement -> statement.execute(sql) } }
    }

    private fun serviceWith(
        ownerRepository: ConnectorRepository,
        lockPort: ConnectorMutationKeyLockPort = PostgresConnectorMutationKeyLockPort(dataSource, Duration.ofSeconds(2)),
    ) = ConnectorAuthorityService(
        ownerRepository,
        SpringConnectorTransactionPort(dataSource, transactions, kotlinx.coroutines.Dispatchers.IO, ConnectorTransactionTimeouts(
            Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(2))),
        lockPort, credentials, clock, searchProjection, dependentStateRemoval, sampleQuery)

    private fun createCommand(
        mutationKey: String,
        displayName: String = "External Warehouse",
    ): CreateConnectorCommand =
        CreateConnectorCommand(
            authorization = authorization(MutationIntent.CONNECTOR_CREATE),
            connectorType = ConnectorProductType.POSTGRESQL,
            displayName = displayName,
            properties = postgresqlProperties(),
            credential = ConnectorCredential.UsernamePassword("dw_query", "initial-secret"),
            mutationIdempotencyKey = mutationKey,
        )

    private fun reviseCommand(
        connectorId: ConnectorId,
        mutationKey: String,
        displayName: String,
        host: String,
    ): ReviseConnectorCommand =
        ReviseConnectorCommand(
            authorization = authorization(MutationIntent.CONNECTOR_UPDATE),
            connectorId = connectorId,
            patch = settingsPatch(displayName, postgresqlProperties(host)),
            mutationIdempotencyKey = mutationKey,
        )

    private fun postgresqlProperties(host: String = "warehouse.internal") =
        ConnectorSettingsProperties.Postgresql(host, 5432, "warehouse", "analytics", PostgresqlSslMode.REQUIRE)

    private fun settingsPatch(name: String, properties: ConnectorSettingsProperties) =
        listOf(ConnectorSettingsPatch("replace", "", buildJsonObject {
            put("displayName", name); put("properties", ConnectorSettingsCodec.encode(properties))
        }))

    private fun readRequest(
        connectorId: ConnectorId,
        reference: CredentialEnvelopeReference,
    ): ManagedCredentialReadRequest =
        ManagedCredentialReadRequest(
            workspaceId = workspaceId,
            connectorId = connectorId,
            connectorType = "postgresql",
            reference = reference,
            serverDeadline = Instant.now().plusSeconds(30),
        )

    private fun <T> tx(block: () -> T): T = checkNotNull(transactions.execute { block() })

    private fun <T> readTx(block: () -> T?): T? = transactions.execute { block() }

    private fun transactionTemplate(readOnly: Boolean): TransactionTemplate =
        TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            timeout = 10
            isReadOnly = readOnly
        }

    private fun truncateOwnedTables() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE connector.connector_mutation_receipts, connector.connectors, connector.credential_versions CASCADE",
                )
            }
        }
    }

    private fun countRows(table: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table").use { statement ->
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun assertReceiptKeys(expected: Set<String>) {
        assertEquals(expected.size, countRows("connector.connector_mutation_receipts"))
        val actual = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT mutation_idempotency_key FROM connector.connector_mutation_receipts",
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                }
            }
        }
        assertEquals(expected.size, actual.toSet().size)
        assertEquals(expected, actual.toSet())
    }

    private fun authorization(
        intent: MutationIntent,
        plan: OrganizationPlan = OrganizationPlan.FREE,
        targetWorkspaceId: WorkspaceId = workspaceId,
        role: WorkspaceRole = WorkspaceRole.OWNER,
        surface: CredentialSurface = CredentialSurface.WEB,
    ): AuthorizedMutationContext {
        val actor = CanonicalPrincipal(actorId, PrincipalKind.USER)
        return AuthorizedMutationContext.restore(
            actor = actor,
            organizationId = organizationId,
            organizationPlan = plan,
            workspaceId = targetWorkspaceId,
            authorizationRevision = AuthorizationRevision(1),
            credentialKind = if (surface == CredentialSurface.WEB) CredentialKind.SESSION else CredentialKind.AGENT_OAUTH,
            credentialSurface = surface,
            agentClientId = if (surface == CredentialSurface.AGENT) cc.monomer.identity.api.AgentClientId("https://agent.example.test/client.json") else null,
            role = role,
            intent = intent,
            admittedAt = instant,
        )
    }
}
