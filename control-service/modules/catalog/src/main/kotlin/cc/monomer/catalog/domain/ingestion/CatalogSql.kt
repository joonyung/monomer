package cc.monomer.catalog.domain.ingestion

import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogMetadataObservation
import cc.monomer.catalog.domain.CatalogQueryParameter
import cc.monomer.catalog.api.CatalogRelationshipKind
import cc.monomer.catalog.api.hasCatalogLevel
import cc.monomer.connector.api.ConnectorProductType

enum class CatalogQueryKind {
    SELF_METADATA,
    CHILD_LIST,
    RELATION_METADATA,
    RELATION_COLUMNS,
    RELATIONSHIPS,
    RELATION_PRIMARY_KEYS,
    RELATION_FOREIGN_KEYS,
}

data class CatalogSqlQuery(
    val kind: CatalogQueryKind,
    val sql: String,
    val parameters: List<CatalogQueryParameter>,
) {
    init { require(sql.isNotBlank()); require(sql == sql.trim()) }
}

/** Control-owned metadata query generation. Execution receives only the resulting SQL. */
object CatalogSqlGenerator {
    fun relationPlan(
        connectorType: ConnectorProductType,
        connectorProperties: Map<String, String>,
        target: CatalogIngestTaskTarget.Relation,
    ): List<CatalogSqlQuery> = buildList {
        add(forTarget(connectorType, connectorProperties, target, CatalogQueryKind.RELATION_METADATA))
        add(forTarget(connectorType, connectorProperties, target, CatalogQueryKind.RELATION_COLUMNS))
        when (connectorType) {
            ConnectorProductType.POSTGRESQL -> add(forTarget(connectorType, connectorProperties, target, CatalogQueryKind.RELATIONSHIPS))
            ConnectorProductType.REDSHIFT -> {
                add(forTarget(connectorType, connectorProperties, target, CatalogQueryKind.RELATION_PRIMARY_KEYS))
                add(forTarget(connectorType, connectorProperties, target, CatalogQueryKind.RELATION_FOREIGN_KEYS))
            }
            else -> Unit
        }
    }

    fun forTarget(
        connectorType: ConnectorProductType,
        connectorProperties: Map<String, String>,
        target: CatalogIngestTaskTarget,
        kind: CatalogQueryKind,
    ): CatalogSqlQuery {
        val twoDepth = !connectorType.hasCatalogLevel
        when (target) {
            CatalogIngestTaskTarget.Connector -> Unit
            is CatalogIngestTaskTarget.Catalog -> require(!twoDepth)
            is CatalogIngestTaskTarget.Schema -> require((target.catalog == null) == twoDepth)
            is CatalogIngestTaskTarget.Relation -> require((target.catalog == null) == twoDepth)
        }
        return when (connectorType) {
            ConnectorProductType.POSTGRESQL -> postgres(target, kind)
            ConnectorProductType.REDSHIFT -> redshift(requireNotNull(connectorProperties["database"]), target, kind)
            ConnectorProductType.TRINO -> trino(target, kind)
            ConnectorProductType.BIGQUERY -> bigQuery(connectorProperties, target, kind)
            ConnectorProductType.SNOWFLAKE -> genericInformationSchema(target, kind, false)
            ConnectorProductType.DATABRICKS -> databricks(target, kind)
            ConnectorProductType.DUCKDB -> duckDb(target, kind)
        }
    }

