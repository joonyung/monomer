package cc.monomer.execution

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionRuntimeSchemaGateTest {
    @Test
    fun `execution runtime DDL owns one run ledger and no operation ledger`() {
        val ddl =
            checkNotNull(javaClass.getResource("/db/execution-runtime.sql"))
                .readText()
                .lowercase()
        val normalized = ddl.replace(Regex("\\s+"), " ")

        assertContains(ddl, "create schema if not exists execution_run")
        assertContains(ddl, "execution_run.runs")
        assertContains(ddl, "execution_run.dispatch_intents")
        assertContains(ddl, "result_sink_object_key")
        assertContains(ddl, "result_sink_maximum_artifact_rows")
        assertContains(ddl, "result_sink_maximum_record_batch_rows")
        assertContains(ddl, "result_sink_maximum_cell_bytes")
        assertContains(ddl, "result_sink_maximum_multipart_part_bytes")
        assertContains(ddl, "result_object_key")
        assertContains(ddl, "result_object_etag")
        assertContains(ddl, "result_byte_size")
        assertContains(ddl, "result_row_count")
        assertContains(ddl, "result_column_count")
        assertContains(ddl, "result_media_type")
        assertContains(ddl, "request_kind text not null check (request_kind = 'query')")
        assertContains(ddl, "result_sink_kind in ('artifact_upload', 'inline_stream')")
        assertFalse(ddl.contains("admission_evidence"))
        assertFalse(ddl.contains("authority_"))
        assertFalse(ddl.contains("accepted_proof"))
        assertFalse(ddl.contains("prepared_proof"))
        assertFalse(ddl.contains("raw_sql_canonical_plan"))
        assertFalse(ddl.contains("reference_set"))
        assertContains(normalized, "state in ('running', 'succeeded', 'failed', 'cancelled')")
        assertFalse(ddl.contains("execution_attempt"))
        assertFalse(ddl.contains("result_preview"))
        assertFalse(ddl.contains("artifact_staging"))
        assertFalse(ddl.contains("lease_terminal_facts"))
        assertFalse(ddl.contains("operational_frame_receipts"))
        assertFalse(ddl.contains("finalization_failed"))
        assertFalse(ddl.contains("unknown_orphaned"))
        assertFalse(ddl.contains("manifest_digest"))
        assertFalse(ddl.contains("staging_handle ~ '^rps:"))
        assertFalse(ddl.contains("catalog_scan"))
        assertFalse(ddl.contains("catalog_metadata"))
        assertFalse(ddl.contains("metadata_stream"))
        assertFalse(ddl.contains("create schema if not exists operation"))
        assertFalse(ddl.contains("retry"))
        assertFalse(ddl.contains("settlement_intent"))
    }

    @Test
    fun `execution api application and domain remain transport independent`() {
        val roots =
            listOf("api", "application", "domain").map { layer ->
                java.nio.file.Path.of("src/main/kotlin/cc/monomer/execution/$layer")
            }
        val forbidden = listOf("cc.monomer.execution.protocol", "com.google.protobuf", "io.grpc")
        val violations =
            roots.flatMap { root ->
                java.nio.file.Files.walk(root).use { paths ->
                    paths.filter { it.toString().endsWith(".kt") }
                        .toList()
                        .flatMap { path ->
                            val source = java.nio.file.Files.readString(path)
                            forbidden.filter(source::contains)
                                .map { token -> "$path imports $token" }
                        }
                }
            }
        assertTrue(violations.isEmpty(), violations.joinToString("\\n"))
    }

    @Test
    fun `nullable domain facts require complete database tuples`() {
        val ddl =
            checkNotNull(javaClass.getResource("/db/execution-runtime.sql"))
                .readText()
                .lowercase()
                .replace(Regex("\\s+"), " ")
        assertContains(ddl, "owner_settled_at timestamp with time zone")
        assertContains(ddl, "physical_outcome_kind text")
        assertFalse(ddl.contains("preparation_decision_id"))
        assertFalse(ddl.contains("ready_to_enter"))
        assertFalse(ddl.contains("external_entry_grant_id"))
    }
}
