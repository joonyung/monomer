@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.domain

import cc.monomer.catalog.api.CatalogListRefreshJobId
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CatalogListRefreshTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `job captures runner and clears active run at every terminal transition`() {
        val job = job().claim("runner-a", now)
        val withRun = job.copy(activeRunId = cc.monomer.execution.api.ExecutionRunId.generate())
        val completed = withRun.complete(now.plusSeconds(1))

        assertEquals(CatalogListRefreshJobState.RUNNING, withRun.state)
        assertEquals(CatalogListRefreshJobState.COMPLETED, completed.state)
        assertNull(completed.activeRunId)
        assertEquals(now.plusSeconds(1), completed.terminalAt)
    }

    @Test
    fun `failed and cancelled refreshes are terminal without cache semantics`() {
        val failed = job().claim("runner-a", now).completeWithErrors(
            "WAREHOUSE_QUERY_FAILED",
            "Warehouse query failed",
            now.plusSeconds(1),
        )
        val cancelled = job().cancel(now.plusSeconds(1))

        assertEquals(CatalogListRefreshJobState.COMPLETED_WITH_ERRORS, failed.state)
        assertEquals("WAREHOUSE_QUERY_FAILED", failed.failureCode)
        assertEquals(CatalogListRefreshJobState.CANCELLED, cancelled.state)
        assertNull(cancelled.runnerInstanceId)
    }

    @Test
    fun `unclaimed cancellation is allowed and claim is one way`() {
        val cancelled = job().cancel(now)
        assertFailsWith<IllegalStateException> { cancelled.claim("runner-a", now.plusSeconds(1)) }
    }

    private fun job(): CatalogListRefreshJob = CatalogListRefreshJob.request(
        id = CatalogListRefreshJobId.generate(),
        workspaceId = WorkspaceId(Uuid.generateV7()),
        connectorId = ConnectorId(Uuid.generateV7()),
        target = CatalogListRefreshTarget.Schema(null, "public"),
        requestedBy = PrincipalId(Uuid.generateV7()),
        requestedAt = now,
        credentialSurface = null,
        agentClientId = null,
    )
}
