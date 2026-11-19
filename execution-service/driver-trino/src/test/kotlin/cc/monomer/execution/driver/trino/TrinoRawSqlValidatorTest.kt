package cc.monomer.execution.driver.trino

import kotlin.test.Test
import kotlin.test.assertFailsWith

class TrinoRawSqlValidatorTest {
    @Test
    fun `accepts read queries independently of relation discovery`() {
        listOf(
            "SELECT 1",
            "SELECT * FROM other_database.private_schema.hidden_table",
            "-- leading comment\n/* block ; comment */ SELECT ';' AS \"semi;colon\"",
            "SELECT 'it''s;valid' AS value",
            "SELECT 1 AS \"MixedCase\"",
            "WITH RECURSIVE x AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM x WHERE n < 3) SELECT * FROM x",
            "WITH source AS (SELECT 1 AS n) SELECT n FROM source",
            "SELECT row_number() OVER (ORDER BY n) FROM UNNEST(ARRAY[1, 2]) t(n)",
            "SELECT array_agg(n) FROM UNNEST(ARRAY[1, 2]) t(n)",
            "SELECT ?",
            "VALUES (1)",
            "TABLE orders",
            "SELECT * FROM (VALUES (1), (2)) AS t(n)",
            "SELECT * FROM a JOIN b ON a.id = b.id WHERE EXISTS (SELECT 1 FROM c)",
            "SELECT (SELECT count(*) FROM unseen) AS n",
            "SELECT * FROM orders TABLESAMPLE SYSTEM (10)",
            "SELECT * FROM UNNEST(ARRAY[1,2])",
        ).forEach { sql ->
            try {
                TrinoRawSqlValidator().validate(sql)
            } catch (failure: Throwable) {
                throw IllegalArgumentException("Trino corpus case failed: $sql", failure)
            }
        }
    }

    @Test
    fun `retains native trailing semicolon rejection`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoRawSqlValidator().validate("SELECT 1;")
        }
    }

    @Test
    fun `rejects mutations multiple statements and nested locking`() {
        listOf(
            "SELECT 1; SELECT 2",
            "DELETE FROM orders",
            "CREATE TABLE x (id INT)",
            "SELECT * INTO copied FROM orders",
            "SELECT * FROM orders FOR UPDATE",
            "WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x",
            "SELECT (SELECT id FROM orders FOR UPDATE)",
            "SELECT * FROM (SELECT * INTO copied FROM orders) x",
        ).forEach { sql ->
            assertFailsWith<Exception>(sql) { TrinoRawSqlValidator().validate(sql) }
        }
    }
}
