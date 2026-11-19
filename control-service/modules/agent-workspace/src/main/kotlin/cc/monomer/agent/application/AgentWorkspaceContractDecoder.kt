@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.application

import cc.monomer.agent.api.*
import cc.monomer.agent.api.AgentPathPatterns.SEMANTIC_NAME
import cc.monomer.agent.api.AgentPagesPaths
import cc.monomer.agent.api.AgentPageContentFormat
import cc.monomer.agent.api.AgentPageUploadCompletedPart
import cc.monomer.agent.api.AgentExploreRunRequest
import cc.monomer.agent.api.AgentCreateInput
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentJsonPatch
import cc.monomer.agent.api.AgentProblemViolation
import cc.monomer.agent.api.AgentJsonPatchOp
import cc.monomer.agent.api.AgentJsonPatchOperation
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentJsonCanonicalizer
import cc.monomer.agent.api.AgentMetricConstantProperty
import cc.monomer.agent.api.AgentMetricDefinition
import cc.monomer.agent.api.AgentMetricFilter
import cc.monomer.agent.api.AgentMetricInput
import cc.monomer.agent.api.AgentMetricWindow
import cc.monomer.agent.api.AgentMetricWindowUnit
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentRevision
import cc.monomer.agent.api.AgentSearchRequest
import cc.monomer.agent.api.AgentSemanticDimension
import cc.monomer.agent.api.AgentSemanticElement
import cc.monomer.agent.api.AgentSemanticEntity
import cc.monomer.agent.api.AgentSemanticGroupBy
import cc.monomer.agent.api.AgentSemanticMeasure
import cc.monomer.agent.api.AgentSemanticModelDefinition
import cc.monomer.agent.api.AgentSemanticTimeKind
import cc.monomer.agent.api.AgentSemanticNonAdditiveDimension
import cc.monomer.agent.api.AgentSemanticMetricTimeRange
import cc.monomer.agent.api.AgentSemanticOrder
import cc.monomer.agent.api.AgentSemanticOrderTarget
import cc.monomer.agent.api.AgentSemanticQuery
import cc.monomer.agent.api.AgentSemanticSourceRelation
import cc.monomer.agent.api.AgentSemanticTimeRangeEndpoint
import cc.monomer.agent.api.AgentSemanticFilter
import cc.monomer.agent.api.AgentTimeGranularity
import cc.monomer.agent.api.AgentWorkspaceDecodedRequest
import cc.monomer.agent.api.AgentWorkspaceRequestDecoderPort
import cc.monomer.agent.api.AgentWorkspaceRequestDecodingException
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonNodePath
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.ValidationMessage
import com.networknt.schema.SpecVersion
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

class AgentWorkspaceContractDecoder : AgentWorkspaceRequestDecoderPort {
    override fun list(body: String): AgentListRequest {
        val root = decodedRoot("AgentListRequest", body)
        return AgentListRequest(
            path = path(root.required("path")),
            cursor = root.get("cursor")?.takeUnless(JsonNode::isNull)?.textValue(),
            limit = root.required("limit").intValue(),
        )
    }

    override fun search(body: String): AgentSearchRequest {
        val root = decodedRoot("AgentSearchRequest", body)
        return AgentSearchRequest(
            path = path(root.required("path")),
            queries = root.required("queries").arrayValue().map { it.textValue() },
            kinds = root.get("kinds")?.takeUnless(JsonNode::isNull)?.arrayValue()?.map {
                enumValue<cc.monomer.agent.api.AgentResourceKind>(it)
            }?.toSet(),
            limitPerQuery = root.required("limitPerQuery").intValue(),
        )
    }

    override fun read(body: String): AgentReadRequest {
        val root = decodedRoot("AgentReadRequest", body)
        val request = AgentReadRequest(path(root.required("path")), root.get("download")?.let { cc.monomer.agent.api.AgentResultDownloadFormat.ARROW })
        val resourcePath = request.path
        if (AgentManagementPaths.organizationCollection.matches(resourcePath.value) ||
            !AgentWorkspaceAdmissionIntent.Read(resourcePath).isSupported() && AgentWorkspaceAdmissionIntent.List(resourcePath).isSupported()
        ) {
            val target = if (AgentManagementPaths.organization(resourcePath.value)) "organizationId" else "workspaceId"
            invalid("path", "Use LIST (monomer_list in MCP) to traverse this collection. Keep the same path and $target target unchanged and include limit as an integer from 1 to 100 (for example, 20). READ returns a single resource snapshot.")
        }
        return request
    }

    override fun create(body: String): AgentWorkspaceDecodedRequest<AgentCreateRequest> {
        val root = decodedRoot("AgentCreateRequest", body)
        val path = path(root.required("path"))
        val input =
            when {
                path.value == "organization/invitations" -> {
                    val value = root.required("input")
                    AgentCreateInput.OrganizationInvitation(value.required("email").textValue(),
                        value.get("workspaceAccess")?.map { access ->
                            AgentCreateInput.OrganizationInvitation.InvitationWorkspaceAccess(Uuid.parse(access.required("workspaceId").textValue()),
                                cc.monomer.identity.api.WorkspaceRole.valueOf(access.required("role").textValue()))
                        }.orEmpty())
                }
                path.value == "workspace/pages" -> {
                    val value = root.required("input")
                    val title = value.required("title").textValue()
                    val description = value.get("description")?.takeUnless(JsonNode::isNull)?.textValue()
                    val parent = value.get("parentPageId")?.takeUnless(JsonNode::isNull)?.textValue()?.let(Uuid::parse)
                    val index = value.get("index")?.takeUnless(JsonNode::isNull)?.intValue()
                    val baseRevision = revision(value.required("baseRevision"), "input.baseRevision")
                    val commandId = value.required("commandId").textValue()
                    when (value.required("kind").textValue()) {
                        "FOLDER_PAGE" -> AgentCreateInput.FolderPage(title, description, parent, index, baseRevision, commandId)
                        "DOCUMENT_PAGE" -> AgentCreateInput.DocumentPage(title, description, parent, index, baseRevision, commandId, Uuid.parse(value.required("uploadRef").textValue()))
                        "DASHBOARD_PAGE" -> AgentCreateInput.DashboardPage(title, description, parent, index, baseRevision, commandId, Uuid.parse(value.required("dashboardId").textValue()))
                        else -> invalid("input.kind", "The Page kind is not declared.")
                    }
                }
                AgentPagesPaths.versions.matches(path.value) -> {
                    val value = root.required("input")
                    AgentCreateInput.PageVersion(value.required("commandId").textValue(), Uuid.parse(value.required("uploadRef").textValue()))
                }
                path.value == "workspace/page-content-uploads" -> {
                    val value = root.required("input")
                    AgentCreateInput.PageContentUpload(enumValue<AgentPageContentFormat>(value.required("format")), value.required("byteSize").longValue(), value.required("sha256").textValue())
                }
                path.value == "workspace/connectors" -> {
                    val value = root.required("input")
                    val type = cc.monomer.connector.api.ConnectorProductType.valueOf(value.required("connectorType").textValue())
                    AgentCreateInput.Connector(type, value.required("displayName").textValue(),
                        cc.monomer.connector.api.ConnectorSettingsCodec.decode(type, jsonElement(value.required("properties")).jsonObject),
                        cc.monomer.connector.api.ConnectorCredentialCodec.decode(jsonElement(value.required("credential")).jsonObject))
                }
                path.value == SEMANTIC_MODEL_COLLECTION_PATH -> {
                    val semanticModelInput = root.required("input").objectValue()
                    AgentCreateInput.SemanticModel(
                        definition = semanticModelDefinition(semanticModelInput.required("definition").objectValue()),
                        aggregateMetrics = semanticModelInput.required("aggregateMetrics").arrayValue().map(::aggregateMetric),
                    )
                }
                path.value == METRIC_COLLECTION_PATH ->
                    AgentCreateInput.Metric(
                        metricDefinition(root.required("input").objectValue().required("definition").objectValue()),
                    )
                path.value == AgentPathPatterns.DASHBOARD_COLLECTION -> {
                    val dashboardInput = root.required("input").objectValue()
                    AgentCreateInput.Dashboard(
                        definition = dashboardDefinition(dashboardInput.required("definition").objectValue()),
                    )
                }
                else -> invalid("path", "The create collection path is not declared.")
            }
        return AgentWorkspaceDecodedRequest(AgentCreateRequest(path, input))
    }

