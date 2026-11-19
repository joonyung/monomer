package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.metricflow.application.engine.SqlPlanRendererRegistry
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnAliasReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.domain.sql.render.SqlPlanRenderResult
import cc.monomer.metricflow.domain.sql.render.SqlPlanRenderer
import cc.monomer.semantic.api.SemanticResultColumn
import cc.monomer.semantic.api.SemanticResultColumnKey

/**
 * Renames the final projection to the request's result columns. Time columns grouped at day or coarser
 * grain become DATE there, after grouping has been matched to the engine's own projected expressions.
 */
internal class MetricFlowResultProjection(
    private val columns: List<SemanticResultColumn>,
    private val orderByColumns: List<SemanticResultColumn>,
    private val calendarDateColumns: Set<SemanticResultColumnKey>,
) : SqlPlanRendererRegistry {
    var sourceAliases: List<String>? = null
        private set

    override fun rendererFor(dialect: SqlEngine): SqlPlanRenderer {
        val delegate = ControlMetricFlowSqlPlanRendererRegistry.rendererFor(dialect)
        return object : SqlPlanRenderer by delegate {
            override fun renderSqlPlan(sqlQueryPlan: SqlPlan): SqlPlanRenderResult {
                val select = requireNotNull(sqlQueryPlan.renderNode.asSelectNode) {
                    "semantic result must have a final select projection"
                }
                require(select.selectColumns.size == columns.size) {
                    "semantic result projection count differs from selectors"
                }
                sourceAliases = select.selectColumns.map { it.columnAlias }
                require(select.orderBys.size == orderByColumns.size) {
                    "semantic result ordering differs from requested ordering"
                }
                return delegate.renderNode(
                    SqlSelectStatementNode.create(
                        description = select.description,
                        selectColumns = select.selectColumns.zip(columns) { column, output ->
                            val expression = if (output.key in calendarDateColumns) {
                                val rendered = delegate.exprRenderer.renderSqlExpr(column.expr)
                                SqlStringExpression.create(
                                    sqlExpr = "CAST(${rendered.sql} AS DATE)",
                                    bindParameterSet = rendered.bindParameterSet,
                                    requiresParenthesis = false,
                                    usedColumns = null,
                                )
                            } else column.expr
                            SqlSelectColumn(expression, output.quotedSqlName(dialect))
                        },
                        fromSource = select.fromSource,
                        fromSourceAlias = select.fromSourceAlias,
                        cteSources = select.cteSources,
                        joinDescs = select.joinDescs,
                        groupBys = select.groupBys.map { groupBy ->
                            val candidates = select.selectColumns.indices.filter { index ->
                                select.selectColumns[index].columnAlias == groupBy.columnAlias
                            }
                            val index = candidates.singleOrNull() ?: candidates.singleOrNull { index ->
                                select.selectColumns[index].expr.matches(groupBy.expr)
                            }
                            requireNotNull(index) { "semantic grouping must reference an unambiguous projected expression" }
                            groupBy.copyWithNewAlias(columns[index].quotedSqlName(dialect))
                        },
                        orderBys = select.orderBys.zip(orderByColumns) { order, output ->
                            order.copy(expr = SqlColumnAliasReferenceExpression(output.quotedSqlName(dialect)))
                        },
                        where = select.where,
                        limit = select.limit,
                        distinct = select.distinct,
                    ),
                )
            }
        }
    }
}

private fun SemanticResultColumn.quotedSqlName(dialect: SqlEngine): String {
    val delimiter = when (dialect) {
        SqlEngine.BIGQUERY, SqlEngine.DATABRICKS -> "`"
        else -> "\""
    }
    return "$delimiter$sqlName$delimiter"
}
