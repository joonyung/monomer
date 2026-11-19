@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardBindValue
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardQueryType
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQueryCodec
import cc.monomer.dashboard.domain.DashboardSqlTemplate
import cc.monomer.dashboard.domain.DashboardValueType
import cc.monomer.dashboard.domain.DashboardValueRefKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

internal fun sqlDashboardDefinition(
    sql: String = "SELECT {{a}} AS value, {{b}} AS other",
    bindings: String = """{"a":{"kind":"VARIABLE","name":"a"},"b":{"kind":"VARIABLE","name":"b"}}""",
    variables: String = """{"a":{"type":"TEXT","nullable":false,"initial":"first"},"b":{"type":"TEXT","nullable":false,"initial":"second"}}""",
    rowLimit: Int = 100,
    columns: String = """["value","other"]""",
): JsonObject = Json.parseToJsonElement("""{
    "schemaVersion":1,"meta":{"title":"SQL dashboard"},"parameters":{},"variables":$variables,
    "queries":{"orders":{"type":"SQL","connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de",
        "sql":${JsonPrimitive(sql)},"bindings":$bindings,"columns":$columns,"rowLimit":$rowLimit}},
    "widgets":{"chart":{"type":"CHART","queryNames":["orders"],"module":{
        "inputs":{"parameters":[],"variables":[]},"source":"export default defineChart({ render() { return {}; } });"}}},
    "layout":{"sections":[{"id":"main","height":{"mode":"SCALED","scale":1.0},"root":{"type":"WIDGET","id":"chartNode","widgetId":"chart"}}]}
}""").jsonObject

class DashboardSqlQueryTest {
    @Test
    fun `template compiler preserves left to right repetition and exact reserved pattern`() {
        val parsed = DashboardSqlTemplate.compile("{{{a}}} {{\tb \t}} {{a}} {{ a\n}} '{{b}}' --{{a}}", setOf("a", "b"), "queries.orders.sql")
        assertEquals("{?} ? ? {{ a\n}} '?' --?", parsed.executableSql)
        assertEquals(listOf("a", "b", "a", "b", "a"), parsed.aliasesInAppearanceOrder)
        assertEquals("{{{a}}} {{\tb \t}} {{a}} {{ a\n}} '{{b}}' --{{a}}", parsed.source)
        assertFailsWith<IllegalArgumentException> { DashboardSqlTemplate.compile("{{a}}", emptySet(), "queries.orders.sql") }
        assertFailsWith<IllegalArgumentException> { DashboardSqlTemplate.compile("{{a}}", setOf("a", "unused"), "queries.orders.sql") }
        assertFailsWith<IllegalArgumentException> { DashboardSqlTemplate.compile("{{__proto__}}", setOf("__proto__"), "queries.orders.sql") }
    }

    @Test
    fun `named rendering preserves spans with repeated adjacent quoted and commented markers`() {
        val source = "SELECT {{a}}tail, '{{b}}', '?' -- {{a}}\n/*{{b}}*/{{a}}"
        val template = DashboardSqlTemplate.compile(source, setOf("a", "b"), "sql")
        assertEquals("SELECT ?tail, '?', '?' -- ?\n/*?*/?", template.executableSql)
        assertEquals(listOf("SELECT ", "tail, '", "', '?' -- ", "\n/*", "*/", ""), template.sourceFragments)
        assertEquals(listOf("monomer_p1", "monomer_p2", "monomer_p3", "monomer_p4", "monomer_p5"), template.namedParameterNames())
        assertEquals("SELECT  :monomer_p1 tail, ' :monomer_p2 ', '?' --  :monomer_p3 \n/* :monomer_p4 */ :monomer_p5 ", template.namedSql())
        assertEquals(template.namedSql(), DashboardSqlTemplate.compile(source, setOf("b", "a"), "sql").namedSql())
        assertEquals(template.namedSql().toByteArray(Charsets.UTF_8).size.toLong(), template.namedSqlUtf8ByteLength())
    }