    private fun duckDb(target: CatalogIngestTaskTarget, kind: CatalogQueryKind): CatalogSqlQuery = when (kind) {
        CatalogQueryKind.SELF_METADATA -> when (target) {
            CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(kind, "SELECT current_database() AS catalog_name", emptyList())
            is CatalogIngestTaskTarget.Catalog -> error("DuckDB samples have no Catalog level")
            is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(kind,
                "SELECT catalog_name, schema_name FROM information_schema.schemata WHERE catalog_name = current_database() AND schema_name = ? LIMIT 1",
                listOf(CatalogQueryParameter("schema", target.name)))
            is CatalogIngestTaskTarget.Relation -> CatalogSqlQuery(kind,
                "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_catalog = current_database() AND table_schema = ? AND table_name = ? LIMIT 1",
                listOf(CatalogQueryParameter("schema", target.schemaName), CatalogQueryParameter("relation", target.name)))
        }
        CatalogQueryKind.CHILD_LIST -> when (target) {
            CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(kind,
                "SELECT catalog_name, schema_name FROM information_schema.schemata WHERE catalog_name = current_database() ORDER BY schema_name", emptyList())
            is CatalogIngestTaskTarget.Catalog -> error("DuckDB samples have no Catalog level")
            is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(kind,
                "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_catalog = current_database() AND table_schema = ? ORDER BY table_name",
                listOf(CatalogQueryParameter("schema", target.name)))
            is CatalogIngestTaskTarget.Relation -> error("Relation has no child list")
        }
        CatalogQueryKind.RELATION_METADATA -> duckDb(target, CatalogQueryKind.SELF_METADATA).copy(kind = kind)
        CatalogQueryKind.RELATION_COLUMNS -> {
            val relation = requireRelation(target)
            CatalogSqlQuery(kind,
                "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name AS relation_name, column_name, ordinal_position, data_type AS native_type, is_nullable, column_default AS default_expression FROM information_schema.columns WHERE table_catalog = current_database() AND table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                listOf(CatalogQueryParameter("schema", relation.schemaName), CatalogQueryParameter("relation", relation.name)))
        }
        CatalogQueryKind.RELATIONSHIPS, CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS ->
            error("Sample views have no observed relationship constraints")
    }

    private fun databricks(
        target: CatalogIngestTaskTarget,
        kind: CatalogQueryKind,
    ): CatalogSqlQuery {
        if (kind != CatalogQueryKind.RELATION_COLUMNS) return genericInformationSchema(target, kind, true)
        val relation = requireRelation(target)
        return CatalogSqlQuery(
            kind,
            "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name AS relation_name, column_name, ROW_NUMBER() OVER (ORDER BY ordinal_position) AS ordinal_position, data_type AS native_type, is_nullable, column_default AS default_expression FROM information_schema.columns WHERE table_catalog = :catalog AND table_schema = :schema AND table_name = :relation ORDER BY ordinal_position",
            listOf(
                CatalogQueryParameter("catalog", requireNotNull(relation.catalog)),
                CatalogQueryParameter("schema", relation.schemaName),
                CatalogQueryParameter("relation", relation.name),
            ),
        )
    }

    private fun bigQuery(
        connectorProperties: Map<String, String>,
        target: CatalogIngestTaskTarget,
        kind: CatalogQueryKind,
    ): CatalogSqlQuery {
        val projectId = requireNotNull(connectorProperties["project_id"])
        val location = requireNotNull(connectorProperties["location"])
        require(projectId.matches(BIGQUERY_PROJECT_ID_PATTERN))
        require(location.matches(BIGQUERY_LOCATION_PATTERN))
        val informationSchema = "`$projectId.region-$location`.INFORMATION_SCHEMA"
        return when (kind) {
            CatalogQueryKind.SELF_METADATA -> when (target) {
                CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(
                    kind,
                    "SELECT ? AS catalog_name",
                    listOf(CatalogQueryParameter("catalog", projectId)),
                )
                is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(
                    kind,
                    "SELECT catalog_name FROM $informationSchema.SCHEMATA WHERE catalog_name = ? LIMIT 1",
                    listOf(CatalogQueryParameter("catalog", target.name)),
                )
                is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(
                    kind,
                    "SELECT catalog_name, schema_name FROM $informationSchema.SCHEMATA WHERE catalog_name = ? AND schema_name = ? LIMIT 1",
                    listOf(
                        CatalogQueryParameter("catalog", requireNotNull(target.catalog)),
                        CatalogQueryParameter("schema", target.name),
                    ),
                )
                is CatalogIngestTaskTarget.Relation -> CatalogSqlQuery(
                    kind,
                    "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM $informationSchema.TABLES WHERE table_catalog = ? AND table_schema = ? AND table_name = ? LIMIT 1",
                    listOf(
                        CatalogQueryParameter("catalog", requireNotNull(target.catalog)),
                        CatalogQueryParameter("schema", target.schemaName),
                        CatalogQueryParameter("relation", target.name),
                    ),
                )
            }
            CatalogQueryKind.CHILD_LIST -> when (target) {
                CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(
                    kind,
                    "SELECT ? AS catalog_name",
                    listOf(CatalogQueryParameter("catalog", projectId)),
                )
                is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(
                    kind,
                    "SELECT catalog_name, schema_name FROM $informationSchema.SCHEMATA WHERE catalog_name = ? ORDER BY schema_name",
                    listOf(CatalogQueryParameter("catalog", target.name)),
                )
                is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(
                    kind,
                    "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM $informationSchema.TABLES WHERE table_catalog = ? AND table_schema = ? ORDER BY table_name",
                    listOf(
                        CatalogQueryParameter("catalog", requireNotNull(target.catalog)),
                        CatalogQueryParameter("schema", target.name),
                    ),
                )
                is CatalogIngestTaskTarget.Relation -> error("Relation has no child list")
            }
            CatalogQueryKind.RELATION_METADATA -> {
                val relation = requireRelation(target)
                CatalogSqlQuery(
                    kind,
                    "SELECT t.table_catalog AS catalog_name, t.table_schema AS schema_name, t.table_name, t.table_type, IF(c.is_partitioning_column = 'YES', c.column_name, NULL) AS partition_key, IF(c.clustering_ordinal_position IS NOT NULL, c.column_name, NULL) AS clustering_key, c.clustering_ordinal_position, COALESCE(SAFE_CAST(o.option_value AS BOOL), FALSE) AS requires_partition_filter FROM $informationSchema.TABLES t LEFT JOIN $informationSchema.COLUMNS c USING (table_catalog, table_schema, table_name) LEFT JOIN $informationSchema.TABLE_OPTIONS o ON o.table_catalog = t.table_catalog AND o.table_schema = t.table_schema AND o.table_name = t.table_name AND o.option_name = 'require_partition_filter' WHERE t.table_catalog = ? AND t.table_schema = ? AND t.table_name = ? ORDER BY c.clustering_ordinal_position, c.column_name",
                    listOf(
                        CatalogQueryParameter("catalog", requireNotNull(relation.catalog)),
                        CatalogQueryParameter("schema", relation.schemaName),
                        CatalogQueryParameter("relation", relation.name),
                    ),
                )
            }
            CatalogQueryKind.RELATION_COLUMNS -> {
                val relation = requireRelation(target)
                CatalogSqlQuery(
                    kind,
                    "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name AS relation_name, column_name, ordinal_position, data_type AS native_type, is_nullable, column_default AS default_expression FROM $informationSchema.COLUMNS WHERE table_catalog = ? AND table_schema = ? AND table_name = ? AND ordinal_position IS NOT NULL ORDER BY ordinal_position",
                    listOf(
                        CatalogQueryParameter("catalog", requireNotNull(relation.catalog)),
                        CatalogQueryParameter("schema", relation.schemaName),
                        CatalogQueryParameter("relation", relation.name),
                    ),
                )
            }
            CatalogQueryKind.RELATIONSHIPS, CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS -> error("BigQuery relationship metadata is not supported by this Catalog engine")
        }
    }

    private fun postgres(target: CatalogIngestTaskTarget, kind: CatalogQueryKind): CatalogSqlQuery = when (kind) {
        CatalogQueryKind.SELF_METADATA -> when (target) {
            CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(kind, "SELECT current_database() AS catalog_name", emptyList())
            is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(kind, "SELECT catalog_name FROM information_schema.schemata WHERE catalog_name = ? LIMIT 1", listOf(CatalogQueryParameter("catalog", target.name)))
            is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(kind, "SELECT catalog_name, schema_name FROM information_schema.schemata WHERE schema_name = ? LIMIT 1", listOf(CatalogQueryParameter("schema", target.name)))
            is CatalogIngestTaskTarget.Relation -> CatalogSqlQuery(kind, "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_schema = ? AND table_name = ? LIMIT 1", listOf(CatalogQueryParameter("schema", target.schemaName), CatalogQueryParameter("relation", target.name)))
        }
        CatalogQueryKind.CHILD_LIST -> when (target) {
            CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(
                kind,
                "SELECT catalog_name, schema_name FROM information_schema.schemata ORDER BY catalog_name, schema_name",
                emptyList(),
            )
            is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(
                kind,
                "SELECT catalog_name, schema_name FROM information_schema.schemata WHERE catalog_name = ? ORDER BY schema_name",
                listOf(CatalogQueryParameter("catalog", target.name)),
            )
            is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(
                kind,
                "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                listOf(CatalogQueryParameter("schema", target.name)),
            )
            is CatalogIngestTaskTarget.Relation -> error("Relation has no child list")
        }
        CatalogQueryKind.RELATION_METADATA -> postgres(target, CatalogQueryKind.SELF_METADATA).copy(kind = kind)
        CatalogQueryKind.RELATION_COLUMNS -> {
            val relation = requireRelation(target)
            CatalogSqlQuery(
                kind,
                "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name AS relation_name, column_name, ordinal_position, data_type AS native_type, is_nullable, column_default AS default_expression FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                listOf(CatalogQueryParameter("schema", relation.schemaName), CatalogQueryParameter("relation", relation.name)),
            )
        }
        CatalogQueryKind.RELATIONSHIPS -> {
            val relation = requireRelation(target)
            CatalogSqlQuery(
                kind,
                """
                SELECT tc.table_catalog AS catalog_name, tc.table_schema AS schema_name, tc.table_name AS relation_name,
                       tc.constraint_name, 'PRIMARY_KEY' AS relationship_kind, kcu.ordinal_position AS member_ordinal,
                       kcu.column_name AS source_column, NULL::text AS referenced_catalog, NULL::text AS referenced_schema,
                       NULL::text AS referenced_relation, NULL::text AS referenced_column
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_catalog = tc.constraint_catalog
                 AND kcu.constraint_schema = tc.constraint_schema
                 AND kcu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'PRIMARY KEY'
                UNION ALL
                SELECT tc.table_catalog AS catalog_name, tc.table_schema AS schema_name, tc.table_name AS relation_name,
                       tc.constraint_name, 'FOREIGN_KEY' AS relationship_kind, kcu.ordinal_position AS member_ordinal,
                       kcu.column_name AS source_column, referenced_kcu.table_catalog AS referenced_catalog,
                       referenced_kcu.table_schema AS referenced_schema, referenced_kcu.table_name AS referenced_relation,
                       referenced_kcu.column_name AS referenced_column
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_catalog = tc.constraint_catalog
                 AND kcu.constraint_schema = tc.constraint_schema
                 AND kcu.constraint_name = tc.constraint_name
                JOIN information_schema.referential_constraints rc
                  ON rc.constraint_catalog = tc.constraint_catalog
                 AND rc.constraint_schema = tc.constraint_schema
                 AND rc.constraint_name = tc.constraint_name
                JOIN information_schema.key_column_usage referenced_kcu
                  ON referenced_kcu.constraint_catalog = rc.unique_constraint_catalog
                 AND referenced_kcu.constraint_schema = rc.unique_constraint_schema
                 AND referenced_kcu.constraint_name = rc.unique_constraint_name
                 AND referenced_kcu.ordinal_position = kcu.position_in_unique_constraint
                WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'FOREIGN KEY'
                ORDER BY constraint_name, member_ordinal
                """.trimIndent(),
                listOf(
                    CatalogQueryParameter("schema", relation.schemaName),
                    CatalogQueryParameter("relation", relation.name),
                    CatalogQueryParameter("schema", relation.schemaName),
                    CatalogQueryParameter("relation", relation.name),
                ),
            )
        }
        CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS ->
            error("PostgreSQL uses the combined relationship query")
    }

    private fun redshift(database: String, target: CatalogIngestTaskTarget, kind: CatalogQueryKind): CatalogSqlQuery = when (kind) {
        CatalogQueryKind.SELF_METADATA -> when (target) {
            CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(
                kind,
                "SELECT current_database() AS catalog_name",
                emptyList(),
            )
            is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(
                kind,
                "SELECT database_name AS catalog_name FROM svv_all_schemas WHERE database_name = ? GROUP BY database_name LIMIT 1",
                listOf(CatalogQueryParameter("catalog", target.name)),
            )
            is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(
                kind,
                "SELECT database_name AS catalog_name, schema_name FROM svv_all_schemas WHERE database_name = ? AND schema_name = ? LIMIT 1",
                listOf(
                    CatalogQueryParameter("catalog", database),
                    CatalogQueryParameter("schema", target.name),
                ),
            )
            is CatalogIngestTaskTarget.Relation -> CatalogSqlQuery(
                kind,
                "SELECT database_name AS catalog_name, schema_name, table_name, table_type FROM svv_all_tables WHERE database_name = ? AND schema_name = ? AND table_name = ? LIMIT 1",
                listOf(
                    CatalogQueryParameter("catalog", database),
                    CatalogQueryParameter("schema", target.schemaName),
                    CatalogQueryParameter("relation", target.name),
                ),
            )
        }
        CatalogQueryKind.CHILD_LIST -> when (target) {
            CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(
                kind,
                "SELECT database_name AS catalog_name, schema_name FROM svv_all_schemas WHERE database_name = ? AND schema_name NOT IN ('information_schema', 'pg_catalog') ORDER BY schema_name",
                listOf(CatalogQueryParameter("catalog", database)),
            )
            is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(
                kind,
                "SELECT database_name AS catalog_name, schema_name FROM svv_all_schemas WHERE database_name = ? AND schema_name NOT IN ('information_schema', 'pg_catalog') ORDER BY schema_name",
                listOf(CatalogQueryParameter("catalog", target.name)),
            )
            is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(
                kind,
                "SELECT database_name AS catalog_name, schema_name, table_name, table_type FROM svv_all_tables WHERE database_name = ? AND schema_name = ? ORDER BY table_name",
                listOf(
                    CatalogQueryParameter("catalog", database),
                    CatalogQueryParameter("schema", target.name),
                ),
            )
            is CatalogIngestTaskTarget.Relation -> error("Relation has no child list")
        }
        CatalogQueryKind.RELATION_METADATA -> redshift(database, target, CatalogQueryKind.SELF_METADATA).copy(kind = kind)
        CatalogQueryKind.RELATION_COLUMNS -> {
            val relation = requireRelation(target)
            CatalogSqlQuery(
                kind,
                "SELECT database_name AS catalog_name, schema_name, table_name AS relation_name, column_name, ordinal_position, data_type AS native_type, is_nullable, column_default AS default_expression FROM svv_all_columns WHERE database_name = ? AND schema_name = ? AND table_name = ? ORDER BY ordinal_position",
                listOf(
                    CatalogQueryParameter("catalog", database),
                    CatalogQueryParameter("schema", relation.schemaName),
                    CatalogQueryParameter("relation", relation.name),
                ),
            )
        }
        CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS -> {
            val relation = requireRelation(target)
            val qualifiedTable = listOf(database, relation.schemaName, relation.name).joinToString(".", transform = ::quoteSqlIdentifier)
            val keyType = if (kind == CatalogQueryKind.RELATION_PRIMARY_KEYS) "PRIMARY" else "FOREIGN"
            CatalogSqlQuery(kind, "SHOW CONSTRAINTS $keyType KEYS FROM TABLE $qualifiedTable", emptyList())
        }
        CatalogQueryKind.RELATIONSHIPS -> error("Redshift requires separate primary and foreign key queries")
    }

    private fun trino(target: CatalogIngestTaskTarget, kind: CatalogQueryKind): CatalogSqlQuery = when (kind) {
        CatalogQueryKind.SELF_METADATA -> when (target) {
            CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(kind, "SELECT current_catalog AS catalog_name", emptyList())
            is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(kind, "SELECT catalog_name FROM ${quoteSqlIdentifier(target.name)}.information_schema.schemata WHERE catalog_name = ? LIMIT 1", listOf(CatalogQueryParameter("catalog", target.name)))
            is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(kind, "SELECT catalog_name, schema_name FROM ${quoteSqlIdentifier(requireNotNull(target.catalog))}.information_schema.schemata WHERE schema_name = ? GROUP BY catalog_name, schema_name", listOf(CatalogQueryParameter("schema", target.name)))
            is CatalogIngestTaskTarget.Relation -> CatalogSqlQuery(kind, "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM ${quoteSqlIdentifier(requireNotNull(target.catalog))}.information_schema.tables WHERE table_schema = ? AND table_name = ? LIMIT 1", listOf(CatalogQueryParameter("schema", target.schemaName), CatalogQueryParameter("relation", target.name)))
        }
        CatalogQueryKind.CHILD_LIST -> when (target) {
            CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(kind, "SHOW CATALOGS", emptyList())
            is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(
                kind,
                "SELECT catalog_name, schema_name FROM ${quoteSqlIdentifier(target.name)}.information_schema.schemata WHERE catalog_name = ? ORDER BY schema_name",
                listOf(CatalogQueryParameter("catalog", target.name)),
            )
            is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(
                kind,
                "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM ${quoteSqlIdentifier(requireNotNull(target.catalog))}.information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                listOf(CatalogQueryParameter("schema", target.name)),
            )
            is CatalogIngestTaskTarget.Relation -> error("Relation has no child list")
        }
        CatalogQueryKind.RELATION_METADATA -> trino(target, CatalogQueryKind.SELF_METADATA).copy(kind = kind)
        CatalogQueryKind.RELATION_COLUMNS -> {
            val relation = requireRelation(target)
            CatalogSqlQuery(
                kind,
                "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name AS relation_name, column_name, ordinal_position, data_type AS native_type, is_nullable, column_default AS default_expression FROM ${quoteSqlIdentifier(requireNotNull(relation.catalog))}.information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                listOf(CatalogQueryParameter("schema", relation.schemaName), CatalogQueryParameter("relation", relation.name)),
            )
        }
        CatalogQueryKind.RELATIONSHIPS, CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS -> error("Trino does not expose relationship metadata through the Catalog query contract")
    }

    private fun genericInformationSchema(
        target: CatalogIngestTaskTarget, kind: CatalogQueryKind, namedParameters: Boolean,
    ): CatalogSqlQuery {
        val catalogMarker = if (namedParameters) ":catalog" else "?"
        val schemaMarker = if (namedParameters) ":schema" else "?"
        val relationMarker = if (namedParameters) ":relation" else "?"
        return when (kind) {
            CatalogQueryKind.SELF_METADATA -> when (target) {
                CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(kind, "SELECT current_catalog AS catalog_name", emptyList())
                is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(kind, "SELECT catalog_name FROM information_schema.schemata WHERE catalog_name = $catalogMarker LIMIT 1", listOf(CatalogQueryParameter("catalog", target.name)))
                is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(kind, "SELECT catalog_name, schema_name FROM information_schema.schemata WHERE catalog_name = $catalogMarker AND schema_name = $schemaMarker LIMIT 1", listOf(CatalogQueryParameter("catalog", requireNotNull(target.catalog)), CatalogQueryParameter("schema", target.name)))
                is CatalogIngestTaskTarget.Relation -> CatalogSqlQuery(kind, "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_catalog = $catalogMarker AND table_schema = $schemaMarker AND table_name = $relationMarker LIMIT 1", listOf(CatalogQueryParameter("catalog", requireNotNull(target.catalog)), CatalogQueryParameter("schema", target.schemaName), CatalogQueryParameter("relation", target.name)))
            }
            CatalogQueryKind.CHILD_LIST -> when (target) {
                CatalogIngestTaskTarget.Connector -> CatalogSqlQuery(
                    kind,
                    "SELECT catalog_name FROM information_schema.schemata GROUP BY catalog_name ORDER BY catalog_name",
                    emptyList(),
                )
                is CatalogIngestTaskTarget.Catalog -> CatalogSqlQuery(
                    kind,
                    "SELECT catalog_name, schema_name FROM information_schema.schemata WHERE catalog_name = $catalogMarker ORDER BY schema_name",
                    listOf(CatalogQueryParameter("catalog", target.name)),
                )
                is CatalogIngestTaskTarget.Schema -> CatalogSqlQuery(
                    kind,
                    "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_catalog = $catalogMarker AND table_schema = $schemaMarker ORDER BY table_name",
                    listOf(CatalogQueryParameter("catalog", requireNotNull(target.catalog)), CatalogQueryParameter("schema", target.name)),
                )
                is CatalogIngestTaskTarget.Relation -> error("Relation has no child list")
            }
            CatalogQueryKind.RELATION_METADATA -> genericInformationSchema(target, CatalogQueryKind.SELF_METADATA, namedParameters).copy(kind = kind)
            CatalogQueryKind.RELATION_COLUMNS -> {
                val relation = requireRelation(target)
                CatalogSqlQuery(
                    kind,
                    "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name AS relation_name, column_name, ordinal_position, data_type AS native_type, is_nullable, column_default AS default_expression FROM information_schema.columns WHERE table_catalog = $catalogMarker AND table_schema = $schemaMarker AND table_name = $relationMarker ORDER BY ordinal_position",
                    listOf(CatalogQueryParameter("catalog", requireNotNull(relation.catalog)), CatalogQueryParameter("schema", relation.schemaName), CatalogQueryParameter("relation", relation.name)),
                )
            }
            CatalogQueryKind.RELATIONSHIPS, CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS -> error("Relationship metadata is not supported by this Catalog engine")
        }
    }

    private val BIGQUERY_PROJECT_ID_PATTERN = Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")
    private val BIGQUERY_LOCATION_PATTERN = Regex("(?![A-Za-z0-9-]*--)[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
}

private fun requireRelation(target: CatalogIngestTaskTarget): CatalogIngestTaskTarget.Relation =
    target as? CatalogIngestTaskTarget.Relation ?: error("Relation metadata query requires a relation target")

private fun quoteSqlIdentifier(value: String): String {
    require(value.isNotBlank() && value == value.trim() && value.none(Character::isISOControl))
    return "\"${value.replace("\"", "\"\"")}\""
}

