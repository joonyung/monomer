@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.publicapi

import cc.monomer.control.agent.toHttpValue

import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActor
import cc.monomer.agent.api.AgentApp
import cc.monomer.agent.api.AgentPagesPaths
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.identity.api.WorkspaceId
import java.net.URI
import cc.monomer.agent.api.AgentAvailableOperation
import cc.monomer.agent.api.AgentResourceBody
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentRequestId
import cc.monomer.agent.application.AgentSemanticDefinitionJson
import com.fasterxml.jackson.databind.ObjectMapper

object AgentWorkspaceJson {
    fun organizationsJson(organizations: List<cc.monomer.identity.api.OAuthOrganization>): Map<String, Any?> = mapOf(
        "organizations" to organizations.map { mapOf("organizationId" to it.organizationId.value.toString(), "displayName" to it.displayName, "role" to it.role) },
    )

    fun workspacesJson(workspaces: List<cc.monomer.identity.api.OAuthWorkspace>): Map<String, Any?> = mapOf(
        "workspaces" to workspaces.map {
            mapOf("workspaceId" to it.workspaceId.value.toString(), "organizationId" to it.organizationId.value.toString(), "organizationDisplayName" to it.organizationDisplayName,
                "displayName" to it.displayName, "sample" to it.sample?.let { sample -> mapOf("dataset" to sample.dataset) })
        },
    )