    @Test
    fun `generated prefix extends until absent everywhere case insensitively`() {
        val template = DashboardSqlTemplate.compile("SELECT :MONOMER_P1, '{{a}}' -- monomer_p_ monomer_P__\n{{a}}", setOf("a"), "sql")
        assertEquals(listOf("monomer_p___1", "monomer_p___2"), template.namedParameterNames())
        assertEquals("SELECT :MONOMER_P1, ' :monomer_p___1 ' -- monomer_p_ monomer_P__\n :monomer_p___2 ", template.namedSql())
        val unbound = DashboardSqlTemplate.compile("SELECT '?' -- monomer_p", emptySet(), "sql")
        assertEquals(unbound.executableSql, unbound.namedSql())
        assertEquals(emptyList(), unbound.namedParameterNames())
        val unicode = DashboardSqlTemplate.compile("SELECT 'é😀', {{a}} -- monomer_p____", setOf("a"), "sql")
        assertEquals(unicode.namedSql().toByteArray(Charsets.UTF_8).size.toLong(), unicode.namedSqlUtf8ByteLength())
    }

    @Test
    fun `quoted slot placements remain distinct in named rendered SQL`() {
        val left = DashboardSqlTemplate.compile("SELECT '{{a}}', '?'", setOf("a"), "sql")
        val right = DashboardSqlTemplate.compile("SELECT '?', '{{a}}'", setOf("a"), "sql")
        assertEquals(left.executableSql, right.executableSql)
        assertNotEquals(left.namedSql(), right.namedSql())
    }