/** Rows are supplied by the inline Arrow decoder owned by the Control application. */
data class CatalogQueryRow(val values: Map<String, String?>) {
    fun required(name: String): String = requireNotNull(values[name]) { "missing catalog column: $name" }
}

object CatalogMetadataParser {
    fun canonicalRows(
        connectorType: ConnectorProductType,
        connectorProperties: Map<String, String>,
        kind: CatalogQueryKind,
        rows: List<CatalogQueryRow>,
    ): List<CatalogQueryRow> {
        if (connectorType.hasCatalogLevel) {
            return rows
        }
        val database = if (connectorType == ConnectorProductType.DUCKDB) {
            require(connectorProperties["sample_dataset"] in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) { "Sample dataset is unsupported" }
            "acme_cloud"
        } else requireNotNull(connectorProperties["database"])
        return rows.map { nativeRow ->
            val row = if (connectorType == ConnectorProductType.REDSHIFT) redshiftRow(kind, nativeRow) else nativeRow
            require(row.required("catalog_name") == database) { "Catalog observation is outside the connector database" }
            val referencedCatalog = row.values["referenced_catalog"]
            if (row.values["relationship_kind"].equals("FOREIGN_KEY", ignoreCase = true) || referencedCatalog != null) {
                require(referencedCatalog == database) { "Catalog relationship is outside the connector database" }
            }
            row.copy(values = row.values + mapOf("catalog_name" to null, "referenced_catalog" to null))
        }
    }

