@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentJsonPatchOp
import cc.monomer.agent.api.AgentJsonPatchOperation
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceOperation
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogConflictReason
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogIngestStarted
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionView
import cc.monomer.catalog.api.ConnectorCatalogSelection
import cc.monomer.catalog.api.ConnectorCatalogSelectionView
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.catalog.application.CatalogBrowseUseCase
import cc.monomer.catalog.application.CatalogNotFoundException
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListOptionReadView
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.control.connector.application.ConnectorNotFoundException
import cc.monomer.control.connector.application.ConnectorView
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobSummary
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

class AgentCatalogOwnerAdapterTest {
    private val connectorId = Uuid.parse("01942b04-6e00-7000-8000-000000000010")
    private val workspaceId = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))

    @Test
    fun `time cursor rejects non v7 identity before owner history access`() = kotlinx.coroutines.test.runTest {
        val collection = path("workspace/connectors/$connectorId/catalog/ingestions")
        listOf("01942b04-6e00-4000-8000-000000000010", "00000000-0000-0000-0000-000000000000").forEach { id ->
            val cursor = binaryCursor('T', collection.value, "2026-09-05T00:00:00Z", id)
            val outcome = adapter().list(caller(), AgentListRequest(collection, cursor, 20))
            assertEquals("CURSOR_INVALID", assertIs<AgentListingOutcome.Rejected>(outcome).problem.code)
        }
    }

    @Test
    fun `malformed cursor is rejected before owner access`() = kotlinx.coroutines.test.runTest {
        val outcome = adapter().list(
            caller(),
            AgentListRequest(path("workspace/connectors/$connectorId/catalog/schemas"), "not-a-cursor", 1),
        )

        assertEquals("CURSOR_INVALID", assertIs<AgentListingOutcome.Rejected>(outcome).problem.code)
    }

    @Test
    fun `time cursor is rejected by a name ordered collection`() = kotlinx.coroutines.test.runTest {
        val collection = path("workspace/connectors/$connectorId/catalog/schemas")
        val cursor = binaryCursor('T', collection.value, "2026-09-05T00:00:00Z", connectorId.toString())

        val outcome = adapter(admission = admittedAdmission()).list(
            caller(),
            AgentListRequest(collection, cursor, 20),
        )

        assertEquals("CURSOR_INVALID", assertIs<AgentListingOutcome.Rejected>(outcome).problem.code)
    }

    @Test
    fun `exact ingestion names its requester, surface, and agent app as the Web does`() = kotlinx.coroutines.test.runTest {
        val connector = ConnectorId(connectorId)
        val entry = CatalogIngestEntry.Connector(connector)
        val mina = PrincipalId(FixedPrincipalDisplayNames.MINA)
        val claude = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
        val unnamed = PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000002"))
        data class Row(val requester: PrincipalId, val surface: cc.monomer.identity.api.CredentialSurface?, val client: cc.monomer.identity.api.AgentClientId?)
        for (row in listOf(
            Row(mina, cc.monomer.identity.api.CredentialSurface.WEB, null),
            Row(mina, cc.monomer.identity.api.CredentialSurface.AGENT, claude),
            Row(unnamed, cc.monomer.identity.api.CredentialSurface.AGENT, null),
            Row(unnamed, null, null),
        )) {
            val job = CatalogIngestJob.request(
                id = CatalogIngestJobId(Uuid.parse("01942b04-6e00-7000-8000-000000000020")),
                workspaceId = workspaceId,
                connectorId = connector,
                selection = CatalogSelection(emptySet()),
                entry = entry,
                requestedBy = row.requester,
                requestedAt = Instant.parse("2026-09-05T00:00:00Z"),
                credentialSurface = row.surface,
                agentClientId = row.client,
            )
            val ingest = mockk<CatalogIngestApplicationPort>()
            coEvery { ingest.findJobForEntry(workspaceId, connector, entry, job.id) } returns
                cc.monomer.catalog.application.ingest.CatalogIngestJobView(job, 0, 0, 0, emptyList(), emptySet())
            val resource = assertIs<AgentResourceOutcome.Found>(
                adapter(ingest = ingest, admission = admittedAdmission()).read(
                    caller(), AgentReadRequest(path("workspace/connectors/$connectorId/catalog/ingestions/${job.id.value}"), null),
                ),
            ).resource
            assertEquals(
                cc.monomer.agent.api.AgentCatalogRequester(
                    principalId = row.requester.toString(),
                    displayName = if (row.requester == mina) "Mina Park" else null,
                    credentialSurface = row.surface,
                    agentApp = if (row.client == claude) cc.monomer.agent.api.AgentApp(claude.value, "Claude", "claude.ai") else null,
                ),
                assertIs<cc.monomer.agent.api.AgentCatalogJobResourceBody>(resource.body).requestedBy,
                "$row",
            )
        }
    }

    @Test
    fun `exact ingestion from another parent is not found`() = kotlinx.coroutines.test.runTest {
        val admission = admittedAdmission()
        val ingest = mockk<CatalogIngestApplicationPort>()
        val jobId = CatalogIngestJobId(Uuid.parse("01942b04-6e00-7000-8000-000000000020"))
        val connector = ConnectorId(connectorId)
        coEvery {
            ingest.findJobForEntry(
                workspaceId,
                connector,
                CatalogIngestEntry.Connector(connector),
                jobId,
            )
        } returns null

        val outcome = adapter(ingest = ingest, admission = admission).read(
            caller(),
            AgentReadRequest(path("workspace/connectors/$connectorId/catalog/ingestions/${jobId.value}"), null),
        )

        assertIs<AgentResourceOutcome.NotFound>(outcome)
    }

    @Test
    fun `selection conflict is mapped to a conflict problem`() = kotlinx.coroutines.test.runTest {
        val admission = admittedAdmission()
        val ingest = mockk<CatalogIngestApplicationPort>()
        coEvery { ingest.saveSelection(any()) } returns CatalogCommandResult.Conflict

        val request = AgentEditRequest(
            path("workspace/connectors/$connectorId/catalog/selection"),
            null,
            listOf(
                AgentJsonPatchOperation(
                    AgentJsonPatchOp.REPLACE,
                    "",
                    Json.parseToJsonElement("{\"selectedSchemas\":[]}"),
                ),
            ),
            commandId = null,
            aggregateMetrics = null,
        )
        val outcome = adapter(ingest = ingest, admission = admission).edit(caller(), request)

        assertEquals("CATALOG_CONFLICT", assertIs<AgentActionOutcome.Rejected>(outcome).problem.code)
    }

    @Test
    fun `selection conflict responses preserve every Problem field for all reasons`() = kotlinx.coroutines.test.runTest {
        val admission = admittedAdmission()
        val ingest = mockk<CatalogIngestApplicationPort>()
        val request = AgentEditRequest(
            path("workspace/connectors/$connectorId/catalog/selection"),
            null,
            listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "", Json.parseToJsonElement("{\"selectedSchemas\":[]}"))),
            commandId = null,
            aggregateMetrics = null,
        )
        for (reason in CatalogConflictReason.entries) {
            coEvery { ingest.saveSelection(any()) } returns CatalogCommandResult.ConflictWithReason(reason)
            val (code, detail) = when (reason) {
                CatalogConflictReason.ACTIVE_INGESTION ->
                    "CATALOG_SELECTION_ACTIVE_INGESTION" to
                        "Finish or cancel the active Catalog ingestion before changing Catalog selection."
                CatalogConflictReason.INVALID_CANDIDATE ->
                    "CATALOG_SELECTION_INVALID_CANDIDATE" to
                        "The Catalog selection is stale or invalid. Refresh the Catalog options and try again."
            }
            val problem = assertIs<AgentActionOutcome.Rejected>(adapter(ingest = ingest, admission = admission).edit(caller(), request)).problem
            assertEquals(
                AgentWorkspaceProblem(
                    code = code,
                    title = "Catalog request conflicts with current state",
                    detail = detail,
                    status = AgentWorkspaceProblemStatus.CONFLICT,
                    retryable = false,
                    currentRevision = null,
                    violations = emptyList(),
                    hints = emptyList(),
                    nextActions = emptyList(),
                    retryAfterSeconds = null,
                    roleRequirement = null,
                    partitionFilters = emptyList(),
                ),
                problem,
                reason.name,
            )
        }
    }

    @Test
    fun `selection edits other than one root replace are invalid patches`() = kotlinx.coroutines.test.runTest {
        val request = AgentEditRequest(
            path("workspace/connectors/$connectorId/catalog/selection"),
            null,
            listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/selectedSchemas", Json.parseToJsonElement("[]"))),
            commandId = null,
            aggregateMetrics = null,
        )
        val problem = assertIs<AgentActionOutcome.Rejected>(adapter().edit(caller(), request)).problem
        assertEquals("PATCH_INVALID", problem.code)
        assertEquals(AgentWorkspaceProblemStatus.BAD_REQUEST, problem.status)
        assertEquals("PATCH_OPERATION_INVALID", problem.violations.single().code)
    }

    @Test
    fun `catalog ingest requires execution admission`() = kotlinx.coroutines.test.runTest {
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeExecution(any()) } returns
            ControlAdmissionDecision.Denied(mockk(), mockk())
        val outcome = adapter(admission = admission).invoke(
            caller(),
            AgentInvokeRequest(path("workspace/connectors/$connectorId/catalog"), "ingest", AgentInvokeInput.None),
        )

        assertIs<AgentActionOutcome.Forbidden>(outcome)
    }

    @Test
    fun `catalog ingest admission does not use the Agent request id as an idempotency key`() = kotlinx.coroutines.test.runTest {
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        val connector = ConnectorId(connectorId)
        val jobId = CatalogIngestJobId(Uuid.parse("01942b04-6e00-7000-8000-000000000021"))
        coEvery { ingest.startIngest(any()) } returns CatalogCommandResult.Accepted(
            CatalogIngestStarted(
                jobId = jobId,
                workspaceId = workspaceId,
                connectorId = connector,
                entry = CatalogIngestEntry.Connector(connector),
            ),
        )
        val caller = caller().also {
            every { it.requestId } throws IllegalStateException("Agent request id must not be used for Catalog admission")
        }

        val outcome = adapter(ingest = ingest, admission = admittedAdmission()).invoke(
            caller,
            AgentInvokeRequest(path("workspace/connectors/$connectorId/catalog"), "ingest", AgentInvokeInput.None),
        )

        val completed = assertIs<AgentActionOutcome.Completed>(outcome)
        assertEquals("CATALOG_INGESTION", completed.action.result.kind.name)
        coVerify(exactly = 1) {
            ingest.startIngest(match {
                it.connectorId == connector && it.entry == CatalogIngestEntry.Connector(connector)
            })
        }
    }

    @Test
    fun `catalog selection edit requires mutation admission`() = kotlinx.coroutines.test.runTest {
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Denied(mockk(), mockk())
        val outcome = adapter(admission = admission).edit(
            caller(),
            AgentEditRequest(
                path("workspace/connectors/$connectorId/catalog/selection"),
                null,
                listOf(
                    AgentJsonPatchOperation(
                        AgentJsonPatchOp.REPLACE,
                        "",
                        Json.parseToJsonElement("{\"selectedSchemas\":[]}"),
                    ),
                ),
                commandId = null,
                aggregateMetrics = null,
            ),
        )

        assertIs<AgentActionOutcome.Forbidden>(outcome)
    }

    @Test
    fun `connector depth aliases are not found`() = kotlinx.coroutines.test.runTest {
        val postgres = adapter(admission = admittedAdmission()).read(
            caller(),
            AgentReadRequest(path("workspace/connectors/$connectorId/catalog/catalogs/warehouse"), null),
        )
        val snowflake = adapter(
            admission = admittedAdmission(),
            connectorProductType = ConnectorProductType.SNOWFLAKE,
        ).list(
            caller(),
            AgentListRequest(path("workspace/connectors/$connectorId/catalog/schemas"), null, 20),
        )

        assertIs<AgentResourceOutcome.NotFound>(postgres)
        assertIs<AgentListingOutcome.NotFound>(snowflake)
    }

    @Test
    fun `missing connector and unavailable Catalog owner stay distinct`() = kotlinx.coroutines.test.runTest {
        val missingConnectorRead = mockk<ConnectorReadUseCase>()
        coEvery { missingConnectorRead.get(workspaceId.toString(), connectorId.toString()) } throws
            ConnectorNotFoundException("missing")
        val missing = adapter(
            connectorRead = missingConnectorRead,
            admission = admittedAdmission(),
            configureConnectorRead = false,
        ).read(
            caller(),
            AgentReadRequest(path("workspace/connectors/$connectorId/catalog"), null),
        )

        val browse = mockk<CatalogBrowseUseCase>()
        coEvery {
            browse.listSchemasAfter(workspaceId.toString(), connectorId.toString(), null, null, 21)
        } throws IllegalStateException("database unavailable")
        val unavailable = adapter(browse = browse, admission = admittedAdmission()).list(
            caller(),
            AgentListRequest(path("workspace/connectors/$connectorId/catalog/schemas"), null, 20),
        )

        assertIs<AgentResourceOutcome.NotFound>(missing)
        assertEquals("OWNER_UNAVAILABLE", assertIs<AgentListingOutcome.Rejected>(unavailable).problem.code)
    }

    @Test
    fun `missing Relation and unavailable Relation owner stay distinct`() = kotlinx.coroutines.test.runTest {
        val relationPath = path("workspace/connectors/$connectorId/catalog/schemas/public/relations/orders")
        val missingBrowse = mockk<CatalogBrowseUseCase>()
        coEvery {
            missingBrowse.getRelation(workspaceId.toString(), connectorId.toString(), null, "public", "orders")
        } throws CatalogNotFoundException("missing")
        val missing = adapter(browse = missingBrowse, admission = admittedAdmission()).read(
            caller(),
            AgentReadRequest(relationPath, null),
        )

        val unavailableBrowse = mockk<CatalogBrowseUseCase>()
        coEvery {
            unavailableBrowse.getRelation(workspaceId.toString(), connectorId.toString(), null, "public", "orders")
        } throws IllegalStateException("database unavailable")
        val unavailable = adapter(browse = unavailableBrowse, admission = admittedAdmission()).read(
            caller(),
            AgentReadRequest(relationPath, null),
        )

        assertIs<AgentResourceOutcome.NotFound>(missing)
        assertEquals("OWNER_UNAVAILABLE", assertIs<AgentResourceOutcome.Rejected>(unavailable).problem.code)
    }

    @Test
    fun `postgres catalog root is readable with a null envelope body`() = kotlinx.coroutines.test.runTest {
        val admission = admittedAdmission()
        val outcome = adapter(admission = admission).read(
            caller(),
            AgentReadRequest(path("workspace/connectors/$connectorId/catalog"), null),
        )

        val resource = assertIs<AgentResourceOutcome.Found>(outcome).resource
        assertEquals(null, resource.body)
        assertEquals("CATALOG_ROOT", resource.kind.name)
        assertEquals(false, resource.availableOperations.any { (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action == "ingest" })
    }

    @Test
    fun `selected three depth catalog with encoded name exists before its first ingestion`() = kotlinx.coroutines.test.runTest {
        val connector = ConnectorId(connectorId)
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        coEvery { ingest.currentConnectorCatalogSelection(workspaceId, connector) } returns ConnectorCatalogSelectionView(
            workspaceId,
            connector,
            ConnectorCatalogSelection(setOf("finance/archive")),
            null,
        )

        val outcome = adapter(
            ingest = ingest,
            admission = admittedAdmission(),
            connectorProductType = ConnectorProductType.SNOWFLAKE,
        ).read(
            caller(),
            AgentReadRequest(path("workspace/connectors/$connectorId/catalog/catalogs/finance~1archive"), null),
        )

        val resource = assertIs<AgentResourceOutcome.Found>(outcome).resource
        assertEquals("CATALOG", resource.kind.name)
        assertEquals(null, resource.body)
        assertEquals(false, resource.availableOperations.any { (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action == "ingest" })
    }

    @Test
    fun `unfetched option exact read returns refresh recovery action`() = kotlinx.coroutines.test.runTest {
        val connector = ConnectorId(connectorId)
        val refresh = mockk<CatalogListRefreshApplicationPort>()
        coEvery {
            refresh.findListOption(
                workspaceId,
                connector,
                cc.monomer.catalog.application.ingest.CatalogListRefreshParent.Connector,
                "public",
            )
        } returns CatalogListOptionReadView(null, null)

        val outcome = adapter(refresh = refresh, admission = admittedAdmission()).read(
            caller(),
            AgentReadRequest(path("workspace/connectors/$connectorId/catalog/selection/options/schemas/public"), null),
        )

        val problem = assertIs<AgentResourceOutcome.Rejected>(outcome).problem
        assertEquals("CATALOG_SELECTION_OPTIONS_NOT_FETCHED", problem.code)
        assertEquals("refresh", assertIs<cc.monomer.agent.api.AgentAvailableOperation.Invoke>(problem.nextActions.single()).action)
    }

    @Test
    fun `empty selection keeps ingestion history list available without advertising ingest`() = kotlinx.coroutines.test.runTest {
        val connector = ConnectorId(connectorId)
        val entry = CatalogIngestEntry.Connector(connector)
        val selection = CatalogSelection(emptySet())
        val historicalSelection = CatalogSelection(
            setOf(CatalogSelectedSchema("public", CatalogRelationSelection.AllRelations)),
        )
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        val browse = mockk<CatalogBrowseUseCase>(relaxed = true)
        coEvery { ingest.currentSelection(workspaceId, connector, entry) } returns selectionView(entry, selection)
        coEvery { ingest.findJobsAfter(workspaceId, connector, entry, null, 2) } returns listOf(historySummary(entry, historicalSelection))
        val adapter = adapter(
            browse = browse,
            ingest = ingest,
            admission = admittedAdmission(),
        )
        val selectionPath = path("workspace/connectors/$connectorId/catalog/selection")
        val historyPath = path("workspace/connectors/$connectorId/catalog/ingestions")

        val resource = assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), AgentReadRequest(selectionPath, null))).resource
        assertEquals(true, resource.availableOperations.any { it.operation == AgentWorkspaceOperation.LIST && it.path == historyPath })
        assertEquals(false, resource.availableOperations.any { (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action == "ingest" })

        val history = assertIs<AgentListingOutcome.Found>(adapter.list(caller(), AgentListRequest(historyPath, null, 1))).listing
        assertEquals(1, history.items.size)
        assertEquals("CATALOG_INGESTION", history.items.single().kind.name)
        coVerify(exactly = 0) { ingest.startIngest(any()) }
        coVerify(exactly = 0) { browse.listSchemasAfter(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `empty selection discovers ingestion history before the first ingestion`() = kotlinx.coroutines.test.runTest {
        val connector = ConnectorId(connectorId)
        val entry = CatalogIngestEntry.Connector(connector)
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        coEvery { ingest.currentSelection(workspaceId, connector, entry) } returns selectionView(entry, CatalogSelection(emptySet()))
        coEvery { ingest.findJobsAfter(workspaceId, connector, entry, null, 2) } returns emptyList()
        val adapter = adapter(ingest = ingest, admission = admittedAdmission())
        val historyPath = path("workspace/connectors/$connectorId/catalog/ingestions")
        val resource = assertIs<AgentResourceOutcome.Found>(
            adapter.read(caller(), AgentReadRequest(path("workspace/connectors/$connectorId/catalog/selection"), null)),
        ).resource
        assertEquals(true, resource.availableOperations.any { it.operation == AgentWorkspaceOperation.LIST && it.path == historyPath })
        assertEquals(false, resource.availableOperations.any { (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action == "ingest" })
        val history = assertIs<AgentListingOutcome.Found>(adapter.list(caller(), AgentListRequest(historyPath, null, 1))).listing
        assertEquals(emptyList(), history.items)
        assertEquals(null, history.nextCursor)
        coVerify(exactly = 0) { ingest.startIngest(any()) }
    }

    @Test
    fun `selected three depth catalog advertises ingestion beside its history`() = kotlinx.coroutines.test.runTest {
        val connector = ConnectorId(connectorId)
        val entry = CatalogIngestEntry.Catalog(connector, "warehouse")
        val selection = CatalogSelection(
            setOf(CatalogSelectedSchema("public", CatalogRelationSelection.AllRelations)),
        )
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        coEvery { ingest.currentSelection(workspaceId, connector, entry) } returns selectionView(entry, selection)
        coEvery { ingest.findJobsAfter(workspaceId, connector, entry, null, 2) } returns listOf(historySummary(entry, selection))
        val adapter = adapter(
            ingest = ingest,
            admission = admittedAdmission(),
            connectorProductType = ConnectorProductType.SNOWFLAKE,
        )
        val selectionPath = path("workspace/connectors/$connectorId/catalog/catalogs/warehouse/selection")
        val historyPath = path("workspace/connectors/$connectorId/catalog/catalogs/warehouse/ingestions")

        val resource = assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), AgentReadRequest(selectionPath, null))).resource
        assertEquals(true, resource.availableOperations.any { it.operation == AgentWorkspaceOperation.LIST && it.path == historyPath })
        assertEquals(true, resource.availableOperations.any { (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action == "ingest" && it.path == path("workspace/connectors/$connectorId/catalog/catalogs/warehouse") })

        val history = assertIs<AgentListingOutcome.Found>(adapter.list(caller(), AgentListRequest(historyPath, null, 1))).listing
        assertEquals(1, history.items.size)
        assertEquals(true, history.items.single().path.value.startsWith("${historyPath.value}/"))
        coVerify { ingest.currentSelection(workspaceId, connector, entry) }
    }

    @Test
    fun `execution capability denial keeps ingestion history readable without mutation or warehouse work`() = kotlinx.coroutines.test.runTest {
        val connector = ConnectorId(connectorId)
        val entry = CatalogIngestEntry.Catalog(connector, "warehouse")
        val selection = CatalogSelection(
            setOf(CatalogSelectedSchema("public", CatalogRelationSelection.AllRelations)),
        )
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        val browse = mockk<CatalogBrowseUseCase>(relaxed = true)
        coEvery { ingest.currentSelection(workspaceId, connector, entry) } returns selectionView(entry, selection)
        coEvery { ingest.findJobsAfter(workspaceId, connector, entry, null, 2) } returns listOf(historySummary(entry, selection))
        val admission = admittedAdmission().also {
            coEvery { it.authorizeExecution(any()) } returns ControlAdmissionDecision.Denied(mockk(), mockk())
        }
        val adapter = adapter(
            browse = browse,
            ingest = ingest,
            admission = admission,
            connectorProductType = ConnectorProductType.SNOWFLAKE,
        )
        val selectionPath = path("workspace/connectors/$connectorId/catalog/catalogs/warehouse/selection")
        val historyPath = path("workspace/connectors/$connectorId/catalog/catalogs/warehouse/ingestions")

        val resource = assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), AgentReadRequest(selectionPath, null))).resource
        assertEquals(true, resource.availableOperations.any { it.operation == AgentWorkspaceOperation.LIST && it.path == historyPath })
        assertEquals(false, resource.availableOperations.any { (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action == "ingest" })

        val history = assertIs<AgentListingOutcome.Found>(adapter.list(caller(), AgentListRequest(historyPath, null, 1))).listing
        assertEquals(1, history.items.size)
        assertEquals(emptyList(), history.availableOperations)
        coVerify(exactly = 0) { ingest.startIngest(any()) }
        coVerify(exactly = 0) { ingest.saveSelection(any()) }
        coVerify(exactly = 0) { browse.listSchemasAfter(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `refresh paths preserve catalog and schema names equal to path vocabulary`() = kotlinx.coroutines.test.runTest {
        val connector = ConnectorId(connectorId)
        val refreshId = cc.monomer.catalog.api.CatalogListRefreshJobId(
            Uuid.parse("01942b04-6e00-7000-8000-000000000030"),
        )
        val refreshView = mockk<cc.monomer.catalog.application.ingest.CatalogListRefreshJobView>()
        every { refreshView.job.id } returns refreshId
        val refresh = mockk<CatalogListRefreshApplicationPort>()
        coEvery { refresh.startListRefresh(any()) } returns CatalogCommandResult.Accepted(refreshView)
        val names = listOf("options", "option-refreshes", "a/b~1😀|%2F")
        val ingest = mockk<CatalogIngestApplicationPort>()
        coEvery { ingest.currentConnectorCatalogSelection(workspaceId, connector) } returns ConnectorCatalogSelectionView(
            workspaceId, connector, ConnectorCatalogSelection(names.toSet()), null,
        )
        names.forEach { name ->
            val encodedName = AgentResourcePath.encodeNameSegment(name)
            val catalogRoot = "workspace/connectors/$connectorId/catalog"
            listOf(
                Triple(ConnectorProductType.POSTGRESQL, "$catalogRoot/selection", "schemas/$encodedName/relations"),
                Triple(ConnectorProductType.SNOWFLAKE, "$catalogRoot/catalog-selection", "catalogs"),
                Triple(ConnectorProductType.SNOWFLAKE, "$catalogRoot/catalogs/$encodedName/selection", "schemas"),
                Triple(ConnectorProductType.SNOWFLAKE, "$catalogRoot/catalogs/$encodedName/selection", "schemas/$encodedName/relations"),
            ).forEach { (type, selection, child) ->
                val request = AgentInvokeRequest(path("$selection/options/$child"), "refresh", AgentInvokeInput.None)
                val owner = adapter(
                    ingest = ingest,
                    refresh = refresh,
                    admission = admittedAdmission(),
                    connectorProductType = type,
                )
                val action = assertIs<AgentActionOutcome.Completed>(owner.invoke(caller(), request)).action
                assertEquals(path("$selection/option-refreshes/$child/${refreshId.value}"), action.result.path)
                assertEquals(cc.monomer.agent.api.AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH, action.result.kind)
                assertEquals(true, cc.monomer.agent.api.AgentWorkspaceResponseRelations.matches(request, action))
                val parent = when {
                    child == "catalogs" -> cc.monomer.catalog.application.ingest.CatalogListRefreshParent.Connector
                    child == "schemas" -> cc.monomer.catalog.application.ingest.CatalogListRefreshParent.Catalog(name)
                    else -> cc.monomer.catalog.application.ingest.CatalogListRefreshParent.Schema(name.takeIf { type == ConnectorProductType.SNOWFLAKE }, name)
                }
                coVerify { refresh.startListRefresh(match { it.connectorId == connector && it.parent == parent }) }
                coEvery { refresh.findListRefreshJob(workspaceId, connector, parent, refreshId) } returns refreshView
                coEvery { refresh.cancelListRefresh(any()) } returns CatalogCommandResult.Accepted(refreshView)
                val cancelRequest = AgentInvokeRequest(action.result.path, "cancel", AgentInvokeInput.None)
                val cancelled = assertIs<AgentActionOutcome.Completed>(owner.invoke(caller(), cancelRequest)).action
                assertEquals(action.result.path, cancelled.result.path)
                coVerify { refresh.cancelListRefresh(match { it.connectorId == connector && it.jobId == refreshId }) }
            }
        }
    }

    @Test
    fun `Catalog Schema and Relation list addresses preserve every owner name`() = kotlinx.coroutines.test.runTest {
        val names = listOf(
            "emoji😀", "한글", "a/b", "a~b", "a~1b", "%2F", "%", "a+b?c#d", "a|b", "quote\"back\\slash",
            ".", "..", "options", "option-refreshes", "selection", "schemas", "relations", "catalogs", "a\u2028b",
        ).sorted()
        val connector = ConnectorId(connectorId)
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        coEvery { ingest.currentConnectorCatalogSelection(workspaceId, connector) } returns ConnectorCatalogSelectionView(
            workspaceId, connector, ConnectorCatalogSelection(names.toSet()), null,
        )
        val browse = mockk<CatalogBrowseUseCase>()
        val catalogAdapter = adapter(browse = browse, ingest = ingest, admission = admittedAdmission(), connectorProductType = ConnectorProductType.SNOWFLAKE)
        val catalogs = assertIs<AgentListingOutcome.Found>(catalogAdapter.list(
            caller(), AgentListRequest(path("workspace/connectors/$connectorId/catalog/catalogs"), null, 100),
        )).listing
        assertEquals(names, catalogs.items.map { assertIs<cc.monomer.agent.api.AgentResourceListingItem.Catalog>(it).name })
        catalogs.items.forEach { item ->
            assertEquals(item.path, assertIs<AgentResourceOutcome.Found>(catalogAdapter.read(caller(), AgentReadRequest(item.path, null))).resource.path)
        }
        listOf(ConnectorProductType.POSTGRESQL, ConnectorProductType.SNOWFLAKE).forEach { type ->
            val catalogName = names.first().takeIf { type == ConnectorProductType.SNOWFLAKE }
            val catalogRoot = "workspace/connectors/$connectorId/catalog" + (catalogName?.let { "/catalogs/${AgentResourcePath.encodeNameSegment(it)}" } ?: "")
            coEvery { browse.listSchemasAfter(workspaceId.toString(), connectorId.toString(), catalogName, null, any()) } returns
                names.map { cc.monomer.catalog.application.SchemaSummary(catalogName, it, null) }
            val owner = adapter(browse = browse, ingest = ingest, admission = admittedAdmission(), connectorProductType = type)
            val schemas = assertIs<AgentListingOutcome.Found>(owner.list(caller(), AgentListRequest(path("$catalogRoot/schemas"), null, 100))).listing
            schemas.items.forEach { item ->
                val schemaName = assertIs<cc.monomer.agent.api.AgentResourceListingItem.Schema>(item).name
                assertEquals(path("$catalogRoot/schemas/${AgentResourcePath.encodeNameSegment(schemaName)}"), item.path)
                val schema = assertIs<AgentResourceOutcome.Found>(owner.read(caller(), AgentReadRequest(item.path, null))).resource
                val relationsPath = schema.availableOperations.single().path
                coEvery { browse.listRelationsAfter(workspaceId.toString(), connectorId.toString(), catalogName, schemaName, null, 101) } returns
                    names.map { cc.monomer.catalog.application.RelationSummary(catalogName, schemaName, it, "TABLE", 0, null) }
                val relations = assertIs<AgentListingOutcome.Found>(owner.list(caller(), AgentListRequest(relationsPath, null, 100))).listing
                relations.items.forEach { relationItem ->
                    val relationName = assertIs<cc.monomer.agent.api.AgentResourceListingItem.Relation>(relationItem).name
                    coEvery { browse.getRelation(workspaceId.toString(), connectorId.toString(), catalogName, schemaName, relationName) } returns relationDetail(catalogName, schemaName, relationName)
                    val relation = assertIs<cc.monomer.agent.api.AgentResourceEnvelope.Relation>(assertIs<AgentResourceOutcome.Found>(owner.read(caller(), AgentReadRequest(relationItem.path, null))).resource)
                    assertEquals(catalogName, relation.body.catalogName)
                    assertEquals(schemaName, relation.body.schemaName)
                    assertEquals(relationName, relation.body.relationName)
                    assertEquals(connectorId, relation.body.connectorId)
                }
            }
        }
    }

    @Test
    fun `name cursor pages preserve raw names and exact collection scope`() = kotlinx.coroutines.test.runTest {
        val names = listOf(".", "..", "%2F", "a|b", "a~1b", "a/b", "emoji😀", "한글").sorted()
        val connector = ConnectorId(connectorId)
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        coEvery { ingest.currentConnectorCatalogSelection(workspaceId, connector) } returns ConnectorCatalogSelectionView(
            workspaceId, connector, ConnectorCatalogSelection(names.toSet()), null,
        )
        val owner = adapter(ingest = ingest, admission = admittedAdmission(), connectorProductType = ConnectorProductType.SNOWFLAKE)
        val collection = path("workspace/connectors/$connectorId/catalog/catalogs")
        val returnedNames = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = assertIs<AgentListingOutcome.Found>(owner.list(caller(), AgentListRequest(collection, cursor, 1))).listing
            returnedNames += page.items.map { assertIs<cc.monomer.agent.api.AgentResourceListingItem.Catalog>(it).name }
            cursor = page.nextCursor
            cursor?.let {
                assertEquals(2, java.util.Base64.getUrlDecoder().decode(it)[0].toInt())
                val otherPath = path("${collection.value}/${AgentResourcePath.encodeNameSegment(names.first())}/schemas")
                assertEquals("CURSOR_INVALID", assertIs<AgentListingOutcome.Rejected>(owner.list(caller(), AgentListRequest(otherPath, it, 1))).problem.code)
            }
        } while (cursor != null)
        assertEquals(names, returnedNames)
    }

    @Test
    fun `v2 cursors reject old ambiguous malformed and noncanonical payloads`() = kotlinx.coroutines.test.runTest {
        val collection = path("workspace/connectors/$connectorId/catalog/schemas")
        val valid = binaryCursor('N', collection.value, "a|b")
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
        val bytes = java.util.Base64.getUrlDecoder().decode(valid)
        val invalid = listOf(
            encoded.encodeToString("1|N|${collection.value}|public".toByteArray()),
            valid + "=",
            encoded.encodeToString(bytes + byteArrayOf(0)),
            encoded.encodeToString(bytes.dropLast(1).toByteArray()),
            binaryCursor('X', collection.value, "public"),
            binaryCursor('N', collection.value, ""),
            binaryCursor('N', collection.value, " padded"),
            binaryCursor('N', collection.value, "e\u0301"),
            binaryCursor('N', collection.value, "a".repeat(1025)),
            binaryCursor('T', collection.value, "invalid", connectorId.toString()),
            binaryCursor('T', collection.value, "2026-09-05T00:00:00Z", "invalid"),
            encoded.encodeToString(byteArrayOf(2, 'N'.code.toByte(), -1, -1, -1, -1)),
            encoded.encodeToString(bytes.copyOf().also { it[it.lastIndex] = 0xff.toByte() }),
        )
        invalid.forEach { cursor ->
            assertEquals("CURSOR_INVALID", assertIs<AgentListingOutcome.Rejected>(adapter().list(caller(), AgentListRequest(collection, cursor, 1))).problem.code)
        }
    }

    @Test
    fun `maximum length two and three depth option addresses and cursors remain readable`() = kotlinx.coroutines.test.runTest {
        val catalogName = "~".repeat(1024)
        val schemaName = "/".repeat(1024)
        val relationNames = listOf("~".repeat(1024), "😀".repeat(256))
        val connector = ConnectorId(connectorId)
        val ingest = mockk<CatalogIngestApplicationPort>(relaxed = true)
        coEvery { ingest.currentConnectorCatalogSelection(workspaceId, connector) } returns ConnectorCatalogSelectionView(
            workspaceId, connector, ConnectorCatalogSelection(setOf(catalogName)), null,
        )
        listOf(ConnectorProductType.POSTGRESQL, ConnectorProductType.SNOWFLAKE).forEach { type ->
            val selectedCatalog = catalogName.takeIf { type == ConnectorProductType.SNOWFLAKE }
            val parent = cc.monomer.catalog.application.ingest.CatalogListRefreshParent.Schema(selectedCatalog, schemaName)
            val refresh = mockk<CatalogListRefreshApplicationPort>()
            coEvery { refresh.findListOptionsAfter(workspaceId, connector, parent, any(), 2) } answers {
                val after = arg<String?>(3)
                val options = relationNames.filter { after == null || it > after }.map { cc.monomer.catalog.domain.CatalogListOption(it, cc.monomer.catalog.api.RelationKind.TABLE, true) }
                cc.monomer.catalog.application.ingest.CatalogListOptionPageView(mockk<cc.monomer.catalog.domain.CatalogListOptionPage>().also {
                    every { it.fetchedAt } returns Instant.EPOCH
                    every { it.options } returns options
                })
            }
            relationNames.forEach { name ->
                coEvery { refresh.findListOption(workspaceId, connector, parent, name) } returns CatalogListOptionReadView(
                    Instant.EPOCH, cc.monomer.catalog.domain.CatalogListOption(name, cc.monomer.catalog.api.RelationKind.TABLE, true),
                )
            }
            val root = "workspace/connectors/$connectorId/catalog" + (selectedCatalog?.let { "/catalogs/${AgentResourcePath.encodeNameSegment(it)}" } ?: "")
            val collection = path("$root/selection/options/schemas/${AgentResourcePath.encodeNameSegment(schemaName)}/relations")
            val owner = adapter(ingest = ingest, refresh = refresh, admission = admittedAdmission(), connectorProductType = type)
            val first = assertIs<AgentListingOutcome.Found>(owner.list(caller(), AgentListRequest(collection, null, 1))).listing
            val cursor = kotlin.test.assertNotNull(first.nextCursor)
            if (selectedCatalog != null) {
                assertEquals(4208, collection.value.toByteArray().size)
                assertEquals(6257, first.items.single().path.value.toByteArray().size)
                assertEquals(6990, cursor.length)
            }
            val second = assertIs<AgentListingOutcome.Found>(owner.list(caller(), AgentListRequest(collection, cursor, 1))).listing
            assertEquals(null, second.nextCursor)
            (first.items + second.items).forEach { item ->
                assertEquals(item.path, assertIs<AgentResourceOutcome.Found>(owner.read(caller(), AgentReadRequest(item.path, null))).resource.path)
            }
            coVerify { refresh.findListOptionsAfter(workspaceId, connector, parent, relationNames.first(), 2) }
        }
    }

    @Test
    fun `unfetched special-name option list retains exact refresh recovery parent`() = kotlinx.coroutines.test.runTest {
        val name = "a/b~1😀|%2F"
        val connector = ConnectorId(connectorId)
        val parent = cc.monomer.catalog.application.ingest.CatalogListRefreshParent.Schema(name, name)
        val ingest = mockk<CatalogIngestApplicationPort>()
        coEvery { ingest.currentConnectorCatalogSelection(workspaceId, connector) } returns ConnectorCatalogSelectionView(
            workspaceId, connector, ConnectorCatalogSelection(setOf(name)), null,
        )
        val refresh = mockk<CatalogListRefreshApplicationPort>()
        coEvery { refresh.findListOptionsAfter(workspaceId, connector, parent, null, 2) } returns
            cc.monomer.catalog.application.ingest.CatalogListOptionPageView(mockk<cc.monomer.catalog.domain.CatalogListOptionPage>().also {
                every { it.fetchedAt } returns null
            })
        val encoded = AgentResourcePath.encodeNameSegment(name)
        val collection = path("workspace/connectors/$connectorId/catalog/catalogs/$encoded/selection/options/schemas/$encoded/relations")
        val owner = adapter(ingest = ingest, refresh = refresh, admission = admittedAdmission(), connectorProductType = ConnectorProductType.SNOWFLAKE)
        val problem = assertIs<AgentListingOutcome.Rejected>(owner.list(caller(), AgentListRequest(collection, null, 1))).problem
        assertEquals("CATALOG_SELECTION_OPTIONS_NOT_FETCHED", problem.code)
        assertEquals(cc.monomer.agent.api.AgentWorkspaceProblemStatus.CONFLICT, problem.status)
        assertEquals(false, problem.retryable)
        val recovery = assertIs<cc.monomer.agent.api.AgentAvailableOperation.Invoke>(problem.nextActions.single())
        assertEquals(collection, recovery.path)
        assertEquals("refresh", recovery.action)
        coVerify(exactly = 0) { refresh.startListRefresh(any()) }
    }

    @Test
    fun `selection edit and read preserve raw names while cancellation retains exact job identity`() = kotlinx.coroutines.test.runTest {
        val catalog = "a/b~1😀|%2F"
        val schema = "schema/~1😀|%2F"
        val relation = "relation/~1😀|%2F"
        val connector = ConnectorId(connectorId)
        val entry = CatalogIngestEntry.Catalog(connector, catalog)
        val selection = CatalogSelection(setOf(CatalogSelectedSchema(schema, CatalogRelationSelection.SelectedRelations(setOf(relation)))))
        val ingest = mockk<CatalogIngestApplicationPort>()
        coEvery { ingest.saveSelection(any()) } returns CatalogCommandResult.Accepted(selectionView(entry, selection))
        coEvery { ingest.currentSelection(workspaceId, connector, entry) } returns selectionView(entry, selection)
        val owner = adapter(ingest = ingest, admission = admittedAdmission(), connectorProductType = ConnectorProductType.SNOWFLAKE)
        val entryPath = "workspace/connectors/$connectorId/catalog/catalogs/${AgentResourcePath.encodeNameSegment(catalog)}"
        val selectionPath = path("$entryPath/selection")
        val patch = Json.parseToJsonElement("""{"selectedSchemas":[{"schemaName":"$schema","relations":{"kind":"SELECTED_RELATIONS","relationNames":["$relation"]}}]}""")
        assertIs<AgentActionOutcome.Completed>(owner.edit(caller(), AgentEditRequest(selectionPath, null, listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "", patch)), commandId = null, aggregateMetrics = null)))
        coVerify { ingest.saveSelection(match { it.connectorId == connector && it.entry == entry && it.selection == selection }) }
        val resource = assertIs<cc.monomer.agent.api.AgentResourceEnvelope.CatalogSelection>(assertIs<AgentResourceOutcome.Found>(owner.read(caller(), AgentReadRequest(selectionPath, null))).resource)
        assertEquals(schema, resource.body.selectedSchemas.single().schemaName)
        assertEquals(listOf(relation), assertIs<cc.monomer.agent.api.AgentCatalogRelationSelection.SelectedRelations>(resource.body.selectedSchemas.single().relations).relationNames)
        val job = historySummary(entry, selection).job
        val view = cc.monomer.catalog.application.ingest.CatalogIngestJobView(job, 0, 0, 0, emptyList(), emptySet())
        coEvery { ingest.findJobForEntry(workspaceId, connector, entry, job.id) } returns view
        coEvery { ingest.cancelIngest(any()) } returns CatalogCommandResult.Accepted(view)
        val jobPath = path("$entryPath/ingestions/${job.id.value}")
        val read = assertIs<AgentResourceOutcome.Found>(owner.read(caller(), AgentReadRequest(jobPath, null))).resource
        val cancel = read.availableOperations.filterIsInstance<cc.monomer.agent.api.AgentAvailableOperation.Invoke>().single()
        assertEquals(jobPath, cancel.path)
        assertEquals("cancel", cancel.action)
        val outcome = assertIs<AgentActionOutcome.Completed>(owner.invoke(caller(), AgentInvokeRequest(cancel.path, cancel.action, AgentInvokeInput.None)))
        assertEquals(jobPath, outcome.action.result.path)
        coVerify { ingest.cancelIngest(match { it.connectorId == connector && it.jobId == job.id }) }
    }

    private fun binaryCursor(kind: Char, vararg fields: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { output ->
            output.writeByte(2)
            output.writeByte(kind.code)
            fields.forEach { field ->
                val value = field.toByteArray(Charsets.UTF_8)
                output.writeInt(value.size)
                output.write(value)
            }
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    private fun relationDetail(catalog: String?, schema: String, name: String) = cc.monomer.catalog.application.RelationDetail(
        resourceId = Uuid.parse("01942b04-6e00-7000-8000-000000000055"),
        catalogName = catalog, schemaName = schema, name = name, kind = "TABLE",
        viewDefinition = null, columns = emptyList(), partitionKeys = emptyList(), clusteringKeys = emptyList(), requiresPartitionFilter = null,
        primaryKey = null, foreignKeys = emptyList(),
    )

    private fun selectionView(entry: CatalogIngestEntry, selection: CatalogSelection) = CatalogSelectionView(
        workspaceId = workspaceId,
        connectorId = ConnectorId(connectorId),
        entry = entry,
        selection = selection,
        committedAt = null,
    )

    private fun historySummary(entry: CatalogIngestEntry, selection: CatalogSelection): CatalogIngestJobSummary {
        val job = CatalogIngestJob.request(
            id = CatalogIngestJobId(Uuid.parse("01942b04-6e00-7000-8000-000000000020")),
            workspaceId = workspaceId,
            connectorId = ConnectorId(connectorId),
            selection = selection,
            entry = entry,
            requestedBy = PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000002")),
            requestedAt = Instant.parse("2026-09-05T00:00:00Z"),
            credentialSurface = null,
            agentClientId = null,
        )
        return CatalogIngestJobSummary(job, 0, 0, 0)
    }

    private fun adapter(
        browse: CatalogBrowseUseCase = mockk(relaxed = true),
        ingest: CatalogIngestApplicationPort = mockk(relaxed = true),
        refresh: CatalogListRefreshApplicationPort = mockk(relaxed = true),
        connectorRead: ConnectorReadUseCase = mockk(),
        admission: ControlAdmissionFacade = mockk(),
        connectorProductType: ConnectorProductType = ConnectorProductType.POSTGRESQL,
        configureConnectorRead: Boolean = true,
    ): AgentCatalogOwnerAdapter {
        if (configureConnectorRead) {
            coEvery { connectorRead.get(any(), any()) } returns ConnectorView(
                id = connectorId.toString(),
                workspaceId = workspaceId.toString(),
                name = "warehouse",
                type = connectorProductType.name,
                properties = emptyMap(),
                createdAtEpochMs = Instant.EPOCH.toEpochMilli(),
                updatedAtEpochMs = Instant.EPOCH.toEpochMilli(),
                sample = null,
            )
        }
        return AgentCatalogOwnerAdapter(browse, ingest, refresh, connectorRead, admission, fixedAgentProvenanceNames())
    }

    private fun admittedAdmission(): ControlAdmissionFacade = mockk<ControlAdmissionFacade>().also { admission ->
        coEvery { admission.authorizeWorkspaceRole(match { it.requiredRole == WorkspaceRole.EXPLORER }) } returns
            ControlAdmissionDecision.Admitted(mockk<IdentityRoleEvidence>())
        coEvery { admission.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Admitted(mockk<AuthorizedMutationContext>())
        coEvery { admission.authorizeExecution(any()) } returns
            ControlAdmissionDecision.Admitted(mockk<AuthorizedExecutionContext>())
    }

    private fun caller(): AgentWorkspaceCaller = mockk<AgentWorkspaceCaller>().also { caller ->
        every { caller.workspaceId } returns workspaceId
        every { caller.actorPrincipalId } returns "actor-1"
        every { caller.presentedCredential } returns cc.monomer.identity.api.PresentedCredential("agent-token")
        every { caller.expectedOAuthResource } returns cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST
        every { caller.requestId } returns cc.monomer.agent.api.AgentRequestId("srv_test")
    }

    private fun path(value: String) = AgentResourcePath.parse(value)
}
