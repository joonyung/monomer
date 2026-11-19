package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorProductType

val ConnectorProductType.hasCatalogLevel: Boolean
    get() = when (this) {
        ConnectorProductType.POSTGRESQL,
        ConnectorProductType.REDSHIFT,
        ConnectorProductType.DUCKDB,
        -> false
        ConnectorProductType.BIGQUERY,
        ConnectorProductType.SNOWFLAKE,
        ConnectorProductType.DATABRICKS,
        ConnectorProductType.TRINO,
        -> true
    }