    override fun edit(body: String): AgentWorkspaceDecodedRequest<AgentEditRequest> {
        val root = decodedRoot("AgentEditRequest", body)
        val patch = root.required("patch").arrayValue().mapIndexed { index, node ->
            val operation = node.objectValue()
            try {
                AgentJsonPatchOperation(
                    op = enumValue<AgentJsonPatchOp>(operation.required("op")),
                    path = operation.required("path").textValue(),
                    value = operation.get("value")?.let(::jsonElement),
                )
            } catch (exception: AgentWorkspaceRequestDecodingException) {
                throw AgentWorkspaceRequestDecodingException(exception.violations.map { violation ->
                    violation.copy(
                        field = "patch[$index].${violation.field}",
                        operationIndex = index,
                        path = safePatchPath(operation.path("path")),
                    )
                })
            } catch (exception: IllegalArgumentException) {
                throw AgentWorkspaceRequestDecodingException(listOf(AgentProblemViolation(
                    "patch[$index]", "INVALID_FIELD", "The JSON Patch operation is invalid.",
                    index, safePatchPath(operation.path("path")),
                )))
            }
        }
        return AgentWorkspaceDecodedRequest(
            AgentEditRequest(
                path = path(root.required("path")),
                revision = root.get("revision")?.takeUnless(JsonNode::isNull)?.let { revision(it, "revision") }
                    ?.let(::AgentRevision),
                patch = patch,
                commandId = root.get("commandId")?.textValue(),
                aggregateMetrics = root.get("aggregateMetrics")?.arrayValue()?.map(::aggregateMetric),
            ),
        )
    }

    override fun invoke(body: String): AgentWorkspaceDecodedRequest<AgentInvokeRequest> {
        val root = decodedRoot("AgentInvokeRequest", body)
        val path = path(root.required("path"))
        val action = root.required("action").textValue()
        val inputNode = root.get("input")
        val input =
            when {
                AgentManagementPaths.invocation(path.value, action, inputNode?.get("kind")?.textValue()) -> when(inputNode?.get("kind")?.textValue()) {
                    "WORKSPACE_MEMBER_ADD" -> AgentInvokeInput.WorkspaceMemberAdd(uuid(inputNode.required("userId")), enumValue(inputNode.required("role")))
                    "WORKSPACE_MEMBER_ROLE" -> AgentInvokeInput.WorkspaceMemberRole(enumValue(inputNode.required("role")))
                    else -> AgentInvokeInput.None
                }
                AgentPagesPaths.page.matches(path.value) && action == "delete" -> {
                    val value = inputNode.requiredInput()
                    AgentInvokeInput.PageDelete(revision(value.required("baseRevision"), "input.baseRevision"), value.required("commandId").textValue())
                }
                AgentPagesPaths.upload.matches(path.value) && action == "complete" -> AgentInvokeInput.PageContentUploadComplete(
                    inputNode.requiredInput().required("parts").arrayValue().map { AgentPageUploadCompletedPart(it.required("partNumber").intValue(), it.required("eTag").textValue()) },
                )
                AgentPagesPaths.upload.matches(path.value) && action == "abort" && inputNode == null -> AgentInvokeInput.None
                (AgentPathPatterns.dashboard.matches(path.value) && action == "delete" ||
                    cc.monomer.agent.api.AgentDashboardHistoryPaths.version.matches(path.value) && action == "restore") && inputNode == null -> AgentInvokeInput.None
                (cc.monomer.agent.api.AgentSemanticHistoryPaths.definition.matches(path.value) && action == "delete" ||
                    cc.monomer.agent.api.AgentSemanticHistoryPaths.version.matches(path.value) && action == "restore") && inputNode == null -> AgentInvokeInput.None
                AgentPathPatterns.connector.matches(path.value) && action == "delete" && inputNode == null -> AgentInvokeInput.None
                SEMANTIC_MODEL_CHECK_PATH.matches(path.value) && action == "check" ->
                    AgentInvokeInput.SemanticModelCheck(
                        semanticModelDefinition(inputNode.requiredInput().objectValue().required("definition").objectValue()),
                    )
                METRIC_CHECK_PATH.matches(path.value) && action == "check" -> {
                    val value = inputNode.requiredInput().objectValue()
                    AgentInvokeInput.MetricCheck(
                        metricDefinition(value.required("definition").objectValue()),
                        value.get("connectorId")?.takeUnless(JsonNode::isNull)?.let(::uuid),
                    )
                }
                AgentPathPatterns.connector.matches(path.value) && action == "rotate-credentials" ->
                    AgentInvokeInput.ConnectorCredentialRotate(cc.monomer.connector.api.ConnectorCredentialCodec.decode(
                        jsonElement(inputNode.requiredInput().required("credential")).jsonObject))
                AgentPathPatterns.dashboardDraft.matches(path.value) && action == "start" ->
                    AgentInvokeInput.DashboardDraftStart(
                        jsonObjectMap(inputNode.requiredInput().objectValue().required("parameterBindings").objectValue()),
                    )
                AgentPathPatterns.dashboardDraft.matches(path.value) && action == "apply" ->
                    AgentInvokeInput.DashboardDraftApply(
                        revision(inputNode.requiredInput().objectValue().required("expectedRevision"), "input.expectedRevision"),
                    )
                AgentPathPatterns.dashboardDraft.matches(path.value) && action == "discard" ->
                    AgentInvokeInput.DashboardDraftDiscard(
                        revision(inputNode.requiredInput().objectValue().required("expectedRevision"), "input.expectedRevision"),
                    )
                AgentPathPatterns.dashboardDraft.matches(path.value) && action == "refresh" ->
                    AgentInvokeInput.DashboardQueryRefresh(
                        inputNode.requiredInput().objectValue().required("queryNames").arrayValue().map(JsonNode::textValue).toSet(),
                    )
                AgentPathPatterns.dashboardSessions.matches(path.value) && action == "open" ->
                    AgentInvokeInput.DashboardSessionOpen(
                        jsonObjectMap(inputNode.requiredInput().objectValue().required("parameterBindings").objectValue()),
                    )
                (AgentPathPatterns.dashboardSession.matches(path.value) || AgentPagesPaths.session.matches(path.value)) && action == "resolve" ->
                    AgentInvokeInput.DashboardSessionResolve(
                        jsonObjectMap(inputNode.requiredInput().objectValue().required("variables").objectValue()),
                    )
                AgentPathPatterns.dashboardSession.matches(path.value) && action == "refresh" ->
                    AgentInvokeInput.DashboardSessionRefresh(
                        jsonObjectMap(inputNode.requiredInput().objectValue().required("variables").objectValue()),
                        inputNode.requiredInput().objectValue().required("queryNames").arrayValue().map(JsonNode::textValue).toSet(),
                    )
                AgentPathPatterns.dashboardDraftQueryRun.matches(path.value) && action == "cancel" && inputNode == null -> AgentInvokeInput.None
                AgentPathPatterns.exploreRun.matches(path.value) && action == "cancel" && inputNode == null -> AgentInvokeInput.None
                AgentPathPatterns.catalogInvokeDeclared(path.value, action) &&
                    inputNode == null -> AgentInvokeInput.None
                path.value == "workspace/explore/runs" && action == "run" ->
                    AgentInvokeInput.ExploreRun(exploreRun(inputNode.requiredInput().objectValue()))
                else -> invalid("path", "The invoke path and action are not declared.")
            }
        return AgentWorkspaceDecodedRequest(AgentInvokeRequest(path, action, input))
    }

