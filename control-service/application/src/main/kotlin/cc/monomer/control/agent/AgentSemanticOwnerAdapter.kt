@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentPathPatterns
import cc.monomer.agent.api.AgentSemanticHistoryPaths
import cc.monomer.agent.api.AgentSemanticDefinitionVersionBody
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActionResultReference
import cc.monomer.agent.api.AgentAvailableOperation
import cc.monomer.agent.api.AgentCreateInput
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentJsonPatch
import cc.monomer.agent.api.AgentJsonPatchException
import cc.monomer.agent.api.AgentJsonPatchOp
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentMetricDefinition
import cc.monomer.agent.api.AgentMetricResourceBody
import cc.monomer.agent.api.AgentProblemViolation
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentSemanticModelDefinition
import cc.monomer.agent.api.AgentSemanticModelResourceBody
import cc.monomer.agent.api.AgentSemanticOwnerPort
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceOperation
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.agent.application.AgentSemanticDefinitionJson
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.semantic.inbound.rest.MetricDefinitionDto
import cc.monomer.control.semantic.inbound.rest.SemanticCheckHttpView
import cc.monomer.control.semantic.inbound.rest.SemanticDefinitionProblem
import cc.monomer.control.semantic.inbound.rest.SemanticModelDefinitionDto
import cc.monomer.control.AdmissionRequest
import cc.monomer.control.MutationAdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.MutationIntent
import cc.monomer.semantic.api.DeleteSemanticModelCommand
import cc.monomer.semantic.api.DeleteMetricCommand
import cc.monomer.semantic.api.RestoreSemanticModelCommand
import cc.monomer.semantic.api.RestoreMetricCommand
import cc.monomer.semantic.api.SemanticDefinitionSnapshot
import cc.monomer.semantic.api.SemanticDefinitionVersion
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.MetricId
import cc.monomer.control.semantic.inbound.rest.decodeHistoryCursor
import cc.monomer.control.semantic.inbound.rest.encodeHistoryCursor
import cc.monomer.control.semantic.inbound.rest.decodeSemanticTransport
import kotlin.uuid.Uuid
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.MetricListBoundary
import cc.monomer.semantic.api.PutMetricCommand
import cc.monomer.semantic.api.PutSemanticModelCommand
import cc.monomer.semantic.api.SemanticAuthorityUseCase
import cc.monomer.semantic.api.SemanticCheckDraft
import cc.monomer.semantic.api.SemanticCheckRequest
import cc.monomer.semantic.api.SemanticCheckResult
import cc.monomer.semantic.api.SemanticDefinitionCheckUseCase
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelListBoundary
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticMutationResult
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class AgentSemanticOwnerAdapter(
    private val authority: SemanticAuthorityUseCase,
    private val currentRead: SemanticCurrentReadUseCase,
    private val checks: SemanticDefinitionCheckUseCase,
    private val editAvailability: AgentSemanticEditAvailability,
    private val admission: ControlAdmissionFacade,
    private val provenanceNames: AgentProvenanceNames,
) : AgentSemanticOwnerPort {
    private val decoder = AgentWorkspaceContractDecoder()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)

    override suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome {
        if (AgentSemanticHistoryPaths.history.matches(request.path.value)) return listHistory(caller, request)
        val collection = collection(request.path) ?: return AgentListingOutcome.NotFound
        val afterName = try {
            request.cursor?.let { cursor -> decodeCursor(cursor, request.path) }
        } catch (_: IllegalArgumentException) {
            return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
        }
        return agentOwnerGuard("AgentSemanticOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentListingOutcome::Rejected) {
            val (items, nextName) = when (collection) {
                SemanticCollection.MODELS -> currentRead.listModels(
                    workspaceId = caller.workspaceId,
                    after = afterName?.let { SemanticModelListBoundary(SemanticElementName(it)) },
                    limit = request.limit,
                ).let { page ->
                    page.items.map { view ->
                        AgentResourceListingItem.SemanticModel(
                            modelPath(view.definition.name.value),
                            view.definition.name.value,
                        )
                    } to page.next?.name?.value
                }
                SemanticCollection.METRICS -> currentRead.listMetrics(
                    workspaceId = caller.workspaceId,
                    after = afterName?.let { MetricListBoundary(SemanticElementName(it)) },
                    limit = request.limit,
                ).let { page ->
                    val sourceConnectors = currentRead.findMetricSourceConnectors(
                        caller.workspaceId, page.items.map { it.definition.name }.toSet(),
                    )
                    page.items.map { view ->
                        AgentResourceListingItem.Metric(
                            metricPath(view.definition.name.value),
                            view.definition.name.value,
                            sourceConnectors.getValue(view.definition.name).map(::agentSourceConnector),
                        )
                    } to page.next?.name?.value
                }
            }
            AgentListingOutcome.Found(
                AgentResourceListingResponse(
                    items = items,
                    nextCursor = nextName?.let { name -> encodeCursor(request.path, name) },
                    availableOperations = buildList {
                        if (canEdit(caller)) add(AgentAvailableOperation.Create(path = request.path, inputKind = collection.inputKind))
                        add(AgentAvailableOperation.Invoke(path = request.path, action = CHECK_ACTION, inputKind = collection.checkInputKind))
                    },
                ),
            )
        }
    }

    override suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome = agentOwnerGuard("AgentSemanticOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentResourceOutcome::Rejected) {
        when {
            AgentSemanticHistoryPaths.version.matches(request.path.value) -> readVersion(caller, request)
            MODEL_PATH.matches(request.path.value) -> {
                val name = pathName(request.path)
                currentRead.findModelByName(caller.workspaceId, SemanticElementName(name))
                    ?.let { view -> modelResource(caller, request.path, view) }
                    ?: AgentResourceOutcome.NotFound
            }
            METRIC_PATH.matches(request.path.value) -> {
                val name = pathName(request.path)
                currentRead.findMetricByName(caller.workspaceId, SemanticElementName(name))
                    ?.let { view -> metricResource(caller, request.path, view) }
                    ?: AgentResourceOutcome.NotFound
            }
            else -> AgentResourceOutcome.NotFound
        }
    }

    override suspend fun create(
        caller: AgentWorkspaceCaller,
        request: AgentCreateRequest,
    ): AgentActionOutcome {
        return agentOwnerGuard("AgentSemanticOwnerAdapter", AgentOwnerProblems.semanticCreateUnconfirmed, AgentActionOutcome::Rejected) {
            try {
                when (val input = request.input) {
                    is AgentCreateInput.SemanticModel -> {
                        if (request.path.value != MODEL_COLLECTION_PATH) return AgentActionOutcome.NotFound
                        val authorization = when (val decision = authorizeMutation(caller, MutationIntent.SEMANTIC_MODEL_CREATE)) {
                            is ControlAdmissionDecision.Admitted -> decision.context
                            is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
                        }
                        authority.putModel(
                            PutSemanticModelCommand(
                                authorization = authorization,
                                idempotencyKey = caller.requestId.value,
                                semanticModelId = null,
                                definition = input.definition.toDomain(),
                                aggregateMetrics = input.aggregateMetrics.mapIndexed { index, metric -> metric.toDomain(index) },
                            ),
                        ).toCreateOutcome(request.path) { result -> modelPath(result.model.definition.name.value) }
                    }
                    is AgentCreateInput.Metric -> {
                        if (request.path.value != METRIC_COLLECTION_PATH) return AgentActionOutcome.NotFound
                        val authorization = when (val decision = authorizeMutation(caller, MutationIntent.METRIC_CREATE)) {
                            is ControlAdmissionDecision.Admitted -> decision.context
                            is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
                        }
                        authority.putMetric(
                            PutMetricCommand(
                                authorization = authorization,
                                idempotencyKey = caller.requestId.value,
                                metricId = null,
                                definition = input.definition.toDomain(),
                            ),
                        ).toCreateOutcome(request.path) { view -> metricPath(view.definition.name.value) }
                    }
                    is AgentCreateInput.FolderPage, is AgentCreateInput.DocumentPage, is AgentCreateInput.DashboardPage,
                    is AgentCreateInput.PageVersion, is AgentCreateInput.PageContentUpload,
                    is AgentCreateInput.OrganizationInvitation, is AgentCreateInput.Connector, is AgentCreateInput.Dashboard -> AgentActionOutcome.NotFound
                }
            } catch (problem: SemanticDefinitionProblem) {
                rejected(
                    problem.code,
                    problem.detail,
                    violations = problem.violations.map { violation ->
                        AgentProblemViolation("input.${violation.field}", violation.code, violation.message.take(512), null, null)
                    },
                )
            }
        }
    }

    override suspend fun edit(
        caller: AgentWorkspaceCaller,
        request: AgentEditRequest,
    ): AgentActionOutcome {
        if (request.revision != null) {
            return rejected("REVISION_NOT_SUPPORTED", "Semantic edits do not accept a revision.")
        }
        val invalidRootOperation = request.patch.indexOfFirst { operation ->
            operation.path.isEmpty() && operation.op != AgentJsonPatchOp.REPLACE
        }
        if (invalidRootOperation >= 0) {
            return rejected(
                "PATCH_INVALID",
                "The Semantic document root supports replace only.",
                violation = patchViolation(
                    invalidRootOperation,
                    request.patch[invalidRootOperation].path,
                    "The document root supports replace only.",
                ),
            )
        }
        return agentOwnerGuard("AgentSemanticOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentActionOutcome::Rejected) {
            try {
                when {
                    MODEL_PATH.matches(request.path.value) -> {
                        val authorization = when (val decision = authorizeMutation(caller, MutationIntent.SEMANTIC_MODEL_UPDATE)) {
                            is ControlAdmissionDecision.Admitted -> decision.context
                            is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
                        }
                        val current = currentRead.findModelByName(
                            caller.workspaceId,
                            SemanticElementName(pathName(request.path)),
                        ) ?: return AgentActionOutcome.NotFound
                        val patched = applyPatch(current.definitionJson(), request)
                        val definition = decodeModel(patched).toDomain()
                        authority.putModel(
                            PutSemanticModelCommand(
                                authorization = authorization,
                                idempotencyKey = caller.requestId.value,
                                semanticModelId = current.semanticModelId,
                                definition = definition,
                                aggregateMetrics = requireNotNull(request.aggregateMetrics).mapIndexed { index, metric -> metric.toDomain(index) },
                            ),
                        ).toEditOutcome(request.path)
                    }
                    METRIC_PATH.matches(request.path.value) -> {
                        val authorization = when (val decision = authorizeMutation(caller, MutationIntent.METRIC_UPDATE)) {
                            is ControlAdmissionDecision.Admitted -> decision.context
                            is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
                        }
                        val current = currentRead.findMetricByName(
                            caller.workspaceId,
                            SemanticElementName(pathName(request.path)),
                        ) ?: return AgentActionOutcome.NotFound
                        val patched = applyPatch(current.definitionJson(), request)
                        val definition = decodeMetric(patched).toDomain()
                        authority.putMetric(
                            PutMetricCommand(
                                authorization = authorization,
                                idempotencyKey = caller.requestId.value,
                                metricId = current.metricId,
                                definition = definition,
                            ),
                        ).toEditOutcome(request.path)
                    }
                    else -> AgentActionOutcome.NotFound
                }
            } catch (exception: AgentJsonPatchException) {
                rejected(
                    "PATCH_INVALID",
                    "The Semantic patch operation is invalid.",
                    violation = patchViolation(exception.operationIndex, exception.path, "The JSON Patch operation cannot be applied to the current document."),
                )
            } catch (exception: cc.monomer.agent.api.AgentWorkspaceRequestDecodingException) {
                rejected(
                    "SEMANTIC_DEFINITION_INVALID",
                    "The patched Semantic definition is invalid.",
                    violations = exception.violations.map { violation ->
                        violation.copy(
                            field = violation.field.removePrefix("input."),
                            operationIndex = null,
                            path = null,
                        )
                    },
                )
            } catch (problem: SemanticDefinitionProblem) {
                rejected(
                    problem.code,
                    problem.detail,
                    violations = problem.violations.map { violation ->
                        AgentProblemViolation(violation.field, violation.code, violation.message.take(512), null, null)
                    },
                )
            }
        }
    }

    override suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome {
        if (request.input == AgentInvokeInput.None && (request.action == "delete" || request.action == "restore")) {
            return mutateDefinition(caller, request)
        }
        if (request.action != CHECK_ACTION) return AgentActionOutcome.NotFound
        return agentOwnerGuard("AgentSemanticOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentActionOutcome::Rejected) {
            try {
                val (draft, reference) = when (val input = request.input) {
                    is AgentInvokeInput.SemanticModelCheck -> when {
                        request.path.value == MODEL_COLLECTION_PATH ->
                            SemanticCheckDraft.Model(null, input.definition.toDomain()) to
                                AgentActionResultReference(AgentResourceKind.COLLECTION, request.path)
                        MODEL_PATH.matches(request.path.value) -> {
                            val current = currentRead.findModelByName(caller.workspaceId, SemanticElementName(pathName(request.path)))
                                ?: return AgentActionOutcome.NotFound
                            SemanticCheckDraft.Model(current.semanticModelId, input.definition.toDomain()) to
                                AgentActionResultReference(AgentResourceKind.SEMANTIC_MODEL, request.path)
                        }
                        else -> return AgentActionOutcome.NotFound
                    }
                    is AgentInvokeInput.MetricCheck -> {
                        val connectorId = input.connectorId?.let(::ConnectorId)
                        when {
                            request.path.value == METRIC_COLLECTION_PATH ->
                                SemanticCheckDraft.Metric(null, input.definition.toDomain(), connectorId) to
                                    AgentActionResultReference(AgentResourceKind.COLLECTION, request.path)
                            METRIC_PATH.matches(request.path.value) -> {
                                val current = currentRead.findMetricByName(caller.workspaceId, SemanticElementName(pathName(request.path)))
                                    ?: return AgentActionOutcome.NotFound
                                SemanticCheckDraft.Metric(current.metricId, input.definition.toDomain(), connectorId) to
                                    AgentActionResultReference(AgentResourceKind.METRIC, request.path)
                            }
                            else -> return AgentActionOutcome.NotFound
                        }
                    }
                    else -> return AgentActionOutcome.NotFound
                }
                when (val result = checks.check(SemanticCheckRequest(caller.workspaceId, draft))) {
                    is SemanticCheckResult.Checked -> AgentActionOutcome.Completed(
                        AgentActionResult.SemanticCheck(
                            path = request.path,
                            result = reference,
                            check = Json.parseToJsonElement(
                                checkMapper.writeValueAsString(SemanticCheckHttpView.fromDomain(result, draft)),
                            ),
                        ),
                    )
                    SemanticCheckResult.NotFoundOrOutsideWorkspace -> AgentActionOutcome.NotFound
                }
            } catch (problem: SemanticDefinitionProblem) {
                rejected(
                    "SEMANTIC_DEFINITION_INVALID",
                    problem.detail,
                    violation = AgentProblemViolation("input.definition", "SEMANTIC_DEFINITION_INVALID", problem.detail.take(512), null, null),
                )
            }
        }
    }

    private suspend fun listHistory(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome {
        return agentOwnerGuard("AgentSemanticOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentListingOutcome::Rejected) {
            val parts = request.path.value.split('/')
            val model = parts[1] == "semantic-models"
            val definitionId = Uuid.parse(parts[4])
            val scope = "${caller.workspaceId}/${request.path.value}"
            val before = try { decodeHistoryCursor(request.cursor, scope) } catch (_: org.springframework.web.server.ResponseStatusException) {
                return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
            }
            val history = (if (model) currentRead.readModelHistory(caller.workspaceId, SemanticModelId(definitionId), before, request.limit)
                else currentRead.readMetricHistory(caller.workspaceId, MetricId(definitionId), before, request.limit))
                ?: return AgentListingOutcome.NotFound
            if (history.name != parts[2]) return AgentListingOutcome.NotFound
            val names = provenanceNames.read(history.changes.map { it.actorId?.toString() }, history.changes.map { it.agentClientId })
            AgentListingOutcome.Found(AgentResourceListingResponse(
                history.changes.map { version -> AgentResourceListingItem.SemanticDefinitionVersion(
                    AgentResourcePath.parse("${request.path.value}/${version.changeId}"), versionKind(model), definitionId,
                    history.name, history.deleted, version.changeId, version.action.name, version.occurredAt,
                    names.actor(version.actorId?.toString()), version.credentialSurface, names.app(version.agentClientId),
                    version.restorable,
                ) },
                history.next?.let { encodeHistoryCursor(it, scope) }, emptyList(),
            ))
        }
    }

    private suspend fun readVersion(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome {
        val parts = request.path.value.split('/')
        val model = parts[1] == "semantic-models"
        val definitionId = Uuid.parse(parts[4])
        val version = findVersion(caller, parts) ?: return AgentResourceOutcome.NotFound
        if (version.definition.name() != parts[2]) return AgentResourceOutcome.NotFound
        val history = (if (model) currentRead.readModelHistory(caller.workspaceId, SemanticModelId(definitionId), null, 1)
            else currentRead.readMetricHistory(caller.workspaceId, MetricId(definitionId), null, 1)) ?: return AgentResourceOutcome.NotFound
        val names = provenanceNames.read(listOf(version.actorId?.toString()), listOf(version.agentClientId))
        return AgentResourceOutcome.Found(AgentResourceEnvelope.SemanticDefinitionVersion(
            request.path, AgentSemanticDefinitionVersionBody(
                versionKind(model), caller.workspaceId, definitionId, parts[2], history.deleted, version.changeId,
                version.action.name, version.definition.json(), version.occurredAt,
                names.actor(version.actorId?.toString()), version.credentialSurface, names.app(version.agentClientId),
                version.restorable,
            ),
            if (version.restorable && canEdit(caller)) listOf(AgentAvailableOperation.Invoke(request.path, "restore", null)) else emptyList(),
        ))
    }

    private suspend fun findVersion(caller: AgentWorkspaceCaller, parts: List<String>): SemanticDefinitionVersion? =
        if (parts[1] == "semantic-models") currentRead.readModelVersion(caller.workspaceId, SemanticModelId(Uuid.parse(parts[4])), Uuid.parse(parts[5]))
        else currentRead.readMetricVersion(caller.workspaceId, MetricId(Uuid.parse(parts[4])), Uuid.parse(parts[5]))

    private suspend fun mutateDefinition(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome {
        if (!(request.action == "delete" && AgentSemanticHistoryPaths.definition.matches(request.path.value) ||
                request.action == "restore" && AgentSemanticHistoryPaths.version.matches(request.path.value))) return AgentActionOutcome.NotFound
        return agentOwnerGuard("AgentSemanticOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentActionOutcome::Rejected) {
            val parts = request.path.value.split('/')
            val model = parts[1] == "semantic-models"
            val result: SemanticMutationResult<*>
            val resultPath: AgentResourcePath
            val kind: AgentResourceKind
            when {
                request.action == "delete" && AgentSemanticHistoryPaths.definition.matches(request.path.value) -> {
                    val authorization = when (val decision = authorizeMutation(caller, if (model) MutationIntent.SEMANTIC_MODEL_DELETE else MutationIntent.METRIC_DELETE)) {
                        is ControlAdmissionDecision.Admitted -> decision.context
                        is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
                    }
                    result = if (model) {
                        val current = currentRead.findModelByName(caller.workspaceId, SemanticElementName(parts[2])) ?: return AgentActionOutcome.NotFound
                        authority.deleteModel(DeleteSemanticModelCommand(authorization, current.semanticModelId))
                    } else {
                        val current = currentRead.findMetricByName(caller.workspaceId, SemanticElementName(parts[2])) ?: return AgentActionOutcome.NotFound
                        authority.deleteMetric(DeleteMetricCommand(authorization, current.metricId))
                    }
                    resultPath = AgentResourcePath.parse(request.path.value.substringBeforeLast('/'))
                    kind = AgentResourceKind.COLLECTION
                }
                request.action == "restore" && AgentSemanticHistoryPaths.version.matches(request.path.value) -> {
                    val authorization = when (val decision = authorizeMutation(caller, if (model) MutationIntent.SEMANTIC_MODEL_UPDATE else MutationIntent.METRIC_UPDATE)) {
                        is ControlAdmissionDecision.Admitted -> decision.context
                        is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
                    }
                    val version = findVersion(caller, parts) ?: return AgentActionOutcome.NotFound
                    if (version.definition.name() != parts[2]) return AgentActionOutcome.NotFound
                    result = if (model) authority.restoreModel(RestoreSemanticModelCommand(authorization, caller.requestId.value, SemanticModelId(Uuid.parse(parts[4])), Uuid.parse(parts[5])))
                        else authority.restoreMetric(RestoreMetricCommand(authorization, caller.requestId.value, MetricId(Uuid.parse(parts[4])), Uuid.parse(parts[5])))
                    resultPath = AgentResourcePath.parse(request.path.value.substringBefore("/history/"))
                    kind = if (model) AgentResourceKind.SEMANTIC_MODEL else AgentResourceKind.METRIC
                }
                else -> return AgentActionOutcome.NotFound
            }
            when (result) {
                is SemanticMutationResult.Committed -> AgentActionOutcome.Completed(AgentActionResult.Invoke(
                    request.path, request.action, AgentActionStatus.COMPLETED, AgentActionResultReference(kind, resultPath),
                ))
                is SemanticMutationResult.AggregateMetricNameConflict -> rejected(
                    "SEMANTIC_NAME_CONFLICT", "A metric with that name already exists.", AgentWorkspaceProblemStatus.CONFLICT,
                    violation = AgentProblemViolation(result.field, "SEMANTIC_NAME_CONFLICT", "Choose another metric name.", null, null),
                )
                SemanticMutationResult.Conflict -> rejected("SEMANTIC_NAME_CONFLICT", "A Semantic resource with that name already exists.", AgentWorkspaceProblemStatus.CONFLICT)
                SemanticMutationResult.IdempotencyConflict -> idempotencyConflict()
                SemanticMutationResult.NotFoundOrOutsideWorkspace -> AgentActionOutcome.NotFound
                SemanticMutationResult.InvalidAuthorization -> AgentActionOutcome.Forbidden
                is SemanticMutationResult.Invalid -> rejected("SEMANTIC_DEFINITION_INVALID", "The recorded Semantic definition is invalid.", violations = result.reasons.map { it.toViolation("definition") })
            }
        }
    }

    private fun versionKind(model: Boolean) = if (model) AgentResourceKind.SEMANTIC_MODEL_VERSION else AgentResourceKind.METRIC_VERSION

    private fun SemanticDefinitionSnapshot.name(): String = when (this) {
        is SemanticDefinitionSnapshot.Model -> definition.name.value
        is SemanticDefinitionSnapshot.Metric -> definition.name.value
    }

    private fun SemanticDefinitionSnapshot.json(): JsonObject = Json.parseToJsonElement(objectMapper.writeValueAsString(when (this) {
        is SemanticDefinitionSnapshot.Model -> SemanticModelDefinitionDto.fromDomain(definition)
        is SemanticDefinitionSnapshot.Metric -> MetricDefinitionDto.fromDomain(definition)
    })).jsonObject

    private suspend fun authorizeMutation(
        caller: AgentWorkspaceCaller,
        intent: MutationIntent,
    ): ControlAdmissionDecision<AuthorizedMutationContext> = admission.authorizeMutation(
        MutationAdmissionRequest(
            AdmissionRequest(caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource),
            intent,
        ),
    )

    private suspend fun modelResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        view: SemanticModelView,
    ): AgentResourceOutcome {
        val change = currentRead.readModelChange(caller.workspaceId, view.semanticModelId)
        val provenance = provenanceNames.read(listOf(change?.actorId?.toString()), listOf(change?.agentClientId))
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.SemanticModel(
                path = path,
                body = AgentSemanticModelResourceBody(
                    semanticModelId = view.semanticModelId.value,
                    workspaceId = view.workspaceId,
                    name = view.definition.name.value,
                    definition = decodeModel(view.definitionJson()),
                    sourcePartitionInfo = view.sourcePartitionInfo.map { it.toAgent() },
                ),
                availableOperations = editOperationIfAllowed(caller, path, view.semanticModelId.value),
                updatedAt = view.updatedAt,
                updatedBy = provenance.actor(change?.actorId?.toString()),
                credentialSurface = change?.credentialSurface,
                agentApp = provenance.app(change?.agentClientId),
            ),
        )
    }

    private suspend fun metricResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        view: MetricView,
    ): AgentResourceOutcome {
        val change = currentRead.readMetricChange(caller.workspaceId, view.metricId)
        val sourceConnectors = currentRead.findMetricSourceConnectors(caller.workspaceId, setOf(view.definition.name))
            .getValue(view.definition.name).map(::agentSourceConnector)
        val provenance = provenanceNames.read(listOf(change?.actorId?.toString()), listOf(change?.agentClientId))
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.Metric(
                path = path,
                body = AgentMetricResourceBody(
                    metricId = view.metricId.value,
                    workspaceId = view.workspaceId,
                    name = view.definition.name.value,
                    definition = decodeMetric(view.definitionJson()),
                    sourcePartitionInfo = view.sourcePartitionInfo.map { it.toAgent() },
                ),
                availableOperations = editOperationIfAllowed(caller, path, view.metricId.value),
                updatedAt = view.updatedAt,
                updatedBy = provenance.actor(change?.actorId?.toString()),
                credentialSurface = change?.credentialSurface,
                agentApp = provenance.app(change?.agentClientId),
                sourceConnectors = sourceConnectors,
            ),
        )
    }

    private suspend fun editOperationIfAllowed(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        definitionId: Uuid,
    ): List<AgentAvailableOperation> = buildList {
        add(AgentAvailableOperation.List(AgentResourcePath.parse("${path.value}/history/$definitionId")))
        if (canEdit(caller)) {
            add(AgentAvailableOperation.Edit(path = path))
            add(AgentAvailableOperation.Invoke(path, "delete", null))
        }
        add(
            AgentAvailableOperation.Invoke(
                path = path,
                action = CHECK_ACTION,
                inputKind = if (MODEL_PATH.matches(path.value)) SemanticCollection.MODELS.checkInputKind else SemanticCollection.METRICS.checkInputKind,
            ),
        )
    }

    private suspend fun canEdit(caller: AgentWorkspaceCaller): Boolean = editAvailability.isAvailable(caller)

    private fun applyPatch(document: JsonElement, request: AgentEditRequest): JsonElement =
        AgentJsonPatch.apply(document, request.patch)

    private fun cc.monomer.agent.api.AgentSemanticAggregateMetric.toDomain(index: Int): cc.monomer.semantic.api.SemanticAggregateMetric {
        return decodeSemanticTransport { objectMapper.convertValue(AgentSemanticDefinitionJson.aggregateMetric(this),
            cc.monomer.control.semantic.inbound.rest.SemanticAggregateMetricDto::class.java).toDomain(index) }
    }

    private fun AgentSemanticModelDefinition.toDomain(): SemanticModelDefinition =
        decodeSemanticTransport { objectMapper.convertValue(AgentSemanticDefinitionJson.model(this), SemanticModelDefinitionDto::class.java).toDomain() }

    private fun AgentMetricDefinition.toDomain(): SemanticMetricDefinition =
        decodeSemanticTransport { objectMapper.convertValue(AgentSemanticDefinitionJson.metric(this), MetricDefinitionDto::class.java).toDomain() }

    private fun SemanticModelView.definitionJson(): JsonElement =
        Json.parseToJsonElement(objectMapper.writeValueAsString(SemanticModelDefinitionDto.fromDomain(definition)))

    private fun MetricView.definitionJson(): JsonElement =
        Json.parseToJsonElement(objectMapper.writeValueAsString(MetricDefinitionDto.fromDomain(definition)))

    private fun decodeModel(definition: JsonElement): AgentSemanticModelDefinition {
        val input = decoder.create(createRequestJson(MODEL_COLLECTION_PATH, "SEMANTIC_MODEL_DEFINITION", definition)).value.input
        return (input as AgentCreateInput.SemanticModel).definition
    }

    private fun decodeMetric(definition: JsonElement): AgentMetricDefinition {
        val input = decoder.create(createRequestJson(METRIC_COLLECTION_PATH, "METRIC_DEFINITION", definition)).value.input
        return (input as AgentCreateInput.Metric).definition
    }

    private fun createRequestJson(path: String, kind: String, definition: JsonElement): String =
        """{"path":${objectMapper.writeValueAsString(path)},"input":{"kind":${objectMapper.writeValueAsString(kind)},"definition":$definition${if (path == MODEL_COLLECTION_PATH) ",\"aggregateMetrics\":[]" else ""}}}"""

    private fun <T> SemanticMutationResult<T>.toCreateOutcome(
        requestPath: AgentResourcePath,
        resultPath: (T) -> AgentResourcePath,
    ): AgentActionOutcome = when (this) {
        is SemanticMutationResult.Committed -> completed(
            AgentWorkspaceOperation.CREATE,
            requestPath,
            resultPath(value),
        )
        is SemanticMutationResult.AggregateMetricNameConflict -> rejected(
            "SEMANTIC_NAME_CONFLICT", "A metric with that name already exists.", AgentWorkspaceProblemStatus.CONFLICT,
            violation = AgentProblemViolation("input.$field", "SEMANTIC_NAME_CONFLICT", "Choose another metric name.", null, null),
        )
        SemanticMutationResult.Conflict -> rejected(
            "SEMANTIC_NAME_CONFLICT",
            "A Semantic resource with that name already exists.",
            AgentWorkspaceProblemStatus.CONFLICT,
        )
        SemanticMutationResult.IdempotencyConflict -> idempotencyConflict()
        is SemanticMutationResult.Invalid -> rejected(
            "SEMANTIC_DEFINITION_INVALID",
            "The Semantic definition is invalid.",
            violations = reasons.map { it.toViolation("input.definition") },
        )
        SemanticMutationResult.NotFoundOrOutsideWorkspace -> AgentActionOutcome.NotFound
        SemanticMutationResult.InvalidAuthorization -> AgentActionOutcome.Forbidden
    }

    private fun <T> SemanticMutationResult<T>.toEditOutcome(
        requestPath: AgentResourcePath,
    ): AgentActionOutcome =
        when (this) {
            is SemanticMutationResult.Committed -> completed(AgentWorkspaceOperation.EDIT, requestPath, requestPath)
            is SemanticMutationResult.AggregateMetricNameConflict -> rejected(
                "SEMANTIC_NAME_CONFLICT", "A metric with that name already exists.", AgentWorkspaceProblemStatus.CONFLICT,
                violation = AgentProblemViolation(field, "SEMANTIC_NAME_CONFLICT", "Choose another metric name.", null, null),
            )
            SemanticMutationResult.Conflict -> rejected(
                "SEMANTIC_NAME_CONFLICT",
                "A Semantic resource with that name already exists.",
                AgentWorkspaceProblemStatus.CONFLICT,
            )
            SemanticMutationResult.IdempotencyConflict -> idempotencyConflict()
            is SemanticMutationResult.Invalid -> rejected(
                "SEMANTIC_DEFINITION_INVALID",
                "The Semantic definition is invalid.",
                violations = reasons.map { it.toViolation("definition") },
            )
            SemanticMutationResult.NotFoundOrOutsideWorkspace -> AgentActionOutcome.NotFound
            SemanticMutationResult.InvalidAuthorization -> AgentActionOutcome.Forbidden
        }

    private fun completed(
        operation: AgentWorkspaceOperation,
        requestPath: AgentResourcePath,
        resultPath: AgentResourcePath,
    ): AgentActionOutcome = AgentActionOutcome.Completed(
        (if (operation == AgentWorkspaceOperation.CREATE) AgentActionResult.Create(path = requestPath, result = AgentActionResultReference(
                kind = if (resultPath.value.startsWith("$MODEL_COLLECTION_PATH/")) {
                    AgentResourceKind.SEMANTIC_MODEL
                } else {
                    AgentResourceKind.METRIC
                },
                path = resultPath,
            )) else AgentActionResult.Edit(path = requestPath, result = AgentActionResultReference(
                kind = if (resultPath.value.startsWith("$MODEL_COLLECTION_PATH/")) {
                    AgentResourceKind.SEMANTIC_MODEL
                } else {
                    AgentResourceKind.METRIC
                },
                path = resultPath,
            ))),
    )

    private fun rejected(
        code: String,
        detail: String,
        status: AgentWorkspaceProblemStatus = AgentWorkspaceProblemStatus.BAD_REQUEST,
        violation: AgentProblemViolation? = null,
        violations: List<AgentProblemViolation> = listOfNotNull(violation),
    ): AgentActionOutcome = AgentActionOutcome.Rejected(
        AgentWorkspaceProblem(
            code = code,
            title = "Semantic operation rejected",
            detail = detail,
            status = status,
            retryable = false,
            currentRevision = null,
            violations = violations,
            hints = emptyList(),
            nextActions = emptyList(),
            retryAfterSeconds = null,
            roleRequirement = null,
            partitionFilters = emptyList(),
        ),
    )

    private fun idempotencyConflict(): AgentActionOutcome = rejected(
        "IDEMPOTENCY_CONFLICT",
        "This request conflicts with an earlier Semantic change recorded under the same request ID.",
        AgentWorkspaceProblemStatus.CONFLICT,
    )

    private fun patchViolation(operationIndex: Int, path: String, message: String) = AgentProblemViolation(
        field = "patch[$operationIndex]",
        code = "PATCH_OPERATION_INVALID",
        message = message.take(512),
        operationIndex = operationIndex,
        path = AgentJsonPatch.safeDiagnosticPath(path),
    )

    private fun cc.monomer.semantic.api.SemanticInvalidReason.toViolation(scope: String) = AgentProblemViolation(
        field = field?.let { if (it.startsWith("aggregateMetrics[")) { if (scope.startsWith("input")) "input.$it" else it } else "$scope.$it" } ?: scope,
        code = code.name,
        message = "The Semantic definition is invalid.",
        operationIndex = null,
        path = null,
    )

    private fun agentSourceConnector(connector: cc.monomer.semantic.api.MetricSourceConnector) =
        cc.monomer.agent.api.AgentMetricSourceConnector(connector.connectorId.value, connector.displayName)

    private fun collection(path: AgentResourcePath): SemanticCollection? = when (path.value) {
        MODEL_COLLECTION_PATH -> SemanticCollection.MODELS
        METRIC_COLLECTION_PATH -> SemanticCollection.METRICS
        else -> null
    }

    private fun encodeCursor(path: AgentResourcePath, name: String): String {
        val payload = """{"version":1,"path":"${path.value}","name":"$name"}"""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(cursor: String, expectedPath: AgentResourcePath): String {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val match = CURSOR_PAYLOAD.matchEntire(decoded) ?: throw IllegalArgumentException("invalid cursor payload")
        val path = AgentResourcePath.parse(match.groupValues[1])
        val name = match.groupValues[2]
        require(path == expectedPath && NAME.matches(name) && encodeCursor(path, name) == cursor)
        return name
    }

    private fun pathName(path: AgentResourcePath): String = path.value.substringAfterLast('/')

    private fun modelPath(name: String) = AgentResourcePath.parse("$MODEL_COLLECTION_PATH/$name")

    private fun metricPath(name: String) = AgentResourcePath.parse("$METRIC_COLLECTION_PATH/$name")

    private enum class SemanticCollection(val inputKind: String, val checkInputKind: String) {
        MODELS("SEMANTIC_MODEL_DEFINITION", "SEMANTIC_MODEL_CHECK"),
        METRICS("METRIC_DEFINITION", "METRIC_CHECK"),
    }

    private companion object {
        const val MODEL_COLLECTION_PATH = "workspace/semantic-models"
        const val METRIC_COLLECTION_PATH = "workspace/metrics"
        const val CHECK_ACTION = "check"
        val checkMapper: ObjectMapper = jacksonObjectMapper()
        val NAME = Regex("^${AgentPathPatterns.SEMANTIC_NAME}$")
        val CURSOR_PAYLOAD = Regex(
            "^\\{\\\"version\\\":1,\\\"path\\\":\\\"(workspace/(?:semantic-models|metrics))\\\",\\\"name\\\":\\\"(${AgentPathPatterns.SEMANTIC_NAME})\\\"}$",
        )
        val MODEL_PATH = Regex("^$MODEL_COLLECTION_PATH/${NAME.pattern.removePrefix("^").removeSuffix("$")}$")
        val METRIC_PATH = Regex("^$METRIC_COLLECTION_PATH/${NAME.pattern.removePrefix("^").removeSuffix("$")}$")
    }
}

fun interface AgentSemanticEditAvailability {
    suspend fun isAvailable(caller: AgentWorkspaceCaller): Boolean
}
