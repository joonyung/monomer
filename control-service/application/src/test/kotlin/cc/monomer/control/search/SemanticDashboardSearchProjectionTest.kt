@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.search.api.SearchIndex
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test
import kotlin.uuid.Uuid

class SemanticDashboardSearchProjectionTest {
    @Test
    fun `semantic deletes remove only the scoped definition search document`() {
        val index = mockk<SearchIndex>(relaxed = true)
        val projection = SemanticDashboardSearchProjection(index)
        val workspace = WorkspaceId(Uuid.generateV7())
        projection.deleteModel(workspace, "orders")
        projection.deleteMetric(workspace, "revenue")
        verify(exactly = 1) { index.deleteSubtree(UUID.fromString(workspace.toString()), "workspace/semantic-models/orders") }
        verify(exactly = 1) { index.deleteSubtree(UUID.fromString(workspace.toString()), "workspace/metrics/revenue") }
        verify(exactly = 0) { index.upsert(any()) }
    }
}
