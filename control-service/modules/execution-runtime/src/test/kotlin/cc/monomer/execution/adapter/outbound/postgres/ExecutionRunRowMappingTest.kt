package cc.monomer.execution.adapter.outbound.postgres

import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.application.StoredExecutionRunRead
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class ExecutionRunRowMappingTest {
    @Test
    fun `null enum in a corrupted stored row is unreadable with its run ID`() =
        assertCorruptedRow<NullPointerException>(mapOf("result_sink_kind" to null))

    @Test
    fun `fractional unsigned count in a corrupted stored row is unreadable with its run ID`() =
        assertCorruptedRow<ArithmeticException>(
            mapOf(
                "result_sink_kind" to "ARTIFACT_UPLOAD",
                "result_sink_object_key" to null,
                "result_sink_maximum_artifact_rows" to BigDecimal("0.5"),
            ),
        )

    @Test
    fun `invalid enum in a corrupted stored row is unreadable with its run ID`() =
        assertCorruptedRow<IllegalArgumentException>(mapOf("result_sink_kind" to "CORRUPTED"))

    @Test
    fun `SQL read failure still fails rather than classifying the row unreadable`() {
        val row = rowFixture(mapOf("run_id" to Uuid.generateV7().toJavaUuid()))
        assertFailsWith<SQLException> { row.toStoredRunRead() }
    }

    private inline fun <reified T : RuntimeException> assertCorruptedRow(corruptedColumns: Map<String, Any?>) {
        val runId = ExecutionRunId(Uuid.generateV7())
        val row = rowFixture(
            mapOf("run_id" to runId.value.toJavaUuid(), "result_sink_schema_version" to 2) + corruptedColumns,
        )
        assertFailsWith<T> { row.toAdmittedRun() }
        val unreadable = assertIs<StoredExecutionRunRead.Unreadable>(row.toStoredRunRead())
        assertEquals(runId, unreadable.runId)
    }

    private fun rowFixture(columns: Map<String, Any?>): ResultSet =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, arguments ->
            val column = arguments?.firstOrNull() as? String ?: error("unexpected ResultSet method ${method.name}")
            if (column !in columns) throw SQLException("fixture column not found: $column")
            when (method.name) {
                "getObject", "getString", "getBigDecimal" -> columns[column]
                "getInt" -> columns[column] ?: 0
                else -> error("unexpected ResultSet method ${method.name}")
            }
        } as ResultSet
}
