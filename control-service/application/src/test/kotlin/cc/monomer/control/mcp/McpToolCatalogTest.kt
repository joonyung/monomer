package cc.monomer.control.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolCatalogTest {
    private val mapper = ObjectMapper()
    private val tools = McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val workspaceId = "01942b04-6e00-7000-8000-000000000001"

    private val catalog = McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl)

    private fun schema(tool: String) = mapper.valueToTree<JsonNode>(tools.single { it["name"] == tool }.getValue("inputSchema"))

    private fun describedInput(operation: String, path: String, inputKind: String?): JsonNode {
        val descriptor = mapper.createObjectNode().put("operation", operation).put("path", path)
        inputKind?.let { descriptor.put("inputKind", it) }
        val described = catalog.describe(mapper.createObjectNode().set<ObjectNode>("operations", mapper.createArrayNode().add(descriptor)))
        return (described.at("/operations/0/argumentsSchema").deepCopy<JsonNode>() as ObjectNode).apply { set<JsonNode>("\$defs", described.path("\$defs")) }
    }

    @Test
    fun `ten tools have minimal envelopes and conservative annotations without schema description copies`() {
        assertEquals(listOf("list", "read", "search", "docs", "describe", "show", "check", "create", "edit", "invoke").map { "monomer_$it" }, tools.map { it["name"] })
        tools.forEach { tool ->
            val name = tool.getValue("name").toString()
            val readOnly = name !in setOf("monomer_create", "monomer_edit", "monomer_invoke")
            val annotations = tool.getValue("annotations") as Map<*, *>
            assertEquals(readOnly, annotations["readOnlyHint"], name)
            assertEquals(readOnly, annotations["idempotentHint"], name)
            assertEquals(!readOnly, annotations["destructiveHint"], name)
            assertEquals(name == "monomer_invoke", annotations["openWorldHint"], name)
            val description = tool.getValue("description").toString()
            assertEquals(name != "monomer_docs", description.contains("https://monomer.cc/en/docs/reference/agent-api/"), name)
            assertEquals(name in setOf("monomer_create", "monomer_edit", "monomer_invoke", "monomer_check"),
                description.contains("Get the exact arguments with monomer_describe first."), name)
            assertTrue(!tool.getValue("description").toString().contains("\"properties\""), name)
            assertEquals(name !in setOf("monomer_docs", "monomer_describe"), tool.containsKey("outputSchema"))
            if (tool.containsKey("outputSchema")) assertTrue(!mapper.valueToTree<JsonNode>(tool.getValue("outputSchema")).has("\$defs"))
        }
        assertEquals(true, schema("monomer_edit").path("additionalProperties").asBoolean())
        assertEquals(mapper.readTree("{\"type\":\"object\"}"), schema("monomer_create").at("/properties/input"))
    }

    @Test
    fun `each tool description carries the shared guidance that tool needs without initialize instructions`() {
        val descriptions = tools.associate { it.getValue("name").toString() to it.getValue("description").toString() }
        val targetRule = "Pass workspaceId for workspace paths or organizationId for organization paths, never both."
        val writeFollowUp = "Afterwards, confirm result.path with monomer_read, or monomer_list for a COLLECTION."
        val requiredGuidance = mapOf(
            "monomer_list" to listOf("{\"path\":\"workspaces\"}", "{\"path\":\"organizations\"}", "no other fields", "then call monomer_read with path workspace",
                "workspaceId for workspace paths or organizationId for organization paths (never both)", "integer limit (1–100)"),
            "monomer_read" to listOf(targetRule, "Start a Workspace at path workspace", "follow availableOperations", "After a write, read result.path for the current revision and next operations"),
            "monomer_search" to listOf("Pass workspaceId.", "Resolve names referenced inside definitions"),
            "monomer_docs" to listOf("For an unfamiliar task, start with no arguments"),
            "monomer_describe" to listOf("verbatim", "Reuse a returned schema for the same entry during this session"),
            "monomer_check" to listOf("Pass workspaceId.", "Accepts only advertised check operations"),
            "monomer_create" to listOf(targetRule, "Never guess an ambiguous target.", writeFollowUp),
            "monomer_edit" to listOf("Pass workspaceId.", "Never guess an ambiguous target.", writeFollowUp),
            "monomer_invoke" to listOf(targetRule, "Check operations belong to monomer_check", "Never guess an ambiguous target.", writeFollowUp),
        )
        requiredGuidance.forEach { (name, guidance) ->
            guidance.forEach { phrase -> assertTrue(descriptions.getValue(name).contains(phrase), "$name lacks $phrase") }
        }
        for (name in listOf("monomer_docs", "monomer_describe", "monomer_show")) {
            for (targetId in listOf("workspaceId", "organizationId")) assertTrue(!descriptions.getValue(name).contains(targetId), "$name mentions $targetId")
        }
        for (name in listOf("monomer_search", "monomer_check", "monomer_edit")) assertTrue(!descriptions.getValue(name).contains("organizationId"), name)
        descriptions.forEach { (name, description) ->
            assertEquals(name in setOf("monomer_create", "monomer_edit", "monomer_invoke"), description.contains("Never guess an ambiguous target."), name)
        }
    }

    @Test
    fun `show declares the dashboard view and accepts only Draft or Session targets`() {
        val show = tools.single { it["name"] == "monomer_show" }
        assertEquals("Show in chat", show["title"])
        assertEquals(mapper.readTree("""{"ui":{"resourceUri":"ui://monomer/dashboard-view/v1"},"ui/resourceUri":"ui://monomer/dashboard-view/v1"}"""), mapper.valueToTree<JsonNode>(show["_meta"]))
        assertEquals(mapper.readTree("""{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}"""), mapper.valueToTree<JsonNode>(show["annotations"]))
        val description = show.getValue("description").toString()
        assertTrue(description.length <= 600, "${description.length}")
        for (guidance in listOf("MCP Apps", "Monomer link", "For a Draft, call it once as soon as the Draft exists and before editing it", "variables and queryRuns", "call again after a new resolve or refresh")) {
            assertTrue(description.contains(guidance), guidance)
        }
        assertEquals(mapper.readTree("""["requestId","viewContract","workspaceId","dashboardId","title","target","webUrl","shownAt"]"""), mapper.valueToTree<JsonNode>(show["outputSchema"]).path("required"))
        val input = factory.getSchema(schema("monomer_show"))
        val dashboard = "workspace/dashboards/01942b04-6e00-7000-8000-000000000010"
        val run = "$dashboard/sessions/01942b04-6e00-7000-8000-000000000012/query-runs/01942b04-6e00-7000-8000-000000000021"
        for (valid in listOf(
            """{"workspaceId":"$workspaceId","path":"$dashboard/draft"}""",
            """{"workspaceId":"$workspaceId","path":"$dashboard/sessions/01942b04-6e00-7000-8000-000000000012","selection":{"variables":{"region":"EMEA"},"queryRuns":{"revenue":"$run"}}}""",
        )) assertTrue(input.validate(mapper.readTree(valid)).isEmpty(), valid)
        for (invalid in listOf(
            """{"path":"$dashboard/draft"}""",
            """{"workspaceId":"$workspaceId","path":"$dashboard"}""",
            """{"workspaceId":"$workspaceId","path":"$dashboard/draft/status"}""",
            """{"workspaceId":"$workspaceId","path":"$dashboard/draft","extra":true}""",
            """{"workspaceId":"$workspaceId","path":"$dashboard/draft","selection":{"variables":{}}}""",
            """{"workspaceId":"$workspaceId","path":"$dashboard/draft","selection":{"variables":{},"queryRuns":{"revenue":1}}}""",
            """{"organizationId":"$workspaceId","path":"$dashboard/draft"}""",
        )) assertTrue(input.validate(mapper.readTree(invalid)).isNotEmpty(), invalid)
    }

    @Test
    fun `edit declares every canonical top level argument with its JSON shape`() {
        val input = schema("monomer_edit")
        assertEquals(setOf("workspaceId", "organizationId", "path", "revision", "patch", "commandId", "aggregateMetrics"), input.path("properties").fieldNames().asSequence().toSet())
        for (field in listOf("patch", "aggregateMetrics")) {
            assertEquals(mapper.readTree("""{"type":"array","items":{"type":"object"}}"""), input.at("/properties/$field"))
        }
        assertEquals(mapper.readTree("""{"type":"string"}"""), input.at("/properties/commandId"))
        val revision = input.at("/properties/revision") as ObjectNode
        assertEquals(mapper.readTree("""{"type":"string","pattern":"^(?:0|[1-9][0-9]{0,18})$","maxLength":19}"""), revision.deepCopy().apply { remove("description") })
        val contractRevision = checkNotNull(javaClass.classLoader.getResourceAsStream("contracts/agent-workspace.schema.json")).use(mapper::readTree).at("/\$defs/RevisionString")
        assertEquals(contractRevision, revision.deepCopy().apply { remove("description") })
        val description = revision.path("description").asText()
        for (guidance in listOf("Decimal string copied unchanged from the READ revision", "\"3\"", "never a JSON number", "monomer_describe")) {
            assertTrue(description.contains(guidance), guidance)
        }
        assertEquals(mapper.readTree("[\"path\"]"), input.path("required"))
        assertEquals(true, input.path("additionalProperties").asBoolean())
        val validator = factory.getSchema(input)
        val draft = "\"workspaceId\":\"$workspaceId\",\"path\":\"workspace/dashboards/$workspaceId/draft\",\"patch\":[]"
        for (valid in listOf("{$draft}", "{$draft,\"revision\":\"0\"}", "{$draft,\"revision\":\"3\"}", "{$draft,\"revision\":\"9223372036854775807\"}")) {
            assertTrue(validator.validate(mapper.readTree(valid)).isEmpty(), valid)
        }
        for (invalid in listOf("3", "\"03\"", "\"\"", "\"-1\"", "\"3.0\"", "\"12345678901234567890\"", "null")) {
            assertTrue(validator.validate(mapper.readTree("{$draft,\"revision\":$invalid}")).isNotEmpty(), invalid)
        }
        for ((field, invalidValues) in mapOf("patch" to listOf("\"[]\"", "{}", "[1]"), "aggregateMetrics" to listOf("\"[]\"", "{}", "[1]"), "commandId" to listOf("1", "null", "{}"))) {
            invalidValues.forEach { value ->
                assertTrue(validator.validate(mapper.readTree("""{"workspaceId":"$workspaceId","path":"workspace/pages/tree","$field":$value}""")).isNotEmpty(), "$field=$value")
            }
        }
        tools.filter { it["name"] != "monomer_edit" }.forEach { tool -> assertTrue(!schema(tool.getValue("name").toString()).path("properties").has("revision"), tool["name"].toString()) }
    }

    @Test
    fun `tools declare every accepted canonical request field while edit paths keep exact validation`() {
        val contract = checkNotNull(javaClass.classLoader.getResourceAsStream("contracts/agent-workspace.schema.json")).use(mapper::readTree)
        fun acceptedFields(node: JsonNode): Set<String> = when {
            node.has("\$ref") -> acceptedFields(contract.at(node.path("\$ref").asText().removePrefix("#")))
            node.has("oneOf") -> node.path("oneOf").flatMap { acceptedFields(it) }.toSet()
            else -> node.path("properties").properties().filter { !it.value.isBoolean || it.value.asBoolean() }.map { it.key }.toSet()
        }
        for ((tool, request) in mapOf("list" to "List", "read" to "Read", "search" to "Search", "create" to "Create", "edit" to "Edit", "invoke" to "Invoke", "check" to "Invoke")) {
            assertEquals(acceptedFields(contract.at("/\$defs/Agent${request}Request")), schema("monomer_$tool").path("properties").fieldNames().asSequence().toSet() - setOf("workspaceId", "organizationId"), tool)
        }
        val decoder = cc.monomer.agent.application.AgentWorkspaceContractDecoder()
        val catalogEdit = mapper.readTree("""{"workspaceId":"$workspaceId","path":"workspace/connectors/$workspaceId/catalog/catalogs/monomer-public-data/selection","patch":[{"op":"replace","path":"","value":{"selectedSchemas":[]}}]}""") as ObjectNode
        val pageEdit = mapper.readTree("""{"workspaceId":"$workspaceId","path":"workspace/pages/$workspaceId","revision":"3","commandId":"edit-page","patch":[{"op":"replace","path":"/title","value":"Updated report"},{"op":"replace","path":"/description","value":null}]}""") as ObjectNode
        for (arguments in listOf(catalogEdit, pageEdit)) {
            assertTrue(factory.getSchema(schema("monomer_edit")).validate(arguments).isEmpty())
            decoder.edit(arguments.deepCopy().apply { remove("workspaceId") }.toString())
        }
        for (arguments in listOf(catalogEdit.deepCopy().apply { put("revision", "3") }, pageEdit.deepCopy().apply { remove("commandId") }, pageEdit.deepCopy().apply { put("aggregateMetrics", "[]") })) {
            assertFailsWith<cc.monomer.agent.api.AgentWorkspaceRequestDecodingException> {
                decoder.edit(arguments.apply { remove("workspaceId") }.toString())
            }
        }
    }

    @Test
    fun `search keeps its exact input and list discovery preserves pagination`() {
        val searchInput = schema("monomer_search")
        assertEquals(setOf("workspaceId", "organizationId", "path", "queries", "kinds", "limitPerQuery"), searchInput.path("properties").fieldNames().asSequence().toSet())
        assertEquals("array", searchInput.at("/properties/queries/type").asText())
        assertEquals("string", searchInput.at("/properties/queries/items/type").asText())
        assertEquals("array", searchInput.at("/properties/kinds/type").asText())
        assertEquals("integer", searchInput.at("/properties/limitPerQuery/type").asText())
        val original = factory.getSchema((searchInput.deepCopy<JsonNode>() as ObjectNode).apply { remove("properties") })
        val search = factory.getSchema(searchInput)
        for (arguments in listOf(
            """{"workspaceId":"$workspaceId","path":"workspace","queries":["revenue"],"limitPerQuery":20}""",
            """{"workspaceId":"$workspaceId","path":"workspace/pages","queries":["Reports"],"kinds":["PAGE"],"limitPerQuery":20}""",
            """{"organizationId":"$workspaceId","path":"organization","queries":["revenue"],"limitPerQuery":20}""",
            """{"workspaceId":"$workspaceId","organizationId":"$workspaceId","path":"workspace","queries":["revenue"],"limitPerQuery":20}""",
            """{"workspaceId":"$workspaceId","path":"workspace","queries":"revenue","limitPerQuery":20}""",
            """{"workspaceId":"$workspaceId","path":"workspace","queries":["revenue"],"limitPerQuery":"20"}""",
            """{"workspaceId":"$workspaceId","path":"workspace","queries":["revenue"],"limitPerQuery":50}""",
            """{"workspaceId":"$workspaceId","path":"workspace","queries":["revenue"],"limitPerQuery":20,"extra":true}""",
        )) assertEquals(original.validate(mapper.readTree(arguments)).isEmpty(), search.validate(mapper.readTree(arguments)).isEmpty(), arguments)
        assertTrue(search.validate(mapper.readTree("""{"workspaceId":"$workspaceId","path":"workspace/pages","queries":["Reports"],"kinds":["PAGE"],"limitPerQuery":20}""")).isEmpty())
        val list = factory.getSchema(schema("monomer_list"))
        for (path in listOf("organizations", "workspaces")) {
            assertTrue(list.validate(mapper.readTree("""{"path":"$path"}""")).isEmpty())
            assertTrue(list.validate(mapper.readTree("""{"path":"$path","limit":20}""")).isNotEmpty())
        }
        for ((target, path) in listOf("workspaceId" to "workspace/dashboards", "organizationId" to "organization/members")) {
            val prefix = "\"$target\":\"$workspaceId\",\"path\":\"$path\""
            assertTrue(list.validate(mapper.readTree("{$prefix}")).isNotEmpty())
            for (limit in listOf("1", "20", "100")) assertTrue(list.validate(mapper.readTree("""{$prefix,"limit":$limit}""")).isEmpty())
            for (limit in listOf("null", "0", "101", "1.5", "\"20\"")) assertTrue(list.validate(mapper.readTree("""{$prefix,"limit":$limit}""")).isNotEmpty())
        }
        assertEquals(setOf("path", "workspaceId", "organizationId", "limit", "cursor"), schema("monomer_list").path("properties").fieldNames().asSequence().toSet())
    }

    @Test
    fun `read exposes flat path and optional Arrow download while decoder keeps exact validation`() {
        val input = schema("monomer_read")
        assertEquals(setOf("workspaceId", "organizationId", "path", "download"), input.path("properties").fieldNames().asSequence().toSet())
        assertEquals(mapper.readTree("[\"path\"]"), input.path("required"))
        assertEquals(mapper.readTree("{\"type\":\"string\",\"enum\":[\"arrow\"]}"), input.at("/properties/download"))
        assertTrue(!input.has("\$defs"))
        assertTrue(input.path("oneOf").all { !it.path("properties").has("download") })
        val validator = factory.getSchema(input)
        for ((target, path, download) in listOf(
            Triple("workspaceId", "workspace", ""), Triple("organizationId", "organization", ""),
            Triple("workspaceId", "workspace/explore/runs/$workspaceId/result", ",\"download\":\"arrow\""),
        )) {
            val arguments = mapper.readTree("{\"$target\":\"$workspaceId\",\"path\":\"$path\"$download}") as ObjectNode
            assertTrue(validator.validate(arguments).isEmpty())
            cc.monomer.agent.application.AgentWorkspaceContractDecoder().read(arguments.deepCopy().apply { remove(target) }.toString())
            assertTrue(validator.validate(arguments.deepCopy().apply { remove(target) }).isNotEmpty())
            assertTrue(validator.validate(arguments.deepCopy().apply { put(if (target == "workspaceId") "organizationId" else "workspaceId", workspaceId) }).isNotEmpty())
            assertTrue(validator.validate(arguments.deepCopy().apply { put("download", "csv") }).isNotEmpty())
            assertTrue(validator.validate(arguments.deepCopy().apply { put("extra", true) }).isNotEmpty())
        }
        val nonResultDownload = mapper.readTree("{\"workspaceId\":\"$workspaceId\",\"path\":\"workspace\",\"download\":\"arrow\"}") as ObjectNode
        assertTrue(validator.validate(nonResultDownload).isEmpty())
        assertFailsWith<cc.monomer.agent.api.AgentWorkspaceRequestDecodingException> {
            cc.monomer.agent.application.AgentWorkspaceContractDecoder().read(nonResultDownload.apply { remove("workspaceId") }.toString())
        }
    }

    @Test
    fun `describe matches megabyte paths against the contract without regex stack growth`() {
        val connector = "workspace/connectors/$workspaceId/catalog"
        for ((operation, path) in listOf(
            "LIST" to "workspace" + "/a".repeat(500_000),
            "READ" to "workspace/metrics/a" + "_a".repeat(500_000),
            "READ" to "$connector/catalogs/" + "~0".repeat(500_000),
            "LIST" to "$connector/catalogs/" + "a".repeat(1_000_000) + "/selection/options/schemas",
        )) {
            val descriptor = mapper.createObjectNode().put("operation", operation).put("path", path)
            val failure = assertFailsWith<cc.monomer.agent.api.AgentWorkspaceRequestDecodingException> {
                catalog.describe(mapper.createObjectNode().set<ObjectNode>("operations", mapper.createArrayNode().add(descriptor)))
            }
            assertEquals("operations[0]", failure.violations.single().field)
        }
    }

    @Test
    fun `six connector vendor inputs retain required fields bounds and discriminated shapes`() {
        val original = factory.getSchema(catalog.schemaForRequest("create"))
        val described = factory.getSchema(describedInput("CREATE", "workspace/connectors", "CONNECTOR_CREATE"))
        val password = """{"kind":"USERNAME_PASSWORD","username":"fixture","password":"fixture"}"""
        val serviceAccount = """{"kind":"SERVICE_ACCOUNT_JSON","serviceAccountJson":"{}"}"""
        val token = """{"kind":"ACCESS_TOKEN","accessToken":"fixture"}"""
        val inputs = listOf(
            Triple("POSTGRESQL", """{"host":"warehouse.example.test","port":5432,"database":"analytics"}""", password),
            Triple("BIGQUERY", """{"projectId":"fixture-project","location":"US"}""", serviceAccount),
            Triple("SNOWFLAKE", """{"account":"fixture","warehouse":"COMPUTE_WH","database":"ANALYTICS"}""", password),
            Triple("TRINO", """{"host":"warehouse.example.test","port":443,"catalog":"analytics"}""", password),
            Triple("REDSHIFT", """{"host":"warehouse.example.test","port":5439,"database":"analytics"}""", password),
            Triple("DATABRICKS", """{"host":"fixture.cloud.databricks.com","warehouseId":"fixture"}""", token),
        ).map { (vendor, properties, credential) ->
            mapper.readTree("""{"workspaceId":"$workspaceId","path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"$vendor","displayName":"Fixture","properties":$properties,"credential":$credential}}""") as ObjectNode
        }
        fun check(input: JsonNode, accepted: Boolean) {
            val originalErrors = original.validate(input.deepCopy<ObjectNode>().apply { remove("workspaceId") })
            val describedErrors = described.validate(input)
            assertEquals(originalErrors.isEmpty(), describedErrors.isEmpty())
            assertEquals(accepted, describedErrors.isEmpty(), describedErrors.toString())
        }
        inputs.forEach { input ->
            check(input, true)
            listOf("path", "input").forEach { field -> check(input.deepCopy().apply { remove(field) }, false) }
            listOf("kind", "connectorType", "displayName", "properties", "credential").forEach { field ->
                check(input.deepCopy().apply { (path("input") as ObjectNode).remove(field) }, false)
            }
            for (field in listOf("properties", "credential")) {
                val value = input.path("input").path(field)
                value.fieldNames().forEachRemaining { requiredField ->
                    check(input.deepCopy().apply { (at("/input/$field") as ObjectNode).remove(requiredField) }, false)
                }
                check(input.deepCopy().apply { (at("/input/$field") as ObjectNode).put("unknown", true) }, false)
            }
            val wrongVendor = inputs.first { candidate ->
                candidate.at("/input/credential/kind") != input.at("/input/credential/kind") &&
                    candidate.at("/input/properties").fieldNames().asSequence().toSet() != input.at("/input/properties").fieldNames().asSequence().toSet()
            }
            for (field in listOf("properties", "credential")) {
                check(input.deepCopy().apply { (path("input") as ObjectNode).set<JsonNode>(field, wrongVendor.at("/input/$field")) }, false)
            }
        }
        val alternatives = listOf(
            "SNOWFLAKE" to """{"kind":"USERNAME_PRIVATE_KEY","username":"fixture","privateKey":"fixture"}""",
            "DATABRICKS" to """{"kind":"OAUTH_CLIENT","oauthClientId":"fixture","oauthClientSecret":"fixture"}""",
            "REDSHIFT" to """{"kind":"AWS_IAM","awsAccessKeyId":"fixture","awsSecretAccessKey":"fixture","awsRegion":"us-east-1","clusterId":"fixture","dbUser":"fixture"}""",
        )
        alternatives.forEach { (vendor, credentialJson) ->
            val credential = mapper.readTree(credentialJson)
            val valid = inputs.single { it.at("/input/connectorType").asText() == vendor }.deepCopy().apply {
                (path("input") as ObjectNode).set<JsonNode>("credential", credential)
            }
            check(valid, true)
            credential.fieldNames().forEachRemaining { field ->
                check(valid.deepCopy().apply { (at("/input/credential") as ObjectNode).remove(field) }, false)
            }
            check(inputs.first().deepCopy().apply { (path("input") as ObjectNode).set<JsonNode>("credential", credential) }, false)
        }
        for (port in listOf(0, 65536)) check(inputs.first().deepCopy().apply { (at("/input/properties") as ObjectNode).put("port", port) }, false)
        check(inputs.first().deepCopy().apply { (path("input") as ObjectNode).put("displayName", "") }, false)
    }

    @Test
    fun `tool descriptions link the Agent API reference on the configured docs origin`() {
        val devTools = McpToolCatalog(MonomerDocsReader("https://dev.monomer.cc").agentApiReferenceUrl).tools
        devTools.filter { it["name"] != "monomer_docs" }.forEach { tool ->
            val description = tool.getValue("description").toString()
            assertTrue(description.contains("Agent API: https://dev.monomer.cc/en/docs/reference/agent-api/"), tool["name"].toString())
            assertTrue(!description.contains("https://monomer.cc/"), tool["name"].toString())
        }
    }

    @Test
    fun `list limit tells agents to omit it for discovery`() {
        val limit = schema("monomer_list").at("/properties/limit")
        assertEquals(1, limit.path("minimum").asInt())
        assertEquals(100, limit.path("maximum").asInt())
        assertTrue(limit.path("description").asText().contains("Omit it for discovery: path workspaces or organizations takes only path."))
        val discoveryLimitSchema = factory.getSchema(schema("monomer_list"))
        assertTrue(discoveryLimitSchema.validate(mapper.readTree("""{"path":"workspaces","limit":20}""")).isNotEmpty())
        assertTrue(discoveryLimitSchema.validate(mapper.readTree("""{"path":"workspaces"}""")).isEmpty())
    }
}