    private fun decodedRoot(definition: String, body: String): ObjectNode {
        if (body.toByteArray(StandardCharsets.UTF_8).size !in 1..MAXIMUM_REQUEST_BYTES) {
            invalid("request", "The request body size is invalid.")
        }
        val root =
            try {
                strictMapper.readTree(body)
            } catch (_: Exception) {
                invalid("request", "The request body must be strict JSON.")
            } ?: invalid("request", "The request body is required.")
        validateIJson(root, "request")
        val violations = schema(definition).validate(root)
        if (violations.isNotEmpty()) {
            throw AgentWorkspaceRequestDecodingException(
                diagnosticErrors(violations.toList()).map { schemaViolation(it, root) }.map { violation ->
                    if (definition == "AgentListRequest" && violation.field == "limit" && violation.code == "REQUIRED_FIELD_MISSING") {
                        violation.copy(message = "Include limit as an integer from 1 to 100, for example 20. Resource listings do not have a default page size.")
                    } else violation
                }.distinct().take(100),
            )
        }
        val objectRoot = root as? ObjectNode ?: invalid("request", "The request body must be an object.")
        validateTypedConstraints(definition, objectRoot)
        return objectRoot
    }

    private fun validateSemanticModelInput(model: JsonNode) {
        val sources = model.path("sourceRelations").toList()
        if (sources.map { it.path("connectorId").asText() }.distinct().size != sources.size) {
            invalid("input.definition.sourceRelations", "Each source relation must use a distinct connectorId.")
        }
        sources.forEachIndexed { index, source ->
            for (name in listOf("catalog", "schema", "relation")) {
                source.get(name)?.let { value ->
                    val text = value.asText()
                    if (text.isBlank() || text.trim() != text || text.any(Char::isISOControl) ||
                        text.toByteArray(StandardCharsets.UTF_8).size > AgentResourcePath.MAXIMUM_NAME_BYTES
                    ) {
                        invalid("input.definition.sourceRelations[$index].$name",
                            "Use a nonblank address without surrounding whitespace or control characters, at most ${AgentResourcePath.MAXIMUM_NAME_BYTES} UTF-8 bytes.")
                    }
                }
            }
        }
        validateOptionalText(model, "input.definition")
        for (name in listOf("entities", "dimensions", "measures")) {
            model.path(name).forEachIndexed { index, element -> validateOptionalText(element, "input.definition.$name[$index]") }
        }
    }

    private fun validateMetricInput(metric: JsonNode) {
        validateOptionalText(metric, "input.definition")
        if (metric.path("type").asText() == "derived" && metric.path("expression").asText().isBlank()) {
            invalid("input.definition.expression", "The expression must not be blank.")
        }
        metric.get("window")?.let { validateMetricWindow(it, "input.definition.window") }
        for (name in listOf("numerator", "denominator", "inputMetric", "baseMetric", "conversionMetric")) {
            metric.path(name).get("offsetWindow")?.let { validateMetricWindow(it, "input.definition.$name.offsetWindow") }
        }
        metric.path("inputMetrics").forEachIndexed { index, input ->
            input.get("offsetWindow")?.let { validateMetricWindow(it, "input.definition.inputMetrics[$index].offsetWindow") }
        }
    }

    private fun validateTypedConstraints(definition: String, root: ObjectNode) {
        when (definition) {
            "AgentListRequest" -> if (root.path("cursor").asText().length > AgentResourcePath.MAXIMUM_CURSOR_LENGTH) {
                invalid("cursor", "The cursor must contain at most ${AgentResourcePath.MAXIMUM_CURSOR_LENGTH} UTF-16 code units.")
            }
            "AgentSearchRequest" -> root.path("queries").forEachIndexed { index, query ->
                if (query.asText().isBlank()) invalid("queries[$index]", "The search query must not be blank.")
            }
            "AgentEditRequest" -> {
                root.get("commandId")?.asText()?.let { commandId ->
                    if (commandId.isBlank() || commandId.length > 200) invalid("commandId", "Use a nonblank commandId of at most 200 UTF-16 code units.")
                }
                root.path("aggregateMetrics").forEachIndexed { index, metric -> validateOptionalText(metric, "aggregateMetrics[$index]") }
            }
            "AgentCreateRequest" -> when (root.path("input").path("kind").asText()) {
                "CONNECTOR_CREATE" -> validateCredential(root.path("input").path("credential"))
                "SEMANTIC_MODEL_DEFINITION" -> {
                    validateSemanticModelInput(root.path("input").path("definition"))
                    root.path("input").path("aggregateMetrics").forEachIndexed { index, metric -> validateOptionalText(metric, "input.aggregateMetrics[$index]") }
                }
                "METRIC_DEFINITION" -> validateMetricInput(root.path("input").path("definition"))
            }
            "AgentInvokeRequest" -> when (root.path("input").path("kind").asText()) {
                "SQL_EXPLORE_RUN" -> {
                    val sql = root.path("input").path("sql").asText()
                    if (sql.isBlank() || sql.length > 1_048_576) {
                        invalid("input.sql", "SQL must not be blank and must contain at most 1048576 UTF-16 code units.")
                    }
                }
                "CONNECTOR_CREDENTIAL_ROTATE" -> validateCredential(root.path("input").path("credential"))
                "SEMANTIC_MODEL_CHECK" -> validateSemanticModelInput(root.path("input").path("definition"))
                "METRIC_CHECK" -> validateMetricInput(root.path("input").path("definition"))
            }
        }
    }

