package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardTablePivot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardTablePivotDefinitionTest {
    private val json = Json { explicitNulls = true }
    private val primaryQueries = mapOf("byRegionMonth" to semanticQuery(groupBys = "$REGION_GROUP_BY,$MONTH_GROUP_BY"))

    @Test
    fun `accepts a COLUMNS cross tab over a SEMANTIC query`() {
        val document = pivotDocument(pivot())
        val table = DashboardDefinitionDecoder.decode(document).widgets.getValue("table")

        assertEquals(
            DashboardTablePivot(
                rowFields = listOf(REGION),
                columnFields = listOf(MONTH),
                valueFields = listOf(REVENUE),
                totalsQueryNames = emptyList(),
            ),
            table.tablePivot,
        )
        assertEquals(listOf("byRegionMonth"), table.orderedQueryNames)
        assertEquals(document.getValue("widgets").jsonObject.getValue("table"), table.document)
    }

    @Test
    fun `accepts a ROWS scorecard with empty rows`() {
        val table = DashboardDefinitionDecoder.decode(
            pivotDocument(
                pivot(
                    rows = "[]",
                    values = """[{"field":"$REVENUE","header":"Revenue"},{"field":"$ORDER_COUNT","header":null}]""",
                    valuesAxis = "ROWS",
                ),
                queries = mapOf("byRegionMonth" to semanticQuery(groupBys = MONTH_GROUP_BY)),
            ),
        ).widgets.getValue("table")

        assertEquals(DashboardTablePivot(emptyList(), listOf(MONTH), listOf(REVENUE, ORDER_COUNT), emptyList()), table.tablePivot)
    }

    @Test
    fun `accepts all three totals placements and adds them after auxiliary queries in the widget query set`() {
        val definition = DashboardDefinitionDecoder.decode(
            pivotDocument(
                pivot(totals = """["byRegion","byMonth","grandTotal"]"""),
                queries = mapOf(
                    "byRegionMonth" to semanticQuery(groupBys = "$REGION_GROUP_BY,$MONTH_GROUP_BY"),
                    "daily" to semanticQuery(groupBys = MONTH_GROUP_BY, metricTimeRange = "null", filters = ""),
                    "byRegion" to semanticQuery(groupBys = REGION_GROUP_BY, filters = "$STATUS_FILTER,$REGION_FILTER"),
                    "byMonth" to semanticQuery(groupBys = MONTH_GROUP_BY, metrics = "\"order_count\",\"revenue\",\"average_order\""),
                    "grandTotal" to semanticQuery(groupBys = ""),
                ),
                module = module(queries = "\"daily\""),
            ),
        )
        val table = definition.widgets.getValue("table")

        assertEquals(listOf("byRegionMonth", "daily", "byRegion", "byMonth", "grandTotal"), table.orderedQueryNames)
        assertEquals(setOf("byRegionMonth", "daily", "byRegion", "byMonth", "grandTotal"), table.queryNames)
        assertEquals(listOf("byRegion", "byMonth", "grandTotal"), table.tablePivot?.totalsQueryNames)
    }

    @Test
    fun `a TABLE needs exactly one of columns or pivot`() {
        val both = """{"type":"TABLE","queryName":"byRegionMonth","columns":[{"field":"$REVENUE"}],"pivot":${pivot()}}"""
        val neither = """{"type":"TABLE","queryName":"byRegionMonth","rowHeight":40}"""

        listOf(both, neither).forEach { widget ->
            assertRejected("widget table: a TABLE needs exactly one of columns or pivot", document(widget))
        }
    }

    @Test
    fun `flat TABLE columns keep their current rules and no pivot`() {
        val widget = """{"type":"TABLE","queryName":"byRegionMonth","columns":[{"field":"unchecked.field","header":"Any","width":100}]}"""
        val table = DashboardDefinitionDecoder.decode(document(widget)).widgets.getValue("table")

        assertNull(table.tablePivot)
        assertEquals(listOf("byRegionMonth"), table.orderedQueryNames)
    }

    @Test
    fun `rejects pivot structures outside the closed contract`() {
        val nineRows = (1..9).joinToString(",", "[", "]") { """{"field":"$REGION"}""" }
        val fourColumns = (1..4).joinToString(",", "[", "]") { """{"field":"$MONTH"}""" }
        val twentyOneValues = (1..21).joinToString(",", "[", "]") { """{"field":"$REVENUE"}""" }
        mapOf(
            """{"rows":[],"columns":[{"field":"$MONTH"}],"values":[{"field":"$REVENUE"}]}""" to "widget table.pivot is missing required fields",
            pivot().replace("\"valuesAxis\"", "\"filters\":[],\"valuesAxis\"") to "widget table.pivot contains unknown fields: [filters]",
            "[]" to "widget table.pivot must be object",
            pivot(rows = nineRows) to "widget table.pivot.rows must contain at most 8 fields",
            pivot(columns = "[]") to "widget table.pivot.columns must contain 1 to 3 fields",
            pivot(columns = fourColumns) to "widget table.pivot.columns must contain 1 to 3 fields",
            pivot(values = "[]") to "widget table.pivot.values must contain 1 to 20 fields",
            pivot(values = twentyOneValues) to "widget table.pivot.values must contain 1 to 20 fields",
            pivot(columns = """[{"field":"$MONTH","width":120}]""") to "widget table.pivot.columns[0] contains unknown fields: [width]",
            pivot(rows = """[{"field":"$REGION","format":"bold"}]""") to "widget table.pivot.rows[0] contains unknown fields: [format]",
            pivot(rows = """[{"header":"Region"}]""") to "widget table.pivot.rows[0] is missing required fields",
            pivot(rows = """[{"field":""}]""") to "widget table.pivot.rows[0].field must not be blank",
            pivot(rows = """[{"field":5}]""") to "widget table.pivot.rows[0].field must be a string",
            pivot(rows = """[{"field":"$REGION","header":" "}]""") to "widget table.pivot.rows[0].header must not be blank",
            pivot(columns = """[{"field":"$MONTH","header":7}]""") to "widget table.pivot.columns[0].header must be a string",
            pivot(values = """[{"field":"$REVENUE","width":79}]""") to "widget table.pivot.values[0].width must be between 80 and 640",
            pivot(values = """[{"field":"$REVENUE","width":641}]""") to "widget table.pivot.values[0].width must be between 80 and 640",
            pivot(values = """[{"field":"$REVENUE","width":"120"}]""") to "widget table.pivot.values[0].width must be between 80 and 640",
            pivot(values = """[{"field":"$REVENUE","width":120.5}]""") to "widget table.pivot.values[0].width must be integer",
            pivot(valuesAxis = "BOTH") to "widget table.pivot.valuesAxis must be COLUMNS or ROWS",
            pivot(totals = "[]") to "widget table.pivot.totals must contain 1 to 3 query names; omit totals for a pivot without totals",
            pivot(totals = """["a","b","c","d"]""") to "widget table.pivot.totals must contain 1 to 3 query names",
            pivot(totals = """["byRegion","byRegion"]""") to "widget table.pivot.totals must contain unique query names",
            pivot(totals = """["by-region"]""") to "widget table.pivot.totals[0] is not a valid query name",
            pivot(totals = "null") to "widget table.pivot.totals must be array",
        ).forEach { (invalidPivot, message) ->
            assertRejected(message, pivotDocument(invalidPivot))
        }
    }

    @Test
    fun `pivot fields must be distinct across rows columns and values`() {
        assertRejected(
            "widget table.pivot.values[0].field repeats $REGION from widget table.pivot.rows[0].field; rows, columns, and values fields must be distinct",
            pivotDocument(pivot(values = """[{"field":"$REGION"}]""")),
        )
        assertRejected(
            "widget table.pivot.columns[1].field repeats $MONTH from widget table.pivot.columns[0].field",
            pivotDocument(pivot(columns = """[{"field":"$MONTH"},{"field":"$MONTH"}]""")),
        )
    }

    @Test
    fun `every pivot field must be a result column of the primary query`() {
        assertRejected(
            "widget table.pivot.values[0].field metric.margin is not a result column of query byRegionMonth",
            pivotDocument(pivot(values = """[{"field":"metric.margin"}]""")),
        )
    }

    @Test
    fun `SEMANTIC rows and columns must place exactly the group-by result keys`() {
        assertRejected(
            "widget table.pivot rows and columns must place every group-by result key of SEMANTIC query byRegionMonth; unplaced: [$REGION]",
            pivotDocument(pivot(rows = "[]")),
        )
        assertRejected(
            "widget table.pivot.rows[0].field $ORDER_COUNT must be a group-by result key of SEMANTIC query byRegionMonth",
            pivotDocument(pivot(rows = """[{"field":"$ORDER_COUNT"}]""")),
        )
    }

    @Test
    fun `SEMANTIC values must be metric result keys`() {
        assertRejected(
            "widget table.pivot.values[0].field $STATUS must be a metric result key of SEMANTIC query byRegionMonth",
            pivotDocument(
                pivot(values = """[{"field":"$STATUS"}]"""),
                queries = mapOf("byRegionMonth" to semanticQuery(groupBys = "$REGION_GROUP_BY,$MONTH_GROUP_BY,$STATUS_GROUP_BY")),
            ),
        )
    }

    @Test
    fun `SQL pivot checks only that fields are declared columns`() {
        val queries = mapOf("byRegionMonth" to SQL_QUERY)
        val table = DashboardDefinitionDecoder.decode(
            pivotDocument(
                pivot(rows = "[]", columns = """[{"field":"month"}]""", values = """[{"field":"revenue"},{"field":"region"}]"""),
                queries = queries,
            ),
        ).widgets.getValue("table")

        assertEquals(DashboardTablePivot(emptyList(), listOf("month"), listOf("revenue", "region"), emptyList()), table.tablePivot)
        assertRejected(
            "widget table.pivot.rows[0].field $REGION is not a result column of query byRegionMonth; result columns: [region, month, revenue]",
            pivotDocument(pivot(), queries = queries),
        )
    }

    @Test
    fun `totals must name distinct declared queries outside the primary and auxiliary queries`() {
        assertRejected(
            "widget table.pivot.totals[0] references unknown query missing",
            pivotDocument(pivot(totals = """["missing"]""")),
        )
        assertRejected(
            "widget table.pivot.totals[0] must not repeat the primary query byRegionMonth",
            pivotDocument(pivot(totals = """["byRegionMonth"]""")),
        )
        assertRejected(
            "widget table.pivot.totals[0] query byRegion must not also be a module.inputs.queries entry",
            pivotDocument(
                pivot(totals = """["byRegion"]"""),
                queries = primaryQueries + ("byRegion" to semanticQuery(groupBys = REGION_GROUP_BY)),
                module = module(queries = "\"byRegion\""),
            ),
        )
    }

    @Test
    fun `totals require SEMANTIC primary and totals queries`() {
        assertRejected(
            "widget table.pivot.totals requires a SEMANTIC primary query; query byRegionMonth is SQL",
            pivotDocument(
                pivot(rows = """[{"field":"region"}]""", columns = """[{"field":"month"}]""", values = """[{"field":"revenue"}]""", totals = """["byRegion"]"""),
                queries = mapOf("byRegionMonth" to SQL_QUERY, "byRegion" to semanticQuery(groupBys = REGION_GROUP_BY)),
            ),
        )
        assertRejected(
            "widget table.pivot.totals[0] query sqlTotal must be SEMANTIC; it is SQL",
            pivotDocument(pivot(totals = """["sqlTotal"]"""), queries = primaryQueries + ("sqlTotal" to SQL_QUERY)),
        )
    }

    @Test
    fun `totals share the connector time range and filters of the primary query and select every value`() {
        mapOf(
            semanticQuery(groupBys = REGION_GROUP_BY, connectorId = OTHER_CONNECTOR) to
                "widget table.pivot.totals[0] query byRegion must use the connector of primary query byRegionMonth",
            semanticQuery(groupBys = REGION_GROUP_BY, metrics = "\"order_count\"") to
                "widget table.pivot.totals[0] query byRegion must select every values metric; missing: [$REVENUE]",
            semanticQuery(groupBys = REGION_GROUP_BY, metricTimeRange = "null") to
                "widget table.pivot.totals[0] query byRegion metricTimeRange must equal the metricTimeRange of primary query byRegionMonth",
            semanticQuery(groupBys = REGION_GROUP_BY, metricTimeRange = """{"kind":"PARAMETER","name":"comparison"}""") to
                "widget table.pivot.totals[0] query byRegion metricTimeRange must equal the metricTimeRange of primary query byRegionMonth",
            semanticQuery(groupBys = REGION_GROUP_BY, filters = REGION_FILTER) to
                "widget table.pivot.totals[0] query byRegion filters must equal the filters of primary query byRegionMonth, in any order",
            semanticQuery(groupBys = REGION_GROUP_BY, filters = "$REGION_FILTER,${STATUS_FILTER.replace("cancelled", "refunded")}") to
                "widget table.pivot.totals[0] query byRegion filters must equal the filters of primary query byRegionMonth, in any order",
        ).forEach { (totalsQuery, message) ->
            assertRejected(message, pivotDocument(pivot(totals = """["byRegion"]"""), queries = primaryQueries + ("byRegion" to totalsQuery)))
        }
        val reordered = DashboardDefinitionDecoder.decode(
            pivotDocument(
                pivot(totals = """["byRegion"]"""),
                queries = primaryQueries + ("byRegion" to semanticQuery(groupBys = REGION_GROUP_BY, filters = "$STATUS_FILTER,$REGION_FILTER")),
            ),
        )
        assertEquals(listOf("byRegionMonth", "byRegion"), reordered.widgets.getValue("table").orderedQueryNames)
    }

    @Test
    fun `totals group-bys must name one distinct coarser placement`() {
        assertRejected(
            "widget table.pivot.totals[0] query byStatus group-by result keys must equal the rows fields [$REGION] (a total column), " +
                "the columns fields [$MONTH] (a total row), or be empty (the grand total); found [$STATUS]",
            pivotDocument(pivot(totals = """["byStatus"]"""), queries = primaryQueries + ("byStatus" to semanticQuery(groupBys = STATUS_GROUP_BY))),
        )
        assertRejected(
            "widget table.pivot.totals[0] query sameGrain groups by the same keys as primary query byRegionMonth, so it is not a total",
            pivotDocument(
                pivot(totals = """["sameGrain"]"""),
                queries = primaryQueries + ("sameGrain" to semanticQuery(groupBys = "$MONTH_GROUP_BY,$REGION_GROUP_BY")),
            ),
        )
        assertRejected(
            "widget table.pivot.totals[1] query regionAgain fills the same total placement as widget table.pivot.totals[0] query byRegion",
            pivotDocument(
                pivot(totals = """["byRegion","regionAgain"]"""),
                queries = primaryQueries + ("byRegion" to semanticQuery(groupBys = REGION_GROUP_BY)) +
                    ("regionAgain" to semanticQuery(groupBys = REGION_GROUP_BY, metrics = "\"revenue\"")),
            ),
        )
    }

    @Test
    fun `empty rows leave one total placement besides the primary grain`() {
        val monthOnly = mapOf("byMonth" to semanticQuery(groupBys = MONTH_GROUP_BY))
        val table = DashboardDefinitionDecoder.decode(
            pivotDocument(
                pivot(rows = "[]", valuesAxis = "ROWS", totals = """["grandTotal"]"""),
                primaryQueryName = "byMonth",
                queries = monthOnly + ("grandTotal" to semanticQuery(groupBys = "")),
            ),
        ).widgets.getValue("table")
        assertEquals(listOf("byMonth", "grandTotal"), table.orderedQueryNames)

        assertRejected(
            "widget table.pivot.totals[1] query totalAgain fills the same total placement as widget table.pivot.totals[0] query grandTotal; " +
                "each placement takes one query (with empty rows, the total column and the grand total are the same placement)",
            pivotDocument(
                pivot(rows = "[]", totals = """["grandTotal","totalAgain"]"""),
                primaryQueryName = "byMonth",
                queries = monthOnly + ("grandTotal" to semanticQuery(groupBys = "")) + ("totalAgain" to semanticQuery(groupBys = "")),
            ),
        )
        assertRejected(
            "widget table.pivot.totals[0] query monthAgain groups by the same keys as primary query byMonth, so it is not a total",
            pivotDocument(
                pivot(rows = "[]", totals = """["monthAgain"]"""),
                primaryQueryName = "byMonth",
                queries = monthOnly + ("monthAgain" to semanticQuery(groupBys = MONTH_GROUP_BY)),
            ),
        )
    }

    private fun assertRejected(expectedMessage: String, document: JsonObject) {
        val error = assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }
        assertTrue(error.message.orEmpty().startsWith(expectedMessage), "expected <$expectedMessage> but was <${error.message}>")
    }

    private fun pivot(
        rows: String = """[{"field":"$REGION","header":"Region","width":160}]""",
        columns: String = """[{"field":"$MONTH","header":"Month"}]""",
        values: String = """[{"field":"$REVENUE","header":"Revenue","width":120}]""",
        valuesAxis: String = "COLUMNS",
        totals: String? = null,
    ): String = """{"rows":$rows,"columns":$columns,"values":$values,"valuesAxis":"$valuesAxis"${totals?.let { ",\"totals\":$it" } ?: ""}}"""

    private fun module(queries: String): String =
        """{"inputs":{"queries":[$queries],"parameters":[],"variables":[]},"source":"export default defineTable({ render() { return {}; } });"}"""

    private fun pivotDocument(
        pivot: String,
        primaryQueryName: String = "byRegionMonth",
        queries: Map<String, String> = primaryQueries,
        module: String? = null,
    ): JsonObject = document(
        """{"type":"TABLE","title":"Revenue","queryName":"$primaryQueryName","pivot":$pivot${module?.let { ",\"module\":$it" } ?: ""}}""",
        queries,
    )

    private fun document(widget: String, queries: Map<String, String> = primaryQueries): JsonObject = json.parseToJsonElement(
        """{
          "schemaVersion":1,"meta":{"title":"Pivot"},
          "parameters":{"period":{"type":"DATE_RANGE","nullable":false},"comparison":{"type":"DATE_RANGE","nullable":false}},
          "variables":{},
          "queries":{${queries.entries.joinToString(",") { (name, query) -> "\"$name\":$query" }}},
          "widgets":{"table":$widget},
          "layout":{"sections":[{"id":"main","height":{"mode":"SCALED","scale":1.0},"root":{"type":"WIDGET","id":"tableItem","widgetId":"table"}}]}
        }""",
    ).jsonObject

    private fun semanticQuery(
        groupBys: String,
        metrics: String = "\"revenue\",\"order_count\"",
        metricTimeRange: String = """{"kind":"PARAMETER","name":"period"}""",
        filters: String = "$REGION_FILTER,$STATUS_FILTER",
        connectorId: String = CONNECTOR,
    ): String =
        """{"type":"SEMANTIC","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":[$metrics],"groupBys":[$groupBys],""" +
            """"metricTimeRange":$metricTimeRange,"filters":[$filters],"orderBy":[],"rowLimit":100}}"""

    private companion object {
        const val CONNECTOR = "0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de"
        const val OTHER_CONNECTOR = "0195f3b2-5c84-7a44-8cc4-3e9bf4fd10df"
        const val REGION = "dimension.orders.region"
        const val STATUS = "dimension.orders.status"
        const val MONTH = "metric_time.month"
        const val REVENUE = "metric.revenue"
        const val ORDER_COUNT = "metric.order_count"
        const val REGION_GROUP_BY = """{"kind":"DIMENSION","semanticModelName":"orders","name":"region"}"""
        const val STATUS_GROUP_BY = """{"kind":"DIMENSION","semanticModelName":"orders","name":"status"}"""
        const val MONTH_GROUP_BY = """{"kind":"METRIC_TIME","granularity":"MONTH"}"""
        const val REGION_FILTER =
            """{"kind":"TEXT","dimension":{"semanticModelName":"orders","name":"region"},"operator":"IN","value":["East","West"]}"""
        const val STATUS_FILTER =
            """{"kind":"TEXT","dimension":{"semanticModelName":"orders","name":"status"},"operator":"NOT_EQUALS","value":"cancelled"}"""
        const val SQL_QUERY =
            """{"type":"SQL","connectorId":"$CONNECTOR","sql":"SELECT region, month, revenue FROM sales","bindings":{},"columns":["region","month","revenue"],"rowLimit":100}"""
    }

}