    @Test
    fun `decoder closes query union and validates scalar references and column normal form`() {
        val valid = sqlDashboardDefinition()
        assertIs<DashboardNamedQuery.Sql>(DashboardDefinitionDecoder.decode(valid).query("orders"))
        val query = valid.getValue("queries").jsonObject.getValue("orders").jsonObject
        for (change in listOf(query - "type", query + ("query" to JsonObject(emptyMap())), query - "rowLimit")) {
            assertFailsWith<InvalidDashboardDefinitionException> { DashboardDefinitionDecoder.decode(JsonObject(valid + ("queries" to JsonObject(mapOf("orders" to JsonObject(change)))))) }
        }
        for (columns in listOf("[]", "[\"same\",\"same\"]", "[\" padded\"]", "[\"line\\n\"]", "[\"e\\u0301\"]", JsonPrimitive("x".repeat(505)).toString().let { "[$it]" })) {
            assertFailsWith<InvalidDashboardDefinitionException> { DashboardDefinitionDecoder.decode(sqlDashboardDefinition(columns = columns)) }
        }
        assertFailsWith<InvalidDashboardDefinitionException> { DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "é".repeat(131073), bindings = "{}")) }
        assertFailsWith<InvalidDashboardDefinitionException> { DashboardDefinitionDecoder.decode(sqlDashboardDefinition(bindings = """{"a":{"kind":"LITERAL","name":"a"},"b":{"kind":"VARIABLE","name":"b"}}""")) }
        assertFailsWith<InvalidDashboardDefinitionException> { DashboardDefinitionDecoder.decode(sqlDashboardDefinition(variables = """{"a":{"type":"TEXT_SET","nullable":false,"initial":["a"]},"b":{"type":"TEXT","nullable":false,"initial":"b"}}""")) }
        assertFailsWith<InvalidDashboardDefinitionException> { DashboardDefinitionDecoder.decode(sqlDashboardDefinition(bindings = """{"a":{"kind":"VARIABLE","name":"a","endpoint":"start"},"b":{"kind":"VARIABLE","name":"b"}}""")) }
    }

    @Test
    fun `ordered typed values are the SQL reuse metadata and injection stays bound`() {
        val first = DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "SELECT {{b}}, {{a}}, {{b}}"))
        val injection = "x' OR '1'='1"
        val values = mapOf("a" to JsonPrimitive(injection), "b" to JsonPrimitive("second"))
        val resolved = assertIs<DashboardResolvedQuery.Sql>(DashboardQueryValueResolver.resolve(first.query("orders"), emptyMap(), values, first.parameters, first.variables))
        assertEquals(listOf(DashboardBindValue.Text("second"), DashboardBindValue.Text(injection), DashboardBindValue.Text("second")), resolved.bindings)
        assertEquals("SELECT ?, ?, ?", (first.query("orders") as DashboardNamedQuery.Sql).template.executableSql)
        val reversed = DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "SELECT {{b}}, {{a}}, {{b}}", bindings = """{"b":{"kind":"VARIABLE","name":"b"},"a":{"kind":"VARIABLE","name":"a"}}"""))
        assertEquals(resolved, DashboardQueryValueResolver.resolve(reversed.query("orders"), emptyMap(), values, reversed.parameters, reversed.variables))
        val swapped = DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "SELECT {{a}}, {{b}}, {{a}}"))
        assertNotEquals(resolved, DashboardQueryValueResolver.resolve(swapped.query("orders"), emptyMap(), values, swapped.parameters, swapped.variables))
        assertNotEquals(DashboardResolvedQueryCodec.encode(resolved), DashboardResolvedQueryCodec.encode(resolved.copy(rowLimit = 10)))
        assertNotEquals(DashboardResolvedQueryCodec.encode(DashboardResolvedQuery.Sql(listOf(DashboardBindValue.Text("1")), 100)),
            DashboardResolvedQueryCodec.encode(DashboardResolvedQuery.Sql(listOf(DashboardBindValue.Number("1")), 100)))
        assertEquals(false, DashboardQueryValueResolver.referencesParameter(first.query("orders"), "a"))
    }

    @Test
    fun `typed null and range endpoint keep declared types while timestamp normalizes to instant`() {
        val definition = DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "SELECT {{a}}, {{b}}", bindings = """{"a":{"kind":"VARIABLE","name":"a","endpoint":"end"},"b":{"kind":"VARIABLE","name":"b"}}""", variables = """{"a":{"type":"DATE_RANGE","nullable":true,"initial":null},"b":{"type":"TIMESTAMP","nullable":false,"initial":"2026-10-06T09:00:00.123456+09:00"}}"""))
        val resolved = assertIs<DashboardResolvedQuery.Sql>(DashboardQueryValueResolver.resolve(definition.query("orders"), emptyMap(), definition.variables.mapValues { it.value.initial }, definition.parameters, definition.variables))
        assertEquals(listOf(DashboardBindValue.Null(DashboardValueType.DATE), DashboardBindValue.Timestamp(Instant.parse("2026-10-06T00:00:00.123456Z"))), resolved.bindings)
        assertEquals(resolved, DashboardResolvedQueryCodec.decode(DashboardQueryType.SQL, DashboardResolvedQueryCodec.encode(resolved)))
        val typed = DashboardResolvedQuery.Sql(listOf(DashboardBindValue.Text(""), DashboardBindValue.Number("12345678901234567890.123456789012345678"), DashboardBindValue.Boolean(true), DashboardBindValue.Date(LocalDate.parse("2026-10-06")), DashboardBindValue.Timestamp(Instant.parse("2026-10-06T00:00:00Z"))) + DashboardBindValue.scalarTypes.map(DashboardBindValue::Null), 100)
        assertEquals(typed, DashboardResolvedQueryCodec.decode(DashboardQueryType.SQL, DashboardResolvedQueryCodec.encode(typed)))
        assertFailsWith<IllegalArgumentException> { DashboardBindValue.Timestamp(Instant.parse("2026-10-06T00:00:00.123456789Z")) }
        assertFailsWith<IllegalArgumentException> { DashboardBindValue.Number("1.000") }
        assertFailsWith<IllegalArgumentException> { DashboardBindValue.Null(DashboardValueType.TEXT_SET) }
    }

    @Test
    fun `Semantic resolved encoding preserves its JSON shape`() {
        val payload = Json.parseToJsonElement("""{"rowLimit":10,"filters":[],"metrics":["count"],"groupBys":[],"metricTimeRange":null,"orderBy":[],"schemaVersion":4}""").jsonObject
        assertEquals(payload, DashboardResolvedQueryCodec.encode(DashboardResolvedQuery.Semantic(payload)))
    }
}
