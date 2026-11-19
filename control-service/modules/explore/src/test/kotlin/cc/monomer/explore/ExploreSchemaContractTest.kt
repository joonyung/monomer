package cc.monomer.explore

import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.explore.api.ExploreRunUseCase
import cc.monomer.explore.api.ExploreResultProjection
import cc.monomer.explore.api.ExploreResultColumn
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.api.ListExploreRunsCommand
import cc.monomer.explore.api.ReadExploreResultCommand
import cc.monomer.explore.domain.ExploreRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreSchemaContractTest {
    @Test
    fun `schema has common root and variant details`() {
        val schema = checkNotNull(javaClass.classLoader.getResource("db/explore/schema.sql")).readText()
        assertTrue("CREATE TABLE IF NOT EXISTS explore.runs" in schema)
        assertTrue("CREATE TABLE IF NOT EXISTS explore.semantic_run_details" in schema)
        assertTrue("CREATE TABLE IF NOT EXISTS explore.terminal_receipts" in schema)
        assertFalse("access_graph_json" in schema)
        assertTrue("FOREIGN KEY (run_id, workspace_id, kind)" in schema)
        assertTrue("terminal_receipts_immutable" in schema)
        assertTrue("workspace_id, owner_principal_id, created_at DESC, run_id DESC" in schema)
        assertFalse("sql_run_details" in schema)
        assertFalse("sql_explore_runs" in schema)
        assertFalse("RawSql" in schema)
    }

    @Test
    fun `both failure code checks accept exactly the public run failure codes`() {
        val schema = checkNotNull(javaClass.classLoader.getResource("db/explore/schema.sql")).readText()
        val checkedCodeLists = Regex("""failure_code IN \(([^)]*)\)""").findAll(schema)
            .map { match -> Regex("""'([A-Z_]+)'""").findAll(match.groupValues[1]).map { it.groupValues[1] }.toSet() }
            .toList()
        assertEquals(2, checkedCodeLists.size)
        checkedCodeLists.forEach { codes ->
            assertEquals(ExploreRunFailureCode.entries.map { it.name }.toSet(), codes)
        }
    }

    @Test
    fun `public explore API exposes common run surface`() {
        assertTrue(ExploreRun.Kind::class.java.isSealed)
        assertTrue(ExploreRunView.Kind::class.java.isSealed)
        assertTrue(ExploreRunUseCase::class.java.methods.any { it.name == "start" })
        assertTrue(ExploreRunState.entries.contains(ExploreRunState.CANCELLED))
        assertTrue(ReadExploreResultCommand::class.java.declaredFields.any { it.name == "runId" })
        assertTrue(ListExploreRunsCommand::class.java.declaredFields.any { it.name == "after" })
        assertTrue(ExploreRunPageBoundary::class.java.declaredFields.any { it.name == "createdAt" })
        assertTrue(ExploreResultProjection::class.java.declaredFields.any { it.name == "totalRowCount" })
        val projectionFields = ExploreResultProjection::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(projectionFields.any { it in setOf("stopReason", "mediaType", "byteSize", "columnCount") })
        val columnFields = ExploreResultColumn::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(columnFields.any { it in setOf("ordinal", "nullable") })
    }
}
