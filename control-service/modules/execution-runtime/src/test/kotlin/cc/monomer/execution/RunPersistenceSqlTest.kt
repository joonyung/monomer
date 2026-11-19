package cc.monomer.execution

import cc.monomer.execution.adapter.outbound.postgres.runInsertSql
import cc.monomer.execution.adapter.outbound.postgres.runUpdateSql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RunPersistenceSqlTest {
    @Test
    fun `run persistence SQL uses actual line breaks`() {
        assertFalse(runInsertSql.contains("\\n"))
        assertFalse(runUpdateSql.contains("\\n"))
    }

    @Test
    fun `run insert binds every declared column`() {
        val columnCount =
            runInsertSql.substringAfter('(')
                .substringBefore(") VALUES")
                .split(',')
                .size

        assertEquals(columnCount, runInsertSql.count { character -> character == '?' })
    }

    @Test
    fun `run update binds the execution ledger shape`() {
        assertEquals(28, runUpdateSql.count { character -> character == '?' })
        assertFalse(runUpdateSql.contains("catalog_selection"))
        assertFalse(runUpdateSql.contains("prepared_catalog"))
        assertFalse(runUpdateSql.contains("proof"))
        assertFalse(runUpdateSql.contains("reference"))
    }
}