    private fun redshiftRow(kind: CatalogQueryKind, row: CatalogQueryRow): CatalogQueryRow = when (kind) {
        CatalogQueryKind.RELATION_PRIMARY_KEYS -> CatalogQueryRow(mapOf(
            "catalog_name" to row.required("database_name"),
            "schema_name" to row.required("schema_name"),
            "relation_name" to row.required("table_name"),
            "constraint_name" to row.required("pk_name"),
            "relationship_kind" to "PRIMARY_KEY",
            "member_ordinal" to row.required("key_seq"),
            "source_column" to row.required("column_name"),
        ))
        CatalogQueryKind.RELATION_FOREIGN_KEYS -> CatalogQueryRow(mapOf(
            "catalog_name" to row.required("fk_database_name"),
            "schema_name" to row.required("fk_schema_name"),
            "relation_name" to row.required("fk_table_name"),
            "constraint_name" to row.required("fk_name"),
            "relationship_kind" to "FOREIGN_KEY",
            "member_ordinal" to row.required("key_seq"),
            "source_column" to row.required("fk_column_name"),
            "referenced_catalog" to row.required("pk_database_name"),
            "referenced_schema" to row.required("pk_schema_name"),
            "referenced_relation" to row.required("pk_table_name"),
            "referenced_column" to row.required("pk_column_name"),
        ))
        else -> row
    }