    private fun validateCredential(node: JsonNode) {
        for (name in listOf("username", "password", "serviceAccountJson", "privateKey", "privateKeyPassphrase",
            "awsAccessKeyId", "awsSecretAccessKey", "awsRegion", "clusterId", "dbUser", "accessToken", "oauthClientId", "oauthClientSecret")) {
            if (node.get(name)?.asText()?.isBlank() == true) invalid("input.credential.$name", "The credential field must not be blank.")
        }
    }

    private fun validateOptionalText(node: JsonNode, field: String) {
        for (name in listOf("label", "description")) {
            if (node.get(name)?.asText()?.isBlank() == true) invalid("$field.$name", "The text must not be blank.")
        }
    }

    private fun validateMetricWindow(node: JsonNode, field: String) {
        if (node.asText().takeWhile(Char::isDigit).toIntOrNull()?.let { it > 0 } != true) {
            invalid(field, "The window amount must be an integer from 1 to 2147483647.")
        }
    }

    private fun diagnosticErrors(errors: List<ValidationMessage>): List<ValidationMessage> {
        val diagnostics = mutableListOf<ValidationMessage>()
        var remaining = errors
        while (remaining.isNotEmpty() && diagnostics.size < 100) {
            val union = remaining.firstOrNull { it.type == "oneOf" || it.type == "anyOf" }
            if (union == null) {
                diagnostics.addAll(remaining.take(100 - diagnostics.size))
                break
            }
            val unionPath = elements(union.evaluationPath)
            val instancePath = elements(union.instanceLocation)
            val descendants = remaining.filter {
                val evaluation = elements(it.evaluationPath)
                evaluation.size > unionPath.size && evaluation.take(unionPath.size) == unionPath &&
                    elements(it.instanceLocation).take(instancePath.size) == instancePath
            }
            val branches = descendants.groupBy { elements(it.evaluationPath)[unionPath.size] }.values
            val matching = branches.filter { branch ->
                branch.none { error ->
                    val location = elements(error.instanceLocation)
                    (location == instancePath && error.type == "type") ||
                        (location.size == instancePath.size + 1 &&
                            (error.type == "const" || (error.type == "pattern" && location.last() == "path")))
                }
            }
            val structurallyMatching = matching.filter { branch ->
                branch.none { it.type == "required" && elements(it.instanceLocation) == instancePath }
            }
            val candidates = structurallyMatching.ifEmpty { matching }.ifEmpty { branches.toList() }.map(::diagnosticErrors)
            val selected = if (candidates.size == 1) {
                candidates.single()
            } else {
                candidates.firstOrNull().orEmpty().filter { candidate ->
                    candidates.all { branch ->
                        branch.any {
                            it.type == candidate.type && it.instanceLocation == candidate.instanceLocation &&
                                it.property == candidate.property &&
                                (candidate.type == "required" || candidate.type == "const" || it.schemaNode == candidate.schemaNode)
                        }
                    }
                }
            }
            diagnostics.addAll(selected.ifEmpty { listOf(union) }.take(100 - diagnostics.size))
            remaining = remaining.filter { it !== union && it !in descendants }
        }
        return diagnostics.filterNot { error ->
            error.type == "unevaluatedProperties" && diagnostics.any { specific ->
                specific !== error && specific.type != "unevaluatedProperties" &&
                    elements(specific.instanceLocation).take(elements(error.instanceLocation).size) == elements(error.instanceLocation)
            }
        }
    }

    private fun schemaViolation(error: ValidationMessage, root: JsonNode): AgentProblemViolation {
        val location = elements(error.instanceLocation)
        val evaluation = elements(error.evaluationPath)
        val declaredProperties = evaluation.zipWithNext().filter { it.first == "properties" }.map { it.second }
        val safeLocation = mutableListOf<Any>()
        var propertyIndex = 0
        for (element in location) {
            if (element is Int) {
                safeLocation.add(element)
            } else if (declaredProperties.getOrNull(propertyIndex) == element) {
                safeLocation.add(element)
                propertyIndex++
            } else {
                break
            }
        }
        if (error.type == "required" && safeLocation.size == location.size &&
            error.schemaNode.any { it.isTextual && it.textValue() == error.property }
        ) {
            safeLocation.add(error.property)
        }
        val field = buildString {
            safeLocation.forEach { element ->
                if (element is Int) append("[$element]") else {
                    if (isNotEmpty()) append('.')
                    append(element)
                }
            }
        }.takeIf { it.isNotBlank() && it.length <= 512 } ?: "request"
        val (code, message) = when (error.type) {
            "pattern" -> "SCHEMA_MISMATCH" to if (error.schemaNode.asText() == "^(?!.*\\.[0-9]{7})") {
                "Timestamp filter values must have at most 6 fractional second digits."
            } else {
                "The field does not match its declared schema."
            }
            "required" -> "REQUIRED_FIELD_MISSING" to "The required field is missing."
            "const" -> "INVALID_ENUM_VALUE" to "The value is not one of the declared options."
            "enum" -> "INVALID_ENUM_VALUE" to (
                "Allowed values: ${error.schemaNode}.".takeIf { it.length <= 512 }
                    ?: "The value is not one of the declared options."
            )
            "minimum" -> "SCHEMA_MISMATCH" to "The value must be at least ${error.schemaNode}."
            "maximum" -> "SCHEMA_MISMATCH" to "The value must be at most ${error.schemaNode}."
            "exclusiveMinimum" -> "SCHEMA_MISMATCH" to "The value must be greater than ${error.schemaNode}."
            "exclusiveMaximum" -> "SCHEMA_MISMATCH" to "The value must be less than ${error.schemaNode}."
            "minItems" -> "SCHEMA_MISMATCH" to "The array must contain at least ${error.schemaNode} items."
            "maxItems" -> "SCHEMA_MISMATCH" to "The array must contain at most ${error.schemaNode} items."
            "minLength" -> "SCHEMA_MISMATCH" to "The string must contain at least ${error.schemaNode} characters."
            "maxLength" -> "SCHEMA_MISMATCH" to "The string must contain at most ${error.schemaNode} characters."
            "type" -> "SCHEMA_MISMATCH" to "The field must have JSON type ${error.schemaNode}."
            "additionalProperties", "unevaluatedProperties" ->
                "SCHEMA_MISMATCH" to "The object contains undeclared fields."
            "minContains" -> "SCHEMA_MISMATCH" to "The array must contain at least ${error.arguments[0]} items matching the constrained item schema."
            "maxContains" -> "SCHEMA_MISMATCH" to "The array must contain at most ${error.arguments[0]} items matching the constrained item schema."
            "uniqueItems" -> "SCHEMA_MISMATCH" to "The array items must be distinct."
            else -> "SCHEMA_MISMATCH" to "The field does not match its declared schema."
        }
        val operationIndex = if (location.firstOrNull() == "patch") location.getOrNull(1) as? Int else null
        return AgentProblemViolation(
            field, code, message, operationIndex,
            operationIndex?.let { safePatchPath(root.path("patch").path(it).path("path")) },
        )
    }

