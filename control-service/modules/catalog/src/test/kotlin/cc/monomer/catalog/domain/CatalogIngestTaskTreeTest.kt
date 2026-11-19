@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.domain

import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ExecutionRunId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CatalogIngestTaskTreeTest {
    @Test
    fun `target keys remain distinct when identifiers contain delimiters`() {
        val first = CatalogIngestTaskTarget.Schema("a:b", "c")
        val second = CatalogIngestTaskTarget.Schema("a", "b:c")
        val relationFirst = CatalogIngestTaskTarget.Relation(
            "a:b",
            "c",
            "d",
            cc.monomer.catalog.api.RelationKind.TABLE,
        )
        val relationSecond = CatalogIngestTaskTarget.Relation(
            "a",
            "b:c",
            "d",
            cc.monomer.catalog.api.RelationKind.TABLE,
        )

        assertNotEquals(first.key, second.key)
        assertNotEquals(relationFirst.key, relationSecond.key)
    }

    private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `claim creates a running entry task and loading includes active ancestors`() {
        val job = job().claim("runner-a", startedAt)
        val root = CatalogIngestTask.start(job, null, CatalogIngestTaskTarget.Connector, startedAt)
        val child = CatalogIngestTask.start(
            job,
            root.id,
            CatalogIngestTaskTarget.Schema(null, "analytics"),
            startedAt.plusSeconds(1),
        )

        assertEquals(CatalogIngestTaskState.RUNNING, root.state)
        assertEquals(
            setOf(root.id, child.id),
            CatalogIngestTaskTree.loadingTaskIds(listOf(root, child)),
        )
        assertEquals(null, child.target.catalog)
    }

    @Test
    fun `parent can succeed after a failed descendant and terminal job aggregates task failures`() {
        val job = job().claim("runner-a", startedAt)
        val root = CatalogIngestTask.start(job, null, CatalogIngestTaskTarget.Connector, startedAt)
        val child = CatalogIngestTask.start(
            job,
            root.id,
            CatalogIngestTaskTarget.Relation(null, "analytics", "broken", cc.monomer.catalog.api.RelationKind.TABLE),
            startedAt.plusSeconds(1),
        ).copy(activeRunId = ExecutionRunId.generate())
        val failedChild = child.fail(
            "QUERY_FAILED",
            "relation query failed",
            startedAt.plusSeconds(2),
            child.activeRunId,
        )
        val completedRoot = root.succeed(startedAt.plusSeconds(3))

        assertEquals(CatalogIngestTaskState.FAILED, failedChild.state)
        assertEquals(CatalogIngestTaskState.SUCCEEDED, completedRoot.state)
        assertEquals(CatalogIngestJobState.COMPLETED_WITH_ERRORS, job.completeWithErrors(startedAt.plusSeconds(4)).state)
        assertTrue(CatalogIngestTaskTree.loadingTaskIds(listOf(completedRoot, failedChild)).isEmpty())
    }

    @Test
    fun `job terminal states are the only aggregate outcomes`() {
        val completed = job().claim("runner-a", startedAt).complete(startedAt.plusSeconds(1))
        val completedWithErrors = job().claim("runner-a", startedAt).completeWithErrors(startedAt.plusSeconds(1))
        val cancelled = job().requestCancellation().cancel(startedAt.plusSeconds(1))

        assertEquals(CatalogIngestJobState.COMPLETED, completed.state)
        assertEquals(CatalogIngestJobState.COMPLETED_WITH_ERRORS, completedWithErrors.state)
        assertEquals(CatalogIngestJobState.CANCELLED, cancelled.state)
    }

    private fun job(): CatalogIngestJob {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val connectorId = ConnectorId(Uuid.generateV7())
        return CatalogIngestJob.request(
            id = CatalogIngestJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = CatalogSelection(
                setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations)),
            ),
            entry = CatalogIngestEntry.Connector(connectorId),
            requestedBy = PrincipalId(Uuid.generateV7()),
            requestedAt = startedAt,
            credentialSurface = null,
            agentClientId = null,
        )
    }
}
