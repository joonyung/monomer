@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog

import cc.monomer.catalog.adapter.outbound.postgres.CatalogTransactionTimeouts
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogIngestRepository
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSelectionRepository
import cc.monomer.catalog.adapter.outbound.postgres.SpringCatalogTransactionPort
import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestStarted
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionState
import cc.monomer.catalog.api.StartCatalogIngestCommand
import cc.monomer.catalog.domain.CatalogIngestJobState
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationService
import cc.monomer.catalog.application.ingest.CatalogIngestCascadeRunner
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class CatalogActiveJobAdmissionApplicationIntegrationTest {
    @Test
    fun `real admission reuses active jobs under concurrent starts and creates jobs after every terminal state`() = runBlocking {
        PostgreSQLContainer(
            DockerImageName.parse(
                "postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777",
            ).asCompatibleSubstituteFor("postgres"),
        ).withDatabaseName("catalog_admission_ephemeral").use { postgres ->
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
            val transactionManager = DataSourceTransactionManager(dataSource)
            val transactionPort = SpringCatalogTransactionPort(
                dataSource,
                TransactionTemplate(transactionManager).apply { timeout = 10 },
                TransactionTemplate(transactionManager).apply {
                    isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                    isReadOnly = true
                    timeout = 10
                },
                Dispatchers.IO,
                CatalogTransactionTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)),
            )
            val selectionRepository = PostgresCatalogSelectionRepository(dataSource, ObjectMapper(), mockk(relaxed = true))
            val repository = PostgresCatalogIngestRepository(dataSource, ObjectMapper(), mockk(relaxed = true), selectionRepository)
            val now = Instant.parse("2026-09-08T00:00:00Z")
            val principalId = PrincipalId.generate()
            val workspaceId = WorkspaceId.generate()
            val connectorId = ConnectorId.generate()
            val entry = CatalogIngestEntry.Connector(connectorId)
            val authorization = mockk<AuthorizedExecutionContext>()
            every { authorization.workspaceId } returns workspaceId
            every { authorization.actor.id } returns principalId
            every { authorization.credentialSurface } returns cc.monomer.identity.api.CredentialSurface.AGENT
            every { authorization.agentClientId } returns cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
            val command = StartCatalogIngestCommand(authorization, connectorId, entry)
            val selection = CatalogSelection(setOf(CatalogSelectedSchema("public", CatalogRelationSelection.AllRelations)))
            transactionPort.inTransaction {
                selectionRepository.saveSelection(CatalogSelectionState(workspaceId, connectorId, entry, selection, principalId, now))
            }

            val binding = mockk<ConnectorExecutionBinding>()
            every { binding.connectorType } returns "POSTGRESQL"
            val access = mockk<ConnectorExecutionAccessUseCase>()
            val arrivingStarts = AtomicInteger()
            val bothStartsReady = CompletableDeferred<Unit>()
            coEvery { access.readCurrentForExecution(workspaceId, connectorId) } coAnswers {
                if (arrivingStarts.incrementAndGet() <= 2) {
                    if (arrivingStarts.get() == 2) bothStartsReady.complete(Unit)
                    withTimeout(5_000) { bothStartsReady.await() }
                }
                ConnectorExecutionAccessResult.Granted(binding)
            }
            // Admission only: the paused runner cannot claim work or execute warehouse queries.
            val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
            val cascadeRunner = mockk<CatalogIngestCascadeRunner>()
            val service = CatalogIngestApplicationService(
                selectionRepository, repository, transactionPort, access, object : cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort {
                override fun readCurrentForExecutionInOwnerTransaction(
                    workspaceId: cc.monomer.identity.api.WorkspaceId,
                    connectorId: cc.monomer.connector.api.ConnectorId,
                ) = cc.monomer.connector.api.ConnectorExecutionAccessResult.Granted(mockk(relaxed = true))
            }, mockk(), cascadeRunner, mockk(),
                Clock.fixed(now, ZoneOffset.UTC), runnerScope, "admission-test-runner",
            )
            try {
                val results = withTimeout(15_000) {
                    List(2) { async(Dispatchers.Default) { service.startIngest(command) } }.awaitAll()
                }
                val started = results.map { assertIs<CatalogCommandResult.Accepted<CatalogIngestStarted>>(it).value }
                assertEquals(started.first().jobId, started.last().jobId)
                val jdbc = JdbcTemplate(dataSource)
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM catalog.ingest_jobs", Int::class.java))
                var currentJob = assertNotNull(transactionPort.inReadOnlyTransaction { repository.findIngestJob(started.first().jobId) })
                assertEquals(CatalogIngestJobState.RUNNING, currentJob.state)
                assertEquals(null, currentJob.runnerInstanceId)
                currentJob = currentJob.claim("fixture-runner", now).requestCancellation()
                transactionPort.inTransaction { assertTrue(repository.updateIngestJob(currentJob)) }
                val cancellationReplay = assertIs<CatalogCommandResult.Accepted<CatalogIngestStarted>>(service.startIngest(command))
                assertEquals(currentJob.id, cancellationReplay.value.jobId)

                listOf(
                    CatalogIngestJobState.COMPLETED,
                    CatalogIngestJobState.COMPLETED_WITH_ERRORS,
                    CatalogIngestJobState.CANCELLED,
                ).forEach { terminalState ->
                    val terminalJob = when (terminalState) {
                        CatalogIngestJobState.COMPLETED -> currentJob.complete(now)
                        CatalogIngestJobState.COMPLETED_WITH_ERRORS -> currentJob.completeWithErrors(now)
                        CatalogIngestJobState.CANCELLED -> currentJob.cancel(now)
                        else -> error("Expected terminal state")
                    }
                    transactionPort.inTransaction { assertTrue(repository.updateIngestJob(terminalJob)) }
                    val next = assertIs<CatalogCommandResult.Accepted<CatalogIngestStarted>>(service.startIngest(command)).value
                    assertNotEquals(terminalJob.id, next.jobId)
                    assertEquals(terminalState, assertNotNull(transactionPort.inReadOnlyTransaction { repository.findIngestJob(terminalJob.id) }).state)
                    currentJob = assertNotNull(transactionPort.inReadOnlyTransaction { repository.findIngestJob(next.jobId) })
                    assertEquals(CatalogIngestJobState.RUNNING, currentJob.state)
                    assertEquals(selection, currentJob.selection)
                }
                assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM catalog.ingest_jobs", Int::class.java))
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT count(*) FROM catalog.ingest_jobs WHERE state IN ('RUNNING', 'CANCEL_REQUESTED')",
                        Int::class.java,
                    ),
                )
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM catalog.ingest_tasks", Int::class.java))
                verify { cascadeRunner wasNot Called }
            } finally {
                runnerScope.cancel()
            }
        }
    }
}