    private fun safePatchPath(node: JsonNode): String? =
        node.takeIf(JsonNode::isTextual)?.textValue()?.let(AgentJsonPatch::safeDiagnosticPath)

    private fun elements(path: JsonNodePath): List<Any> = (0 until path.nameCount).map(path::getElement)

    private fun path(node: JsonNode): AgentResourcePath =
        try {
            AgentResourcePath.parse(node.textValue())
        } catch (_: IllegalArgumentException) {
            invalid("path", "The resource path is not canonical.")
        }


    private fun aggregateMetric(node: JsonNode): AgentSemanticAggregateMetric = AgentSemanticAggregateMetric(
        measure = node.required("measure").textValue(),
        name = node.required("name").textValue(),
        label = node.get("label")?.textValue(),
        description = node.get("description")?.textValue(),
        timeGranularity = node.get("timeGranularity")?.let { enumValue<AgentTimeGranularity>(it) },
        filters = node.get("filters")?.arrayValue()?.map(::metricFilter).orEmpty(),
    )

    private fun semanticModelDefinition(node: ObjectNode): AgentSemanticModelDefinition =
        AgentSemanticModelDefinition(
            schemaVersion = node.required("schemaVersion").intValue().toUInt(),
            name = node.required("name").textValue(),
            label = optionalText(node, "label"),
            description = optionalText(node, "description"),
            sourceRelations = node.required("sourceRelations").arrayValue().map(::sourceRelation),
            aggregationTimeDimension = optionalText(node, "aggregationTimeDimension"),
            entities = node.required("entities").arrayValue().map(::semanticEntity),
            dimensions = node.required("dimensions").arrayValue().map(::semanticDimension),
            measures = node.required("measures").arrayValue().map(::semanticMeasure),
        )

    private fun sourceRelation(node: JsonNode): AgentSemanticSourceRelation {
        val value = node.objectValue()
        return AgentSemanticSourceRelation(
            connectorId = uuid(value.required("connectorId")),
            catalog = optionalText(value, "catalog"),
            schema = value.required("schema").textValue(),
            relation = value.required("relation").textValue(),
        )
    }

    private fun semanticEntity(node: JsonNode): AgentSemanticEntity {
        val value = node.objectValue()
        return AgentSemanticEntity(
            name = value.required("name").textValue(),
            expression = value.required("expression").textValue(),
            kind = enumValue(value.required("type")),
            label = optionalText(value, "label"),
            description = optionalText(value, "description"),
        )
    }

    private fun semanticDimension(node: JsonNode): AgentSemanticDimension {
        val value = node.objectValue()
        return AgentSemanticDimension(
            name = value.required("name").textValue(),
            expression = value.required("expression").textValue(),
            kind = enumValue(value.required("type")),
            granularity = optionalText(value, "granularity")?.let(::timeGranularity),
            label = optionalText(value, "label"),
            description = optionalText(value, "description"),
            isPartition = value.required("isPartition").booleanValue(),
            timeKind = optionalText(value, "timeKind")?.let { enumValueText<AgentSemanticTimeKind>(it) },
        )
    }

    private fun semanticMeasure(node: JsonNode): AgentSemanticMeasure {
        val value = node.objectValue()
        return AgentSemanticMeasure(
            name = value.required("name").textValue(),
            expression = value.required("expression").textValue(),
            aggregation = enumValue(value.required("aggregation")),
            aggregationTimeDimension = optionalText(value, "aggregationTimeDimension"),
            nonAdditiveDimension = value.get("nonAdditiveDimension")?.takeUnless(JsonNode::isNull)?.let(::nonAdditiveDimension),
            label = optionalText(value, "label"),
            description = optionalText(value, "description"),
        )
    }

    private fun nonAdditiveDimension(node: JsonNode): AgentSemanticNonAdditiveDimension {
        val value = node.objectValue()
        return AgentSemanticNonAdditiveDimension(
            name = value.required("name").textValue(),
            windowChoice = enumValue(value.required("windowChoice")),
            windowGroupings = value.get("windowGroupings")?.takeUnless(JsonNode::isNull)?.arrayValue()?.map(JsonNode::textValue),
        )
    }

    private fun metricDisplay(node: JsonNode): cc.monomer.agent.api.AgentMetricDisplay = when (node.required("style").textValue()) {
        "NUMBER" -> cc.monomer.agent.api.AgentMetricDisplay.Number(node.required("decimals").intValue(),
            optionalText(node.objectValue(), "unit")?.let { name -> cc.monomer.agent.api.AgentMetricDisplayUnit.entries.single { it.name.lowercase() == name } })
        "CURRENCY" -> cc.monomer.agent.api.AgentMetricDisplay.Currency(node.required("decimals").intValue(),
            cc.monomer.agent.api.AgentMetricDisplayCurrency.valueOf(node.required("currency").textValue()))
        "PERCENT" -> cc.monomer.agent.api.AgentMetricDisplay.Percent(node.required("decimals").intValue())
        else -> invalid("input.definition.display.style", "The display style is not declared.")
    }

