@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.application

import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ConnectorType
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ListExploreRunsCommand
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.explore.domain.ExploreTerminalReceipt
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticQuerySpec
import io.mockk.mockk
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ExploreServicePaginationTest {
    private val workspaceId = WorkspaceId.generate()
    private val ownerPrincipalId = PrincipalId.generate()

    @Test
    fun `page uses limit plus one and returns the last visible tuple as next boundary`() = runBlocking {
        val runs = listOf(
            sqlRun("019e0000-0000-7000-8000-000000000003"),
            semanticRun("019e0000-0000-7000-8000-000000000002"),
        )
        val repository = RecordingRepository(runs)
        val service = ExploreService(mockk(), mockk(), repository, Dispatchers.Unconfined)

        val page = service.list(ListExploreRunsCommand(workspaceId, ownerPrincipalId, null, 1))

        assertEquals(2, repository.requestedLimit)
        assertEquals(listOf(runs.first().asView()), page.runs)
        assertEquals(ExploreRunPageBoundary(runs.first().createdAt, runs.first().id), page.next)
    }

    @Test
    fun `empty and exhausted pages omit next boundary`() = runBlocking {
        val boundary = ExploreRunPageBoundary(
            Instant.parse("2026-08-20T00:00:00Z"),
            ExploreRunId(Uuid.parse("019e0000-0000-7000-8000-000000000001")),
        )
        val repository = RecordingRepository(emptyList())
        val service = ExploreService(mockk(), mockk(), repository, Dispatchers.Unconfined)

        val page = service.list(ListExploreRunsCommand(workspaceId, ownerPrincipalId, boundary, 20))

        assertEquals(emptyList(), page.runs)
        assertEquals(null, page.next)
        assertEquals(boundary, repository.after)
        assertEquals(21, repository.requestedLimit)
    }

    @Test
    fun `history page reads the repository on the JDBC dispatcher`() = runBlocking {
        val repository = RecordingRepository(emptyList())
        Executors.newSingleThreadExecutor { task -> Thread(task, "explore-jdbc-test") }.asCoroutineDispatcher().use { jdbc ->
            ExploreService(mockk(), mockk(), repository, jdbc).list(ListExploreRunsCommand(workspaceId, ownerPrincipalId, null, 20))
        }
        assertEquals(true, repository.readThread?.startsWith("explore-jdbc-test"), repository.readThread)
    }

    private fun sqlRun(id: String): ExploreRun = ExploreRun(
        id = ExploreRunId(Uuid.parse(id)),
        kind = ExploreRun.Kind.Sql,
        idempotencyKey = id,
        ownerPrincipalId = ownerPrincipalId,
        credentialSurface = null,
        agentClientId = null,
        connectorBinding = ExploreConnectorBinding(workspaceId, ConnectorId.generate(), ConnectorType.POSTGRESQL),
        requestDigest = ExploreDigest("sha256:${"a".repeat(64)}"),
        state = ExploreRunState.RUNNING,
        visibleResult = null,
        failureCode = null,
        failureMessage = null,
        revision = 0,
        createdAt = Instant.parse("2026-08-20T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-20T00:00:00Z"),
    )

    private fun semanticRun(id: String): ExploreRun {
        return ExploreRun(
            id = ExploreRunId(Uuid.parse(id)),
            kind = ExploreRun.Kind.Semantic(query = SemanticQuerySpec(
                listOf(SemanticElementName("revenue")),
                emptyList(),
                null,
                emptyList(),
                emptyList(),
                100,
            ), outputColumnDisplays = null, reportingTimeZone = java.time.ZoneId.of("UTC")),
            idempotencyKey = id,
            ownerPrincipalId = ownerPrincipalId,
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = ExploreConnectorBinding(workspaceId, ConnectorId.generate(), ConnectorType.POSTGRESQL),
            requestDigest = ExploreDigest("sha256:${"b".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            visibleResult = null,
            failureCode = null,
            failureMessage = null,
            revision = 0,
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T00:00:00Z"),
        )
    }

    private class RecordingRepository(private val answer: List<ExploreRun>) : ExploreRunRepository {
        var after: ExploreRunPageBoundary? = null
        var requestedLimit: Int = 0
        var readThread: String? = null

        override fun findRuns(
            workspaceId: WorkspaceId,
            ownerPrincipalId: PrincipalId,
            after: ExploreRunPageBoundary?,
            limit: Int,
        ): List<ExploreRun> {
            this.after = after
            requestedLimit = limit
            readThread = Thread.currentThread().name
            return answer
        }

        override fun lockAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String) = error("unused")
        override fun findByAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String) = error("unused")
        override fun findRun(runId: ExploreRunId) = error("unused")
        override fun insertAdmittedRun(run: ExploreRun) = error("unused")
        override fun lockRunForOwnerMutation(runId: ExploreRunId) = error("unused")
        override fun lockRunForResultRead(runId: ExploreRunId) = error("unused")
        override fun findTerminalReceipt(workspaceId: WorkspaceId, settlementIdempotencyKey: String) = error("unused")
        override fun commitTerminalReceipt(run: ExploreRun, receipt: ExploreTerminalReceipt, expectedRevision: Long) =
            error("unused")
        override fun lockVisibleResultForRead(runId: ExploreRunId) = error("unused")
    }
}
