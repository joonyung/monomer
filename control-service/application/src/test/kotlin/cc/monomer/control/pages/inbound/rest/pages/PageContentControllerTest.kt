package cc.monomer.control.pages.inbound.rest.pages

import com.fasterxml.jackson.databind.ObjectMapper
import cc.monomer.pages.application.content.PageContentMutationOutcome
import cc.monomer.pages.application.content.PageContentUploadAbortOutcome
import cc.monomer.pages.application.content.PageContentUploadCompleteOutcome
import cc.monomer.pages.application.content.PageContentUploadCreateOutcome
import cc.monomer.pages.application.content.PageContentUploadError
import cc.monomer.pages.application.content.PageContentUploadErrorCode
import cc.monomer.pages.application.content.PageContentUploadPart
import cc.monomer.pages.application.content.PageContentUploadUseCase
import cc.monomer.pages.application.content.PageContentUploadView
import cc.monomer.pages.application.content.PageContentUseCase
import cc.monomer.pages.application.content.PageContentVersionPage
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.content.PageContentConsumptionRejection
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentUploadRef
import cc.monomer.pages.domain.content.PageVersionMetadata
import cc.monomer.pages.domain.content.PageVersionNumber
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class PageContentControllerTest {
    private val workspaceId = Uuid.parse("019e6000-0000-7000-8000-000000000401")
    private val userId = Uuid.parse("019e6000-0000-7000-8000-000000000402")
    private val organizationId = Uuid.parse("019e6000-0000-7000-8000-000000000403")
    private val pageId = PageId(Uuid.parse("019e6000-0000-7000-8000-000000000404"))
    private val actor = PageActor.create(PageActorKind.USER, userId.toString())
    private val uploadRef = PageContentUploadRef(Uuid.parse("019e6000-0000-7000-8000-000000000405"))
    private val now = Instant.parse("2026-07-11T00:00:00Z")
    private val uploadUseCase = mockk<PageContentUploadUseCase>()
    private val contentUseCase = mockk<PageContentUseCase>()
    private val displayNames = mockk<cc.monomer.identity.api.PrincipalDisplayNames>().also { names ->
        coEvery { names.read(any()) } answers { firstArg<Set<Uuid>>().associateWith { "Sam Kim" } }
    }
    private val controller = PageContentController(uploadUseCase, contentUseCase, testPagesRequestAccess(), displayNames, cc.monomer.control.agentapp.FixedAgentAppDisplays)

    @Test
    fun `upload create returns exact presigned part contract and no filename or object reference`() = runTest {
        val command = slot<PageContentUploadUseCase.CreateSessionCommand>()
        coEvery { uploadUseCase.createSession(capture(command)) } returns PageContentUploadCreateOutcome.Created(
            uploadView(),
            listOf(PageContentUploadPart(1, 12, "https://upload.invalid/part-1")),
        )

        val response = controller.createUpload(
            workspaceId.toString(),
            CreatePageContentUploadRequest("MARKDOWN", 12, "a".repeat(64)),
            exchange(TestAuthorities.editor()),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.location.toString()).endsWith("/page-content-uploads/$uploadRef")
        assertThat(command.captured.workspaceId).isEqualTo(workspaceId)
        assertThat(command.captured.actor).isEqualTo(actor)
        assertThat(command.captured.format).isEqualTo(PageContentFormat.MARKDOWN)
        assertThat(command.captured.byteSize).isEqualTo(12)
        val body = checkNotNull(response.body)
        assertThat(body.partSizeBytes).isEqualTo(8L * 1024 * 1024)
        assertThat(body.parts.single().contentLength).isEqualTo(12)
        assertThat(body.parts.single().requiredHeaders).isEqualTo(mapOf("Content-Length" to "12"))
        assertThat(ObjectMapper().writeValueAsString(body)).doesNotContain(
            "filename",
            "objectRef",
            "temporaryObjectRef",
            "sourceObjectRef",
            "Authorization",
            "Cookie",
        )
    }

    @Test
    fun `Viewer cannot create upload or replace content`() = runTest {
        val memberExchange = exchange(TestAuthorities.viewer())

        val createFailure = runCatching {
            controller.createUpload(
                workspaceId.toString(),
                CreatePageContentUploadRequest("MARKDOWN", 12, "a".repeat(64)),
                memberExchange,
            )
        }.exceptionOrNull()
        val replaceFailure = runCatching {
            controller.replaceContent(
                workspaceId.toString(),
                pageId.toString(),
                ReplacePageContentRequest(uploadRef.toString(), "replace-member"),
                memberExchange,
            )
        }.exceptionOrNull()

        assertThat(createFailure)
            .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThat(replaceFailure)
            .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        coVerify(exactly = 0) { uploadUseCase.createSession(any()) }
        coVerify(exactly = 0) { contentUseCase.replace(any()) }
    }

    @Test
    fun `completion and abort map replay conflicts and hidden uploads without leaking refs`() = runTest {
        coEvery { uploadUseCase.complete(any()) } returnsMany listOf(
            PageContentUploadCompleteOutcome.Completed(uploadView()),
            PageContentUploadCompleteOutcome.NotFound,
            PageContentUploadCompleteOutcome.InProgress,
            PageContentUploadCompleteOutcome.RequestReused,
        )
        val editor = exchange(TestAuthorities.editor())
        val request = CompletePageContentUploadRequest(
            listOf(CompletedPageContentUploadPartRequest(1, "etag-1")),
        )

        val completed = controller.completeUpload(workspaceId.toString(), uploadRef.toString(), request, editor)
        val failures = (1..3).map {
            runCatching {
                controller.completeUpload(workspaceId.toString(), uploadRef.toString(), request, editor)
            }.exceptionOrNull() as PageContentRestProblem
        }

        assertThat(completed.body?.result).isEqualTo("COMPLETED")
        assertThat(failures.map(PageContentRestProblem::code)).containsExactly(
            "UPLOAD_NOT_FOUND",
            "UPLOAD_COMPLETION_IN_PROGRESS",
            "UPLOAD_COMPLETION_REQUEST_REUSED",
        )

        coEvery { uploadUseCase.abort(any()) } returnsMany listOf(
            PageContentUploadAbortOutcome.Aborted,
            PageContentUploadAbortOutcome.Replayed,
            PageContentUploadAbortOutcome.InProgress,
            PageContentUploadAbortOutcome.AlreadyConsumed,
        )
        assertThat(controller.abortUpload(workspaceId.toString(), uploadRef.toString(), editor).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(controller.abortUpload(workspaceId.toString(), uploadRef.toString(), editor).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        val abortConflicts = (1..2).map {
            runCatching {
                controller.abortUpload(workspaceId.toString(), uploadRef.toString(), editor)
            }.exceptionOrNull() as PageContentRestProblem
        }
        assertThat(abortConflicts.map(PageContentRestProblem::code)).containsExactly(
            "UPLOAD_COMPLETION_IN_PROGRESS",
            "UPLOAD_ALREADY_CONSUMED",
        )
    }

    @Test
    fun `typed upload rejections expose only bounded repair metadata`() = runTest {
        coEvery { uploadUseCase.createSession(any()) } returns PageContentUploadCreateOutcome.Rejected(
            PageContentUploadError(PageContentUploadErrorCode.SOURCE_SIZE_INVALID, 20L * 1024 * 1024),
        )
        coEvery { uploadUseCase.complete(any()) } returns PageContentUploadCompleteOutcome.Rejected(
            PageContentUploadError(PageContentUploadErrorCode.SOURCE_HASH_MISMATCH, null),
        )
        val editor = exchange(TestAuthorities.editor())

        val sizeFailure = runCatching {
            controller.createUpload(
                workspaceId.toString(),
                CreatePageContentUploadRequest("HTML", 0, "a".repeat(64)),
                editor,
            )
        }.exceptionOrNull() as PageContentRestProblem
        val hashFailure = runCatching {
            controller.completeUpload(
                workspaceId.toString(),
                uploadRef.toString(),
                CompletePageContentUploadRequest(listOf(CompletedPageContentUploadPartRequest(1, "etag"))),
                editor,
            )
        }.exceptionOrNull() as PageContentRestProblem

        assertThat(sizeFailure.status).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
        assertThat(sizeFailure.code).isEqualTo("SOURCE_SIZE_INVALID")
        assertThat(sizeFailure.properties).isEqualTo(mapOf("maximumBytes" to 20L * 1024 * 1024))
        assertThat(hashFailure.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(hashFailure.properties).isEmpty()
    }

    @Test
    fun `replace creates immutable version without directory revision and carries audit identity`() = runTest {
        val command = slot<PageContentUseCase.ReplaceCommand>()
        val version = version(2, PageContentFormat.HTML)
        coEvery { contentUseCase.replace(capture(command)) } returns PageContentMutationOutcome.Applied(version)

        val response = controller.replaceContent(
            workspaceId.toString(),
            pageId.toString(),
            ReplacePageContentRequest(uploadRef.toString(), "replace-2"),
            exchange(TestAuthorities.editor()),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.location.toString()).endsWith("/pages/$pageId/versions/2")
        assertThat(response.body?.version?.versionNo).isEqualTo(2)
        assertThat(command.captured.workspaceId).isEqualTo(workspaceId)
        assertThat(command.captured.pageId).isEqualTo(pageId)
        assertThat(command.captured.uploadRef).isEqualTo(uploadRef)
        assertThat(command.captured.requestId).isEqualTo("replace-2")
        assertThat(command.captured.auditActorContext.organizationId).isEqualTo(organizationId)
        assertThat(command.captured.auditActorContext.principalId).isEqualTo(userId)
        assertThat(ObjectMapper().writeValueAsString(response.body)).doesNotContain(
            "baseRevision",
            "directoryRevision",
            "sourceObjectRef",
            uploadRef.toString(),
        )
    }

    @Test
    fun `replace outcomes retain request idempotency and explicit upload state errors`() = runTest {
        coEvery { contentUseCase.replace(any()) } returnsMany listOf(
            PageContentMutationOutcome.Replayed(version(2, PageContentFormat.HTML)),
            PageContentMutationOutcome.RequestIdReused("f".repeat(64)),
            PageContentMutationOutcome.UploadRejected(PageContentConsumptionRejection.UPLOAD_ABORTED),
            PageContentMutationOutcome.PageNotFound,
        )
        val editor = exchange(TestAuthorities.editor())
        val request = ReplacePageContentRequest(uploadRef.toString(), "replace-replay")

        val replay = controller.replaceContent(workspaceId.toString(), pageId.toString(), request, editor)
        val failures = (1..3).map {
            runCatching {
                controller.replaceContent(workspaceId.toString(), pageId.toString(), request, editor)
            }.exceptionOrNull() as PageContentRestProblem
        }

        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(replay.body?.result).isEqualTo("REPLAYED")
        assertThat(failures.map(PageContentRestProblem::code)).containsExactly(
            "REQUEST_ID_REUSED",
            "UPLOAD_ABORTED",
            "PAGE_NOT_FOUND",
        )
        assertThat(failures.first().requestId).isEqualTo("replace-replay")
        assertThat(failures.first().properties).isEqualTo(mapOf("existingRequestHash" to "f".repeat(64)))
    }

    @Test
    fun `version history is bounded cursor based and exposes viewer safe metadata only`() = runTest {
        val first = version(3, PageContentFormat.NOTEBOOK)
        val second = version(2, PageContentFormat.MARKDOWN)
        coEvery { contentUseCase.listVersions(workspaceId, pageId, null, 2) } returns
            PageContentVersionPage(listOf(first, second), 2)
        coEvery { contentUseCase.getVersion(workspaceId, pageId, 2) } returns second
        val member = exchange(TestAuthorities.member())

        val history = controller.listVersions(workspaceId.toString(), pageId.toString(), null, 2, member)
        val exact = controller.version(workspaceId.toString(), pageId.toString(), 2, member)

        assertThat(history.items.map(PageContentVersionResponse::versionNo)).containsExactly(3, 2)
        assertThat(history.nextBeforeVersionNo).isEqualTo(2)
        assertThat(exact).isEqualTo(second.toResponse(PageVersionFinalizers(mapOf(userId.toString() to "Sam Kim"), emptyMap())))
        assertThat(exact.finalizedBy.displayName).isEqualTo("Sam Kim")
        assertThat(exact.finalizedBy.kind).isEqualTo("USER")
        coVerify { contentUseCase.listVersions(workspaceId, pageId, null, 2) }
        val serialized = ObjectMapper().writeValueAsString(history)
        assertThat(serialized).doesNotContain(
            "sourceObjectRef",
            "temporaryObjectRef",
            "objectRef",
            "uploadRef",
            "url",
            "raw",
            "html",
        )
    }

    @Test
    fun `invalid history bounds are rejected before query and missing Document stays hidden`() = runTest {
        val member = exchange(TestAuthorities.member())
        val invalidLimit = runCatching {
            controller.listVersions(workspaceId.toString(), pageId.toString(), null, 101, member)
        }.exceptionOrNull() as PageContentRestProblem
        coEvery { contentUseCase.listVersions(workspaceId, pageId, null, 10) } returns
            PageContentVersionPage(emptyList(), null)
        coEvery { contentUseCase.getVersion(workspaceId, pageId, 1) } returns null

        val missing = runCatching {
            controller.listVersions(workspaceId.toString(), pageId.toString(), null, 10, member)
        }.exceptionOrNull() as PageContentRestProblem

        assertThat(invalidLimit.code).isEqualTo("INVALID_LIMIT")
        assertThat(missing.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(missing.code).isEqualTo("PAGE_NOT_FOUND")
        coVerify(exactly = 0) { contentUseCase.listVersions(workspaceId, pageId, null, 101) }
    }

    private fun uploadView(): PageContentUploadView = PageContentUploadView(
        uploadRef,
        PageContentFormat.MARKDOWN,
        12,
        PageContentHash("a".repeat(64)),
        now.plusSeconds(900),
    )

    private fun version(versionNumber: Int, format: PageContentFormat): PageVersionMetadata = PageVersionMetadata(
        pageId,
        PageVersionNumber(versionNumber),
        format,
        PageContentHash(versionNumber.toString().repeat(64)),
        100 + versionNumber.toLong(),
        actor,
        null,
        now.plusSeconds(versionNumber.toLong()),
    )

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
