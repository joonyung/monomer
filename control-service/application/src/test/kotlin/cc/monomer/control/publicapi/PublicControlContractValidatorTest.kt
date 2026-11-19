package cc.monomer.control.publicapi

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PublicControlContractValidatorTest {
    private val validator = PublicControlContractValidator()
    private val objectMapper = ObjectMapper()

    @Test
    fun `session resolution result requires both transient maps and accepted status`() {
        for (verb in listOf("resolve", "refresh")) {
            val result = action("INVOKE", SESSION_PATH, verb, "ACCEPTED", "DASHBOARD_SESSION", SESSION_PATH) + mapOf(
                "variables" to mapOf("country" to null),
                "queryRuns" to mapOf("orders" to "$SESSION_PATH/query-runs/$ID"),
            )
            assertEquals(result, validator.agentAction(result))
            listOf(
                result - "variables", result - "queryRuns", result + ("status" to "COMPLETED"),
                result + ("queryRuns" to mapOf("orders" to "$SESSION_PATH/query-runs")),
                result + ("revision" to "1"),
            ).forEach { invalid -> assertFailsWith<IllegalStateException> { validator.agentAction(invalid) } }
        }
        assertFailsWith<IllegalStateException> {
            validator.agentAction(action("EDIT", SESSION_PATH, null, "COMPLETED", "DASHBOARD_SESSION", SESSION_PATH))
        }
    }

    @Test
    fun `packaged owner schemas validate actual resource variants`() {
        val resources = listOf(
            resource("COLLECTION", "workspace", """{"role":"OWNER","resources":[{"operation":"LIST","path":"workspace/metrics"}]}"""),
            resource("RELATION", "$CATALOG_PATH/catalogs/warehouse/schemas/analytics/relations/orders", """{
                "connectorId":"$ID","catalogName":"warehouse","schemaName":"analytics",
                "relationName":"orders","relationKind":"TABLE","columns":[],"foreignKeys":[],"partitionKeys":[],"clusteringKeys":[],"requiresPartitionFilter":null
            }"""),
            resource("CONNECTOR_CATALOG_SELECTION", "$CATALOG_PATH/catalog-selection", """{"selectedCatalogNames":["warehouse"]}"""),
            resource("CATALOG_SELECTION", "$CATALOG_PATH/catalogs/warehouse/selection", """{"selectedSchemas":[]}"""),
            resource("CATALOG_SELECTION_OPTION", "$CATALOG_PATH/catalog-selection/options/catalogs/warehouse", """{"optionKind":"CATALOG"}"""),
            resource("CATALOG_SELECTION_OPTION_REFRESH", "$CATALOG_PATH/catalog-selection/option-refreshes/catalogs/$OTHER_ID", """{"state":"RUNNING","requestedAt":"$TIME"}"""),
            resource("CATALOG_INGESTION", "$CATALOG_PATH/ingestions/$OTHER_ID", """{
                "state":"RUNNING","requestedAt":"$TIME","refreshedRelationCount":0,
                "runningTaskCount":0,"errorCount":0,"runningTargets":[],
                "requestedBy":{"principalId":"$ID","displayName":"Mina Park","credentialSurface":"AGENT","agentApp":$CLAUDE_APP}
            }"""),
            resource("CATALOG_INGESTION_FAILURE", "$CATALOG_PATH/ingestions/$OTHER_ID/failures/$ID", """{
                "target":{"kind":"RELATION","path":"$CATALOG_PATH/catalogs/warehouse/schemas/analytics/relations/orders"},
                "failureCode":"EXECUTION_FAILED","message":"The relation could not be read."
            }"""),
            resource("SEMANTIC_MODEL", "workspace/semantic-models/orders", MODEL.dropLast(1) + ""","sourcePartitionInfo":[]}""") + ("updatedAt" to TIME),
            resource("METRIC", METRIC_PATH, METRIC.dropLast(1) + ""","sourcePartitionInfo":[]}""") + mapOf(
                "sourceConnectors" to emptyList<Any>(),
                "updatedAt" to TIME, "updatedBy" to json("""{"id":"$ID","displayName":null}"""),
                "credentialSurface" to "AGENT", "agentApp" to json(CLAUDE_APP),
            ),
            resource("EXPLORE_RUN", EXPLORE_PATH, """{
                "runKind":"SQL","connectorId":"$ID","state":"RUNNING","sql":"select 1","createdAt":"$TIME","updatedAt":"$TIME"
            }"""),
            resource("EXPLORE_RUN", EXPLORE_PATH, """{
                "runKind":"SQL","connectorId":"$ID","state":"RUNNING","sql":"select 1","createdAt":"$TIME","updatedAt":"$TIME",
                "credentialSurface":"AGENT","agentApp":$CLAUDE_APP
            }"""),
            resource("EXPLORE_RESULT", "$EXPLORE_PATH/result", RESULT.dropLast(1) + ""","expiresAt":"$TIME"}"""),
            resource("DASHBOARD", DASHBOARD_PATH, """{"definition":$DASHBOARD,"createdAt":"$TIME","updatedAt":"$TIME"}"""),
            resource("DASHBOARD", DASHBOARD_PATH, """{
                "definition":$DASHBOARD,"createdAt":"$TIME","updatedAt":"$TIME",
                "updatedBy":{"id":"$ID","displayName":"Mina Park"},"credentialSurface":"WEB"
            }"""),
            dashboardDraft(),
            resource("DASHBOARD_DRAFT", "$DASHBOARD_PATH/draft", """{
                "definition":$DASHBOARD,"parameterBindings":{},"variables":{},
                "targetQueryRuns":{},"readyResults":{},"createdAt":"$TIME","updatedAt":"$TIME",
                "lastEditedBy":{"id":"$ID","displayName":"Mina Park"},"lastEditedAt":"$TIME",
                "lastEditSurface":"AGENT","lastEditAgentApp":$CLAUDE_APP
            }""", "1"),
            resource("DASHBOARD_SESSION", SESSION_PATH, """{
                "definition":$DASHBOARD,"parameterBindings":{"region":"US"},"initialVariables":{"selection":"US"},
                "runningQueryRuns":[{"queryName":"orders","path":"$SESSION_PATH/query-runs/$OTHER_ID","state":"RUNNING"}]
            }"""),
            resource("DASHBOARD_QUERY_RUN", DRAFT_RUN_PATH, """{
                "queryName":"orders","queryType":"SEMANTIC","connectorId":"$ID","state":"RUNNING","outputColumnNames":[],
                "createdAt":"$TIME","updatedAt":"$TIME"
            }"""),
            resource("DASHBOARD_QUERY_RESULT", "$DRAFT_RUN_PATH/result", DASHBOARD_RESULT.dropLast(1) + ""","queryType":"SEMANTIC"}"""),
        )

        resources.forEach { response ->
            val actual = runCatching { validator.agentResource(response) }.getOrElse { failure ->
                throw AssertionError("Resource ${response["kind"]} at ${response["path"]} must satisfy its owner schema", failure)
            }
            assertEquals(response, actual)
        }
    }

    @Test
    fun `factored metric read and partition guidance remain closed and bounded`() {
        val read = resource("METRIC", METRIC_PATH, METRIC.dropLast(1) + ""","sourcePartitionInfo":[]}""") +
            mapOf("sourceConnectors" to emptyList<Any>(), "updatedAt" to TIME)
        assertEquals(read, validator.agentResource(read))
        val body = (read.getValue("body") as Map<*, *>).mapKeys { it.key as String }
        for (extra in listOf("unknown", "numerator")) {
            assertFailsWith<IllegalStateException> { validator.agentResource(read + ("body" to (body + (extra to "unexpected")))) }
        }
        val core = mapOf("connectorId" to ID, "relation" to mapOf("catalog" to null, "schema" to "analytics", "name" to "orders"), "partitionKeys" to listOf("order_date"))
        val model = core + mapOf("kind" to "SEMANTIC_MODEL", "semanticModelName" to "orders", "dimensions" to listOf("order_date"), "timeRangeApplies" to true)
        val spine = core + ("kind" to "TIME_SPINE")
        val problem = mapOf("type" to "https://monomer.cc/problems/partition-filter-required", "title" to "Conflict", "status" to 409,
            "detail" to "BigQuery needs a partition filter.", "instance" to "/mcp", "code" to "PARTITION_FILTER_REQUIRED", "retryable" to false,
            "requestId" to "srv_partition", "traceId" to "srv_partition", "partitionFilters" to listOf(model, spine))
        assertEquals(problem, validator.problem(problem))
        for (filters in listOf(List(17) { model }, listOf(spine + ("semanticModelName" to "orders")), listOf(model - "timeRangeApplies"))) {
            assertFailsWith<IllegalStateException> { validator.problem(problem + ("partitionFilters" to filters)) }
        }
    }

    @Test
    fun `Dashboard result columns require nullability while Explore result columns keep their shape`() {
        val dashboardResult = resource("DASHBOARD_QUERY_RESULT", "$DRAFT_RUN_PATH/result", DASHBOARD_RESULT.dropLast(1) + ""","queryType":"SEMANTIC"}""")
        assertEquals(dashboardResult, validator.agentResource(dashboardResult))
        for (columns in listOf(RESULT, DASHBOARD_RESULT.replace("\"nullable\":true", "\"nullable\":\"true\""))) {
            assertFailsWith<IllegalStateException>(columns) {
                validator.agentResource(resource("DASHBOARD_QUERY_RESULT", "$DRAFT_RUN_PATH/result", columns.dropLast(1) + ""","queryType":"SEMANTIC"}"""))
            }
        }
        assertFailsWith<IllegalStateException> { validator.agentResource(resource("EXPLORE_RESULT", "$EXPLORE_PATH/result", DASHBOARD_RESULT.dropLast(1) + ""","expiresAt":"$TIME"}""")) }
        for (zone in listOf(""",""", ""","reportingTimeZone":"",""", ""","reportingTimeZone":7,""")) {
            val withoutValidZone = DASHBOARD_RESULT.replace(""","reportingTimeZone":"Asia/Seoul"""", "").dropLast(1) + zone + """"queryType":"SEMANTIC"}"""
            assertFailsWith<IllegalStateException>(zone) {
                validator.agentResource(resource("DASHBOARD_QUERY_RESULT", "$DRAFT_RUN_PATH/result", withoutValidZone))
            }
        }
    }

    @Test
    fun `provenance keeps the Web shapes and rejects anything else`() {
        val dashboard = """"definition":$DASHBOARD,"createdAt":"$TIME","updatedAt":"$TIME""""
        listOf(
            """{$dashboard,"credentialSurface":"MCP"}""",
            """{$dashboard,"updatedBy":{"id":"$ID"}}""",
            """{$dashboard,"updatedBy":{"id":"$ID","displayName":"Mina Park","email":"mina@example.com"}}""",
            """{$dashboard,"agentApp":{"clientId":"https://claude.ai/oauth/mcp-oauth-client-metadata","name":"Claude"}}""",
            """{$dashboard,"agentApp":null}""",
        ).forEach { body ->
            assertFailsWith<IllegalStateException>(body) { validator.agentResource(resource("DASHBOARD", DASHBOARD_PATH, body)) }
        }
        assertFailsWith<IllegalStateException> { validator.agentResource(resource("METRIC", METRIC_PATH, METRIC)) }
        assertFailsWith<IllegalStateException> {
            validator.agentResource(resource("CATALOG_INGESTION", "$CATALOG_PATH/ingestions/$OTHER_ID", """{
                "state":"RUNNING","requestedAt":"$TIME","refreshedRelationCount":0,
                "runningTaskCount":0,"errorCount":0,"runningTargets":[]
            }"""))
        }
    }

    @Test
    fun `Explore history has strict SQL and semantic branches independently of terminal state`() {
        val query = json("""{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}""")
        for (runKind in listOf("SQL", "SEMANTIC")) {
            for (state in listOf("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")) {
                val body = buildMap<String, Any?> {
                    putAll(mapOf("runKind" to runKind, "connectorId" to ID, "state" to state,
                        "createdAt" to TIME, "updatedAt" to TIME, "sql" to "select '서울🙂'"))
                    if (runKind == "SEMANTIC") put("query", query)
                    if (state in listOf("FAILED", "CANCELLED")) {
                        put("failureCode", if (state == "FAILED") "EXECUTION_FAILED" else "CANCELLED_BY_ACTOR")
                        put("failureDetail", "The run did not succeed.")
                    }
                }
                val envelope = resource("EXPLORE_RUN", EXPLORE_PATH) + ("body" to body)
                assertEquals(envelope, validator.agentResource(envelope))
                val invalidBodies = listOf(
                    body - "sql", body + ("sql" to null), body + ("parameters" to emptyMap<String, Any?>()),
                    body + ("renderedSql" to "select 1"), body + ("runKind" to "RAW_SQL"),
                    body + ("query" to null), body + ("query" to emptyMap<String, Any?>()),
                    if (runKind == "SQL") body + ("query" to query) else body - "query",
                    if (state in listOf("FAILED", "CANCELLED")) body - "failureCode" else body + ("failureCode" to "EXECUTION_FAILED"),
                    if (state in listOf("FAILED", "CANCELLED")) body - "failureDetail" else body + ("failureDetail" to "Unexpected failure"),
                )
                invalidBodies.forEach { invalid ->
                    assertFailsWith<IllegalStateException> { validator.agentResource(envelope + ("body" to invalid)) }
                }
            }
        }
        val body = mapOf("runKind" to "SEMANTIC", "connectorId" to ID, "state" to "SUCCEEDED",
            "createdAt" to TIME, "updatedAt" to TIME, "sql" to "x".repeat(2_097_152), "query" to query)
        val envelope = resource("EXPLORE_RUN", EXPLORE_PATH) + ("body" to body)
        assertEquals(envelope, validator.agentResource(envelope))
        assertFailsWith<IllegalStateException> {
            validator.agentResource(envelope + ("body" to body + ("sql" to "x".repeat(2_097_153))))
        }
    }

    @Test
    fun `catalog root entry and schema are bodyless resources`() {
        listOf(
            resource("CATALOG_ROOT", CATALOG_PATH),
            resource("CATALOG", "$CATALOG_PATH/catalogs/warehouse"),
            resource("SCHEMA", "$CATALOG_PATH/catalogs/warehouse/schemas/analytics"),
        ).forEach { response ->
            assertEquals(response, validator.agentResource(response))
            assertFailsWith<IllegalStateException> { validator.agentResource(response + ("body" to emptyMap<String, Any?>())) }
            assertFailsWith<IllegalStateException> { validator.agentResource(response + ("revision" to "1")) }
        }
    }

    @Test
    fun `resource kind fixes required body path and revision`() {
        val metric = resource("METRIC", METRIC_PATH, METRIC)
        listOf(
            metric - "body",
            metric + ("revision" to "1"),
            metric + ("body" to json(MODEL)),
            metric + ("kind" to "SEMANTIC_MODEL"),
            metric + ("path" to "workspace/semantic-models/revenue"),
            metric + ("revision" to "0"),
            resource("EXPLORE_RUN", EXPLORE_PATH, METRIC),
            resource("DASHBOARD", DASHBOARD_PATH, """{"definition":$DASHBOARD,"createdAt":"$TIME","updatedAt":"$TIME"}""", "1"),
            dashboardDraft() - "revision",
        ).forEach { response -> assertFailsWith<IllegalStateException>(response.toString()) { validator.agentResource(response) } }
    }

    @Test
    fun `resource owner refs reject malformed semantic and dashboard definitions`() {
        listOf(
            resource("METRIC", METRIC_PATH, METRIC.replace("\"simple\"", "\"BANANA\"")),
            resource("SEMANTIC_MODEL", "workspace/semantic-models/orders", MODEL.replace("\"sum\"", "\"BANANA\"")),
            resource("DASHBOARD", DASHBOARD_PATH, """{"definition":{},"createdAt":"$TIME","updatedAt":"$TIME"}"""),
            resource("DASHBOARD", DASHBOARD_PATH, """{
                "definition":${DASHBOARD.replace("\"rowLimit\":100", "\"rowLimit\":\"invalid\"")},
                "createdAt":"$TIME","updatedAt":"$TIME"
            }"""),
        ).forEach { response -> assertFailsWith<IllegalStateException> { validator.agentResource(response) } }
    }

    @Test
    fun `dashboard resource definitions reject former item nodes and malformed widget references`() {
        val canonicalNode = """"type":"WIDGET","id":"chartItem","widgetId":"chart""""
        val invalidNodes = listOf(
            """"type":"ITEM","id":"chartItem","item":{"kind":"WIDGET","id":"chart"}""",
            """"type":"WIDGET","id":"chartItem"""",
            """"type":"WIDGET","id":"chartItem","widgetId":null""",
            """"type":"WIDGET","id":"chartItem","widgetId":1""",
            """"type":"WIDGET","id":"chartItem","widgetId":"bad reference"""",
            """"type":"WIDGET","id":"chartItem","widgetId":{"id":"chart"}""",
            """"type":"WIDGET","id":"chartItem","widgetId":"chart","item":{"kind":"WIDGET","id":"chart"}""",
        )
        invalidNodes.forEach { node ->
            val definition = DASHBOARD.replace(canonicalNode, node)
            val resources = listOf(
                resource("DASHBOARD", DASHBOARD_PATH, """{"definition":$definition,"createdAt":"$TIME","updatedAt":"$TIME"}"""),
                dashboardDraft().let { it + ("body" to ((it.getValue("body") as Map<*, *>) + ("definition" to json(definition)))) },
                resource("DASHBOARD_SESSION", SESSION_PATH, """{
                    "definition":$definition,"parameterBindings":{},"initialVariables":{},"runningQueryRuns":[]
                }"""),
            )
            resources.forEach { response ->
                assertFailsWith<IllegalStateException> { validator.agentResource(response) }
            }
        }
    }

    @Test
    fun `listing variants accept owner fields and reject cross kind fields`() {
        val metric = json("""{"kind":"METRIC","path":"$METRIC_PATH","name":"revenue","sourceConnectors":[]}""")
        val run = json("""{
            "kind":"EXPLORE_RUN","path":"$EXPLORE_PATH","runKind":"SQL","connectorId":"$ID",
            "state":"RUNNING","createdAt":"$TIME","updatedAt":"$TIME"
        }""")
        val dashboardRun = json("""{
            "kind":"DASHBOARD_QUERY_RUN","path":"$DRAFT_RUN_PATH","queryName":"orders","queryType":"SEMANTIC","connectorId":"$ID",
            "state":"RUNNING","createdAt":"$TIME","updatedAt":"$TIME"
        }""")
        listOf("sql" to "select 1", "query" to emptyMap<String, Any?>(), "parameters" to emptyMap<String, Any?>()).forEach { extra ->
            assertFailsWith<IllegalStateException> { validator.agentListing(listing(run + extra)) }
        }
        val otherOwnerItems = listOf(
            """{"kind":"COLLECTION","path":"workspace/metrics","name":"metrics"}""",
            """{"kind":"SEMANTIC_MODEL","path":"workspace/semantic-models/orders","name":"orders"}""",
            """{"kind":"CATALOG","path":"$CATALOG_PATH/catalogs/warehouse","name":"warehouse"}""",
            """{"kind":"SCHEMA","path":"$CATALOG_PATH/catalogs/warehouse/schemas/analytics","name":"analytics"}""",
            """{"kind":"RELATION","path":"$CATALOG_PATH/catalogs/warehouse/schemas/analytics/relations/orders","name":"orders","relationKind":"TABLE"}""",
            """{"kind":"CATALOG_SELECTION_OPTION","path":"$CATALOG_PATH/catalog-selection/options/catalogs/warehouse","name":"warehouse"}""",
            """{"kind":"CATALOG_SELECTION_OPTION","path":"$CATALOG_PATH/catalogs/warehouse/selection/options/schemas/analytics","name":"analytics","optionKind":"SCHEMA","available":true}""",
            """{"kind":"CATALOG_SELECTION_OPTION","path":"$CATALOG_PATH/catalogs/warehouse/selection/options/schemas/analytics/relations/orders","name":"orders","optionKind":"RELATION","available":true,"relationKind":"TABLE"}""",
            """{"kind":"CATALOG_SELECTION_OPTION_REFRESH","path":"$CATALOG_PATH/catalog-selection/option-refreshes/catalogs/$OTHER_ID","state":"RUNNING","requestedAt":"$TIME"}""",
            """{"kind":"CATALOG_INGESTION","path":"$CATALOG_PATH/ingestions/$OTHER_ID","state":"RUNNING","requestedAt":"$TIME"}""",
            """{"kind":"CATALOG_INGESTION_FAILURE","path":"$CATALOG_PATH/ingestions/$OTHER_ID/failures/$ID"}""",
            """{"kind":"DASHBOARD","path":"$DASHBOARD_PATH","title":"Revenue","updatedAt":"$TIME"}""",
            """{"kind":"DASHBOARD_SESSION","path":"$SESSION_PATH","parameterBindings":{"region":"US"},
                "runningQueryRuns":[{"queryName":"orders","path":"$SESSION_PATH/query-runs/$ID","state":"RUNNING"}],"lastQueryRunAt":null,
                "createdAt":"$TIME","updatedAt":"$TIME"}""",
        ).map(::json)
        (listOf(metric, run, dashboardRun) + otherOwnerItems).forEach { item ->
            val response = listing(item)
            val actual = runCatching { validator.agentListing(response) }.getOrElse { failure ->
                throw AssertionError("Listing item ${item["kind"]} at ${item["path"]} must satisfy its owner schema", failure)
            }
            assertEquals(response, actual)
        }
        listOf(
            metric - "name",
            metric + ("state" to "BANANA"),
            metric + ("queryName" to "orders"),
            metric + ("path" to "workspace/semantic-models/revenue"),
            run + ("state" to "BANANA"),
            run + ("runKind" to "BANANA"),
            dashboardRun - "queryName",
            dashboardRun + ("state" to "BANANA"),
        ).forEach { item -> assertFailsWith<IllegalStateException>(item.toString()) { validator.agentListing(listing(item)) } }
    }

    @Test
    fun `available operations use exact implemented action path and input variants`() {
        listOf(
            """{"operation":"CREATE","path":"workspace/metrics","inputKind":"METRIC_DEFINITION"}""",
            """{"operation":"EDIT","path":"$METRIC_PATH"}""",
            """{"operation":"INVOKE","path":"workspace/explore/runs","action":"run","inputKind":"SQL_EXPLORE_RUN"}""",
            """{"operation":"INVOKE","path":"workspace/explore/runs","action":"run","inputKind":"SEMANTIC_EXPLORE_RUN"}""",
            """{"operation":"INVOKE","path":"$EXPLORE_PATH","action":"cancel"}""",
            """{"operation":"INVOKE","path":"$DASHBOARD_PATH/draft","action":"refresh","inputKind":"DASHBOARD_QUERY_REFRESH"}""",
            """{"operation":"INVOKE","path":"$SESSION_PATH","action":"refresh","inputKind":"DASHBOARD_SESSION_REFRESH"}""",
            """{"operation":"INVOKE","path":"$DASHBOARD_PATH/sessions","action":"open","inputKind":"DASHBOARD_SESSION_OPEN"}""",
        ).forEach { operation -> validator.agentListing(operations(operation)) }

        listOf(
            """{"operation":"INVOKE","path":"workspace/explore/runs","inputKind":"SQL_EXPLORE_RUN"}""",
            """{"operation":"INVOKE","path":"workspace/explore/runs","action":"run"}""",
            """{"operation":"INVOKE","path":"workspace/explore/runs","action":"run","inputKind":"DASHBOARD_QUERY_REFRESH"}""",
            """{"operation":"INVOKE","path":"$EXPLORE_PATH","action":"cancel","inputKind":"SQL_EXPLORE_RUN"}""",
            """{"operation":"INVOKE","path":"$METRIC_PATH","action":"run","inputKind":"SQL_EXPLORE_RUN"}""",
            """{"operation":"INVOKE","path":"$DASHBOARD_PATH/sessions","action":"refresh","inputKind":"DASHBOARD_QUERY_REFRESH"}""",
            """{"operation":"CREATE","path":"workspace/metrics","inputKind":"SEMANTIC_MODEL_DEFINITION"}""",
            """{"operation":"EDIT","path":"$METRIC_PATH","inputKind":"JSON_PATCH"}""",
            """{"operation":"EDIT","path":"$METRIC_PATH","action":"apply"}""",
            """{"operation":"READ","path":"$METRIC_PATH","inputKind":"METRIC_DEFINITION"}""",
        ).forEach { operation -> assertFailsWith<IllegalStateException>(operation) { validator.agentListing(operations(operation)) } }
    }

    @Test
    fun `action results follow actual owner operation status and result combinations`() {
        listOf(
            action("CREATE", "workspace/metrics", null, "COMPLETED", "METRIC", METRIC_PATH),
            action("CREATE", "workspace/semantic-models", null, "COMPLETED", "SEMANTIC_MODEL", "workspace/semantic-models/orders"),
            action("CREATE", "workspace/dashboards", null, "COMPLETED", "DASHBOARD", DASHBOARD_PATH) + mapOf("webUrl" to "https://app.monomer.cc/workspaces/$OTHER_ID/dashboards/$OTHER_ID"),
            action("EDIT", METRIC_PATH, null, "COMPLETED", "METRIC", METRIC_PATH),
            action("EDIT", "workspace/semantic-models/orders", null, "COMPLETED", "SEMANTIC_MODEL", "workspace/semantic-models/orders"),
            action("EDIT", "$DASHBOARD_PATH/draft", null, "COMPLETED", "DASHBOARD_DRAFT", "$DASHBOARD_PATH/draft") + mapOf("webUrl" to "https://app.monomer.cc/workspaces/$OTHER_ID/dashboards/$OTHER_ID"),
            action("EDIT", "$CATALOG_PATH/catalog-selection", null, "COMPLETED", "CONNECTOR_CATALOG_SELECTION", "$CATALOG_PATH/catalog-selection"),
            action("EDIT", "$CATALOG_PATH/catalogs/warehouse/selection", null, "COMPLETED", "CATALOG_SELECTION", "$CATALOG_PATH/catalogs/warehouse/selection"),
            action("INVOKE", "workspace/explore/runs", "run", "ACCEPTED", "EXPLORE_RUN", EXPLORE_PATH),
            action("INVOKE", "workspace/explore/runs", "run", "COMPLETED", "EXPLORE_RUN", EXPLORE_PATH),
            action("INVOKE", EXPLORE_PATH, "cancel", "ACCEPTED", "EXPLORE_RUN", EXPLORE_PATH),
            action("INVOKE", EXPLORE_PATH, "cancel", "COMPLETED", "EXPLORE_RUN", EXPLORE_PATH),
            action("INVOKE", "$DASHBOARD_PATH/draft", "start", "ACCEPTED", "DASHBOARD_DRAFT", "$DASHBOARD_PATH/draft"),
            action("INVOKE", "$DASHBOARD_PATH/draft", "apply", "COMPLETED", "DASHBOARD", DASHBOARD_PATH) + mapOf("webUrl" to "https://app.monomer.cc/workspaces/$OTHER_ID/dashboards/$OTHER_ID"),
            action("INVOKE", "$DASHBOARD_PATH/draft", "refresh", "ACCEPTED", "DASHBOARD_DRAFT", "$DASHBOARD_PATH/draft"),
            action("INVOKE", "$DASHBOARD_PATH/sessions", "open", "ACCEPTED", "DASHBOARD_SESSION", SESSION_PATH),
            action("INVOKE", SESSION_PATH, "refresh", "ACCEPTED", "DASHBOARD_SESSION", SESSION_PATH) + mapOf("variables" to emptyMap<String, Any?>(), "queryRuns" to emptyMap<String, Any?>()),
            action("INVOKE", DRAFT_RUN_PATH, "cancel", "ACCEPTED", "DASHBOARD_QUERY_RUN", DRAFT_RUN_PATH),
            action("INVOKE", CATALOG_PATH, "ingest", "ACCEPTED", "CATALOG_INGESTION", "$CATALOG_PATH/ingestions/$OTHER_ID"),
            action("INVOKE", "$CATALOG_PATH/catalogs/warehouse", "ingest", "ACCEPTED", "CATALOG_INGESTION", "$CATALOG_PATH/catalogs/warehouse/ingestions/$OTHER_ID"),
            action("INVOKE", "$CATALOG_PATH/ingestions/$OTHER_ID", "cancel", "ACCEPTED", "CATALOG_INGESTION", "$CATALOG_PATH/ingestions/$OTHER_ID"),
            action("INVOKE", "$CATALOG_PATH/catalog-selection/options/catalogs", "refresh", "ACCEPTED", "CATALOG_SELECTION_OPTION_REFRESH", "$CATALOG_PATH/catalog-selection/option-refreshes/catalogs/$OTHER_ID"),
            action("INVOKE", "$CATALOG_PATH/catalog-selection/option-refreshes/catalogs/$OTHER_ID", "cancel", "ACCEPTED", "CATALOG_SELECTION_OPTION_REFRESH", "$CATALOG_PATH/catalog-selection/option-refreshes/catalogs/$OTHER_ID"),
        ).forEach { response -> assertEquals(response, validator.agentAction(response)) }

        listOf(
            action("CREATE", "workspace/metrics", null, "ACCEPTED", "METRIC", METRIC_PATH),
            action("CREATE", "workspace/metrics", "run", "COMPLETED", "METRIC", METRIC_PATH),
            action("CREATE", "workspace/metrics", null, "COMPLETED", "SEMANTIC_MODEL", "workspace/semantic-models/orders"),
            action("EDIT", METRIC_PATH, null, "ACCEPTED", "METRIC", METRIC_PATH),
            action("EDIT", METRIC_PATH, "apply", "COMPLETED", "METRIC", METRIC_PATH),
            action("INVOKE", "workspace/explore/runs", null, "ACCEPTED", "EXPLORE_RUN", EXPLORE_PATH),
            action("INVOKE", "workspace/explore/runs", "run", "ACCEPTED", "METRIC", METRIC_PATH),
            action("INVOKE", "workspace/explore/runs", "run", "ACCEPTED", "EXPLORE_RUN", METRIC_PATH),
            action("INVOKE", "$DASHBOARD_PATH/draft", "apply", "ACCEPTED", "DASHBOARD", DASHBOARD_PATH),
            action("INVOKE", "$DASHBOARD_PATH/draft", "refresh", "ACCEPTED", "DASHBOARD_SESSION", SESSION_PATH),
            action("INVOKE", CATALOG_PATH, "refresh", "ACCEPTED", "CATALOG_INGESTION", "$CATALOG_PATH/ingestions/$OTHER_ID"),
        ).forEach { response -> assertFailsWith<IllegalStateException>(response.toString()) { validator.agentAction(response) } }
    }

    @Test
    fun `dashboard dynamic bindings preserve names and validate literal values`() {
        val accepted = """{
            "region":"US","enabled":true,"nullable":null,"regions":["US","CA"],
            "window":{"start":"2026-09-01","end":"2026-09-06"}
        }"""
        validator.agentResource(dashboardDraft(accepted, accepted))
        listOf("""{"region":{"unknown":true}}""", """{"region":17}""", """{"region":[{"unknown":true}]}""").forEach { bindings ->
            assertFailsWith<IllegalStateException> { validator.agentResource(dashboardDraft(bindings, "{}")) }
            assertFailsWith<IllegalStateException> { validator.agentResource(dashboardDraft("{}", bindings)) }
        }
    }

    @Test
    fun `session listing requires nullable last query run creation time`() {
        val item = json("""{"kind":"DASHBOARD_SESSION","path":"$SESSION_PATH","parameterBindings":{},"runningQueryRuns":[],"createdAt":"$TIME","updatedAt":"$TIME","lastQueryRunAt":null}""")
        validator.agentListing(listing(item))
        validator.agentListing(listing(item + ("lastQueryRunAt" to TIME)))
        assertFailsWith<IllegalStateException> { validator.agentListing(listing(item - "lastQueryRunAt")) }
        assertFailsWith<IllegalStateException> { validator.agentListing(listing(item + ("lastQueryRunAt" to 1))) }
    }

    private fun json(value: String): Map<String, Any?> = objectMapper.readValue(value, object : TypeReference<Map<String, Any?>>() {})

    private fun resource(kind: String, path: String, body: String? = null, revision: String? = null): Map<String, Any?> = buildMap {
        put("requestId", "contract-test")
        put("kind", kind)
        put("path", path)
        body?.let { put("body", json(it)) }
        revision?.let { put("revision", it) }
    }

    private fun dashboardDraft(parameterBindings: String = "{}", variables: String = "{}"): Map<String, Any?> = resource(
        "DASHBOARD_DRAFT", "$DASHBOARD_PATH/draft", """{
            "definition":$DASHBOARD,"parameterBindings":$parameterBindings,"variables":$variables,
            "targetQueryRuns":{"orders":"$DRAFT_RUN_PATH"},"readyResults":{},"createdAt":"$TIME","updatedAt":"$TIME"
        }""", "1",
    )

    private fun listing(item: Map<String, Any?>): Map<String, Any?> = mapOf("requestId" to "contract-test", "items" to listOf(item))

    private fun operations(operation: String): Map<String, Any?> = mapOf(
        "requestId" to "contract-test", "items" to emptyList<Any>(), "availableOperations" to listOf(json(operation)),
    )

    private fun action(operation: String, path: String, action: String?, status: String, resultKind: String, resultPath: String): Map<String, Any?> = buildMap {
        put("requestId", "contract-test")
        put("operation", operation)
        put("path", path)
        action?.let { put("action", it) }
        put("status", status)
        put("result", mapOf("kind" to resultKind, "path" to resultPath))
    }

    private companion object {
        const val ID = "01942b04-6e00-7000-8000-000000000010"
        const val OTHER_ID = "01942b04-6e00-7000-8000-000000000011"
        const val TIME = "2026-09-06T00:00:00Z"
        const val CLAUDE_APP = """{"clientId":"https://claude.ai/oauth/mcp-oauth-client-metadata","name":"Claude","verifiedHost":"claude.ai"}"""
        const val METRIC_PATH = "workspace/metrics/revenue"
        const val CATALOG_PATH = "workspace/connectors/$ID/catalog"
        const val EXPLORE_PATH = "workspace/explore/runs/$ID"
        const val DASHBOARD_PATH = "workspace/dashboards/$ID"
        const val SESSION_PATH = "$DASHBOARD_PATH/sessions/$OTHER_ID"
        const val DRAFT_RUN_PATH = "$DASHBOARD_PATH/draft/query-runs/$OTHER_ID"
        const val METRIC = """{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","measure":"revenue"}"""
        const val MODEL = """{
            "schemaVersion":3,"name":"orders","sourceRelations":[{"connectorId":"$ID","catalog":"warehouse","schema":"analytics","relation":"orders"}],
            "entities":[{"name":"order","type":"primary","expression":"order_id"}],
            "dimensions":[],"measures":[{"name":"revenue","aggregation":"sum","expression":"revenue"}]
        }"""
        const val RESULT = """{"columns":[{"name":"revenue","logicalType":"DECIMAL","display":null}],"rows":[["12.5"]],"totalRowCount":"1","returnedRowCount":1,"truncated":false,"reportingTimeZone":null}"""
        const val DASHBOARD_RESULT = """{"columns":[{"name":"revenue","logicalType":"DECIMAL","nullable":true,"display":null}],"rows":[["12.5"]],"totalRowCount":"1","returnedRowCount":1,"truncated":false,"reportingTimeZone":"Asia/Seoul"}"""
        const val DASHBOARD = """{
            "schemaVersion":1,"meta":{"title":"Revenue"},
            "parameters":{"region":{"type":"TEXT","nullable":false}},
            "variables":{"selection":{"type":"TEXT","nullable":false,"initial":"US"}},
            "queries":{"orders":{"type":"SEMANTIC","connectorId":"$ID","query":{
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
        }"""
    }
}
