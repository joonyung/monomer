@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cc.monomer.control.catalog.application.ingest

import cc.monomer.catalog.adapter.outbound.execution.CatalogInlineQueryAdapter
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogIngestTaskId
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.application.CatalogIngestRepository
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.catalog.application.ingest.CatalogIngestCascadeRunner
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobState
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskState
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogMetadataObservation
import cc.monomer.catalog.domain.ingestion.CatalogMetadataParser
import cc.monomer.catalog.domain.ingestion.CatalogQueryRow
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ExecutionRunDialCoordinator
import cc.monomer.control.jdbc.sqlStateChain
import cc.monomer.control.logExecutionRunAccepted
import cc.monomer.control.registerExecutionRunAcceptedAfterCommit
import cc.monomer.execution.adapter.inbound.grpc.ProtoInlineQueryRunSpecFactory
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.InlineQueryRunSpec
import cc.monomer.execution.api.InlineQueryRunSpecCommand
import cc.monomer.execution.api.ManagedCredentialDispatchReference
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CatalogIngestCascadeRunnerTest {
    @Test
    fun `postgres relation runs all planned queries before one completion`() = runBlocking {
        val harness = harness(
            target = relationTarget().copy(catalogName = null),
            connectorType = ConnectorType.POSTGRESQL,
            queryRows = relationRows(includeRelationships = true),
        )

        try {
            harness.runner.run(harness.job, harness.binding, RUNNER_INSTANCE_ID)

            assertEquals(3, harness.commands.size)
            assertEquals(1, harness.completeCallsCount)
            assertEquals(CatalogIngestTaskState.SUCCEEDED, harness.currentTaskState)
            assertEquals(1, harness.relationObservationsValue.size)
            assertEquals(1, harness.columnObservationsValue.size)
            assertEquals(1, harness.relationshipObservationsValue.size)
            assertTrue(harness.relationObservationsValue.all { it.catalog == null })
            assertTrue(harness.columnObservationsValue.all { it.catalog == null })
            assertTrue(harness.relationshipObservationsValue.all { it.catalog == null })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `trino relation runs its two-query plan before one completion`() = runBlocking {
        val harness = harness(
            target = relationTarget(),
            connectorType = ConnectorType.TRINO,
            queryRows = relationRows(includeRelationships = false),
        )

        try {
            harness.runner.run(harness.job, harness.binding, RUNNER_INSTANCE_ID)

            assertEquals(2, harness.commands.size)
            assertEquals(1, harness.completeCallsCount)
            assertTrue(harness.relationshipObservationsValue.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `failure in a later relation query does not complete the relation`() = runBlocking {
        val harness = harness(
            target = relationTarget().copy(catalogName = null),
            connectorType = ConnectorType.POSTGRESQL,
            queryRows = relationRows(includeRelationships = true),
            failedQueryIndex = 2,
        )

        try {
            harness.runner.run(harness.job, harness.binding, RUNNER_INSTANCE_ID)

            assertEquals(3, harness.commands.size)
            assertEquals(0, harness.completeCallsCount)
            assertEquals(CatalogIngestTaskState.FAILED, harness.currentTaskState)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `cancellation before relation apply leaves the relation unmodified`() = runBlocking {
        val harness = harness(
            target = relationTarget(),
            connectorType = ConnectorType.TRINO,
            queryRows = relationRows(includeRelationships = false),
            cancelAfterQueryCount = 2,
        )

        try {
            harness.runner.run(harness.job, harness.binding, RUNNER_INSTANCE_ID)

            assertEquals(2, harness.commands.size)
            assertEquals(0, harness.completeCallsCount)
            assertEquals(CatalogIngestTaskState.CANCELLED, harness.currentTaskState)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `container applies self metadata and children together before task success`() = runBlocking {
        val harness = harness(
            target = CatalogIngestTaskTarget.Catalog("warehouse"),
            connectorType = ConnectorType.TRINO,
            enforceTargetKey = true,
            queryRows = listOf(
                listOf(CatalogQueryRow(mapOf("catalog_name" to "warehouse"))),
                emptyList(),
            ),
        )

        try {
            harness.runner.run(harness.job, harness.binding, RUNNER_INSTANCE_ID)

            assertEquals(2, harness.commands.size)
            assertEquals(1, harness.containerCallsCount)
            assertEquals(
                listOf(CatalogMetadataObservation.Catalog("warehouse")),
                harness.selfObservationsValue,
            )
            assertTrue(harness.childrenValue.isEmpty())
            assertEquals(listOf("container-apply", "task-update"), harness.events)
            assertEquals(CatalogIngestTaskState.SUCCEEDED, harness.currentTaskState)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `postgres connector matches a schema selection without a catalog address`() = runBlocking {
        val harness = harness(
            target = CatalogIngestTaskTarget.Connector,
            connectorType = ConnectorType.POSTGRESQL,
            selection = exactSelection(null, "analytics", "orders"),
            queryRows = listOf(
                listOf(CatalogQueryRow(mapOf("catalog_name" to "warehouse", "schema_name" to "analytics"))),
            ),
            createChildTasks = false,
        )

        try {
            harness.runner.run(harness.job, harness.binding, RUNNER_INSTANCE_ID)

            assertEquals(
                listOf(CatalogIngestTaskTarget.Schema(null, "analytics")),
                harness.childrenValue,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `postgres schema matches exact relations without a catalog address`() = runBlocking {
        val harness = harness(
            target = CatalogIngestTaskTarget.Schema(null, "analytics"),
            connectorType = ConnectorType.POSTGRESQL,
            selection = exactSelection(null, "analytics", "orders"),
            queryRows = listOf(
                listOf(CatalogQueryRow(mapOf("catalog_name" to "warehouse", "schema_name" to "analytics"))),
                listOf(
                    CatalogQueryRow(mapOf("catalog_name" to "warehouse", "schema_name" to "analytics", "table_name" to "orders", "table_type" to "BASE TABLE")),
                    CatalogQueryRow(mapOf("catalog_name" to "warehouse", "schema_name" to "analytics", "table_name" to "customers", "table_type" to "BASE TABLE")),
                ),
            ),
            createChildTasks = false,
        )

        try {
            harness.runner.run(harness.job, harness.binding, RUNNER_INSTANCE_ID)

            assertEquals(
                listOf(CatalogIngestTaskTarget.Relation(null, "analytics", "orders", RelationKind.TABLE)),
                harness.childrenValue,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `foreign database observation fails a relation without applying current metadata`() = runBlocking {
        val rows = relationRows(includeRelationships = true).map { batch ->
            batch.map { it.copy(values = it.values + ("catalog_name" to "other_database")) }
        }
        val harness = harness(relationTarget().copy(catalogName = null), ConnectorType.POSTGRESQL, queryRows = rows)
        try {
            harness.runner.run(harness.job, harness.binding, RUNNER_INSTANCE_ID)
            assertEquals(0, harness.completeCallsCount)
            assertEquals(CatalogIngestTaskState.FAILED, harness.currentTaskState)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `redshift native dev metadata yields null product addresses and scoped SQL`() = runBlocking {
        val harness = harness(relationTarget().copy(catalogName = null), ConnectorType.REDSHIFT, queryRows = redshiftRelationRows())
        try {
            val binding = harness.binding.copy(properties = harness.binding.properties + ("database" to ConnectorRuntimePropertyValue.Text("dev")))
            harness.runner.run(harness.job, binding, RUNNER_INSTANCE_ID)
            assertEquals(4, harness.commands.size)
            assertEquals(listOf(
                "SHOW CONSTRAINTS PRIMARY KEYS FROM TABLE \"dev\".\"analytics\".\"orders\"",
                "SHOW CONSTRAINTS FOREIGN KEYS FROM TABLE \"dev\".\"analytics\".\"orders\"",
            ), harness.commands.takeLast(2))
            assertEquals(listOf("orders_pkey", "orders_customer_fkey"), harness.relationshipObservationsValue.map { it.constraintName })
            assertEquals(1, harness.completeCallsCount)
            assertEquals(CatalogIngestTaskState.SUCCEEDED, harness.currentTaskState)
            assertTrue(harness.relationObservationsValue.all { it.catalog == null })
            assertTrue(harness.columnObservationsValue.all { it.catalog == null })
            assertTrue(harness.relationshipObservationsValue.all { it.catalog == null })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `redshift primary or foreign key query failure preserves all previous relation metadata`() = runBlocking {
        listOf(2, 3).forEach { failedQueryIndex ->
            val harness = harness(relationTarget().copy(catalogName = null), ConnectorType.REDSHIFT, queryRows = redshiftRelationRows(), failedQueryIndex = failedQueryIndex)
            val repository = harness.repository as FakeCatalogIngestRepository
            val previousRows = relationRows(includeRelationships = true)
            val previousRelations = CatalogMetadataParser.relations(previousRows[0].asSequence())
            val previousColumns = CatalogMetadataParser.columns(previousRows[1].asSequence())
            val previousRelationships = CatalogMetadataParser.relationships(previousRows[2].asSequence())
            repository.relationObservations = previousRelations
            repository.columnObservations = previousColumns
            repository.relationshipObservations = previousRelationships
            try {
                val binding = harness.binding.copy(properties = harness.binding.properties + ("database" to ConnectorRuntimePropertyValue.Text("dev")))
                harness.runner.run(harness.job, binding, RUNNER_INSTANCE_ID)
                assertEquals(failedQueryIndex + 1, harness.commands.size)
                assertEquals(0, harness.completeCallsCount)
                assertEquals(CatalogIngestTaskState.FAILED, harness.currentTaskState)
                assertEquals(previousRelations, harness.relationObservationsValue)
                assertEquals(previousColumns, harness.columnObservationsValue)
                assertEquals(previousRelationships, harness.relationshipObservationsValue)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun `redshift view with no keys completes after both empty constraint queries`() = runBlocking {
        val rows = redshiftRelationRows().take(2).map { batch -> batch.map { row ->
            row.copy(values = row.values + ("table_type" to "VIEW"))
        } } + listOf(emptyList(), emptyList())
        val harness = harness(relationTarget().copy(catalogName = null, kind = RelationKind.VIEW), ConnectorType.REDSHIFT, queryRows = rows)
        try {
            val binding = harness.binding.copy(properties = harness.binding.properties + ("database" to ConnectorRuntimePropertyValue.Text("dev")))
            harness.runner.run(harness.job, binding, RUNNER_INSTANCE_ID)
            assertEquals(4, harness.commands.size)
            assertEquals(1, harness.completeCallsCount)
            assertEquals(CatalogIngestTaskState.SUCCEEDED, harness.currentTaskState)
            assertEquals(RelationKind.VIEW, harness.relationObservationsValue.single().relationKind)
            assertTrue(harness.relationshipObservationsValue.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a failing heartbeat does not cancel the traversal or strand the job`() = runTest {
        val fixture = TaskStoreFixture(CatalogSelection(emptySet()), queryCount = 1)
        fixture.repository.failHeartbeats = true
        coEvery { fixture.dialCoordinator.runInline(fixture.runIds[0], any()) } coAnswers {
            delay(Duration.ofMinutes(3).toMillis())
            fixture.succeeded(fixture.runIds[0])
        }

        try {
            val finished = fixture.runner.run(fixture.job, fixture.binding, RUNNER_INSTANCE_ID)

            assertTrue(fixture.repository.heartbeatAttempts >= 2)
            assertEquals(CatalogIngestJobState.COMPLETED, finished.state)
            assertEquals(CatalogIngestTaskState.SUCCEEDED, fixture.repository.tasks.getValue(fixture.entryTask.id).state)
        } finally {
            unmockkObject(ExecutionRunId.Companion)
        }
    }

    @Test
    fun `a failed child task creation lets its siblings finish before failing the parent`() = runTest {
        val fixture = TaskStoreFixture(
            CatalogSelection(
                setOf(
                    CatalogSelectedSchema("alpha", CatalogRelationSelection.AllRelations),
                    CatalogSelectedSchema("beta", CatalogRelationSelection.AllRelations),
                ),
            ),
            queryCount = 2,
        )
        fixture.repository.failChildCreationKey = CatalogIngestTaskTarget.Schema(null, "alpha").key
        fixture.decodedRows += listOf(
            CatalogQueryRow(mapOf("catalog_name" to "warehouse", "schema_name" to "alpha")),
            CatalogQueryRow(mapOf("catalog_name" to "warehouse", "schema_name" to "beta")),
        )
        coEvery { fixture.dialCoordinator.runInline(fixture.runIds[0], any()) } coAnswers {
            secondArg<suspend (ExecutionRunEvent.InlineResultBatch) -> Unit>()(
                ExecutionRunEvent.InlineResultBatch.fromPayload(1uL, BinaryValue.copyOf(byteArrayOf(1))),
            )
            fixture.succeeded(fixture.runIds[0])
        }
        val siblingQuery = CompletableDeferred<Unit>()
        coEvery { fixture.dialCoordinator.runInline(fixture.runIds[1], any()) } coAnswers {
            siblingQuery.await()
            PhysicalOutcome("${fixture.runIds[1]}:terminal", PhysicalOutcomeKind.FAILED, "QUERY_FAILED", "Catalog query failed")
        }

        try {
            val running = async { fixture.runner.run(fixture.job, fixture.binding, RUNNER_INSTANCE_ID) }
            runCurrent()
            val sibling = fixture.repository.tasks.values.single { it.target == CatalogIngestTaskTarget.Schema(null, "beta") }
            assertEquals(CatalogIngestTaskState.RUNNING, sibling.state)
            siblingQuery.complete(Unit)
            val finished = running.await()

            assertEquals(CatalogIngestTaskState.FAILED, fixture.repository.tasks.getValue(sibling.id).state)
            assertEquals("QUERY_FAILED", fixture.repository.tasks.getValue(sibling.id).failureCode)
            assertEquals(CatalogIngestTaskState.FAILED, fixture.repository.tasks.getValue(fixture.entryTask.id).state)
            assertTrue(fixture.repository.tasks.values.none { it.state == CatalogIngestTaskState.RUNNING })
            assertEquals(CatalogIngestJobState.COMPLETED_WITH_ERRORS, finished.state)
        } finally {
            unmockkObject(ExecutionRunId.Companion)
        }
    }

    private fun redshiftRelationRows(): List<List<CatalogQueryRow>> =
        relationRows(includeRelationships = false).map { batch ->
            batch.map { it.copy(values = it.values + ("catalog_name" to "dev")) }
        } + listOf(
            listOf(CatalogQueryRow(mapOf(
                "database_name" to "dev", "schema_name" to "analytics", "table_name" to "orders",
                "pk_name" to "orders_pkey", "column_name" to "order_id", "key_seq" to "1",
            ))),
            listOf(CatalogQueryRow(mapOf(
                "fk_database_name" to "dev", "fk_schema_name" to "analytics", "fk_table_name" to "orders",
                "fk_name" to "orders_customer_fkey", "fk_column_name" to "order_id", "key_seq" to "1",
                "pk_database_name" to "dev", "pk_schema_name" to "analytics", "pk_table_name" to "customers",
                "pk_column_name" to "id",
            ))),
        )

    private fun harness(
        target: CatalogIngestTaskTarget,
        connectorType: ConnectorType,
        selection: CatalogSelection = CatalogSelection(emptySet()),
        queryRows: List<List<CatalogQueryRow>>,
        failedQueryIndex: Int? = null,
        cancelAfterQueryCount: Int? = null,
        createChildTasks: Boolean = true,
        enforceTargetKey: Boolean = false,
    ): Harness {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val entry = when (target) {
            is CatalogIngestTaskTarget.Catalog -> CatalogIngestEntry.Catalog(connectorId, target.name)
            else -> CatalogIngestEntry.Connector(connectorId)
        }
        val job = CatalogIngestJob.request(
            id = CatalogIngestJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = selection,
            entry = entry,
            requestedBy = PrincipalId.generate(),
            requestedAt = now,
            credentialSurface = null,
            agentClientId = null,
        ).claim(RUNNER_INSTANCE_ID, now)
        val task = CatalogIngestTask.start(job, null, target, now)
        val binding = ConnectorRuntimeBindingSpec(
            connectorId = ConnectorReference(connectorId.value),
            connectorType = connectorType,
            properties = mapOf(
                "host" to ConnectorRuntimePropertyValue.Text("localhost"),
                "database" to ConnectorRuntimePropertyValue.Text("warehouse"),
            ),
            managedCredential = ManagedCredentialDispatchReference(
                credentialVersion = CredentialVersionReference(Uuid.generateV7()),
                keyVersion = "test-key-v1",
                envelopeDigest = Sha256Digest("0".repeat(64)),
            ),
        )
        var completedQueryCount = 0
        val commands = mutableListOf<String>()
        val rows = ArrayDeque(queryRows)
        val repository = FakeCatalogIngestRepository(
            initialJob = job,
            initialTask = task,
            cancelAfterQueryCount = cancelAfterQueryCount,
            queryCount = { completedQueryCount },
            createChildTasks = createChildTasks,
            enforceTargetKey = enforceTargetKey,
        )

        val runSpec = mockk<InlineQueryRunSpec>(relaxed = true)
        every { runSpec.canonicalRequestDigest } returns Sha256Digest("1".repeat(64))
        every { runSpec.runtimeBindings } returns RunRuntimeBindings(
            connectorBindingDigest = Sha256Digest("2".repeat(64)),
            credentialVersion = binding.managedCredential.credentialVersion,
            credentialKeyVersion = binding.managedCredential.keyVersion,
            credentialBindingDigest = Sha256Digest("3".repeat(64)),
            resultSink = ResultSinkBinding(
                schemaVersion = 2u,
                kind = ResultSinkKind.INLINE_STREAM,
                objectKey = null,
                maximumArtifactRows = null,
                maximumArtifactBytes = null,
                expiresAt = null,
                maximumRecordBatchRows = 128,
                maximumFrameBytes = 8 * 1024,
                maximumCellBytes = 4 * 1024,
                maximumMultipartPartBytes = null,
                bindingDigest = Sha256Digest("4".repeat(64)),
            ),
        )
        every { runSpec.assignmentTemplate } returns BinaryValue.copyOf(byteArrayOf(1))
        val runSpecFactory = mockk<ProtoInlineQueryRunSpecFactory>()
        every { runSpecFactory.create(any()) } answers {
            val command = invocation.args[0] as InlineQueryRunSpecCommand
            commands += command.sql
            runSpec
        }
        val runIds = queryRows.indices.map { ExecutionRunId(Uuid.generateV7()) }
        mockkObject(ExecutionRunId.Companion)
        every { ExecutionRunId.generate() } returnsMany runIds
        val admission = object : ExecutionRunAdmissionPort {
            override fun lockWorkspaceInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId) = Unit
            override fun hasUnreleasedOccupancyInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId, runIds: Set<cc.monomer.execution.api.ExecutionRunId>): Boolean = false
            override fun reserveInOwnerTransaction(
                command: ExecutionRunAdmissionPort.ReserveCommand,
            ): ExecutionRunAdmissionPort.ReserveOutcome {
                val snapshot = mockk<cc.monomer.execution.api.ExecutionRunSnapshot>(relaxed = true)
                every { snapshot.id } returns command.runId
                every { snapshot.workspaceId } returns command.workspaceId
                every { snapshot.owner } returns command.owner
                every { snapshot.runtimeBindings } returns command.runtimeBindings
                return ExecutionRunAdmissionPort.ReserveOutcome.Reserved(snapshot)
            }
        }
        val dialCoordinator = mockk<ExecutionRunDialCoordinator>()
        runIds.forEachIndexed { queryIndex, runId ->
            coEvery { dialCoordinator.runInline(runId, any()) } coAnswers {
                val deliverBatch = secondArg<suspend (ExecutionRunEvent.InlineResultBatch) -> Unit>()
                completedQueryCount += 1
                if (queryIndex != failedQueryIndex) {
                    deliverBatch(
                        ExecutionRunEvent.InlineResultBatch.fromPayload(
                            sequence = 1uL,
                            arrowIpc = BinaryValue.copyOf(byteArrayOf(1)),
                        ),
                    )
                }
                PhysicalOutcome(
                    terminalIdempotencyKey = "$runId:terminal",
                    kind = if (queryIndex == failedQueryIndex) PhysicalOutcomeKind.FAILED else PhysicalOutcomeKind.SUCCEEDED,
                    failureCode = if (queryIndex == failedQueryIndex) "QUERY_FAILED" else null,
                    safeMessage = if (queryIndex == failedQueryIndex) "Catalog query failed" else null,
                )
            }
        }

        val runner = CatalogIngestCascadeRunner(
            repository = repository,
            transactionPort = DirectCatalogTransactionPort(),
            createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                CatalogInlineQueryAdapter(
                    runSpecFactory = runSpecFactory,
                    admittedRunAdmission = admission,
                    dispatchReservationDuration = Duration.ofSeconds(5),
                    executionDeadlineDuration = Duration.ofMinutes(5),
                    maximumRecordBatchRows = 128,
                    maximumRecordBatchBytes = 8 * 1024,
                    maximumCellBytes = 4 * 1024,
                    connectorBinding = capturedBinding,
                    transactionPort = ownerTransaction,
                    workspaceId = capturedWorkspaceId,
                    owner = capturedOwner,
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                    runInline = dialCoordinator::runInline,
                    registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                    logAcceptance = ::logExecutionRunAccepted,
                )
            },
            clock = Clock.fixed(now, ZoneOffset.UTC),
            heartbeatInterval = Duration.ofMinutes(1),
            decodeBatch = { rows.removeFirst() },
            failureSqlStates = { it.sqlStateChain() },
        )
        return Harness(
            runner = runner,
            job = job,
            binding = binding,
            repository = repository,
            task = task,
            commands = commands,
            events = repository.events,
            currentTask = { repository.task },
            completeCalls = { repository.completeCalls },
            containerCalls = { repository.containerCalls },
            selfObservations = { repository.selfObservations },
            children = { repository.children },
            relationObservations = { repository.relationObservations },
            columnObservations = { repository.columnObservations },
            relationshipObservations = { repository.relationshipObservations },
        )
    }

    private fun relationTarget() = CatalogIngestTaskTarget.Relation(
        catalogName = "warehouse",
        schemaName = "analytics",
        name = "orders",
        kind = RelationKind.TABLE,
    )

    private fun exactSelection(catalogName: String?, schemaName: String, relationName: String) =
        CatalogSelection(
            selectedSchemas = setOf(
                CatalogSelectedSchema(
                    schemaName = schemaName,
                    relations = CatalogRelationSelection.SelectedRelations(setOf(relationName)),
                ),
            ),
        )

    private fun relationRows(includeRelationships: Boolean): List<List<CatalogQueryRow>> = buildList {
        add(
            listOf(
                CatalogQueryRow(
                    mapOf(
                        "catalog_name" to "warehouse",
                        "schema_name" to "analytics",
                        "table_name" to "orders",
                        "table_type" to "BASE TABLE",
                    ),
                ),
            ),
        )
        add(
            listOf(
                CatalogQueryRow(
                    mapOf(
                        "catalog_name" to "warehouse",
                        "schema_name" to "analytics",
                        "relation_name" to "orders",
                        "column_name" to "order_id",
                        "ordinal_position" to "1",
                        "native_type" to "bigint",
                        "is_nullable" to "NO",
                        "default_expression" to null,
                    ),
                ),
            ),
        )
        if (includeRelationships) {
            add(
                listOf(
                    CatalogQueryRow(
                        mapOf(
                            "catalog_name" to "warehouse",
                            "schema_name" to "analytics",
                            "relation_name" to "orders",
                            "constraint_name" to "orders_pkey",
                            "relationship_kind" to "PRIMARY_KEY",
                            "member_ordinal" to "1",
                            "source_column" to "order_id",
                            "referenced_catalog" to null,
                            "referenced_schema" to null,
                            "referenced_relation" to null,
                            "referenced_column" to null,
                        ),
                    ),
                ),
            )
        }
    }

    private class FakeCatalogIngestRepository(
        initialJob: CatalogIngestJob,
        initialTask: CatalogIngestTask,
        private val cancelAfterQueryCount: Int?,
        private val queryCount: () -> Int,
        private val createChildTasks: Boolean,
        private val enforceTargetKey: Boolean,
    ) : CatalogIngestRepository {
        var job: CatalogIngestJob = initialJob
        var task: CatalogIngestTask = initialTask
        val events = mutableListOf<String>()
        var completeCalls = 0
        var containerCalls = 0
        var selfObservations = emptyList<CatalogMetadataObservation>()
        var children = emptyList<CatalogIngestTaskTarget>()
        var relationObservations = emptyList<CatalogMetadataObservation.Relation>()
        var columnObservations = emptyList<CatalogMetadataObservation.Column>()
        var relationshipObservations = emptyList<CatalogMetadataObservation.RelationshipMember>()

        override fun removeCurrentCatalogSubtree(
            workspaceId: WorkspaceId,
            connectorId: ConnectorId,
            catalogName: String,
        ) = error("unused")

        override fun findIngestJob(id: CatalogIngestJobId): CatalogIngestJob? {
            requireOwnerTransaction()
            return currentJob(id)
        }

        override fun findIngestJobSummary(id: CatalogIngestJobId) = error("unused")

        override fun findActiveIngestJob(workspaceId: WorkspaceId, entryKey: String): CatalogIngestJob? = error("unused")

        override fun findIngestJobPage(
            workspaceId: WorkspaceId,
            connectorId: ConnectorId,
            entryKey: String,
            page: Int,
            pageSize: Int,
        ) = error("unused")

        override fun findIngestJobsAfter(
            workspaceId: WorkspaceId,
            connectorId: ConnectorId,
            entryKey: String,
            requestedBefore: Instant?,
            jobIdBefore: CatalogIngestJobId?,
            limit: Int,
        ) = error("unused")

        override fun findIngestErrorPage(jobId: CatalogIngestJobId, page: Int, pageSize: Int) = error("unused")

        override fun findIngestErrorsAfter(
            jobId: CatalogIngestJobId,
            terminalAfter: Instant?,
            taskIdAfter: CatalogIngestTaskId?,
            limit: Int,
        ) = error("unused")

        override fun findUnownedRunningIngestJobs(maximumJobs: Int): List<CatalogIngestJob> = error("unused")

        override fun findStaleIngestJobs(heartbeatBefore: Instant, maximumJobs: Int): List<CatalogIngestJob> = error("unused")

        override fun insertIngestJob(job: CatalogIngestJob) = error("unused")

        override fun updateIngestJob(job: CatalogIngestJob): Boolean {
            this.job = job
            return true
        }

        override fun updateOwnedIngestJob(job: CatalogIngestJob, runnerInstanceId: String): Boolean {
            this.job = job
            return true
        }

        override fun claimIngestJob(
            jobId: CatalogIngestJobId,
            runnerInstanceId: String,
            heartbeatAt: Instant,
        ): CatalogIngestJob? = error("unused")

        override fun heartbeatIngestJob(
            jobId: CatalogIngestJobId,
            runnerInstanceId: String,
            heartbeatAt: Instant,
        ): Boolean = true

        override fun lockIngestJob(jobId: CatalogIngestJobId): CatalogIngestJob? = currentJob(jobId)

        override fun failStaleIngestJob(
            jobId: CatalogIngestJobId,
            now: Instant,
            staleAfter: Duration,
        ): CatalogIngestJob? = error("unused")

        override fun findIngestTask(id: CatalogIngestTaskId): CatalogIngestTask? =
            task.takeIf { it.id == id }

        override fun findIngestTask(jobId: CatalogIngestJobId, targetKey: String): CatalogIngestTask? {
            requireOwnerTransaction()
            return task.takeIf {
                it.jobId == jobId &&
                    if (enforceTargetKey) {
                        it.target.key == targetKey
                    } else {
                        when (it.target) {
                            CatalogIngestTaskTarget.Connector,
                            is CatalogIngestTaskTarget.Catalog,
                            -> it.target.key == targetKey

                            else -> true
                        }
                    }
            }
        }

        override fun findIngestTasks(jobId: CatalogIngestJobId): List<CatalogIngestTask> =
            listOfNotNull(task.takeIf { it.jobId == jobId })

        override fun findRunningIngestTasks(jobId: CatalogIngestJobId) = error("unused")

        override fun findOrInsertOwnedIngestTask(
            task: CatalogIngestTask,
            runnerInstanceId: String,
        ): CatalogIngestTask? {
            if (!createChildTasks) return null
            if (job.state != cc.monomer.catalog.domain.CatalogIngestJobState.RUNNING) return null
            if (job.runnerInstanceId != runnerInstanceId) return null
            if (this.task.target.key == task.target.key) return this.task
            this.task = task
            return task
        }

        override fun updateOwnedIngestTask(task: CatalogIngestTask, runnerInstanceId: String): Boolean {
            this.task = task
            events += "task-update"
            return true
        }

        override fun setActiveRun(
            taskId: CatalogIngestTaskId,
            runId: ExecutionRunId,
            runnerInstanceId: String,
        ): Boolean {
            if (task.id != taskId) return false
            task = task.copy(activeRunId = runId)
            return true
        }

        override fun applyContainer(
            task: CatalogIngestTask,
            selfObservations: List<CatalogMetadataObservation>,
            children: List<CatalogIngestTaskTarget>,
            runnerInstanceId: String,
            observedAt: Instant,
        ) {
            containerCalls += 1
            this.selfObservations = selfObservations
            this.children = children
            events += "container-apply"
        }

        override fun completeRelationTask(
            task: CatalogIngestTask,
            relationObservations: List<CatalogMetadataObservation.Relation>,
            columnObservations: List<CatalogMetadataObservation.Column>,
            relationshipObservations: List<CatalogMetadataObservation.RelationshipMember>,
            runnerInstanceId: String,
            observedAt: Instant,
        ) {
            completeCalls += 1
            this.relationObservations = relationObservations
            this.columnObservations = columnObservations
            this.relationshipObservations = relationshipObservations
            this.task = this.task.succeed(observedAt)
        }

        private fun currentJob(jobId: CatalogIngestJobId): CatalogIngestJob? {
            if (job.id != jobId) return null
            if (
                cancelAfterQueryCount != null &&
                queryCount() >= cancelAfterQueryCount &&
                job.state == cc.monomer.catalog.domain.CatalogIngestJobState.RUNNING
            ) {
                job = job.requestCancellation()
            }
            return job
        }
    }

    private class TaskStoreFixture(selection: CatalogSelection, queryCount: Int) {
        private val now = Instant.parse("2026-08-20T00:00:00Z")
        private val connectorId = ConnectorId.generate()
        val job: CatalogIngestJob = CatalogIngestJob.request(
            id = CatalogIngestJobId.generate(),
            workspaceId = WorkspaceId.generate(),
            connectorId = connectorId,
            selection = selection,
            entry = CatalogIngestEntry.Connector(connectorId),
            requestedBy = PrincipalId.generate(),
            requestedAt = now,
            credentialSurface = null,
            agentClientId = null,
        ).claim(RUNNER_INSTANCE_ID, now)
        val entryTask: CatalogIngestTask = CatalogIngestTask.start(job, null, CatalogIngestTaskTarget.Connector, now)
        val repository = TaskStoreCatalogIngestRepository(job, entryTask)
        val binding = ConnectorRuntimeBindingSpec(
            connectorId = ConnectorReference(connectorId.value),
            connectorType = ConnectorType.POSTGRESQL,
            properties = mapOf(
                "host" to ConnectorRuntimePropertyValue.Text("localhost"),
                "database" to ConnectorRuntimePropertyValue.Text("warehouse"),
            ),
            managedCredential = ManagedCredentialDispatchReference(
                credentialVersion = CredentialVersionReference(Uuid.generateV7()),
                keyVersion = "test-key-v1",
                envelopeDigest = Sha256Digest("0".repeat(64)),
            ),
        )
        val runIds: List<ExecutionRunId> = List(queryCount) { ExecutionRunId(Uuid.generateV7()) }
        val dialCoordinator = mockk<ExecutionRunDialCoordinator>()
        val decodedRows = mutableListOf<CatalogQueryRow>()
        val runner: CatalogIngestCascadeRunner

        init {
            val runSpec = mockk<InlineQueryRunSpec>(relaxed = true)
            every { runSpec.canonicalRequestDigest } returns Sha256Digest("1".repeat(64))
            every { runSpec.assignmentTemplate } returns BinaryValue.copyOf(byteArrayOf(1))
            val runSpecFactory = mockk<ProtoInlineQueryRunSpecFactory>()
            every { runSpecFactory.create(any()) } returns runSpec
            mockkObject(ExecutionRunId.Companion)
            every { ExecutionRunId.generate() } returnsMany runIds
            val admission = object : ExecutionRunAdmissionPort {
                override fun lockWorkspaceInOwnerTransaction(workspaceId: WorkspaceId) = Unit
                override fun hasUnreleasedOccupancyInOwnerTransaction(workspaceId: WorkspaceId, runIds: Set<ExecutionRunId>): Boolean = false
                override fun reserveInOwnerTransaction(
                    command: ExecutionRunAdmissionPort.ReserveCommand,
                ): ExecutionRunAdmissionPort.ReserveOutcome {
                    val snapshot = mockk<cc.monomer.execution.api.ExecutionRunSnapshot>(relaxed = true)
                    every { snapshot.id } returns command.runId
                    every { snapshot.workspaceId } returns command.workspaceId
                    every { snapshot.owner } returns command.owner
                    every { snapshot.runtimeBindings } returns command.runtimeBindings
                    return ExecutionRunAdmissionPort.ReserveOutcome.Reserved(snapshot)
                }
            }
            runner = CatalogIngestCascadeRunner(
                repository = repository,
                transactionPort = DirectCatalogTransactionPort(),
                createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                    CatalogInlineQueryAdapter(
                        runSpecFactory = runSpecFactory,
                        admittedRunAdmission = admission,
                        dispatchReservationDuration = Duration.ofSeconds(5),
                        executionDeadlineDuration = Duration.ofMinutes(5),
                        maximumRecordBatchRows = 128,
                        maximumRecordBatchBytes = 8 * 1024,
                        maximumCellBytes = 4 * 1024,
                        connectorBinding = capturedBinding,
                        transactionPort = ownerTransaction,
                        workspaceId = capturedWorkspaceId,
                        owner = capturedOwner,
                        clock = Clock.fixed(now, ZoneOffset.UTC),
                        runInline = dialCoordinator::runInline,
                        registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                        logAcceptance = ::logExecutionRunAccepted,
                    )
                },
                clock = Clock.fixed(now, ZoneOffset.UTC),
                heartbeatInterval = Duration.ofMinutes(1),
                decodeBatch = { decodedRows.toList() },
                failureSqlStates = { it.sqlStateChain() },
            )
        }

        fun succeeded(runId: ExecutionRunId) = PhysicalOutcome("$runId:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null)
    }

    private class TaskStoreCatalogIngestRepository(
        initialJob: CatalogIngestJob,
        entryTask: CatalogIngestTask,
    ) : CatalogIngestRepository by mockk<CatalogIngestRepository>() {
        var job: CatalogIngestJob = initialJob
        val tasks = linkedMapOf(entryTask.id to entryTask)
        var failHeartbeats = false
        var heartbeatAttempts = 0
        var failChildCreationKey: String? = null

        override fun findIngestJob(id: CatalogIngestJobId): CatalogIngestJob? {
            requireOwnerTransaction()
            return job.takeIf { it.id == id }
        }

        override fun lockIngestJob(jobId: CatalogIngestJobId): CatalogIngestJob? = job.takeIf { it.id == jobId }

        override fun updateOwnedIngestJob(job: CatalogIngestJob, runnerInstanceId: String): Boolean {
            this.job = job
            return true
        }

        override fun heartbeatIngestJob(jobId: CatalogIngestJobId, runnerInstanceId: String, heartbeatAt: Instant): Boolean {
            heartbeatAttempts += 1
            check(!failHeartbeats) { "Injected heartbeat database failure" }
            return true
        }

        override fun findIngestTask(id: CatalogIngestTaskId): CatalogIngestTask? = tasks[id]

        override fun findIngestTask(jobId: CatalogIngestJobId, targetKey: String): CatalogIngestTask? {
            requireOwnerTransaction()
            return tasks.values.firstOrNull { it.jobId == jobId && it.target.key == targetKey }
        }

        override fun findIngestTasks(jobId: CatalogIngestJobId): List<CatalogIngestTask> =
            tasks.values.filter { it.jobId == jobId }

        override fun findOrInsertOwnedIngestTask(task: CatalogIngestTask, runnerInstanceId: String): CatalogIngestTask? {
            check(task.target.key != failChildCreationKey) { "Injected child task database failure" }
            tasks.values.firstOrNull { it.target.key == task.target.key }?.let { return it }
            tasks[task.id] = task
            return task
        }

        override fun updateOwnedIngestTask(task: CatalogIngestTask, runnerInstanceId: String): Boolean {
            tasks[task.id] = task
            return true
        }

        override fun setActiveRun(taskId: CatalogIngestTaskId, runId: ExecutionRunId, runnerInstanceId: String): Boolean {
            tasks[taskId] = tasks.getValue(taskId).copy(activeRunId = runId)
            return true
        }

        override fun applyContainer(
            task: CatalogIngestTask,
            selfObservations: List<CatalogMetadataObservation>,
            children: List<CatalogIngestTaskTarget>,
            runnerInstanceId: String,
            observedAt: Instant,
        ) = Unit
    }

    private data class Harness(
        val runner: CatalogIngestCascadeRunner,
        val job: CatalogIngestJob,
        val binding: ConnectorRuntimeBindingSpec,
        val repository: CatalogIngestRepository,
        val task: CatalogIngestTask,
        val commands: MutableList<String>,
        val events: MutableList<String>,
        val currentTask: () -> CatalogIngestTask,
        val completeCalls: () -> Int,
        val containerCalls: () -> Int,
        val selfObservations: () -> List<CatalogMetadataObservation>,
        val children: () -> List<CatalogIngestTaskTarget>,
        val relationObservations: () -> List<CatalogMetadataObservation.Relation>,
        val columnObservations: () -> List<CatalogMetadataObservation.Column>,
        val relationshipObservations: () -> List<CatalogMetadataObservation.RelationshipMember>,
    ) {
        val currentTaskState: CatalogIngestTaskState
            get() = currentTask().state
        val completeCallsCount: Int
            get() = completeCalls()
        val containerCallsCount: Int
            get() = containerCalls()
        val selfObservationsValue: List<CatalogMetadataObservation>
            get() = selfObservations()
        val childrenValue: List<CatalogIngestTaskTarget>
            get() = children()
        val relationObservationsValue: List<CatalogMetadataObservation.Relation>
            get() = relationObservations()
        val columnObservationsValue: List<CatalogMetadataObservation.Column>
            get() = columnObservations()
        val relationshipObservationsValue: List<CatalogMetadataObservation.RelationshipMember>
            get() = relationshipObservations()

        fun close() {
            unmockkObject(ExecutionRunId.Companion)
        }
    }

    private class DirectCatalogTransactionPort : CatalogTransactionPort {
        override suspend fun <T> inTransaction(block: () -> T): T = inOwnerTransaction(block)
        override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = inOwnerTransaction(block)

        private fun <T> inOwnerTransaction(block: () -> T): T {
            val outer = ownerTransactionActive.get()
            ownerTransactionActive.set(true)
            return try {
                block()
            } finally {
                ownerTransactionActive.set(outer)
            }
        }
    }

    private companion object {
        const val RUNNER_INSTANCE_ID = "runner-1"
    }
}

private val ownerTransactionActive: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

private fun requireOwnerTransaction() {
    check(ownerTransactionActive.get()) { "Catalog repository was read outside an owner transaction" }
}
