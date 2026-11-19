package cc.monomer.catalog.domain.ingestion

import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.connector.api.ConnectorProductType
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogSqlTest {
    @Test
    fun `BigQuery collects pseudo partition keys and ordered clustering rows independently of column ordinals`() {
        val target = CatalogIngestTaskTarget.Relation("monomer-test", "analytics", "events", cc.monomer.catalog.api.RelationKind.TABLE)
        val properties = mapOf("project_id" to "monomer-test", "location" to "us")
        val columns = CatalogSqlGenerator.forTarget(ConnectorProductType.BIGQUERY, properties, target, CatalogQueryKind.RELATION_COLUMNS)
        assertTrue("ordinal_position IS NOT NULL" in columns.sql)
        val metadata = CatalogSqlGenerator.forTarget(ConnectorProductType.BIGQUERY, properties, target, CatalogQueryKind.RELATION_METADATA)
        assertTrue("TABLE_OPTIONS" in metadata.sql && "is_partitioning_column" in metadata.sql)
        val base = mapOf("catalog_name" to "monomer-test", "schema_name" to "analytics", "table_name" to "events", "table_type" to "BASE TABLE", "requires_partition_filter" to "true")
        val observations = CatalogMetadataParser.relations(sequenceOf(
            CatalogQueryRow(base + mapOf("partition_key" to "_PARTITIONTIME", "clustering_key" to null)),
            CatalogQueryRow(base + mapOf("clustering_key" to "region", "clustering_ordinal_position" to "2")),
            CatalogQueryRow(base + mapOf("clustering_key" to "customer_id", "clustering_ordinal_position" to "1")),
        ))
        assertEquals(1, observations.size)
        assertEquals(listOf("_PARTITIONTIME"), observations.single().partitionKeys)
        assertEquals(listOf("customer_id", "region"), observations.single().clusteringKeys)
        assertEquals(true, observations.single().requiresPartitionFilter)
        val plain = CatalogMetadataParser.relations(sequenceOf(CatalogQueryRow(base - "requires_partition_filter"))).single()
        assertEquals(null, plain.partitionKeys)
        assertEquals(null, plain.requiresPartitionFilter)
        assertFalse(cc.monomer.catalog.domain.digestObservation(plain) == cc.monomer.catalog.domain.digestObservation(observations.single()))
    }

    @Test
    fun `all Databricks catalog queries use their parameter names while Snowflake SQL stays positional`() {
        val connector = CatalogIngestTaskTarget.Connector
        val catalog = CatalogIngestTaskTarget.Catalog("warehouse")
        val schema = CatalogIngestTaskTarget.Schema("warehouse", "analytics")
        val relation = CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", cc.monomer.catalog.api.RelationKind.TABLE)
        val cases = listOf(
            Triple(connector, CatalogQueryKind.SELF_METADATA, "SELECT current_catalog AS catalog_name"),
            Triple(connector, CatalogQueryKind.CHILD_LIST, "SELECT catalog_name FROM information_schema.schemata GROUP BY catalog_name ORDER BY catalog_name"),
            Triple(catalog, CatalogQueryKind.SELF_METADATA, "SELECT catalog_name FROM information_schema.schemata WHERE catalog_name = ? LIMIT 1"),
            Triple(catalog, CatalogQueryKind.CHILD_LIST, "SELECT catalog_name, schema_name FROM information_schema.schemata WHERE catalog_name = ? ORDER BY schema_name"),
            Triple(schema, CatalogQueryKind.SELF_METADATA, "SELECT catalog_name, schema_name FROM information_schema.schemata WHERE catalog_name = ? AND schema_name = ? LIMIT 1"),
            Triple(schema, CatalogQueryKind.CHILD_LIST, "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_catalog = ? AND table_schema = ? ORDER BY table_name"),
            Triple(relation, CatalogQueryKind.SELF_METADATA, "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_catalog = ? AND table_schema = ? AND table_name = ? LIMIT 1"),
            Triple(relation, CatalogQueryKind.RELATION_METADATA, "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_catalog = ? AND table_schema = ? AND table_name = ? LIMIT 1"),
            Triple(relation, CatalogQueryKind.RELATION_COLUMNS, "SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name AS relation_name, column_name, ordinal_position, data_type AS native_type, is_nullable, column_default AS default_expression FROM information_schema.columns WHERE table_catalog = ? AND table_schema = ? AND table_name = ? ORDER BY ordinal_position"),
        )
        cases.forEach { (target, kind, expectedSql) ->
            val snowflake = CatalogSqlGenerator.forTarget(ConnectorProductType.SNOWFLAKE, emptyMap(), target, kind)
            val databricks = CatalogSqlGenerator.forTarget(ConnectorProductType.DATABRICKS, emptyMap(), target, kind)
            assertEquals(expectedSql, snowflake.sql)
            assertEquals(snowflake.parameters, databricks.parameters)
            val names = Regex(":([A-Za-z_][A-Za-z0-9_]*)").findAll(databricks.sql).map { it.groupValues[1] }.toList()
            assertEquals(databricks.parameters.map { it.name }, names)
            assertFalse('?' in databricks.sql)
            assertEquals(expectedSql, databricks.sql.replace(Regex(":(catalog|schema|relation)"), "?")
                .replace("ROW_NUMBER() OVER (ORDER BY ordinal_position) AS ordinal_position", "ordinal_position"))
        }
    }

    @Test
    fun `postgres self metadata queries the requested schema and relation`() {
        val schema = CatalogSqlGenerator.forTarget(
            ConnectorProductType.POSTGRESQL,
            emptyMap(),
            CatalogIngestTaskTarget.Schema(null, "analytics"),
            CatalogQueryKind.SELF_METADATA,
        )
        val relation = CatalogSqlGenerator.forTarget(
            ConnectorProductType.POSTGRESQL,
            emptyMap(),
            CatalogIngestTaskTarget.Relation(null, "analytics", "orders", cc.monomer.catalog.api.RelationKind.TABLE),
            CatalogQueryKind.SELF_METADATA,
        )

        assertTrue("information_schema.schemata" in schema.sql)
        assertTrue("information_schema.tables" in relation.sql)
        assertTrue(schema.parameters.size == 1)
        assertTrue(relation.parameters.size == 2)
        assertFalse("current_database()" in schema.sql)
        assertFalse("current_database()" in relation.sql)
        assertTrue(schema.sql.count { it == '?' } == schema.parameters.size)
        assertTrue(relation.sql.count { it == '?' } == relation.parameters.size)
    }

    @Test
    fun `trino uses supported catalog and information schema queries with quoted identifiers`() {
        val connector = CatalogSqlGenerator.forTarget(
            ConnectorProductType.TRINO,
            emptyMap(),
            CatalogIngestTaskTarget.Connector,
            CatalogQueryKind.CHILD_LIST,
        )
        val catalog = CatalogSqlGenerator.forTarget(
            ConnectorProductType.TRINO,
            emptyMap(),
            CatalogIngestTaskTarget.Catalog("hive\"lake"),
            CatalogQueryKind.CHILD_LIST,
        )

        assertTrue(connector.sql == "SHOW CATALOGS")
        assertTrue("information_schema.schemata" in catalog.sql)
        assertTrue("\"hive\"\"lake\"" in catalog.sql)
        assertTrue(catalog.parameters.single().value == "hive\"lake")
        assertTrue(catalog.sql.count { it == '?' } == catalog.parameters.size)
        assertFalse("schema_properties" in connector.sql)
    }

    @Test
    fun `three depth information schema queries stay inside the selected catalog`() {
        val catalogChildren = CatalogSqlGenerator.forTarget(
            ConnectorProductType.SNOWFLAKE,
            emptyMap(),
            CatalogIngestTaskTarget.Catalog("warehouse"),
            CatalogQueryKind.CHILD_LIST,
        )
        val schemaChildren = CatalogSqlGenerator.forTarget(
            ConnectorProductType.SNOWFLAKE,
            emptyMap(),
            CatalogIngestTaskTarget.Schema("warehouse", "analytics"),
            CatalogQueryKind.CHILD_LIST,
        )

        assertTrue("information_schema.schemata" in catalogChildren.sql)
        assertTrue("catalog_name = ?" in catalogChildren.sql)
        assertTrue(catalogChildren.parameters.map { it.value } == listOf("warehouse"))
        assertTrue("table_catalog = ?" in schemaChildren.sql)
        assertTrue("table_schema = ?" in schemaChildren.sql)
        assertTrue(schemaChildren.parameters.map { it.value } == listOf("warehouse", "analytics"))
        listOf(catalogChildren, schemaChildren).forEach { query ->
            assertTrue(query.sql.count { it == '?' } == query.parameters.size)
        }
    }

    @Test
    fun `databricks normalizes column ordinals to the one based catalog contract`() {
        val relationColumns = CatalogSqlGenerator.forTarget(
            ConnectorProductType.DATABRICKS,
            emptyMap(),
            CatalogIngestTaskTarget.Relation(
                "workspace",
                "example_ext_test",
                "orders",
                cc.monomer.catalog.api.RelationKind.TABLE,
            ),
            CatalogQueryKind.RELATION_COLUMNS,
        )
        assertTrue("ROW_NUMBER() OVER (ORDER BY ordinal_position) AS ordinal_position" in relationColumns.sql)
        assertTrue(
            relationColumns.parameters.map { it.value } ==
                listOf("workspace", "example_ext_test", "orders"),
        )
        assertTrue(relationColumns.parameters.all { ":${it.name}" in relationColumns.sql })
        assertFalse('?' in relationColumns.sql)
    }

    @Test
    fun `bigquery scopes metadata queries to the connector project and region`() {
        val properties = mapOf("project_id" to "example-project", "location" to "asia-northeast3")
        val catalogChildren = CatalogSqlGenerator.forTarget(
            ConnectorProductType.BIGQUERY,
            properties,
            CatalogIngestTaskTarget.Catalog("example-project"),
            CatalogQueryKind.CHILD_LIST,
        )
        val relationColumns = CatalogSqlGenerator.forTarget(
            ConnectorProductType.BIGQUERY,
            properties,
            CatalogIngestTaskTarget.Relation(
                "example-project",
                "example_ext_test",
                "orders",
                cc.monomer.catalog.api.RelationKind.TABLE,
            ),
            CatalogQueryKind.RELATION_COLUMNS,
        )

        assertTrue("`example-project.region-asia-northeast3`.INFORMATION_SCHEMA.SCHEMATA" in catalogChildren.sql)
        assertTrue("`example-project.region-asia-northeast3`.INFORMATION_SCHEMA.COLUMNS" in relationColumns.sql)
        assertTrue(catalogChildren.parameters.map { it.value } == listOf("example-project"))
        assertTrue(
            relationColumns.parameters.map { it.value } ==
                listOf("example-project", "example_ext_test", "orders"),
        )
        listOf(catalogChildren, relationColumns).forEach { query ->
            assertTrue(query.sql.count { it == '?' } == query.parameters.size)
        }
    }

    @Test
    fun `bigquery rejects missing or unsafe information schema qualifiers`() {
        assertFailsWith<IllegalArgumentException> {
            CatalogSqlGenerator.forTarget(
                ConnectorProductType.BIGQUERY,
                mapOf("project_id" to "example-project"),
                CatalogIngestTaskTarget.Connector,
                CatalogQueryKind.CHILD_LIST,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogSqlGenerator.forTarget(
                ConnectorProductType.BIGQUERY,
                mapOf("project_id" to "example-project`; DROP TABLE x; --", "location" to "asia-northeast3"),
                CatalogIngestTaskTarget.Connector,
                CatalogQueryKind.CHILD_LIST,
            )
        }
    }

    @Test
    fun `redshift uses privilege aware system views for metadata discovery`() {
        val connectorChildren = CatalogSqlGenerator.forTarget(
            ConnectorProductType.REDSHIFT,
            mapOf("database" to "dev"),
            CatalogIngestTaskTarget.Connector,
            CatalogQueryKind.CHILD_LIST,
        )
        val schemaChildren = CatalogSqlGenerator.forTarget(
            ConnectorProductType.REDSHIFT,
            mapOf("database" to "dev"),
            CatalogIngestTaskTarget.Schema(null, "monomer_ext_test"),
            CatalogQueryKind.CHILD_LIST,
        )
        val relationColumns = CatalogSqlGenerator.forTarget(
            ConnectorProductType.REDSHIFT,
            mapOf("database" to "dev"),
            CatalogIngestTaskTarget.Relation(
                null,
                "monomer_ext_test",
                "orders",
                cc.monomer.catalog.api.RelationKind.TABLE,
            ),
            CatalogQueryKind.RELATION_COLUMNS,
        )


        assertTrue("svv_all_schemas" in connectorChildren.sql)
        assertTrue("schema_name NOT IN ('information_schema', 'pg_catalog')" in connectorChildren.sql)
        assertTrue(connectorChildren.parameters.map { it.value } == listOf("dev"))
        assertTrue("svv_all_tables" in schemaChildren.sql)
        assertTrue(schemaChildren.parameters.map { it.value } == listOf("dev", "monomer_ext_test"))
        assertTrue("svv_all_columns" in relationColumns.sql)
        assertTrue("table_name AS relation_name" in relationColumns.sql)
        assertTrue("data_type AS native_type" in relationColumns.sql)
        assertTrue(
            relationColumns.parameters.map { it.value } ==
                listOf("dev", "monomer_ext_test", "orders"),
        )
        listOf(connectorChildren, schemaChildren, relationColumns).forEach { query ->
            assertTrue(query.sql.count { it == '?' } == query.parameters.size)
        }
    }

    @Test
    fun `catalog parser accepts show catalogs output and relation kinds`() {
        val catalogs = CatalogMetadataParser.catalogs(sequenceOf(CatalogQueryRow(mapOf("Catalog" to "hive"))))
        val relations = CatalogMetadataParser.relations(
            sequenceOf(CatalogQueryRow(mapOf("catalog_name" to "warehouse", "schema_name" to "analytics", "table_name" to "orders", "table_type" to "VIEW"))),
        )

        assertTrue(catalogs.single().name == "hive")
        assertTrue(relations.single().relationKind == cc.monomer.catalog.api.RelationKind.VIEW)
    }

    @Test
    fun `warehouse table types map to the matching relation kind`() {
        mapOf(
            "BASE TABLE" to cc.monomer.catalog.api.RelationKind.TABLE,
            "MANAGED" to cc.monomer.catalog.api.RelationKind.TABLE,
            "VIEW" to cc.monomer.catalog.api.RelationKind.VIEW,
            "MATERIALIZED VIEW" to cc.monomer.catalog.api.RelationKind.MATERIALIZED_VIEW,
            "MATERIALIZED_VIEW" to cc.monomer.catalog.api.RelationKind.MATERIALIZED_VIEW,
            "EXTERNAL" to cc.monomer.catalog.api.RelationKind.EXTERNAL,
            "EXTERNAL TABLE" to cc.monomer.catalog.api.RelationKind.EXTERNAL,
            "FOREIGN" to cc.monomer.catalog.api.RelationKind.FOREIGN,
        ).forEach { (tableType, expected) ->
            val relation = CatalogMetadataParser.relations(
                sequenceOf(CatalogQueryRow(mapOf("catalog_name" to "warehouse", "schema_name" to "analytics", "table_name" to "orders", "table_type" to tableType))),
            ).single()
            assertTrue(relation.relationKind == expected, tableType)
        }
    }

    @Test
    fun `relation plan separates metadata columns and supported relationships`() {
        val target = CatalogIngestTaskTarget.Relation("warehouse", "analytics", "orders", cc.monomer.catalog.api.RelationKind.TABLE)
        val postgres = CatalogSqlGenerator.relationPlan(ConnectorProductType.POSTGRESQL, emptyMap(), target.copy(catalogName = null))
        val redshift = CatalogSqlGenerator.relationPlan(ConnectorProductType.REDSHIFT, mapOf("database" to "warehouse"), target.copy(catalogName = null))
        val trino = CatalogSqlGenerator.relationPlan(ConnectorProductType.TRINO, emptyMap(), target)

        assertTrue(postgres.map { it.kind } == listOf(CatalogQueryKind.RELATION_METADATA, CatalogQueryKind.RELATION_COLUMNS, CatalogQueryKind.RELATIONSHIPS))
        assertTrue(redshift.map { it.kind } == listOf(CatalogQueryKind.RELATION_METADATA, CatalogQueryKind.RELATION_COLUMNS, CatalogQueryKind.RELATION_PRIMARY_KEYS, CatalogQueryKind.RELATION_FOREIGN_KEYS))
        assertTrue(trino.map { it.kind } == listOf(CatalogQueryKind.RELATION_METADATA, CatalogQueryKind.RELATION_COLUMNS))
        postgres.forEach { assertTrue(it.sql.count { character -> character == '?' } == it.parameters.size) }
        redshift.forEach { assertTrue(it.sql.count { character -> character == '?' } == it.parameters.size) }
        assertTrue("table_constraints" in postgres.last().sql)
        assertTrue(redshift.last().sql == "SHOW CONSTRAINTS FOREIGN KEYS FROM TABLE \"warehouse\".\"analytics\".\"orders\"")
        assertFalse(trino.any { "constraint" in it.sql.lowercase() })
    }

    @Test
    fun `catalog parser maps relation columns and relationship members`() {
        val columns = CatalogMetadataParser.columns(
            sequenceOf(
                CatalogQueryRow(
                    mapOf(
                        "catalog_name" to "warehouse", "schema_name" to "analytics", "relation_name" to "orders",
                        "column_name" to "id", "ordinal_position" to "1", "native_type" to "integer",
                        "is_nullable" to "NO", "default_expression" to null,
                    ),
                ),
            ),
        )
        val relationships = CatalogMetadataParser.relationships(
            sequenceOf(
                CatalogQueryRow(
                    mapOf(
                        "catalog_name" to "warehouse", "schema_name" to "analytics", "relation_name" to "orders",
                        "constraint_name" to "orders_pkey", "relationship_kind" to "PRIMARY_KEY", "member_ordinal" to "1",
                        "source_column" to "id",
                    ),
                ),
            ),
        )

        assertTrue(columns.single().nativeType == "integer")
        assertTrue(!columns.single().nullable)
        assertTrue(relationships.single().relationshipKind == cc.monomer.catalog.api.CatalogRelationshipKind.PRIMARY_KEY)
    }
    @Test
    fun `metadata canonicalization validates the database before stripping source and foreign key catalogs`() {
        listOf(Triple(ConnectorProductType.POSTGRESQL, "warehouse", null),
            Triple(ConnectorProductType.DUCKDB, "acme_cloud", "acme-cloud@1"),
            Triple(ConnectorProductType.DUCKDB, "acme_cloud", "acme-cloud@2"),
            Triple(ConnectorProductType.DUCKDB, "acme_cloud", "acme-cloud@3")).forEach { (type, database, dataset) ->
            val properties = if (type == ConnectorProductType.DUCKDB) mapOf("sample_dataset" to requireNotNull(dataset))
                else mapOf("database" to database)
            val nativeRow = CatalogQueryRow(mapOf(
                "catalog_name" to database, "schema_name" to "analytics", "relation_name" to "orders",
                "column_name" to "customer_id", "ordinal_position" to "1", "native_type" to "bigint",
                "constraint_name" to "orders_customer_fkey", "relationship_kind" to "FOREIGN_KEY",
                "member_ordinal" to "1", "source_column" to "customer_id", "referenced_catalog" to database,
                "referenced_schema" to "analytics", "referenced_relation" to "customers", "referenced_column" to "id",
            ))
            val rows = CatalogMetadataParser.canonicalRows(type, properties, CatalogQueryKind.RELATIONSHIPS, listOf(nativeRow))
            assertTrue(CatalogMetadataParser.schemas(rows.asSequence()).single().catalog == null)
            assertTrue(CatalogMetadataParser.relations(rows.asSequence()).single().catalog == null)
            assertTrue(CatalogMetadataParser.columns(rows.asSequence()).single().catalog == null)
            val relationship = CatalogMetadataParser.relationships(rows.asSequence()).single()
            assertTrue(relationship.catalog == null && relationship.referencedCatalog == null)
            assertTrue(relationship.referencedRelation == "customers")
            assertTrue(nativeRow.values["catalog_name"] == database)
            listOf(
                nativeRow.copy(values = nativeRow.values + ("catalog_name" to "foreign")),
                nativeRow.copy(values = nativeRow.values + ("catalog_name" to null)),
                nativeRow.copy(values = nativeRow.values + ("referenced_catalog" to "foreign")),
                nativeRow.copy(values = nativeRow.values + ("referenced_catalog" to null)),
            ).forEach { invalid ->
                assertFailsWith<IllegalArgumentException> {
                    CatalogMetadataParser.canonicalRows(type, properties, CatalogQueryKind.RELATIONSHIPS, listOf(invalid))
                }
            }
            assertTrue(CatalogMetadataParser.canonicalRows(ConnectorProductType.TRINO, emptyMap(), CatalogQueryKind.RELATIONSHIPS, listOf(nativeRow)) == listOf(nativeRow))
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogMetadataParser.canonicalRows(ConnectorProductType.DUCKDB, mapOf("sample_dataset" to "acme-cloud@4"),
                CatalogQueryKind.RELATION_METADATA, emptyList())
        }
    }

    @Test
    fun `redshift quotes every constraint target identifier without splitting dots`() {
        val target = CatalogIngestTaskTarget.Relation(null, "schema.\"name", "table.\"name", cc.monomer.catalog.api.RelationKind.TABLE)
        val queries = CatalogSqlGenerator.relationPlan(ConnectorProductType.REDSHIFT, mapOf("database" to "db.\"name"), target).takeLast(2)
        val qualifiedTable = "\"db.\"\"name\".\"schema.\"\"name\".\"table.\"\"name\""
        assertTrue(queries.map { it.sql } == listOf(
            "SHOW CONSTRAINTS PRIMARY KEYS FROM TABLE $qualifiedTable",
            "SHOW CONSTRAINTS FOREIGN KEYS FROM TABLE $qualifiedTable",
        ))
        assertTrue(queries.all { it.parameters.isEmpty() })
    }

    @Test
    fun `redshift native primary and foreign keys preserve composite sequence and names`() {
        val primaryRows = listOf(2, 1).map { ordinal -> CatalogQueryRow(mapOf(
            "database_name" to "dev", "schema_name" to "analytics", "table_name" to "orders",
            "pk_name" to "orders_pkey", "column_name" to "order_$ordinal", "key_seq" to ordinal.toString(),
        )) }
        val foreignRows = listOf(2, 1).map { ordinal -> CatalogQueryRow(mapOf(
            "fk_database_name" to "dev", "fk_schema_name" to "analytics", "fk_table_name" to "orders",
            "fk_name" to "orders_customer_fkey", "fk_column_name" to "customer_$ordinal", "key_seq" to ordinal.toString(),
            "pk_database_name" to "dev", "pk_schema_name" to "crm", "pk_table_name" to "customers",
            "pk_column_name" to "id_$ordinal", "pk_name" to "customers_pkey",
        )) }
        listOf(CatalogQueryKind.RELATION_PRIMARY_KEYS to primaryRows, CatalogQueryKind.RELATION_FOREIGN_KEYS to foreignRows).forEach { (kind, nativeRows) ->
            val canonical = CatalogMetadataParser.canonicalRows(ConnectorProductType.REDSHIFT, mapOf("database" to "dev"), kind, nativeRows)
            val members = CatalogMetadataParser.relationships(canonical.asSequence())
            assertTrue(members.map { it.memberOrdinal } == listOf(2, 1))
            assertTrue(members.all { it.catalog == null && it.referencedCatalog == null && it.schema == "analytics" && it.sourceRelation == "orders" })
            if (kind == CatalogQueryKind.RELATION_PRIMARY_KEYS) {
                assertTrue(members.all { it.constraintName == "orders_pkey" && it.relationshipKind == cc.monomer.catalog.api.CatalogRelationshipKind.PRIMARY_KEY })
                assertTrue(members.map { it.sourceColumn } == listOf("order_2", "order_1"))
            } else {
                assertTrue(members.all { it.constraintName == "orders_customer_fkey" && it.relationshipKind == cc.monomer.catalog.api.CatalogRelationshipKind.FOREIGN_KEY })
                assertTrue(members.map { it.sourceColumn } == listOf("customer_2", "customer_1"))
                assertTrue(members.map { it.referencedColumn } == listOf("id_2", "id_1"))
                assertTrue(members.all { it.referencedSchema == "crm" && it.referencedRelation == "customers" })
            }
            val databaseFields = if (kind == CatalogQueryKind.RELATION_PRIMARY_KEYS) listOf("database_name") else listOf("fk_database_name", "pk_database_name")
            databaseFields.forEach { field ->
                listOf("foreign", null).forEach { invalidDatabase ->
                    val invalidRow = nativeRows.first().copy(values = nativeRows.first().values + (field to invalidDatabase))
                    assertFailsWith<IllegalArgumentException> {
                        CatalogMetadataParser.canonicalRows(ConnectorProductType.REDSHIFT, mapOf("database" to "dev"), kind, listOf(invalidRow))
                    }
                }
            }
            assertTrue(CatalogMetadataParser.canonicalRows(ConnectorProductType.REDSHIFT, mapOf("database" to "dev"), kind, emptyList()).isEmpty())
        }
    }

    @Test
    fun `query targets enforce connector depth without physical catalog fallback`() {
        ConnectorProductType.entries.forEach { type ->
            val twoDepth = type == ConnectorProductType.POSTGRESQL || type == ConnectorProductType.REDSHIFT || type == ConnectorProductType.DUCKDB
            assertFailsWith<IllegalArgumentException> {
                CatalogSqlGenerator.forTarget(
                    type,
                    mapOf("database" to "warehouse"),
                    CatalogIngestTaskTarget.Schema(if (twoDepth) "warehouse" else null, "analytics"),
                    CatalogQueryKind.CHILD_LIST,
                )
            }
        }
    }

}
