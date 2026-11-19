@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.application

import cc.monomer.agent.api.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentInputConstraintTest {
    private val decoder = AgentWorkspaceContractDecoder()

    @Test
    fun `all five authoring paths retain every declared time grain and reject undeclared values at the exact field`() {
        val cases = listOf(
            "input.definition.dimensions[0].granularity" to model,
            "input.definition.timeGranularity" to metric(""""type":"simple","semanticModelName":"orders","measure":"revenue","timeGranularity":"microsecond""""),
            "input.definition.numerator.offsetToGrain" to metric(""""type":"ratio","numerator":{"metricName":"revenue","offsetToGrain":"microsecond"},"denominator":{"metricName":"count"}"""),
            "input.definition.grainToDate" to metric(""""type":"cumulative","inputMetric":{"metricName":"revenue"},"grainToDate":"microsecond""""),
            "input.definition.filters[0].dimension.granularity" to metric(""""type":"simple","semanticModelName":"orders","measure":"revenue","filters":[{"dimension":{"semanticModelName":"orders","name":"ordered_at","granularity":"microsecond"},"operator":"is_not_null","values":[]}]"""),
        )
        for ((index, entry) in cases.withIndex()) {
            val (field, body) = entry
            for (granularity in AgentTimeGranularity.entries) {
                val input = decoder.create(body.replace("microsecond", granularity.name.lowercase())).value.input
                val retained = when (index) {
                    0 -> assertIs<AgentCreateInput.SemanticModel>(input).definition.dimensions.single().granularity
                    1 -> assertIs<AgentCreateInput.Metric>(input).definition.timeGranularity
                    2 -> assertIs<AgentMetricDefinition.Ratio>(assertIs<AgentCreateInput.Metric>(input).definition).numerator.offsetToGrain
                    3 -> assertIs<AgentMetricDefinition.Cumulative>(assertIs<AgentCreateInput.Metric>(input).definition).grainToDate
                    else -> assertIs<AgentMetricFilter.Predicate>(assertIs<AgentCreateInput.Metric>(input).definition.filters.single()).dimension.granularity
                }
                assertEquals(granularity, retained, field)
            }
            val failure = assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(body.replace("microsecond", "secret-grain")) }
            assertTrue(failure.violations.any { it.field == field && it.code == "INVALID_ENUM_VALUE" }, failure.violations.toString())
            assertFalse(failure.violations.toString().contains("secret-grain"))
        }
    }

    @Test
    fun `metric window remains calendar only and overflowing nested amount identifies its input`() {
        for (unit in listOf("day", "week", "month", "quarter", "year")) {
            decoder.create(metric(""""type":"cumulative","inputMetric":{"metricName":"revenue"},"window":"2 $unit""""))
        }
        failure("input.definition.window") {
            decoder.create(metric(""""type":"cumulative","inputMetric":{"metricName":"revenue"},"window":"2 hours""""))
        }
        failure("input.definition.numerator.offsetWindow", "1 to 2147483647") {
            decoder.create(metric(""""type":"ratio","numerator":{"metricName":"revenue","offsetWindow":"2147483648 days"},"denominator":{"metricName":"count"}"""))
        }
    }

    @Test
    fun `model constructor invariants return safe field and constraint details`() {
        val source = """{"connectorId":"$connector","schema":"analytics","relation":"orders"}"""
        failure("input.definition.sourceRelations", "distinct connectorId") { decoder.create(model.replace(source, "$source,$source")) }
        failure("input.definition.dimensions[0].label", "not be blank") {
            decoder.create(model.replace("\"expression\":\"ordered_at\"", "\"expression\":\"ordered_at\",\"label\":\"   \""))
        }
        failure("input.definition.sourceRelations[0].schema", "UTF-8 bytes") { decoder.create(model.replace("analytics", "한".repeat(400))) }
        failure("input.definition.sourceRelations[0].relation", "surrounding whitespace") { decoder.create(model.replace("\"relation\":\"orders\"", "\"relation\":\" orders \"")) }
        failure("input.definition.expression", "not be blank") {
            decoder.create(metric(""""type":"derived","expression":"   ","inputMetrics":[{"metricName":"revenue"}]"""))
        }
    }

    @Test
    fun `blank credential create and rotation identify only declared field names`() {
        val credential = """{"kind":"USERNAME_PASSWORD","username":"secret-user","password":"   "}"""
        failure("input.credential.password", "not be blank") {
            decoder.create("""{"path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"POSTGRESQL","displayName":"Warehouse","properties":{"host":"warehouse.example.com","port":5432,"database":"analytics"},"credential":$credential}}""")
        }
        failure("input.credential.password", "not be blank") {
            decoder.invoke("""{"path":"workspace/connectors/$connector","action":"rotate-credentials","input":{"kind":"CONNECTOR_CREDENTIAL_ROTATE","credential":$credential}}""")
        }
        failure("input.sql", "not be blank") {
            decoder.invoke("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"$connector","sql":"   "}}""")
        }
    }

    @Test
    fun `query relational constraints have wire coordinates instead of generic constructor failures`() {
        failure("input.query.groupBys", "at most 1") {
            decoder.invoke(query(groupBys = """[{"kind":"METRIC_TIME","granularity":"DAY"},{"kind":"METRIC_TIME","granularity":"HOUR"}]""", orderBy = "[]", range = "null", filters = "[]"))
        }
        failure("input.query.orderBy[0].target.metricName", "included in metrics") {
            decoder.invoke(query("[]", """[{"target":{"kind":"METRIC","metricName":"secret_metric"},"direction":"ASC"}]""", "null", "[]"))
        }
        val metricTarget = """"target":{"kind":"METRIC","metricName":"revenue"}"""
        failure("input.query.orderBy", "distinct") {
            decoder.invoke(query("[]", """[{$metricTarget,"direction":"ASC"},{$metricTarget,"direction":"DESC"}]""", "null", "[]"))
        }
        failure("input.query.metricTimeRange.end.value", "at or after") {
            decoder.invoke(query("[]", "[]", """{"start":{"kind":"DATE","value":"2026-09-02"},"end":{"kind":"DATE","value":"2026-09-01"}}""", "[]"))
        }
        failure("input.query.metricTimeRange.start.value", "ISO 8601") {
            decoder.invoke(query("[]", "[]", """{"start":{"kind":"DATE","value":"2026-02-30"},"end":{"kind":"DATE","value":"2026-09-01"}}""", "[]"))
        }
        failure("input.query.filters[0].values[0]", "ISO 8601") {
            decoder.invoke(query("[]", "[]", "null", """[{"kind":"DATE","dimension":{"semanticModelName":"orders","name":"ordered_at"},"operator":"EQUALS","values":["2026-02-30"]}]"""))
        }
    }

    private fun failure(field: String, message: String = "", action: () -> Unit) {
        val failure = assertFailsWith<AgentWorkspaceRequestDecodingException>(block = action)
        assertTrue(failure.violations.any { it.field == field && it.message.contains(message) }, failure.violations.toString())
        assertFalse(failure.violations.toString().contains("secret"))
    }

    private fun metric(fields: String) = """{"path":"workspace/metrics","input":{"kind":"METRIC_DEFINITION","definition":{"schemaVersion":3,"name":"revenue",$fields}}}"""
    private fun query(groupBys: String, orderBy: String, range: String, filters: String) = """{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connector","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":$groupBys,"metricTimeRange":$range,"filters":$filters,"orderBy":$orderBy,"rowLimit":10}}}"""

    private companion object {
        const val connector = "01942b04-6e00-7000-8000-000000000011"
        val model = """{"path":"workspace/semantic-models","input":{"kind":"SEMANTIC_MODEL_DEFINITION", "aggregateMetrics": [],"definition":{"schemaVersion":3,"name":"orders","sourceRelations":[{"connectorId":"$connector","schema":"analytics","relation":"orders"}],"entities":[{"name":"order","type":"primary","expression":"order_id"}],"dimensions":[{"isPartition":false,"name":"ordered_at","type":"time","granularity":"microsecond","expression":"ordered_at"}],"measures":[]}}}"""
    }
}
