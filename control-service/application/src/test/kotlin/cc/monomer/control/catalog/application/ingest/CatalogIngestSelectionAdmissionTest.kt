@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.application.ingest

import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogConflictReason
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionState
import cc.monomer.catalog.api.ConnectorCatalogSelection
import cc.monomer.catalog.api.ConnectorCatalogSelectionState
import cc.monomer.catalog.api.SaveCatalogSelectionCommand
import cc.monomer.catalog.api.SaveConnectorCatalogSelectionCommand
import cc.monomer.catalog.api.StartCatalogIngestCommand
import cc.monomer.catalog.application.CatalogIngestRepository
import cc.monomer.catalog.application.CatalogSelectionRepository
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.catalog.application.ingest.CatalogActiveIngestResult
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationService
import cc.monomer.catalog.application.ingest.CatalogIngestCascadeRunner
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobSummary
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class CatalogIngestSelectionAdmissionTest {
    private val now = Instant.parse("2026-08-21T01:00:00Z")

    @Test
    fun `save rejects a catalog entry for a two-depth connector`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        val serviceFixture = service(repository, workspaceId, connectorId, "POSTGRESQL")
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId

        val result = serviceFixture.service.saveSelection(
            SaveCatalogSelectionCommand(
                authorization = authorization,
                connectorId = connectorId,
                entry = CatalogIngestEntry.Catalog(connectorId, "warehouse"),
                selection = selection("public"),
            ),
        )

        assertIs<CatalogCommandResult.Rejected>(result)
        verify(exactly = 0) { repository.saveSelection(any()) }
        serviceFixture.scope.cancel()
    }

    @Test
    fun `catalog entry reads only its own selection`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.findActiveIngestJob(workspaceId, any()) } returns null
        val entry = CatalogIngestEntry.Catalog(connectorId, "archive")
        every { repository.isConnectorCatalogSelected(workspaceId, connectorId, "archive") } returns true
        every { repository.currentSelection(workspaceId, connectorId, entry) } returns null
        val serviceFixture = service(repository, workspaceId, connectorId, "SNOWFLAKE")
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId

        val result = serviceFixture.service.startIngest(
            StartCatalogIngestCommand(
                authorization = authorization,
                connectorId = connectorId,
                entry = entry,
            ),
        )

        assertIs<CatalogCommandResult.Rejected>(result)
        verify(exactly = 0) { repository.insertIngestJob(any()) }
        verify(exactly = 1) { repository.currentSelection(workspaceId, connectorId, entry) }
        serviceFixture.scope.cancel()
    }

    @Test
    fun `selected three-depth catalog entry admits a job with its catalog selection`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val entry = CatalogIngestEntry.Catalog(connectorId, "analytics")
        val selectedSelection = selection("public")
        val requestedBy = PrincipalId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.isConnectorCatalogSelected(workspaceId, connectorId, "analytics") } returns true
        every { repository.findActiveIngestJob(workspaceId, entry.key) } returns null
        every { repository.insertIngestJob(any()) } answers {
            val admittedJob = firstArg<CatalogIngestJob>()
            every { repository.claimIngestJob(admittedJob.id, any(), any()) } returns null
            Unit
        }
        every { repository.currentSelection(workspaceId, connectorId, entry) } returns
            CatalogSelectionState(
                workspaceId = workspaceId,
                connectorId = connectorId,
                entry = entry,
                selection = selectedSelection,
                committedBy = requestedBy,
                committedAt = now,
            )
        val serviceFixture = service(repository, workspaceId, connectorId, "SNOWFLAKE")
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId
        every { authorization.actor } returns cc.monomer.identity.api.CanonicalPrincipal(
            id = requestedBy,
            kind = cc.monomer.identity.api.PrincipalKind.USER,
        )

        try {
            val result = serviceFixture.service.startIngest(
                StartCatalogIngestCommand(
                    authorization = authorization,
                    connectorId = connectorId,
                    entry = entry,
                ),
            )

            val started = assertIs<CatalogCommandResult.Accepted<*>>(result).value as cc.monomer.catalog.api.CatalogIngestStarted
            assertEquals(entry, started.entry)
            verify(exactly = 1) {
                repository.insertIngestJob(match {
                    it.workspaceId == workspaceId &&
                        it.connectorId == connectorId &&
                        it.entry == entry &&
                        it.selection == selectedSelection &&
                        it.requestedBy == requestedBy
                })
            }
        } finally {
            serviceFixture.scope.cancel()
        }
    }

    @Test
    fun `running and cancellation requested jobs are reused for the same entry`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val entry = CatalogIngestEntry.Connector(connectorId)
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId

        val activeJobs = listOf(
            CatalogIngestJob.request(
                id = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
                workspaceId = workspaceId,
                connectorId = connectorId,
                selection = selection("public"),
                entry = entry,
                requestedBy = PrincipalId.generate(),
                requestedAt = now,
                credentialSurface = null,
                agentClientId = null,
            ),
            CatalogIngestJob.request(
                id = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
                workspaceId = workspaceId,
                connectorId = connectorId,
                selection = selection("public"),
                entry = entry,
                requestedBy = PrincipalId.generate(),
                requestedAt = now,
                credentialSurface = null,
                agentClientId = null,
            ).claim("runner-a", now).requestCancellation(),
        )

        activeJobs.forEach { activeJob ->
            val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
            every { repository.findActiveIngestJob(workspaceId, entry.key) } returns activeJob
            val serviceFixture = service(repository, workspaceId, connectorId, "POSTGRESQL")

            val result = serviceFixture.service.startIngest(
                StartCatalogIngestCommand(authorization, connectorId, entry),
            )

            val started = assertIs<CatalogCommandResult.Accepted<*>>(result).value as cc.monomer.catalog.api.CatalogIngestStarted
            assertEquals(activeJob.id, started.jobId)
            verify(exactly = 0) { repository.currentSelection(workspaceId, connectorId, entry) }
            verify(exactly = 0) { repository.insertIngestJob(any()) }
            serviceFixture.scope.cancel()
        }
    }

    @Test
    fun `cancellation requested catalog ingest reuses its active job id`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val entry = CatalogIngestEntry.Connector(connectorId)
        val activeJob = CatalogIngestJob.request(
            id = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = selection("public"),
            entry = entry,
            requestedBy = PrincipalId.generate(),
            requestedAt = now,
            credentialSurface = null,
            agentClientId = null,
        ).claim("runner-a", now).requestCancellation()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.findActiveIngestJob(workspaceId, entry.key) } returns activeJob
        val serviceFixture = service(repository, workspaceId, connectorId, "POSTGRESQL")
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId

        try {
            val result = serviceFixture.service.startIngest(
                StartCatalogIngestCommand(authorization, connectorId, entry),
            )

            val started = assertIs<CatalogCommandResult.Accepted<*>>(result).value as cc.monomer.catalog.api.CatalogIngestStarted
            assertEquals(activeJob.id, started.jobId)
            assertEquals(activeJob.entry, started.entry)
            verify(exactly = 0) { repository.currentSelection(workspaceId, connectorId, entry) }
            verify(exactly = 0) { repository.insertIngestJob(any()) }
        } finally {
            serviceFixture.scope.cancel()
        }
    }

    @Test
    fun `connector authorization rejection performs no selection save or job start`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        val serviceFixture = service(
            repository = repository,
            workspaceId = workspaceId,
            connectorId = connectorId,
            connectorType = "POSTGRESQL",
            executionAccessResult = ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace,
        )
        val mutationAuthorization = mockk<AuthorizedMutationContext>(relaxed = true)
        every { mutationAuthorization.agentClientId } returns null
        val executionAuthorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { executionAuthorization.agentClientId } returns null
        every { mutationAuthorization.workspaceId } returns workspaceId
        every { executionAuthorization.workspaceId } returns workspaceId
        val entry = CatalogIngestEntry.Connector(connectorId)

        val saveResult = serviceFixture.service.saveSelection(
            SaveCatalogSelectionCommand(mutationAuthorization, connectorId, entry, selection("public")),
        )
        val startResult = serviceFixture.service.startIngest(
            StartCatalogIngestCommand(executionAuthorization, connectorId, entry),
        )

        assertEquals(CatalogCommandResult.NotFoundOrOutsideWorkspace, saveResult)
        assertEquals(CatalogCommandResult.NotFoundOrOutsideWorkspace, startResult)
        verify(exactly = 0) { repository.saveSelection(any()) }
        verify(exactly = 0) { repository.insertIngestJob(any()) }
        verify(exactly = 0) { repository.lockConnector(workspaceId, connectorId) }
        serviceFixture.scope.cancel()
    }

    @Test
    fun `unknown catalog entry cannot create selection membership by writing itself`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.isConnectorCatalogSelected(workspaceId, connectorId, "invented") } returns false
        val serviceFixture = service(repository, workspaceId, connectorId, "SNOWFLAKE")
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId

        val result = serviceFixture.service.saveSelection(
            SaveCatalogSelectionCommand(
                authorization = authorization,
                connectorId = connectorId,
                entry = CatalogIngestEntry.Catalog(connectorId, "invented"),
                selection = selection("public"),
            ),
        )

        assertEquals(
            CatalogCommandResult.ConflictWithReason(CatalogConflictReason.INVALID_CANDIDATE),
            result,
        )
        verify(exactly = 0) { repository.saveSelection(any()) }
        serviceFixture.scope.cancel()
    }

    @Test
    fun `missing selection reads as an effective empty view after entry validation`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every {
            repository.currentSelection(workspaceId, connectorId, CatalogIngestEntry.Connector(connectorId))
        } returns null
        val serviceFixture = service(repository, workspaceId, connectorId, "POSTGRESQL")

        val view = serviceFixture.service.currentSelection(
            workspaceId,
            connectorId,
            CatalogIngestEntry.Connector(connectorId),
        )

        assertNotNull(view)
        assertEquals(CatalogSelection(emptySet()), view.selection)
        assertEquals(null, view.committedAt)
        serviceFixture.scope.cancel()
    }

    @Test
    fun `active ingest read reports a missing entry separately from an entry without an active job`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val selectedEntry = CatalogIngestEntry.Catalog(connectorId, "analytics")
        val activeJob = CatalogIngestJob.request(
            id = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = selection("public"),
            entry = selectedEntry,
            requestedBy = PrincipalId.generate(),
            requestedAt = now,
            credentialSurface = null,
            agentClientId = null,
        )
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.isConnectorCatalogSelected(workspaceId, connectorId, "analytics") } returns true
        every { repository.isConnectorCatalogSelected(workspaceId, connectorId, "archive") } returns false
        every { repository.findActiveIngestJob(workspaceId, selectedEntry.key) } returns null
        val serviceFixture = service(repository, workspaceId, connectorId, "SNOWFLAKE")
        val missingConnectorFixture = service(
            repository = repository,
            workspaceId = workspaceId,
            connectorId = connectorId,
            connectorType = "SNOWFLAKE",
            executionAccessResult = ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace,
        )

        try {
            assertEquals(
                CatalogActiveIngestResult.EntryNotFound,
                missingConnectorFixture.service.findActive(workspaceId, connectorId, selectedEntry),
            )
            assertEquals(
                CatalogActiveIngestResult.EntryNotFound,
                serviceFixture.service.findActive(workspaceId, connectorId, CatalogIngestEntry.Connector(connectorId)),
            )
            assertEquals(
                CatalogActiveIngestResult.EntryNotFound,
                serviceFixture.service.findActive(
                    workspaceId,
                    connectorId,
                    CatalogIngestEntry.Catalog(connectorId, "archive"),
                ),
            )
            verify(exactly = 0) { repository.findActiveIngestJob(workspaceId, any()) }

            assertEquals(
                CatalogActiveIngestResult.NoActiveIngest,
                serviceFixture.service.findActive(workspaceId, connectorId, selectedEntry),
            )

            every { repository.findActiveIngestJob(workspaceId, selectedEntry.key) } returns activeJob
            every { repository.findIngestJobSummary(activeJob.id) } returns CatalogIngestJobSummary(
                job = activeJob,
                refreshedRelationCount = 0,
                runningTaskCount = 0,
                errorCount = 0,
            )
            every { repository.findRunningIngestTasks(activeJob.id) } returns emptyList()
            val active = assertIs<CatalogActiveIngestResult.Active>(
                serviceFixture.service.findActive(workspaceId, connectorId, selectedEntry),
            )
            assertEquals(activeJob.id, active.view.job.id)
        } finally {
            serviceFixture.scope.cancel()
            missingConnectorFixture.scope.cancel()
        }
    }

    @Test
    fun `connector catalog selection reads as an effective empty view before first save`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.currentConnectorCatalogSelection(workspaceId, connectorId) } returns null
        val serviceFixture = service(repository, workspaceId, connectorId, "SNOWFLAKE")

        val view = serviceFixture.service.currentConnectorCatalogSelection(workspaceId, connectorId)

        assertNotNull(view)
        assertEquals(ConnectorCatalogSelection(emptySet()), view.selection)
        assertEquals(null, view.committedAt)
        serviceFixture.scope.cancel()
    }

    @Test
    fun `connector catalog selection allows discovered additions and retires removed subtrees atomically`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        val previous = ConnectorCatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = ConnectorCatalogSelection(setOf("archive")),
            committedBy = cc.monomer.identity.api.PrincipalId.generate(),
            committedAt = now.minusSeconds(60),
        )
        every { repository.currentConnectorCatalogSelection(workspaceId, connectorId) } returns previous
        every { repository.hasCatalogDiscoveryOption(workspaceId, connectorId, "warehouse") } returns true
        every {
            repository.findActiveIngestJob(
                workspaceId,
                CatalogIngestEntry.Catalog(connectorId, "archive").key,
            )
        } returns null
        val serviceFixture = service(repository, workspaceId, connectorId, "SNOWFLAKE")
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId

        val result = serviceFixture.service.saveConnectorCatalogSelection(
            SaveConnectorCatalogSelectionCommand(
                authorization = authorization,
                connectorId = connectorId,
                selection = ConnectorCatalogSelection(setOf("warehouse")),
            ),
        )

        assertTrue(result is CatalogCommandResult.Accepted<*>)
        verify(exactly = 1) { repository.lockConnector(workspaceId, connectorId) }
        verify(exactly = 1) {
            repository.saveConnectorCatalogSelection(match {
                it.selection.selectedCatalogNames == setOf("warehouse")
            })
        }
        verify(exactly = 1) { repository.deleteCatalogSelection(workspaceId, connectorId, "archive") }
        verify(exactly = 1) {
            repository.removeCurrentCatalogSubtree(workspaceId, connectorId, "archive")
        }
        serviceFixture.scope.cancel()
    }

    @Test
    fun `connector catalog deselection conflicts with active ingestion and does not mutate`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.currentConnectorCatalogSelection(workspaceId, connectorId) } returns ConnectorCatalogSelectionState(
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = ConnectorCatalogSelection(setOf("archive")),
            committedBy = cc.monomer.identity.api.PrincipalId.generate(),
            committedAt = now.minusSeconds(60),
        )
        every {
            repository.findActiveIngestJob(
                workspaceId,
                CatalogIngestEntry.Catalog(connectorId, "archive").key,
            )
        } returns mockk<CatalogIngestJob>()
        val serviceFixture = service(repository, workspaceId, connectorId, "SNOWFLAKE")
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId

        val result = serviceFixture.service.saveConnectorCatalogSelection(
            SaveConnectorCatalogSelectionCommand(
                authorization = authorization,
                connectorId = connectorId,
                selection = ConnectorCatalogSelection(emptySet()),
            ),
        )

        assertEquals(
            CatalogCommandResult.ConflictWithReason(CatalogConflictReason.ACTIVE_INGESTION),
            result,
        )
        verify(exactly = 0) { repository.saveConnectorCatalogSelection(any()) }
        verify(exactly = 0) { repository.deleteCatalogSelection(workspaceId, connectorId, "archive") }
        verify(exactly = 0) {
            repository.removeCurrentCatalogSubtree(workspaceId, connectorId, "archive")
        }
        serviceFixture.scope.cancel()
    }

    @Test
    fun `selection saves and ingest admission reject a connector deleted before the catalog connector lock`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.isConnectorCatalogSelected(workspaceId, connectorId, "warehouse") } returns true
        val serviceFixture = service(repository, workspaceId, connectorId, "SNOWFLAKE",
            lockedExecutionAccessResult = ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace)
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId
        val executionAuthorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { executionAuthorization.agentClientId } returns null
        every { executionAuthorization.workspaceId } returns workspaceId
        val entry = CatalogIngestEntry.Catalog(connectorId, "warehouse")

        assertEquals(CatalogCommandResult.NotFoundOrOutsideWorkspace, serviceFixture.service.saveSelection(
            SaveCatalogSelectionCommand(authorization, connectorId, entry, selection("analytics")),
        ))
        assertEquals(CatalogCommandResult.NotFoundOrOutsideWorkspace, serviceFixture.service.saveConnectorCatalogSelection(
            SaveConnectorCatalogSelectionCommand(authorization, connectorId, ConnectorCatalogSelection(setOf("warehouse"))),
        ))
        assertEquals(CatalogCommandResult.NotFoundOrOutsideWorkspace, serviceFixture.service.startIngest(
            StartCatalogIngestCommand(executionAuthorization, connectorId, entry),
        ))

        verify(exactly = 3) { repository.lockConnector(workspaceId, connectorId) }
        verify(exactly = 0) { repository.saveSelection(any()) }
        verify(exactly = 0) { repository.saveConnectorCatalogSelection(any()) }
        verify(exactly = 0) { repository.insertIngestJob(any()) }
        serviceFixture.scope.cancel()
    }

    private fun service(
        repository: CatalogSelectionAndIngestRepository,
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        connectorType: String,
        executionAccessResult: ConnectorExecutionAccessResult? = null,
        lockedExecutionAccessResult: ConnectorExecutionAccessResult? = null,
    ): ServiceFixture {
        val binding = mockk<ConnectorExecutionBinding>(relaxed = true)
        every { binding.connectorType } returns connectorType
        val access = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { access.readCurrentForExecution(workspaceId, connectorId) } returns
            (executionAccessResult ?: ConnectorExecutionAccessResult.Granted(binding))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return ServiceFixture(
            service = CatalogIngestApplicationService(
                selectionRepository = repository,
                repository = repository,
                transactionPort = DirectTransactionPort(),
                connectorExecutionAccessUseCase = access,
                connectorAccessInOwnerTransaction = object : cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort {
                    override fun readCurrentForExecutionInOwnerTransaction(
                        workspaceId: WorkspaceId,
                        connectorId: ConnectorId,
                    ) = lockedExecutionAccessResult ?: executionAccessResult ?: ConnectorExecutionAccessResult.Granted(binding)
                },
                connectorRuntimeBindingProjection = mockk<ConnectorRuntimeBindingProjectionPort>(),
                cascadeRunner = mockk<CatalogIngestCascadeRunner>(),
                ownerExecutionRunCancellation = mockk<OwnerExecutionRunCancellationPort>(),
                clock = Clock.fixed(now, ZoneOffset.UTC),
                runnerScope = scope,
                runnerInstanceId = "runner-a",
            ),
            scope = scope,
        )
    }

    private fun selection(schemaName: String) = CatalogSelection(
        selectedSchemas = setOf(
            CatalogSelectedSchema(
                schemaName = schemaName,
                relations = CatalogRelationSelection.AllRelations,
            ),
        ),
    )

    private data class ServiceFixture(
        val service: CatalogIngestApplicationService,
        val scope: CoroutineScope,
    )

    private class DirectTransactionPort : CatalogTransactionPort {
        override suspend fun <T> inTransaction(block: () -> T): T = block()
        override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = block()
    }

    private interface CatalogSelectionAndIngestRepository : CatalogSelectionRepository, CatalogIngestRepository
}