    fun catalogs(rows: Sequence<CatalogQueryRow>): List<CatalogMetadataObservation.Catalog> = rows.map {
        CatalogMetadataObservation.Catalog(
            it.values["catalog_name"] ?: it.values["catalog"] ?: it.values["Catalog"]
            ?: error("missing catalog column: catalog_name"),
        )
    }.toList()

    fun schemas(rows: Sequence<CatalogQueryRow>): List<CatalogMetadataObservation.Schema> = rows.map {
        CatalogMetadataObservation.Schema(it.values["catalog_name"], it.required("schema_name"))
    }.toList()

    fun relations(rows: Sequence<CatalogQueryRow>): List<CatalogMetadataObservation.Relation> = rows.toList()
        .groupBy { Triple(it.values["catalog_name"], it.required("schema_name"), it.values["table_name"] ?: it.required("relation_name")) }
        .map { (address, relationRows) ->
            val row = relationRows.first()
            val partitionObserved = "requires_partition_filter" in row.values
            CatalogMetadataObservation.Relation(
                catalog = address.first,
                schema = address.second,
                name = address.third,
                relationKind = relationKind(row.values["table_type"]),
                definition = row.values["definition"],
                partitionKeys = if (partitionObserved) relationRows.mapNotNull { it.values["partition_key"] }.distinct() else null,
                clusteringKeys = if (partitionObserved) relationRows.filter { it.values["clustering_key"] != null }
                    .sortedBy { it.required("clustering_ordinal_position").toInt() }.map { it.required("clustering_key") }.distinct() else null,
                requiresPartitionFilter = if (partitionObserved) row.required("requires_partition_filter").toBooleanStrict() else null,
            )
        }

