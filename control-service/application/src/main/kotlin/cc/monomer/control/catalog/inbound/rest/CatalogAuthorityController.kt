@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.inbound.rest

import cc.monomer.control.agentapp.AgentAppResponse
import cc.monomer.control.agentapp.responses
import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogIngestStarted
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionView
import cc.monomer.catalog.api.ConnectorCatalogSelection
import cc.monomer.catalog.api.ConnectorCatalogSelectionView
import cc.monomer.catalog.api.SaveCatalogSelectionCommand
import cc.monomer.catalog.api.SaveConnectorCatalogSelectionCommand
import cc.monomer.catalog.api.StartCatalogIngestCommand
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogIngestErrorPage
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobPage
import cc.monomer.catalog.domain.CatalogIngestJobSummary
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListRefreshParent
import cc.monomer.catalog.application.ingest.CancelCatalogListRefreshCommand
import cc.monomer.catalog.application.ingest.StartCatalogListRefreshCommand
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.AdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ExecutionAdmissionRequest
import cc.monomer.control.MutationAdmissionRequest
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.human.auth.toConnectorCatalogProblem
import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.catalog.application.ingest.CatalogActiveIngestResult
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationPort
import cc.monomer.catalog.application.ingest.CatalogIngestCancelCommand
import cc.monomer.catalog.application.ingest.CatalogIngestJobView
import cc.monomer.identity.api.ExecutionIntent
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import kotlin.uuid.Uuid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
class CatalogAuthorityController(
    private val catalogIngest: CatalogIngestApplicationPort,
    private val catalogListRefresh: CatalogListRefreshApplicationPort,
    private val controlAdmissionFacade: ControlAdmissionFacade,
    private val principalDisplayNames: PrincipalDisplayNames,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
) {
    @PutMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/catalog-selection",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun saveConnectorCatalogSelection(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestBody body: ConnectorCatalogSelectionHttpRequest,
        exchange: ServerWebExchange,
    ): ConnectorCatalogSelectionHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        val authorization = authorizeMutation(workspace, exchange)
        val selection = try {
            body.toDomain()
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message)
        }
        return catalogIngest.saveConnectorCatalogSelection(
            SaveConnectorCatalogSelectionCommand(authorization, connector, selection),
        ).acceptedOrThrow().toHttpView()
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/catalog-selection",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getConnectorCatalogSelection(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        exchange: ServerWebExchange,
    ): ConnectorCatalogSelectionHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        authorizeMembership(workspace, exchange)
        return catalogIngest.currentConnectorCatalogSelection(workspace, connector)?.toHttpView()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @PutMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/selection",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun saveConnectorSelection(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestBody body: CatalogSelectionHttpRequest,
        exchange: ServerWebExchange,
    ): CatalogSelectionHttpView {
        return saveSelection(workspaceId, connectorId, null, body, exchange)
    }

    @PutMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/catalogs/{catalogName}/selection",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun saveCatalogSelection(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable catalogName: String,
        @RequestBody body: CatalogSelectionHttpRequest,
        exchange: ServerWebExchange,
    ): CatalogSelectionHttpView {
        return saveSelection(workspaceId, connectorId, catalogName, body, exchange)
    }

    private suspend fun saveSelection(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        body: CatalogSelectionHttpRequest,
        exchange: ServerWebExchange,
    ): CatalogSelectionHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        val authorization = authorizeMutation(workspace, exchange)
        val entry = parseEntry(connector, catalogName)
        val selection = try {
            body.toDomain()
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message)
        }
        return catalogIngest.saveSelection(
            SaveCatalogSelectionCommand(authorization, connector, entry, selection),
        ).acceptedOrThrow().toHttpView()
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/selection",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getConnectorSelection(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        exchange: ServerWebExchange,
    ): CatalogSelectionHttpView {
        return getSelection(workspaceId, connectorId, null, exchange)
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/catalogs/{catalogName}/selection",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getCatalogSelection(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable catalogName: String,
        exchange: ServerWebExchange,
    ): CatalogSelectionHttpView {
        return getSelection(workspaceId, connectorId, catalogName, exchange)
    }

    private suspend fun getSelection(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        exchange: ServerWebExchange,
    ): CatalogSelectionHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        authorizeMembership(workspace, exchange)
        val entry = parseEntry(connector, catalogName)
        return catalogIngest.currentSelection(workspace, connector, entry)?.toHttpView()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/selection/options",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getSelectionOptions(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestParam parentKind: String,
        @RequestParam(required = false) catalogName: String?,
        @RequestParam(required = false) schemaName: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") pageSize: Int,
        @RequestParam(required = false) query: String?,
        exchange: ServerWebExchange,
    ): CatalogListOptionPageHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        authorizeMembership(workspace, exchange)
        val parent = try {
            CatalogListRefreshHttpRequest(parentKind, catalogName, schemaName).toParent()
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message)
        }
        if (page < 1 || pageSize !in 1..200) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid option page")
        }
        return catalogListRefresh.findListOptions(
            workspaceId = workspace,
            connectorId = connector,
            parent = parent,
            page = page,
            pageSize = pageSize,
            query = query,
        )?.toHttpView() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @GetMapping("/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/selection/list-refresh-jobs/{jobId}")
    suspend fun getSelectionListRefresh(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable jobId: String,
        @RequestParam parentKind: String,
        @RequestParam(required = false) catalogName: String?,
        @RequestParam(required = false) schemaName: String?,
        exchange: ServerWebExchange,
    ): CatalogListRefreshJobHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        authorizeMembership(workspace, exchange)
        val parent = try { CatalogListRefreshHttpRequest(parentKind, catalogName, schemaName).toParent() }
        catch (exception: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid refresh target.") }
        return catalogListRefresh.findListRefreshJob(workspace, connector, parent, parseListRefreshJobId(jobId))?.toHttpView()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @PostMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/selection/list-refresh-jobs",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun startSelectionListRefresh(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestBody body: CatalogListRefreshHttpRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<CatalogListRefreshJobHttpView> {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        val authorization = authorizeCatalogIngestion(workspace, exchange)
        val parent = try {
            body.toParent()
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message)
        }
        val result = catalogListRefresh.startListRefresh(
            StartCatalogListRefreshCommand(authorization, connector, parent),
        )
        val job = result.acceptedOrThrow()
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toHttpView())
    }

    @PostMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/selection/list-refresh-jobs/{jobId}/cancel",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun cancelSelectionListRefresh(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable jobId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<CatalogListRefreshJobHttpView> {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        val authorization = authorizeCatalogIngestion(workspace, exchange)
        val result = catalogListRefresh.cancelListRefresh(
            CancelCatalogListRefreshCommand(
                authorization = authorization,
                connectorId = connector,
                jobId = parseListRefreshJobId(jobId),
                reasonCode = "USER_REQUESTED",
            ),
        )
        val job = result.acceptedOrThrow()
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toHttpView())
    }

    @PostMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/ingests",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun startIngest(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestBody body: CatalogIngestHttpRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<CatalogIngestAcceptedHttpView> {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        val authorization = authorizeCatalogIngestion(workspace, exchange)
        val entry = try {
            body.toEntry(connector)
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message)
        }
        val result = catalogIngest.startIngest(
            StartCatalogIngestCommand(
                authorization = authorization,
                connectorId = connector,
                entry = entry,
            ),
        )
        val started = result.acceptedOrThrow()
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .header(HttpHeaders.LOCATION, jobLocation(started))
            .header(HttpHeaders.RETRY_AFTER, "2")
            .body(started.toHttpView())
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/ingests",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun listIngests(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestParam(required = false) catalogName: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
        exchange: ServerWebExchange,
    ): CatalogIngestJobPageHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        authorizeMembership(workspace, exchange)
        if (page < 1 || pageSize !in 1..200) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ingest page")
        }
        val entry = parseEntry(connector, catalogName)
        val jobPage = catalogIngest.findJobs(workspace, connector, entry, page, pageSize)
        return jobPage.toHttpView(requesterNames(jobPage.jobs.map { it.job }))
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/ingests/active",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getActiveIngest(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestParam(required = false) catalogName: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<CatalogIngestJobHttpView> {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        authorizeMembership(workspace, exchange)
        val entry = parseEntry(connector, catalogName)
        return when (val result = catalogIngest.findActive(workspace, connector, entry)) {
            is CatalogActiveIngestResult.Active -> ResponseEntity.ok(result.view.toHttpView(requesterNames(listOf(result.view.job))))
            CatalogActiveIngestResult.NoActiveIngest -> ResponseEntity.noContent().build()
            CatalogActiveIngestResult.EntryNotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/ingests/{jobId}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getIngest(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable jobId: String,
        exchange: ServerWebExchange,
    ): CatalogIngestJobHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        authorizeMembership(workspace, exchange)
        val view = catalogIngest.findJob(workspace, parseJobId(jobId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (view.job.connectorId != connector) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return view.toHttpView(requesterNames(listOf(view.job)))
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/ingests/{jobId}/errors",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun listIngestErrors(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable jobId: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
        exchange: ServerWebExchange,
    ): CatalogIngestErrorPageHttpView {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        authorizeMembership(workspace, exchange)
        if (page < 1 || pageSize !in 1..200) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ingest error page")
        }
        return catalogIngest.findErrors(workspace, connector, parseJobId(jobId), page, pageSize)?.toHttpView()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @PostMapping(
        "/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}/ingests/{jobId}:cancel",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun cancelIngest(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable jobId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<CatalogIngestJobHttpView> {
        val workspace = parseWorkspaceId(workspaceId)
        val connector = parseConnectorId(connectorId)
        val authorization = authorizeCatalogIngestion(workspace, exchange)
        val result = catalogIngest.cancelIngest(
            CatalogIngestCancelCommand(
                authorization = authorization,
                connectorId = connector,
                jobId = parseJobId(jobId),
                reasonCode = "USER_REQUESTED",
            ),
        )
        val view = result.acceptedOrThrow()
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view.toHttpView(requesterNames(listOf(view.job))))
    }

    private suspend fun requesterNames(jobs: List<CatalogIngestJob>): CatalogRequesters = CatalogRequesters(
        names = if (jobs.isEmpty()) emptyMap() else principalDisplayNames.read(jobs.map { it.requestedBy.value }.toSet()),
        apps = agentApps.responses(jobs.map { it.agentClientId }),
    )

    private suspend fun authorizeMutation(
        workspace: WorkspaceId,
        exchange: ServerWebExchange,
    ) = when (val decision = controlAdmissionFacade.authorizeMutation(
        MutationAdmissionRequest(
            common = AdmissionRequest(
                workspaceId = workspace,
                credential = requireBearerCredential(exchange),
                expectedOAuthResource = null,
            ),
            intent = MutationIntent.CATALOG_SELECTION_UPDATE,
        ),
    )) {
        is ControlAdmissionDecision.Admitted -> decision.context
        is ControlAdmissionDecision.Denied -> throw decision.toConnectorCatalogProblem()
    }

    private suspend fun authorizeCatalogIngestion(
        workspace: WorkspaceId,
        exchange: ServerWebExchange,
    ) = when (val decision = controlAdmissionFacade.authorizeExecution(
        ExecutionAdmissionRequest(
            workspaceId = workspace,
            credential = requireBearerCredential(exchange),
            expectedOAuthResource = null,
            intent = ExecutionIntent.CATALOG_INGEST,
        ),
    )) {
        is ControlAdmissionDecision.Admitted -> decision.context
        is ControlAdmissionDecision.Denied -> throw decision.toConnectorCatalogProblem()
    }

    private suspend fun authorizeMembership(
        workspace: WorkspaceId,
        exchange: ServerWebExchange,
    ) = when (val decision = controlAdmissionFacade.authorizeWorkspaceRole(
        WorkspaceRoleAdmissionRequest(
            workspaceId = workspace,
            credential = requireBearerCredential(exchange),
            expectedOAuthResource = null,
                    requiredRole = cc.monomer.identity.api.WorkspaceRole.EXPLORER,
        ),
    )) {
        is ControlAdmissionDecision.Admitted -> Unit
        is ControlAdmissionDecision.Denied -> throw decision.toConnectorCatalogProblem()
    }

    data class CatalogSelectionHttpRequest(val selectedSchemas: List<SelectedSchemaRequest>) {
        fun toDomain(): CatalogSelection {
            require(selectedSchemas.map { it.schemaName }.distinct().size == selectedSchemas.size) {
                "selected schema names must be unique"
            }
            return CatalogSelection(
                selectedSchemas = selectedSchemas.map { it.toDomain() }.toSet(),
            )
        }
    }

    data class ConnectorCatalogSelectionHttpRequest(val selectedCatalogNames: List<String>) {
        fun toDomain(): ConnectorCatalogSelection {
            require(selectedCatalogNames.distinct().size == selectedCatalogNames.size) {
                "selected Catalog names must be unique"
            }
            return ConnectorCatalogSelection(selectedCatalogNames.toSet())
        }
    }

    data class SelectedSchemaRequest(
        val schemaName: String,
        val relations: RelationSelectionRequest,
    ) {
        fun toDomain() = CatalogSelectedSchema(
            schemaName = schemaName,
            relations = relations.toDomain(),
        )
    }

    data class RelationSelectionRequest(val kind: String, val relationNames: List<String>? = null) {
        fun toDomain() = when (kind.trim().uppercase()) {
            "ALL_RELATIONS" -> {
                require(relationNames == null) { "ALL_RELATIONS must not have relationNames" }
                CatalogRelationSelection.AllRelations
            }
            "SELECTED_RELATIONS" -> {
                val names = requireNotNull(relationNames) { "SELECTED_RELATIONS requires relationNames" }
                require(names.distinct().size == names.size) { "selected relation names must be unique" }
                CatalogRelationSelection.SelectedRelations(names.toSet())
            }
            else -> throw IllegalArgumentException("unsupported relation selection kind: $kind")
        }
    }

    data class CatalogIngestHttpRequest(
        val entryKind: String,
        val catalogName: String?,
    ) {
        fun toEntry(connectorId: ConnectorId): CatalogIngestEntry = when (entryKind.trim().uppercase()) {
            "CONNECTOR" -> {
                require(catalogName == null) { "connector entry must not have catalogName" }
                CatalogIngestEntry.Connector(connectorId)
            }
            "CATALOG" -> CatalogIngestEntry.Catalog(connectorId, requireNotNull(catalogName))
            else -> throw IllegalArgumentException("unsupported entryKind: $entryKind")
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class CatalogSelectionHttpView(
        val selectedSchemas: List<SelectedSchemaView>,
        val committedAt: Instant?,
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ConnectorCatalogSelectionHttpView(
        val selectedCatalogNames: List<String>,
        val committedAt: Instant?,
    )

    data class SelectedSchemaView(
        val schemaName: String,
        val relations: RelationSelectionView,
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class RelationSelectionView(
        val kind: String,
        val relationNames: List<String>?,
    )

    data class CatalogListRefreshHttpRequest(
        val parentKind: String,
        val catalogName: String?,
        val schemaName: String?,
    ) {
        fun toParent(): CatalogListRefreshParent = when (parentKind.trim().uppercase()) {
            "CONNECTOR" -> {
                require(catalogName == null && schemaName == null) {
                    "connector parent must not have catalogName or schemaName"
                }
                CatalogListRefreshParent.Connector
            }
            "CATALOG" -> {
                require(schemaName == null) { "catalog parent must not have schemaName" }
                CatalogListRefreshParent.Catalog(requireNotNull(catalogName))
            }
            "SCHEMA" -> CatalogListRefreshParent.Schema(catalogName, requireNotNull(schemaName))
            else -> throw IllegalArgumentException("unsupported parentKind: $parentKind")
        }
    }

    data class CatalogListRefreshJobHttpView(
        val jobId: String,
        val parentKind: String,
        val childKind: String,
        val catalogName: String?,
        val schemaName: String?,
        val state: String,
        val requestedAt: Instant,
        val terminalAt: Instant?,
        val failureCode: String?,
        val safeMessage: String?,
    )

    data class CatalogListOptionPageHttpView(
        val parentKind: String,
        val childKind: String,
        val catalogName: String?,
        val schemaName: String?,
        val fetchedAt: Instant?,
        val options: List<CatalogListOptionHttpView>,
        val totalCount: Int,
        val page: Int,
        val pageSize: Int,
        val latestJob: CatalogListRefreshJobHttpView?,
    )

    data class CatalogListOptionHttpView(
        val name: String,
        val relationKind: String?,
        val available: Boolean,
    )

    data class CatalogIngestAcceptedHttpView(
        val jobId: String,
        val workspaceId: String,
        val connectorId: String,
        val entryKind: String,
        val catalogName: String?,
    )

    data class CatalogIngestJobHttpView(
        val jobId: String,
        val connectorId: String,
        val state: String,
        val entryKind: String,
        val catalogName: String?,
        val requestedAt: Instant,
        val terminalAt: Instant?,
        val refreshedRelationCount: Int,
        val runningTaskCount: Int,
        val errorCount: Int,
        val requestedBy: CatalogRequesterHttpView,
        val tasks: List<CatalogIngestTaskHttpView>,
    )

    data class CatalogRequesterHttpView(
        val principalId: String,
        val displayName: String?,
        val credentialSurface: String?,
        val agentApp: AgentAppResponse?,
    )

    data class CatalogIngestJobSummaryHttpView(
        val jobId: String,
        val connectorId: String,
        val state: String,
        val entryKind: String,
        val catalogName: String?,
        val requestedAt: Instant,
        val terminalAt: Instant?,
        val refreshedRelationCount: Int,
        val runningTaskCount: Int,
        val errorCount: Int,
        val requestedBy: CatalogRequesterHttpView,
    )

    data class CatalogIngestJobPageHttpView(
        val jobs: List<CatalogIngestJobSummaryHttpView>,
        val totalCount: Int,
        val page: Int,
        val pageSize: Int,
    )

    data class CatalogIngestErrorPageHttpView(
        val errors: List<CatalogIngestTaskHttpView>,
        val totalCount: Int,
        val page: Int,
        val pageSize: Int,
    )

    data class CatalogIngestTaskHttpView(
        val taskId: String,
        val parentTaskId: String?,
        val targetKind: String,
        val catalogName: String?,
        val schemaName: String?,
        val relationName: String?,
        val relationKind: String?,
        val state: String,
        val loading: Boolean,
        val failureCode: String?,
        val safeMessage: String?,
    )

    private companion object {
        fun parseWorkspaceId(raw: String): WorkspaceId = runCatching { WorkspaceId.parse(raw) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid workspace id") }

        fun parseConnectorId(raw: String): ConnectorId = runCatching { ConnectorId(Uuid.parse(raw)) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid connector id") }

        fun parseJobId(raw: String): CatalogIngestJobId = runCatching { CatalogIngestJobId(Uuid.parse(raw)) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid job id") }

        fun parseEntry(connectorId: ConnectorId, catalogName: String?): CatalogIngestEntry = runCatching {
            if (catalogName == null) CatalogIngestEntry.Connector(connectorId)
            else CatalogIngestEntry.Catalog(connectorId, catalogName)
        }.getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid catalogName") }

        fun parseListRefreshJobId(raw: String) = runCatching {
            cc.monomer.catalog.api.CatalogListRefreshJobId(Uuid.parse(raw))
        }.getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid list refresh job id") }

        fun requireBearerCredential(exchange: ServerWebExchange): PresentedCredential =
            when (val bearer = BearerCredentials.fromFirstAuthorizationHeader(exchange.request.headers)) {
                is BearerAuthorization.Presented -> bearer.credential
                BearerAuthorization.Missing, BearerAuthorization.Malformed ->
                    throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required")
            }

        fun jobLocation(started: CatalogIngestStarted) =
            "/v2/workspaces/${started.workspaceId}/catalog/connectors/${started.connectorId}/ingests/${started.jobId}"
    }
}

private fun <T> CatalogCommandResult<T>.acceptedOrThrow(): T = when (this) {
    is CatalogCommandResult.Accepted -> value
    CatalogCommandResult.InvalidAuthorization -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
    CatalogCommandResult.NotFoundOrOutsideWorkspace -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
    CatalogCommandResult.Conflict -> throw ResponseStatusException(HttpStatus.CONFLICT)
    is CatalogCommandResult.ConflictWithReason -> throw reason.toCatalogConflictProblem()
    is CatalogCommandResult.Rejected -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, failures.firstOrNull())
}

private fun CatalogSelectionView.toHttpView() = CatalogAuthorityController.CatalogSelectionHttpView(
    selectedSchemas = selection.selectedSchemas.sortedBy { it.schemaName }.map { selected ->
        CatalogAuthorityController.SelectedSchemaView(
            schemaName = selected.schemaName,
            relations = when (val relations = selected.relations) {
                CatalogRelationSelection.AllRelations ->
                    CatalogAuthorityController.RelationSelectionView("ALL_RELATIONS", null)
                is CatalogRelationSelection.SelectedRelations ->
                    CatalogAuthorityController.RelationSelectionView(
                        "SELECTED_RELATIONS",
                        relations.relationNames.sorted(),
                    )
            },
        )
    },
    committedAt = committedAt,
)

private fun ConnectorCatalogSelectionView.toHttpView() =
    CatalogAuthorityController.ConnectorCatalogSelectionHttpView(
        selectedCatalogNames = selection.selectedCatalogNames.sorted(),
        committedAt = committedAt,
    )

private fun CatalogIngestStarted.toHttpView() = CatalogAuthorityController.CatalogIngestAcceptedHttpView(
    jobId = jobId.toString(),
    workspaceId = workspaceId.toString(),
    connectorId = connectorId.toString(),
    entryKind = when (entry) {
        is CatalogIngestEntry.Connector -> "CONNECTOR"
        is CatalogIngestEntry.Catalog -> "CATALOG"
    },
    catalogName = (entry as? CatalogIngestEntry.Catalog)?.name,
)

/** The people who started jobs and the agent apps that started AGENT jobs. */
private data class CatalogRequesters(
    val names: Map<Uuid, String>,
    val apps: Map<cc.monomer.identity.api.AgentClientId, AgentAppResponse>,
)

private fun CatalogIngestJob.requesterHttpView(requesters: CatalogRequesters) = CatalogAuthorityController.CatalogRequesterHttpView(
    principalId = requestedBy.toString(),
    displayName = requesters.names[requestedBy.value],
    credentialSurface = credentialSurface?.name,
    agentApp = agentClientId?.let(requesters.apps::get),
)

private fun CatalogIngestJobView.toHttpView(requesters: CatalogRequesters) = CatalogAuthorityController.CatalogIngestJobHttpView(
    jobId = job.id.toString(),
    connectorId = job.connectorId.toString(),
    state = job.state.name,
    entryKind = when (job.entry) {
        is CatalogIngestEntry.Connector -> "CONNECTOR"
        is CatalogIngestEntry.Catalog -> "CATALOG"
    },
    catalogName = (job.entry as? CatalogIngestEntry.Catalog)?.name,
    requestedAt = job.requestedAt,
    terminalAt = job.terminalAt,
    refreshedRelationCount = refreshedRelationCount,
    runningTaskCount = runningTaskCount,
    errorCount = errorCount,
    requestedBy = job.requesterHttpView(requesters),
    tasks = tasks.map { task ->
        CatalogAuthorityController.CatalogIngestTaskHttpView(
            taskId = task.id.toString(),
            parentTaskId = task.parentTaskId?.toString(),
            targetKind = when (task.target) {
                CatalogIngestTaskTarget.Connector -> "CONNECTOR"
                is CatalogIngestTaskTarget.Catalog -> "CATALOG"
                is CatalogIngestTaskTarget.Schema -> "SCHEMA"
                is CatalogIngestTaskTarget.Relation -> "RELATION"
            },
            catalogName = task.target.catalog,
            schemaName = task.target.schema,
            relationName = task.target.relation,
            relationKind = (task.target as? CatalogIngestTaskTarget.Relation)?.kind?.name,
            state = task.state.name,
            loading = task.id in loadingTaskIds,
            failureCode = task.failureCode,
            safeMessage = task.safeMessage,
        )
    },
)

private fun CatalogIngestJobSummary.toHttpView(requesters: CatalogRequesters) = CatalogAuthorityController.CatalogIngestJobSummaryHttpView(
    jobId = job.id.toString(),
    connectorId = job.connectorId.toString(),
    state = job.state.name,
    entryKind = when (job.entry) {
        is CatalogIngestEntry.Connector -> "CONNECTOR"
        is CatalogIngestEntry.Catalog -> "CATALOG"
    },
    catalogName = (job.entry as? CatalogIngestEntry.Catalog)?.name,
    requestedAt = job.requestedAt,
    terminalAt = job.terminalAt,
    refreshedRelationCount = refreshedRelationCount,
    runningTaskCount = runningTaskCount,
    errorCount = errorCount,
    requestedBy = job.requesterHttpView(requesters),
)

private fun CatalogIngestJobPage.toHttpView(requesters: CatalogRequesters) = CatalogAuthorityController.CatalogIngestJobPageHttpView(
    jobs = jobs.map { it.toHttpView(requesters) },
    totalCount = totalCount,
    page = page,
    pageSize = pageSize,
)

private fun CatalogIngestErrorPage.toHttpView() = CatalogAuthorityController.CatalogIngestErrorPageHttpView(
    errors = errors.map { task ->
        CatalogAuthorityController.CatalogIngestTaskHttpView(
            taskId = task.id.toString(),
            parentTaskId = task.parentTaskId?.toString(),
            targetKind = when (task.target) {
                CatalogIngestTaskTarget.Connector -> "CONNECTOR"
                is CatalogIngestTaskTarget.Catalog -> "CATALOG"
                is CatalogIngestTaskTarget.Schema -> "SCHEMA"
                is CatalogIngestTaskTarget.Relation -> "RELATION"
            },
            catalogName = task.target.catalog,
            schemaName = task.target.schema,
            relationName = task.target.relation,
            relationKind = (task.target as? CatalogIngestTaskTarget.Relation)?.kind?.name,
            state = task.state.name,
            loading = false,
            failureCode = task.failureCode,
            safeMessage = task.safeMessage,
        )
    },
    totalCount = totalCount,
    page = page,
    pageSize = pageSize,
)

private fun cc.monomer.catalog.application.ingest.CatalogListRefreshJobView.toHttpView() =
    CatalogAuthorityController.CatalogListRefreshJobHttpView(
        jobId = job.id.toString(),
        parentKind = job.target.parentKind.name,
        childKind = job.target.childKind.name,
        catalogName = job.target.catalogName,
        schemaName = job.target.schemaName,
        state = job.state.name,
        requestedAt = job.requestedAt,
        terminalAt = job.terminalAt,
        failureCode = job.failureCode,
        safeMessage = job.safeMessage,
    )

private fun cc.monomer.catalog.application.ingest.CatalogListOptionPageView.toHttpView() =
    CatalogAuthorityController.CatalogListOptionPageHttpView(
        parentKind = page.target.parentKind.name,
        childKind = page.target.childKind.name,
        catalogName = page.target.catalogName,
        schemaName = page.target.schemaName,
        fetchedAt = page.fetchedAt,
        options = page.options.map { option ->
            CatalogAuthorityController.CatalogListOptionHttpView(
                name = option.name,
                relationKind = option.relationKind?.name,
                available = option.available,
            )
        },
        totalCount = page.totalCount,
        page = page.page,
        pageSize = page.pageSize,
        latestJob = page.latestJob?.let {
            cc.monomer.catalog.application.ingest.CatalogListRefreshJobView(it).toHttpView()
        },
    )
