package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.metricflow.application.engine.SqlPlanRendererRegistration
import cc.monomer.metricflow.application.engine.SqlPlanRendererRegistry
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.databricks.DatabricksSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.duckdb.DuckDbSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.postgres.PostgresSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.redshift.RedshiftSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.snowflake.SnowflakeSqlPlanRenderer
import cc.monomer.metricflow.infrastructure.sql.render.trino.TrinoSqlPlanRenderer

internal object ControlMetricFlowSqlPlanRendererRegistry : SqlPlanRendererRegistry by SqlPlanRendererRegistry.of(
    SqlPlanRendererRegistration(SqlEngine.BIGQUERY, BigQuerySqlPlanRenderer()),
    SqlPlanRendererRegistration(SqlEngine.DATABRICKS, DatabricksSqlPlanRenderer()),
    SqlPlanRendererRegistration(SqlEngine.DUCKDB, DuckDbSqlPlanRenderer()),
    SqlPlanRendererRegistration(SqlEngine.POSTGRES, PostgresSqlPlanRenderer()),
    SqlPlanRendererRegistration(SqlEngine.REDSHIFT, RedshiftSqlPlanRenderer()),
    SqlPlanRendererRegistration(SqlEngine.SNOWFLAKE, SnowflakeSqlPlanRenderer()),
    SqlPlanRendererRegistration(SqlEngine.TRINO, TrinoSqlPlanRenderer()),
)
