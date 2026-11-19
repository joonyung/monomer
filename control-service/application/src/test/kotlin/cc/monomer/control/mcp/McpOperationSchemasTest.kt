@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.mcp

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.control.publicapi.AgentWorkspaceHttpPorts
import cc.monomer.identity.api.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.mockk.coEvery
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class McpOperationSchemasTest {
    private val mapper = ObjectMapper()
    private val catalog = McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl)
    private val documents = listOf("agent-workspace.schema.json", "connector/connector.schema.json", "semantic/model.schema.json", "semantic/metric.schema.json", "semantic/query.schema.json", "dashboard/dashboard.schema.json", "metric-display.schema.json").map { path ->
        checkNotNull(javaClass.classLoader.getResourceAsStream("contracts/$path")).use(mapper::readTree)
    }
    private val canonical = documents.first()
    private val uuid = "01942b04-6e00-7000-8000-000000000001"
    private val dashboard = "workspace/dashboards/$uuid"
    private val page = "workspace/pages/$uuid"
    private val catalogRoot = "workspace/connectors/$uuid/catalog"
    private val paths = mapOf(
        "DashboardResourcePath" to listOf(dashboard),
        "DashboardDraftResourcePath" to listOf("$dashboard/draft"), "DashboardDraftStatusResourcePath" to listOf("$dashboard/draft/status"),
        "DashboardSessionCollectionPath" to listOf("$dashboard/sessions"),
        "DashboardSessionResourcePath" to listOf("$dashboard/sessions/$uuid"),
        "DashboardDraftQueryRunCollectionPath" to listOf("$dashboard/draft/query-runs"),
        "DashboardQueryRunResourcePath" to listOf("$dashboard/draft/query-runs/$uuid"),
        "DashboardDraftQueryResultResourcePath" to listOf("$dashboard/draft/query-runs/$uuid/result"),
        "DashboardSessionQueryRunCollectionPath" to listOf("$dashboard/sessions/$uuid/query-runs"),
        "DashboardSessionQueryRunResourcePath" to listOf("$dashboard/sessions/$uuid/query-runs/$uuid"),
        "DashboardSessionQueryResultResourcePath" to listOf("$dashboard/sessions/$uuid/query-runs/$uuid/result"),
        "SemanticModelResourcePath" to listOf("workspace/semantic-models/orders"),
        "MetricResourcePath" to listOf("workspace/metrics/revenue"),
        "ExploreRunResourcePath" to listOf("workspace/explore/runs/$uuid"),
        "ExploreResultResourcePath" to listOf("workspace/explore/runs/$uuid/result"),
        "CatalogRootResourcePath" to listOf(catalogRoot),
        "CatalogEntryResourcePath" to listOf("$catalogRoot/catalogs/analytics"),
        "CatalogSchemaResourcePath" to listOf("$catalogRoot/schemas/public", "$catalogRoot/catalogs/analytics/schemas/public"),
        "CatalogRelationResourcePath" to listOf("$catalogRoot/schemas/public/relations/orders", "$catalogRoot/catalogs/analytics/schemas/public/relations/orders"),
        "ConnectorCatalogSelectionResourcePath" to listOf("$catalogRoot/catalog-selection"),
        "CatalogSelectionResourcePath" to listOf("$catalogRoot/selection", "$catalogRoot/catalogs/analytics/selection"),
        "CatalogOptionCollectionPath" to listOf("$catalogRoot/catalog-selection/options/catalogs", "$catalogRoot/selection/options/schemas", "$catalogRoot/selection/options/schemas/public/relations", "$catalogRoot/catalogs/analytics/selection/options/schemas", "$catalogRoot/catalogs/analytics/selection/options/schemas/public/relations"),
        "CatalogOptionResourcePath" to listOf("$catalogRoot/catalog-selection/options/catalogs/analytics", "$catalogRoot/selection/options/schemas/public", "$catalogRoot/catalogs/analytics/selection/options/schemas/public/relations/orders"),
        "CatalogOptionRefreshCollectionPath" to listOf("$catalogRoot/catalog-selection/option-refreshes/catalogs", "$catalogRoot/selection/option-refreshes/schemas", "$catalogRoot/catalogs/analytics/selection/option-refreshes/schemas/public/relations"),
        "CatalogOptionRefreshResourcePath" to listOf("$catalogRoot/catalog-selection/option-refreshes/catalogs/$uuid", "$catalogRoot/selection/option-refreshes/schemas/$uuid", "$catalogRoot/catalogs/analytics/selection/option-refreshes/schemas/public/relations/$uuid"),
        "CatalogIngestionResourcePath" to listOf("$catalogRoot/ingestions/$uuid", "$catalogRoot/catalogs/analytics/ingestions/$uuid"),
        "CatalogIngestionFailureResourcePath" to listOf("$catalogRoot/ingestions/$uuid/failures/$uuid", "$catalogRoot/catalogs/analytics/ingestions/$uuid/failures/$uuid"),
        "CatalogCollectionPath" to listOf("$catalogRoot/catalogs", "$catalogRoot/schemas", "$catalogRoot/catalogs/analytics/schemas", "$catalogRoot/schemas/public/relations", "$catalogRoot/ingestions", "$catalogRoot/catalogs/analytics/ingestions", "$catalogRoot/ingestions/$uuid/failures"),
        "ConnectorResourcePath" to listOf("workspace/connectors/$uuid"),
        "PageResourcePath" to listOf(page), "PageVersionCollectionPath" to listOf("$page/versions"),
        "PageVersionResourcePath" to listOf("$page/versions/1"), "PageUploadResourcePath" to listOf("workspace/page-content-uploads/$uuid"),
        "PagesTreeResourcePath" to listOf("workspace/pages/tree"),
        "OrganizationCollectionPath" to listOf("organization/members", "organization/invitations", "organization/workspaces", "organization/workspaces/$uuid/members"),
        "OrganizationResourcePath" to listOf("organization"), "OrganizationMemberPath" to listOf("organization/members/$uuid"),
        "OrganizationInvitationPath" to listOf("organization/invitations/$uuid"),
        "WorkspaceManagementCollectionPath" to listOf("workspace/members", "workspace/member-candidates", "workspace/lineage/relations", "workspace/lineage/semantic-models", "workspace/lineage/metrics"),
        "WorkspaceMemberPath" to listOf("workspace/members/$uuid"), "OrganizationWorkspaceMemberPath" to listOf("organization/workspaces/$uuid/members/$uuid"),
        "WorkspaceMemberCandidatePath" to listOf("workspace/member-candidates/$uuid"),
        "WorkspaceAccessPath" to listOf("workspace/access"), "WorkspaceAuthorizationPath" to listOf("workspace/authorization"),
        "LineageResourcePath" to listOf("workspace/lineage/relations/$uuid", "workspace/lineage/semantic-models/$uuid", "workspace/lineage/metrics/$uuid"),
        "DashboardSchedulesResourcePath" to listOf("$dashboard/schedules", "$dashboard/draft/schedules"),
        "DashboardOccurrenceCollectionPath" to listOf("$dashboard/occurrences"), "DashboardOccurrenceResourcePath" to listOf("$dashboard/occurrences/daily/2026-10-08T00:00:00Z"),
        "PageContentResourcePath" to listOf("$page/content"), "PageSessionCollectionPath" to listOf("$page/sessions"),
        "PageSessionResourcePath" to listOf("$page/sessions/$uuid"), "PageRunCollectionPath" to listOf("$page/sessions/$uuid/query-runs"),
        "PageRunResourcePath" to listOf("$page/sessions/$uuid/query-runs/$uuid"), "PageResultResourcePath" to listOf("$page/sessions/$uuid/query-runs/$uuid/result"),
        "SemanticModelHistoryPath" to listOf("workspace/semantic-models/orders/history/$uuid"), "SemanticModelVersionPath" to listOf("workspace/semantic-models/orders/history/$uuid/$uuid"),
        "MetricHistoryPath" to listOf("workspace/metrics/revenue/history/$uuid"), "MetricVersionPath" to listOf("workspace/metrics/revenue/history/$uuid/$uuid"),
        "DashboardHistoryPath" to listOf("$dashboard/history"), "DashboardVersionPath" to listOf("$dashboard/history/$uuid"),
    )
    private val requestNames = listOf(
        "SemanticModelCheckInvokeRequest", "MetricCheckInvokeRequest", "AgentListRequest", "AgentReadRequest", "EDIT",
        "SemanticModelCreateRequest", "MetricCreateRequest", "DashboardCreateRequest", "ExploreRunInvokeRequest", "ExploreRunInvokeRequest", "ExploreRunCancelInvokeRequest",
        "DashboardDraftStartInvokeRequest", "DashboardDraftApplyInvokeRequest", "DashboardDraftDiscardInvokeRequest", "DashboardQueryRefreshInvokeRequest", "DashboardDraftRunCancelInvokeRequest",
        "DashboardSessionOpenInvokeRequest", "DashboardSessionRefreshInvokeRequest", "CatalogIngestInvokeRequest", "CatalogOptionRefreshInvokeRequest", "CatalogJobCancelInvokeRequest", "DashboardSessionResolveInvokeRequest",
        "ConnectorCreateRequest", "ConnectorCredentialRotateInvokeRequest", "ConnectorDeleteInvokeRequest", "FolderPageCreateRequest", "DocumentPageCreateRequest", "DashboardPageCreateRequest",
        "PageVersionCreateRequest", "PageUploadCreateRequest", "PageDeleteInvokeRequest", "PageUploadCompleteInvokeRequest", "PageUploadAbortInvokeRequest", "OrganizationInvitationCreateRequest", "OrganizationInvitationResendInvokeRequest", "OrganizationInvitationCancelInvokeRequest",
        "WorkspaceMemberAddInvokeRequest", "WorkspaceMemberRemoveInvokeRequest", "WorkspaceMemberRoleInvokeRequest", "PageSessionResolveInvokeRequest", "SemanticModelDeleteInvokeRequest", "SemanticModelRestoreInvokeRequest", "MetricDeleteInvokeRequest", "MetricRestoreInvokeRequest", "DashboardDeleteInvokeRequest", "DashboardRestoreInvokeRequest",
    )
    private val editRequests = mapOf("SemanticModelResourcePath" to "SemanticModelEditRequest", "MetricResourcePath" to "MetricEditRequest", "DashboardDraftResourcePath" to "DashboardDraftEditRequest", "ConnectorCatalogSelectionResourcePath" to "ConnectorCatalogSelectionEditRequest", "CatalogSelectionResourcePath" to "CatalogSelectionEditRequest", "ConnectorResourcePath" to "ConnectorEditRequest", "PageResourcePath" to "PageMetadataEditRequest", "PagesTreeResourcePath" to "PagesTreeEditRequest")

    private fun samples(pathKind: JsonNode): List<String> = if (pathKind.has("const")) listOf(pathKind.path("const").asText()) else paths.getValue(pathKind.path("\$ref").asText().substringAfterLast('/'))

    private fun descriptor(branch: McpToolCatalog.OperationBranch, path: String): ObjectNode = mapper.createObjectNode().put("operation", branch.operation).put("path", path).apply {
        branch.action?.let { put("action", it) }; branch.inputKind?.let { put("inputKind", it) }
    }
    private fun describe(entries: List<JsonNode>): ObjectNode = catalog.describe(mapper.createObjectNode().set<ObjectNode>("operations", mapper.createArrayNode().apply { entries.forEach(::add) }))

    private fun sameSchema(expected: JsonNode, expectedDocument: JsonNode, actual: JsonNode, actualDocument: JsonNode, checked: MutableSet<Pair<JsonNode, JsonNode>>) {
        if (!checked.add(expected to actual)) return
        fun resolve(node: JsonNode, document: JsonNode): Pair<JsonNode, JsonNode> {
            if (!node.has("\$ref")) return node to document
            val reference = node.path("\$ref").asText()
            val base = reference.substringBefore('#')
            val targetDocument = if (base.isEmpty()) document else documents.single { it.path("\$id").asText() == base }
            val target = targetDocument.at(reference.substringAfter('#', "")).deepCopy<JsonNode>() as ObjectNode
            node.properties().filter { it.key != "\$ref" }.forEach { (key, value) -> target.set<JsonNode>(key, value) }
            return target to targetDocument
        }
        if (expected.has("\$ref") || actual.has("\$ref")) {
            val (resolvedExpected, expectedOwner) = resolve(expected, expectedDocument)
            val (resolvedActual, actualOwner) = resolve(actual, actualDocument)
            sameSchema(resolvedExpected, expectedOwner, resolvedActual, actualOwner, checked)
        } else if (expected.isObject && actual.isObject) {
            val ignored = setOf("\$defs", "\$id", "\$schema")
            val fields = expected.fieldNames().asSequence().toSet() - ignored
            assertEquals(fields, actual.fieldNames().asSequence().toSet() - ignored)
            fields.forEach { sameSchema(expected.path(it), expectedDocument, actual.path(it), actualDocument, checked) }
        } else if (expected.isArray && actual.isArray) {
            assertEquals(expected.size(), actual.size())
            expected.forEachIndexed { index, value -> sameSchema(value, expectedDocument, actual.path(index), actualDocument, checked) }
        } else assertEquals(expected, actual)
    }
    private fun auditReferences(node: JsonNode, root: JsonNode) {
        if (node.isObject) {
            node.get("\$ref")?.let { ref -> assertTrue(ref.asText().startsWith("#/")); assertTrue(!root.at(ref.asText().removePrefix("#")).isMissingNode, ref.asText()) }
            node.elements().forEachRemaining { auditReferences(it, root) }
        } else if (node.isArray) node.forEach { auditReferences(it, root) }
    }

    @Test
    fun `every available operation and all path kinds describe the exact decoder request branch`() {
        assertEquals(46, canonical.at("/\$defs/AgentAvailableOperation/oneOf").size())
        assertEquals((0 until 46).toSet(), catalog.operationBranches.map { it.availableBranch }.toSet())
        fun canonicalKinds(path: JsonNode): List<JsonNode> = when {
            path.has("oneOf") -> path.path("oneOf").flatMap(::canonicalKinds)
            path.has("\$ref") && canonical.at(path.path("\$ref").asText().removePrefix("#")).has("enum") -> canonicalKinds(canonical.at(path.path("\$ref").asText().removePrefix("#")))
            path.has("enum") -> path.path("enum").map { mapper.createObjectNode().set<JsonNode>("const", it) }
            else -> listOf(path)
        }
        val canonicalKeys = canonical.at("/\$defs/AgentAvailableOperation/oneOf").flatMapIndexed { index, available ->
            canonicalKinds(available.at("/properties/path")).map { index to it }
        }.toSet()
        assertEquals(canonicalKeys, catalog.operationBranches.map { it.availableBranch to it.pathKind }.toSet())
        var sampleCount = 0
        catalog.operationBranches.forEach { branch ->
            samples(branch.pathKind).forEach { path ->
                val entry = descriptor(branch, path)
                val response = describe(listOf(entry))
                auditReferences(response, response)
                assertEquals(branch.tool, response.at("/operations/0/tool").asText())
                if (branch.operation == "INVOKE" && branch.inputKind == null) assertEquals("no input", response.at("/operations/0/input").asText())
                val expectedName = if (branch.operation == "EDIT") editRequests.getValue(branch.pathKind.path("\$ref").asText().substringAfterLast('/')) else requestNames[branch.availableBranch]
                val expected = canonical.path("\$defs").path(expectedName).deepCopy<JsonNode>() as ObjectNode
                if (branch.availableBranch in setOf(8, 9)) {
                    val input = canonical.path("\$defs").path(if (branch.availableBranch == 8) "SemanticExploreRun" else "SqlExploreRun")
                    (expected.path("properties") as ObjectNode).set<JsonNode>("input", input)
                }
                val argumentsSchema = response.at("/operations/0/argumentsSchema").deepCopy<JsonNode>() as ObjectNode
                val target = if (path.startsWith("organization")) "organizationId" else "workspaceId"
                fun removeTarget(schema: ObjectNode) {
                    if (schema.has("oneOf")) schema.path("oneOf").forEach { removeTarget(it as ObjectNode) }
                    else {
                        assertEquals(mapper.readTree("{\"type\":\"string\",\"format\":\"uuid\"}"), schema.path("properties").path(target))
                        assertTrue(schema.path("required").any { it.asText() == target })
                        (schema.path("properties") as ObjectNode).remove(target)
                        schema.set<JsonNode>("required", mapper.createArrayNode().apply { schema.path("required").filter { it.asText() != target }.forEach(::add) })
                    }
                }
                removeTarget(argumentsSchema)
                sameSchema(expected, canonical, argumentsSchema, response, mutableSetOf())
                sampleCount++
            }
        }
        println("MCP describe coverage: 46 available branches, ${catalog.operationBranches.size} path-kind mappings, $sampleCount concrete paths")
    }

    private fun sampleArguments(branch: McpToolCatalog.OperationBranch, path: String): ObjectNode {
        val body = if (branch.operation == "EDIT") {
            val name = editRequests.getValue(branch.pathKind.path("\$ref").asText().substringAfterLast('/'))
            canonical.path("\$defs").path(name).path("examples").first().deepCopy<JsonNode>() as ObjectNode
        } else mapper.createObjectNode()
        body.put("path", path)
        if (branch.operation == "LIST") body.put("limit", 20)
        branch.action?.let { body.put("action", it) }
        branch.inputKind?.let { kind ->
            val input = mapper.createObjectNode().put("kind", kind)
            val model = """{"schemaVersion":3,"name":"orders","sourceRelations":[{"connectorId":"$uuid","schema":"analytics","relation":"orders"}],"entities":[{"name":"order_id","type":"primary","expression":"order_id"}],"dimensions":[],"measures":[]}"""
            val metric = """{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","measure":"revenue"}"""
            val credential = """{"kind":"USERNAME_PASSWORD","username":"fixture","password":"fixture"}"""
            when (kind) {
                "SEMANTIC_MODEL_CHECK", "SEMANTIC_MODEL_DEFINITION" -> {
                    input.set<JsonNode>("definition", mapper.readTree(model))
                    if (kind == "SEMANTIC_MODEL_DEFINITION") input.set<JsonNode>("aggregateMetrics", mapper.createArrayNode())
                }
                "METRIC_CHECK", "METRIC_DEFINITION" -> input.set<JsonNode>("definition", mapper.readTree(metric))
                "DASHBOARD_DEFINITION" -> input.set<JsonNode>("definition", mapper.readTree("""{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}"""))
                "SEMANTIC_EXPLORE_RUN" -> {
                    input.put("connectorId", uuid)
                    input.set<JsonNode>("query", mapper.readTree("""{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}"""))
                }
                "SQL_EXPLORE_RUN" -> input.put("connectorId", uuid).put("sql", "SELECT 1")
                "DASHBOARD_DRAFT_START", "DASHBOARD_SESSION_OPEN" -> input.set<JsonNode>("parameterBindings", mapper.createObjectNode())
                "DASHBOARD_DRAFT_APPLY", "DASHBOARD_DRAFT_DISCARD" -> input.put("expectedRevision", "1")
                "DASHBOARD_QUERY_REFRESH", "DASHBOARD_SESSION_REFRESH" -> {
                    input.set<JsonNode>("queryNames", mapper.createArrayNode().add("orders"))
                    if (kind == "DASHBOARD_SESSION_REFRESH") input.set<JsonNode>("variables", mapper.createObjectNode())
                }
                "DASHBOARD_SESSION_RESOLVE" -> input.set<JsonNode>("variables", mapper.createObjectNode())
                "CONNECTOR_CREATE" -> {
                    input.put("connectorType", "POSTGRESQL").put("displayName", "Fixture")
                    input.set<JsonNode>("properties", mapper.readTree("""{"host":"warehouse.example.test","port":5432,"database":"analytics"}"""))
                    input.set<JsonNode>("credential", mapper.readTree(credential))
                }
                "CONNECTOR_CREDENTIAL_ROTATE" -> input.set<JsonNode>("credential", mapper.readTree(credential))
                "FOLDER_PAGE", "DOCUMENT_PAGE", "DASHBOARD_PAGE" -> {
                    input.put("title", "Fixture").put("baseRevision", "0").put("commandId", "fixture")
                    if (kind == "DOCUMENT_PAGE") input.put("uploadRef", uuid)
                    if (kind == "DASHBOARD_PAGE") input.put("dashboardId", uuid)
                }
                "PAGE_VERSION" -> input.put("commandId", "fixture").put("uploadRef", uuid)
                "PAGE_CONTENT_UPLOAD" -> input.put("format", "MARKDOWN").put("byteSize", 12).put("sha256", "a".repeat(64))
                "PAGE_DELETE" -> input.put("baseRevision", "0").put("commandId", "fixture")
                "PAGE_CONTENT_UPLOAD_COMPLETE" -> input.set<JsonNode>("parts", mapper.readTree("""[{"partNumber":1,"eTag":"fixture"}]"""))
                "ORGANIZATION_INVITATION_CREATE" -> input.put("email", "member@example.test")
                "WORKSPACE_MEMBER_ADD" -> input.put("userId", uuid).put("role", "EXPLORER")
                "WORKSPACE_MEMBER_ROLE" -> input.put("role", "EXPLORER")
                else -> error("Missing owning input fixture: $kind")
            }
            body.set<JsonNode>("input", input)
        }
        return body
    }

    @Test
    fun `every described arguments schema reaches its real decoder and expected MCP route`() {
        val oauth = mockk<AgentOAuthPort>()
        val handler = mockk<AgentWorkspaceHandler>()
        val management = mockk<AgentManagementOwnerPort>()
        val admission = mockk<AgentWorkspaceAdmissionPort>()
        val decoder = AgentWorkspaceContractDecoder()
        val workspaceId = WorkspaceId(Uuid.parse(uuid))
        val organizationId = OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000002"))
        val principalId = PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000003"))
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(OAuthAgentIdentity(principalId, "https://client.example/metadata.json"))
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(principalId, PrincipalKind.USER), organizationId,
                workspaceId, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, AgentClientId("https://client.example/metadata.json"), WorkspaceRole.OWNER, request.intent))
        }
        var reached: Pair<String, Any>? = null
        coEvery { handler.list(any(), any()) } answers { reached = "workspace/LIST" to secondArg<AgentListRequest>(); AgentListingOutcome.NotFound }
        coEvery { handler.read(any(), any()) } answers { reached = "workspace/READ" to secondArg<AgentReadRequest>(); AgentResourceOutcome.NotFound }
        coEvery { handler.create(any(), any()) } answers { reached = "workspace/CREATE" to secondArg<AgentCreateRequest>(); AgentActionOutcome.NotFound }
        coEvery { handler.edit(any(), any()) } answers { reached = "workspace/EDIT" to secondArg<AgentEditRequest>(); AgentActionOutcome.NotFound }
        coEvery { handler.invoke(any(), any()) } answers { reached = "workspace/INVOKE" to secondArg<AgentInvokeRequest>(); AgentActionOutcome.NotFound }
        coEvery { management.list(any<AgentOrganizationCaller>(), any()) } answers { reached = "organization/LIST" to secondArg<AgentListRequest>(); AgentListingOutcome.NotFound }
        coEvery { management.read(any<AgentOrganizationCaller>(), any()) } answers { reached = "organization/READ" to secondArg<AgentReadRequest>(); AgentResourceOutcome.NotFound }
        coEvery { management.create(any<AgentOrganizationCaller>(), any()) } answers { reached = "organization/CREATE" to secondArg<AgentCreateRequest>(); AgentActionOutcome.NotFound }
        coEvery { management.invoke(any<AgentOrganizationCaller>(), any()) } answers { reached = "organization/INVOKE" to secondArg<AgentInvokeRequest>(); AgentActionOutcome.NotFound }
        val client = WebTestClient.bindToRouterFunction(McpRouter(
            AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(admission), handler, decoder), oauth,
            McpTransportProperties("https://api.example.com/.well-known/oauth-protected-resource/mcp", setOf("https://app.example.com")),
            MonomerDocsReader("https://monomer.cc"), managementOwnerPort = management,
            publicApiProperties = cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("https://app.example.com")),
            dashboardViewHtml = "<!doctype html><title>Fixture view</title>", clock = java.time.Clock.systemUTC(),
        ).routes()).webFilter<WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter())
            .configureClient().codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }.build()
        fun call(tool: String, arguments: JsonNode): JsonNode {
            val payload = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .set<ObjectNode>("params", mapper.createObjectNode().put("name", tool).set<JsonNode>("arguments", arguments))
            val response = client.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", "2025-11-25").headers { it.setBearerAuth("fixture-token") }.bodyValue(payload.toString())
                .exchange().expectStatus().isOk.expectBody().returnResult().responseBody
            return mapper.readTree(response).path("result")
        }
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val covered = mutableSetOf<Pair<Int, JsonNode>>()
        var roundTrips = 0
        catalog.operationBranches.forEach { branch ->
            samples(branch.pathKind).forEach { path ->
                val description = call("monomer_describe", mapper.createObjectNode().set<JsonNode>("operations", mapper.createArrayNode().add(descriptor(branch, path))))
                assertEquals(false, description.path("isError").asBoolean(), path)
                val response = mapper.readTree(description.at("/content/0/text").asText())
                val entry = response.at("/operations/0")
                val schema = entry.path("argumentsSchema").deepCopy<JsonNode>() as ObjectNode
                schema.set<JsonNode>("\$defs", response.path("\$defs"))
                val validation = factory.getSchema(schema)
                val body = sampleArguments(branch, path)
                val target = if (path.startsWith("organization")) "organizationId" else "workspaceId"
                val arguments = body.deepCopy().put(target, if (target == "organizationId") organizationId.value.toString() else workspaceId.value.toString())
                assertTrue(validation.validate(arguments).isEmpty(), "$branch: ${validation.validate(arguments)}")
                assertTrue(validation.validate(body).isNotEmpty(), "Target ID required: $branch")
                val expected = when (branch.operation) {
                    "LIST" -> decoder.list(body.toString())
                    "READ" -> decoder.read(body.toString())
                    "CREATE" -> decoder.create(body.toString()).value
                    "EDIT" -> decoder.edit(body.toString()).value
                    "INVOKE" -> decoder.invoke(body.toString()).value
                    else -> error(branch.operation)
                }
                reached = null
                val result = call(entry.path("tool").asText(), arguments)
                assertEquals("${path.substringBefore('/')}/${branch.operation}", reached?.first, "$branch: $result")
                val actual = reached?.second
                fun checkCredential(credential: cc.monomer.connector.api.ConnectorCredential) {
                    val password = credential as cc.monomer.connector.api.ConnectorCredential.UsernamePassword
                    assertEquals("fixture", password.username)
                    assertEquals("fixture", password.password)
                }
                val comparable = when (branch.inputKind) {
                    "CONNECTOR_CREATE" -> {
                        val request = actual as AgentCreateRequest
                        val input = request.input as AgentCreateInput.Connector
                        checkCredential(input.credential)
                        request.copy(input = input.copy(credential = ((expected as AgentCreateRequest).input as AgentCreateInput.Connector).credential))
                    }
                    "CONNECTOR_CREDENTIAL_ROTATE" -> {
                        val request = actual as AgentInvokeRequest
                        checkCredential((request.input as AgentInvokeInput.ConnectorCredentialRotate).credential)
                        request.copy(input = (expected as AgentInvokeRequest).input)
                    }
                    else -> actual
                }
                assertEquals(expected, comparable, "$branch: $result")
                assertTrue(result.path("isError").asBoolean())
                assertEquals("NOT_FOUND", mapper.readTree(result.at("/content/0/text").asText().substringAfter('\n')).path("code").asText())
                covered.add(branch.availableBranch to branch.pathKind)
                roundTrips++
            }
        }
        assertEquals(124, covered.size)
        assertEquals(163, roundTrips)
        println("MCP arguments round trips: ${covered.size} mappings, $roundTrips paths; real strict decoder and workspace/organization routes; missing target IDs rejected by schemas")
    }

    @Test
    fun `long valid ASCII and Unicode paths do not inflate the schema-only describe response`() {
        fun extended(path: String): String {
            val catalogName = when {
                path.contains("/catalogs/analytics") -> "analytics"
                path.contains("/schemas/public") -> "public"
                path.contains("/relations/orders") -> "orders"
                else -> null
            }
            val semanticName = when {
                path.startsWith("workspace/metrics/revenue") -> "revenue"
                path.startsWith("workspace/semantic-models/orders") -> "orders"
                else -> null
            }
            val original = catalogName ?: semanticName ?: return path
            val available = 6257 - path.codePointCount(0, path.length) + original.length
            val token = if (catalogName != null) "🧬\"\\" else "a"
            val name = buildString {
                repeat(available / token.codePointCount(0, token.length)) { append(token) }
                repeat(available % token.codePointCount(0, token.length)) { append("a") }
            }
            return path.replaceFirst(original, name)
        }
        var boundaries = 0
        catalog.operationBranches.forEach { branch ->
            samples(branch.pathKind).forEach { short ->
                val long = extended(short)
                if (long != short) {
                    assertEquals(6257, long.codePointCount(0, long.length))
                    assertEquals(describe(List(10) { descriptor(branch, short) }), describe(List(10) { descriptor(branch, long) }))
                    boundaries++
                }
            }
        }
        assertTrue(boundaries > 20)
        println("MCP long-path describe boundaries: $boundaries batches of 10; schema response unchanged")
    }

    @Test
    fun `private path matcher keeps original acceptance for escaped names and malformed boundaries`() {
        val factory = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
        catalog.operationBranches.distinctBy { it.pathKind }.forEach { branch ->
            val schema = branch.pathKind.deepCopy<JsonNode>() as ObjectNode
            schema.set<JsonNode>("\$defs", canonical.path("\$defs"))
            val original = factory.getSchema(schema)
            samples(branch.pathKind).forEach { path ->
                val variants = listOf(path, "$path/", "$path~2", "$path/extra", "$path\u0000", "$path\n", path.replace("analytics", "a~0b~1c"), path.replace("analytics", "🧬\"\\"), path.replace("revenue", "a_b"))
                variants.forEach { variant ->
                    val input = mapper.valueToTree<JsonNode>(variant)
                    assertEquals(original.validate(input).isEmpty(), branch.pathValidator.validate(input).isEmpty(), "${branch.pathKind}: $variant")
                }
            }
        }
        for (path in listOf("workspace/" + "a/".repeat(3125), "workspace/metrics/" + "a_".repeat(3119) + "a")) {
            val metric = catalog.operationBranches.first { it.operation == "EDIT" && it.pathKind.path("\$ref").asText().endsWith("/MetricResourcePath") }
            val valid = path.startsWith("workspace/metrics/") && path.length <= 6257
            assertEquals(valid, metric.pathValidator.validate(mapper.valueToTree(path)).isEmpty())
        }
    }

    @Test
    fun `describe rejects malformed and unknown combinations with located violations`() {
        for ((input, field) in listOf("{}" to "arguments", "{\"operations\":[]}" to "operations", "{\"operations\":[42]}" to "operations[0]", "{\"operations\":[{\"operation\":\"EDIT\",\"path\":\"workspace/metrics/revenue\",\"inputKind\":\"METRIC_DEFINITION\"}]}" to "operations[0]", "{\"operations\":[{\"operation\":\"INVOKE\",\"path\":\"workspace/metrics\",\"action\":\"check\",\"inputKind\":\"SEMANTIC_MODEL_CHECK\"}]}" to "operations[0]")) {
            val failure = assertFailsWith<AgentWorkspaceRequestDecodingException> { catalog.describe(mapper.readTree(input)) }
            assertEquals(field, failure.violations.single().field)
        }
        val entries = List(11) { mapper.readTree("{\"operation\":\"READ\",\"path\":\"workspace\"}") }
        assertFailsWith<AgentWorkspaceRequestDecodingException> { describe(entries) }
    }

    @Test
    fun `Session describe explains execution selection complete inputs and result readback`() {
        val meanings = mapOf(
            "DashboardSessionOpenInvokeRequest" to listOf("immutable definition snapshot", "starts a QueryRun for every named query", "without rows or a queryRuns map", "initialVariables", "empty array does not mean no queries ran"),
            "DashboardSessionResolveInvokeRequest" to listOf("latest matching SUCCEEDED", "matching RUNNING", "starts a new QueryRun", "FAILED and CANCELLED runs are not reused", "Unlike refresh", "queryRuns is empty"),
            "DashboardSessionRefreshInvokeRequest" to listOf("Unlike resolve", "only for those names", "Unselected queries", "FAILED or CANCELLED", "REFRESH_QUERY_RUN_UNAVAILABLE", "resolve the inputs first"),
            "PageSessionResolveInvokeRequest" to listOf("current Page access", "Page-to-Dashboard connection", "starts a new QueryRun", "not offered Session open or refresh", "PAGE_SESSION result.path", "Page QueryRun path"),
        )
        meanings.forEach { (requestName, expectedMeanings) ->
            val canonicalRequest = canonical.path("\$defs").path(requestName)
            val branch = catalog.operationBranches.single { it.requestSchema == canonicalRequest }
            val response = describe(listOf(descriptor(branch, samples(branch.pathKind).first())))
            val request = response.at("/operations/0/argumentsSchema")
            val description = request.path("description").asText()
            assertEquals(canonicalRequest.path("description").asText(), description)
            for (meaning in expectedMeanings + listOf("Returns ACCEPTED", "READ each", "advertised result child when SUCCEEDED", "readyResults")) {
                assertTrue(description.contains(meaning), "$requestName: $meaning")
            }
            val input = request.at("/properties/input")
            assertEquals(canonicalRequest.at("/properties/input/description"), input.path("description"))
            if (requestName == "DashboardSessionOpenInvokeRequest") {
                val parameters = input.at("/properties/parameterBindings/description").asText()
                for (meaning in listOf("Complete map", "every declared Parameter", "explicit null", "immutable", "empty map is valid only", "omitted keys do not receive defaults")) {
                    assertTrue(parameters.contains(meaning), "$requestName: $meaning")
                }
            } else {
                val variables = input.at("/properties/variables/description").asText()
                for (meaning in listOf("Complete map", "every declared Variable", "explicit null", "initialVariables", "not a patch", "empty map is valid only", "does not apply defaults or retain earlier values")) {
                    assertTrue(variables.contains(meaning), "$requestName: $meaning")
                }
            }
            if (requestName == "DashboardSessionRefreshInvokeRequest") {
                val queryNames = input.at("/properties/queryNames/description").asText()
                for (meaning in listOf("Non-empty unique names", "Only these queries", "matching RUNNING", "Other queries must already have a run", "widget")) {
                    assertTrue(queryNames.contains(meaning), "$requestName: $meaning")
                }
            }
        }
    }

    @Test
    fun `Pages describe explains tree base revisions and command idempotency for every command`() {
        val branches = catalog.operationBranches.filter { branch ->
            branch.requestSchema.toString().contains("PageCommandId")
        }
        assertEquals(7, branches.size)
        var baseRevisions = 0
        branches.forEach { branch ->
            val response = describe(listOf(descriptor(branch, samples(branch.pathKind).first())))
            val request = response.at("/operations/0/argumentsSchema")
            val properties = if (request.path("properties").has("input")) request.at("/properties/input/properties") else request.path("properties")
            val command = properties.path("commandId")
            val commandDescription = response.at(command.path("\$ref").asText().removePrefix("#")).path("description").asText()
            for (meaning in listOf("idempotency", "same target", "request content", "revision", "replay", "new commandId", "rejected")) {
                assertTrue(commandDescription.contains(meaning), "$branch: $meaning")
            }
            if (properties.has("baseRevision")) {
                val description = properties.at("/baseRevision/description").asText()
                assertTrue(description.contains("Pages tree revision"))
                assertTrue(description.contains("revision field from READ workspace/pages/tree"))
                baseRevisions++
            }
        }
        assertEquals(4, baseRevisions)
    }

    @Test
    fun `shared definitions appear once and describe payloads stay within their separate cap`() {
        val entries = catalog.operationBranches.distinctBy { it.requestSchema }.map { descriptor(it, samples(it.pathKind).first()) }
        fun bytes(entries: List<JsonNode>): Int = mapper.writeValueAsBytes(mapOf("jsonrpc" to "2.0", "id" to 1,
            "result" to mapOf("isError" to false, "content" to listOf(mapOf("type" to "text", "text" to mapper.writeValueAsString(describe(entries))))))).size
        val singles = entries.map { it to bytes(listOf(it)) }.sortedByDescending { it.second }
        println("MCP largest single describe wire bytes: ${singles.take(8).map { it.first to it.second }}")
        val greedy = mutableListOf<JsonNode>()
        repeat(10) { greedy.add(entries.filter { it !in greedy }.maxBy { bytes(greedy + listOf(it)) }) }
        val largest = listOf(greedy, singles.take(10).map { it.first }).maxOf(::bytes)
        println("MCP largest measured 10-entry describe wire bytes: $largest")
        assertTrue(largest < 95_000, "Describe JSON cap: $largest bytes")
        for ((entry, _) in singles) {
            val response = describe(List(10) { entry })
            assertTrue(bytes(List(10) { entry }) < 95_000)
            assertEquals(describe(listOf(entry)).path("\$defs"), response.path("\$defs"))
            auditReferences(response, response)
        }
    }
}