    private fun metricDefinition(node: ObjectNode): AgentMetricDefinition {
        val common = metricCommon(node)
        return when (node.required("type").textValue()) {
            "simple" -> AgentMetricDefinition.Simple(
                schemaVersion = common.schemaVersion,
                name = common.name,
                label = common.label,
                description = common.description,
                timeGranularity = common.timeGranularity,
                filters = common.filters,
                semanticModelName = node.required("semanticModelName").textValue(),
                measure = node.required("measure").textValue(),
                display = common.display,
            )
            "ratio" -> AgentMetricDefinition.Ratio(
                schemaVersion = common.schemaVersion,
                name = common.name,
                label = common.label,
                description = common.description,
                timeGranularity = common.timeGranularity,
                filters = common.filters,
                numerator = metricInput(node.required("numerator").objectValue()),
                denominator = metricInput(node.required("denominator").objectValue()),
                display = common.display,
            )
            "derived" -> AgentMetricDefinition.Derived(
                schemaVersion = common.schemaVersion,
                name = common.name,
                label = common.label,
                description = common.description,
                timeGranularity = common.timeGranularity,
                filters = common.filters,
                expression = node.required("expression").textValue(),
                inputMetrics = node.required("inputMetrics").arrayValue().map { metricInput(it.objectValue()) },
                display = common.display,
            )
            "cumulative" -> AgentMetricDefinition.Cumulative(
                schemaVersion = common.schemaVersion,
                name = common.name,
                label = common.label,
                description = common.description,
                timeGranularity = common.timeGranularity,
                filters = common.filters,
                inputMetric = metricInput(node.required("inputMetric").objectValue()),
                window = optionalText(node, "window")?.let(::metricWindow),
                grainToDate = optionalText(node, "grainToDate")?.let(::timeGranularity),
                periodAggregation = optionalText(node, "periodAggregation")?.let(::enumValueText),
                display = common.display,
            )
            "conversion" -> AgentMetricDefinition.Conversion(
                schemaVersion = common.schemaVersion,
                name = common.name,
                label = common.label,
                description = common.description,
                timeGranularity = common.timeGranularity,
                filters = common.filters,
                entity = node.required("entity").textValue(),
                calculation = enumValue(node.required("calculation")),
                baseMetric = metricInput(node.required("baseMetric").objectValue()),
                conversionMetric = metricInput(node.required("conversionMetric").objectValue()),
                window = optionalText(node, "window")?.let(::metricWindow),
                constantProperties = node.get("constantProperties")?.takeUnless(JsonNode::isNull)?.arrayValue()?.map {
                    val property = it.objectValue()
                    AgentMetricConstantProperty(
                        baseProperty = property.required("baseProperty").textValue(),
                        conversionProperty = property.required("conversionProperty").textValue(),
                    )
                },
                display = common.display,
            )
            else -> invalid("input.definition.type", "The metric type is not declared.")
        }
    }

    private fun dashboardDefinition(node: ObjectNode): JsonObject =
        try {
            Json.parseToJsonElement(strictMapper.writeValueAsString(node)).jsonObject
        } catch (_: Exception) {
            invalid("input.definition", "The dashboard definition is invalid.")
        }

    private fun jsonElement(node: JsonNode): JsonElement =
        try {
            Json.parseToJsonElement(strictMapper.writeValueAsString(node))
        } catch (_: Exception) {
            invalid("value", "The JSON value is invalid.")
        }

    private fun jsonObjectMap(node: ObjectNode): Map<String, JsonElement> =
        node.properties().asSequence().associate { (name, value) ->
            name to try {
                Json.parseToJsonElement(strictMapper.writeValueAsString(value))
            } catch (_: Exception) {
                invalid("input", "The JSON value is invalid.")
            }
        }

    private fun revision(node: JsonNode, field: String): Long =
        node.textValue()?.toLongOrNull()?.takeIf { it >= 0 }
            ?: invalid(field, "The revision is invalid.")

    private data class MetricCommon(
        val schemaVersion: UInt,
        val name: String,
        val label: String?,
        val description: String?,
        val timeGranularity: AgentTimeGranularity?,
        val filters: List<AgentMetricFilter>,
        val display: cc.monomer.agent.api.AgentMetricDisplay?,
    )

    private fun metricCommon(node: ObjectNode): MetricCommon = MetricCommon(
        display = node.get("display")?.takeUnless(JsonNode::isNull)?.let(::metricDisplay),
        schemaVersion = node.required("schemaVersion").intValue().toUInt(),
        name = node.required("name").textValue(),
        label = optionalText(node, "label"),
        description = optionalText(node, "description"),
        timeGranularity = optionalText(node, "timeGranularity")?.let(::timeGranularity),
        filters = node.get("filters")?.takeUnless(JsonNode::isNull)?.arrayValue()?.map(::metricFilter).orEmpty(),
    )

    private fun metricInput(node: ObjectNode): AgentMetricInput = AgentMetricInput(
        metricName = node.required("metricName").textValue(),
        filter = node.get("filter")?.takeUnless(JsonNode::isNull)?.let(::metricFilter),
        alias = optionalText(node, "alias"),
        offsetWindow = optionalText(node, "offsetWindow")?.let(::metricWindow),
        offsetToGrain = optionalText(node, "offsetToGrain")?.let(::timeGranularity),
    )

    private fun metricWindow(value: String): AgentMetricWindow {
        val match = Regex("^(\\d+)\\s+(day|days|week|weeks|month|months|quarter|quarters|year|years)$").matchEntire(value)
            ?: invalid("input.definition.window", "The metric window is not declared.")
        val amount = match.groupValues[1].toIntOrNull()
            ?: invalid("input.definition.window", "The metric window amount is invalid.")
        val unit = when (match.groupValues[2].removeSuffix("s")) {
            "day" -> AgentMetricWindowUnit.DAY
            "week" -> AgentMetricWindowUnit.WEEK
            "month" -> AgentMetricWindowUnit.MONTH
            "quarter" -> AgentMetricWindowUnit.QUARTER
            "year" -> AgentMetricWindowUnit.YEAR
            else -> invalid("input.definition.window", "The metric window unit is not declared.")
        }
        return AgentMetricWindow(amount, unit)
    }

    private fun metricFilter(node: JsonNode): AgentMetricFilter {
        val value = node.objectValue()
        return when {
            value.has("dimension") -> AgentMetricFilter.Predicate(
                dimension = metricElement(value.required("dimension").objectValue()),
                operator = enumValue(value.required("operator")),
                values = value.required("values").arrayValue().map(JsonNode::textValue),
            )
            value.has("and") -> AgentMetricFilter.And(value.required("and").arrayValue().map(::metricFilter))
            value.has("or") -> AgentMetricFilter.Or(value.required("or").arrayValue().map(::metricFilter))
            value.has("not") -> AgentMetricFilter.Not(metricFilter(value.required("not")))
            else -> invalid("input.definition.filters", "The metric filter is not declared.")
        }
    }

    private fun metricElement(node: ObjectNode): AgentSemanticElement = AgentSemanticElement(
        semanticModelName = node.required("semanticModelName").textValue(),
        name = node.required("name").textValue(),
        granularity = optionalText(node, "granularity")?.let(::timeGranularity),
    )

    private fun exploreRun(node: ObjectNode): AgentExploreRunRequest =
        when (node.required("kind").textValue()) {
            "SQL_EXPLORE_RUN" -> AgentExploreRunRequest.Sql(
                connectorId = uuid(node.required("connectorId")),
                statement = node.required("sql").textValue(),
            )
            "SEMANTIC_EXPLORE_RUN" -> AgentExploreRunRequest.Semantic(
                connectorId = uuid(node.required("connectorId")),
                query = semanticQuery(node.required("query").objectValue()),
            )
            else -> invalid("input.kind", "The explore input kind is not declared.")
        }

