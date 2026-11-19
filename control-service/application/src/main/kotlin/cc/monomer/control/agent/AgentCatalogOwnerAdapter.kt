@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActionResultReference
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentAvailableOperation
import cc.monomer.agent.api.AgentCatalogColumn
import cc.monomer.agent.api.AgentCatalogForeignKey
import cc.monomer.agent.api.AgentCatalogIngestionFailureResourceBody
import cc.monomer.agent.api.AgentCatalogJobResourceBody
import cc.monomer.agent.api.AgentCatalogOptionResourceBody
import cc.monomer.agent.api.AgentCatalogPrimaryKey
import cc.monomer.agent.api.AgentCatalogRelationSelection
import cc.monomer.agent.api.AgentCatalogResourceBody
import cc.monomer.agent.api.AgentCatalogSelectedSchema
import cc.monomer.agent.api.AgentCatalogSelectionResourceBody
import cc.monomer.agent.api.AgentCatalogDiscoveryPort
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentJsonPatchOp
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentProblemViolation
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentResourcePath.Companion.decodeNameSegment
import cc.monomer.agent.api.AgentResourcePath.Companion.encodeNameSegment
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.ConnectorCatalogSelection
import cc.monomer.catalog.api.SaveCatalogSelectionCommand
import cc.monomer.catalog.api.SaveConnectorCatalogSelectionCommand
import cc.monomer.catalog.api.StartCatalogIngestCommand
import cc.monomer.catalog.api.hasCatalogLevel
import cc.monomer.catalog.domain.CatalogIngestJobState
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogListOption
import cc.monomer.catalog.domain.CatalogListRefreshJob
import cc.monomer.catalog.domain.CatalogListRefreshJobState
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.control.AdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ExecutionAdmissionRequest
import cc.monomer.control.MutationAdmissionRequest
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.catalog.application.CatalogBrowseUseCase
import cc.monomer.catalog.application.CatalogNotFoundException
import cc.monomer.catalog.application.RelationDetail
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationPort
import cc.monomer.catalog.application.ingest.CatalogIngestCancelCommand
import cc.monomer.catalog.application.ingest.CatalogIngestFailureCursor
import cc.monomer.catalog.application.ingest.CatalogIngestHistoryCursor
import cc.monomer.catalog.application.ingest.CatalogIngestJobView
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListRefreshParent
import cc.monomer.catalog.application.ingest.CatalogListRefreshJobView
import cc.monomer.catalog.application.ingest.CatalogListRefreshHistoryCursor
import cc.monomer.catalog.application.ingest.CancelCatalogListRefreshCommand
import cc.monomer.catalog.application.ingest.StartCatalogListRefreshCommand
import cc.monomer.control.catalog.toCatalogConflictDetails
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.control.connector.application.ConnectorNotFoundException
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.ExecutionIntent
import cc.monomer.identity.api.MutationIntent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Agent projection of the existing Catalog owner. It does not own Catalog state. */
class AgentCatalogOwnerAdapter(
    private val catalogBrowse: CatalogBrowseUseCase,
    private val catalogIngest: CatalogIngestApplicationPort,
    private val catalogListRefresh: CatalogListRefreshApplicationPort,
    private val connectorRead: ConnectorReadUseCase,
    private val controlAdmissionFacade: ControlAdmissionFacade,
    private val provenanceNames: AgentProvenanceNames,
) : AgentCatalogDiscoveryPort {
    override suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome {
        val path = parsePath(request.path) ?: return AgentListingOutcome.NotFound
        val cursor = try {
            request.cursor?.let { decodeCursor(request.path, it) }
        } catch (_: IllegalArgumentException) {
            return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
        }
        if (!isExplorer(caller)) return AgentListingOutcome.Forbidden
        return agentOwnerGuard("AgentCatalogOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentListingOutcome::Rejected) {
            val connectorType = connectorType(caller, path.connectorId) ?: return AgentListingOutcome.NotFound
            val route = listRoute(path, connectorType)
            if (cursor != null && route.cursorKind != cursor.kind) {
                return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
            }
            when (route) {
                is ListRoute.Catalogs -> listCatalogs(caller, request, route, cursor)
                is ListRoute.Schemas -> listSchemas(caller, request, route, cursor)
                is ListRoute.Relations -> listRelations(caller, request, route, cursor)
                is ListRoute.Options -> listOptions(caller, request, route, cursor)
                is ListRoute.Refreshes -> listRefreshes(caller, request, route, cursor)
                is ListRoute.Ingestions -> listIngestions(caller, request, route, cursor)
                is ListRoute.Failures -> listFailures(caller, request, route, cursor)
                ListRoute.Unsupported -> AgentListingOutcome.NotFound
            }
        }
    }

    override suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome {
        val path = parsePath(request.path) ?: return AgentResourceOutcome.NotFound
        if (!isExplorer(caller)) return AgentResourceOutcome.Forbidden
        return agentOwnerGuard("AgentCatalogOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentResourceOutcome::Rejected) {
            val connectorType = connectorType(caller, path.connectorId) ?: return AgentResourceOutcome.NotFound
            when (val route = readRoute(path, connectorType)) {
                ReadRoute.Root -> rootResource(caller, request.path, connectorType)
                is ReadRoute.CatalogSelection -> connectorCatalogSelection(caller, request.path, route)
                is ReadRoute.Selection -> selectionResource(caller, request.path, route)
                is ReadRoute.Catalog -> catalogResource(caller, request.path, route)
                is ReadRoute.Schema -> schemaResource(caller, request.path, route)
                is ReadRoute.Relation -> relationResource(caller, request.path, route)
                is ReadRoute.Option -> optionResource(caller, request.path, route)
                is ReadRoute.Refresh -> refreshResource(caller, request.path, route)
                is ReadRoute.Ingestion -> ingestionResource(caller, request.path, route)
                is ReadRoute.Failure -> failureResource(caller, request.path, route)
                ReadRoute.Unsupported -> AgentResourceOutcome.NotFound
            }
        }
    }

    override suspend fun edit(
        caller: AgentWorkspaceCaller,
        request: AgentEditRequest,
    ): AgentActionOutcome {
        val path = parsePath(request.path) ?: return AgentActionOutcome.NotFound
        if (request.revision != null) return rejected("REVISION_NOT_SUPPORTED", "Catalog selection edits do not accept a revision.")
        if (request.patch.size != 1 || request.patch.single().op != AgentJsonPatchOp.REPLACE || request.patch.single().path != "") {
            return rejected(
                "PATCH_INVALID",
                "Catalog selection edits accept exactly one root replace operation.",
                violation = AgentProblemViolation(
                    field = "patch",
                    code = "PATCH_OPERATION_INVALID",
                    message = "The Catalog selection document supports one root replace only.",
                    operationIndex = request.patch.singleOrNull()?.let { 0 },
                    path = request.patch.singleOrNull()?.let { cc.monomer.agent.api.AgentJsonPatch.safeDiagnosticPath(it.path) },
                ),
            )
        }
        val connectorType = agentOwnerGuard("AgentCatalogOwnerAdapter", AgentOwnerProblems.ownerUnavailable, { return AgentActionOutcome.Rejected(it) }) {
            connectorType(caller, path.connectorId)
        } ?: return AgentActionOutcome.NotFound
        val route = editRoute(path, connectorType) ?: return AgentActionOutcome.NotFound
        val authorization = authorizeMutation(caller) ?: return AgentActionOutcome.Forbidden
        return agentOwnerGuard("AgentCatalogOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentActionOutcome::Rejected) {
            when (route) {
                EditRoute.ConnectorCatalogSelection -> {
                    val selection = try { decodeConnectorCatalogSelection(request.patch.single().value!!) }
                        catch (_: IllegalArgumentException) { return rejected("PATCH_INVALID", "The Catalog selection replacement is invalid.") }
                    when (
                        val result = catalogIngest.saveConnectorCatalogSelection(
                            SaveConnectorCatalogSelectionCommand(authorization, path.connectorId, selection),
                        )
                    ) {
                        is CatalogCommandResult.Accepted -> completed(
                            request.path,
                            null,
                            AgentResourceKind.CONNECTOR_CATALOG_SELECTION,
                            request.path,
                        )
                        else -> result.toActionOutcome()
                    }
                }
                is EditRoute.Selection -> {
                    val selection = try { decodeSelection(request.patch.single().value!!) }
                        catch (_: IllegalArgumentException) { return rejected("PATCH_INVALID", "The Catalog selection replacement is invalid.") }
                    when (
                        val result = catalogIngest.saveSelection(
                            SaveCatalogSelectionCommand(authorization, path.connectorId, route.entry, selection),
                        )
                    ) {
                        is CatalogCommandResult.Accepted -> completed(
                            request.path,
                            null,
                            AgentResourceKind.CATALOG_SELECTION,
                            request.path,
                        )
                        else -> result.toActionOutcome()
                    }
                }
            }
        }
    }

    override suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome {
        val path = parsePath(request.path) ?: return AgentActionOutcome.NotFound
        val connectorType = agentOwnerGuard("AgentCatalogOwnerAdapter", AgentOwnerProblems.ownerUnavailable, { return AgentActionOutcome.Rejected(it) }) {
            connectorType(caller, path.connectorId)
        } ?: return AgentActionOutcome.NotFound
        return agentOwnerGuard("AgentCatalogOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentActionOutcome::Rejected) {
            when (val route = invokeRoute(path, connectorType, request.action)) {
                is InvokeRoute.StartRefresh -> startRefresh(caller, request, route)
                is InvokeRoute.CancelRefresh -> cancelRefresh(caller, request, route)
                is InvokeRoute.StartIngestion -> startIngestion(caller, request, route)
                is InvokeRoute.CancelIngestion -> cancelIngestion(caller, request, route)
                InvokeRoute.Unsupported -> AgentActionOutcome.NotFound
            }
        }
    }

    private suspend fun listCatalogs(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        route: ListRoute.Catalogs,
        cursor: Cursor?,
    ): AgentListingOutcome {
        val selection = catalogIngest.currentConnectorCatalogSelection(caller.workspaceId, route.connectorId)
            ?: return AgentListingOutcome.NotFound
        val names = selection.selection.selectedCatalogNames
            .asSequence()
            .sorted()
            .filter { cursor?.name?.let { afterName -> it > afterName } ?: true }
            .take(request.limit + 1)
            .toList()
        return listing(
            items = names.take(request.limit).map { name ->
                AgentResourceListingItem.Catalog(path = catalogPath(route.connectorId, name), name = name)
            },
            source = names,
            limit = request.limit,
            path = request.path,
            cursor = cursor,
            keyOf = { CursorKey(name = it) },
            availableOperations = emptyList(),
        )
    }

    private suspend fun listSchemas(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        route: ListRoute.Schemas,
        cursor: Cursor?,
    ): AgentListingOutcome {
        if (route.catalogName != null && !isSelectedCatalog(caller, route.connectorId, route.catalogName)) {
            return AgentListingOutcome.NotFound
        }
        val all = catalogBrowse.listSchemasAfter(
            caller.workspaceId.toString(),
            route.connectorId.toString(),
            route.catalogName,
            cursor?.name,
            request.limit + 1,
        )
        return listing(
            items = all.take(request.limit).map { summary ->
                AgentResourceListingItem.Schema(path = schemaPath(route.connectorId, route.catalogName, summary.name), name = summary.name, updatedAt = summary.lastRefreshedAtEpochMs?.let(Instant::ofEpochMilli))
            },
            source = all,
            limit = request.limit,
            path = request.path,
            cursor = cursor,
            keyOf = { CursorKey(name = it.name) },
            availableOperations = emptyList(),
        )
    }

    private suspend fun listRelations(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        route: ListRoute.Relations,
        cursor: Cursor?,
    ): AgentListingOutcome {
        if (route.catalogName != null && !isSelectedCatalog(caller, route.connectorId, route.catalogName)) {
            return AgentListingOutcome.NotFound
        }
        val all = catalogBrowse.listRelationsAfter(
            caller.workspaceId.toString(),
            route.connectorId.toString(),
            route.catalogName,
            route.schemaName,
            cursor?.name,
            request.limit + 1,
        )
        return listing(
            items = all.take(request.limit).map { relation ->
                AgentResourceListingItem.Relation(path = relationPath(route.connectorId, route.catalogName, route.schemaName, relation.name), name = relation.name, relationKind = cc.monomer.agent.api.AgentRelationKind.valueOf(relation.kind), updatedAt = relation.lastRefreshedAtEpochMs?.let(Instant::ofEpochMilli))
            },
            source = all,
            limit = request.limit,
            path = request.path,
            cursor = cursor,
            keyOf = { CursorKey(name = it.name) },
            availableOperations = emptyList(),
        )
    }

    private suspend fun listOptions(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        route: ListRoute.Options,
        cursor: Cursor?,
    ): AgentListingOutcome {
        if (route.catalogName != null && !isSelectedCatalog(caller, route.connectorId, route.catalogName)) {
            return AgentListingOutcome.NotFound
        }
        val page = catalogListRefresh.findListOptionsAfter(
            caller.workspaceId,
            route.connectorId,
            route.parent,
            cursor?.name,
            request.limit + 1,
        )
            ?: return AgentListingOutcome.NotFound
        if (page.page.fetchedAt == null) {
            return AgentListingOutcome.Rejected(
                optionsNotFetchedProblem(request.path, isCatalogExecutionAuthorized(caller)),
            )
        }
        val all = page.page.options
        return listing(
            items = all.take(request.limit).map { option ->
                optionListingItem(route, option)
            },
            source = all,
            limit = request.limit,
            path = request.path,
            cursor = cursor,
            keyOf = { CursorKey(name = it.name) },
            availableOperations = buildList {
                if (isCatalogExecutionAuthorized(caller)) {
                    add(AgentAvailableOperation.Invoke(path = request.path, action = "refresh", inputKind = null))
                }
                add(
                    AgentAvailableOperation.List(path = refreshCollectionPath(route.connectorId, route.parent, route.catalogCandidate)),
                )
            },
        )
    }

    private suspend fun listRefreshes(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        route: ListRoute.Refreshes,
        cursor: Cursor?,
    ): AgentListingOutcome {
        val jobs = catalogListRefresh.findListRefreshJobsAfter(
            caller.workspaceId,
            route.connectorId,
            route.parent,
            cursor?.let {
                CatalogListRefreshHistoryCursor(
                    requestedAt = it.time ?: error("refresh cursor has no timestamp"),
                    jobId = cc.monomer.catalog.api.CatalogListRefreshJobId(it.id),
                )
            },
            request.limit + 1,
        ) ?: return AgentListingOutcome.NotFound
        return listing(
            items = jobs.take(request.limit).map { job -> refreshListingItem(route, job.job) },
            source = jobs,
            limit = request.limit,
            path = request.path,
            keyOf = { CursorKey(time = it.job.requestedAt, id = it.job.id.value) },
            availableOperations = emptyList(),
        )
    }

    private suspend fun listIngestions(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        route: ListRoute.Ingestions,
        cursor: Cursor?,
    ): AgentListingOutcome {
        val all = catalogIngest.findJobsAfter(
            caller.workspaceId,
            route.connectorId,
            route.entry,
            cursor?.let { CatalogIngestHistoryCursor(it.time ?: error("history cursor has no timestamp"), cc.monomer.catalog.api.CatalogIngestJobId(it.id)) },
            request.limit + 1,
        )
        val canIngest = hasIngestableSelection(caller, route.connectorId, route.entry) && isCatalogExecutionAuthorized(caller)
        return listing(
            items = all.take(request.limit).map { summary ->
                ingestionListingItem(route.entry, summary.job)
            },
            source = all,
            limit = request.limit,
            path = request.path,
            cursor = cursor,
            keyOf = { CursorKey(time = it.job.requestedAt, id = it.job.id.value) },
            availableOperations = if (canIngest) {
                listOf(AgentAvailableOperation.Invoke(path = entryPath(route.entry), action = "ingest", inputKind = null))
            } else {
                emptyList()
            },
        )
    }

    private suspend fun listFailures(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        route: ListRoute.Failures,
        cursor: Cursor?,
    ): AgentListingOutcome {
        catalogIngest.findJobForEntry(caller.workspaceId, route.connectorId, route.entry, route.jobId)
            ?: return AgentListingOutcome.NotFound
        val all = catalogIngest.findErrorsAfter(
            caller.workspaceId,
            route.connectorId,
            route.entry,
            route.jobId,
            cursor?.let { CatalogIngestFailureCursor(it.time ?: error("failure cursor has no timestamp"), cc.monomer.catalog.api.CatalogIngestTaskId(it.id)) },
            request.limit + 1,
        ) ?: return AgentListingOutcome.NotFound
        return listing(
            items = all.take(request.limit).map { task -> failureListingItem(route, task) },
            source = all,
            limit = request.limit,
            path = request.path,
            cursor = cursor,
            keyOf = { CursorKey(time = it.terminalAt ?: error("failed task has no terminal time"), id = it.id.value) },
            availableOperations = emptyList(),
        )
    }

    private suspend fun rootResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        connectorType: ConnectorProductType,
    ): AgentResourceOutcome = AgentResourceOutcome.Found(
        AgentResourceEnvelope.CatalogRoot(path = path, availableOperations = rootOperations(caller, path, connectorType)),
    )

    private suspend fun connectorCatalogSelection(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.CatalogSelection,
    ): AgentResourceOutcome {
        val view = catalogIngest.currentConnectorCatalogSelection(caller.workspaceId, route.connectorId)
            ?: return AgentResourceOutcome.NotFound
        val availableOperations = mutableListOf<AgentAvailableOperation>()
        if (isCatalogMutationAuthorized(caller)) availableOperations += AgentAvailableOperation.Edit(path = path)
        availableOperations += AgentAvailableOperation.List(path = catalogSelectionOptionPath(route.connectorId))
        if (isCatalogExecutionAuthorized(caller)) {
            availableOperations += AgentAvailableOperation.Invoke(path = catalogSelectionOptionPath(route.connectorId), action = "refresh", inputKind = null)
        }
        availableOperations += AgentAvailableOperation.List(path = catalogSelectionRefreshPath(route.connectorId))
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.ConnectorCatalogSelection(path = path, body = cc.monomer.agent.api.AgentConnectorCatalogSelectionResourceBody(
                    view.selection.selectedCatalogNames.sorted(),
                ), availableOperations = availableOperations),
        )
    }

    private suspend fun selectionResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.Selection,
    ): AgentResourceOutcome {
        val selection = catalogIngest.currentSelection(caller.workspaceId, route.connectorId, route.entry)
            ?: return AgentResourceOutcome.NotFound
        val availableOperations = mutableListOf<AgentAvailableOperation>()
        if (isCatalogMutationAuthorized(caller)) availableOperations += AgentAvailableOperation.Edit(path = path)
        availableOperations += AgentAvailableOperation.List(path = selectionOptionPath(route.entry))
        if (isCatalogExecutionAuthorized(caller)) {
            availableOperations += AgentAvailableOperation.Invoke(path = selectionOptionPath(route.entry), action = "refresh", inputKind = null)
        }
        availableOperations += AgentAvailableOperation.List(path = selectionRefreshPath(route.entry))
        availableOperations += AgentAvailableOperation.List(path = ingestionsPath(route.entry))
        if (
            selection.selection.selectedSchemas.isNotEmpty() &&
            isCatalogExecutionAuthorized(caller)
        ) {
            availableOperations += AgentAvailableOperation.Invoke(path = entryPath(route.entry), action = "ingest", inputKind = null)
        }
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.CatalogSelection(path = path, body = selection.selection.toAgentBody(), availableOperations = availableOperations),
        )
    }

    private suspend fun catalogResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.Catalog,
    ): AgentResourceOutcome {
        val selection = catalogIngest.currentConnectorCatalogSelection(caller.workspaceId, route.connectorId)
            ?: return AgentResourceOutcome.NotFound
        if (route.catalogName !in selection.selection.selectedCatalogNames) return AgentResourceOutcome.NotFound
        val operations = mutableListOf<AgentAvailableOperation>(
            AgentAvailableOperation.List(path = schemasPath(route.connectorId, route.catalogName)),
            AgentAvailableOperation.Read(path = selectionPath(route.entry)),
            AgentAvailableOperation.List(path = ingestionsPath(route.entry)),
        )
        if (hasIngestableSelection(caller, route.connectorId, route.entry) && isCatalogExecutionAuthorized(caller)) {
            operations += AgentAvailableOperation.Invoke(path = entryPath(route.entry), action = "ingest", inputKind = null)
        }
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.Catalog(path = path, availableOperations = operations),
        )
    }

    private suspend fun schemaResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.Schema,
    ): AgentResourceOutcome {
        if (route.catalogName != null && !isSelectedCatalog(caller, route.connectorId, route.catalogName)) {
            return AgentResourceOutcome.NotFound
        }
        val found = catalogBrowse.listSchemasAfter(
            caller.workspaceId.toString(),
            route.connectorId.toString(),
            route.catalogName,
            null,
            Int.MAX_VALUE,
        ).any { it.name == route.schemaName }
        if (!found) return AgentResourceOutcome.NotFound
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.Schema(path = path, availableOperations = listOf(AgentAvailableOperation.List(path = relationsPath(route.connectorId, route.catalogName, route.schemaName)))),
        )
    }

    private suspend fun relationResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.Relation,
    ): AgentResourceOutcome {
        if (route.catalogName != null && !isSelectedCatalog(caller, route.connectorId, route.catalogName)) {
            return AgentResourceOutcome.NotFound
        }
        val detail = try {
            catalogBrowse.getRelation(
                caller.workspaceId.toString(),
                route.connectorId.toString(),
                route.catalogName,
                route.schemaName,
                route.relationName,
            )
        } catch (_: CatalogNotFoundException) {
            return AgentResourceOutcome.NotFound
        }
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.Relation(path = path, body = detail.toAgentBody(route.connectorId), availableOperations = emptyList()),
        )
    }

    private suspend fun optionResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.Option,
    ): AgentResourceOutcome {
        if (route.catalogName != null && !isSelectedCatalog(caller, route.connectorId, route.catalogName)) {
            return AgentResourceOutcome.NotFound
        }
        val optionRead = catalogListRefresh.findListOption(
            caller.workspaceId,
            route.connectorId,
            route.parent,
            route.optionName,
        ) ?: return AgentResourceOutcome.NotFound
        val optionCollection = optionCollectionPath(route.connectorId, route.parent, route.optionKind == "CATALOG")
        if (optionRead.fetchedAt == null) {
            return AgentResourceOutcome.Rejected(
                optionsNotFetchedProblem(optionCollection, isCatalogExecutionAuthorized(caller)),
            )
        }
        val option = optionRead.option ?: return AgentResourceOutcome.NotFound
        val operations = if (route.optionKind == "SCHEMA") {
            buildList {
                add(AgentAvailableOperation.List(path = optionRelationsPath(route)))
                if (isCatalogExecutionAuthorized(caller)) {
                    add(AgentAvailableOperation.Invoke(path = optionRelationsPath(route), action = "refresh", inputKind = null))
                }
            }
        } else {
            emptyList()
        }
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.CatalogOption(path = path, body = AgentCatalogOptionResourceBody(route.optionKind, option.available.takeUnless { route.optionKind == "CATALOG" }, option.relationKind?.name), availableOperations = operations),
        )
    }

    private suspend fun refreshResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.Refresh,
    ): AgentResourceOutcome {
        val view = catalogListRefresh.findListRefreshJob(
            caller.workspaceId,
            route.connectorId,
            route.parent,
            route.refreshId,
        ) ?: return AgentResourceOutcome.NotFound
        val operations = mutableListOf<AgentAvailableOperation>(
            AgentAvailableOperation.List(path = optionCollectionPath(route.connectorId, route.parent, route.catalogCandidate)),
        )
        if (view.job.state == CatalogListRefreshJobState.RUNNING) {
            if (isCatalogExecutionAuthorized(caller)) operations += AgentAvailableOperation.Invoke(path = path, action = "cancel", inputKind = null)
        }
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.CatalogRefresh(path = path, body = view.toAgentBody(), availableOperations = operations),
        )
    }

    private suspend fun ingestionResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.Ingestion,
    ): AgentResourceOutcome {
        val view = catalogIngest.findJobForEntry(caller.workspaceId, route.connectorId, route.entry, route.jobId)
            ?: return AgentResourceOutcome.NotFound
        val operations = mutableListOf<AgentAvailableOperation>(
            AgentAvailableOperation.List(path = failuresPath(route.entry, route.jobId)),
        )
        if (view.job.state == CatalogIngestJobState.RUNNING) {
            if (isCatalogExecutionAuthorized(caller)) operations += AgentAvailableOperation.Invoke(path = path, action = "cancel", inputKind = null)
        }
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.CatalogIngestion(
                path = path,
                body = view.toAgentBody(route.entry, provenanceNames.read(listOf(view.job.requestedBy.toString()), listOf(view.job.agentClientId))),
                availableOperations = operations,
            ),
        )
    }

    private suspend fun failureResource(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        route: ReadRoute.Failure,
    ): AgentResourceOutcome {
        catalogIngest.findJobForEntry(caller.workspaceId, route.connectorId, route.entry, route.jobId)
            ?: return AgentResourceOutcome.NotFound
        val task = catalogIngest.findFailure(
            caller.workspaceId,
            route.connectorId,
            route.entry,
            route.jobId,
            route.failureId,
        )
            ?: return AgentResourceOutcome.NotFound
        val failureCode = task.failureCode ?: return AgentResourceOutcome.NotFound
        val message = task.safeMessage ?: return AgentResourceOutcome.NotFound
        return AgentResourceOutcome.Found(
            AgentResourceEnvelope.CatalogFailure(path = path, body = AgentCatalogIngestionFailureResourceBody(task.target.toReference(route.entry), failureCode, message), availableOperations = emptyList()),
        )
    }

    private suspend fun startRefresh(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        route: InvokeRoute.StartRefresh,
    ): AgentActionOutcome {
        if (!isRefreshParentSelected(caller, route.connectorId, route.parent)) return AgentActionOutcome.NotFound
        val authorization = authorizeExecution(caller) ?: return AgentActionOutcome.Forbidden
        val result = catalogListRefresh.startListRefresh(
            StartCatalogListRefreshCommand(authorization, route.connectorId, route.parent),
        )
        return when (result) {
            is CatalogCommandResult.Accepted -> AgentActionOutcome.Completed(
                AgentActionResult.Invoke(path = request.path, action = request.action, status = AgentActionStatus.ACCEPTED, result = AgentActionResultReference(
                        AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH,
                        refreshPath(route.connectorId, route.parent, route.catalogCandidate, result.value.job.id),
                    )),
            )
            else -> result.toActionOutcome()
        }
    }

    private suspend fun cancelRefresh(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        route: InvokeRoute.CancelRefresh,
    ): AgentActionOutcome {
        val authorization = authorizeExecution(caller) ?: return AgentActionOutcome.Forbidden
        catalogListRefresh.findListRefreshJob(
            caller.workspaceId,
            route.connectorId,
            route.parent,
            route.refreshId,
        ) ?: return AgentActionOutcome.NotFound
        val result = catalogListRefresh.cancelListRefresh(
            CancelCatalogListRefreshCommand(authorization, route.connectorId, route.refreshId, "USER_REQUESTED"),
        )
        return when (result) {
            is CatalogCommandResult.Accepted -> accepted(request, AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH, request.path)
            else -> result.toActionOutcome()
        }
    }

    private suspend fun startIngestion(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        route: InvokeRoute.StartIngestion,
    ): AgentActionOutcome {
        val authorization = authorizeExecution(caller) ?: return AgentActionOutcome.Forbidden
        return when (
            val result = catalogIngest.startIngest(
                StartCatalogIngestCommand(authorization, route.connectorId, route.entry),
            )
        ) {
            is CatalogCommandResult.Accepted -> accepted(
                request,
                AgentResourceKind.CATALOG_INGESTION,
                ingestionPath(route.entry, result.value.jobId),
            )
            else -> result.toActionOutcome()
        }
    }

    private suspend fun cancelIngestion(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        route: InvokeRoute.CancelIngestion,
    ): AgentActionOutcome {
        val authorization = authorizeExecution(caller) ?: return AgentActionOutcome.Forbidden
        catalogIngest.findJobForEntry(caller.workspaceId, route.connectorId, route.entry, route.jobId)
            ?: return AgentActionOutcome.NotFound
        return when (
            val result = catalogIngest.cancelIngest(
                CatalogIngestCancelCommand(authorization, route.connectorId, route.jobId, "USER_REQUESTED"),
            )
        ) {
            is CatalogCommandResult.Accepted -> accepted(request, AgentResourceKind.CATALOG_INGESTION, request.path)
            else -> result.toActionOutcome()
        }
    }

    private suspend fun connectorType(caller: AgentWorkspaceCaller, connectorId: ConnectorId): ConnectorProductType? = try {
        ConnectorProductType.fromStorageValue(
            connectorRead.get(caller.workspaceId.toString(), connectorId.toString()).type,
        )
    } catch (_: ConnectorNotFoundException) {
        null
    }

    private suspend fun isSelectedCatalog(
        caller: AgentWorkspaceCaller,
        connectorId: ConnectorId,
        catalogName: String,
    ): Boolean = catalogIngest.currentConnectorCatalogSelection(caller.workspaceId, connectorId)
        ?.selection
        ?.selectedCatalogNames
        ?.contains(catalogName) == true

    private suspend fun isRefreshParentSelected(
        caller: AgentWorkspaceCaller,
        connectorId: ConnectorId,
        parent: CatalogListRefreshParent,
    ): Boolean = when (parent) {
        CatalogListRefreshParent.Connector -> true
        is CatalogListRefreshParent.Catalog -> isSelectedCatalog(caller, connectorId, parent.name)
        is CatalogListRefreshParent.Schema -> parent.catalogName?.let {
            isSelectedCatalog(caller, connectorId, it)
        } ?: true
    }

    private suspend fun hasIngestableSelection(
        caller: AgentWorkspaceCaller,
        connectorId: ConnectorId,
        entry: CatalogIngestEntry,
    ): Boolean = catalogIngest.currentSelection(caller.workspaceId, connectorId, entry)
        ?.selection
        ?.selectedSchemas
        ?.isNotEmpty() == true

    private suspend fun isExplorer(caller: AgentWorkspaceCaller): Boolean = when (
        controlAdmissionFacade.authorizeWorkspaceRole(
            WorkspaceRoleAdmissionRequest(caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource, WorkspaceRole.EXPLORER),
        )
    ) {
        is ControlAdmissionDecision.Admitted -> true
        is ControlAdmissionDecision.Denied -> false
    }

    private suspend fun authorizeMutation(caller: AgentWorkspaceCaller): AuthorizedMutationContext? {
        val decision = controlAdmissionFacade.authorizeMutation(
            MutationAdmissionRequest(
                AdmissionRequest(caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource),
                MutationIntent.CATALOG_SELECTION_UPDATE,
            ),
        )
        return when (decision) {
            is ControlAdmissionDecision.Admitted -> decision.context
            is ControlAdmissionDecision.Denied -> null
        }
    }

    private suspend fun authorizeExecution(caller: AgentWorkspaceCaller): AuthorizedExecutionContext? = when (
        val decision = controlAdmissionFacade.authorizeExecution(
            ExecutionAdmissionRequest(
                workspaceId = caller.workspaceId,
                credential = caller.presentedCredential,
                expectedOAuthResource = caller.expectedOAuthResource,
                intent = ExecutionIntent.CATALOG_INGEST,
            ),
        )
    ) {
        is ControlAdmissionDecision.Admitted -> decision.context
        is ControlAdmissionDecision.Denied -> null
    }

    private suspend fun isCatalogExecutionAuthorized(caller: AgentWorkspaceCaller): Boolean = authorizeExecution(caller) != null

    private suspend fun isCatalogMutationAuthorized(caller: AgentWorkspaceCaller): Boolean = authorizeMutation(caller) != null

    private suspend fun rootOperations(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
        connectorType: ConnectorProductType,
    ): List<AgentAvailableOperation> {
        val connectorId = parsePath(path)!!.connectorId
        return if (connectorType.isThreeDepth()) {
            buildList {
                add(AgentAvailableOperation.List(path = catalogsPath(connectorId)))
                add(AgentAvailableOperation.Read(path = catalogSelectionPath(connectorId)))
                if (isCatalogExecutionAuthorized(caller)) {
                    add(AgentAvailableOperation.Invoke(path = catalogSelectionOptionPath(connectorId), action = "refresh", inputKind = null))
                }
                add(AgentAvailableOperation.List(path = catalogSelectionRefreshPath(connectorId)))
            }
        } else {
            buildList {
                add(AgentAvailableOperation.List(path = schemasPath(connectorId, null)))
                add(AgentAvailableOperation.Read(path = selectionPath(CatalogIngestEntry.Connector(connectorId))))
                add(AgentAvailableOperation.List(path = ingestionsPath(CatalogIngestEntry.Connector(connectorId))))
                if (
                    hasIngestableSelection(caller, connectorId, CatalogIngestEntry.Connector(connectorId)) &&
                    isCatalogExecutionAuthorized(caller)
                ) {
                    add(AgentAvailableOperation.Invoke(path = path, action = "ingest", inputKind = null))
                }
            }
        }
    }

    private fun parsePath(path: AgentResourcePath): PathParts? = runCatching {
        val raw = path.value.split('/')
        require(raw.size >= 4 && raw[0] == "workspace" && raw[1] == "connectors" && raw[3] == "catalog")
        PathParts(ConnectorId(Uuid.parse(raw[2])), raw.drop(4).map(::decodeNameSegment))
    }.getOrNull()

    private fun listRoute(path: PathParts, type: ConnectorProductType): ListRoute {
        if (type.isThreeDepth() && path.tail == listOf("catalogs")) return ListRoute.Catalogs(path.connectorId)
        if (type.isTwoDepth() && path.tail == listOf("schemas")) return ListRoute.Schemas(path.connectorId, null)
        if (type.isThreeDepth() && path.tail.size == 3 && path.tail[0] == "catalogs" && path.tail[2] == "schemas") {
            return ListRoute.Schemas(path.connectorId, path.tail[1])
        }
        if (type.isThreeDepth() && path.tail.size == 5 && path.tail[0] == "catalogs" && path.tail[2] == "schemas" && path.tail[4] == "relations") {
            return ListRoute.Relations(path.connectorId, path.tail[1], path.tail[3])
        }
        if (type.isTwoDepth() && path.tail.size == 3 && path.tail[0] == "schemas" && path.tail[2] == "relations") {
            return ListRoute.Relations(path.connectorId, null, path.tail[1])
        }
        optionCollection(path, type)?.let {
            return ListRoute.Options(it.connectorId, it.parent, it.optionKind, it.catalogName, it.schemaName, it.catalogCandidate)
        }
        refreshCollection(path, type)?.let { return it }
        ingestionCollection(path, type)?.let { return it }
        failureCollection(path, type)?.let { return it }
        return ListRoute.Unsupported
    }

    private fun readRoute(path: PathParts, type: ConnectorProductType): ReadRoute {
        if (path.tail.isEmpty()) return ReadRoute.Root
        if (type.isThreeDepth() && path.tail == listOf("catalog-selection")) return ReadRoute.CatalogSelection(path.connectorId)
        selectionRoute(path, type)?.let { return it }
        if (type.isThreeDepth() && path.tail.size == 2 && path.tail[0] == "catalogs") return ReadRoute.Catalog(path.connectorId, path.tail[1])
        schemaRoute(path, type)?.let { return it }
        relationRoute(path, type)?.let { return it }
        optionRoute(path, type)?.let { return it }
        refreshRoute(path, type)?.let { return ReadRoute.Refresh(path.connectorId, it.parent, it.catalogCandidate, it.refreshId) }
        ingestionRoute(path, type)?.let { return it }
        failureRoute(path, type)?.let { return it }
        return ReadRoute.Unsupported
    }

    private fun editRoute(path: PathParts, type: ConnectorProductType): EditRoute? {
        if (type.isThreeDepth() && path.tail == listOf("catalog-selection")) return EditRoute.ConnectorCatalogSelection
        selectionRoute(path, type)?.let { return EditRoute.Selection(it.entry) }
        return null
    }

    private fun invokeRoute(path: PathParts, type: ConnectorProductType, action: String): InvokeRoute = when {
        action == "refresh" -> optionCollection(path, type)?.let { InvokeRoute.StartRefresh(path.connectorId, it.parent, it.catalogCandidate) } ?: InvokeRoute.Unsupported
        action == "cancel" -> refreshRoute(path, type)?.let { InvokeRoute.CancelRefresh(path.connectorId, it.parent, it.catalogCandidate, it.refreshId) }
            ?: ingestionRoute(path, type)?.let { InvokeRoute.CancelIngestion(path.connectorId, it.entry, it.jobId) }
            ?: InvokeRoute.Unsupported
        action == "ingest" -> ingestionEntryRoute(path, type)?.let { InvokeRoute.StartIngestion(path.connectorId, it) } ?: InvokeRoute.Unsupported
        else -> InvokeRoute.Unsupported
    }

    private fun selectionRoute(path: PathParts, type: ConnectorProductType): ReadRoute.Selection? = when {
        type.isTwoDepth() && path.tail == listOf("selection") -> ReadRoute.Selection(path.connectorId, CatalogIngestEntry.Connector(path.connectorId))
        type.isThreeDepth() && path.tail.size == 3 && path.tail[0] == "catalogs" && path.tail[2] == "selection" ->
            ReadRoute.Selection(path.connectorId, CatalogIngestEntry.Catalog(path.connectorId, path.tail[1]))
        else -> null
    }

    private fun schemaRoute(path: PathParts, type: ConnectorProductType): ReadRoute.Schema? = when {
        type.isTwoDepth() && path.tail.size == 2 && path.tail[0] == "schemas" -> ReadRoute.Schema(path.connectorId, null, path.tail[1])
        type.isThreeDepth() && path.tail.size == 3 && path.tail[0] == "catalogs" && path.tail[2] == "schemas" -> null
        type.isThreeDepth() && path.tail.size == 4 && path.tail[0] == "catalogs" && path.tail[2] == "schemas" ->
            ReadRoute.Schema(path.connectorId, path.tail[1], path.tail[3])
        else -> null
    }

    private fun relationRoute(path: PathParts, type: ConnectorProductType): ReadRoute.Relation? = when {
        type.isTwoDepth() && path.tail.size == 4 && path.tail[0] == "schemas" && path.tail[2] == "relations" ->
            ReadRoute.Relation(path.connectorId, null, path.tail[1], path.tail[3])
        type.isThreeDepth() && path.tail.size == 5 && path.tail[0] == "catalogs" && path.tail[2] == "schemas" && path.tail[4] == "relations" -> null
        type.isThreeDepth() && path.tail.size == 6 && path.tail[0] == "catalogs" && path.tail[2] == "schemas" && path.tail[4] == "relations" ->
            ReadRoute.Relation(path.connectorId, path.tail[1], path.tail[3], path.tail[5])
        else -> null
    }

    private fun optionCollection(path: PathParts, type: ConnectorProductType): OptionCollection? = when {
        type.isThreeDepth() && path.tail == listOf("catalog-selection", "options", "catalogs") ->
            OptionCollection(path.connectorId, CatalogListRefreshParent.Connector, "CATALOG", null, null, true)
        type.isTwoDepth() && path.tail == listOf("selection", "options", "schemas") ->
            OptionCollection(path.connectorId, CatalogListRefreshParent.Connector, "SCHEMA", null, null)
        type.isThreeDepth() && path.tail.size == 4 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "options" -> null
        type.isThreeDepth() && path.tail.size == 5 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "options" && path.tail[4] == "schemas" ->
            OptionCollection(path.connectorId, CatalogListRefreshParent.Catalog(path.tail[1]), "SCHEMA", path.tail[1], null)
        type.isTwoDepth() && path.tail.size == 4 && path.tail[0] == "selection" && path.tail[1] == "options" && path.tail[2] == "schemas" -> null
        type.isTwoDepth() && path.tail.size == 5 && path.tail[0] == "selection" && path.tail[1] == "options" && path.tail[2] == "schemas" && path.tail[4] == "relations" ->
            OptionCollection(path.connectorId, CatalogListRefreshParent.Schema(null, path.tail[3]), "RELATION", null, path.tail[3])
        type.isThreeDepth() && path.tail.size == 7 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "options" && path.tail[4] == "schemas" && path.tail[6] == "relations" ->
            OptionCollection(path.connectorId, CatalogListRefreshParent.Schema(path.tail[1], path.tail[5]), "RELATION", path.tail[1], path.tail[5])
        else -> null
    }

    private fun refreshCollection(path: PathParts, type: ConnectorProductType): ListRoute.Refreshes? = when {
        type.isThreeDepth() && path.tail == listOf("catalog-selection", "option-refreshes", "catalogs") ->
            ListRoute.Refreshes(path.connectorId, CatalogListRefreshParent.Connector, true)
        type.isTwoDepth() && path.tail == listOf("selection", "option-refreshes", "schemas") ->
            ListRoute.Refreshes(path.connectorId, CatalogListRefreshParent.Connector, false)
        type.isThreeDepth() && path.tail.size == 5 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "option-refreshes" && path.tail[4] == "schemas" ->
            ListRoute.Refreshes(path.connectorId, CatalogListRefreshParent.Catalog(path.tail[1]), false)
        type.isTwoDepth() && path.tail.size == 5 && path.tail[0] == "selection" && path.tail[1] == "option-refreshes" && path.tail[2] == "schemas" && path.tail[4] == "relations" ->
            ListRoute.Refreshes(path.connectorId, CatalogListRefreshParent.Schema(null, path.tail[3]), false)
        type.isThreeDepth() && path.tail.size == 7 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "option-refreshes" && path.tail[4] == "schemas" && path.tail[6] == "relations" ->
            ListRoute.Refreshes(path.connectorId, CatalogListRefreshParent.Schema(path.tail[1], path.tail[5]), false)
        else -> null
    }

    private fun optionRoute(path: PathParts, type: ConnectorProductType): ReadRoute.Option? = when {
        type.isThreeDepth() && path.tail.size == 4 && path.tail[0] == "catalog-selection" && path.tail[1] == "options" && path.tail[2] == "catalogs" ->
            ReadRoute.Option(path.connectorId, CatalogListRefreshParent.Connector, "CATALOG", path.tail[3], null, null)
        type.isTwoDepth() && path.tail.size == 4 && path.tail[0] == "selection" && path.tail[1] == "options" && path.tail[2] == "schemas" ->
            ReadRoute.Option(path.connectorId, CatalogListRefreshParent.Connector, "SCHEMA", path.tail[3], null, null)
        type.isThreeDepth() && path.tail.size == 6 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "options" && path.tail[4] == "schemas" ->
            ReadRoute.Option(path.connectorId, CatalogListRefreshParent.Catalog(path.tail[1]), "SCHEMA", path.tail[5], path.tail[1], null)
        type.isTwoDepth() && path.tail.size == 6 && path.tail[0] == "selection" && path.tail[1] == "options" && path.tail[2] == "schemas" && path.tail[4] == "relations" ->
            ReadRoute.Option(path.connectorId, CatalogListRefreshParent.Schema(null, path.tail[3]), "RELATION", path.tail[5], null, path.tail[3])
        type.isThreeDepth() && path.tail.size == 8 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "options" && path.tail[4] == "schemas" && path.tail[6] == "relations" ->
            ReadRoute.Option(path.connectorId, CatalogListRefreshParent.Schema(path.tail[1], path.tail[5]), "RELATION", path.tail[7], path.tail[1], path.tail[5])
        else -> null
    }

    private fun ingestionCollection(path: PathParts, type: ConnectorProductType): ListRoute.Ingestions? = when {
        type.isTwoDepth() && path.tail == listOf("ingestions") -> ListRoute.Ingestions(path.connectorId, CatalogIngestEntry.Connector(path.connectorId))
        type.isThreeDepth() && path.tail.size == 3 && path.tail[0] == "catalogs" && path.tail[2] == "ingestions" ->
            ListRoute.Ingestions(path.connectorId, CatalogIngestEntry.Catalog(path.connectorId, path.tail[1]))
        else -> null
    }

    private fun ingestionRoute(path: PathParts, type: ConnectorProductType): ReadRoute.Ingestion? = when {
        type.isTwoDepth() && path.tail.size == 2 && path.tail[0] == "ingestions" -> parseJob(path.tail[1])?.let { ReadRoute.Ingestion(path.connectorId, CatalogIngestEntry.Connector(path.connectorId), it) }
        type.isThreeDepth() && path.tail.size == 4 && path.tail[0] == "catalogs" && path.tail[2] == "ingestions" -> parseJob(path.tail[3])?.let {
            ReadRoute.Ingestion(path.connectorId, CatalogIngestEntry.Catalog(path.connectorId, path.tail[1]), it)
        }
        else -> null
    }

    private fun failureCollection(path: PathParts, type: ConnectorProductType): ListRoute.Failures? = when {
        type.isTwoDepth() && path.tail.size == 3 && path.tail[0] == "ingestions" && path.tail[2] == "failures" -> parseJob(path.tail[1])?.let {
            ListRoute.Failures(path.connectorId, CatalogIngestEntry.Connector(path.connectorId), it)
        }
        type.isThreeDepth() && path.tail.size == 5 && path.tail[0] == "catalogs" && path.tail[2] == "ingestions" && path.tail[4] == "failures" -> parseJob(path.tail[3])?.let {
            ListRoute.Failures(path.connectorId, CatalogIngestEntry.Catalog(path.connectorId, path.tail[1]), it)
        }
        else -> null
    }

    private fun failureRoute(path: PathParts, type: ConnectorProductType): ReadRoute.Failure? = when {
        type.isTwoDepth() && path.tail.size == 4 && path.tail[0] == "ingestions" && path.tail[2] == "failures" -> {
            val job = parseJob(path.tail[1]); val failure = parseTask(path.tail[3]); if (job != null && failure != null) ReadRoute.Failure(path.connectorId, CatalogIngestEntry.Connector(path.connectorId), job, failure) else null
        }
        type.isThreeDepth() && path.tail.size == 6 && path.tail[0] == "catalogs" && path.tail[2] == "ingestions" && path.tail[4] == "failures" -> {
            val job = parseJob(path.tail[3]); val failure = parseTask(path.tail[5]); if (job != null && failure != null) ReadRoute.Failure(path.connectorId, CatalogIngestEntry.Catalog(path.connectorId, path.tail[1]), job, failure) else null
        }
        else -> null
    }

    private fun refreshRoute(path: PathParts, type: ConnectorProductType): RefreshRoute? = when {
        type.isThreeDepth() && path.tail.size == 4 && path.tail[0] == "catalog-selection" && path.tail[1] == "option-refreshes" && path.tail[2] == "catalogs" -> parseRefresh(path.tail[3])?.let {
            RefreshRoute(CatalogListRefreshParent.Connector, true, it)
        }
        type.isTwoDepth() && path.tail.size == 4 && path.tail[0] == "selection" && path.tail[1] == "option-refreshes" && path.tail[2] == "schemas" -> parseRefresh(path.tail[3])?.let {
            RefreshRoute(CatalogListRefreshParent.Connector, false, it)
        }
        type.isThreeDepth() && path.tail.size == 6 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "option-refreshes" && path.tail[4] == "schemas" -> parseRefresh(path.tail[5])?.let {
            RefreshRoute(CatalogListRefreshParent.Catalog(path.tail[1]), false, it)
        }
        type.isTwoDepth() && path.tail.size == 6 && path.tail[0] == "selection" && path.tail[1] == "option-refreshes" && path.tail[2] == "schemas" && path.tail[4] == "relations" -> parseRefresh(path.tail[5])?.let {
            RefreshRoute(CatalogListRefreshParent.Schema(null, path.tail[3]), false, it)
        }
        type.isThreeDepth() && path.tail.size == 8 && path.tail[0] == "catalogs" && path.tail[2] == "selection" && path.tail[3] == "option-refreshes" && path.tail[4] == "schemas" && path.tail[6] == "relations" -> parseRefresh(path.tail[7])?.let {
            RefreshRoute(CatalogListRefreshParent.Schema(path.tail[1], path.tail[5]), false, it)
        }
        else -> null
    }

    private fun ingestionEntryRoute(path: PathParts, type: ConnectorProductType): CatalogIngestEntry? = when {
        type.isTwoDepth() && path.tail.isEmpty() -> CatalogIngestEntry.Connector(path.connectorId)
        type.isThreeDepth() && path.tail.size == 2 && path.tail[0] == "catalogs" -> CatalogIngestEntry.Catalog(path.connectorId, path.tail[1])
        else -> null
    }

    private fun decodeConnectorCatalogSelection(element: JsonElement): ConnectorCatalogSelection {
        val objectValue = element.jsonObject
        require(objectValue.keys == setOf("selectedCatalogNames"))
        val names = objectValue.getValue("selectedCatalogNames").jsonArray.map { it.jsonPrimitive.content }
        require(names.distinct().size == names.size)
        return ConnectorCatalogSelection(names.toSet())
    }

    private fun decodeSelection(element: JsonElement): cc.monomer.catalog.api.CatalogSelection {
        val objectValue = element.jsonObject
        require(objectValue.keys == setOf("selectedSchemas"))
        val selected = objectValue.getValue("selectedSchemas").jsonArray.map { schemaElement ->
            val schema = schemaElement.jsonObject
            require(schema.keys == setOf("schemaName", "relations"))
            CatalogSelectedSchema(schema.getValue("schemaName").jsonPrimitive.content, decodeRelations(schema.getValue("relations")))
        }
        require(selected.map { it.schemaName }.distinct().size == selected.size)
        return cc.monomer.catalog.api.CatalogSelection(selected.toSet())
    }

    private fun decodeRelations(element: JsonElement): CatalogRelationSelection {
        val objectValue = element.jsonObject
        val kind = objectValue.getValue("kind").jsonPrimitive.content
        return when (kind) {
            "ALL_RELATIONS" -> {
                require(objectValue.keys == setOf("kind"))
                CatalogRelationSelection.AllRelations
            }
            "SELECTED_RELATIONS" -> {
                require(objectValue.keys == setOf("kind", "relationNames"))
                val names = objectValue.getValue("relationNames").jsonArray.map { it.jsonPrimitive.content }
                require(names.distinct().size == names.size)
                CatalogRelationSelection.SelectedRelations(names.toSet())
            }
            else -> error("unsupported relation selection kind")
        }
    }

    private fun cc.monomer.catalog.api.CatalogSelection.toAgentBody() = AgentCatalogSelectionResourceBody(
        selectedSchemas = selectedSchemas.sortedBy { it.schemaName }.map { schema ->
            AgentCatalogSelectedSchema(
                schemaName = schema.schemaName,
                relations = when (val relations = schema.relations) {
                    CatalogRelationSelection.AllRelations -> AgentCatalogRelationSelection.AllRelations
                    is CatalogRelationSelection.SelectedRelations -> AgentCatalogRelationSelection.SelectedRelations(relations.relationNames.sorted())
                },
            )
        },
    )

    private fun RelationDetail.toAgentBody(connectorId: ConnectorId) = AgentCatalogResourceBody(
        connectorId = connectorId.value,
        catalogName = catalogName,
        schemaName = schemaName,
        relationName = name,
        relationKind = kind,
        viewDefinition = viewDefinition,
        partitionKeys = partitionKeys,
        clusteringKeys = clusteringKeys,
        requiresPartitionFilter = requiresPartitionFilter,
        columns = columns.map { column ->
            AgentCatalogColumn(
                column.name,
                column.nativeType,
                column.ordinalPosition,
                column.nullable,
                column.defaultValue,
            )
        },
        primaryKey = primaryKey?.let { AgentCatalogPrimaryKey(it.name, it.columns) },
        foreignKeys = foreignKeys.map { key ->
            AgentCatalogForeignKey(
                name = key.name,
                columns = key.columns,
                referencedConnectorId = Uuid.parse(key.referencedConnectorId),
                referencedCatalogName = key.referencedCatalogName,
                referencedSchemaName = key.referencedSchemaName,
                referencedRelationName = key.referencedRelationName,
                referencedColumns = key.referencedColumns,
            )
        },
    )

    private fun CatalogIngestJobView.toAgentBody(entry: CatalogIngestEntry, provenance: AgentProvenance) = AgentCatalogJobResourceBody(
        kind = AgentResourceKind.CATALOG_INGESTION,
        state = job.state.name,
        requestedAt = job.requestedAt,
        terminalAt = job.terminalAt,
        failureCode = null,
        message = null,
        refreshedRelationCount = refreshedRelationCount,
        runningTaskCount = runningTaskCount,
        errorCount = errorCount,
        runningTargets = tasks.map { task -> task.target.toReference(entry) },
        requestedBy = cc.monomer.agent.api.AgentCatalogRequester(
            principalId = job.requestedBy.toString(),
            displayName = provenance.actor(job.requestedBy.toString())?.displayName,
            credentialSurface = job.credentialSurface,
            agentApp = provenance.app(job.agentClientId),
        ),
    )

    private fun CatalogIngestTaskTarget.toReference(entry: CatalogIngestEntry): AgentActionResultReference = when (this) {
        CatalogIngestTaskTarget.Connector -> AgentActionResultReference(AgentResourceKind.CONNECTOR, connectorPath(entry.connectorId))
        is CatalogIngestTaskTarget.Catalog -> AgentActionResultReference(AgentResourceKind.CATALOG, catalogPath(entry.connectorId, name))
        is CatalogIngestTaskTarget.Schema -> AgentActionResultReference(AgentResourceKind.SCHEMA, schemaPath(entry.connectorId, catalogName, name))
        is CatalogIngestTaskTarget.Relation -> AgentActionResultReference(AgentResourceKind.RELATION, relationPath(entry.connectorId, catalogName, schemaName, name))
    }

    private fun CatalogListRefreshJobView.toAgentBody() = AgentCatalogJobResourceBody(
        kind = AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH,
        state = job.state.name,
        requestedAt = job.requestedAt,
        terminalAt = job.terminalAt,
        failureCode = job.failureCode,
        message = job.safeMessage,
        refreshedRelationCount = null,
        runningTaskCount = null,
        errorCount = null,
        runningTargets = null,
        requestedBy = null,
    )

    private fun refreshListingItem(route: ListRoute.Refreshes, job: CatalogListRefreshJob) = AgentResourceListingItem.CatalogRefresh(path = refreshPath(route.connectorId, route.parent, route.catalogCandidate, job.id), state = cc.monomer.agent.api.AgentCatalogJobState.valueOf(job.state.name), requestedAt = job.requestedAt, terminalAt = job.terminalAt)

    private data class CursorKey(
        val name: String? = null,
        val time: Instant? = null,
        val id: Uuid? = null,
    )

    private fun <T> listing(
        items: List<AgentResourceListingItem>,
        source: List<T>,
        limit: Int,
        path: AgentResourcePath,
        cursor: Cursor? = null,
        keyOf: (T) -> CursorKey,
        availableOperations: List<AgentAvailableOperation>,
    ): AgentListingOutcome {
        val hasMore = source.size > limit
        val next = if (hasMore) {
            keyOf(source[limit - 1]).let { key ->
                when {
                    key.name != null -> encodeNameCursor(path, key.name)
                    key.time != null && key.id != null -> encodeTimeCursor(path, key.time, key.id)
                    else -> error("cursor key is incomplete")
                }
            }
        } else null
        return AgentListingOutcome.Found(AgentResourceListingResponse(items, next, availableOperations))
    }

    private fun optionListingItem(route: ListRoute.Options, option: CatalogListOption) = when (route.optionKind) {
        "CATALOG" -> AgentResourceListingItem.CatalogOption(optionPath(route, option.name), option.name)
        "SCHEMA" -> AgentResourceListingItem.SchemaOption(optionPath(route, option.name), option.name, option.available)
        "RELATION" -> AgentResourceListingItem.RelationOption(optionPath(route, option.name), option.name, option.available,
            cc.monomer.agent.api.AgentRelationKind.valueOf(requireNotNull(option.relationKind).name))
        else -> error("unsupported Catalog option kind")
    }

    private fun ingestionListingItem(entry: CatalogIngestEntry, job: CatalogIngestJob) = AgentResourceListingItem.CatalogIngestion(path = ingestionPath(entry, job.id), state = cc.monomer.agent.api.AgentCatalogJobState.valueOf(job.state.name), requestedAt = job.requestedAt, terminalAt = job.terminalAt)

    private fun failureListingItem(route: ListRoute.Failures, task: CatalogIngestTask) = AgentResourceListingItem.CatalogFailure(path = failurePath(route.entry, route.jobId, task.id))

    private fun completed(path: AgentResourcePath, action: String?, kind: AgentResourceKind, resultPath: AgentResourcePath) = AgentActionOutcome.Completed(
        AgentActionResult.Edit(path = path, result = AgentActionResultReference(kind, resultPath)),
    )

    private fun accepted(request: AgentInvokeRequest, kind: AgentResourceKind, resultPath: AgentResourcePath) = AgentActionOutcome.Completed(
        AgentActionResult.Invoke(path = request.path, action = request.action, status = AgentActionStatus.ACCEPTED, result = AgentActionResultReference(kind, resultPath)),
    )

    private fun rejected(code: String, detail: String, violation: AgentProblemViolation? = null) = AgentActionOutcome.Rejected(
        AgentWorkspaceProblem(
            code,
            code.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase),
            detail,
            AgentWorkspaceProblemStatus.BAD_REQUEST,
            false,
            null,
            listOfNotNull(violation),
            hints = emptyList(),
            nextActions = emptyList(),
            retryAfterSeconds = null,
            roleRequirement = null,
            partitionFilters = emptyList(),
        ),
    )

    private fun CatalogCommandResult<*>.toActionOutcome(): AgentActionOutcome = when (this) {
        CatalogCommandResult.InvalidAuthorization -> AgentActionOutcome.Forbidden
        CatalogCommandResult.NotFoundOrOutsideWorkspace -> AgentActionOutcome.NotFound
        CatalogCommandResult.Conflict -> AgentActionOutcome.Rejected(
            conflictProblem("CATALOG_CONFLICT", "The Catalog owner rejected the request because its current state changed."),
        )
        is CatalogCommandResult.ConflictWithReason -> reason.toCatalogConflictDetails().let { conflict ->
            AgentActionOutcome.Rejected(conflictProblem(conflict.code, conflict.detail))
        }
        is CatalogCommandResult.Rejected -> rejected("CATALOG_REQUEST_INVALID", failures.firstOrNull() ?: "The Catalog request is invalid.")
        is CatalogCommandResult.Accepted<*> -> error("accepted result must be handled by caller")
    }

    private fun optionsNotFetchedProblem(path: AgentResourcePath, canRefresh: Boolean) = AgentWorkspaceProblem(
        "CATALOG_SELECTION_OPTIONS_NOT_FETCHED",
        "Catalog selection options have not been fetched",
        "Refresh this option collection before listing its children.",
        AgentWorkspaceProblemStatus.CONFLICT,
        false,
        null,
        emptyList(),
        emptyList(),
        if (canRefresh) listOf(AgentAvailableOperation.Invoke(path = path, action = "refresh", inputKind = null)) else emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    private fun conflictProblem(code: String, detail: String) = AgentWorkspaceProblem(
        code, "Catalog request conflicts with current state", detail, AgentWorkspaceProblemStatus.CONFLICT, false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    private fun encodeNameCursor(path: AgentResourcePath, name: String): String =
        encodeCursor('N', listOf(path.value, name))

    private fun encodeTimeCursor(path: AgentResourcePath, time: Instant, id: Uuid): String =
        encodeCursor('T', listOf(path.value, time.toString(), id.toString()))

    private fun encodeCursor(kind: Char, fields: List<String>): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeByte(2)
            stream.writeByte(kind.code)
            fields.forEach { field ->
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                stream.writeInt(bytes.size)
                stream.write(bytes)
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }

    private fun decodeCursor(path: AgentResourcePath, cursor: String): Cursor {
        val decoded = Base64.getUrlDecoder().decode(cursor)
        val input = DataInputStream(ByteArrayInputStream(decoded))
        require(input.available() >= 2 && input.readUnsignedByte() == 2)
        val kind = input.readUnsignedByte().toChar()
        require(kind == 'N' || kind == 'T')
        require(readCursorField(input) == path.value)
        if (kind == 'N') {
            val name = readCursorField(input)
            encodeNameSegment(name)
            require(input.available() == 0 && encodeNameCursor(path, name) == cursor)
            return Cursor(CursorKind.NAME, name, null, Uuid.NIL)
        }
        val time = try {
            Instant.parse(readCursorField(input))
        } catch (exception: java.time.DateTimeException) {
            throw IllegalArgumentException("invalid cursor timestamp", exception)
        }
        val id = cc.monomer.catalog.api.CatalogIngestJobId(Uuid.parse(readCursorField(input))).value
        require(input.available() == 0 && encodeTimeCursor(path, time, id) == cursor)
        return Cursor(CursorKind.TIME, null, time, id)
    }

    private fun readCursorField(input: DataInputStream): String {
        require(input.available() >= Int.SIZE_BYTES)
        val length = input.readInt()
        require(length >= 0 && length <= input.available())
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (exception: CharacterCodingException) {
            throw IllegalArgumentException("cursor is not valid UTF-8", exception)
        }
    }

    private fun connectorPath(id: ConnectorId) = AgentResourcePath.parse("workspace/connectors/${id.value}/catalog")
    private fun catalogsPath(id: ConnectorId) = AgentResourcePath.parse("${connectorPath(id).value}/catalogs")
    private fun catalogPath(id: ConnectorId, name: String) = AgentResourcePath.parse("${connectorPath(id).value}/catalogs/${encodeNameSegment(name)}")
    private fun schemasPath(id: ConnectorId, catalog: String?) = AgentResourcePath.parse(if (catalog == null) "${connectorPath(id).value}/schemas" else "${catalogPath(id, catalog).value}/schemas")
    private fun schemaPath(id: ConnectorId, catalog: String?, schema: String) = AgentResourcePath.parse("${schemasPath(id, catalog).value}/${encodeNameSegment(schema)}")
    private fun relationsPath(id: ConnectorId, catalog: String?, schema: String) = AgentResourcePath.parse("${schemaPath(id, catalog, schema).value}/relations")
    private fun relationPath(id: ConnectorId, catalog: String?, schema: String, relation: String) = AgentResourcePath.parse("${relationsPath(id, catalog, schema).value}/${encodeNameSegment(relation)}")
    private fun catalogSelectionPath(id: ConnectorId) = AgentResourcePath.parse("${connectorPath(id).value}/catalog-selection")
    private fun selectionPath(entry: CatalogIngestEntry) = AgentResourcePath.parse("${entryPath(entry).value}/selection")
    private fun catalogSelectionOptionPath(id: ConnectorId) = AgentResourcePath.parse("${catalogSelectionPath(id).value}/options/catalogs")
    private fun catalogSelectionRefreshPath(id: ConnectorId) = AgentResourcePath.parse("${catalogSelectionPath(id).value}/option-refreshes/catalogs")
    private fun selectionOptionPath(entry: CatalogIngestEntry) = AgentResourcePath.parse("${selectionPath(entry).value}/options/schemas")
    private fun selectionRefreshPath(entry: CatalogIngestEntry) = AgentResourcePath.parse("${selectionPath(entry).value}/option-refreshes/schemas")
    private fun ingestionsPath(entry: CatalogIngestEntry) = AgentResourcePath.parse("${entryPath(entry).value}/ingestions")
    private fun failuresPath(entry: CatalogIngestEntry, job: cc.monomer.catalog.api.CatalogIngestJobId) = AgentResourcePath.parse("${ingestionPath(entry, job).value}/failures")
    private fun entryPath(entry: CatalogIngestEntry) = when (entry) {
        is CatalogIngestEntry.Connector -> connectorPath(entry.connectorId)
        is CatalogIngestEntry.Catalog -> catalogPath(entry.connectorId, entry.name)
    }
    private fun ingestionPath(entry: CatalogIngestEntry, id: cc.monomer.catalog.api.CatalogIngestJobId) = AgentResourcePath.parse("${ingestionsPath(entry).value}/${id.value}")
    private fun failurePath(entry: CatalogIngestEntry, job: cc.monomer.catalog.api.CatalogIngestJobId, id: cc.monomer.catalog.api.CatalogIngestTaskId) = AgentResourcePath.parse("${failuresPath(entry, job).value}/${id.value}")
    private fun optionRelationsPath(route: ReadRoute.Option) = AgentResourcePath.parse("${optionPath(route.asOptions(), route.optionName).value}/relations")
    private fun optionPath(route: ListRoute.Options, name: String): AgentResourcePath = when (route.optionKind) {
        "CATALOG" -> AgentResourcePath.parse("${catalogSelectionOptionPath(route.connectorId).value}/${encodeNameSegment(name)}")
        "SCHEMA" -> AgentResourcePath.parse("${selectionOptionPath(route.entry!!).value}/${encodeNameSegment(name)}")
        else -> AgentResourcePath.parse("${selectionOptionPath(route.entry!!).value}/${encodeNameSegment(requireNotNull(route.schemaName))}/relations/${encodeNameSegment(name)}")
    }
    private fun optionPath(route: OptionCollection, name: String): AgentResourcePath = optionPath(
        ListRoute.Options(route.connectorId, route.parent, route.optionKind, route.catalogName, route.schemaName, route.catalogCandidate),
        name,
    )
    private fun optionCollectionPath(id: ConnectorId, parent: CatalogListRefreshParent, catalogCandidate: Boolean): AgentResourcePath = when (parent) {
        CatalogListRefreshParent.Connector -> if (catalogCandidate) catalogSelectionOptionPath(id) else selectionOptionPath(CatalogIngestEntry.Connector(id))
        is CatalogListRefreshParent.Catalog -> selectionOptionPath(CatalogIngestEntry.Catalog(id, parent.name))
        is CatalogListRefreshParent.Schema -> AgentResourcePath.parse("${selectionOptionPath(parent.catalogName?.let { CatalogIngestEntry.Catalog(id, it) } ?: CatalogIngestEntry.Connector(id)).value}/${encodeNameSegment(parent.name)}/relations")
    }
    private fun refreshCollectionPath(
        id: ConnectorId,
        parent: CatalogListRefreshParent,
        catalogCandidate: Boolean,
    ): AgentResourcePath = when (parent) {
        CatalogListRefreshParent.Connector -> if (catalogCandidate) {
            catalogSelectionRefreshPath(id)
        } else {
            selectionRefreshPath(CatalogIngestEntry.Connector(id))
        }
        is CatalogListRefreshParent.Catalog -> selectionRefreshPath(CatalogIngestEntry.Catalog(id, parent.name))
        is CatalogListRefreshParent.Schema -> {
            val entry = parent.catalogName?.let { CatalogIngestEntry.Catalog(id, it) } ?: CatalogIngestEntry.Connector(id)
            AgentResourcePath.parse("${selectionRefreshPath(entry).value}/${encodeNameSegment(parent.name)}/relations")
        }
    }
    private fun refreshPath(id: ConnectorId, parent: CatalogListRefreshParent, catalogCandidate: Boolean, refreshId: cc.monomer.catalog.api.CatalogListRefreshJobId): AgentResourcePath =
        AgentResourcePath.parse("${refreshCollectionPath(id, parent, catalogCandidate).value}/${refreshId.value}")

    private fun parseJob(raw: String) = runCatching { cc.monomer.catalog.api.CatalogIngestJobId(Uuid.parse(raw)) }.getOrNull()
    private fun parseTask(raw: String) = runCatching { cc.monomer.catalog.api.CatalogIngestTaskId(Uuid.parse(raw)) }.getOrNull()
    private fun parseRefresh(raw: String) = runCatching { cc.monomer.catalog.api.CatalogListRefreshJobId(Uuid.parse(raw)) }.getOrNull()

    private data class PathParts(val connectorId: ConnectorId, val tail: List<String>)
    private enum class CursorKind { NAME, TIME }
    private data class Cursor(val kind: CursorKind, val name: String?, val time: Instant?, val id: Uuid)
    private sealed interface ListRoute {
        data class Catalogs(val connectorId: ConnectorId) : ListRoute
        data class Schemas(val connectorId: ConnectorId, val catalogName: String?) : ListRoute
        data class Relations(val connectorId: ConnectorId, val catalogName: String?, val schemaName: String) : ListRoute
        data class Options(val connectorId: ConnectorId, val parent: CatalogListRefreshParent, val optionKind: String, val catalogName: String?, val schemaName: String?, val catalogCandidate: Boolean = false) : ListRoute {
            val entry: CatalogIngestEntry? = when {
                optionKind == "CATALOG" -> null
                catalogName == null -> CatalogIngestEntry.Connector(connectorId)
                else -> CatalogIngestEntry.Catalog(connectorId, catalogName)
            }
        }
        data class Refreshes(val connectorId: ConnectorId, val parent: CatalogListRefreshParent, val catalogCandidate: Boolean) : ListRoute
        data class Ingestions(val connectorId: ConnectorId, val entry: CatalogIngestEntry) : ListRoute
        data class Failures(val connectorId: ConnectorId, val entry: CatalogIngestEntry, val jobId: cc.monomer.catalog.api.CatalogIngestJobId) : ListRoute
        data object Unsupported : ListRoute
    }

    private val ListRoute.cursorKind: CursorKind?
        get() = when (this) {
            is ListRoute.Catalogs,
            is ListRoute.Schemas,
            is ListRoute.Relations,
            is ListRoute.Options,
            -> CursorKind.NAME
            is ListRoute.Refreshes,
            is ListRoute.Ingestions,
            is ListRoute.Failures,
            -> CursorKind.TIME
            ListRoute.Unsupported -> null
        }
    private sealed interface ReadRoute {
        data object Root : ReadRoute
        data class CatalogSelection(val connectorId: ConnectorId) : ReadRoute
        data class Selection(val connectorId: ConnectorId, val entry: CatalogIngestEntry) : ReadRoute
        data class Catalog(val connectorId: ConnectorId, val catalogName: String) : ReadRoute {
            val entry: CatalogIngestEntry = CatalogIngestEntry.Catalog(connectorId, catalogName)
        }
        data class Refresh(val connectorId: ConnectorId, val parent: CatalogListRefreshParent, val catalogCandidate: Boolean, val refreshId: cc.monomer.catalog.api.CatalogListRefreshJobId) : ReadRoute
        data class Schema(val connectorId: ConnectorId, val catalogName: String?, val schemaName: String) : ReadRoute
        data class Relation(val connectorId: ConnectorId, val catalogName: String?, val schemaName: String, val relationName: String) : ReadRoute
        data class Option(val connectorId: ConnectorId, val parent: CatalogListRefreshParent, val optionKind: String, val optionName: String, val catalogName: String?, val schemaName: String?) : ReadRoute {
            fun asOptions() = ListRoute.Options(connectorId, parent, optionKind, catalogName, schemaName)
        }
        data class Ingestion(val connectorId: ConnectorId, val entry: CatalogIngestEntry, val jobId: cc.monomer.catalog.api.CatalogIngestJobId) : ReadRoute
        data class Failure(val connectorId: ConnectorId, val entry: CatalogIngestEntry, val jobId: cc.monomer.catalog.api.CatalogIngestJobId, val failureId: cc.monomer.catalog.api.CatalogIngestTaskId) : ReadRoute
        data object Unsupported : ReadRoute
    }
    private sealed interface EditRoute {
        data object ConnectorCatalogSelection : EditRoute
        data class Selection(val entry: CatalogIngestEntry) : EditRoute
    }
    private sealed interface InvokeRoute {
        data class StartRefresh(val connectorId: ConnectorId, val parent: CatalogListRefreshParent, val catalogCandidate: Boolean) : InvokeRoute
        data class CancelRefresh(val connectorId: ConnectorId, val parent: CatalogListRefreshParent, val catalogCandidate: Boolean, val refreshId: cc.monomer.catalog.api.CatalogListRefreshJobId) : InvokeRoute
        data class StartIngestion(val connectorId: ConnectorId, val entry: CatalogIngestEntry) : InvokeRoute
        data class CancelIngestion(val connectorId: ConnectorId, val entry: CatalogIngestEntry, val jobId: cc.monomer.catalog.api.CatalogIngestJobId) : InvokeRoute
        data object Unsupported : InvokeRoute
    }
    private data class OptionCollection(
        val connectorId: ConnectorId,
        val parent: CatalogListRefreshParent,
        val optionKind: String,
        val catalogName: String?,
        val schemaName: String?,
        val catalogCandidate: Boolean = false,
    )
    private data class RefreshRoute(
        val parent: CatalogListRefreshParent,
        val catalogCandidate: Boolean,
        val refreshId: cc.monomer.catalog.api.CatalogListRefreshJobId,
    )

    private companion object {
        fun ConnectorProductType.isTwoDepth() = !hasCatalogLevel
        fun ConnectorProductType.isThreeDepth() = !isTwoDepth()
    }
}
