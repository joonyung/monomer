package cc.monomer.execution.driver.trino

import cc.monomer.execution.driver.spi.MAXIMUM_RAW_SQL_BYTES
import io.trino.sql.parser.ParsingException
import io.trino.sql.parser.SqlParser
import io.trino.sql.tree.Node
import io.trino.sql.tree.Query
import java.nio.charset.StandardCharsets

internal class TrinoRawSqlValidator {
    fun validate(sql: String) {
        require(sql.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_RAW_SQL_BYTES) {
            "Trino Raw SQL exceeds the maximum UTF-8 byte length"
        }
        val statement = try {
            SqlParser().createStatement(sql)
        } catch (failure: ParsingException) {
            throw IllegalArgumentException("Trino Raw SQL could not be parsed", failure)
        }
        require(statement is Query) { "Trino Raw SQL must be a single Query" }
        var nodeCount = 0
        fun validateNode(node: Node, depth: Int) {
            require(depth <= MAXIMUM_AST_DEPTH) { "Trino Raw SQL exceeds the maximum AST depth" }
            require(++nodeCount <= MAXIMUM_AST_NODES) { "Trino Raw SQL exceeds the maximum AST node count" }
            node.children.forEach { validateNode(it, depth + 1) }
        }
        validateNode(statement, 0)
    }

    private companion object {
        const val MAXIMUM_AST_NODES = 10_000
        const val MAXIMUM_AST_DEPTH = 256
    }
}
