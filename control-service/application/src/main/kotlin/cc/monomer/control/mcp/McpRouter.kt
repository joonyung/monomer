@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.mcp

import cc.monomer.control.agent.toHttpValue

import cc.monomer.agent.api.*
import cc.monomer.control.publicapi.AgentTransportProblems
import cc.monomer.control.publicapi.AgentWorkspaceHttpPorts
import cc.monomer.control.publicapi.AgentWorkspaceJson
import cc.monomer.control.publicapi.logAgentTransportFailure
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.control.publicapi.PublicControlContractValidator
import cc.monomer.identity.api.*
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.time.Clock
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

data class McpTransportProperties(val resourceMetadataUrl: String, val allowedOrigins: Set<String>) {
    init {
        val uri = java.net.URI(resourceMetadataUrl)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.fragment == null)
        require(resourceMetadataUrl.none { it == '"' || it == '\r' || it == '\n' })
    }
}

class McpRouter(
    private val agentPorts: AgentWorkspaceHttpPorts,
    private val oauth: AgentOAuthPort,
    private val properties: McpTransportProperties,
    private val docs: MonomerDocsReader,
    private val managementOwnerPort: AgentManagementOwnerPort,
    private val publicApiProperties: ControlPublicApiProperties,
    private val dashboardViewHtml: String,
    private val clock: Clock,
) {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val catalog = McpToolCatalog(docs.agentApiReferenceUrl)
    private val validator = PublicControlContractValidator()
    private val serverInfo = mapOf(
        "name" to "Monomer",
        "version" to "1.0.0",
        "description" to "Explore data, build semantic models, and manage dashboards in your Monomer workspaces.",
        "websiteUrl" to "https://monomer.cc",
        "icons" to listOf(mapOf(
            "src" to "data:image/png;base64,${Base64.getEncoder().encodeToString(checkNotNull(McpRouter::class.java.getResourceAsStream("/brand/monomer-favicon.png")) { "Missing bundled Monomer icon" }.use { it.readBytes() })}",
            "mimeType" to "image/png",
            "sizes" to listOf("512x512"),
        )),
    )

    fun routes(): RouterFunction<ServerResponse> = RouterFunctions.route()
        .POST("/mcp") { request -> mono(org.springframework.core.PropagationContextElement()) { handle(request) } }
        .GET("/mcp") { request -> mono(org.springframework.core.PropagationContextElement()) { handle(request) } }
        .DELETE("/mcp") { request -> mono(org.springframework.core.PropagationContextElement()) { handle(request) } }
        .build()

    private suspend fun handle(request: ServerRequest): ServerResponse {
        val authorization = request.headers().header(HttpHeaders.AUTHORIZATION)
        val header = authorization.singleOrNull()
        if (header == null || !header.startsWith("Bearer ", true) || !TOKEN.matches(header.substring(7))) return unauthorized()
        val credential = PresentedCredential(header.substring(7))
        val identity = try {
            oauth.validateCredential(credential, ExpectedOAuthResource.AGENT_MCP)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            logAgentTransportFailure("MCP", AgentRequestId(request.exchange().request.id), failure)
            return empty(500)
        } catch (failure: Exception) {
            logAgentTransportFailure("MCP", AgentRequestId(request.exchange().request.id), failure)
            return ServerResponse.status(503).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.RETRY_AFTER, AgentTransportProblems.RETRY_AFTER_SECONDS.toString()).build().awaitSingle()
        }
        if (identity !is OAuthOutcome.Success) return unauthorized()
        cc.monomer.control.observability.RequestLogContext.agent(identity.value.clientId)
        if (!validOrigin(request)) return empty(403)
        if (request.method() != HttpMethod.POST) return ServerResponse.status(405).header("Allow", "POST").build().awaitSingle()
        return post(request, credential)
    }

    private fun validOrigin(request: ServerRequest): Boolean {
        val origins = request.headers().header(HttpHeaders.ORIGIN)
        return origins.isEmpty() || origins.size == 1 && origins.single() in properties.allowedOrigins
    }

    private suspend fun post(request: ServerRequest, credential: PresentedCredential): ServerResponse {
        val contentType = request.headers().contentType().orElse(null)
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType) || contentType.charset?.let { it != StandardCharsets.UTF_8 } == true) return empty(415)
        val accepts = runCatching { request.headers().accept() }.getOrElse { return empty(406) }
        if (!accepts.any { it.qualityValue > 0 && it.type == "application" && it.subtype == "json" } || !accepts.any { it.qualityValue > 0 && it.type == "text" && it.subtype == "event-stream" }) return empty(406)
        val versions = request.headers().header("MCP-Protocol-Version")
        if (versions.size > 1 || versions.singleOrNull()?.let { it !in VERSIONS } == true) return empty(400)
        val message = try {
            val bytes = request.bodyToMono(ByteArray::class.java).defaultIfEmpty(byteArrayOf()).awaitSingle()
            val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            mapper.readTree(text)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return rpcError(null, -32700, "Parse error", 400) }
        val id = message?.get("id")
        if (message == null || !message.isObject || message.path("jsonrpc").asText() != "2.0" || !message.path("method").isTextual || id != null && !id.isTextual && !id.isIntegralNumber || message.has("params") && !message.path("params").isObject) return rpcError(null, -32600, "Invalid Request", 400)
        val method = message.path("method").asText()
        val params = message.path("params")
        if (id == null) return if (method == "notifications/initialized") empty(202) else empty(400)
        return when (method) {
            "initialize" -> {
                if (!params.path("protocolVersion").isTextual || !params.path("capabilities").isObject || !params.path("clientInfo").isObject || !params.path("clientInfo").path("name").isTextual || !params.path("clientInfo").path("version").isTextual) rpcError(id, -32602, "Invalid params")
                else result(id, mapOf("protocolVersion" to params.path("protocolVersion").asText().takeIf { it in VERSIONS }.orEmpty().ifEmpty { VERSIONS.first() }, "capabilities" to mapOf("tools" to mapOf("listChanged" to false), "resources" to emptyMap<String, Any>()), "serverInfo" to serverInfo, "instructions" to catalog.instructions))
            }
            "ping" -> result(id, emptyMap<String, Any>())
            "tools/list" -> if (params.has("cursor")) rpcError(id, -32602, "Invalid params") else result(id, mapOf("tools" to catalog.tools))
            "tools/call" -> call(request, credential, id, params)
            "resources/list" -> if (params.has("cursor")) rpcError(id, -32602, "Invalid params") else result(id, mapOf("resources" to listOf(linkedMapOf(
                "uri" to McpDashboardView.URI, "name" to "dashboard-view", "title" to "Monomer dashboard view",
                "description" to "Shows a Monomer Dashboard Draft or Session inside the chat.", "mimeType" to McpDashboardView.MIME_TYPE,
            ))))
            "resources/templates/list" -> if (params.has("cursor")) rpcError(id, -32602, "Invalid params") else result(id, mapOf("resourceTemplates" to emptyList<Any>()))
            "resources/read" -> readResource(id, params)
            else -> rpcError(id, -32601, "Method not found")
        }
    }

    private suspend fun readResource(id: JsonNode, params: JsonNode): ServerResponse {
        if (params.has("_meta") && !params.path("_meta").isObject) return rpcError(id, -32602, "Invalid params")
        val uri = params.path("uri").takeIf { it.isTextual }?.asText()
        val html = when {
            uri == McpDashboardView.URI -> dashboardViewHtml
            uri != null && McpDashboardView.VERSIONED_URI.matches(uri) -> McpDashboardView.OUT_OF_DATE_HTML
            else -> return rpcError(id, -32002, "Resource not found")
        }
        return result(id, mapOf("contents" to listOf(linkedMapOf(
            "uri" to uri, "mimeType" to McpDashboardView.MIME_TYPE, "text" to html, "_meta" to mapOf("ui" to mapOf("prefersBorder" to true)),
        ))))
    }

    private suspend fun call(request: ServerRequest, credential: PresentedCredential, id: JsonNode, params: JsonNode): ServerResponse = try {
        authenticatedCall(request, credential, id, params)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OwnerResponseInvalid) {
        val requestId = AgentRequestId(request.exchange().request.id)
        logAgentTransportFailure("MCP", requestId, failure)
        problemError(id, requestId, failure.input?.let(AgentTransportProblems::ownerResponseInvalid) ?: AgentTransportProblems.ownerResponseInvalid, params.path("name").asText())
    } catch (failure: cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException) {
        val requestId = AgentRequestId(request.exchange().request.id)
        logAgentTransportFailure("MCP", requestId, failure)
        internalFailure(id, requestId)
    } catch (failure: IllegalArgumentException) {
        val requestId = AgentRequestId(request.exchange().request.id)
        logAgentTransportFailure("MCP", requestId, failure)
        internalFailure(id, requestId)
    } catch (failure: Exception) {
        val requestId = AgentRequestId(request.exchange().request.id)
        logAgentTransportFailure("MCP", requestId, failure)
        problemError(id, requestId, AgentTransportProblems.ownerUnavailable, params.path("name").asText())
    }

    private suspend fun authenticatedCall(request: ServerRequest, credential: PresentedCredential, id: JsonNode, params: JsonNode): ServerResponse {
        val name = params.path("name").takeIf { it.isTextual }?.asText() ?: return rpcError(id, -32602, "Invalid params")
        if (catalog.tools.none { it["name"] == name }) return rpcError(id, -32602, "Unknown tool")
        val arguments = params.get("arguments") ?: mapper.createObjectNode()
        if (!arguments.isObject) return rpcError(id, -32602, "Invalid params")
        val requestId = AgentRequestId(request.exchange().request.id)
        if (name == "monomer_describe") {
            return try {
                result(id, mapOf("isError" to false, "content" to listOf(mapOf("type" to "text", "text" to mapper.writeValueAsString(catalog.describe(arguments))))))
            } catch (failure: AgentWorkspaceRequestDecodingException) {
                invalidArguments(id, requestId, failure.violations)
            }
        }
        if (name == "monomer_docs") {
            if (arguments.fieldNames().asSequence().any { it != "path" }) return invalidArguments(id, requestId, listOf(AgentProblemViolation("arguments", "SCHEMA_MISMATCH", "Only the optional path field is allowed. Omit it to read /en/llms.txt.", null, null)))
            val path = if (arguments.has("path")) arguments.path("path").takeIf { it.isTextual }?.asText()
                ?: return invalidArguments(id, requestId, listOf(AgentProblemViolation("path", "SCHEMA_MISMATCH", "The path must be a string. Omit it to read /en/llms.txt.", null, null))) else MonomerDocsReader.ROOT_PATH
            if (!MonomerDocsReader.validPath(path)) return invalidArguments(id, requestId, listOf(AgentProblemViolation("path", "INVALID_FIELD", "Use /llms.txt, /en/.../llms.txt or /en/.../index.md with lowercase letter, digit or hyphen segments. Start with /en/llms.txt and follow its document paths.", null, null)))
            val document = docs.read(path)
            val error = document !is MonomerDocsResult.Read
            val text = when (document) {
                is MonomerDocsResult.Read -> document.markdown
                MonomerDocsResult.NotFound -> "Document not found. Call monomer_docs with no arguments and follow a document path from the index."
                MonomerDocsResult.TooLarge -> "Document exceeds the reading limit. No partial content was returned. Choose a smaller document from its parent index."
                MonomerDocsResult.Unavailable -> "Documentation is temporarily unavailable. Try again later; do not infer missing instructions."
            }
            return result(id, mapOf("isError" to error, "content" to listOf(mapOf("type" to "text", "text" to text))))
        }
        if (name == "monomer_list" && arguments.path("path").isTextual && arguments.path("path").asText() in setOf("organizations", "workspaces")) {
            if (arguments.size() != 1) return invalidArguments(id, requestId, listOf(AgentProblemViolation("arguments", "SCHEMA_MISMATCH", "Discovery accepts only path set to organizations or workspaces. Omit target IDs, limit, cursor and other fields.", null, null)))
            return if (arguments.path("path").asText() == "organizations") when (val organizations = oauth.listOrganizations(credential, ExpectedOAuthResource.AGENT_MCP)) {
                is OAuthOutcome.Failure -> unauthorized()
                is OAuthOutcome.Success -> toolResult(id, ownerProjection { AgentWorkspaceJson.organizationsJson(organizations.value) })
            } else when (val workspaces = oauth.listWorkspaces(credential, ExpectedOAuthResource.AGENT_MCP)) {
                is OAuthOutcome.Failure -> unauthorized()
                is OAuthOutcome.Success -> toolResult(id, ownerProjection { AgentWorkspaceJson.workspacesJson(workspaces.value) })
            }
        }
        return try {
            if (name == "monomer_show") show(id, credential, requestId, arguments)
            else if (AgentManagementPaths.organization(arguments.path("path").asText())) {
                if (arguments.has("workspaceId")) throw AgentWorkspaceRequestDecodingException("workspaceId", "Organization paths require organizationId and must omit workspaceId.")
                val organizationId = arguments.path("organizationId").takeIf { it.isTextual }?.asText()?.let {
                    runCatching { OrganizationId(Uuid.parse(it)).takeIf { parsed -> parsed.value.toString() == it } }.getOrNull()
                } ?: throw AgentWorkspaceRequestDecodingException("organizationId", "An organizationId UUID is required. Discover Organizations with monomer_list and path organizations.")
                val body = arguments.deepCopy<ObjectNode>().apply { remove("organizationId") }.toString()
                dispatchOrganization(id, name, organizationId, credential, requestId, body)
            } else {
                if (arguments.has("organizationId")) throw AgentWorkspaceRequestDecodingException("organizationId", "Workspace paths require only workspaceId; omit organizationId.")
                val workspaceId = arguments.path("workspaceId").takeIf { it.isTextual }?.asText()?.let { value ->
                    runCatching { WorkspaceId(Uuid.parse(value)).takeIf { it.value.toString() == value } }.getOrNull()
                } ?: return invalidArguments(id, requestId, listOf(AgentProblemViolation("workspaceId", "INVALID_FIELD", "A workspaceId UUID string is required. Call monomer_list with path set to workspaces and use a returned workspaceId.", null, null)), name)
                val body = arguments.deepCopy<ObjectNode>().apply { remove("workspaceId") }.toString()
                dispatch(id, name, workspaceId, credential, requestId, body)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Unauthenticated) { unauthorized()
        } catch (failure: AdmissionProblem) { problemError(id, requestId, failure.problem, name)
        } catch (failure: AgentWorkspaceRequestDecodingException) { invalidArguments(id, requestId, failure.violations, name)
        } catch (failure: OwnerResponseInvalid) {
            logAgentTransportFailure("MCP", requestId, failure)
            problemError(id, requestId, failure.input?.let(AgentTransportProblems::ownerResponseInvalid) ?: AgentTransportProblems.ownerResponseInvalid, name)
        } catch (failure: cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException) {
            logAgentTransportFailure("MCP", requestId, failure)
            internalFailure(id, requestId)
        } catch (failure: IllegalArgumentException) {
            logAgentTransportFailure("MCP", requestId, failure)
            internalFailure(id, requestId)
        } catch (failure: Exception) {
            logAgentTransportFailure("MCP", requestId, failure)
            problemError(id, requestId, AgentTransportProblems.ownerUnavailable, name)
        }
    }

    private suspend fun show(id: JsonNode, credential: PresentedCredential, requestId: AgentRequestId, arguments: JsonNode): ServerResponse {
        val tool = "monomer_show"
        fun invalid(field: String, code: String, message: String) = listOf(AgentProblemViolation(field, code, message, null, null))
        if (arguments.fieldNames().asSequence().any { it !in setOf("workspaceId", "path", "selection") }) {
            return invalidArguments(id, requestId, invalid("arguments", "SCHEMA_MISMATCH", "Pass only workspaceId, path and, for a Session, selection."), tool)
        }
        val workspaceId = arguments.path("workspaceId").takeIf { it.isTextual }?.asText()?.let { runCatching { WorkspaceId(Uuid.parse(it)).takeIf { parsed -> parsed.value.toString() == it } }.getOrNull() }
            ?: return invalidArguments(id, requestId, invalid("workspaceId", "INVALID_FIELD", "A workspaceId UUID string is required. Call monomer_list with path set to workspaces and use a returned workspaceId."), tool)
        val path = arguments.path("path").takeIf { it.isTextual }?.asText().orEmpty()
        val session = AgentPathPatterns.dashboardSession.matches(path)
        if (!session && !AgentPathPatterns.dashboardDraft.matches(path)) {
            return invalidArguments(id, requestId, invalid("path", "INVALID_FIELD", "Use a Draft path workspace/dashboards/{dashboardId}/draft or a Session path workspace/dashboards/{dashboardId}/sessions/{sessionId}."), tool)
        }
        val selection = arguments.get("selection")
        if (!session && selection != null) {
            return invalidArguments(id, requestId, invalid("selection", "INVALID_FIELD", "Omit selection for a Draft. The view follows later Draft edits and finished runs by itself."), tool)
        }
        if (session && selection == null) {
            return invalidArguments(id, requestId, invalid("selection", "REQUIRED_FIELD_MISSING", "Pass selection with the variables and queryRuns from the latest resolve or refresh result for this Session."), tool)
        }
        if (selection != null && !(selection.isObject && selection.fieldNames().asSequence().toSet() == setOf("variables", "queryRuns") &&
                selection.path("variables").isObject && selection.path("queryRuns").isObject && selection.path("queryRuns").all(JsonNode::isTextual))) {
            return invalidArguments(id, requestId, invalid("selection", "SCHEMA_MISMATCH", "selection must contain only variables (an object) and queryRuns (an object of QueryRun paths), copied from the resolve or refresh result."), tool)
        }
        val input = decodeRequest { agentPorts.requestDecoder.read(mapper.writeValueAsString(mapOf("path" to path))) }
        val outcome = agentPorts.handler.read(caller(agentPorts.callerAuthenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.MCP, input, requestId)), input)
        if (outcome !is AgentResourceOutcome.Found) return respond(id, requestId, tool, outcome, workspaceId)
        requireOwnerResponse(AgentWorkspaceResponseRelations.matches(input, outcome.resource) && AgentWorkspaceResponseRelations.belongsToWorkspace(workspaceId, outcome.resource))
        val segments = path.split('/')
        val dashboardWebPath = "/workspaces/${workspaceId.value}/dashboards/${segments[2]}"
        val (definition, target, webPath) = when (val resource = outcome.resource) {
            is AgentResourceEnvelope.DashboardDraft -> Triple(resource.body.definition,
                linkedMapOf<String, Any?>("kind" to "DRAFT", "path" to path, "revision" to resource.revision.toString()), "$dashboardWebPath/builder")
            is AgentResourceEnvelope.DashboardSession -> {
                val violations = sessionSelectionViolations(path, resource.body.definition, checkNotNull(selection))
                if (violations.isNotEmpty()) return invalidArguments(id, requestId, violations.take(100), tool)
                Triple(resource.body.definition, linkedMapOf<String, Any?>("kind" to "SESSION", "path" to path,
                    "variables" to selection.path("variables"), "queryRuns" to selection.path("queryRuns")), "$dashboardWebPath?session=${segments[4]}")
            }
            else -> throw OwnerResponseInvalid()
        }
        val title = ((definition["meta"] as? JsonObject)?.get("title") as? JsonPrimitive)?.content ?: throw OwnerResponseInvalid()
        val webUrl = publicApiProperties.webOrigin.resolve(webPath).toString()
        val structured = linkedMapOf<String, Any?>(
            "requestId" to requestId.value, "viewContract" to McpDashboardView.VIEW_CONTRACT, "workspaceId" to workspaceId.value.toString(),
            "dashboardId" to segments[2], "title" to title, "target" to target, "webUrl" to webUrl, "shownAt" to clock.millis(),
        )
        val label = if (session) "Session" else "Draft"
        return result(id, mapOf(
            "structuredContent" to structured,
            "content" to listOf(mapOf("type" to "text", "text" to "Showing \"$title\" ($label) in the chat. If no view appears, open it in Monomer: $webUrl")),
            "isError" to false, "_meta" to VIEW_CONTRACT_META,
        ))
    }

    private fun sessionSelectionViolations(sessionPath: String, definition: JsonObject, selection: JsonNode): List<AgentProblemViolation> {
        val queryNames = (definition["queries"] as? JsonObject)?.keys.orEmpty()
        val variableNames = (definition["variables"] as? JsonObject)?.keys.orEmpty()
        val queryRuns = selection.path("queryRuns")
        val runPath = Regex("^${Regex.escape(sessionPath)}/query-runs/${AgentPathPatterns.UUID_V7}$")
        fun field(scope: String, name: String) = if (DEFINITION_NAME.matches(name)) "$scope.$name" else scope
        return buildList {
            queryNames.filterNot(queryRuns::has).forEach { name ->
                add(AgentProblemViolation("selection.queryRuns.$name", "REQUIRED_FIELD_MISSING", "Include the QueryRun path of every Session query, copied from the resolve or refresh result.", null, null))
            }
            queryRuns.properties().forEach { (name, value) ->
                if (name !in queryNames) add(AgentProblemViolation(field("selection.queryRuns", name), "INVALID_FIELD", "Remove queries that the Session definition does not declare.", null, null))
                else if (!runPath.matches(value.asText())) add(AgentProblemViolation("selection.queryRuns.$name", "INVALID_FIELD", "Use this Session's QueryRun path from the resolve or refresh result.", null, null))
            }
            selection.path("variables").properties().forEach { (name, value) ->
                if (name !in variableNames) add(AgentProblemViolation(field("selection.variables", name), "INVALID_FIELD", "Remove Variables that the Session definition does not declare.", null, null))
                else if (!catalog.dashboardValue(value)) add(AgentProblemViolation("selection.variables.$name", "SCHEMA_MISMATCH", "Copy the variables from the resolve or refresh result unchanged.", null, null))
            }
        }
    }

    private fun caller(outcome: AgentWorkspaceCallerAuthenticationOutcome): AgentWorkspaceCaller = when (outcome) {
        is AgentWorkspaceCallerAuthenticationOutcome.Authenticated -> outcome.caller
        is AgentWorkspaceCallerAuthenticationOutcome.Denied -> when (outcome.reason) {
            AgentWorkspaceAdmissionDenial.UNAUTHENTICATED -> throw Unauthenticated()
            AgentWorkspaceAdmissionDenial.NOT_FOUND -> throw AdmissionProblem(AgentTransportProblems.resourceNotFound)
            AgentWorkspaceAdmissionDenial.FORBIDDEN -> throw AdmissionProblem(outcome.problem ?: AgentTransportProblems.forbidden)
            AgentWorkspaceAdmissionDenial.UNAVAILABLE -> throw AdmissionProblem(AgentTransportProblems.ownerUnavailable)
        }
        AgentWorkspaceCallerAuthenticationOutcome.InvalidRequest -> throw AdmissionProblem(AgentTransportProblems.requestInvalid(emptyList()))
        AgentWorkspaceCallerAuthenticationOutcome.EvidenceMismatch -> throw AdmissionProblem(AgentTransportProblems.authorizationEvidenceMismatch)
    }

    private suspend fun dispatch(id: JsonNode, name: String, workspaceId: WorkspaceId, credential: PresentedCredential, requestId: AgentRequestId, body: String): ServerResponse {
        val audience = AgentWorkspaceAdmissionAudience.MCP
        var responseContext: Any? = null
        try {
            val outcome: Any = when (name) {
                "monomer_list" -> {
                    val input = decodeRequest { agentPorts.requestDecoder.list(body) }
                    agentPorts.handler.list(caller(agentPorts.callerAuthenticator.authenticate(workspaceId, credential, audience, input, requestId)), input)
                }
                "monomer_read" -> {
                    val input = decodeRequest { agentPorts.requestDecoder.read(body) }
                    val result = agentPorts.handler.read(caller(agentPorts.callerAuthenticator.authenticate(workspaceId, credential, audience, input, requestId)), input)
                    if (result is AgentResourceOutcome.Found) requireOwnerResponse(AgentWorkspaceResponseRelations.matches(input, result.resource) && AgentWorkspaceResponseRelations.belongsToWorkspace(workspaceId, result.resource))
                    result
                }
                "monomer_search" -> {
                    val input = decodeRequest { agentPorts.requestDecoder.search(body) }
                    val result = agentPorts.handler.search(caller(agentPorts.callerAuthenticator.authenticate(workspaceId, credential, audience, input, requestId)), input)
                    if (result is AgentSearchOutcome.Found) requireOwnerResponse(result.response.results.map { it.query } == input.queries && result.response.results.all { page ->
                        page.items.size <= input.limitPerQuery && page.items.map { it.path }.distinct().size == page.items.size && page.items.all { item -> item.kind in (input.kinds ?: AgentSearchRequest.SUPPORTED_KINDS) && (item.path == input.path || item.path.value.startsWith("${input.path.value}/")) }
                    })
                    result
                }
                "monomer_create" -> {
                    val input = decodeRequest { agentPorts.requestDecoder.create(body).value }
                    responseContext = input
                    decodeRequest { catalog.requireToolRoute(name, mapper.readTree(body)) }
                    val result = agentPorts.handler.create(caller(agentPorts.callerAuthenticator.authenticate(workspaceId, credential, audience, input, requestId)), input)
                    if (result is AgentActionOutcome.Completed) requireOwnerResponse(AgentWorkspaceResponseRelations.matches(input, result.action) && result.action.status == AgentActionStatus.COMPLETED)
                    result
                }
                "monomer_edit" -> {
                    val input = decodeRequest { agentPorts.requestDecoder.edit(body).value }
                    decodeRequest { catalog.requireToolRoute(name, mapper.readTree(body)) }
                    val result = agentPorts.handler.edit(caller(agentPorts.callerAuthenticator.authenticate(workspaceId, credential, audience, input, requestId)), input)
                    if (result is AgentActionOutcome.Completed) requireOwnerResponse(AgentWorkspaceResponseRelations.matches(input, result.action) && result.action.status == AgentActionStatus.COMPLETED)
                    result
                }
                "monomer_invoke", "monomer_check" -> {
                    val input = decodeRequest { agentPorts.requestDecoder.invoke(body).value }
                    responseContext = input
                    decodeRequest { catalog.requireToolRoute(name, mapper.readTree(body)) }
                    val result = agentPorts.handler.invoke(caller(agentPorts.callerAuthenticator.authenticate(workspaceId, credential, audience, input, requestId)), input)
                    if (result is AgentActionOutcome.Completed) requireOwnerResponse(AgentWorkspaceResponseRelations.matches(input, result.action))
                    result
                }
                else -> error("Unregistered tool")
            }
            return respond(id, requestId, name, outcome, workspaceId)
        } catch (failure: OwnerResponseInvalid) {
            throw OwnerResponseInvalid(failure, responseContext)
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is IllegalArgumentException ||
                failure is cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException || failure is Unauthenticated ||
                failure is AdmissionProblem || failure is AgentWorkspaceRequestDecodingException) throw failure
            logAgentTransportFailure("MCP", requestId, failure)
            return problemError(id, requestId,
                responseContext?.let(AgentTransportProblems::ownerUnavailable) ?: AgentTransportProblems.ownerUnavailable, name)
        }
    }

    private suspend fun respond(id: JsonNode, requestId: AgentRequestId, name: String, outcome: Any, workspaceId: WorkspaceId?): ServerResponse = when (outcome) {
            is AgentListingOutcome.Found -> toolResult(id, ownerProjection { validator.agentListing(AgentWorkspaceJson.agentListingJson(requestId, outcome.listing)) })
            is AgentResourceOutcome.Found -> toolResult(id, ownerProjection { validator.agentResource(AgentWorkspaceJson.agentResourceJson(requestId, outcome.resource)) }, name == "monomer_read")
            is AgentSearchOutcome.Found -> toolResult(id, ownerProjection { validator.agentSearch(mapOf("requestId" to requestId.value, "results" to outcome.response.results.map { mapOf("query" to it.query, "items" to it.items.map(AgentWorkspaceJson::listingItemJson)) })) })
            is AgentActionOutcome.Completed -> toolResult(id, ownerProjection { validator.agentAction(AgentWorkspaceJson.agentActionJson(requestId, outcome.action, workspaceId, publicApiProperties.webOrigin)) })
            is AgentListingOutcome.Rejected -> ownerError(id, requestId, name, outcome.problem)
            is AgentResourceOutcome.Rejected -> ownerError(id, requestId, name, outcome.problem)
            is AgentSearchOutcome.Rejected -> ownerError(id, requestId, name, outcome.problem)
            is AgentActionOutcome.Rejected -> ownerError(id, requestId, name, outcome.problem)
            AgentListingOutcome.NotFound, AgentResourceOutcome.NotFound, AgentSearchOutcome.NotFound, AgentActionOutcome.NotFound -> problemError(id, requestId, AgentTransportProblems.resourceNotFound, name)
            AgentListingOutcome.Forbidden, AgentResourceOutcome.Forbidden, AgentActionOutcome.Forbidden -> problemError(id, requestId, AgentTransportProblems.forbidden, name)
            AgentListingOutcome.Unavailable, AgentResourceOutcome.Unavailable, AgentActionOutcome.Unavailable -> problemError(id, requestId, AgentTransportProblems.ownerUnavailable, name)
            else -> problemError(id, requestId, AgentTransportProblems.ownerResponseInvalid, name)
        }

    private suspend fun dispatchOrganization(id: JsonNode, name: String, organizationId: OrganizationId, credential: PresentedCredential, requestId: AgentRequestId, body: String): ServerResponse {
        val caller = when (val result = AgentOrganizationCaller.authenticate(organizationId, oauth, credential, AgentWorkspaceAdmissionAudience.MCP, requestId)) {
            is OAuthOutcome.Success -> result.value
            is OAuthOutcome.Failure -> throw Unauthenticated()
        }
        val outcome: Any = when (name) {
            "monomer_list" -> managementOwnerPort.list(caller, decodeRequest { agentPorts.requestDecoder.list(body) })
            "monomer_read" -> {
                val input = decodeRequest { agentPorts.requestDecoder.read(body) }
                managementOwnerPort.read(caller, input).also { if (it is AgentResourceOutcome.Found) requireOwnerResponse(AgentWorkspaceResponseRelations.matches(input, it.resource) && (it.resource !is AgentResourceEnvelope.Organization || (it.resource as AgentResourceEnvelope.Organization).body.id == caller.organizationId.value)) }
            }
            "monomer_create" -> {
                val input = decodeRequest { agentPorts.requestDecoder.create(body).value }
                decodeRequest { catalog.requireToolRoute(name, mapper.readTree(body)) }
                managementOwnerPort.create(caller, input).also { if (it is AgentActionOutcome.Completed) requireOwnerResponse(AgentWorkspaceResponseRelations.matches(input, it.action) && it.action.status == AgentActionStatus.COMPLETED) }
            }
            "monomer_invoke", "monomer_check" -> {
                val input = decodeRequest { agentPorts.requestDecoder.invoke(body).value }
                decodeRequest { catalog.requireToolRoute(name, mapper.readTree(body)) }
                managementOwnerPort.invoke(caller, input).also { if (it is AgentActionOutcome.Completed) requireOwnerResponse(AgentWorkspaceResponseRelations.matches(input, it.action) && it.action.status == AgentActionStatus.COMPLETED) }
            }
            else -> throw AgentWorkspaceRequestDecodingException("path", "Organization resources support list, read, create and invoke.")
        }
        return respond(id, requestId, name, outcome, null)
    }

    private inline fun <Input> decodeRequest(decode: () -> Input): Input = try {
        decode()
    } catch (failure: AgentWorkspaceRequestDecodingException) {
        throw failure
    } catch (_: IllegalArgumentException) {
        throw AgentWorkspaceRequestDecodingException("arguments", "The arguments do not match the tool contract. Use monomer_describe and the task guide from monomer_docs.")
    }

    private inline fun <Projection> ownerProjection(project: () -> Projection): Projection = try {
        project()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw OwnerResponseInvalid(failure)
    }

    private fun requireOwnerResponse(valid: Boolean) {
        if (!valid) throw OwnerResponseInvalid()
    }

    private suspend fun internalFailure(id: JsonNode, requestId: AgentRequestId): ServerResponse = result(id, mapOf(
        "isError" to true,
        "content" to listOf(mapOf("type" to "text", "text" to mapper.writeValueAsString(validator.problem(linkedMapOf(
            "type" to "https://monomer.cc/problems/internal-error", "title" to "Internal Server Error", "status" to 500,
            "detail" to "The Agent request could not be completed. Read the target resource before resending a create or start request.",
            "instance" to "/mcp", "code" to "INTERNAL_ERROR", "requestId" to requestId.value, "traceId" to requestId.value, "retryable" to false,
        ))))),
    ))

    private suspend fun ownerError(id: JsonNode, requestId: AgentRequestId, name: String, problem: AgentWorkspaceProblem): ServerResponse {
        if (problem.status == AgentWorkspaceProblemStatus.UNAUTHORIZED) return unauthorized()
        if (name == "monomer_search" && problem.status in setOf(AgentWorkspaceProblemStatus.CONFLICT, AgentWorkspaceProblemStatus.TOO_MANY_REQUESTS)) return problemError(id, requestId, AgentTransportProblems.ownerResponseInvalid)
        return problemError(id, requestId, problem, name)
    }

    private suspend fun invalidArguments(id: JsonNode, requestId: AgentRequestId, violations: List<AgentProblemViolation>, tool: String? = null): ServerResponse =
        problemError(id, requestId, AgentTransportProblems.requestInvalid(violations), tool, inputSchemaError = true)

    private suspend fun problemError(id: JsonNode, requestId: AgentRequestId, problem: AgentWorkspaceProblem, tool: String? = null, inputSchemaError: Boolean = false): ServerResponse {
        val body = linkedMapOf<String, Any?>(
            "type" to "https://monomer.cc/problems/${problem.code.lowercase().replace('_', '-')}", "title" to problem.title,
            "detail" to problem.detail, "status" to problem.status.value, "instance" to "/mcp", "code" to problem.code,
            "requestId" to requestId.value, "traceId" to requestId.value, "retryable" to problem.retryable,
        )
        problem.currentRevision?.let { body["current"] = mapOf("revision" to it.toString()) }
        if (problem.hints.isNotEmpty()) body["hints"] = problem.hints
        if (problem.nextActions.isNotEmpty()) body["nextActions"] = problem.nextActions.map(AgentWorkspaceJson::availableOperationJson)
        problem.roleRequirement?.let { requirement ->
            body["requiredRole"] = requirement.requiredRole
            body["currentRole"] = requirement.currentRole
            body["askOwners"] = requirement.askOwners
        }
        if (problem.partitionFilters.isNotEmpty()) body["partitionFilters"] = problem.partitionFilters.map { it.toHttpValue() }
        if (problem.violations.isNotEmpty()) body["violations"] = problem.violations.map { violation -> linkedMapOf<String, Any?>("field" to violation.field, "code" to violation.code, "message" to violation.message).apply {
            violation.operationIndex?.let { put("operationIndex", it) }; violation.path?.let { put("path", it) }
        } }
        val retryAfterSeconds = AgentTransportProblems.retryAfterSeconds(problem.status.value, problem.retryable, problem.retryAfterSeconds)
        val correctTool = when (tool) { "monomer_invoke" -> "monomer_check"; "monomer_check" -> "monomer_invoke"; else -> null }
        val wrongTool = problem.violations.singleOrNull()?.takeIf {
            inputSchemaError && problem.code == "AGENT_REQUEST_INVALID" && correctTool != null && it.field == "action" && it.message.startsWith("Use $correctTool for this available operation.")
        }
        val hint = if (wrongTool != null) "${wrongTool.message}\n" else if (inputSchemaError && tool in setOf("monomer_create", "monomer_edit", "monomer_invoke")) "Use monomer_describe with the availableOperations or nextActions entry to confirm this operation's input schema.\n" else ""
        val text = ownerProjection { hint + mapper.writeValueAsString(validator.problem(body)) }
        return result(id, mapOf("isError" to true, "content" to listOf(mapOf("type" to "text", "text" to text))), retryAfterSeconds)
    }

    private suspend fun toolResult(id: JsonNode, body: Map<String, Any?>, viewContract: Boolean = false): ServerResponse {
        val content = mutableListOf<Map<String, Any?>>(mapOf("type" to "text", "text" to ownerProjection { mapper.writeValueAsString(body) }))
        val download = (body["body"] as? Map<*, *>)?.get("download") as? Map<*, *>
        if (download != null) content += mapOf(
            "type" to "resource_link", "uri" to download["url"],
            "name" to download["fileName"], "mimeType" to download["mediaType"],
        )
        val response = linkedMapOf<String, Any?>("structuredContent" to body, "content" to content, "isError" to false)
        if (viewContract) response["_meta"] = VIEW_CONTRACT_META
        val bytes = ownerProjection { mapper.writeValueAsBytes(mapOf("jsonrpc" to "2.0", "id" to id, "result" to response)) }
        return ServerResponse.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(bytes).awaitSingle()
    }
    private suspend fun result(id: JsonNode, body: Any, retryAfterSeconds: Long? = null): ServerResponse = json(200, mapOf("jsonrpc" to "2.0", "id" to id, "result" to body), retryAfterSeconds)
    private suspend fun rpcError(id: JsonNode?, code: Int, message: String, status: Int = 200): ServerResponse = json(status, mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message)))
    private suspend fun json(status: Int, body: Any, retryAfterSeconds: Long? = null): ServerResponse = ServerResponse.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").apply { retryAfterSeconds?.let { header(HttpHeaders.RETRY_AFTER, it.toString()) } }.contentType(MediaType.APPLICATION_JSON).bodyValue(mapper.writeValueAsBytes(body)).awaitSingle()
    private suspend fun unauthorized(): ServerResponse = ServerResponse.status(401).header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"${properties.resourceMetadataUrl}\", scope=\"agent.workspace\"").build().awaitSingle()
    private suspend fun empty(status: Int): ServerResponse = ServerResponse.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").build().awaitSingle()
    private class OwnerResponseInvalid(cause: Exception? = null, val input: Any? = null) : RuntimeException(cause)
    private class Unauthenticated : RuntimeException()
    private class AdmissionProblem(val problem: AgentWorkspaceProblem) : RuntimeException()
    private companion object {
        val VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        val TOKEN = Regex("[A-Za-z0-9._~+/=-]+")
        val DEFINITION_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")
        val VIEW_CONTRACT_META = mapOf(McpDashboardView.CONTRACT_META_KEY to McpDashboardView.VIEW_CONTRACT)
    }
}
