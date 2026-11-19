package cc.monomer.execution.driver.spi

import kotlin.test.Test
import kotlin.test.assertFailsWith

class QueryParameterNamesTest {
    @Test
    fun `Databricks requires complete unique ASCII names without reading SQL`() {
        requireQueryParameterNames(ConnectorType.DATABRICKS, emptyList())
        requireQueryParameterNames(ConnectorType.DATABRICKS, listOf(QueryParameter.Text("x", "_p1"), QueryParameter.Text("x", "p2")))
        for (name in listOf("", "1p", ":p", "p-1", "p ", "é", "p\u0000")) {
            assertFailsWith<IllegalArgumentException> { requireQueryParameterNames(ConnectorType.DATABRICKS, listOf(QueryParameter.Text("x", name))) }
        }
        for (names in listOf(listOf("p1", "p1"), listOf("p1", "P1"), listOf("p1", ""))) {
            assertFailsWith<IllegalArgumentException> { requireQueryParameterNames(ConnectorType.DATABRICKS, names.map { QueryParameter.Text("x", it) }) }
        }
    }

    @Test
    fun `every positional engine rejects names and accepts existing unnamed values`() {
        ConnectorType.entries.filter { it != ConnectorType.DATABRICKS }.forEach { engine ->
            requireQueryParameterNames(engine, listOf(QueryParameter.Text("x", "")))
            assertFailsWith<IllegalArgumentException> { requireQueryParameterNames(engine, listOf(QueryParameter.Text("x", "p1"))) }
        }
    }
}
