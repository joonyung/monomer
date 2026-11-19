package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.pages.application.PagesDirectoryProjection
import cc.monomer.pages.application.PagesDirectoryUseCase
import cc.monomer.pages.application.PagesDirectoryWakeup
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.Page
import cc.monomer.pages.domain.PageCreationResult
import cc.monomer.pages.domain.PageNavigationMetadata
import cc.monomer.pages.domain.PageNavigationMetadataResult
import cc.monomer.pages.domain.PagePlacement
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.PagesDirectorySnapshot
import cc.monomer.pages.domain.content.PageContentFormat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class PagesEventStreamControllerTest {
    private val workspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000011")
    private val userId = Uuid.parse("019e501f-0000-7000-8000-000000000099")
    private val actor = PageActor.create(PageActorKind.USER, userId.toString())
    private val now = Instant.parse("2026-07-10T00:00:00Z")
    private val useCase = mockk<PagesDirectoryUseCase>()
    private val controller = PagesEventStreamController(useCase, testPagesRequestAccess())

    @Test
    fun `snapshot stream sends the authorized current snapshot after coalesced workspace wakeups`() = runTest {
        val first = snapshot(PagesDirectoryRevision(1))
        val third = snapshot(PagesDirectoryRevision(3))
        coEvery { useCase.getProjection(workspaceId) } returnsMany listOf(
            projection(first),
            projection(third),
        )
        every { useCase.subscribeEvents(workspaceId) } returns flowOf(
            PagesDirectoryWakeup(workspaceId),
            PagesDirectoryWakeup(workspaceId),
        )

        val (firstMessage, latestMessage) = controller
            .events(workspaceId.toString(), exchange())
            .take(2)
            .toList()

        assertThat(listOf(firstMessage.id(), latestMessage.id())).containsExactly("1", "3")
        assertThat(listOf(firstMessage.event(), latestMessage.event())).containsExactly("snapshot", "snapshot")
    }

    @Test
    fun `snapshot stream sends a same-revision format projection after content finalization`() = runTest {
        val document = documentPage()
        val snapshot = snapshot(PagesDirectoryRevision(1), document)
        val first = projection(snapshot)
        val latest = projection(snapshot, mapOf(document.id to PageContentFormat.NOTEBOOK))
        coEvery { useCase.getProjection(workspaceId) } returnsMany listOf(first, latest)
        every { useCase.subscribeEvents(workspaceId) } returns flowOf(PagesDirectoryWakeup(workspaceId))

        val (firstMessage, latestMessage) = controller
            .events(workspaceId.toString(), exchange())
            .take(2)
            .toList()

        assertThat(listOf(firstMessage.id(), latestMessage.id())).containsExactly("1", "1")
        assertThat((latestMessage.data() as PagesTreeResponse).pages.single().documentFormat)
            .isEqualTo("NOTEBOOK")
    }

    @Test
    fun `snapshot stream closes without another payload when membership is revoked`() = runTest {
        val first = projection(snapshot(PagesDirectoryRevision(1)))
        coEvery { useCase.getProjection(workspaceId) } returns first
        every { useCase.subscribeEvents(workspaceId) } returns flowOf(PagesDirectoryWakeup(workspaceId))
        val admitted = testPagesRequestAccess().require(exchange(), workspaceId.toString())
        var membershipChecks = 0
        val requestAccess = object : PagesRequestAccess {
            override suspend fun require(exchange: org.springframework.web.server.ServerWebExchange, rawWorkspaceId: String) = admitted

            override suspend fun isCurrentExplorer(access: PagesAccess): Boolean {
                membershipChecks += 1
                return membershipChecks == 1
            }
        }

        val messages = PagesEventStreamController(useCase, requestAccess)
            .events(workspaceId.toString(), exchange())
            .toList()

        assertThat(messages).hasSize(1)
        assertThat(messages.single().event()).isEqualTo("snapshot")
        assertThat(membershipChecks).isEqualTo(2)
        coVerify(exactly = 1) { useCase.getProjection(workspaceId) }
    }

    private fun snapshot(
        revision: PagesDirectoryRevision,
        document: Page? = null,
    ): PagesDirectorySnapshot = PagesDirectorySnapshot(
        workspaceId = workspaceId,
        pages = listOfNotNull(document),
        placements = document?.let { listOf(PagePlacement(it.id, emptyList())) }.orEmpty(),
        revision = revision,
        updatedAt = now.plusSeconds(revision.value),
        updatedBy = actor,
    )

    private fun projection(
        snapshot: PagesDirectorySnapshot,
        documentFormats: Map<cc.monomer.pages.domain.PageId, PageContentFormat> = emptyMap(),
    ): PagesDirectoryProjection = PagesDirectoryProjection(
        snapshot,
        snapshot.pages.associate { page -> page.id to requireNotNull(page.navigationMetadata.title) },
        documentFormats,
    )

    private fun documentPage(): Page {
        val metadata = (PageNavigationMetadata.create("Document", null) as PageNavigationMetadataResult.Valid).metadata
        return (Page.createDocument(workspaceId, metadata, actor, null, now) as PageCreationResult.Created).page
    }

    private fun exchange(): MockServerWebExchange {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build())
        exchange.attributes[RequestContextAttributes.ATTRIBUTE_KEY] = RequestContext(
            userId = userId,
            organizationId = Uuid.parse("019e501f-0000-7000-8000-000000000010"),
            workspaceId = workspaceId,
            bearerToken = "token",
            access = TestAuthorities.member(),
        )
        return exchange
    }
}
