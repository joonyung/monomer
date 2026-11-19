package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.pages.application.PagesDirectoryMutationOutcome
import cc.monomer.pages.application.PagesDirectoryProjection
import cc.monomer.pages.application.PagesDirectoryUseCase
import cc.monomer.pages.domain.Page
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageCreationResult
import cc.monomer.pages.domain.PageDirectoryChange
import cc.monomer.pages.domain.PageDirectoryRequestRejection
import cc.monomer.pages.domain.PageMetadataRejection
import cc.monomer.pages.domain.PageNavigationMetadata
import cc.monomer.pages.domain.PageNavigationMetadataResult
import cc.monomer.pages.domain.PagePlacement
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.PagesDirectorySnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class PagesControllerTest {
    private val workspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000011")
    private val userId = Uuid.parse("019e501f-0000-7000-8000-000000000099")
    private val organizationId = Uuid.parse("019e501f-0000-7000-8000-000000000010")
    private val actor = PageActor.create(PageActorKind.USER, userId.toString())
    private val now = Instant.parse("2026-07-10T00:00:00Z")
    private val useCase = mockk<PagesDirectoryUseCase>()
    private val dashboardOwner = mockk<cc.monomer.dashboard.api.DashboardOwnerPort>()
    private val dashboardAuthorizer = mockk<cc.monomer.control.dashboard.DashboardRequestAuthorizer>()
    private val controller = PagesController(useCase, testPagesRequestAccess(), dashboardOwner, dashboardAuthorizer)

    @BeforeEach
    fun setUp() {
        coEvery { useCase.projectSnapshot(any()) } answers { projection(firstArg()) }
        coEvery { useCase.getProjection(workspaceId) } returns projection(emptySnapshot())
    }

    @Test
    fun `dashboard link rejects malformed owner identity and missing Pages edit`() = runTest {
        for (target in listOf("invalid", Uuid.NIL.toString(), Uuid.random().toString())) {
            val request = CreatePageRequest("DASHBOARD", "Link", null, null, target, null, null, 0, "invalid")
            val failure = runCatching { controller.createPage(workspaceId.toString(), request, exchange(TestAuthorities.editor())) }.exceptionOrNull() as PagesRestProblem
            assertThat(failure.code).isEqualTo("INVALID_DASHBOARD_ID")
        }
        val request = CreatePageRequest("DASHBOARD", "Link", null, null, Uuid.generateV7().toString(), null, null, 0, "no-edit")
        assertThat(runCatching { controller.createPage(workspaceId.toString(), request, exchange(TestAuthorities.viewer())) }.exceptionOrNull())
            .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        coVerify(exactly = 0) { useCase.createDashboardPage(any()) }
    }

    @Test
    fun `dashboard creation rechecks read admission and same workspace target on replay`() = runTest {
        val target = Uuid.generateV7()
        val command = slot<PagesDirectoryUseCase.CreateDashboardPageCommand>()
        val page = (Page.createDashboard(workspaceId, target,
            (PageNavigationMetadata.create("Navigation name", null) as PageNavigationMetadataResult.Valid).metadata,
            actor, null, now) as PageCreationResult.Created).page
        val applied = PagesDirectoryMutationOutcome.Applied(PageDirectoryChange.DashboardPageCreated(page.id), snapshot(page, PagesDirectoryRevision(1)))
        coEvery { dashboardAuthorizer.requireWorkspace(any(), workspaceId.toString()) } returns mockk()
        coEvery { dashboardOwner.read(cc.monomer.identity.api.WorkspaceId(workspaceId), cc.monomer.dashboard.api.DashboardId(target)) } returns
            cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(mockk())
        coEvery { useCase.createDashboardPage(capture(command)) } returns applied
        val request = CreatePageRequest("DASHBOARD", "Navigation name", null, null, target.toString(), null, null, 0, "link")
        val response = controller.createPage(workspaceId.toString(), request, exchange(TestAuthorities.editor()))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat((response.body as PageMutationResponse).snapshot.pages.single().dashboardId).isEqualTo(target.toString())
        assertThat(command.captured.dashboardId).isEqualTo(target)
        coEvery { dashboardAuthorizer.requireWorkspace(any(), workspaceId.toString()) } throws
            org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND)
        assertThat(runCatching { controller.createPage(workspaceId.toString(), request, exchange(TestAuthorities.editor())) }.exceptionOrNull())
            .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        coVerify(exactly = 1) { useCase.createDashboardPage(any()) }
    }

    @Test
    fun `missing and foreign workspace targets do not create a Page`() = runTest {
        coEvery { dashboardAuthorizer.requireWorkspace(any(), workspaceId.toString()) } returns mockk()
        repeat(2) {
            val target = Uuid.generateV7()
            coEvery { dashboardOwner.read(cc.monomer.identity.api.WorkspaceId(workspaceId), cc.monomer.dashboard.api.DashboardId(target)) } returns
                cc.monomer.dashboard.api.DashboardOwnerOutcome.NotFound
            val request = CreatePageRequest("DASHBOARD", "Link", null, null, target.toString(), null, null, 0, "missing-$it")
            val failure = runCatching { controller.createPage(workspaceId.toString(), request, exchange(TestAuthorities.editor())) }.exceptionOrNull() as PagesRestProblem
            assertThat(failure.status).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(failure.code).isEqualTo("DASHBOARD_NOT_FOUND")
        }
        coVerify(exactly = 0) { useCase.createDashboardPage(any()) }
    }

    @Test
    fun `dashboard deleted before the locked recheck uses the same not found problem`() = runTest {
        val target = Uuid.generateV7()
        coEvery { dashboardAuthorizer.requireWorkspace(any(), workspaceId.toString()) } returns mockk()
        coEvery { dashboardOwner.read(cc.monomer.identity.api.WorkspaceId(workspaceId), cc.monomer.dashboard.api.DashboardId(target)) } returns
            cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(mockk())
        coEvery { useCase.createDashboardPage(any()) } returns PagesDirectoryMutationOutcome.NotFound
        val request = CreatePageRequest("DASHBOARD", "Link", null, null, target.toString(), null, null, 0, "deleted")

        val failure = runCatching { controller.createPage(workspaceId.toString(), request, exchange(TestAuthorities.editor())) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PagesRestProblem::class.java)
        failure as PagesRestProblem
        assertThat(failure.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(failure.code).isEqualTo("DASHBOARD_NOT_FOUND")
    }

    @Test
    fun `create rejects fields belonging to another Page kind`() = runTest {
        val target = Uuid.generateV7().toString()
        for (request in listOf(
            CreatePageRequest("FOLDER", "Link", null, null, target, null, null, 0, "folder"),
            CreatePageRequest("DOCUMENT", "Link", null, null, target, null, null, 0, "document"),
            CreatePageRequest("DASHBOARD", "Link", null, target, target, null, null, 0, "dashboard"),
        )) {
            val failure = runCatching { controller.createPage(workspaceId.toString(), request, exchange(TestAuthorities.editor())) }.exceptionOrNull() as PagesRestProblem
            assertThat(failure.code).isEqualTo("INVALID_PAGE_PAYLOAD")
        }
        coVerify(exactly = 0) { useCase.createDashboardPage(any()) }
        coVerify(exactly = 0) { useCase.createFolder(any()) }
        coVerify(exactly = 0) { useCase.createDocumentPage(any()) }
    }

    @Test
    fun `member reads the Pages tree`() = runTest {
        val response = controller.tree(workspaceId.toString(), exchange(TestAuthorities.member()))

        assertThat(response.workspaceId).isEqualTo(workspaceId.toString())
        assertThat(response.revision).isZero()
    }

    @Test
    fun `Viewer cannot mutate Pages`() = runTest {
        val failure = runCatching {
            controller.patchTree(
                workspaceId.toString(),
                PatchPagesTreeRequest(
                    0,
                    "patch-1",
                    listOf(mapOf("op" to "test", "path" to "", "value" to emptyList<Any>())),
                ),
                exchange(TestAuthorities.viewer()),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        coVerify(exactly = 0) { useCase.patchPlacements(any()) }
    }

    @Test
    fun `workspace editor can create a folder and carries authenticated actor`() = runTest {
        val command = slot<PagesDirectoryUseCase.CreateFolderCommand>()
        val page = folderPage("Folder")
        val snapshot = snapshot(page, PagesDirectoryRevision(1))
        coEvery { useCase.createFolder(capture(command)) } returns
            PagesDirectoryMutationOutcome.Applied(PageDirectoryChange.FolderCreated(page.id), snapshot)

        val response = controller.createPage(
            workspaceId.toString(),
            CreatePageRequest(
                kind = "FOLDER",
                title = "Folder",
                description = null,
                dashboardId = null,
                uploadRef = null,
                parentPageId = null,
                index = null,
                baseRevision = 0,
                requestId = "create-1",
            ),
            exchange(TestAuthorities.editor()),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.location.toString()).endsWith("/pages/${page.id}")
        assertThat(command.captured.actor).isEqualTo(actor)
        assertThat(command.captured.workspaceId).isEqualTo(workspaceId)
        assertThat(command.captured.auditActorContext.organizationId).isEqualTo(organizationId)
        assertThat(command.captured.auditActorContext.principalId).isEqualTo(userId)
        assertThat(command.captured.auditActorContext.actorRef).isEqualTo(userId.toString())
    }

    @Test
    fun `Dashboard Page requires target before invoking a create use case`() = runTest {
        val failure = runCatching {
            controller.createPage(
                workspaceId.toString(),
                CreatePageRequest(
                    kind = "DASHBOARD",
                    title = null,
                    description = null,
                    dashboardId = null,
                    uploadRef = null,
                    parentPageId = null,
                    index = null,
                    baseRevision = 0,
                    requestId = "retired-dashboard",
                ),
                exchange(TestAuthorities.editor()),
            )
        }.exceptionOrNull() as PagesRestProblem

        assertThat(failure.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(failure.code).isEqualTo("DASHBOARD_ID_REQUIRED")
        coVerify(exactly = 0) { useCase.createFolder(any()) }
        coVerify(exactly = 0) { useCase.createDocumentPage(any()) }
    }

    @Test
    fun `revision conflict exposes the current workspace tree as a typed 409 problem`() = runTest {
        coEvery { useCase.createFolder(any()) } returns PagesDirectoryMutationOutcome.RevisionConflict(emptySnapshot())

        val failure = runCatching {
            controller.createPage(
                workspaceId.toString(),
                CreatePageRequest(
                    kind = "FOLDER",
                    title = "Folder",
                    description = null,
                    dashboardId = null,
                    uploadRef = null,
                    parentPageId = null,
                    index = null,
                    baseRevision = 0,
                    requestId = "conflict-1",
                ),
                exchange(TestAuthorities.editor()),
            )
        }.exceptionOrNull() as PagesRestProblem

        assertThat(failure.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(failure.code).isEqualTo("REVISION_CONFLICT")
        assertThat(failure.current?.revision).isZero()
    }

    @Test
    fun `rejected mutation exposes the complete current workspace tree`() = runTest {
        val first = folderPage("First")
        val second = folderPage("Second")
        val directory = PagesDirectorySnapshot(
            workspaceId,
            listOf(first, second),
            listOf(PagePlacement(first.id, emptyList()), PagePlacement(second.id, emptyList())),
            PagesDirectoryRevision(1),
            now,
            actor,
        )
        coEvery { useCase.createFolder(any()) } returns PagesDirectoryMutationOutcome.Rejected(
            PageDirectoryRequestRejection.MetadataInvalid(PageMetadataRejection.TITLE_BLANK),
            directory,
        )

        val failure = runCatching {
            controller.createPage(
                workspaceId.toString(),
                CreatePageRequest(
                    kind = "FOLDER",
                    title = " ",
                    description = null,
                    dashboardId = null,
                    uploadRef = null,
                    parentPageId = first.id.toString(),
                    index = null,
                    baseRevision = 1,
                    requestId = "rejected-create",
                ),
                exchange(TestAuthorities.editor()),
            )
        }.exceptionOrNull() as PagesRestProblem

        assertThat(failure.current?.pages?.map(PageResponse::pageId))
            .containsExactlyInAnyOrder(first.id.toString(), second.id.toString())
        assertThat(failure.current?.placements?.map(PagePlacementResponse::pageId))
            .containsExactlyInAnyOrder(first.id.toString(), second.id.toString())
    }

    @Test
    fun `tree patch rejects operation count before invoking the use case`() = runTest {
        val patch = (1..101).map { mapOf<String, Any?>("op" to "test", "path" to "", "value" to emptyList<Any>()) }

        val failure = runCatching {
            controller.patchTree(
                workspaceId.toString(),
                PatchPagesTreeRequest(0, "too-many", patch),
                exchange(TestAuthorities.editor()),
            )
        }.exceptionOrNull() as PagesRestProblem

        assertThat(failure.status).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
        assertThat(failure.code).isEqualTo("PATCH_OPERATION_LIMIT_EXCEEDED")
        coVerify(exactly = 0) { useCase.patchPlacements(any()) }
    }

    @Test
    fun `tree patch rejects pointer depth and body exposure limits before invoking the use case`() = runTest {
        var deepValue: Any? = "leaf"
        repeat(30) { deepValue = mapOf("child" to deepValue) }
        val cases = listOf(
            Triple(
                PatchPagesTreeRequest(
                    0,
                    "long-pointer",
                    listOf(mapOf("op" to "test", "path" to "/" + "x".repeat(1_024))),
                ),
                HttpStatus.BAD_REQUEST,
                "PATCH_PATH_TOO_LONG",
            ),
            Triple(
                PatchPagesTreeRequest(
                    0,
                    "deep-value",
                    listOf(mapOf("op" to "add", "path" to "/0", "value" to deepValue)),
                ),
                HttpStatus.BAD_REQUEST,
                "PATCH_DEPTH_LIMIT_EXCEEDED",
            ),
            Triple(
                PatchPagesTreeRequest(
                    0,
                    "large-body",
                    listOf(mapOf("op" to "add", "path" to "/0", "value" to "x".repeat(513 * 1_024))),
                ),
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PATCH_BODY_TOO_LARGE",
            ),
        )

        val failures = cases.map { (request, _, _) ->
            runCatching {
                controller.patchTree(workspaceId.toString(), request, exchange(TestAuthorities.editor()))
            }.exceptionOrNull() as PagesRestProblem
        }

        assertThat(failures.map(PagesRestProblem::status)).containsExactlyElementsOf(cases.map { it.second })
        assertThat(failures.map(PagesRestProblem::code)).containsExactlyElementsOf(cases.map { it.third })
        coVerify(exactly = 0) { useCase.patchPlacements(any()) }
    }

    @Test
    fun `Page read returns path and direct children while missing Page is 404`() = runTest {
        val folder = folderPage("Folder")
        val child = documentPage("Child")
        val snapshot = PagesDirectorySnapshot(
            workspaceId,
            listOf(folder, child),
            listOf(PagePlacement(folder.id, listOf(PagePlacement(child.id, emptyList())))),
            PagesDirectoryRevision(2),
            now,
            actor,
        )
        coEvery { useCase.getProjection(workspaceId) } returns projection(snapshot)

        val found = controller.page(workspaceId.toString(), folder.id.toString(), exchange(TestAuthorities.member()))
        val missing = runCatching {
            controller.page(workspaceId.toString(), Uuid.generateV7().toString(), exchange(TestAuthorities.member()))
        }.exceptionOrNull() as PagesRestProblem

        assertThat(found.path).containsExactly(folder.id.toString())
        assertThat(found.children).containsExactly(child.id.toString())
        assertThat(found.page.displayTitle).isEqualTo("Folder")
        assertThat(missing.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun projection(snapshot: PagesDirectorySnapshot): PagesDirectoryProjection = PagesDirectoryProjection(
        snapshot,
        snapshot.pages.associate { page -> page.id to requireNotNull(page.navigationMetadata.title) },
        emptyMap(),
    )

    private fun emptySnapshot(): PagesDirectorySnapshot = PagesDirectorySnapshot(
        workspaceId,
        emptyList(),
        emptyList(),
        PagesDirectoryRevision(0),
        Instant.EPOCH,
        PageActor.create(PageActorKind.SYSTEM, "initial"),
    )

    private fun snapshot(page: Page, revision: PagesDirectoryRevision): PagesDirectorySnapshot = PagesDirectorySnapshot(
        workspaceId,
        listOf(page),
        listOf(PagePlacement(page.id, emptyList())),
        revision,
        now,
        actor,
    )

    private fun folderPage(title: String): Page {
        val metadata = (PageNavigationMetadata.create(title, null) as PageNavigationMetadataResult.Valid).metadata
        return (Page.createFolder(workspaceId, metadata, actor, null, now) as PageCreationResult.Created).page
    }

    private fun documentPage(title: String): Page {
        val metadata = (PageNavigationMetadata.create(title, null) as PageNavigationMetadataResult.Valid).metadata
        return (Page.createDocument(workspaceId, metadata, actor, null, now) as PageCreationResult.Created).page
    }

    private fun exchange(access: TestAccess): MockServerWebExchange {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build())
        exchange.attributes[RequestContextAttributes.ATTRIBUTE_KEY] = RequestContext(
            userId,
            organizationId,
            workspaceId,
            "token",
            access,
        )
        return exchange
    }
}
