package cc.monomer.control.agent

import cc.monomer.agent.api.*
import io.mockk.coEvery
import io.mockk.coVerify
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentSearchOutcome
import cc.monomer.agent.api.AgentSearchRequest
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentSearchAdapterTest {
    private val caller = mockk<AgentWorkspaceCaller> {
        every { workspaceId } returns WorkspaceId.generate()
    }
    private val index = mockk<SearchIndex>()
    private val transactions = SnapshotRunner()
    private val pagesOwner = mockk<AgentPagesOwnerPort>()
    private var membership = true
    private var scopePresent = true
    private val scopeCalls = mutableListOf<AgentResourcePath>()
    private var hydrate: suspend (List<AgentResourcePath>) -> List<AgentResourceListingItem> = { paths ->
        paths.map { AgentResourceListingItem.Metric(it, it.value.substringAfterLast('/'), emptyList()) }
    }
    private val adapter = AgentSearchAdapter(
        searchIndex = index,
        transactionRunner = transactions,
        admitted = { _, _ -> assertThat(transactions.active).isTrue(); membership },
        scopeExists = { actualCaller, path ->
            assertThat(actualCaller).isSameAs(caller)
            assertThat(transactions.active).isTrue()
            scopeCalls += path
            scopePresent
        },
        currentListings = { actualCaller, paths ->
            assertThat(actualCaller).isSameAs(caller)
            assertThat(transactions.active).isTrue()
            hydrate(paths)
        },
        pagesOwner = pagesOwner,
    )

    @Test
    fun `unsupported scopes and tokenless queries do not open a database snapshot`() = runBlocking<Unit> {
        listOf(
            request(path = "workspace/metrics/orders"),
            request(queries = listOf("orders", "--- 😀")),
        ).forEach {
            val result = adapter.search(caller, it) as AgentSearchOutcome.Rejected
            assertThat(result.problem.status).isEqualTo(AgentWorkspaceProblemStatus.BAD_REQUEST)
        }
        assertThat(transactions.calls).isZero()
        assertThat(scopeCalls).isEmpty()
        verify { index wasNot Called }
    }

    @Test
    fun `lost membership and missing source scope stop before searching`() = runBlocking<Unit> {
        membership = false
        val denied = adapter.search(caller, request()) as AgentSearchOutcome.Rejected
        assertThat(denied.problem.status).isEqualTo(AgentWorkspaceProblemStatus.FORBIDDEN)
        assertThat(scopeCalls).isEmpty()
        membership = true
        scopePresent = false
        assertThat(adapter.search(caller, request())).isEqualTo(AgentSearchOutcome.NotFound)
        assertThat(scopeCalls).containsExactly(AgentResourcePath.parse("workspace"))
        verify { index wasNot Called }
    }

    @Test
    fun `queries execute independently in order including duplicates in one snapshot`() = runBlocking<Unit> {
        val observed = mutableListOf<String>()
        every { index.candidates(any(), "workspace", setOf(SearchResourceKind.METRIC), any(), SearchMatch.WHOLE_TOKENS, 100, 0) } answers {
            assertThat(transactions.active).isTrue()
            observed += arg<String>(3)
            if (arg<String>(3) == "missing") emptyList() else listOf("workspace/metrics/orders")
        }
        val outcome = adapter.search(caller, request(listOf("orders", "missing", "orders"))) as AgentSearchOutcome.Found
        assertThat(observed).containsExactly("orders", "missing", "orders")
        assertThat(outcome.response.results.map { it.query }).isEqualTo(observed)
        assertThat(outcome.response.results.map { it.items.size }).containsExactly(1, 0, 1)
        assertThat(outcome.response.results.first().items).isEqualTo(outcome.response.results.last().items)
        assertThat(transactions.calls).isEqualTo(1)
        assertThat(scopeCalls).hasSize(1)
    }

    @Test
    fun `hydration removes denied missing and mismatched kinds and continues candidates until limit`() = runBlocking<Unit> {
        val firstPage = (0 until 100).map { "workspace/metrics/metric$it" }
        val secondPage = listOf("workspace/metrics/metric100", "workspace/metrics/metric101", "workspace/metrics/metric102")
        every { index.candidates(any(), any(), any(), any(), SearchMatch.WHOLE_TOKENS, 100, 0) } returns firstPage
        every { index.candidates(any(), any(), any(), any(), SearchMatch.WHOLE_TOKENS, 100, 100) } returns secondPage
        hydrate = { paths ->
            if (paths.first().value == firstPage.first()) {
                listOf(AgentResourceListingItem.SemanticModel(paths.first(), "wrong kind"))
            } else {
                paths.reversed().map { AgentResourceListingItem.Metric(it, "current name", emptyList()) }
            }
        }
        val result = adapter.search(caller, request(limit = 2)) as AgentSearchOutcome.Found
        assertThat(result.response.results.single().items.map { it.path.value }).containsExactly(secondPage[0], secondPage[1])
        assertThat(result.response.results.single().items.map { (it as AgentResourceListingItem.Metric).name })
            .containsExactly("current name", "current name")
        verify(exactly = 1) { index.candidates(any(), any(), any(), any(), SearchMatch.WHOLE_TOKENS, 100, 100) }
    }

    @Test
    fun `coroutine cancellation propagates instead of becoming an owner failure`() {
        every { index.candidates(any(), any(), any(), "orders", SearchMatch.WHOLE_TOKENS, 100, 0) } throws
            kotlinx.coroutines.CancellationException("request cancelled")
        assertThrows<kotlinx.coroutines.CancellationException> {
            runBlocking { adapter.search(caller, request()) }
        }
    }

    @Test
    fun `later query failure aborts whole response without partial results`() = runBlocking<Unit> {
        every { index.candidates(any(), any(), any(), "orders", SearchMatch.WHOLE_TOKENS, 100, 0) } returns listOf("workspace/metrics/orders")
        every { index.candidates(any(), any(), any(), "broken", SearchMatch.WHOLE_TOKENS, 100, 0) } throws IllegalStateException("source unavailable")
        val result = adapter.search(caller, request(listOf("orders", "broken"))) as AgentSearchOutcome.Rejected
        assertThat(result.problem.code).isEqualTo("OWNER_UNAVAILABLE")
        assertThat(result.problem.status).isEqualTo(AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE)
        assertThat(transactions.completed).isZero()
        assertThat(transactions.active).isFalse()
    }

    @Test
    fun `Page search reads authorized current tree once and matches all title words without the metadata index`() = runBlocking<Unit> {
        val page = page("01942b04-6e00-7000-8000-000000000010", "Quarterly Revenue Report")
        val other = page("01942b04-6e00-7000-8000-000000000011", "Reports", "Quarterly Revenue Report")
        coEvery { pagesOwner.read(caller, any()) } answers {
            assertThat(transactions.active).isTrue()
            assertThat(secondArg<AgentReadRequest>().path.value).isEqualTo("workspace/pages/tree")
            tree(listOf(page, other))
        }
        val request = AgentSearchRequest(AgentResourcePath.parse("workspace/pages"), listOf("REVEN quart", "missing", "REVEN quart"), null, 20)
        val result = adapter.search(caller, request) as AgentSearchOutcome.Found
        assertThat(result.response.results.map { it.items }).containsExactly(listOf(page), emptyList(), listOf(page))
        coVerify(exactly = 1) { pagesOwner.read(caller, any()) }
        verify { index wasNot Called }
        coEvery { pagesOwner.read(caller, any()) } returns tree(emptyList())
        val afterDeletion = adapter.search(caller, request) as AgentSearchOutcome.Found
        assertThat(afterDeletion.response.results.flatMap { it.items }).isEmpty()
    }

    @Test
    fun `Page search uses Web whitespace including Unicode spaces line separators and BOM`() = runBlocking<Unit> {
        val page = page("01942b04-6e00-7000-8000-000000000010", "Weekly Revenue")
        coEvery { pagesOwner.read(caller, any()) } returns tree(listOf(page))
        val queries = listOf("\u00a0", "\u2003", "\u202f", "\u2028", "\u2029", "\ufeff")
            .map { whitespace -> "${whitespace}Weekly${whitespace}${whitespace}Revenue${whitespace}" }
        for (batch in queries.chunked(5)) {
            val request = AgentSearchRequest(AgentResourcePath.parse("workspace/pages"), batch, null, 20)
            val result = adapter.search(caller, request) as AgentSearchOutcome.Found
            assertThat(result.response.results.map { it.items }).containsExactlyElementsOf(batch.map { listOf(page) })
        }
        val nonWebWhitespace = AgentSearchRequest(AgentResourcePath.parse("workspace/pages"), listOf("Weekly\u0085Revenue", "\u001cWeekly Revenue\u001c"), null, 20)
        assertThat((adapter.search(caller, nonWebWhitespace) as AgentSearchOutcome.Found).response.results.flatMap { it.items }).isEmpty()
        verify { index wasNot Called }
    }

    @Test
    fun `mixed searches append Page matches within the shared limit and kinds stay scoped`() = runBlocking<Unit> {
        val page = page("01942b04-6e00-7000-8000-000000000010", "Orders report")
        coEvery { pagesOwner.read(caller, any()) } returns tree(listOf(page))
        every { index.candidates(any(), "workspace", setOf(SearchResourceKind.METRIC), any(), SearchMatch.WHOLE_TOKENS, 100, 0) } returns listOf("workspace/metrics/orders")
        val mixed = request(limit = 2).copy(kinds = setOf(AgentResourceKind.PAGE, AgentResourceKind.METRIC))
        assertThat((adapter.search(caller, mixed) as AgentSearchOutcome.Found).response.results.single().items.map { it.kind })
            .containsExactly(AgentResourceKind.METRIC, AgentResourceKind.PAGE)
        assertThat((adapter.search(caller, mixed.copy(limitPerQuery = 1)) as AgentSearchOutcome.Found).response.results.single().items.map { it.kind })
            .containsExactly(AgentResourceKind.METRIC)
        val pageOnly = mixed.copy(kinds = setOf(AgentResourceKind.PAGE))
        assertThat((adapter.search(caller, pageOnly) as AgentSearchOutcome.Found).response.results.single().items).containsExactly(page)
        val unrelated = pageOnly.copy(path = AgentResourcePath.parse("workspace/metrics"))
        assertThat((adapter.search(caller, unrelated) as AgentSearchOutcome.Found).response.results.single().items).isEmpty()
        coVerify(exactly = 3) { pagesOwner.read(caller, any()) }
    }

    @Test
    fun `omitted kinds includes Pages at root and unrelated kinds in Pages scope remain empty`() = runBlocking<Unit> {
        val page = page("01942b04-6e00-7000-8000-000000000010", "Orders report")
        coEvery { pagesOwner.read(caller, any()) } returns tree(listOf(page))
        every { index.candidates(any(), "workspace", SearchResourceKind.entries.toSet(), any(), SearchMatch.WHOLE_TOKENS, 100, 0) } returns emptyList()
        assertThat((adapter.search(caller, request().copy(kinds = null)) as AgentSearchOutcome.Found).response.results.single().items).containsExactly(page)
        assertThat((adapter.search(caller, request(path = "workspace/pages")) as AgentSearchOutcome.Found).response.results.single().items).isEmpty()
        coVerify(exactly = 1) { pagesOwner.read(caller, any()) }
        verify(exactly = 1) { index.candidates(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Page owner rejection and failure never become empty or partial success`() = runBlocking<Unit> {
        val request = request().copy(kinds = setOf(AgentResourceKind.PAGE))
        coEvery { pagesOwner.read(caller, any()) } returns AgentResourceOutcome.Forbidden
        assertThat((adapter.search(caller, request) as AgentSearchOutcome.Rejected).problem.status).isEqualTo(AgentWorkspaceProblemStatus.FORBIDDEN)
        coEvery { pagesOwner.read(caller, any()) } returns AgentResourceOutcome.NotFound
        assertThat(adapter.search(caller, request)).isEqualTo(AgentSearchOutcome.NotFound)
        coEvery { pagesOwner.read(caller, any()) } returns AgentResourceOutcome.Unavailable
        assertThat((adapter.search(caller, request) as AgentSearchOutcome.Rejected).problem.status).isEqualTo(AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE)
        coEvery { pagesOwner.read(caller, any()) } throws kotlinx.coroutines.CancellationException("cancelled")
        assertThrows<kotlinx.coroutines.CancellationException> { adapter.search(caller, request) }
        verify { index wasNot Called }
    }

    private fun page(id: String, title: String, description: String? = null) = AgentResourceListingItem.Page(
        AgentResourcePath.parse("workspace/pages/$id"),
        AgentPageResourceBody(AgentPageKind.FOLDER, title, description, title, null, null, null, null, null, null, null),
    )
    private fun tree(pages: List<AgentResourceListingItem.Page>) = AgentResourceOutcome.Found(AgentResourceEnvelope.PagesTree(
        AgentResourcePath.parse("workspace/pages/tree"), AgentRevision(1), AgentPagesTreeResourceBody(pages, emptyList()), emptyList(),
    ))

    private fun request(queries: List<String> = listOf("orders"), path: String = "workspace", limit: Int = 1) =
        AgentSearchRequest(AgentResourcePath.parse(path), queries, setOf(AgentResourceKind.METRIC), limit)

    private class SnapshotRunner : ConsistentSnapshotTransactionRunner {
        var calls = 0
        var completed = 0
        var active = false
        override suspend fun <T> run(action: suspend () -> T): T {
            calls++
            active = true
            return try {
                action().also { completed++ }
            } finally {
                active = false
            }
        }
    }
}
