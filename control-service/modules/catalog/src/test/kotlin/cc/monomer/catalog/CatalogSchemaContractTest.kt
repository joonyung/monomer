package cc.monomer.catalog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogSchemaContractTest {
    @Test
    fun `catalog ddl owns the current selection and minimal ingestion tree`() {
        val ddl = catalogDdl()

        assertTrue("create table if not exists catalog.selections" in ddl)
        assertTrue("entry_catalog_name text" in ddl)
        assertTrue("unique nulls not distinct (workspace_id, connector_id, entry_catalog_name)" in ddl)
        assertTrue("create table if not exists catalog.connector_catalog_selections" in ddl)
        assertTrue("selected_catalog_names jsonb" in ddl)
        assertTrue("create table if not exists catalog.list_refresh_jobs" in ddl)
        assertTrue("child_kind text" in ddl)
        assertTrue("list_refresh_jobs_one_active_per_target" in ddl)
        assertTrue("create table if not exists catalog.selection_option_lists" in ddl)
        assertTrue("create table if not exists catalog.selection_options" in ddl)
        assertTrue("selection_options_page" in ddl)
        assertTrue("create table if not exists catalog.ingest_jobs" in ddl)
        assertTrue("runner_instance_id text" in ddl)
        assertTrue("heartbeat_at timestamptz" in ddl)
        assertTrue("state in ('running', 'cancel_requested', 'completed', 'completed_with_errors', 'cancelled')" in ddl)
        assertTrue("create table if not exists catalog.ingest_tasks" in ddl)
        assertTrue("parent_task_id uuid" in ddl)
        assertTrue("active_run_id uuid" in ddl)
        assertTrue("state in ('running', 'succeeded', 'failed', 'cancelled')" in ddl)
        assertTrue("create table if not exists catalog.current_objects" in ddl)
        assertTrue("relation_definition text" in ddl)
        assertTrue("column_ordinal integer" in ddl)
        assertTrue("column_nullable boolean" in ddl)
        assertTrue("column_default_expression text" in ddl)
        assertTrue("relationship_member_ordinal integer" in ddl)
        assertTrue("relationship_constraint_name text" in ddl)
        assertTrue("relationship_referenced_column text" in ddl)
        assertTrue("current_relations_search_page" in ddl)
        assertTrue("current_objects_catalog_address" in ddl)
        assertTrue("current_objects_schema_address" in ddl)
        assertTrue("current_objects_relation_address" in ddl)
        assertTrue("nulls not distinct" in ddl)
        assertTrue("ingest_jobs_one_active_per_entry" in ddl)
        assertFalse("authority_revision" in ddl)
    }

    @Test
    fun `catalog ddl contains no removed workflow or job failure concepts`() {
        val ddl = catalogDdl()

        listOf(
            "current_state",
            "removed",
            "selection_revisions",
            "scan_snapshots",
            "scan_snapshot_objects",
            "projection_heads",
            "applied_snapshots",
            "ingest_task_runs",
            "ingest_issues",
            "phase",
            "execution_run_id",
            "selection_patterns",
        ).forEach { removed ->
            assertFalse(removed in ddl, "removed Catalog concept remains in DDL: $removed")
        }
        listOf("connector.", "operation.", "execution.", "query.").forEach {
            assertFalse(it in ddl, "cross-owner DDL remains: $it")
        }
    }

    private fun catalogDdl(): String {
        val root = File(checkNotNull(System.getProperty("control.service.root")))
        return root.resolve("modules/catalog/src/main/resources/db/catalog/schema.sql")
            .readText()
            .lowercase()
    }
}
