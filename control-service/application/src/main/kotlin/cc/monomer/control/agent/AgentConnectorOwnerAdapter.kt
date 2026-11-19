@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.connector.api.*
import cc.monomer.control.AdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.MutationAdmissionRequest
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.WorkspaceSampleQuery
import java.util.Base64
import kotlin.uuid.Uuid

class AgentConnectorOwnerAdapter(
    private val authority: ConnectorAuthorityUseCase,
    private val currentRead: ConnectorWorkspaceReadUseCase,
    private val admission: ControlAdmissionFacade,
    private val workspaceSamples: WorkspaceSampleQuery,
) : AgentConnectorOwnerPort {
    override suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome {
        if (request.path.value != COLLECTION) return AgentListingOutcome.NotFound
        val after = try {
            request.cursor?.let { cursor ->
                val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
                val value = decoded.removePrefix("1:$COLLECTION:")
                require(decoded == "1:$COLLECTION:$value" && RESOURCE.matches(value) && encodeCursor(value) == cursor)
                value
            }
        } catch (_: IllegalArgumentException) {
            return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
        }
        return agentOwnerGuard(OWNER, AgentOwnerProblems.ownerUnavailable, AgentListingOutcome::Rejected) {
            val remaining = currentRead.listByWorkspace(caller.workspaceId).sortedBy { path(it.connectorId).value }
                .filter { after == null || path(it.connectorId).value > after }
            val page = remaining.take(request.limit)
            AgentListingOutcome.Found(AgentResourceListingResponse(
                items = page.map { AgentResourceListingItem.Connector(path(it.connectorId), it.connectorType.name, it.displayName) },
                nextCursor = if (remaining.size > page.size) encodeCursor(path(page.last().connectorId).value) else null,
                availableOperations = if (workspaceSamples.readMarker(caller.workspaceId) == null && canManage(caller, MutationIntent.CONNECTOR_CREATE)) {
                    listOf(AgentAvailableOperation.Create(request.path, "CONNECTOR_CREATE"))
                } else emptyList(),
            ))
        }
    }

    override suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome {
        val id = connectorId(request.path) ?: return AgentResourceOutcome.NotFound
        return agentOwnerGuard(OWNER, AgentOwnerProblems.ownerUnavailable, AgentResourceOutcome::Rejected) {
            val current = currentRead.findByWorkspaceAndId(caller.workspaceId, id) ?: return AgentResourceOutcome.NotFound
            AgentResourceOutcome.Found(AgentResourceEnvelope.Connector(
                path = request.path,
                body = AgentConnectorResourceBody(current.connectorType.name, current.displayName, ConnectorSettingsCodec.encode(current.properties), current.sample),
                availableOperations = buildList {
                    add(AgentAvailableOperation.Read(AgentResourcePath.parse("${request.path.value}/catalog")))
                    if (current.connectorType != ConnectorProductType.DUCKDB && current.sample == null && canManage(caller, MutationIntent.CONNECTOR_UPDATE)) {
                        add(AgentAvailableOperation.Edit(request.path))
                        add(AgentAvailableOperation.Invoke(request.path, "rotate-credentials", "CONNECTOR_CREDENTIAL_ROTATE"))
                    }
                    if (current.connectorType != ConnectorProductType.DUCKDB && current.sample == null && canManage(caller, MutationIntent.CONNECTOR_DELETE)) {
                        add(AgentAvailableOperation.Invoke(request.path, "delete", null))
                    }
                },
            ))
        }
    }

    override suspend fun create(caller: AgentWorkspaceCaller, request: AgentCreateRequest): AgentActionOutcome {
        val input = request.input as? AgentCreateInput.Connector ?: return AgentActionOutcome.NotFound
        if (request.path.value != COLLECTION) return AgentActionOutcome.NotFound
        return mutate(caller, MutationIntent.CONNECTOR_CREATE) { authorization ->
            authority.create(CreateConnectorCommand(authorization, input.connectorType, input.displayName,
                input.properties, input.credential, caller.requestId.value)).outcome("input") { resultPath ->
                AgentActionResult.Create(request.path, AgentActionResultReference(AgentResourceKind.CONNECTOR, resultPath))
            }
        }
    }

    override suspend fun edit(caller: AgentWorkspaceCaller, request: AgentEditRequest): AgentActionOutcome {
        val id = connectorId(request.path) ?: return AgentActionOutcome.NotFound
        if (request.revision != null) return AgentActionOutcome.Rejected(problem("REVISION_NOT_SUPPORTED", AgentWorkspaceProblemStatus.BAD_REQUEST, null))
        return mutate(caller, MutationIntent.CONNECTOR_UPDATE) { authorization ->
            authority.revise(ReviseConnectorCommand(authorization, id,
                request.patch.map { ConnectorSettingsPatch(it.op.name.lowercase(), it.path, it.value) },
                caller.requestId.value)).outcome("patch") { resultPath ->
                AgentActionResult.Edit(request.path, AgentActionResultReference(AgentResourceKind.CONNECTOR, resultPath))
            }
        }
    }

    override suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome {
        val id = connectorId(request.path) ?: return AgentActionOutcome.NotFound
        if (request.action == "delete") {
            if (request.input != AgentInvokeInput.None) return AgentActionOutcome.NotFound
            return mutate(caller, MutationIntent.CONNECTOR_DELETE) { authorization ->
                when (authority.delete(DeleteConnectorCommand(authorization, id))) {
                    ConnectorDeletionResult.Deleted -> AgentActionOutcome.Completed(AgentActionResult.Invoke(request.path, request.action,
                        AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.COLLECTION, AgentResourcePath.parse(COLLECTION))))
                    ConnectorDeletionResult.NotFoundOrOutsideWorkspace -> AgentActionOutcome.NotFound
                    ConnectorDeletionResult.InvalidAuthorization -> AgentActionOutcome.Forbidden
                    is ConnectorDeletionResult.InvalidInput -> AgentActionOutcome.Rejected(
                        problem("CONNECTOR_INPUT_INVALID", AgentWorkspaceProblemStatus.BAD_REQUEST, null).copy(
                            detail = "Sample connectors are managed by Monomer and cannot be changed.",
                        ))
                    ConnectorDeletionResult.RefreshActive -> AgentActionOutcome.Rejected(problem("CONNECTOR_REFRESH_ACTIVE", AgentWorkspaceProblemStatus.CONFLICT, null).copy(
                        detail = "A Catalog refresh is running for this connector. Cancel it in Catalog, then delete the connector.",
                    ))
                }
            }
        }
        val input = request.input as? AgentInvokeInput.ConnectorCredentialRotate ?: return AgentActionOutcome.NotFound
        if (request.action != "rotate-credentials") return AgentActionOutcome.NotFound
        return mutate(caller, MutationIntent.CONNECTOR_UPDATE) { authorization ->
            authority.rotateCredential(RotateConnectorCredentialCommand(authorization, id, input.credential, caller.requestId.value))
                .outcome("input.credential") { resultPath -> AgentActionResult.Invoke(request.path, request.action, AgentActionStatus.COMPLETED,
                    AgentActionResultReference(AgentResourceKind.CONNECTOR, resultPath)) }
        }
    }

    private suspend fun canManage(caller: AgentWorkspaceCaller, intent: MutationIntent): Boolean =
        authorize(caller, intent) is ControlAdmissionDecision.Admitted

    private suspend fun authorize(caller: AgentWorkspaceCaller, intent: MutationIntent) = admission.authorizeMutation(
        MutationAdmissionRequest(AdmissionRequest(caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource), intent),
    )

    private suspend fun mutate(
        caller: AgentWorkspaceCaller,
        intent: MutationIntent,
        operation: suspend (cc.monomer.identity.api.AuthorizedMutationContext) -> AgentActionOutcome,
    ): AgentActionOutcome = agentOwnerGuard(OWNER, CHANGE_UNCONFIRMED, AgentActionOutcome::Rejected) {
        when (val decision = authorize(caller, intent)) {
            is ControlAdmissionDecision.Admitted -> operation(decision.context)
            is ControlAdmissionDecision.Denied -> decision.toAgentActionDenial()
        }
    }

    private fun ConnectorMutationResult.outcome(inputField: String, result: (AgentResourcePath) -> AgentActionResult): AgentActionOutcome = when (this) {
        is ConnectorMutationResult.Committed -> AgentActionOutcome.Completed(result(path(connector.connectorId)))
        ConnectorMutationResult.NotFoundOrOutsideWorkspace -> AgentActionOutcome.NotFound
        ConnectorMutationResult.InvalidAuthorization -> AgentActionOutcome.Forbidden
        is ConnectorMutationResult.InvalidInput -> AgentActionOutcome.Rejected(
            problem("CONNECTOR_INPUT_INVALID", AgentWorkspaceProblemStatus.BAD_REQUEST, inputField).copy(detail = message),
        )
        ConnectorMutationResult.LimitExceeded -> AgentActionOutcome.Rejected(problem("CONNECTOR_LIMIT_EXCEEDED", AgentWorkspaceProblemStatus.CONFLICT, null).copy(
            detail = "Free workspaces can have up to 10 connectors. Use an existing connector or choose another workspace.",
        ))
        ConnectorMutationResult.Conflict -> AgentActionOutcome.Rejected(problem("CONNECTOR_CONFLICT", AgentWorkspaceProblemStatus.CONFLICT, null))
    }

    private fun connectorId(path: AgentResourcePath): ConnectorId? = try {
        if (RESOURCE.matches(path.value)) ConnectorId(Uuid.parse(path.value.substringAfterLast('/'))) else null
    } catch (_: IllegalArgumentException) { null }

    private fun path(id: ConnectorId) = AgentResourcePath.parse("$COLLECTION/$id")
    private fun encodeCursor(path: String) = Base64.getUrlEncoder().withoutPadding().encodeToString("1:$COLLECTION:$path".toByteArray(Charsets.UTF_8))
    private fun problem(code: String, status: AgentWorkspaceProblemStatus, inputField: String?) = AgentWorkspaceProblem(
        code = code,
        title = "Connector operation rejected",
        detail = if (inputField != null) {
            "Check the declared Connector input and current settings. Connector type and PostgreSQL or Redshift database changes require a new Connector."
        } else "The Connector operation could not be completed.",
        status = status,
        retryable = false,
        currentRevision = null,
        violations = inputField?.let { listOf(AgentProblemViolation(it, "CONNECTOR_INPUT_INVALID", "The Connector input is invalid.", null, null)) }.orEmpty(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    private companion object {
        const val OWNER = "AgentConnectorOwnerAdapter"
        const val COLLECTION = "workspace/connectors"
        val RESOURCE = Regex("^$COLLECTION/${AgentPathPatterns.UUID_V7}$")

        // The owner keys receipts by the server requestId, so a resend is a new request that may duplicate the change.
        val CHANGE_UNCONFIRMED = AgentWorkspaceProblem(
            code = "OWNER_UNAVAILABLE",
            title = "Service Unavailable",
            detail = "Monomer could not confirm whether the Connector change was saved. Read the Connector before sending the change again.",
            status = AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE,
            retryable = false,
            currentRevision = null,
            violations = emptyList(),
            hints = emptyList(),
            nextActions = emptyList(),
            retryAfterSeconds = null,
            roleRequirement = null,
            partitionFilters = emptyList(),
        )
    }
}