    fun columns(rows: Sequence<CatalogQueryRow>): List<CatalogMetadataObservation.Column> = rows.map {
        CatalogMetadataObservation.Column(
            catalog = it.values["catalog_name"],
            schema = it.required("schema_name"),
            relation = it.values["relation_name"] ?: it.values["table_name"] ?: error("missing catalog column: relation_name"),
            name = it.required("column_name"),
            ordinal = it.required("ordinal_position").toInt(),
            nativeType = it.values["native_type"] ?: it.values["data_type"] ?: "UNKNOWN",
            nullable = (it.values["is_nullable"] ?: "YES").equals("YES", ignoreCase = true),
            defaultExpression = it.values["default_expression"] ?: it.values["column_default"],
        )
    }.toList()

    fun relationships(rows: Sequence<CatalogQueryRow>): List<CatalogMetadataObservation.RelationshipMember> = rows.map {
        CatalogMetadataObservation.RelationshipMember(
            catalog = it.values["catalog_name"],
            schema = it.required("schema_name"),
            sourceRelation = it.values["relation_name"] ?: it.values["table_name"] ?: error("missing catalog column: relation_name"),
            constraintName = it.required("constraint_name"),
            relationshipKind = when (it.required("relationship_kind").uppercase()) {
                "PRIMARY_KEY" -> CatalogRelationshipKind.PRIMARY_KEY
                "FOREIGN_KEY" -> CatalogRelationshipKind.FOREIGN_KEY
                else -> error("unsupported relationship kind")
            },
            memberOrdinal = it.required("member_ordinal").toInt(),
            sourceColumn = it.required("source_column"),
            referencedCatalog = it.values["referenced_catalog"],
            referencedSchema = it.values["referenced_schema"],
            referencedRelation = it.values["referenced_relation"],
            referencedColumn = it.values["referenced_column"],
        )
    }.toList()

    private fun relationKind(value: String?): RelationKind = when (value?.uppercase()) {
        "VIEW" -> RelationKind.VIEW
        "MATERIALIZED VIEW", "MATERIALIZED_VIEW" -> RelationKind.MATERIALIZED_VIEW
        "EXTERNAL", "EXTERNAL TABLE" -> RelationKind.EXTERNAL
        "FOREIGN" -> RelationKind.FOREIGN
        else -> RelationKind.TABLE
    }
}
