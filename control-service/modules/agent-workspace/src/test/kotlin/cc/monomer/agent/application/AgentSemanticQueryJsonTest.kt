package cc.monomer.agent.application

import cc.monomer.agent.api.AgentExploreRunRequest
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentSemanticFilter
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentSemanticQueryJsonTest {
    private val mapper = ObjectMapper()
    private val decoder = AgentWorkspaceContractDecoder()

    @Test
    fun `history query round trip retains every typed field and precise decimal strings`() {
        val dimension = """{"semanticModelName":"orders","name":"region"}"""
        val groupBy = """{"kind":"DIMENSION","semanticModelName":"orders","name":"ordered_at","granularity":"DAY"}"""
        val queryJson = """{
            "schemaVersion":4,"metrics":["revenue"],
            "groupBys":[$groupBy,{"kind":"ENTITY","semanticModelName":"orders","name":"customer"},{"kind":"METRIC_TIME","granularity":"MONTH"}],
            "metricTimeRange":{"start":{"kind":"DATE","value":"2026-01-01"},"end":{"kind":"DATE","value":"2026-09-06"}},
            "filters":[
                {"kind":"TEXT","dimension":$dimension,"operator":"IN","values":["서울🙂","US"]},
                {"kind":"NUMBER","dimension":$dimension,"operator":"IN","values":["12345678901234567890.123456789012345678","-0.000000000000000001"]},
                {"kind":"BOOLEAN","dimension":$dimension,"operator":"EQUALS","values":[true]},
                {"kind":"DATE","dimension":$dimension,"operator":"GREATER_THAN","values":["2026-01-01"]},
                {"kind":"TIMESTAMP","dimension":$dimension,"operator":"LESS_THAN","values":["2026-09-06T00:00:00.123456Z"]}
            ],
            "orderBy":[{"target":{"kind":"METRIC","metricName":"revenue"},"direction":"DESC"},
                {"target":{"kind":"GROUP_BY","groupBy":$groupBy},"direction":"ASC"}],"rowLimit":123
        }"""
        val query = decode(queryJson)
        assertEquals(BigDecimal("12345678901234567890.123456789012345678"), assertIs<AgentSemanticFilter.Number>(query.filters[1]).values[0])
        assertEquals(mapper.readTree(queryJson), mapper.valueToTree(AgentSemanticQueryJson.query(query)))
        assertEquals(query, decode(mapper.writeValueAsString(AgentSemanticQueryJson.query(query))))
    }

    @Test
    fun `history query preserves null and timestamp time ranges`() {
        listOf("null", """{"start":{"kind":"TIMESTAMP","value":"2026-01-01T00:00:00.123456789Z"},"end":{"kind":"TIMESTAMP","value":"2026-09-06T00:00:00Z"}}""").forEach { range ->
            val json = """{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":$range,"filters":[],"orderBy":[],"rowLimit":100}"""
            assertEquals(mapper.readTree(json), mapper.valueToTree(AgentSemanticQueryJson.query(decode(json))))
        }
    }

    @Test
    fun `history query retains every time granularity in the owning query schema`() {
        listOf("NANOSECOND", "MICROSECOND", "MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR").forEach { granularity ->
            val json = """{"schemaVersion":4,"metrics":["revenue"],
                "groupBys":[{"kind":"DIMENSION","semanticModelName":"orders","name":"ordered_at","granularity":"$granularity"},
                    {"kind":"METRIC_TIME","granularity":"$granularity"}],
                "metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}"""
            assertEquals(mapper.readTree(json), mapper.valueToTree(AgentSemanticQueryJson.query(decode(json))))
        }
    }

    private fun decode(query: String) = assertIs<AgentExploreRunRequest.Semantic>(
        assertIs<AgentInvokeInput.ExploreRun>(decoder.invoke("""{
            "path":"workspace/explore/runs","action":"run",
            "input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000010","query":$query}
        }""").value.input).request,
    ).query
}