    fun agentResourceJson(requestId: AgentRequestId, resource: AgentResourceEnvelope): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "requestId" to requestId.value,
            "kind" to resource.kind.name,
            "path" to resource.path.value,
        ).apply {
            putOptional("revision", resource.revision?.toString())
            putOptional("body", resource.body?.let(::agentResourceBodyJson))
            putOptional("availableOperations", resource.availableOperations.takeIf { it.isNotEmpty() || AgentPagesPaths.result.matches(resource.path.value) }?.map(::availableOperationJson))
            when (resource) {
                is AgentResourceEnvelope.SemanticModel -> putSemanticProvenance(resource.updatedAt, resource.updatedBy, resource.credentialSurface, resource.agentApp)
                is AgentResourceEnvelope.Metric -> {
                    putSemanticProvenance(resource.updatedAt, resource.updatedBy, resource.credentialSurface, resource.agentApp)
                    put("sourceConnectors", resource.sourceConnectors.map(::metricSourceConnectorJson))
                }
                else -> Unit
            }
        }

    private fun MutableMap<String, Any?>.putSemanticProvenance(
        updatedAt: java.time.Instant,
        updatedBy: AgentActor?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        agentApp: AgentApp?,
    ) {
        put("updatedAt", updatedAt.toString())
        putOptional("updatedBy", updatedBy?.let(::actorJson))
        putOptional("credentialSurface", credentialSurface?.name)
        putOptional("agentApp", agentApp?.let(::agentAppJson))
    }

    private fun actorJson(actor: AgentActor): Map<String, Any?> = linkedMapOf("id" to actor.id, "displayName" to actor.displayName)

    private fun agentAppJson(app: AgentApp): Map<String, Any?> =
        linkedMapOf("clientId" to app.clientId, "name" to app.name, "verifiedHost" to app.verifiedHost)

    private fun metricSourceConnectorJson(connector: cc.monomer.agent.api.AgentMetricSourceConnector): Map<String, Any?> =
        mapOf("connectorId" to connector.connectorId.toString(), "displayName" to connector.displayName)

    private fun pageActorJson(actor: cc.monomer.agent.api.AgentPageActor): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to actor.kind.name, "id" to actor.id, "displayName" to actor.displayName,
    ).apply { putOptional("agentApp", actor.agentApp?.let(::agentAppJson)) }

    fun agentListingJson(requestId: AgentRequestId, listing: AgentResourceListingResponse): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "requestId" to requestId.value,
            "items" to listing.items.map(::listingItemJson),
        ).apply {
            putOptional("nextCursor", listing.nextCursor)
            putOptional("availableOperations", listing.availableOperations.takeIf { it.isNotEmpty() }?.map(::availableOperationJson))
        }

    fun listingItemJson(item: AgentResourceListingItem): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to item.kind.name,
        "path" to item.path.value,
    ).apply {
        when (item) {
            is AgentResourceListingItem.DashboardDefinitionVersion -> {
                put("definitionId", item.definitionId.toString())
                put("name", item.name)
                put("deleted", item.deleted)
                put("changeId", item.changeId.toString())
                put("action", item.action)
                put("occurredAt", item.occurredAt.toString())
                put("actor", item.actor?.let(::actorJson))
                put("credentialSurface", item.credentialSurface?.name)
                put("agentApp", item.agentApp?.let(::agentAppJson))
                put("restorable", item.restorable)
            }
            is AgentResourceListingItem.SemanticDefinitionVersion -> {
                put("definitionId", item.definitionId.toString())
                put("name", item.name)
                put("deleted", item.deleted)
                put("changeId", item.changeId.toString())
                put("action", item.action)
                put("occurredAt", item.occurredAt.toString())
                put("actor", item.actor?.let(::actorJson))
                put("credentialSurface", item.credentialSurface?.name)
                put("agentApp", item.agentApp?.let(::agentAppJson))
                put("restorable", item.restorable)
            }
            is AgentResourceListingItem.OrganizationMember -> {
                put("userId", item.userId.toString())
                put("displayName", item.displayName)
                put("role", item.role.name)
            }
            is AgentResourceListingItem.OrganizationInvitation -> {
                put("id", item.id.toString())
                put("email", item.email)
                put("status", item.status.name)
            }
            is AgentResourceListingItem.OrganizationWorkspaceMember -> putAll(AgentManagementJson.json(item.body))
            is AgentResourceListingItem.WorkspaceMember -> {
                put("userId", item.userId.toString())
                put("displayName", item.displayName)
                put("role", item.role.name)
            }
            is AgentResourceListingItem.WorkspaceMemberCandidate -> {
                put("userId", item.userId.toString())
                put("displayName", item.displayName)
            }
            is AgentResourceListingItem.LineageResource -> {
                put("ref", AgentManagementJson.json(item.ref))
                put("label", item.label)
                put("address", item.address)
            }
            is AgentResourceListingItem.PageSession -> {
                put("createdAt", item.createdAt.toString())
                put("updatedAt", item.updatedAt.toString())
            }
            is AgentResourceListingItem.PageRun -> put("body", agentResourceBodyJson(item.body))
            is AgentResourceListingItem.Page -> put("body", agentResourceBodyJson(item.body))
            is AgentResourceListingItem.PageVersion -> put("body", agentResourceBodyJson(item.body))
            is AgentResourceListingItem.Connector -> {
                put("connectorType", item.connectorType)
                put("displayName", item.displayName)
            }
            is AgentResourceListingItem.Collection -> {
                put("name", item.name)
            }
            is AgentResourceListingItem.SemanticModel -> {
                put("name", item.name)
            }
            is AgentResourceListingItem.Metric -> {
                put("name", item.name)
                put("sourceConnectors", item.sourceConnectors.map(::metricSourceConnectorJson))
            }
            is AgentResourceListingItem.Catalog -> {
                put("name", item.name)
            }
            is AgentResourceListingItem.Schema -> {
                put("name", item.name)
                putOptional("updatedAt", item.updatedAt?.toString())
            }
            is AgentResourceListingItem.Relation -> {
                put("name", item.name)
                put("relationKind", item.relationKind.name)
                putOptional("updatedAt", item.updatedAt?.toString())
            }
            is AgentResourceListingItem.CatalogOption -> {
                put("name", item.name)
            }
            is AgentResourceListingItem.SchemaOption -> {
                put("optionKind", "SCHEMA")
                put("name", item.name)
                put("available", item.available)
            }
            is AgentResourceListingItem.RelationOption -> {
                put("optionKind", "RELATION")
                put("name", item.name)
                put("available", item.available)
                put("relationKind", item.relationKind.name)
            }
            is AgentResourceListingItem.CatalogRefresh -> {
                put("state", item.state.name)
                put("requestedAt", item.requestedAt.toString())
                putOptional("terminalAt", item.terminalAt?.toString())
            }
            is AgentResourceListingItem.CatalogIngestion -> {
                put("state", item.state.name)
                put("requestedAt", item.requestedAt.toString())
                putOptional("terminalAt", item.terminalAt?.toString())
            }
            is AgentResourceListingItem.CatalogFailure -> {
            }
            is AgentResourceListingItem.ExploreRun -> {
                put("runKind", item.runKind.name)
                put("connectorId", item.connectorId.toString())
                put("state", item.state.name)
                put("createdAt", item.createdAt.toString())
                put("updatedAt", item.updatedAt.toString())
            }
            is AgentResourceListingItem.DashboardScheduleOccurrence -> put("body", mapOf(
                "scheduleKey" to item.body.scheduleKey,
                "scheduledAt" to item.body.scheduledAt.toString(),
                "decidedAt" to item.body.decidedAt.toString(),
                "outcome" to item.body.outcome,
                "reason" to item.body.reason,
                "sessionId" to item.body.sessionId?.toString(),
                "resultState" to item.body.resultState,
            ))
            is AgentResourceListingItem.Dashboard -> {
                putOptional("title", item.title)
                putOptional("description", item.description)
                put("updatedAt", item.updatedAt.toString())
            }
            is AgentResourceListingItem.DashboardSession -> {
                put("parameterBindings", item.parameterBindings.mapValues { (_, value) -> jsonElementValue(value) })
                put("runningQueryRuns", item.runningQueryRuns.map { run -> mapOf("queryName" to run.queryName, "path" to run.path.value, "state" to run.state) })
                put("createdAt", item.createdAt.toString())
                put("updatedAt", item.updatedAt.toString())
                put("lastQueryRunAt", item.lastQueryRunAt?.toString())
            }
            is AgentResourceListingItem.DashboardQueryRun -> {
                put("queryName", item.queryName)
                put("queryType", item.queryType)
                put("connectorId", item.connectorId.toString())
                put("state", item.state.name)
                put("createdAt", item.createdAt.toString())
                put("updatedAt", item.updatedAt.toString())
            }
        }
    }

    fun availableOperationJson(operation: AgentAvailableOperation): Map<String, Any?> = linkedMapOf<String, Any?>(
        "operation" to operation.operation.name,
        "path" to operation.path.value,
    ).apply {
        when (operation) {
            is AgentAvailableOperation.List, is AgentAvailableOperation.Read, is AgentAvailableOperation.Edit -> Unit
            is AgentAvailableOperation.Create -> put("inputKind", operation.inputKind)
            is AgentAvailableOperation.Invoke -> {
                put("action", operation.action)
                putOptional("inputKind", operation.inputKind)
            }
        }
    }

    fun agentActionJson(requestId: AgentRequestId, action: AgentActionResult, workspaceId: WorkspaceId?, webOrigin: URI): Map<String, Any?> = linkedMapOf<String, Any?>(
        "requestId" to requestId.value,
        "operation" to action.operation.name,
        "path" to action.path.value,
        "status" to action.status.name,
        "result" to mapOf("kind" to action.result.kind.name, "path" to action.result.path.value),
    ).apply {
        putOptional("webUrl", dashboardActionWebUrl(action, workspaceId, webOrigin))
        if (action is AgentActionResult.PageUploadPrepared) {
            put("upload", pageUploadJson(action.upload))
            put("partSizeBytes", action.partSizeBytes)
            put("parts", action.parts.map { part -> mapOf(
                "partNumber" to part.partNumber, "contentLength" to part.contentLength,
                "url" to part.url, "requiredHeaders" to part.requiredHeaders,
            ) })
        }
        if (action is AgentActionResult.PageUploadCompleted) {
            put("action", action.action)
            put("upload", pageUploadJson(action.upload))
        }
        if (action is AgentActionResult.Invoke) put("action", action.action)
        if (action is AgentActionResult.SemanticCheck) {
            put("action", action.action)
            put("check", jsonElementValue(action.check))
        }
        if (action is AgentActionResult.DashboardSessionResolution) {
            put("action", action.action)
            put("variables", action.variables.mapValues { (_, value) -> jsonElementValue(value) })
            put("queryRuns", action.queryRuns.mapValues { (_, value) -> value.value })
        }
    }

    private fun dashboardActionWebUrl(action: AgentActionResult, workspaceId: WorkspaceId?, webOrigin: URI): String? {
        val builder = when {
            action is AgentActionResult.Create && action.result.kind == AgentResourceKind.DASHBOARD -> true
            action is AgentActionResult.Edit && action.result.kind == AgentResourceKind.DASHBOARD_DRAFT -> true
            action is AgentActionResult.Invoke && action.action == "apply" && action.result.kind == AgentResourceKind.DASHBOARD -> false
            else -> return null
        }
        val dashboardId = action.result.path.value.split('/')[2]
        val path = "/workspaces/${requireNotNull(workspaceId).value}/dashboards/$dashboardId" + if (builder) "/builder" else ""
        return webOrigin.resolve(path).toString()
    }

    private fun pageUploadJson(upload: cc.monomer.agent.api.AgentPageUploadMetadata): Map<String, Any?> = mapOf(
        "uploadRef" to upload.uploadRef.toString(), "format" to upload.format.name,
        "byteSize" to upload.byteSize, "sha256" to upload.sha256, "expiresAt" to upload.expiresAt.toString(),
    )

    private fun pageReferenceJson(reference: cc.monomer.agent.api.AgentActionResultReference?): Map<String, String>? =
        reference?.let { mapOf("kind" to it.kind.name, "path" to it.path.value) }

    private fun pagePlacementJson(placement: cc.monomer.agent.api.AgentPagePlacement): Map<String, Any?> = mapOf(
        "pageId" to placement.pageId.toString(), "children" to placement.children.map(::pagePlacementJson),
    )

    private fun agentResourceBodyJson(body: AgentResourceBody): Map<String, Any?> = when (body) {
        is cc.monomer.agent.api.AgentDashboardDefinitionVersionBody -> mapOf(
            "workspaceId" to body.workspaceId.toString(), "definitionId" to body.definitionId.toString(),
            "name" to body.name, "deleted" to body.deleted, "changeId" to body.changeId.toString(),
            "action" to body.action, "definition" to jsonElementValue(body.definition), "occurredAt" to body.occurredAt.toString(),
            "actor" to body.actor?.let(::actorJson), "credentialSurface" to body.credentialSurface?.name,
            "agentApp" to body.agentApp?.let(::agentAppJson),
            "restorable" to body.restorable,
        )
        is cc.monomer.agent.api.AgentSemanticDefinitionVersionBody -> mapOf(
            "workspaceId" to body.workspaceId.toString(), "definitionId" to body.definitionId.toString(),
            "name" to body.name, "deleted" to body.deleted, "changeId" to body.changeId.toString(),
            "action" to body.action, "definition" to jsonElementValue(body.definition), "occurredAt" to body.occurredAt.toString(),
            "actor" to body.actor?.let(::actorJson), "credentialSurface" to body.credentialSurface?.name,
            "agentApp" to body.agentApp?.let(::agentAppJson),
            "restorable" to body.restorable,
        )
        is cc.monomer.agent.api.AgentOrganizationBody -> AgentManagementJson.json(body)
        is cc.monomer.agent.api.AgentOrganizationMemberBody -> AgentManagementJson.json(body)
        is cc.monomer.agent.api.AgentOrganizationInvitationBody -> AgentManagementJson.json(body)
        is cc.monomer.agent.api.AgentWorkspaceAccessBody -> AgentManagementJson.json(body)
        is cc.monomer.agent.api.AgentWorkspaceAuthorizationBody -> AgentManagementJson.json(body)
        is cc.monomer.agent.api.AgentLineageResourceBody -> AgentManagementJson.json(body)
        is cc.monomer.agent.api.AgentWorkspaceMemberBody -> AgentManagementJson.json(body)
        is cc.monomer.agent.api.AgentPageContentBody -> mapOf(
            "title" to body.title, "format" to body.format.name, "source" to body.source,
        )
        is cc.monomer.agent.api.AgentPageSessionBody -> mapOf(
            "title" to body.title, "display" to jsonElementValue(body.display),
            "parameterBindings" to body.parameterBindings.mapValues { jsonElementValue(it.value) },
            "initialVariables" to body.initialVariables.mapValues { jsonElementValue(it.value) },
            "createdAt" to body.createdAt.toString(), "updatedAt" to body.updatedAt.toString(),
        )
        is cc.monomer.agent.api.AgentPageRunBody -> mapOf(
            "queryName" to body.queryName, "queryType" to body.queryType, "state" to body.state, "resultAvailable" to body.resultAvailable,
            "failureCode" to body.failureCode, "outputColumnNames" to body.outputColumnNames,
            "createdAt" to body.createdAt.toString(), "updatedAt" to body.updatedAt.toString(),
        )
        is cc.monomer.agent.api.AgentPageResourceBody -> buildMap {
            put("pageKind", body.pageKind.name)
            put("title", body.title)
            put("description", body.description)
            put("displayTitle", body.displayTitle)
            put("dashboard", pageReferenceJson(body.dashboard))
            put("latestVersion", pageReferenceJson(body.latestVersion))
            put("documentFormat", body.documentFormat?.name)
            body.createdAt?.let { put("createdAt", it.toString()) }
            body.updatedAt?.let { put("updatedAt", it.toString()) }
            body.createdBy?.let { put("createdBy", pageActorJson(it)) }
            body.updatedBy?.let { put("updatedBy", pageActorJson(it)) }
        }
        is cc.monomer.agent.api.AgentPagesTreeResourceBody -> mapOf(
            "pages" to body.pages.map(::listingItemJson), "placements" to body.placements.map(::pagePlacementJson),
        )
        is cc.monomer.agent.api.AgentPageVersionResourceBody -> mapOf(
            "format" to body.format.name, "byteSize" to body.byteSize, "sha256" to body.sha256,
            "finalizedAt" to body.finalizedAt.toString(), "finalizedBy" to pageActorJson(body.finalizedBy),
        )
        is cc.monomer.agent.api.AgentWorkspaceRootBody -> mapOf("role" to body.role.name, "resources" to body.resources.map(::availableOperationJson))
        is cc.monomer.agent.api.AgentConnectorResourceBody -> mapOf(
            "connectorType" to body.connectorType, "displayName" to body.displayName,
            "properties" to jsonElementValue(body.properties),
            "sample" to body.sample?.let { mapOf("dataset" to it.dataset, "label" to it.label) },
        )
        is cc.monomer.agent.api.AgentCatalogResourceBody -> catalogResourceJson(body)
        is cc.monomer.agent.api.AgentConnectorCatalogSelectionResourceBody -> mapOf(
            "selectedCatalogNames" to body.selectedCatalogNames,
        )
        is cc.monomer.agent.api.AgentCatalogSelectionResourceBody -> mapOf(
            "selectedSchemas" to body.selectedSchemas.map { selected ->
                mapOf(
                    "schemaName" to selected.schemaName,
                    "relations" to when (val relations = selected.relations) {
                        cc.monomer.agent.api.AgentCatalogRelationSelection.AllRelations -> mapOf("kind" to "ALL_RELATIONS")
                        is cc.monomer.agent.api.AgentCatalogRelationSelection.SelectedRelations -> mapOf(
                            "kind" to "SELECTED_RELATIONS",
                            "relationNames" to relations.relationNames,
                        )
                    },
                )
            },
        )
        is cc.monomer.agent.api.AgentCatalogOptionResourceBody -> linkedMapOf<String, Any?>(
            "optionKind" to body.optionKind,
        ).apply {
            putOptional("available", body.available)
            putOptional("relationKind", body.relationKind)
        }
        is cc.monomer.agent.api.AgentCatalogJobResourceBody -> linkedMapOf<String, Any?>(
            "state" to body.state,
            "requestedAt" to body.requestedAt.toString(),
        ).apply {
            putOptional("terminalAt", body.terminalAt?.toString())
            putOptional("failureCode", body.failureCode)
            putOptional("message", body.message)
            putOptional("refreshedRelationCount", body.refreshedRelationCount)
            putOptional("runningTaskCount", body.runningTaskCount)
            putOptional("errorCount", body.errorCount)
            putOptional("runningTargets", body.runningTargets?.map { mapOf("kind" to it.kind.name, "path" to it.path.value) })
            putOptional("requestedBy", body.requestedBy?.let { requester ->
                linkedMapOf<String, Any?>("principalId" to requester.principalId, "displayName" to requester.displayName).apply {
                    putOptional("credentialSurface", requester.credentialSurface?.name)
                    putOptional("agentApp", requester.agentApp?.let(::agentAppJson))
                }
            })
        }
        is cc.monomer.agent.api.AgentCatalogIngestionFailureResourceBody -> mapOf(
            "target" to mapOf("kind" to body.target.kind.name, "path" to body.target.path.value),
            "failureCode" to body.failureCode,
            "message" to body.message,
        )
        is cc.monomer.agent.api.AgentSemanticModelResourceBody -> AgentSemanticDefinitionJson.model(body.definition) +
            mapOf("sourcePartitionInfo" to body.sourcePartitionInfo.map { it.toHttpValue() })
        is cc.monomer.agent.api.AgentMetricResourceBody -> AgentSemanticDefinitionJson.metric(body.definition) +
            mapOf("sourcePartitionInfo" to body.sourcePartitionInfo.map { it.toHttpValue() })
        is cc.monomer.agent.api.AgentExploreRunResourceBody -> linkedMapOf<String, Any?>(
            "runKind" to body.runKind,
            "sql" to body.sql,
            "connectorId" to body.connectorId.toString(),
            "state" to body.state,
            "createdAt" to body.createdAt.toString(),
            "updatedAt" to body.updatedAt.toString(),
        ).apply {
            if (body is cc.monomer.agent.api.AgentExploreRunResourceBody.Semantic) {
                put("query", cc.monomer.agent.application.AgentSemanticQueryJson.query(body.query))
            }
            putOptional("failureCode", body.failureCode)
            putOptional("failureDetail", body.failureDetail)
            putOptional("credentialSurface", body.credentialSurface?.name)
            putOptional("agentApp", body.agentApp?.let(::agentAppJson))
        }
        is cc.monomer.agent.api.AgentExploreResultResourceBody -> linkedMapOf<String, Any?>(
            "columns" to body.columns.map { column ->
                mapOf("name" to column.name, "logicalType" to column.logicalType, "display" to column.display?.let(AgentSemanticDefinitionJson::display))
            },
            "rows" to body.rows.map { row -> row.map(::jsonElementValue) },
            "totalRowCount" to body.totalRowCount,
            "returnedRowCount" to body.returnedRowCount,
            "truncated" to body.truncated,
            "expiresAt" to body.expiresAt.toString(),
            "reportingTimeZone" to body.reportingTimeZone?.id,
        ).apply {
            body.download?.let { link -> put("download", mapOf(
                "url" to link.url, "fileName" to link.fileName, "mediaType" to link.mediaType,
                "byteSize" to link.byteSize, "expiresAt" to link.expiresAt.toString(),
            )) }
        }
        is cc.monomer.agent.api.AgentDashboardSchedulesBody -> body.observation.mapValues { jsonElementValue(it.value) }
        is cc.monomer.agent.api.AgentDashboardScheduleOccurrenceBody -> body.observation.mapValues { jsonElementValue(it.value) }
        is cc.monomer.agent.api.AgentDashboardResourceBody -> linkedMapOf<String, Any?>(
            "definition" to jsonElementValue(body.definition),
            "createdAt" to body.createdAt.toString(),
            "updatedAt" to body.updatedAt.toString(),
        ).apply {
            putOptional("updatedBy", body.updatedBy?.let(::actorJson))
            putOptional("credentialSurface", body.credentialSurface?.name)
            putOptional("agentApp", body.agentApp?.let(::agentAppJson))
        }
        is cc.monomer.agent.api.AgentDashboardDraftResourceBody -> linkedMapOf<String, Any?>(
            "definition" to jsonElementValue(body.definition),
            "parameterBindings" to body.parameterBindings.mapValues { (_, value) -> jsonElementValue(value) },
            "variables" to body.variables.mapValues { (_, value) -> jsonElementValue(value) },
            "targetQueryRuns" to body.targetQueryRuns.mapValues { (_, value) -> value.value },
            "readyResults" to body.readyResults.mapValues { (_, ready) -> readyResultJson(ready) },
            "createdAt" to body.createdAt.toString(),
            "updatedAt" to body.updatedAt.toString(),
        ).apply {
            putOptional("lastEditedBy", body.lastEditedBy?.let(::actorJson))
            putOptional("lastEditedAt", body.lastEditedAt?.toString())
            putOptional("lastEditSurface", body.lastEditSurface?.name)
            putOptional("lastEditAgentApp", body.lastEditAgentApp?.let(::agentAppJson))
        }
        is cc.monomer.agent.api.AgentDashboardDraftStatusBody -> linkedMapOf<String, Any?>(
            "draftRevision" to body.draftRevision.toString(),
            "targetQueryRuns" to body.targetQueryRuns.mapValues { (_, target) -> mapOf("path" to target.path.value, "state" to target.state.name) },
            "readyResults" to body.readyResults.mapValues { (_, ready) -> readyResultJson(ready) },
        )
        is cc.monomer.agent.api.AgentDashboardQueryRunBody -> linkedMapOf<String, Any?>(
            "queryName" to body.queryName,
            "queryType" to body.queryType,
            "connectorId" to body.connectorId.toString(),
            "state" to body.state,
            "outputColumnNames" to body.outputColumnNames,
            "createdAt" to body.createdAt.toString(),
            "updatedAt" to body.updatedAt.toString(),
        ).apply {
            putOptional("failureCode", body.failureCode)
            putOptional("failureDetail", body.failureDetail)
            putOptional("credentialSurface", body.credentialSurface?.name)
            putOptional("agentApp", body.agentApp?.let(::agentAppJson))
        }
        is cc.monomer.agent.api.AgentDashboardSessionResourceBody -> mapOf(
            "definition" to jsonElementValue(body.definition),
            "parameterBindings" to body.parameterBindings.mapValues { (_, value) -> jsonElementValue(value) },
            "initialVariables" to body.initialVariables.mapValues { (_, value) -> jsonElementValue(value) },
            "runningQueryRuns" to body.runningQueryRuns.map { run -> mapOf("queryName" to run.queryName, "path" to run.path.value, "state" to run.state) },
        )
        is cc.monomer.agent.api.AgentDashboardResultResourceBody -> mapOf(
            "queryType" to body.queryType,
            "columns" to body.columns.map { column -> mapOf("name" to column.name, "logicalType" to column.logicalType, "nullable" to column.nullable, "display" to column.display?.let(AgentSemanticDefinitionJson::display)) },
            "rows" to body.rows.map { row -> row.map(::jsonElementValue) },
            "totalRowCount" to body.totalRowCount,
            "returnedRowCount" to body.returnedRowCount,
            "truncated" to body.truncated,
            "reportingTimeZone" to body.reportingTimeZone?.id,
        )
    }

    private fun readyResultJson(ready: cc.monomer.agent.api.AgentDashboardReadyResult): Map<String, Any?> = mapOf(
        "authoringRevision" to ready.authoringRevision.toString(),
        "parameterBindings" to ready.parameterBindings.mapValues { (_, value) -> jsonElementValue(value) },
        "variables" to ready.variables.mapValues { (_, value) -> jsonElementValue(value) },
        "queryRuns" to ready.queryRuns.mapValues { (_, value) -> value.value },
    )

    private fun catalogResourceJson(body: cc.monomer.agent.api.AgentCatalogResourceBody): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "connectorId" to body.connectorId.toString(),
            "schemaName" to body.schemaName,
            "relationName" to body.relationName,
            "relationKind" to body.relationKind,
            "partitionKeys" to body.partitionKeys,
            "clusteringKeys" to body.clusteringKeys,
            "requiresPartitionFilter" to body.requiresPartitionFilter,
            "columns" to body.columns.map { column ->
                linkedMapOf<String, Any?>(
                    "name" to column.name,
                    "nativeType" to column.nativeType,
                    "ordinalPosition" to column.ordinalPosition,
                    "nullable" to column.nullable,
                ).apply {
                    putOptional("defaultValue", column.defaultValue)
                }
            },
            "foreignKeys" to body.foreignKeys.map { foreignKey ->
                linkedMapOf<String, Any?>(
                    "columns" to foreignKey.columns,
                    "referencedConnectorId" to foreignKey.referencedConnectorId.toString(),
                    "referencedSchemaName" to foreignKey.referencedSchemaName,
                    "referencedRelationName" to foreignKey.referencedRelationName,
                    "referencedColumns" to foreignKey.referencedColumns,
                ).apply {
                    putOptional("name", foreignKey.name)
                    putOptional("referencedCatalogName", foreignKey.referencedCatalogName)
                }
            },
        ).apply {
            putOptional("catalogName", body.catalogName)
            putOptional("viewDefinition", body.viewDefinition)
            putOptional("primaryKey", body.primaryKey?.let { primaryKey ->
                linkedMapOf<String, Any?>("columns" to primaryKey.columns).apply { putOptional("name", primaryKey.name) }
            })
        }

    private fun jsonElementValue(value: kotlinx.serialization.json.JsonElement): Any? = objectMapper.readValue(value.toString(), Any::class.java)

    private fun MutableMap<String, Any?>.putOptional(key: String, value: Any?) { if (value != null) put(key, value) }

    private val objectMapper = ObjectMapper()
}
