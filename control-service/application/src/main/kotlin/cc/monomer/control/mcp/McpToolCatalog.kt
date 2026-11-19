package cc.monomer.control.mcp

import cc.monomer.agent.api.AgentPathPatterns
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

class McpToolCatalog(private val agentApiDocs: String) {
    private val mapper = ObjectMapper()
    private val descriptions = mapOf(
        "list" to "Start with {\"path\":\"workspaces\"} or {\"path\":\"organizations\"} and no other fields to discover targets, then call monomer_read with path workspace. List a resource collection with workspaceId for workspace paths or organizationId for organization paths (never both), required integer limit (1–100) and optional cursor. Agent API: $agentApiDocs",
        "read" to "Read a resource snapshot. $TARGET_ID_RULE Start a Workspace at path workspace and follow availableOperations in responses. After a write, read result.path for the current revision and next operations. Optional download: arrow requests an Explore result download link. Metric sourceConnectors are definition candidates; monomer_check validates them. Agent API: $agentApiDocs",
        "search" to "Find named Workspace resources or Pages by display title using queries, kinds and limitPerQuery. Pass workspaceId. Resolve names referenced inside definitions here, then read the matching path. Agent API: $agentApiDocs",
        "describe" to "Accept 1–10 availableOperations or Problem nextActions entries verbatim in operations. Return each operation's named tool and complete argumentsSchema as compact JSON text, with shared definitions once. Inputless actions report no input. Reuse a returned schema for the same entry during this session. Agent API: $agentApiDocs",
        "check" to "Validate an unsaved Semantic Model or Metric without saving or contacting a warehouse. Accepts only advertised check operations. Pass workspaceId. Get the exact arguments with monomer_describe first. Agent API: $agentApiDocs",
        "create" to "Create a resource from an advertised CREATE operation with path and input. $TARGET_ID_RULE Get the exact arguments with monomer_describe first. $WRITE_TOOL_GUIDANCE Agent API: $agentApiDocs",
        "edit" to "Edit a resource from an advertised EDIT operation. Pass workspaceId. The path determines required top-level fields such as revision, patch and commandId; do not wrap them in input. Get the exact arguments with monomer_describe first. $WRITE_TOOL_GUIDANCE Agent API: $agentApiDocs",
        "invoke" to "Perform an advertised action, including executions, deletes and restores. $TARGET_ID_RULE Omit input for inputless actions. Check operations belong to monomer_check. Get the exact arguments with monomer_describe first. $WRITE_TOOL_GUIDANCE Agent API: $agentApiDocs",
    )
    private val documents = listOf(
        "contracts/agent-workspace.schema.json", "contracts/connector/connector.schema.json",
        "contracts/problem-details.schema.json", "contracts/semantic/model.schema.json",
        "contracts/semantic/metric.schema.json", "contracts/semantic/query.schema.json",
        "contracts/dashboard/dashboard.schema.json", "contracts/control-service.openapi.json",
        "contracts/metric-display.schema.json",
    ).map { path -> javaClass.classLoader.getResourceAsStream(path)?.use(mapper::readTree) ?: error("Missing contract: $path") }
    private val definitions = documents.first().path("\$defs")
    private val schemaFactory = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)

    internal data class OperationBranch(
        val availableBranch: Int,
        val operation: String,
        val pathKind: JsonNode,
        val action: String?,
        val inputKind: String?,
        val requestSchema: JsonNode,
        val tool: String,
        val pathValidator: com.networknt.schema.JsonSchema,
    )

    internal val operationBranches: List<OperationBranch> = definitions.path("AgentAvailableOperation").path("oneOf").flatMapIndexed { availableIndex, available ->
        val properties = available.path("properties")
        val operation = properties.path("operation").path("const").asText()
        val action = properties.path("action").get("const")?.asText()
        val inputKind = properties.path("inputKind").get("const")?.asText()
        val requestName = "Agent${operation.lowercase().replaceFirstChar(Char::uppercase)}Request"
        val candidates = alternatives(definitions.path(requestName))
        pathKinds(properties.path("path")).map { pathKind ->
            val request = if (operation in setOf("LIST", "READ")) definitions.path(requestName) else candidates.single { candidate ->
                val requestProperties = candidate.path("properties")
                pathKinds(requestProperties.path("path")).any { it == pathKind } &&
                    requestProperties.path("action").get("const")?.asText() == action &&
                    inputKinds(requestProperties.path("input")).contains(inputKind)
            }
            val exactRequest = request.deepCopy<JsonNode>()
            if (inputKind != null && inputKinds(request.path("properties").path("input")).size > 1) {
                (exactRequest.path("properties") as ObjectNode).set<JsonNode>("input", alternatives(request.path("properties").path("input")).single {
                    it.path("properties").path("kind").path("const").asText() == inputKind
                })
            }
            OperationBranch(availableIndex, operation, pathKind, action, inputKind, exactRequest,
                if (operation == "INVOKE" && action == "check") "monomer_check" else "monomer_${operation.lowercase()}",
                schemaFactory.getSchema(reachableSchema(bundle(pathKind, 0) as ObjectNode)))
        }
    }

    private val dashboardValueSchema = schemaFactory.getSchema(reachableSchema(bundle(definitions.path("DashboardValue"), 0) as ObjectNode))

    val instructions: String get() = INSTRUCTIONS

    fun dashboardValue(value: JsonNode): Boolean = dashboardValueSchema.validate(value).isEmpty()

    val tools: List<Map<String, Any>> = listOf("list", "read", "search", "docs", "describe", "show", "check", "create", "edit", "invoke").map { operation ->
        if (operation == "docs") docs() else if (operation == "show") show() else {
            val readOnly = operation in setOf("list", "read", "search", "describe", "check")
            linkedMapOf<String, Any>(
                "name" to "monomer_$operation", "title" to "Monomer $operation",
                "description" to descriptions.getValue(operation),
                "inputSchema" to when (operation) {
                    "list" -> listInputSchema()
                    "search" -> inputSchema("AgentSearchRequest")
                    "describe" -> describeSchema()
                    else -> thinInputSchema(operation)
                },
                "annotations" to mapOf("readOnlyHint" to readOnly, "destructiveHint" to !readOnly, "idempotentHint" to readOnly, "openWorldHint" to (operation == "invoke")),
            ).apply {
                if (operation != "describe") put("outputSchema", minimumOutputSchema(operation))
            }
        }
    }

    fun describe(arguments: JsonNode): ObjectNode {
        if (arguments.fieldNames().asSequence().toSet() != setOf("operations")) invalid("arguments", "Pass only operations copied from availableOperations or Problem nextActions; no target ID is needed.")
        val operations = arguments.path("operations")
        if (!operations.isArray || operations.size() !in 1..10) invalid("operations", "Supply between 1 and 10 operation entries.")
        val response = mapper.createObjectNode().set<ObjectNode>("operations", mapper.createArrayNode().apply {
            operations.forEachIndexed { index, entry ->
                val branch = describedBranch(entry, "operations[$index]")
                add(mapper.createObjectNode().apply {
                    put("tool", branch.tool)
                    if (branch.operation == "INVOKE" && branch.inputKind == null) put("input", "no input")
                    val target = if (entry.path("path").asText().startsWith("organization")) "organizationId" else "workspaceId"
                    set<JsonNode>("argumentsSchema", bundle(addArgumentsTarget(branch.requestSchema, target), 0))
                })
            }
        })
        return reachableSchema(response)
    }

    private fun addArgumentsTarget(original: JsonNode, target: String): ObjectNode {
        val schema = dereference(original).deepCopy<JsonNode>() as ObjectNode
        if (schema.has("oneOf")) {
            schema.set<JsonNode>("oneOf", mapper.createArrayNode().apply { schema.path("oneOf").forEach { add(addArgumentsTarget(it, target)) } })
        } else addTarget(schema, target)
        return schema
    }

    fun schemaForRequest(operation: String): ObjectNode = reachableSchema(bundle(definitions.path("Agent${operation.lowercase().replaceFirstChar(Char::uppercase)}Request"), 0) as ObjectNode)

    fun requireToolRoute(tool: String, request: JsonNode) {
        val operation = when (tool) {
            "monomer_check", "monomer_invoke" -> "INVOKE"
            "monomer_create" -> "CREATE"
            "monomer_edit" -> "EDIT"
            else -> return
        }
        val descriptor = mapper.createObjectNode().put("operation", operation).set<ObjectNode>("path", request.path("path"))
        request.get("action")?.let { descriptor.set<JsonNode>("action", it) }
        request.path("input").get("kind")?.let { descriptor.set<JsonNode>("inputKind", it) }
        val branch = describedBranch(descriptor, "arguments")
        if (branch.tool != tool) invalid("action", "Use ${branch.tool} for this available operation. monomer_check accepts only contract check operations; monomer_invoke does not accept check.")
    }

    private fun describedBranch(entry: JsonNode, location: String): OperationBranch {
        if (!entry.isObject) invalid(location, "Copy an operation object verbatim from availableOperations or Problem nextActions.")
        if (entry.fieldNames().asSequence().any { it !in setOf("operation", "path", "action", "inputKind") }) invalid(location, "Only operation, path, action and inputKind from the advertised entry are allowed.")
        for (field in listOf("operation", "path")) if (!entry.path(field).isTextual) invalid("$location.$field", "The advertised $field string is required.")
        for (field in listOf("action", "inputKind")) if (entry.has(field) && !entry.path(field).isTextual) invalid("$location.$field", "Use the advertised $field string or omit this field when it is absent.")
        val candidates = operationBranches.filter { branch -> branch.operation == entry.path("operation").asText() &&
            branch.action == entry.get("action")?.asText() && branch.inputKind == entry.get("inputKind")?.asText() &&
            branch.pathValidator.validate(entry.path("path")).isEmpty()
        }
        if (candidates.size != 1) invalid(location, "This operation, path, action and inputKind combination is not declared. Copy an entry from availableOperations or Problem nextActions verbatim.")
        return candidates.single()
    }

    private fun invalid(field: String, message: String): Nothing = throw cc.monomer.agent.api.AgentWorkspaceRequestDecodingException(field, message)

    private fun dereference(node: JsonNode): JsonNode = if (node.has("\$ref")) {
        val reference = node.path("\$ref").asText()
        check(reference.startsWith("#/")) { "Operation mapping must use the Agent contract's local branches" }
        dereference(documents.first().at(reference.removePrefix("#"))).deepCopy<JsonNode>().also { resolved ->
            if (resolved is ObjectNode) node.properties().filter { it.key != "\$ref" }.forEach { (key, value) -> resolved.set<JsonNode>(key, value) }
        }
    } else node

    private fun alternatives(node: JsonNode): List<JsonNode> {
        val resolved = dereference(node)
        return if (resolved.has("oneOf")) resolved.path("oneOf").flatMap(::alternatives) else listOf(resolved)
    }

    private fun inputKinds(node: JsonNode): Set<String?> = if (node.isMissingNode) setOf(null) else alternatives(node).map { it.path("properties").path("kind").get("const")?.asText() }.toSet()

    private fun pathKinds(node: JsonNode): List<JsonNode> = when {
        node.has("oneOf") -> node.path("oneOf").flatMap(::pathKinds)
        node.has("\$ref") && dereference(node).has("enum") -> pathKinds(dereference(node))
        node.has("enum") -> node.path("enum").map { mapper.createObjectNode().set<JsonNode>("const", it) }
        else -> listOf(node)
    }

    private fun describeSchema(): Map<String, Any> = mapOf("type" to "object", "additionalProperties" to false, "required" to listOf("operations"),
        "properties" to mapOf("operations" to mapOf("type" to "array", "minItems" to 1, "maxItems" to 10, "items" to mapOf(
            "type" to "object", "additionalProperties" to false, "required" to listOf("operation", "path"), "properties" to mapOf(
                "operation" to mapOf("type" to "string", "enum" to listOf("LIST", "READ", "CREATE", "EDIT", "INVOKE")),
                "path" to mapOf("type" to "string"), "action" to mapOf("type" to "string"), "inputKind" to mapOf("type" to "string"),
            ),
        ))))

    private fun thinInputSchema(operation: String): Map<String, Any> {
        val properties = linkedMapOf<String, Any>("workspaceId" to mapOf("type" to "string", "format" to "uuid"),
            "organizationId" to mapOf("type" to "string", "format" to "uuid"), "path" to mapOf("type" to "string"))
        if (operation in setOf("invoke", "check")) properties["action"] = mapOf("type" to "string")
        if (operation == "read") properties["download"] = mapOf("type" to "string", "enum" to listOf("arrow"))
        if (operation !in setOf("edit", "read")) properties["input"] = mapOf("type" to "object")
        if (operation == "edit") {
            alternatives(definitions.path("AgentEditRequest")).forEach { branch ->
                branch.path("properties").properties().forEach { (field, schema) ->
                    if (field !in properties && schema.isObject) {
                        properties[field] = thinArgumentShape(schema, 0)
                    }
                }
            }
            properties["revision"] = (definitions.path("RevisionString").deepCopy<JsonNode>() as ObjectNode).put("description", EDIT_REVISION_DESCRIPTION)
        }
        return mapOf("type" to "object", "additionalProperties" to (operation == "edit"), "properties" to properties,
            "required" to when (operation) { "create" -> listOf("path", "input"); "edit", "read" -> listOf("path"); else -> listOf("path", "action") },
            "oneOf" to listOf(mapOf("required" to listOf("workspaceId"), "not" to mapOf("required" to listOf("organizationId")), "properties" to mapOf("path" to mapOf("pattern" to "^workspace(?:/|$)"))),
                mapOf("required" to listOf("organizationId"), "not" to mapOf("required" to listOf("workspaceId")), "properties" to mapOf("path" to mapOf("pattern" to "^organization(?:/|$)")))))
    }

    private fun thinArgumentShape(argument: JsonNode, documentIndex: Int): ObjectNode {
        fun shape(node: JsonNode, currentDocument: Int): ObjectNode? {
            if (node.has("\$ref")) {
                val reference = node.path("\$ref").asText()
                val base = reference.substringBefore('#')
                val targetDocument = if (base.isEmpty()) currentDocument else documents.indexOfFirst { it.path("\$id").asText() == base }
                check(targetDocument >= 0) { "Unknown owning schema reference" }
                return shape(documents[targetDocument].at(reference.substringAfter('#', "")), targetDocument)
            }
            val type = node.get("type")
            if (type != null) return mapper.createObjectNode().apply {
                set<JsonNode>("type", type)
                if (type.asText() == "array") set<JsonNode>("items", checkNotNull(shape(node.path("items"), currentDocument)))
            }
            val shapes = listOf("oneOf", "anyOf", "allOf").flatMap { keyword -> node.path(keyword).mapNotNull { shape(it, currentDocument) } }.distinct()
            check(shapes.size <= 1) { "Thin argument alternatives must have the same JSON shape" }
            return shapes.singleOrNull()
        }
        return checkNotNull(shape(argument, documentIndex)) { "Thin argument must declare its JSON shape" }
    }

    private fun minimumOutputSchema(operation: String): Map<String, Any> {
        fun envelope(required: List<String>, fields: Map<String, Any>) = mapOf("type" to "object", "required" to required, "properties" to fields)
        val requestId = mapOf("requestId" to mapOf("type" to "string"))
        return when (operation) {
            "list" -> mapOf("type" to "object", "oneOf" to listOf(
                envelope(listOf("requestId", "items"), requestId + ("items" to mapOf("type" to "array"))),
                envelope(listOf("organizations"), mapOf("organizations" to mapOf("type" to "array"))),
                envelope(listOf("workspaces"), mapOf("workspaces" to mapOf("type" to "array"))),
            ))
            "read" -> envelope(listOf("requestId", "kind", "path"), requestId + mapOf("kind" to mapOf("type" to "string"), "path" to mapOf("type" to "string")))
            "search" -> envelope(listOf("requestId", "results"), requestId + ("results" to mapOf("type" to "array")))
            else -> envelope(listOf("requestId", "operation", "path", "status", "result"), requestId + mapOf("operation" to mapOf("type" to "string"), "path" to mapOf("type" to "string"), "status" to mapOf("type" to "string"), "result" to mapOf("type" to "object")))
        }
    }

    private fun show(): Map<String, Any> {
        val string = mapOf("type" to "string")
        return linkedMapOf(
            "name" to "monomer_show", "title" to "Show in chat",
            "description" to "Show a Dashboard Draft or Session to the user as a live view inside the chat when the client supports MCP Apps; other clients get a Monomer link. For a Draft, call it once as soon as the Draft exists and before editing it: the view follows later edits and finished runs by itself. For a Session, pass the variables and queryRuns from the resolve or refresh result, and call again after a new resolve or refresh. Agent API: $agentApiDocs",
            "inputSchema" to mapOf(
                "type" to "object", "additionalProperties" to false, "required" to listOf("workspaceId", "path"),
                "properties" to linkedMapOf(
                    "workspaceId" to mapOf("type" to "string", "format" to "uuid"),
                    "path" to mapOf("type" to "string", "pattern" to "^workspace/dashboards/${AgentPathPatterns.UUID_V7}/(?:draft|sessions/${AgentPathPatterns.UUID_V7})$",
                        "description" to "A Draft path workspace/dashboards/{dashboardId}/draft or a Session path workspace/dashboards/{dashboardId}/sessions/{sessionId}."),
                    "selection" to mapOf(
                        "type" to "object", "additionalProperties" to false, "required" to listOf("variables", "queryRuns"),
                        "description" to "Session only. Copy variables and queryRuns unchanged from the resolve or refresh result.",
                        "properties" to linkedMapOf("variables" to mapOf("type" to "object"), "queryRuns" to mapOf("type" to "object", "additionalProperties" to string)),
                    ),
                ),
            ),
            "annotations" to mapOf("readOnlyHint" to true, "destructiveHint" to false, "idempotentHint" to true, "openWorldHint" to false),
            "outputSchema" to mapOf(
                "type" to "object",
                "required" to listOf("requestId", "viewContract", "workspaceId", "dashboardId", "title", "target", "webUrl", "shownAt"),
                "properties" to linkedMapOf(
                    "requestId" to string, "viewContract" to mapOf("type" to "integer"), "workspaceId" to string, "dashboardId" to string,
                    "title" to string, "target" to mapOf("type" to "object"), "webUrl" to string, "shownAt" to mapOf("type" to "integer"),
                ),
            ),
            "_meta" to mapOf("ui" to mapOf("resourceUri" to McpDashboardView.URI), "ui/resourceUri" to McpDashboardView.URI),
        )
    }

    private fun docs(): Map<String, Any> = mapOf(
        "name" to "monomer_docs", "title" to "Read Monomer documentation",
        "description" to "Read task guides for connecting data, defining metrics, analyzing data, dashboards, Pages, and access. For an unfamiliar task, start with no arguments for the index, then follow document paths such as /en/analyze/llms.txt. Documentation paths are scoped to Monomer’s public guides.",
        "inputSchema" to mapOf(
            "type" to "object", "additionalProperties" to false,
            "properties" to mapOf("path" to mapOf(
                "type" to "string", "maxLength" to 512,
                "description" to "The URL path of a Markdown page or llms.txt index, without origin, query, or fragment. Omit to read the root index.",
                "pattern" to "^/(?:llms\\.txt|en/(?:[a-z0-9]+(?:-[a-z0-9]+)*/)*(?:llms\\.txt|index\\.md))$",
            )),
        ),
        "annotations" to mapOf("readOnlyHint" to true, "destructiveHint" to false, "idempotentHint" to true, "openWorldHint" to false),
    )

    private companion object {
        const val INSTRUCTIONS = "Discover Workspaces with monomer_list and {\"path\":\"workspaces\"}, or Organizations with {\"path\":\"organizations\"}. Discovery accepts only path. Select workspaceId for workspace paths or organizationId for organization paths; supply exactly one target ID. monomer_docs and monomer_describe need no target ID. Read the workspace root, then follow response availableOperations. Before supplying operation inputs, copy the advertised entry verbatim into monomer_describe; reuse that entry's schema during this session. After a write, confirm result.path with monomer_read for resources or monomer_list for COLLECTION results to obtain revision and next operations. Resolve name references in definitions with monomer_search. Resource lists require limit, an integer from 1 to 100 (for example, 20). Use monomer_check for advertised check operations. For unfamiliar tasks, start monomer_docs with no arguments and follow linked guides. Never guess an ambiguous target before making changes. Show a Dashboard Draft with monomer_show as soon as the Draft exists and before editing it, so the user can watch it in the chat when supported: right after the Draft start returns, for a new or an existing Dashboard. Call it once per Draft; the view follows later edits and finished runs. For a Session, call monomer_show after each resolve or refresh with the returned variables and queryRuns."
        const val EDIT_REVISION_DESCRIPTION = "Decimal string copied unchanged from the READ revision, for example \"3\"; never a JSON number. Send it only when the path's monomer_describe schema requires it; other EDIT paths reject it."
        const val LIST_LIMIT_DESCRIPTION = "Required page size for workspace/ and organization/ listings, 1 to 100, for example 20. Omit it for discovery: path workspaces or organizations takes only path."
        const val TARGET_ID_RULE = "Pass workspaceId for workspace paths or organizationId for organization paths, never both."
        const val WRITE_TOOL_GUIDANCE = "Never guess an ambiguous target. Afterwards, confirm result.path with monomer_read, or monomer_list for a COLLECTION."
    }

    private fun bundle(node: JsonNode, documentIndex: Int): JsonNode = when {
        node.isObject -> mapper.createObjectNode().apply {
            node.properties().forEach { (key, value) ->
                if (key != "\$id" && key != "\$schema") {
                    if (key == "\$ref") {
                        val reference = value.asText()
                        val base = reference.substringBefore('#')
                        val index = if (base.isEmpty()) documentIndex else documents.indexOfFirst { it.path("\$id").asText() == base }
                        require(index >= 0) { "Unknown owning schema reference" }
                        val fragment = reference.substringAfter('#', "")
                        put(key, "#/\$defs/document$index$fragment")
                    } else set<JsonNode>(key, bundle(value, documentIndex))
                }
            }
        }
        node.isArray -> mapper.createArrayNode().apply { node.forEach { add(bundle(it, documentIndex)) } }
        else -> node.deepCopy()
    }

    private fun inputSchema(name: String): ObjectNode = reachableSchema(addWorkspace(documents.first().path("\$defs").path(name)).apply {
        put("type", "object")
    }).apply {
        if (has("oneOf")) {
            val branches = path("oneOf")
            set<JsonNode>("properties", mapper.createObjectNode().apply {
                branches.flatMap { it.path("properties").fieldNames().asSequence().toList() }.distinct().forEach { field ->
                    val alternatives = branches.mapNotNull { it.path("properties").get(field) }.distinct()
                    set<JsonNode>(field, if (alternatives.size == 1) alternatives.single() else thinArgumentShape(definitions.path(name).path("properties").path(field), 0))
                }
            })
        }
    }

    private fun addWorkspace(original: JsonNode): ObjectNode {
        if (original.has("\$ref")) return addWorkspace(documents.first().at(original.path("\$ref").asText().removePrefix("#")))
        val result = bundle(original, 0) as ObjectNode
        if (original.has("oneOf")) {
            result.set<JsonNode>("oneOf", mapper.createArrayNode().apply { original.path("oneOf").forEach { add(addWorkspace(it)) } })
        } else {
            val path = original.path("properties").path("path")
            val target = if (path.has("\$ref")) documents.first().at(path.path("\$ref").asText().removePrefix("#")) else path
            if (target.path("const").asText().startsWith("organization") || target.path("pattern").asText().startsWith("^organization")) return addTarget(result, "organizationId")
            if (path.path("\$ref").asText() == "#/\$defs/CanonicalResourcePath") {
                val organization = result.deepCopy().apply {
                    (path("properties") as ObjectNode).set<JsonNode>("path", mapper.createObjectNode().set<JsonNode>("allOf", mapper.createArrayNode().add(path("properties").path("path")).add(mapper.createObjectNode().put("pattern", "^organization(?:/|$)"))))
                }
                (result.path("properties") as ObjectNode).set<JsonNode>("path", mapper.createObjectNode().set<JsonNode>("allOf", mapper.createArrayNode().add(result.path("properties").path("path")).add(mapper.createObjectNode().put("pattern", "^workspace(?:/|$)"))))
                (result.path("properties") as ObjectNode).set<JsonNode>("workspaceId", mapper.createObjectNode().put("type", "string").put("format", "uuid"))
                (result.path("required") as com.fasterxml.jackson.databind.node.ArrayNode).add("workspaceId")
                return mapper.createObjectNode().set<ObjectNode>("oneOf", mapper.createArrayNode().add(addTarget(organization, "organizationId")).add(result))
            }
            (result.path("properties") as ObjectNode).set<JsonNode>("workspaceId", mapper.createObjectNode().put("type", "string").put("format", "uuid"))
            (result.path("required") as com.fasterxml.jackson.databind.node.ArrayNode).add("workspaceId")
        }
        return result
    }

    private fun addTarget(schema: ObjectNode, field: String): ObjectNode = schema.apply {
        (path("properties") as ObjectNode).set<JsonNode>(field, mapper.createObjectNode().put("type", "string").put("format", "uuid"))
        (path("required") as com.fasterxml.jackson.databind.node.ArrayNode).add(field)
    }

    private fun reachableSchema(root: ObjectNode): ObjectNode {
        val references = linkedMapOf<String, String>()
        val reachable = mapper.createObjectNode()
        fun resolve(node: JsonNode): JsonNode = when {
            node.isObject -> mapper.createObjectNode().apply {
                node.properties().forEach { (key, value) ->
                    if (key == "\$ref") {
                        val reference = value.asText()
                        val name = references[reference] ?: "d${references.size}".also { name ->
                            references[reference] = name
                            val address = reference.removePrefix("#/\$defs/document")
                            val documentIndex = address.substringBefore('/').toInt()
                            val pointer = address.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
                            val target = documents[documentIndex].at(pointer)
                            check(!target.isMissingNode) { "Unresolved owning schema reference" }
                            reachable.set<JsonNode>(name, resolve(bundle(target, documentIndex)))
                        }
                        put(key, "#/\$defs/$name")
                    } else if (key != "\$defs") set<JsonNode>(key, resolve(value))
                }
            }
            node.isArray -> mapper.createArrayNode().apply { node.forEach { add(resolve(it)) } }
            else -> node.deepCopy()
        }
        return (resolve(root) as ObjectNode).apply {
            if (!reachable.isEmpty) set<JsonNode>("\$defs", reachable)
        }
    }

    private fun listInputSchema(): ObjectNode {
        val discovery = mapper.createObjectNode().apply {
            put("type", "object")
            put("additionalProperties", false)
            set<JsonNode>("required", mapper.createArrayNode().add("path"))
            set<JsonNode>("properties", mapper.createObjectNode().apply {
                set<JsonNode>("path", mapper.createObjectNode().put("type", "string").set<JsonNode>("enum", mapper.createArrayNode().add("organizations").add("workspaces")))
            })
        }
        val resourceBranches = addWorkspace(documents.first().path("\$defs").path("AgentListRequest")).path("oneOf")
        val branches = mapper.createArrayNode().add(discovery).apply { resourceBranches.forEach { add(it) } }
        val properties = mapper.createObjectNode()
        branches.flatMap { it.path("properties").fieldNames().asSequence().toList() }.distinct().forEach { field ->
            val alternatives = branches.mapNotNull { it.path("properties").get(field) }.distinct()
            properties.set<JsonNode>(field, if (alternatives.size == 1) alternatives.single() else mapper.createObjectNode().apply {
                put("type", "string")
                set<JsonNode>("anyOf", mapper.createArrayNode().apply { alternatives.forEach { add(it) } })
            })
        }
        (properties.get("limit") as? ObjectNode)?.let { limit ->
            properties.set<JsonNode>("limit", limit.deepCopy().put("description", LIST_LIMIT_DESCRIPTION))
        }
        return reachableSchema(mapper.createObjectNode().apply {
            put("type", "object")
            set<JsonNode>("required", mapper.createArrayNode().add("path"))
            set<JsonNode>("properties", properties)
            set<JsonNode>("oneOf", branches)
        })
    }

}
