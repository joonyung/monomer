package cc.monomer.dashboard.domain

import cc.monomer.connector.api.ConnectorId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate

enum class DashboardQueryType { SEMANTIC, SQL }

sealed interface DashboardNamedQuery {
    val name: String
    val connectorId: ConnectorId
    val type: DashboardQueryType
    fun resultColumnNames(): List<String>

    data class Semantic(
        override val name: String,
        override val connectorId: ConnectorId,
        val query: JsonObject,
    ) : DashboardNamedQuery {
        override val type = DashboardQueryType.SEMANTIC
        override fun resultColumnNames(): List<String> = cc.monomer.semantic.api.SemanticResultColumns.keys(
            DashboardJson.arrayNode(query.getValue("metrics"), "$name.metrics"),
            DashboardJson.arrayNode(query.getValue("groupBys"), "$name.groupBys"),
        ).map { it.value }

        fun groupByResultKeys(): List<String> = cc.monomer.semantic.api.SemanticResultColumns.keys(
            JsonArray(emptyList()),
            DashboardJson.arrayNode(query.getValue("groupBys"), "$name.groupBys"),
        ).map { it.value }

        fun metricResultKeys(): List<String> = cc.monomer.semantic.api.SemanticResultColumns.keys(
            DashboardJson.arrayNode(query.getValue("metrics"), "$name.metrics"),
            JsonArray(emptyList()),
        ).map { it.value }
    }

    data class Sql(
        override val name: String,
        override val connectorId: ConnectorId,
        val template: DashboardSqlTemplate,
        val bindings: Map<String, DashboardValueRef>,
        val columns: List<String>,
        val rowLimit: Int,
    ) : DashboardNamedQuery {
        override val type = DashboardQueryType.SQL
        override fun resultColumnNames(): List<String> = columns
    }
}

enum class DashboardValueRefKind { PARAMETER, VARIABLE }
enum class DashboardRangeEndpoint { START, END }

data class DashboardValueRef(
    val kind: DashboardValueRefKind,
    val name: String,
    val endpoint: DashboardRangeEndpoint?,
)

@ConsistentCopyVisibility
data class DashboardSqlTemplate private constructor(
    val source: String,
    val sourceFragments: List<String>,
    val aliasesInAppearanceOrder: List<String>,
) {
    val executableSql: String get() = sourceFragments.joinToString("?")

    fun namedParameterNames(): List<String> {
        val prefix = "monomer_p" + "_".repeat(namedPrefixLength() - "monomer_p".length)
        return aliasesInAppearanceOrder.indices.map { "$prefix${it + 1}" }
    }

    fun namedSqlUtf8ByteLength(): Long = sourceFragments.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } +
        aliasesInAppearanceOrder.size.toLong() * (namedPrefixLength() + 3) +
        (1..aliasesInAppearanceOrder.size).sumOf { appearanceIndex ->
            var remaining = appearanceIndex
            var digits = 1L
            while (remaining >= 10) { remaining /= 10; digits++ }
            digits
        }

    private fun namedPrefixLength(): Int =
        (generatedPrefix.findAll(source).maxOfOrNull { it.range.last - it.range.first + 1 } ?: ("monomer_p".length - 1)) + 1

    fun namedSql(): String {
        val names = namedParameterNames()
        return buildString {
            sourceFragments.forEachIndexed { index, fragment ->
                append(fragment)
                if (index < names.size) append(" :${names[index]} ")
            }
        }
    }

    companion object {
        fun compile(source: String, declaredAliases: Set<String>, context: String): DashboardSqlTemplate {
            require(source.isNotBlank()) { "$context must not be blank" }
            require(source.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "$context must not exceed 256 KiB in UTF-8 bytes" }
            require('\u0000' !in source) { "$context must not contain NUL" }
            require(declaredAliases.all(DashboardDefinition::isDashboardName)) { "$context bindings contain an invalid alias" }
            val aliases = mutableListOf<String>()
            val fragments = mutableListOf<String>()
            var offset = 0
            placeholder.findAll(source).forEach { match ->
                val alias = match.groupValues[1]
                require(alias in declaredAliases) { "$context uses undeclared binding alias $alias" }
                aliases += alias
                fragments += source.substring(offset, match.range.first)
                offset = match.range.last + 1
            }
            require(aliases.toSet() == declaredAliases) { "$context bindings contain unused aliases: ${declaredAliases - aliases.toSet()}" }
            fragments += source.substring(offset)
            return DashboardSqlTemplate(source, fragments.toList(), aliases.toList())
        }
        private val generatedPrefix = Regex("monomer_p_*", RegexOption.IGNORE_CASE)
        private val placeholder = Regex("\\{\\{[ \t]*([A-Za-z_][A-Za-z0-9_]*)[ \t]*\\}\\}")
    }
}

sealed interface DashboardBindValue {
    val type: DashboardValueType
    data class Text(val value: String) : DashboardBindValue { override val type = DashboardValueType.TEXT }
    data class Number(val value: String) : DashboardBindValue {
        override val type = DashboardValueType.NUMBER
        init { DashboardJson.decimal(kotlinx.serialization.json.JsonPrimitive(value), "SQL NUMBER binding") }
    }
    data class Boolean(val value: kotlin.Boolean) : DashboardBindValue { override val type = DashboardValueType.BOOLEAN }
    data class Date(val value: LocalDate) : DashboardBindValue { override val type = DashboardValueType.DATE }
    data class Timestamp(val value: Instant) : DashboardBindValue {
        override val type = DashboardValueType.TIMESTAMP
        init { require(value.nano % 1000 == 0) { "SQL TIMESTAMP binding must have at most microsecond precision" } }
    }
    data class Null(override val type: DashboardValueType) : DashboardBindValue {
        init { require(type in scalarTypes) { "SQL NULL binding must carry a scalar declared type" } }
    }
    companion object {
        val scalarTypes = setOf(DashboardValueType.TEXT, DashboardValueType.NUMBER, DashboardValueType.BOOLEAN, DashboardValueType.DATE, DashboardValueType.TIMESTAMP)
    }
}

sealed interface DashboardResolvedQuery {
    val type: DashboardQueryType
    val rowLimit: Int
    val bindings: List<DashboardBindValue>

    data class Semantic(val query: JsonObject) : DashboardResolvedQuery {
        override val type = DashboardQueryType.SEMANTIC
        override val rowLimit: Int get() = DashboardJson.integer(query.getValue("rowLimit"), "query.rowLimit")
        override val bindings: List<DashboardBindValue> get() = emptyList()
    }
    data class Sql(override val bindings: List<DashboardBindValue>, override val rowLimit: Int) : DashboardResolvedQuery {
        override val type = DashboardQueryType.SQL
        init { require(rowLimit in 1..100_000) }
    }
}
