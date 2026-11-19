package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlOrderByDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.semantic.api.SemanticResultColumnKey
import cc.monomer.semantic.api.SemanticResultColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricFlowResultProjectionTest {
    @Test
    fun `final aliases may collide while their source expressions and ordering stay distinct`() {
        val columns = SemanticResultColumns.fromKeys(
            listOf(SemanticResultColumnKey("dimension.orders.status"), SemanticResultColumnKey("metric.status")),
        )
        val projection = MetricFlowResultProjection(columns, listOf(columns[1]), emptySet())
        val root = SqlSelectStatementNode.create(
            description = "",
            selectColumns = listOf(
                SqlSelectColumn.fromColumnReference("s", "status").copyWithNewAlias("status"),
                SqlSelectColumn.fromColumnReference("s", "amount").copyWithNewAlias("status"),
            ),
            fromSource = SqlTableNode(SqlTable(schemaName = "analytics", tableName = "orders", dbName = null)),
            fromSourceAlias = "s",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = listOf(SqlSelectColumn.fromColumnReference("s", "status")),
            orderBys = listOf(SqlOrderByDescription(
                SqlColumnReferenceExpression.fromColumnReference("s", "status"), true,
            )),
            where = null,
            limit = 7,
            distinct = false,
        )
        val delimiters = mapOf(
            SqlEngine.POSTGRES to "\"", SqlEngine.SNOWFLAKE to "\"", SqlEngine.REDSHIFT to "\"",
            SqlEngine.TRINO to "\"", SqlEngine.BIGQUERY to "`", SqlEngine.DATABRICKS to "`",
        )
        delimiters.forEach { (dialect, delimiter) ->
            val sql = projection.rendererFor(dialect).renderSqlPlan(SqlPlan(root)).sql
            assertEquals(listOf("status", "status"), projection.sourceAliases)
            assertEquals(listOf("__monomer_result_0", "__monomer_result_1"), columns.map { it.sqlName })
            assertTrue("status AS ${delimiter}__monomer_result_0$delimiter" in sql, sql)
            assertTrue("amount AS ${delimiter}__monomer_result_1$delimiter" in sql, sql)
            assertTrue("ORDER BY ${delimiter}__monomer_result_1$delimiter DESC" in sql, sql)
            assertTrue("LIMIT 7" in sql, sql)
            if (dialect == SqlEngine.BIGQUERY) {
                assertTrue("GROUP BY\n  ${delimiter}__monomer_result_0$delimiter" in sql, sql)
            }
        }
        assertEquals(listOf("status", "status"), root.selectColumns.map { it.columnAlias })
        assertEquals("status", root.groupBys.single().columnAlias)
    }
}
