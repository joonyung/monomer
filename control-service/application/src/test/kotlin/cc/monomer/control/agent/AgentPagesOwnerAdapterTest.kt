@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.control.*
import cc.monomer.control.search.SearchApplicationComposition
import cc.monomer.search.api.SearchIndex
import cc.monomer.control.publicapi.AgentWorkspaceJson
import cc.monomer.control.publicapi.AgentTransportProblems
import cc.monomer.control.publicapi.PublicControlContractValidator
import cc.monomer.dashboard.api.*
import cc.monomer.identity.api.*
import cc.monomer.control.pages.publicpages.PageReaderService
import cc.monomer.control.pagereader.PublicPagesAccess
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.pages.api.*
import cc.monomer.pages.application.*
import cc.monomer.pages.application.content.*
import cc.monomer.pages.domain.*
import cc.monomer.pages.domain.content.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlin.uuid.Uuid

class AgentPagesOwnerAdapterTest {
    private val directory = mockk<PagesDirectoryUseCase>()
    private val content = mockk<PageContentUseCase>()
    private val uploads = mockk<PageContentUploadUseCase>()
    private val dashboard = mockk<DashboardOwnerPort>()
    private val admission = mockk<ControlAdmissionFacade>()
    private val caller = mockk<AgentWorkspaceCaller>()
    private val workspace = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val principal = CanonicalPrincipal(PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000002")), PrincipalKind.USER)
    private val organization = OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000003"))
    private val id = PageId(Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
    private val secondId = PageId(Uuid.parse("01942b04-6e00-7000-8000-000000000011"))
    private val uploadRef = PageContentUploadRef(Uuid.parse("01942b04-6e00-7000-8000-000000000012"))
    private val clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC)
    private val actor = PageActor.create(PageActorKind.AGENT, principal.id.value.toString())
    private val capability = IdentityRoleEvidence(OrganizationPlan.FREE, principal, organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EXPLORER)
    private val membership = IdentityMembershipEvidence(principal, organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EXPLORER)
    private val currentPages = mockk<PagesCurrentReadPort>()
    private val publicPages = mockk<WorkspacePublicPagesQuery>()
    private var insideSnapshot = false
    private val reader = AgentPageReader(PageReaderService(
        PublicPagesAccess(publicPages, admission, object : ConsistentSnapshotTransactionRunner {
            override suspend fun <T> run(action: suspend () -> T): T {
                check(!insideSnapshot)
                insideSnapshot = true
                return try { action() } finally { insideSnapshot = false }
            }
        }), currentPages, dashboard, mockk(), mockk(), publicPages, clock,
    ))
    private val adapter = AgentPagesOwnerAdapter(directory, content, uploads, dashboard, admission, clock, reader, fixedAgentProvenanceNames())
    private val decoder = AgentWorkspaceContractDecoder()
    private val validator = PublicControlContractValidator()
    private val requestId = AgentRequestId("server-trace")

    init {
        coEvery { publicPages.pageReaderName(workspace) } returns "Reports"
        every { caller.role } returns WorkspaceRole.OWNER
        every { caller.workspaceId } returns workspace
        every { caller.actorPrincipalId } returns principal.id.value.toString()
        every { caller.presentedCredential } returns PresentedCredential("fixture-token")
        every { caller.expectedOAuthResource } returns ExpectedOAuthResource.AGENT_REST
        every { caller.requestId } returns requestId
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(membership)
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(capability)
    }

    @Test
    fun `Page search uses current role and the same placed Viewer projection as Pages read`() = runTest {
        val pages = listOf(page(id), page(secondId))
        val current = snapshot(5, pages).copy(placements = listOf(PagePlacement(id, emptyList())))
        coEvery { directory.getProjection(workspace.value) } returns projection(current)
        coEvery { currentPages.tree(workspace.value) } returns readerTree(current)
        val index = mockk<SearchIndex>()
        val search = pageSearch(index)
        val input = AgentSearchRequest(path("workspace/pages"), listOf("rep"), null, 20)
        for (role in listOf(WorkspaceRole.EXPLORER, WorkspaceRole.VIEWER)) {
            coEvery { admission.authorizeWorkspaceRole(any()) } answers {
                val request = firstArg<WorkspaceRoleAdmissionRequest>()
                assertEquals(WorkspaceRole.VIEWER, request.requiredRole)
                ControlAdmissionDecision.Admitted(capability.copy(role = role))
            }
            coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(membership.copy(role = role))
            val result = assertIs<AgentSearchOutcome.Found>(search.search(caller, input)).response.results.single().items
            val read = assertIs<AgentResourceEnvelope.PagesTree>(assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/tree"), null))).resource)
            assertEquals(read.body.pages, result)
            assertEquals(if (role == WorkspaceRole.VIEWER) listOf(path("workspace/pages/$id")) else listOf(path("workspace/pages/$id"), path("workspace/pages/$secondId")), result.map { it.path })
            if (role == WorkspaceRole.VIEWER) {
                val body = assertIs<AgentResourceListingItem.Page>(result.single()).body
                assertNull(body.createdAt)
                assertNull(body.updatedAt)
                assertNull(body.createdBy)
                assertNull(body.updatedBy)
                assertNull(body.dashboard)
                assertNull(body.latestVersion)
            }
        }
        verify { index wasNot Called }
    }

    @Test
    fun `Page search rechecks credential membership and actor and never falls back to public access`() = runTest {
        val index = mockk<SearchIndex>()
        val search = pageSearch(index)
        val input = AgentSearchRequest(path("workspace"), listOf("Reports"), setOf(AgentResourceKind.PAGE), 20)
        coEvery { publicPages.isPublic(workspace) } returns true
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        assertIs<AgentSearchOutcome.Rejected>(search.search(caller, input))
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(capability)
        every { caller.actorPrincipalId } returns "another-actor"
        assertIs<AgentSearchOutcome.Rejected>(search.search(caller, input))
        coVerify(exactly = 0) { directory.getProjection(any()) }
        coVerify(exactly = 0) { currentPages.tree(any()) }
        coVerify(exactly = 0) { publicPages.isPublic(workspace) }
        verify { index wasNot Called }
    }

    @Test
    fun `current Page deletion and placement removal are reflected on the next search`() = runTest {
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(capability.copy(role = WorkspaceRole.VIEWER))
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(membership.copy(role = WorkspaceRole.VIEWER))
        val index = mockk<SearchIndex>()
        val search = pageSearch(index)
        val input = AgentSearchRequest(path("workspace/pages"), listOf("Reports"), setOf(AgentResourceKind.PAGE), 20)
        for (current in listOf(snapshot(1, listOf(page(id))), snapshot(2, listOf(page(id))).copy(placements = emptyList()), snapshot(3))) {
            coEvery { currentPages.tree(workspace.value) } returns readerTree(current)
            val result = assertIs<AgentSearchOutcome.Found>(search.search(caller, input)).response.results.single().items
            assertEquals(if (current.revision.value == 1L) listOf(path("workspace/pages/$id")) else emptyList(), result.map { it.path })
        }
        verify { index wasNot Called }
    }

    private fun pageSearch(index: SearchIndex) = SearchApplicationComposition().agentSearchPort(
        index, object : ConsistentSnapshotTransactionRunner {
            override suspend fun <T> run(action: suspend () -> T): T = action()
        }, admission, mockk(), mockk(), mockk(), dashboard, adapter,
    )

    @Test
    fun `Viewer projection rechecks current membership outside the inventory snapshot despite stale ingress role`() = runTest {
        every { caller.role } returns WorkspaceRole.EXPLORER
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(membership.copy(role = WorkspaceRole.VIEWER))
        val current = snapshot(5, listOf(page(id))).copy(placements = listOf(PagePlacement(id, emptyList())))
        coEvery { currentPages.tree(workspace.value) } returns readerTree(current)
        assertIs<AgentListingOutcome.Found>(adapter.list(caller, AgentListRequest(path("workspace/pages"), null, 10)))
        assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/tree"), null)))
        coVerify(exactly = 4) { admission.authorizeWorkspaceMembership(any()) }
        coVerify(exactly = 0) { admission.authorizeWorkspaceRole(any()) }
        coVerify(exactly = 0) { directory.getProjection(any()) }
    }

    @Test
    fun `Viewer membership loss between inventory and reader snapshots remains hidden`() = runTest {
        for (listing in listOf(false, true)) {
            coEvery { admission.authorizeWorkspaceMembership(any()) } returnsMany listOf(
                ControlAdmissionDecision.Admitted(membership.copy(role = WorkspaceRole.VIEWER)),
                ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
                    ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)),
            )
            val problem = if (listing) {
                assertIs<AgentListingOutcome.Rejected>(adapter.list(caller, AgentListRequest(path("workspace/pages"), null, 10))).problem
            } else {
                assertIs<AgentResourceOutcome.Rejected>(adapter.read(caller, AgentReadRequest(path("workspace/pages/$id"), null))).problem
            }
            assertEquals("NOT_FOUND", problem.code)
            assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND, problem.status)
            assertFalse(problem.retryable)
        }
        coVerify { listOf(directory, currentPages, publicPages) wasNot Called }
    }

    @Test
    fun `create records the person as an AGENT actor with the owner receipt key rather than server request ID`() = runTest {
        val command = slot<PagesDirectoryUseCase.CreateFolderCommand>()
        coEvery { directory.createFolder(capture(command)) } returns PagesDirectoryMutationOutcome.Applied(PageDirectoryChange.FolderCreated(id), snapshot())
        val request = createFolder()
        val result = assertIs<AgentActionOutcome.Completed>(adapter.create(caller, request))
        assertEquals(PageDirectoryRequestId("folder-command"), command.captured.requestId)
        assertEquals(actor, command.captured.actor)
        assertEquals(organization.value, command.captured.auditActorContext.organizationId)
        assertNull(command.captured.parentPageId)
        assertNull(command.captured.index)
        assertNull(command.captured.description)
        assertTrue(AgentWorkspaceResponseRelations.matches(request, result.action))
        validator.agentAction(AgentWorkspaceJson.agentActionJson(requestId, result.action, null, java.net.URI("http://127.0.0.1:3100")))
        coVerify { admission.authorizeWorkspaceRole(match { it.requiredRole == WorkspaceRole.EXPLORER && it.expectedOAuthResource == ExpectedOAuthResource.AGENT_REST }) }
        coVerify(exactly = 0) { directory.getProjection(any()) }
    }

    @Test
    fun `lost edit capability denies every mutation before reaching owner writes`() = runTest {
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        assertEquals(AgentActionOutcome.Forbidden, adapter.create(caller, createFolder()))
        assertEquals(AgentActionOutcome.Forbidden, adapter.edit(caller, metadataEdit("rename", "Reports")))
        val complete = decoder.invoke("""{"path":"workspace/page-content-uploads/$uploadRef","action":"complete","input":{"kind":"PAGE_CONTENT_UPLOAD_COMPLETE","parts":[{"partNumber":1,"eTag":"tag"}]}}""").value
        assertEquals(AgentActionOutcome.Forbidden, adapter.invoke(caller, complete))
        assertEquals(AgentActionOutcome.Forbidden, adapter.invoke(caller, AgentInvokeRequest(path("workspace/page-content-uploads/$uploadRef"), "abort", AgentInvokeInput.None)))
        coVerify { listOf(directory, content, uploads, dashboard) wasNot Called }
    }

    @Test
    fun `late inventory admission preserves credential membership and role denials before reading owner metadata`() = runTest {
        for ((reason, status, code) in listOf(
            Triple(IdentityAdmissionDenial.CREDENTIAL_EXPIRED, 401, "AUTHENTICATION_REQUIRED"),
            Triple(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE, 404, "NOT_FOUND"),
            Triple(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE, 403, "ROLE_REQUIRED"),
        )) {
            coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(
                ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(reason))
            val listing = adapter.list(caller, AgentListRequest(path("workspace/pages"), null, 10))
            val listingProblem = when (listing) {
                is AgentListingOutcome.Rejected -> listing.problem
                AgentListingOutcome.NotFound -> AgentTransportProblems.resourceNotFound
                AgentListingOutcome.Forbidden -> AgentTransportProblems.forbidden
                else -> error("Admission denial must stop inventory listing")
            }
            val resource = adapter.read(caller, AgentReadRequest(path("workspace/pages/tree"), null))
            val resourceProblem = when (resource) {
                is AgentResourceOutcome.Rejected -> resource.problem
                AgentResourceOutcome.NotFound -> AgentTransportProblems.resourceNotFound
                AgentResourceOutcome.Forbidden -> AgentTransportProblems.forbidden
                else -> error("Admission denial must stop inventory read")
            }
            for (problem in listOf(listingProblem, resourceProblem)) {
                assertEquals(status, problem.status.value)
                assertEquals(code, problem.code)
                assertFalse(problem.retryable)
            }
        }
        coVerify { listOf(directory, content, currentPages, publicPages, dashboard) wasNot Called }
    }

    @Test
    fun `late mutation admission preserves credential membership and role denials before owner writes`() = runTest {
        val abort = AgentInvokeRequest(path("workspace/page-content-uploads/$uploadRef"), "abort", AgentInvokeInput.None)
        for ((reason, status, code) in listOf(
            Triple(IdentityAdmissionDenial.CREDENTIAL_REVOKED, 401, "AUTHENTICATION_REQUIRED"),
            Triple(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE, 404, "NOT_FOUND"),
            Triple(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE, 403, "ROLE_REQUIRED"),
        )) {
            coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(
                ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(reason))
            val outcomes = listOf(adapter.create(caller, createFolder()), adapter.edit(caller, metadataEdit("rename", "Reports")), adapter.invoke(caller, abort))
            for (outcome in outcomes) {
                val problem = when (outcome) {
                    is AgentActionOutcome.Rejected -> outcome.problem
                    AgentActionOutcome.NotFound -> AgentTransportProblems.resourceNotFound
                    AgentActionOutcome.Forbidden -> AgentTransportProblems.forbidden
                    else -> error("Admission denial must stop Pages mutation")
                }
                assertEquals(status, problem.status.value)
                assertEquals(code, problem.code)
                assertFalse(problem.retryable)
            }
        }
        coVerify { listOf(directory, content, uploads, dashboard) wasNot Called }
    }

    @Test
    fun `current role downgrade blocks version history before reading any owner metadata`() = runTest {
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(membership.copy(role = WorkspaceRole.VIEWER))
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        assertEquals(AgentListingOutcome.Forbidden, adapter.list(caller, AgentListRequest(path("workspace/pages/$id/versions"), null, 10)))
        assertEquals(AgentResourceOutcome.Forbidden, adapter.read(caller, AgentReadRequest(path("workspace/pages/$id/versions/1"), null)))
        assertEquals(AgentResourceOutcome.NotFound, adapter.read(caller, AgentReadRequest(path("workspace/page-content-uploads/$uploadRef"), null)))
        coVerify { listOf(directory, content, uploads, dashboard) wasNot Called }
        coVerify(exactly = 2) { admission.authorizeWorkspaceMembership(any()) }
        coVerify(exactly = 0) { admission.authorizeWorkspaceRole(any()) }
    }

    @Test
    fun `Viewer exposes only current placed Pages including nested children while Explorer sees detached inventory`() = runTest {
        every { caller.role } returns WorkspaceRole.EXPLORER
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(membership.copy(role = WorkspaceRole.VIEWER))
        val detachedId = PageId(Uuid.generateV7())
        val pages = listOf(page(id), page(secondId, PageKind.DOCUMENT), page(detachedId, PageKind.DOCUMENT))
        val current = snapshot(5, pages).copy(placements = listOf(PagePlacement(id, listOf(PagePlacement(secondId, emptyList())))))
        coEvery { directory.getProjection(workspace.value) } returns projection(current)
        coEvery { currentPages.tree(workspace.value) } returns readerTree(current)
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        val listing = assertIs<AgentListingOutcome.Found>(adapter.list(caller, AgentListRequest(path("workspace/pages"), null, 10))).listing
        assertEquals(setOf(path("workspace/pages/$id"), path("workspace/pages/$secondId")), listing.items.map { it.path }.toSet())
        val tree = assertIs<AgentResourceEnvelope.PagesTree>(assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/tree"), null))).resource)
        assertEquals(listing.items.map { it.path }.toSet(), tree.body.pages.map { it.path }.toSet())
        assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND,
            assertIs<AgentResourceOutcome.Rejected>(adapter.read(caller, AgentReadRequest(path("workspace/pages/$detachedId"), null))).problem.status)
        val document = assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/$secondId"), null))).resource
        assertNull(assertIs<AgentPageResourceBody>(document.body).latestVersion)
        assertTrue(document.availableOperations.none { "/versions" in it.path.value })
        coVerify { content wasNot Called }
        coVerify(exactly = 0) { directory.getProjection(any()) }
        coVerify(exactly = 0) { publicPages.isPublic(workspace) }
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(capability)
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(membership)
        coEvery { content.listVersions(workspace.value, secondId, null, 1) } returns PageContentVersionPage(emptyList(), null)
        coEvery { content.listVersions(workspace.value, detachedId, null, 1) } returns PageContentVersionPage(emptyList(), null)
        every { caller.role } returns WorkspaceRole.EXPLORER
        val inventory = assertIs<AgentListingOutcome.Found>(adapter.list(caller, AgentListRequest(path("workspace/pages"), null, 10))).listing
        assertEquals(3, inventory.items.size)
    }

    @Test
    fun `foreign dashboard is checked in caller Workspace before directory mutation`() = runTest {
        val dashboardId = DashboardId(secondId.value)
        coEvery { dashboard.read(workspace, dashboardId) } returns DashboardOwnerOutcome.NotFound
        val request = AgentCreateRequest(path("workspace/pages"), AgentCreateInput.DashboardPage("Reports", null, null, null, 0, "link-dashboard", dashboardId.value))
        assertEquals(AgentActionOutcome.NotFound, adapter.create(caller, request))
        coVerify(exactly = 1) { dashboard.read(workspace, dashboardId) }
        coVerify { directory wasNot Called }
    }

    @Test
    fun `metadata replay forwards complete original command after intervening edit without reading current values`() = runTest {
        val commands = mutableListOf<PagesDirectoryUseCase.UpdateNavigationMetadataCommand>()
        coEvery { directory.updateNavigationMetadata(capture(commands)) } returnsMany listOf(
            PagesDirectoryMutationOutcome.Applied(PageDirectoryChange.NavigationMetadataUpdated(id), snapshot(1)),
            PagesDirectoryMutationOutcome.Applied(PageDirectoryChange.NavigationMetadataUpdated(id), snapshot(2)),
            PagesDirectoryMutationOutcome.Replayed(PageDirectoryChange.NavigationMetadataUpdated(id), snapshot(1)),
        )
        val original = metadataEdit("first-command", "Original")
        val first = adapter.edit(caller, original)
        adapter.edit(caller, metadataEdit("later-command", "Later"))
        val replay = adapter.edit(caller, original)
        assertEquals(first, replay)
        assertEquals(commands[0], commands[2])
        assertEquals("Original", commands[2].title)
        assertNull(commands[2].description)
        coVerify(exactly = 3) { directory.updateNavigationMetadata(any()) }
        confirmVerified(directory)
    }

    @Test
    fun `tree prefix is pure transport mapping and conflict has safe tree repair path`() = runTest {
        val command = slot<PagesDirectoryUseCase.PatchPlacementsCommand>()
        coEvery { directory.patchPlacements(capture(command)) } returns PagesDirectoryMutationOutcome.RevisionConflict(snapshot(7))
        val request = decoder.edit("""{"path":"workspace/pages/tree","revision":"3","commandId":"move-page","patch":[{"op":"remove","path":"/placements/0"},{"op":"add","path":"/placements/-","value":{"pageId":"$id","children":[]}}]}""").value
        val result = assertIs<AgentActionOutcome.Rejected>(adapter.edit(caller, request))
        assertEquals(listOf(JsonPrimitive("/0"), JsonPrimitive("/-")), command.captured.patch.map { it["path"] })
        assertEquals("move-page", command.captured.requestId.value)
        assertEquals(7, result.problem.currentRevision!!.value)
        assertEquals(listOf(AgentAvailableOperation.Read(path("workspace/pages/tree"))), result.problem.nextActions)
        assertFalse(result.problem.retryable)
    }

    @Test
    fun `list cursors bind Workspace revision and boundary and edit discovery follows current capability`() = runTest {
        val pages = listOf(page(secondId), page(id))
        coEvery { directory.getProjection(workspace.value) } returns projection(snapshot(4, pages))
        val first = assertIs<AgentListingOutcome.Found>(adapter.list(caller, AgentListRequest(path("workspace/pages"), null, 1))).listing
        assertEquals(path("workspace/pages/$id"), first.items.single().path)
        assertTrue(first.availableOperations.contains(AgentAvailableOperation.Create(path("workspace/page-content-uploads"), "PAGE_CONTENT_UPLOAD")))
        validator.agentListing(AgentWorkspaceJson.agentListingJson(requestId, first))
        val next = assertIs<AgentListingOutcome.Found>(adapter.list(caller, AgentListRequest(path("workspace/pages"), first.nextCursor, 1))).listing
        assertEquals(path("workspace/pages/$secondId"), next.items.single().path)
        assertNull(next.nextCursor)
        coEvery { directory.getProjection(workspace.value) } returns projection(snapshot(5, pages))
        assertEquals("CURSOR_STALE", assertIs<AgentListingOutcome.Rejected>(adapter.list(caller, AgentListRequest(path("workspace/pages"), first.nextCursor, 1))).problem.code)
        assertEquals("CURSOR_INVALID", assertIs<AgentListingOutcome.Rejected>(adapter.list(caller, AgentListRequest(path("workspace/pages"), "broken", 1))).problem.code)
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(membership.copy(role = WorkspaceRole.VIEWER))
        coEvery { currentPages.tree(workspace.value) } returns readerTree(snapshot(5, pages))
        val readOnly = assertIs<AgentListingOutcome.Found>(adapter.list(caller, AgentListRequest(path("workspace/pages"), null, 10))).listing
        assertEquals(listOf(AgentAvailableOperation.Read(path("workspace/pages/tree"))), readOnly.availableOperations)
    }

    @Test
    fun `Folder and Dashboard do not expose Document versions and version results carry no duplicate identity`() = runTest {
        coEvery { directory.getProjection(workspace.value) } returns projection(snapshot(1, listOf(page(id))))
        assertEquals(AgentListingOutcome.NotFound, adapter.list(caller, AgentListRequest(path("workspace/pages/$id/versions"), null, 10)))
        assertEquals(AgentResourceOutcome.NotFound, adapter.read(caller, AgentReadRequest(path("workspace/pages/$id/versions/1"), null)))
        val folder = assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/$id"), null))).resource
        assertTrue(folder.availableOperations.none { "/versions" in it.path.value })
        validator.agentResource(AgentWorkspaceJson.agentResourceJson(requestId, folder))
        coVerify { content wasNot Called }
        val document = page(id, PageKind.DOCUMENT)
        coEvery { directory.getProjection(workspace.value) } returns projection(snapshot(1, listOf(document)))
        coEvery { content.getVersion(workspace.value, id, 2) } returns version(2)
        val resource = assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/$id/versions/2"), null))).resource
        val json = validator.agentResource(AgentWorkspaceJson.agentResourceJson(requestId, resource))
        assertFalse((json["body"] as Map<*, *>).containsKey("pageId"))
        assertFalse((json["body"] as Map<*, *>).containsKey("versionNo"))
    }

    @Test
    fun `Explorer page, tree, and version reads name who created, updated, and finalized them as the Web does`() = runTest {
        val mina = FixedPrincipalDisplayNames.MINA.toString()
        val claude = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE.value
        val minaOnWeb = PageActor.create(PageActorKind.USER, mina)
        val minaViaAgent = PageActor.create(PageActorKind.AGENT, mina)
        val system = PageActor.create(PageActorKind.SYSTEM, "pages-migration")
        val document = page(id, PageKind.DOCUMENT, createdBy = minaOnWeb, updatedBy = minaViaAgent, updatedByApp = claude)
        val historical = page(secondId, createdBy = actor, updatedBy = actor)
        coEvery { directory.getProjection(workspace.value) } returns projection(snapshot(1, listOf(document, historical)))
        coEvery { content.listVersions(workspace.value, id, null, 1) } returns PageContentVersionPage(listOf(version(3, minaViaAgent, claude)), null)
        coEvery { content.listVersions(workspace.value, id, null, 10) } returns PageContentVersionPage(listOf(version(3, minaViaAgent, claude), version(2, system), version(1, actor)), null)
        coEvery { content.getVersion(workspace.value, id, 3) } returns version(3, minaViaAgent, claude)
        val claudeApp = AgentApp(claude, "Claude", "claude.ai")

        val read = assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/$id"), null))).resource
        val body = assertIs<AgentPageResourceBody>(read.body)
        assertEquals(AgentPageActor(AgentPageActorKind.USER, mina, "Mina Park", null), body.createdBy)
        assertEquals(AgentPageActor(AgentPageActorKind.AGENT, mina, "Mina Park", claudeApp), body.updatedBy)
        val json = validator.agentResource(AgentWorkspaceJson.agentResourceJson(requestId, read))
        assertEquals(mapOf("kind" to "USER", "id" to mina, "displayName" to "Mina Park"), (json["body"] as Map<*, *>)["createdBy"])

        val tree = assertIs<AgentPagesTreeResourceBody>(assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/tree"), null))).resource.body)
        val unnamed = AgentPageActor(AgentPageActorKind.AGENT, actor.id, null, null)
        assertEquals(listOf(body.updatedBy, unnamed), tree.pages.map { it.body.updatedBy })

        val version = assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("workspace/pages/$id/versions/3"), null))).resource
        assertEquals(AgentPageActor(AgentPageActorKind.AGENT, mina, "Mina Park", claudeApp), assertIs<AgentPageVersionResourceBody>(version.body).finalizedBy)
        validator.agentResource(AgentWorkspaceJson.agentResourceJson(requestId, version))

        val versions = assertIs<AgentListingOutcome.Found>(adapter.list(caller, AgentListRequest(path("workspace/pages/$id/versions"), null, 10))).listing
        assertEquals(
            listOf(
                AgentPageActor(AgentPageActorKind.AGENT, mina, "Mina Park", claudeApp),
                AgentPageActor(AgentPageActorKind.SYSTEM, "pages-migration", null, null),
                unnamed,
            ),
            versions.items.map { (it as AgentResourceListingItem.PageVersion).body.finalizedBy },
        )
        validator.agentListing(AgentWorkspaceJson.agentListingJson(requestId, versions))
    }

    @Test
    fun `prepare complete and repeated version commits preserve typed results and owner command semantics`() = runTest {
        val upload = PageContentUploadView(uploadRef, PageContentFormat.MARKDOWN, 12, PageContentHash("a".repeat(64)), clock.instant().plusSeconds(3600))
        coEvery { uploads.createSession(any()) } returns PageContentUploadCreateOutcome.Created(upload, listOf(PageContentUploadPart(1, 12, "https://storage.example.test/part")))
        val prepare = decoder.create("""{"path":"workspace/page-content-uploads","input":{"kind":"PAGE_CONTENT_UPLOAD","format":"MARKDOWN","byteSize":12,"sha256":"${"a".repeat(64)}"}}""").value
        val prepared = assertIs<AgentActionResult.PageUploadPrepared>(assertIs<AgentActionOutcome.Completed>(adapter.create(caller, prepare)).action)
        assertEquals(mapOf("Content-Length" to "12"), prepared.parts.single().requiredHeaders)
        assertTrue(AgentWorkspaceResponseRelations.matches(prepare, prepared))
        validator.agentAction(AgentWorkspaceJson.agentActionJson(requestId, prepared, null, java.net.URI("http://127.0.0.1:3100")))
        coEvery { uploads.complete(any()) } returnsMany listOf(PageContentUploadCompleteOutcome.Completed(upload), PageContentUploadCompleteOutcome.Replayed(upload))
        val complete = decoder.invoke("""{"path":"workspace/page-content-uploads/$uploadRef","action":"complete","input":{"kind":"PAGE_CONTENT_UPLOAD_COMPLETE","parts":[{"partNumber":1,"eTag":"etag"}]}}""").value
        val first = adapter.invoke(caller, complete)
        assertEquals(first, adapter.invoke(caller, complete))
        validator.agentAction(AgentWorkspaceJson.agentActionJson(requestId, assertIs<AgentActionOutcome.Completed>(first).action, null, java.net.URI("http://127.0.0.1:3100")))
        val commands = mutableListOf<PageContentUseCase.ReplaceCommand>()
        coEvery { content.replace(capture(commands)) } returnsMany listOf(PageContentMutationOutcome.Applied(version(2)), PageContentMutationOutcome.Replayed(version(2)))
        val commit = AgentCreateRequest(path("workspace/pages/$id/versions"), AgentCreateInput.PageVersion("version-command", uploadRef.value))
        val committed = adapter.create(caller, commit)
        assertEquals(committed, adapter.create(caller, commit))
        assertEquals(commands[0], commands[1])
        assertEquals("version-command", commands[0].requestId)
        assertEquals(actor, commands[0].actor)
        assertTrue(AgentWorkspaceResponseRelations.matches(commit, assertIs<AgentActionOutcome.Completed>(committed).action))
    }

    @Test
    fun `lost membership prevents metadata reads and cancellation is never converted to product rejection`() = runTest {
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        assertEquals(AgentResourceOutcome.NotFound, adapter.read(caller, AgentReadRequest(path("workspace/pages/tree"), null)))
        assertEquals(AgentListingOutcome.NotFound, adapter.list(caller, AgentListRequest(path("workspace/pages"), null, 10)))
        coVerify { directory wasNot Called }
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(capability)
        coEvery { directory.createFolder(any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { adapter.create(caller, createFolder()) }
    }

    @Test
    fun `composition installs real bridge only with every existing owner dependency`() = runTest {
        val composition = AgentWorkspaceApplicationComposition()
        val wired = composition.agentPagesOwnerPort(provider(directory), provider(content), provider(uploads), provider(dashboard), admission, provider(clock), provider(mockk()), fixedAgentProvenanceNames())
        assertIs<AgentPagesOwnerAdapter>(wired)
        val missing = composition.agentPagesOwnerPort(provider<PagesDirectoryUseCase>(null), provider(content), provider(uploads), provider(dashboard), admission, provider(clock), provider(mockk()), fixedAgentProvenanceNames())
        val handler = composition.agentWorkspaceHandler(mockk(), mockk(), mockk(), mockk(), mockk(), missing, provider<AgentSearchPort>(null), managementOwnerPort = io.mockk.mockk { io.mockk.coEvery { read(any<cc.monomer.agent.api.AgentWorkspaceCaller>(), any()) } returns cc.monomer.agent.api.AgentResourceOutcome.Unavailable })
        assertEquals(AgentListingOutcome.Unavailable, handler.list(caller, AgentListRequest(path("workspace"), null, 100)))
        assertIs<AgentActionOutcome.Rejected>(handler.create(caller, createFolder()))
    }

    private inline fun <reified T : Any> provider(value: T?): org.springframework.beans.factory.ObjectProvider<T> = mockk { every { ifAvailable } returns value }
    private fun createFolder() = decoder.create("""{"path":"workspace/pages","input":{"kind":"FOLDER_PAGE","title":"Reports","baseRevision":"0","commandId":"folder-command"}}""").value
    private fun metadataEdit(command: String, title: String) = decoder.edit("""{"path":"workspace/pages/$id","revision":"0","commandId":"$command","patch":[{"op":"replace","path":"/title","value":"$title"},{"op":"replace","path":"/description","value":null}]}""").value
    private fun path(value: String) = AgentResourcePath.parse(value)
    private fun page(
        id: PageId,
        kind: PageKind = PageKind.FOLDER,
        createdBy: PageActor = actor,
        createdByApp: String? = null,
        updatedBy: PageActor = actor,
        updatedByApp: String? = null,
    ): Page = mockk {
        every { this@mockk.id } returns id
        every { workspaceId } returns workspace.value
        every { this@mockk.kind } returns kind
        every { dashboardId } returns null
        every { navigationMetadata } returns (PageNavigationMetadata.create("Reports", null) as PageNavigationMetadataResult.Valid).metadata
        every { createdAt } returns clock.instant()
        every { updatedAt } returns clock.instant()
        every { this@mockk.createdBy } returns createdBy
        every { createdByAgentClientId } returns createdByApp
        every { this@mockk.updatedBy } returns updatedBy
        every { updatedByAgentClientId } returns updatedByApp
    }
    private fun snapshot(revision: Long = 0, pages: List<Page> = emptyList()) = PagesDirectorySnapshot(workspace.value, pages, pages.map { PagePlacement(it.id, emptyList()) }, PagesDirectoryRevision(revision), clock.instant(), actor)
    private fun readerTree(snapshot: PagesDirectorySnapshot): CurrentPagesTree {
        fun placement(value: PagePlacement): CurrentPagePlacement = CurrentPagePlacement(value.pageId.value, value.children.map(::placement))
        return CurrentPagesTree(snapshot.revision.value, snapshot.pages.map {
            CurrentPage(it.id.value, it.kind.name, if (it.kind == PageKind.DOCUMENT) "MARKDOWN" else null,
                null, "Reports", "Reports", null)
        }, snapshot.placements.map(::placement))
    }
    private fun projection(snapshot: PagesDirectorySnapshot) = PagesDirectoryProjection(snapshot, snapshot.pages.associate { it.id to "Reports" }, emptyMap())
    private fun version(number: Int, finalizedBy: PageActor = actor, agentClientId: String? = null) =
        PageVersionMetadata(id, PageVersionNumber(number), PageContentFormat.MARKDOWN, PageContentHash("a".repeat(64)), 12, finalizedBy, agentClientId, clock.instant())
}