    private fun semanticQuery(node: ObjectNode): AgentSemanticQuery {
        val metrics = node.required("metrics").arrayValue().map(JsonNode::textValue)
        val groupBys = node.required("groupBys").arrayValue().map(::semanticGroupBy)
        val filters = node.required("filters").arrayValue().mapIndexed { index, filter -> semanticFilter(filter, "input.query.filters[$index]") }
        val orderBy = node.required("orderBy").arrayValue().map(::semanticOrder)
        if (filters.distinct().size != filters.size) invalid("input.query.filters", "Filters must be distinct after parsing their typed values.")
        orderBy.forEachIndexed { index, order ->
            when (val target = order.target) {
                is AgentSemanticOrderTarget.Metric -> if (target.metricName !in metrics) {
                    invalid("input.query.orderBy[$index].target.metricName", "The ordered metric must be included in metrics.")
                }
                is AgentSemanticOrderTarget.GroupBy -> if (target.groupBy !in groupBys) {
                    invalid("input.query.orderBy[$index].target.groupBy", "The ordered group-by must be included in groupBys.")
                }
            }
        }
        if (orderBy.map(AgentSemanticOrder::target).distinct().size != orderBy.size) {
            invalid("input.query.orderBy", "Each order target must be distinct, regardless of direction.")
        }
        return AgentSemanticQuery(
            schemaVersion = node.required("schemaVersion").intValue().toUInt(),
            metrics = metrics,
            groupBys = groupBys,
            metricTimeRange = node.required("metricTimeRange").takeUnless(JsonNode::isNull)?.let(::semanticMetricTimeRange),
            filters = filters,
            orderBy = orderBy,
            rowLimit = node.required("rowLimit").intValue(),
        )
    }

    private fun semanticGroupBy(node: JsonNode): AgentSemanticGroupBy {
        val value = node.objectValue()
        return when (value.required("kind").textValue()) {
            "DIMENSION" ->
                AgentSemanticGroupBy.Dimension(
                    semanticModelName = value.required("semanticModelName").textValue(),
                    name = value.required("name").textValue(),
                    granularity = optionalText(value, "granularity")?.let(::timeGranularity),
                )
            "ENTITY" -> AgentSemanticGroupBy.Entity(
                semanticModelName = value.required("semanticModelName").textValue(),
                name = value.required("name").textValue(),
            )
            "METRIC_TIME" ->
                AgentSemanticGroupBy.MetricTime(
                    granularity = enumValue(value.required("granularity")),
                )
            else -> invalid("input.query.groupBys", "The group-by is not declared.")
        }
    }

    private fun semanticMetricTimeRange(node: JsonNode): AgentSemanticMetricTimeRange {
        val value = node.objectValue()
        val start = semanticTimeRangeEndpoint(value.required("start"), "input.query.metricTimeRange.start.value")
        val end = semanticTimeRangeEndpoint(value.required("end"), "input.query.metricTimeRange.end.value")
        val ordered = when {
            start is AgentSemanticTimeRangeEndpoint.Date && end is AgentSemanticTimeRangeEndpoint.Date -> start.value <= end.value
            start is AgentSemanticTimeRangeEndpoint.Timestamp && end is AgentSemanticTimeRangeEndpoint.Timestamp -> start.value <= end.value
            else -> invalid("input.query.metricTimeRange", "The start and end must have the same endpoint kind.")
        }
        if (!ordered) invalid("input.query.metricTimeRange.end.value", "The end must be at or after the start.")
        return AgentSemanticMetricTimeRange(start, end)
    }

    private fun semanticTimeRangeEndpoint(node: JsonNode, field: String): AgentSemanticTimeRangeEndpoint {
        val value = node.objectValue()
        val rawValue = value.required("value").textValue()
        return when (value.required("kind").textValue()) {
            "DATE" ->
                try {
                    AgentSemanticTimeRangeEndpoint.Date(LocalDate.parse(rawValue))
                } catch (_: java.time.format.DateTimeParseException) {
                    invalid(field, "Use a valid ISO 8601 date.")
                }
            "TIMESTAMP" ->
                try {
                    AgentSemanticTimeRangeEndpoint.Timestamp(Instant.parse(rawValue))
                } catch (_: java.time.format.DateTimeParseException) {
                    invalid(field, "Use a valid ISO 8601 timestamp with an offset.")
                }
            else -> invalid("input.query.metricTimeRange", "The time endpoint is not declared.")
        }
    }

    private fun semanticFilter(node: JsonNode, field: String): AgentSemanticFilter {
        val value = node.objectValue()
        val dimension = semanticElement(value.required("dimension"))
        val operator = value.required("operator")
        if (value.required("kind").textValue() == "TEXT") {
            value.required("values").arrayValue().forEachIndexed { index, item ->
                val text = item.textValue()
                if (text.codePointCount(0, text.length) !in 1..256) invalid("$field.values[$index]", "Text filter values must contain 1 to 256 characters.")
                if ('\u0000' in text) invalid("$field.values[$index]", "Text filter values must not contain NUL.")
            }
        }
        return when (value.required("kind").textValue()) {
            "TEXT" -> AgentSemanticFilter.Text(
                dimension = dimension,
                operator = enumValue(operator),
                values = value.required("values").arrayValue().map(JsonNode::textValue),
            )
            "NUMBER" -> AgentSemanticFilter.Number(
                dimension = dimension,
                operator = enumValue(operator),
                values = value.required("values").arrayValue().map { it.textValue().toBigDecimal() },
            )
            "BOOLEAN" -> AgentSemanticFilter.Boolean(
                dimension = dimension,
                operator = enumValue(operator),
                values = value.required("values").arrayValue().map(JsonNode::booleanValue),
            )
            "DATE" -> AgentSemanticFilter.Date(
                dimension = dimension,
                operator = enumValue(operator),
                values = value.required("values").arrayValue().mapIndexed { index, item -> dateValue(item, "$field.values[$index]") },
            )
            "TIMESTAMP" -> AgentSemanticFilter.Timestamp(
                dimension = dimension,
                operator = enumValue(operator),
                values = value.required("values").arrayValue().mapIndexed { index, item -> timestampValue(item, "$field.values[$index]") }.also { parsedValues ->
                    if (parsedValues.distinct().size != parsedValues.size) invalid("$field.values", "Filter values must be unique.")
                },
            )
            else -> invalid("input.query.filters", "The filter kind is not declared.")
        }
    }

    private fun semanticOrder(node: JsonNode): AgentSemanticOrder {
        val value = node.objectValue()
        return AgentSemanticOrder(
            target = semanticOrderTarget(value.required("target")),
            direction = enumValue(value.required("direction")),
        )
    }

