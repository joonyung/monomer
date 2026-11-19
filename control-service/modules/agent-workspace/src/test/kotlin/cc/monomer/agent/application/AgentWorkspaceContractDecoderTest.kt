@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.application

import cc.monomer.agent.api.AgentCreateInput
import cc.monomer.agent.api.AgentExploreRunRequest
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentMetricDefinition
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentWorkspaceRequestDecodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

class AgentWorkspaceContractDecoderTest {
    private val decoder = AgentWorkspaceContractDecoder()

    @Test
    fun `dashboard delete and history restore are inputless canonical invokes`() {
        val dashboard = "workspace/dashboards/01942b04-6e00-7000-8000-000000000021"
        val version = "$dashboard/history/01942b04-6e00-7000-8000-000000000022"
        for ((path, action) in listOf(dashboard to "delete", version to "restore")) {
            assertIs<AgentInvokeInput.None>(decoder.invoke("""{"path":"$path","action":"$action"}""").value.input)
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke("""{"path":"$path","action":"$action","input":{}}""") }
        }
        assertEquals("$dashboard/history", decoder.list("""{"path":"$dashboard/history","limit":100}""").path.value)
        assertEquals(version, decoder.read("""{"path":"$version"}""").path.value)
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke("""{"path":"$dashboard/history","action":"restore"}""") }
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke("""{"path":"workspace/dashboards/not-a-uuid","action":"delete"}""") }
    }

    @Test
    fun `resource listing missing limit provides a repair hint without defaulting`() {
        for (path in listOf("workspace/dashboards", "organization/members")) {
            val failure = assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.list("""{"path":"$path"}""")
            }
            assertEquals("limit", failure.violations.single().field)
            assertEquals("REQUIRED_FIELD_MISSING", failure.violations.single().code)
            assertTrue(failure.violations.single().message.contains("integer from 1 to 100"))
            assertTrue(failure.violations.single().message.contains("for example 20"))
            assertEquals(20, decoder.list("""{"path":"$path","limit":20}""").limit)
        }
    }

    @Test
    fun `collection reads give LIST repair instructions without echoing path input`() {
        val resourceId = "01942b04-6e00-7000-8000-000000000021"
        for (path in listOf(
            "workspace/connectors", "workspace/semantic-models", "workspace/metrics", "workspace/dashboards",
            "workspace/pages", "workspace/explore/runs", "workspace/members", "workspace/member-candidates",
            "workspace/lineage/metrics", "workspace/dashboards/$resourceId/sessions",
            "workspace/dashboards/$resourceId/draft/query-runs", "workspace/pages/$resourceId/versions",
            "workspace/connectors/$resourceId/catalog/catalogs/secret-catalog/schemas",
            "organization/members", "organization/invitations", "organization/workspaces",
            "organization/workspaces/$resourceId/members",
        )) {
            val failure = assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.read("""{"path":"$path"}""")
            }
            val violation = failure.violations.single()
            assertEquals("path", violation.field)
            assertTrue(violation.message.contains("monomer_list"))
            assertTrue(violation.message.contains("same path"))
            assertTrue(violation.message.contains(if (path.startsWith("organization")) "organizationId" else "workspaceId"))
            assertTrue(violation.message.contains("integer from 1 to 100"))
            assertFalse(violation.message.contains("secret-catalog"))
            assertFalse(violation.message.contains(resourceId))
        }
        for (path in listOf("workspace", "organization", "workspace/metrics/revenue", "workspace/pages/tree",
            "workspace/dashboards/$resourceId/schedules", "workspace/connectors/$resourceId/catalog")) {
            assertEquals(path, decoder.read("""{"path":"$path"}""").path.value)
        }
        val malformed = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.read("""{"path":"workspace/metrics","unknownSecret":"secret-value"}""")
        }
        assertTrue(malformed.violations.none { it.message.contains("monomer_list") || it.message.contains("secret-value") })
    }

    @Test
    fun `SaaS connector create enforces vendor entrypoints through the public schema`() {
        val databricks = """{"path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"DATABRICKS","displayName":"Warehouse","properties":{"host":"dbc-fixture.cloud.databricks.com","warehouseId":"fixture"},"credential":{"kind":"ACCESS_TOKEN","accessToken":"fixture-only"}}}"""
        decoder.create(databricks)
        for (host in listOf("127.0.0.1", "proxy.example.com", "dbc-fixture.cloud.databricks.com.evil.test", "accounts.cloud.databricks.com")) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.create(databricks.replace("dbc-fixture.cloud.databricks.com", host))
            }
        }
        val snowflake = """{"path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"SNOWFLAKE","displayName":"Warehouse","properties":{"account":"myorg-myaccount","warehouse":"COMPUTE_WH","database":"ANALYTICS"},"credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}}"""
        decoder.create(snowflake)
        for (account in listOf("myorg-myaccount.privatelink", "myorg-myaccount.snowflakecomputing.com", "user@evil.test", "account/?insecureMode=true")) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.create(snowflake.replace("myorg-myaccount", account))
            }
        }
    }

    @Test
    fun `Arrow download is explicit and restricted to Explore results`() {
        val path = "workspace/explore/runs/01942b04-6e00-7000-8000-000000000021/result"
        assertEquals(null, decoder.read("""{"path":"$path"}""").download)
        assertEquals(cc.monomer.agent.api.AgentResultDownloadFormat.ARROW, decoder.read("""{"path":"$path","download":"arrow"}""").download)
        listOf(
            """{"path":"workspace","download":"arrow"}""",
            """{"path":"${path.removeSuffix("/result")}","download":"arrow"}""",
            """{"path":"$path","download":"csv"}""",
            """{"path":"$path","download":null}""",
            """{"path":"$path","download":true}""",
            """{"path":"$path","download":"arrow","objectKey":"other"}""",
        ).forEach { body -> assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.read(body) } }
    }

    @Test
    fun `search preserves ordered duplicate queries and omitted kinds`() {
        val request = decoder.search("""{"path":"workspace","queries":["고객 매출","orders","orders"],"limitPerQuery":20}""")
        assertEquals(listOf("고객 매출", "orders", "orders"), request.queries)
        assertEquals(null, request.kinds)
        assertEquals(20, request.limitPerQuery)
    }

    @Test
    fun `search rejects obsolete fields and invalid query or kind boundaries`() {
        val request = """{"path":"workspace","queries":["orders"],"limitPerQuery":10}"""
        val invalid = listOf(
            request.replace("[\"orders\"]", "[]"),
            request.replace("[\"orders\"]", "[\" \"]"),
            request.replace("[\"orders\"]", "[\"${"x".repeat(1025)}\"]"),
            request.replace("[\"orders\"]", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]"),
            request.replace("10", "0"), request.replace("10", "21"),
            request.replace("\"limitPerQuery\":10", "\"limit\":10"),
            request.replace("\"queries\":[\"orders\"]", "\"query\":\"orders\""),
            request.dropLast(1) + ",\"cursor\":\"old\"}",
            request.dropLast(1) + ",\"kinds\":[]}",
            request.dropLast(1) + ",\"kinds\":[\"EXPLORE_RUN\"]}",
            request.dropLast(1) + ",\"kinds\":[\"METRIC\",\"METRIC\"]}",
            request.replace("\"path\":\"workspace\",", ""),
        )
        invalid.forEach { body -> assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.search(body) } }
    }

    @Test
    fun `session resolution accepts complete empty and nullable maps and rejects old forms`() {
        val session = "workspace/dashboards/01942b04-6e00-7000-8000-000000000010/sessions/01942b04-6e00-7000-8000-000000000012"
        val request = """{"path":"$session","action":"resolve","input":{"kind":"DASHBOARD_SESSION_RESOLVE","variables":{}}}"""
        assertEquals(emptyMap(), assertIs<AgentInvokeInput.DashboardSessionResolve>(decoder.invoke(request).value.input).variables)
        val nullable = request.replace("\"variables\":{}", "\"variables\":{\"country\":null}")
        assertEquals(kotlinx.serialization.json.JsonNull, assertIs<AgentInvokeInput.DashboardSessionResolve>(decoder.invoke(nullable).value.input).variables["country"])
        listOf(
            request.replace(",\"variables\":{}", ""),
            request.replace("DASHBOARD_SESSION_RESOLVE", "DASHBOARD_QUERY_REFRESH"),
            request.replace("\"action\":\"resolve\"", "\"action\":\"refresh\""),
            request.replace("\"variables\":{}", "\"variables\":{},\"revision\":\"1\""),
            """{"path":"$session","action":"refresh","input":{"kind":"DASHBOARD_SESSION_REFRESH","variables":{},"queryNames":[]}}""",
            """{"path":"$session","action":"refresh","input":{"kind":"DASHBOARD_SESSION_REFRESH","variables":{},"queryNames":["orders","orders"]}}""",
        ).forEach { invalid -> assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(invalid) } }
    }

    @Test
    fun `resource names decode after JSON escaping without URL interpretation`() {
        val base = "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog/schemas/"
        val request = """{"path":"${base}한글\uD83D\uDE00~1%2F+?#\\\""}"""
        val path = decoder.read(request).path
        assertEquals(base + "한글😀~1%2F+?#\\\"", path.value)
        assertEquals("한글😀/%2F+?#\\\"", AgentResourcePath.decodeNameSegment(path.value.substringAfterLast('/')))
        assertEquals(base + "a/b", decoder.read("""{"path":"${base}a\/b"}""").path.value)
        for (invalid in listOf("~", "~2", "~x", "\uD800", "e\u0301", "a\u0085b")) {
            val requestPath = kotlinx.serialization.json.JsonPrimitive(base + invalid)
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.read("""{"path":$requestPath}""") }
        }
    }

    @Test
    fun `semantic model create retains the owner definition`() {
        val decoded = decoder.create(semanticModelCreateRequest).value
        val input = assertIs<AgentCreateInput.SemanticModel>(decoded.input)

        assertEquals(AgentResourcePath.parse("workspace/semantic-models"), decoded.path)
        assertEquals(3u, input.definition.schemaVersion)
        assertEquals("orders", input.definition.name)
        assertEquals("orders", input.definition.sourceRelations.single().relation)
        assertEquals("revenue", input.definition.measures.single().name)
    }

    @Test
    fun `semantic time dimension timeKind round trips and stays closed to time dimensions`() {
        val dimension = "\"name\": \"ordered_at\", \"type\": \"time\""
        val declared = semanticModelCreateRequest.replace(dimension, "$dimension, \"timeKind\": \"instant\"")
        val input = assertIs<AgentCreateInput.SemanticModel>(decoder.create(declared).value.input)
        assertEquals(cc.monomer.agent.api.AgentSemanticTimeKind.INSTANT, input.definition.dimensions.single().timeKind)
        val encoded = AgentSemanticDefinitionJson.model(input.definition)["dimensions"] as List<*>
        assertEquals("instant", (encoded.single() as Map<*, *>)["timeKind"])
        val undeclared = assertIs<AgentCreateInput.SemanticModel>(decoder.create(semanticModelCreateRequest).value.input)
        assertEquals(null, undeclared.definition.dimensions.single().timeKind)
        assertTrue("timeKind" !in ((AgentSemanticDefinitionJson.model(undeclared.definition)["dimensions"] as List<*>).single() as Map<*, *>))
        assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.create(semanticModelCreateRequest.replace(dimension, "$dimension, \"timeKind\": \"zoned\""))
        }
        val categorical = semanticModelCreateRequest.replace(
            "{\"isPartition\":false,\"name\": \"ordered_at\"",
            "{\"isPartition\":false,\"name\":\"status\",\"type\":\"categorical\",\"expression\":\"status\",\"timeKind\":\"local\"},{\"isPartition\":false,\"name\": \"ordered_at\"",
        )
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(categorical) }
    }

    @Test
    fun `metric display creates round trip through Agent values and closed schema`() {
        val displays = listOf(
            """{"style":"NUMBER","decimals":0,"unit":"percent"}""",
            """{"style":"CURRENCY","decimals":2,"currency":"USD"}""",
            """{"style":"PERCENT","decimals":2}""",
        )
        displays.forEach { display ->
            val request = metricCreateRequest.replace("\"measure\": \"revenue\"", "\"measure\":\"revenue\",\"display\":$display")
            val input = assertIs<AgentCreateInput.Metric>(decoder.create(request).value.input)
            assertEquals(2.takeIf { !display.contains("NUMBER") } ?: 0, input.definition.display?.decimals)
            val encoded = AgentSemanticDefinitionJson.metric(input.definition)
            assertTrue(encoded["display"] is Map<*, *>)
        }
        listOf(
            """{"style":"NUMBER","decimals":2,"currency":"USD"}""",
            """{"style":"CURRENCY","decimals":2,"currency":"ZZZ"}""",
            """{"style":"PERCENT","decimals":7}""",
        ).forEach { display ->
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.create(metricCreateRequest.replace("\"measure\": \"revenue\"", "\"measure\":\"revenue\",\"display\":$display"))
            }
        }
    }

    @Test
    fun `metric create decodes the current metric definition`() {
        val decoded = decoder.create(metricCreateRequest).value
        val input = assertIs<AgentCreateInput.Metric>(decoded.input)
        val definition = assertIs<AgentMetricDefinition.Simple>(input.definition)

        assertEquals(AgentResourcePath.parse("workspace/metrics"), decoded.path)
        assertEquals(3u, definition.schemaVersion)
        assertEquals("revenue", definition.name)
        assertEquals("orders", definition.semanticModelName)
        assertEquals("revenue", definition.measure)
    }

    @Test
    fun `dashboard create loads owner DSL and transitive semantic query schemas`() {
        val decoded = decoder.create(dashboardCreateRequest).value
        val dashboard = assertIs<AgentCreateInput.Dashboard>(decoded.input).definition

        assertEquals(AgentResourcePath.parse("workspace/dashboards"), decoded.path)
        assertEquals("TEXT", dashboard.getValue("parameters").jsonObject.getValue("region").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("US", dashboard.getValue("variables").jsonObject.getValue("selection").jsonObject.getValue("initial").jsonPrimitive.content)
        assertEquals("4", dashboard.getValue("queries").jsonObject.getValue("orders").jsonObject.getValue("query").jsonObject.getValue("schemaVersion").jsonPrimitive.content)
    }

    @Test
    fun `dashboard create rejects former item nodes and malformed direct widget references`() {
        val canonicalNode = """"type":"WIDGET","id":"chartItem","widgetId":"chart""""
        val invalidNodes = listOf(
            """"type":"ITEM","id":"chartItem","item":{"kind":"WIDGET","id":"chart"}""",
            """"type":"WIDGET","id":"chartItem"""",
            """"type":"WIDGET","id":"chartItem","widgetId":null""",
            """"type":"WIDGET","id":"chartItem","widgetId":1""",
            """"type":"WIDGET","id":"chartItem","widgetId":""""",
            """"type":"WIDGET","id":"chartItem","widgetId":"bad reference"""",
            """"type":"WIDGET","id":"chartItem","widgetId":{"id":"chart"}""",
            """"type":"WIDGET","id":"chartItem","widgetId":"chart","item":{"kind":"WIDGET","id":"chart"}""",
        )
        invalidNodes.forEach { node ->
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.create(dashboardCreateRequest.replace(canonicalNode, node))
            }
        }
    }

    @Test
    fun `dashboard table module follows the published owner schema`() {
        val request = dashboardCreateRequest
            .replace("\"type\":\"CHART\",\"queryNames\":[\"orders\"]", "\"type\":\"TABLE\",\"queryName\":\"orders\",\"columns\":[{\"field\":\"metric.revenue\",\"width\":180}],\"rowHeight\":56")
            .replace("\"inputs\":{\"parameters\"", "\"inputs\":{\"queries\":[],\"parameters\"")
            .replace("defineChart", "defineTable")
        val definition = assertIs<AgentCreateInput.Dashboard>(decoder.create(request).value.input).definition
        assertEquals("TABLE", definition.getValue("widgets").jsonObject.getValue("chart").jsonObject.getValue("type").jsonPrimitive.content)
        listOf(
            request.replace("\"width\":180", "\"width\":79"),
            request.replace("\"width\":180", "\"width\":\"180\""),
            request.replace("\"rowHeight\":56", "\"rowHeight\":161"),
            request.replace("\"queries\":[],", ""),
            request.replace("\"queries\":[]", "\"queries\":[\"daily\",\"daily\"]"),
            request.replace("\"rowHeight\":56", "\"rowHeight\":56,\"actions\":[]"),
        ).forEach { invalid ->
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(invalid) }
        }
    }

    @Test
    fun `dashboard owner schema rejects incomplete definitions and invalid transitive query values`() {
        listOf(
            """{"path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{}}}""",
            dashboardCreateRequest.replace("\"rowLimit\":100", "\"rowLimit\":\"invalid\""),
            dashboardCreateRequest.replace("\"type\":\"TEXT\"", "\"type\":\"BANANA\""),
            dashboardCreateRequest.replace("\"initial\":\"US\"", "\"initial\":{\"unexpected\":true}"),
        ).forEach { request ->
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(request) }
        }
    }

    @Test
    fun `dashboard numeric strings match exactly one initial value variant`() {
        listOf("TEXT", "NUMBER").forEach { type ->
            val request = dashboardCreateRequest.replace(
                "\"selection\":{\"type\":\"TEXT\",\"nullable\":false,\"initial\":\"US\"}",
                "\"selection\":{\"type\":\"$type\",\"nullable\":false,\"initial\":\"0\"}",
            )
            val definition = assertIs<AgentCreateInput.Dashboard>(decoder.create(request).value.input).definition
            assertEquals("0", definition.getValue("variables").jsonObject.getValue("selection").jsonObject.getValue("initial").jsonPrimitive.content)
        }
        assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.create(dashboardCreateRequest.replace(
                "\"selection\":{\"type\":\"TEXT\",\"nullable\":false,\"initial\":\"US\"}",
                "\"selection\":{\"type\":\"NUMBER\",\"nullable\":false,\"initial\":\"01\"}",
            ))
        }
    }

    @Test
    fun `dashboard session bindings accept dynamic literal names and reject invalid values`() {
        val request = """{
            "path":"workspace/dashboards/01942b04-6e00-7000-8000-000000000010/sessions","action":"open",
            "input":{"kind":"DASHBOARD_SESSION_OPEN","parameterBindings":{
                "region":"US","enabled":true,"nullable":null,"regions":["US","CA"],
                "window":{"start":"2026-09-01","end":"2026-09-06"}
            }}
        }"""
        val bindings = assertIs<AgentInvokeInput.DashboardSessionOpen>(decoder.invoke(request).value.input).parameterBindings
        assertEquals(setOf("region", "enabled", "nullable", "regions", "window"), bindings.keys)
        assertEquals("US", bindings.getValue("region").jsonPrimitive.content)

        listOf("17", "{\"unexpected\":true}", "[{\"unexpected\":true}]").forEach { invalidValue ->
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.invoke(request.replace("\"region\":\"US\"", "\"region\":$invalidValue"))
            }
        }
    }

    @Test
    fun `edit decodes ordered RFC 6902 operations without a trace envelope`() {
        val decoded = decoder.edit(
            """
            {
              "path": "workspace/semantic-models/orders", "aggregateMetrics": [],
              "patch": [
                {"op": "replace", "path": "/label", "value": "Order facts"},
                {"op": "remove", "path": "/description"}
              ]
            }
            """.trimIndent(),
        ).value

        assertEquals(AgentResourcePath.parse("workspace/semantic-models/orders"), decoded.path)
        assertEquals(null, decoded.revision)
        assertEquals(2, decoded.patch.size)
        assertEquals("/label", decoded.patch[0].path)
        assertEquals("/description", decoded.patch[1].path)
    }

    @Test
    fun `strict JSON rejects duplicate fields before decoding`() {
        assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.create(semanticModelCreateRequest.replace(
                "\"path\": \"workspace/semantic-models\"",
                "\"path\": \"workspace/semantic-models\", \"path\": \"workspace/metrics\"",
            ))
        }
    }

    @Test
    fun `legacy write paths and wrappers are not accepted`() {
        assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.create(semanticModelCreateRequest.replace(
                "workspace/semantic-models",
                "workspace/semantic_models/orders/model.json",
            ))
        }
        assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.create(semanticModelCreateRequest.replace(
                "\"kind\": \"SEMANTIC_MODEL_DEFINITION\",",
                "\"kind\": \"SEMANTIC_MODEL_DEFINITION\", \"semanticModelId\": \"01942b04-6e00-7000-8000-000000000001\",",
            ))
        }
    }

    @Test
    fun `payload free dashboard cancellation omits input`() {
        val decoded = decoder.invoke(
            """
            {
              "path": "workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft/query-runs/01942b04-6e00-7000-8000-000000000011",
              "action": "cancel"
            }
            """.trimIndent(),
        ).value

        assertIs<AgentInvokeInput.None>(decoded.input)
    }

    @Test
    fun `dashboard session refresh accepts complete variables separately from draft refresh`() {
        val draft = decoder.invoke(
            """
            {
              "path": "workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft",
              "action": "refresh",
              "input": {"kind": "DASHBOARD_QUERY_REFRESH", "queryNames": ["revenue"]}
            }
            """.trimIndent(),
        ).value
        val session = decoder.invoke(
            """
            {
              "path": "workspace/dashboards/01942b04-6e00-7000-8000-000000000010/sessions/01942b04-6e00-7000-8000-000000000012",
              "action": "refresh",
              "input": {"kind": "DASHBOARD_SESSION_REFRESH", "variables": {}, "queryNames": ["revenue"]}
            }
            """.trimIndent(),
        ).value

        assertEquals(setOf("revenue"), assertIs<AgentInvokeInput.DashboardQueryRefresh>(draft.input).queryNames)
        assertEquals(setOf("revenue"), assertIs<AgentInvokeInput.DashboardSessionRefresh>(session.input).queryNames)
    }

    @Test
    fun `viewer session open decodes parameter bindings on the session collection`() {
        val decoded = decoder.invoke(
            """
            {
              "path": "workspace/dashboards/01942b04-6e00-7000-8000-000000000010/sessions",
              "action": "open",
              "input": {"kind": "DASHBOARD_SESSION_OPEN", "parameterBindings": {"window_days": "30"}}
            }
            """.trimIndent(),
        ).value

        val open = assertIs<AgentInvokeInput.DashboardSessionOpen>(decoded.input)
        assertEquals("30", open.parameterBindings.getValue("window_days").jsonPrimitive.content)
    }

    @Test
    fun `dashboard refresh rejects legacy input kind and duplicate query names`() {
        assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.invoke(
                """
                {
                  "path": "workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft",
                  "action": "refresh",
                  "input": {"kind": "DASHBOARD_DRAFT_REFRESH", "queryNames": ["revenue"]}
                }
                """.trimIndent(),
            )
        }
        assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.invoke(
                """
                {
                  "path": "workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft",
                  "action": "refresh",
                  "input": {"kind": "DASHBOARD_QUERY_REFRESH", "queryNames": ["revenue", "revenue"]}
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `SQL explore invoke uses the canonical run collection path`() {
        val decoded = decoder.invoke(
            """
            {
              "path": "workspace/explore/runs",
              "action": "run",
              "input": {
                "kind": "SQL_EXPLORE_RUN",
                "connectorId": "018f0000-0000-7000-8000-000000000099",
                "sql": "SELECT 1"
              }
            }
            """.trimIndent(),
        ).value
        val explore = assertIs<AgentInvokeInput.ExploreRun>(decoded.input)
        val sql = assertIs<AgentExploreRunRequest.Sql>(explore.request)

        assertEquals("SELECT 1", sql.statement)
        assertEquals("018f0000-0000-7000-8000-000000000099", sql.connectorId.toString())
    }

    @Test
    fun `payload free explore cancellation uses an exact run path`() {
        val decoded = decoder.invoke(
            """
            {
              "path": "workspace/explore/runs/01942b04-6e00-7000-8000-000000000010",
              "action": "cancel"
            }
            """.trimIndent(),
        ).value

        assertIs<AgentInvokeInput.None>(decoded.input)
    }

    @Test
    fun `catalog lifecycle invokes are payload free and path specific`() {
        val pathsAndActions = listOf(
            "workspace/connectors/$CONNECTOR_ID/catalog" to "ingest",
            "workspace/connectors/$CONNECTOR_ID/catalog/catalogs/warehouse" to "ingest",
            "workspace/connectors/$CONNECTOR_ID/catalog/catalog-selection/options/catalogs" to "refresh",
            "workspace/connectors/$CONNECTOR_ID/catalog/catalogs/warehouse/selection/options/schemas/analytics/relations" to "refresh",
            "workspace/connectors/$CONNECTOR_ID/catalog/ingestions/01942b04-6e00-7000-8000-000000000010" to "cancel",
            "workspace/connectors/$CONNECTOR_ID/catalog/catalog-selection/option-refreshes/catalogs/01942b04-6e00-7000-8000-000000000011" to "cancel",
        )

        pathsAndActions.forEach { (path, action) ->
            val decoded = decoder.invoke("""{"path":"$path","action":"$action"}""").value
            assertIs<AgentInvokeInput.None>(decoded.input)
        }
    }

    @Test
    fun `catalog lifecycle rejects mismatched action path and legacy repeated input`() {
        listOf(
            """{"path":"workspace/connectors/$CONNECTOR_ID/catalog","action":"refresh"}""",
            """{"path":"workspace/connectors/$CONNECTOR_ID/catalog/catalog-selection/options/catalogs","action":"ingest"}""",
            """{"path":"workspace/connectors/$CONNECTOR_ID/catalog","action":"ingest","input":{"entryKind":"CONNECTOR"}}""",
        ).forEach { request ->
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(request) }
        }
    }

    @Test
    fun `catalog selection edit is revisionless whole document replacement`() {
        val path = "workspace/connectors/$CONNECTOR_ID/catalog/selection"
        val accepted = decoder.edit(
            """{"path":"$path","patch":[{"op":"replace","path":"","value":{"selectedSchemas":[]}}]}""",
        ).value
        assertEquals(null, accepted.revision)

        listOf(
            """{"path":"$path","revision":"1","patch":[{"op":"replace","path":"","value":{"selectedSchemas":[]}}]}""",
            """{"path":"$path","patch":[{"op":"replace","path":"/selectedSchemas","value":[]}]}""",
        ).forEach { request ->
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.edit(request) }
        }
    }

    @Test
    fun `schema diagnostics explain numeric bounds and declared enum options without reflecting input`() {
        for ((limit, message) in listOf(0 to "at least 1", 50 to "at most 20")) {
            val failure = assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.search("""{"path":"workspace","queries":["secret-query"],"limitPerQuery":$limit}""")
            }
            assertEquals("limitPerQuery", failure.violations.single().field)
            assertTrue(failure.violations.single().message.contains(message))
            assertFalse(failure.violations.toString().contains("secret-query"))
        }
        val failure = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.invoke("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"secret-kind","connectorId":"$CONNECTOR_ID"}}""")
        }
        assertFalse(failure.violations.single().message.contains("SQL_EXPLORE_RUN"))
        assertFalse(failure.violations.single().message.contains("Allowed values"))
        assertFalse(failure.violations.toString().contains("secret-kind"))
    }

    @Test
    fun `schema diagnostics retain nested enum location without alternate variants`() {
        val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.create(semanticModelCreateRequest.replace("\"aggregation\": \"sum\"", "\"aggregation\": \"secret-enum\""))
        }
        assertEquals(listOf("input.definition.measures[0].aggregation"), exception.violations.map { it.field })
        assertEquals("INVALID_ENUM_VALUE", exception.violations.single().code)
        val schema = com.fasterxml.jackson.databind.ObjectMapper().readTree(
            javaClass.classLoader.getResourceAsStream("contracts/semantic/model.schema.json"),
        )
        val aggregationOptions = schema.findValues("enum").single { it.any { value -> value.asText() == "sum" } }
        assertEquals("Allowed values: $aggregationOptions.", exception.violations.single().message)
        assertFalse(exception.toString().contains("secret-enum"))
        assertFalse(exception.violations.toString().contains("secret-enum"))
    }

    @Test
    fun `aggregate metrics are required model envelopes and rejected by other edit branches`() {
        val create = semanticModelCreateRequest.replace("\"aggregateMetrics\": []", "\"aggregateMetrics\": [{\"measure\":\"revenue\",\"name\":\"total\",\"timeGranularity\":\"month\"}]")
        assertEquals("total", assertIs<AgentCreateInput.SemanticModel>(decoder.create(create).value.input).aggregateMetrics.single().name)
        val edit = """{"path":"workspace/semantic-models/orders","aggregateMetrics":[{"measure":"revenue","name":"total"}],"patch":[{"op":"replace","path":"/label","value":"Orders"}]}"""
        assertEquals("total", decoder.edit(edit).value.aggregateMetrics?.single()?.name)
        val missing = assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.edit(edit.replace("\"aggregateMetrics\":[{\"measure\":\"revenue\",\"name\":\"total\"}],", "")) }
        assertEquals("aggregateMetrics", missing.violations.single().field)
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.edit(edit.replace("workspace/semantic-models/orders", "workspace/metrics/total")) }
        val malformed = assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.edit(edit.replace("\"name\":\"total\"", "\"name\":17")) }
        assertEquals("aggregateMetrics[0].name", malformed.violations.single().field)
    }

    @Test
    fun `selected request variant reports required fields and actual nested input coordinates`() {
        val missing = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.create(metricCreateRequest.replace("\"measure\": \"revenue\"", "\"description\": \"secret-description\""))
        }
        assertEquals(listOf("input.definition.measure"), missing.violations.map { it.field })
        assertEquals("REQUIRED_FIELD_MISSING", missing.violations.single().code)
        val wrongType = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.create(metricCreateRequest.replace("\"name\": \"revenue\"", "\"name\": 17"))
        }
        assertTrue(wrongType.violations.any { it.field == "input.definition.name" && it.code == "SCHEMA_MISMATCH" })
    }

    @Test
    fun `patch diagnostics identify operation and safe pointer before typed decoding`() {
        for (invalidOperation in listOf("\"op\": \"secret-op\",", "")) {
            val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.edit("""{"path":"workspace/semantic-models/orders", "aggregateMetrics": [],"patch":[
                    {"op":"replace","path":"/label","value":"ok"},
                    {$invalidOperation "path":"/measures/0/aggregation","value":"secret-value"}
                ]}""")
            }
            val violation = exception.violations.single()
            assertEquals("patch[1].op", violation.field)
            assertEquals(1, violation.operationIndex)
            assertEquals("/measures/0/aggregation", violation.path)
            assertEquals(if (invalidOperation.isEmpty()) "REQUIRED_FIELD_MISSING" else "INVALID_ENUM_VALUE", violation.code)
            assertFalse(exception.violations.toString().contains("secret-"))
        }
    }

    @Test
    fun `missing patch value belongs only to its selected operation`() {
        val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.edit("""{"path":"workspace/semantic-models/orders", "aggregateMetrics": [],"patch":[{"op":"replace","path":"/label"}]}""")
        }
        assertEquals("patch[0].value", exception.violations.single().field)
        assertEquals(0, exception.violations.single().operationIndex)
        assertEquals("/label", exception.violations.single().path)
    }

    @Test
    fun `diagnostics never reflect unknown keys unsafe pointers or unicode failures`() {
        val requests = listOf(
            """{"path":"workspace/semantic-models/orders","secret-property":{"secret-nested":"value"}}""",
            """{"path":"workspace/semantic-models/orders","secret-property":"e\u0301"}""",
        )
        requests.forEach { request ->
            val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.read(request) }
            assertFalse(exception.toString().contains("secret-"))
            assertFalse(exception.violations.toString().contains("secret-"))
        }
        listOf("/secret-pointer", "/label~9", "secret-pointer").forEach { pointer ->
            val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.edit("""{"path":"workspace/semantic-models/orders", "aggregateMetrics": [],"patch":[{"op":"secret-op","path":"$pointer"}]}""")
            }
            assertTrue(exception.violations.all { it.path == null })
            assertFalse(exception.violations.toString().contains("secret-"))
        }
    }

    @Test
    fun `numeric object keys and impossible array indexes are not public repair paths`() {
        for (pointer in listOf("/123456", "/123456789012345678901234567890", "/name/123456", "/measures/2147483648", "/measures/0/123456")) {
            val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.edit("""{"path":"workspace/semantic-models/orders", "aggregateMetrics": [],"patch":[{"op":"invalid","path":"$pointer"}]}""")
            }
            assertTrue(exception.violations.all { it.path == null }, pointer)
        }
    }

    @Test
    fun `schema diagnostics obey the public violation count bound`() {
        val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.edit("""{"path":"workspace/semantic-models/orders", "aggregateMetrics": [],"patch":[${List(100) { "{}" }.joinToString(",")}]}""")
        }
        assertEquals(100, exception.violations.size)
        assertTrue(exception.violations.all { it.field.length <= 512 && it.message.length <= 512 })
    }

    @Test
    fun `unknown discriminator avoids invented fields from unmatched input variants`() {
        val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.invoke("""{"path":"workspace/explore/runs","action":"run","input":{
                "kind":"secret-kind","connectorId":"$CONNECTOR_ID"
            }}""")
        }
        assertEquals(listOf("input.kind"), exception.violations.map { it.field })
        assertEquals("INVALID_ENUM_VALUE", exception.violations.single().code)
    }

    @Test
    fun `semantic query diagnostics use wire coordinates`() {
        val exception = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.invoke("""{"path":"workspace/explore/runs","action":"run","input":{
                "kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$CONNECTOR_ID","query":{
                    "schemaVersion":4,"metrics":["revenue"],"groupBys":[{"kind":"METRIC_TIME","granularity":"secret-granularity"}],
                    "metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":10
                }
            }}""")
        }
        assertEquals(listOf("input.query.groupBys[0].granularity"), exception.violations.map { it.field })
        assertEquals("INVALID_ENUM_VALUE", exception.violations.single().code)
    }

    @Test
    fun `explicit JSON null remains a value for sequential owner validation`() {
        val request = decoder.edit("""{"path":"workspace/semantic-models/orders", "aggregateMetrics": [],"patch":[
                {"op":"replace","path":"/label","value":null}
            ]}""").value
        assertEquals(kotlinx.serialization.json.JsonNull, request.patch.single().value)
    }

    @Test
    fun `patch array indexes accept only canonical decimal positions`() {
        val document = kotlinx.serialization.json.JsonArray(listOf(
            kotlinx.serialization.json.JsonPrimitive("a"), kotlinx.serialization.json.JsonPrimitive("b"),
        ))
        val replacement = kotlinx.serialization.json.JsonPrimitive("c")
        listOf("/+1", "/-0", "/01").forEach { path ->
            assertFailsWith<cc.monomer.agent.api.AgentJsonPatchException>(path) {
                cc.monomer.agent.api.AgentJsonPatch.apply(document, listOf(cc.monomer.agent.api.AgentJsonPatchOperation(cc.monomer.agent.api.AgentJsonPatchOp.REPLACE, path, replacement)))
            }
        }
        assertEquals(
            kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("a"), replacement)),
            cc.monomer.agent.api.AgentJsonPatch.apply(document, listOf(cc.monomer.agent.api.AgentJsonPatchOperation(cc.monomer.agent.api.AgentJsonPatchOp.REPLACE, "/1", replacement))),
        )
    }

    @Test
    fun `safe patch diagnostic paths preserve declared fields only`() {
        listOf("", "/label", "/measures/0/aggregation", "/definition/measures/-").forEach {
            assertEquals(it, cc.monomer.agent.api.AgentJsonPatch.safeDiagnosticPath(it))
        }
        listOf("/secret-pointer", "/label~9", "/label\n", "/" + "label".repeat(500)).forEach {
            assertEquals(null, cc.monomer.agent.api.AgentJsonPatch.safeDiagnosticPath(it))
        }
    }

    @Test
    fun `semantic text filter values count Unicode characters and reject empty oversized or NUL text`() {
        for (value in listOf("a", " ", "a".repeat(256), "😀".repeat(256), "{{ literal }}")) {
            val decoded = decoder.invoke(semanticFilterRequest("TEXT", "EQUALS", listOf(value))).value
            val query = assertIs<AgentExploreRunRequest.Semantic>(assertIs<AgentInvokeInput.ExploreRun>(decoded.input).request).query
            assertEquals(listOf(value), assertIs<cc.monomer.agent.api.AgentSemanticFilter.Text>(query.filters.single()).values)
        }
        for (value in listOf("", "a".repeat(257), "😀".repeat(257), "a\u0000b")) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.invoke(semanticFilterRequest("TEXT", "EQUALS", listOf(value)))
            }
        }
    }

    @Test
    fun `semantic filter schema scalar set and empty operator arity matches typed values`() {
        for ((kind, value) in listOf("TEXT" to "a", "NUMBER" to "1", "DATE" to "2026-10-06", "TIMESTAMP" to "2026-10-06T00:00:00Z")) {
            for ((operator, values) in listOf(
                "EQUALS" to emptyList(), "NOT_EQUALS" to listOf(value, value),
                "IS_NULL" to listOf(value), "IS_NOT_NULL" to listOf(value),
                "IN" to emptyList(), "NOT_IN" to List(101) { value }, "IN" to listOf(value, value),
            )) {
                assertFailsWith<AgentWorkspaceRequestDecodingException> {
                    decoder.invoke(semanticFilterRequest(kind, operator, values))
                }
            }
            for (operator in listOf("EQUALS", "NOT_EQUALS", "IN", "NOT_IN")) decoder.invoke(semanticFilterRequest(kind, operator, listOf(value)))
            for (operator in listOf("IS_NULL", "IS_NOT_NULL")) decoder.invoke(semanticFilterRequest(kind, operator, emptyList()))
        }
        decoder.invoke(semanticFilterRequest("TEXT", "IN", (1..100).map(Int::toString)))
    }

    @Test
    fun `timestamp filter precision has a schema violation without rounding trailing zeros`() {
        for (fraction in listOf("1234567", "12345678", "123456789", "1234567890", "1234560", "0000000")) {
            for (offset in listOf("Z", "+09:00", "-05:00")) {
                val failure = assertFailsWith<AgentWorkspaceRequestDecodingException> {
                    decoder.invoke(semanticFilterRequest("TIMESTAMP", "IN", listOf("2026-10-01T00:00:00Z", "2026-10-01T00:00:00.$fraction$offset")))
                }
                val violation = failure.violations.single()
                assertEquals("input.query.filters[0].values[1]", violation.field)
                assertEquals("SCHEMA_MISMATCH", violation.code)
                assertEquals("Timestamp filter values must have at most 6 fractional second digits.", violation.message)
            }
        }
    }

    @Test
    fun `timestamp filters with zero through six digits retain the exact instant`() {
        for (fraction in listOf("", ".1", ".12", ".123", ".1234", ".12345", ".123456", ".000000")) {
            for (offset in listOf("Z", "+09:00", "-05:00")) {
                val raw = "2026-10-01T00:00:00$fraction$offset"
                val decoded = decoder.invoke(semanticFilterRequest("TIMESTAMP", "EQUALS", listOf(raw))).value
                val query = assertIs<AgentExploreRunRequest.Semantic>(assertIs<AgentInvokeInput.ExploreRun>(decoded.input).request).query
                assertEquals(listOf(java.time.Instant.parse(raw)), assertIs<cc.monomer.agent.api.AgentSemanticFilter.Timestamp>(query.filters.single()).values)
            }
        }
    }

    @Test
    fun `timestamp filter values remain unique after offset normalization`() {
        val failure = assertFailsWith<AgentWorkspaceRequestDecodingException> {
            decoder.invoke(semanticFilterRequest("TIMESTAMP", "IN", listOf("2026-10-06T00:00:00Z", "2026-10-06T09:00:00+09:00")))
        }
        assertEquals("input.query.filters[0].values", failure.violations.single().field)
        assertEquals("Filter values must be unique.", failure.violations.single().message)
    }

    @Test
    fun `semantic number filter values use canonical decimals`() {
        for (value in listOf("01", "+1", "1e2", "1.0", "-0", "0.0", " 1", "1.", "123456789012345678901", "0.1234567890123456789")) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.invoke(semanticFilterRequest("NUMBER", "EQUALS", listOf(value)))
            }
        }
        for (value in listOf("0", "-1", "0.1", "-0.1", "12345678901234567890.123456789012345678")) decoder.invoke(semanticFilterRequest("NUMBER", "EQUALS", listOf(value)))
    }

    private fun semanticFilterRequest(kind: String, operator: String, values: List<String>): String {
        val valueJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values)
        return """{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$CONNECTOR_ID","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[{"kind":"$kind","dimension":{"semanticModelName":"orders","name":"status"},"operator":"$operator","values":$valueJson}],"orderBy":[],"rowLimit":10}}}"""
    }

    private companion object {
        const val CONNECTOR_ID = "018f0000-0000-7000-8000-000000000099"

        val dashboardCreateRequest = """{
            "path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{
                "schemaVersion":1,"meta":{"title":"Revenue"},
                "parameters":{"region":{"type":"TEXT","nullable":false}},
                "variables":{"selection":{"type":"TEXT","nullable":false,"initial":"US"}},
                "queries":{"orders":{"type":"SEMANTIC","connectorId":"$CONNECTOR_ID","query":{
                    "schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,
                    "filters":[],"orderBy":[],"rowLimit":100
                }}},
                "widgets":{"chart":{"type":"CHART","queryNames":["orders"],"module":{
                    "inputs":{"parameters":["region"],"variables":["selection"]},
                    "source":"export default defineChart({ render() { return {}; } });"
                }}},
                "layout":{"sections":[{"id":"main","height": { "mode": "SCALED", "scale": 1.0 },"root":{
                    "type":"WIDGET","id":"chartItem","widgetId":"chart"
                }}]}
            }}
        }"""

        val semanticModelCreateRequest =
            """
            {
              "path": "workspace/semantic-models",
              "input": {
                "kind": "SEMANTIC_MODEL_DEFINITION", "aggregateMetrics": [],
                "definition": {
                  "schemaVersion": 3,
                  "name": "orders",
                  "label": "Orders",
                  "description": "Current orders model",
                  "sourceRelations": [
                    {"connectorId": "$CONNECTOR_ID", "catalog": "warehouse", "schema": "analytics", "relation": "orders"}
                  ],
                  "aggregationTimeDimension": "ordered_at",
                  "entities": [
                    {"name": "order", "type": "primary", "expression": "order_id", "label": "Order", "description": "Order identifier"}
                  ],
                  "dimensions": [
                    {"isPartition":false,"name": "ordered_at", "type": "time", "granularity": "day", "expression": "ordered_at", "label": "Ordered at", "description": "Order date"}
                  ],
                  "measures": [
                    {"name": "revenue", "aggregation": "sum", "expression": "revenue", "aggregationTimeDimension": "ordered_at", "label": "Revenue", "description": "Gross revenue"}
                  ]
                }
              }
            }
            """.trimIndent()

        val metricCreateRequest =
            """
            {
              "path": "workspace/metrics",
              "input": {
                "kind": "METRIC_DEFINITION",
                "definition": {
                  "schemaVersion": 3,
                  "name": "revenue",
                  "type": "simple",
                  "semanticModelName": "orders",
                  "measure": "revenue"
                }
              }
            }
            """.trimIndent()
    }
}