    private fun semanticOrderTarget(node: JsonNode): AgentSemanticOrderTarget {
        val value = node.objectValue()
        return when (value.required("kind").textValue()) {
            "METRIC" -> AgentSemanticOrderTarget.Metric(value.required("metricName").textValue())
            "GROUP_BY" -> AgentSemanticOrderTarget.GroupBy(semanticGroupBy(value.required("groupBy")))
            else -> invalid("input.query.orderBy.target", "The order target is not declared.")
        }
    }

    private fun semanticElement(node: JsonNode): AgentSemanticElement {
        val value = node.objectValue()
        return AgentSemanticElement(
            semanticModelName = value.required("semanticModelName").textValue(),
            name = value.required("name").textValue(),
            granularity = null,
        )
    }

    private fun dateValue(node: JsonNode, field: String): LocalDate =
        try {
            LocalDate.parse(node.textValue())
        } catch (_: java.time.format.DateTimeParseException) {
            invalid(field, "Use a valid ISO 8601 date.")
        }

    private fun timestampValue(node: JsonNode, field: String): Instant =
        try {
            Instant.parse(node.textValue())
        } catch (_: java.time.format.DateTimeParseException) {
            invalid(field, "Use a valid ISO 8601 timestamp with an offset.")
        }

    private fun optionalText(node: ObjectNode, name: String): String? =
        node.get(name)?.takeUnless(JsonNode::isNull)?.textValue()

    private fun timeGranularity(value: String): AgentTimeGranularity =
        when (value.lowercase()) {
            "day" -> AgentTimeGranularity.DAY
            "week" -> AgentTimeGranularity.WEEK
            "month" -> AgentTimeGranularity.MONTH
            "quarter" -> AgentTimeGranularity.QUARTER
            "year" -> AgentTimeGranularity.YEAR
            "hour" -> AgentTimeGranularity.HOUR
            "minute" -> AgentTimeGranularity.MINUTE
            "second" -> AgentTimeGranularity.SECOND
            "millisecond" -> AgentTimeGranularity.MILLISECOND
            "microsecond" -> AgentTimeGranularity.MICROSECOND
            "nanosecond" -> AgentTimeGranularity.NANOSECOND
            else -> invalid("input", "The time granularity is not declared.")
        }

    private fun uuid(node: JsonNode): Uuid =
        try {
            Uuid.parse(node.textValue())
        } catch (_: IllegalArgumentException) {
            invalid("input", "The identifier is invalid.")
        }

    private inline fun <reified T : Enum<T>> enumValue(node: JsonNode): T =
        enumValues<T>().singleOrNull { value -> value.name.equals(node.textValue(), ignoreCase = true) }
            ?: invalid("input", "The enum value is not declared.")

    private inline fun <reified T : Enum<T>> enumValueText(value: String): T =
        enumValues<T>().singleOrNull { item -> item.name.equals(value, ignoreCase = true) }
            ?: invalid("input", "The enum value is not declared.")

    private fun JsonNode.required(name: String): JsonNode =
        (this as? ObjectNode)?.get(name) ?: invalid(name, "The required field is missing.")

    private fun JsonNode.objectValue(): ObjectNode = this as? ObjectNode ?: invalid("input", "The value must be an object.")

    private fun JsonNode?.requiredInput(): JsonNode = this ?: invalid("input", "The required field is missing.")

    private fun JsonNode.arrayValue(): ArrayNode = this as? ArrayNode ?: invalid("input", "The value must be an array.")

    private fun validateIJson(node: JsonNode, field: String) {
        when {
            node.isObject -> node.properties().asSequence().forEach { (name, value) ->
                validateString(name, field)
                validateIJson(value, field)
            }
            node.isArray -> node.forEach { value -> validateIJson(value, field) }
            node.isTextual -> validateString(node.textValue(), field)
            node.isNumber -> canonicalNumber(node)
        }
    }

    private fun validateString(value: String, field: String) {
        if (
            !AgentJsonCanonicalizer.hasOnlyUnicodeScalars(value) ||
            Normalizer.normalize(value, Normalizer.Form.NFC) != value
        ) {
            invalid(field, "Text must contain only NFC Unicode scalar values.")
        }
    }

    private fun canonicalNumber(node: JsonNode): String =
        try {
            AgentJsonCanonicalizer.canonicalNumberPreservingValue(node.toString())
        } catch (_: IllegalArgumentException) {
            invalid("request", "The JSON number is not RFC 8785 canonicalizable.")
        }

    private fun schema(definition: String): JsonSchema = schemas.getValue(definition)

    private fun invalid(field: String, detail: String): Nothing =
        throw AgentWorkspaceRequestDecodingException(field, detail)

    private companion object {
        val strictMapper =
            ObjectMapper(
                JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build(),
            )
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        val agentDocument = resource("contracts/agent-workspace.schema.json")
        val schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
                builder.schemaLoaders { loaders ->
                    loaders.schemas(
                        listOf(
                            agentDocument,
                            resource("contracts/problem-details.schema.json"),
                            resource("contracts/metric-display.schema.json"),
                            resource("contracts/connector/connector.schema.json"),
                            resource("contracts/semantic/model.schema.json"),
                            resource("contracts/semantic/metric.schema.json"),
                            resource("contracts/semantic/query.schema.json"),
                            resource("contracts/dashboard/dashboard.schema.json"),
                        ).associate { document -> document.path("\$id").asText() to document.toString() },
                    )
                }
            }
        val schemas =
            setOf("AgentListRequest", "AgentSearchRequest", "AgentReadRequest", "AgentCreateRequest", "AgentEditRequest", "AgentInvokeRequest")
                .associateWith(::definitionSchema)
        const val MAXIMUM_REQUEST_BYTES = 1_048_576
        const val SEMANTIC_MODEL_COLLECTION_PATH = "workspace/semantic-models"
        const val METRIC_COLLECTION_PATH = "workspace/metrics"
        val SEMANTIC_MODEL_CHECK_PATH = Regex("^workspace/semantic-models(?:/$SEMANTIC_NAME)?$")
        val METRIC_CHECK_PATH = Regex("^workspace/metrics(?:/$SEMANTIC_NAME)?$")

        fun resource(path: String): JsonNode =
            AgentWorkspaceContractDecoder::class.java.classLoader.getResourceAsStream(path)
                ?.use(strictMapper::readTree)
                ?: error("Missing packaged contract resource: $path")

        fun definitionSchema(definition: String): JsonSchema =
            schemaFactory.getSchema(
                strictMapper.createObjectNode().apply {
                    put("\$schema", agentDocument.path("\$schema").asText())
                    put("\$id", agentDocument.path("\$id").asText())
                    put("\$ref", "#/\$defs/$definition")
                    set<JsonNode>("\$defs", agentDocument.path("\$defs").deepCopy())
                },
            )
    }
}
