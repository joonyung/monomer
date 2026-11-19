@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class DashboardSessionEntryReaderTest {
    private val workspace = WorkspaceId.generate()
    private val dashboard = DashboardId.generate()
    private val newest = DashboardSessionId.generate()
    private val older = DashboardSessionId.generate()
    private val at = Instant.parse("2026-09-21T00:00:00Z")
    private val owner = mockk<DashboardOwnerPort>()
    private val artifacts = mockk<ExecutionArtifactStore>()
    private val reader = DashboardSessionEntryReader(owner, artifacts, Clock.fixed(at, ZoneOffset.UTC))
    private fun session(id: DashboardSessionId): DashboardOwnerSession = mockk {
        io.mockk.every { sessionId } returns id
    }
    private fun candidates(ids: List<DashboardSessionId>) {
        coEvery { owner.listSessions(ListDashboardSessionsCommand(workspace, dashboard, null, 20)) } returns
            DashboardOwnerOutcome.Success(DashboardOwnerSessionPage(ids.map { id ->
                DashboardOwnerSessionSummary(id, dashboard, emptyMap(), emptyList(), at, at, null)
            }, null))
        ids.forEach { id -> coEvery { owner.readSession(workspace, dashboard, id) } returns DashboardOwnerOutcome.Success(session(id)) }
    }
    private fun inspected(id: DashboardSessionId, queries: Set<String>, count: Int, exhausted: Boolean) {
        coEvery { owner.readInitialSessionResults(match { it.sessionId == id }) } returns
            DashboardOwnerOutcome.Success(DashboardOwnerInitialSessionResults(queries, emptyMap(), count, exhausted))
    }

    private fun verifyAll(results: List<DashboardOwnerResultLookup>) = Unit

    private fun results(count: Int): Map<String, DashboardOwnerResultLookup> = (0 until count).associate { index ->
        val run = QueryRunId.generate()
        "query$index" to DashboardOwnerResultLookup(
            DashboardOwnerQueryRun(run, dashboard, newest, "query$index", "SEMANTIC", cc.monomer.connector.api.ConnectorId.generate(), "SUCCEEDED", true, null, null, listOf("metric.value"), at, at, null, null, null, outputColumnDisplays = null, reportingTimeZone = null),
            cc.monomer.execution.api.ArrowResultManifest("artifacts/$index", 100u, "etag", 1u, 1, "application/vnd.apache.arrow.file"),
        )
    }

    @Test fun `member entry projection includes connector, the person who started it, and safe failure message`() {
        val actorId = "01900000-0000-7000-8000-00000000a11c"
        val run = results(1).getValue("query0").queryRun.copy(failureCode = "QUERY_FAILED", failureMessage = "PRIVATE DIAGNOSTIC", actorId = actorId,
            credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT, agentClientId = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE)
        val provenance = DashboardProvenance(mapOf(actorId to "Mina Park"),
            mapOf(cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE to cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE_APP))
        assertEquals(QueryRunResponse(run.queryRunId.toString(), run.queryName, run.queryType, run.connectorId.toString(), run.state,
            run.resultAvailable, run.failureCode, dashboardFailureMessage(run.failureCode, run.failureMessage), run.createdAt, run.updatedAt,
            run.outputColumnNames, DashboardActorResponse(actorId, "Mina Park"), "AGENT", cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE_APP),
            run.entryResponse(provenance))
        assertEquals(null, run.copy(actorId = null).entryResponse(provenance).startedBy)
    }

    @Test fun `seven equal artifact checks keep at most four in flight`() = runTest {
        candidates(listOf(newest))
        val results = results(7)
        coEvery { owner.readInitialSessionResults(any()) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerInitialSessionResults(results.keys, results, 7, false))
        var active = 0
        var peak = 0
        coEvery { artifacts.head(any(), any()) } coAnswers {
            active++
            peak = maxOf(peak, active)
            try {
                kotlinx.coroutines.delay(100)
                cc.monomer.execution.api.ExecutionArtifactHead(firstArg(), 100u, "etag", at)
            } finally { active-- }
        }
        val start = testScheduler.currentTime
        var globalChecks = 0
        var batchChecks = 0
        assertEquals(7, reader.select(workspace, dashboard, null, { globalChecks++ }, { lookups ->
            batchChecks++
            assertEquals(results.values.toList(), lookups)
            verifyAll(lookups)
        }).initialQueryRuns.size)
        assertEquals(3, globalChecks)
        assertEquals(1, batchChecks)
        assertEquals(200, testScheduler.currentTime - start)
        assertEquals(4, peak)
        assertEquals(0, active)
    }

    @Test fun `a slow artifact check never holds back later checks beyond four in flight`() = runTest {
        candidates(listOf(newest))
        val results = results(7)
        coEvery { owner.readInitialSessionResults(any()) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerInitialSessionResults(results.keys, results, 7, false))
        var active = 0
        var peak = 0
        val completed = mutableListOf<String>()
        coEvery { artifacts.head(any(), any()) } coAnswers {
            active++
            peak = maxOf(peak, active)
            try {
                kotlinx.coroutines.delay(if (firstArg<String>() == "artifacts/0") 300 else 100)
                completed += firstArg<String>()
                cc.monomer.execution.api.ExecutionArtifactHead(firstArg(), 100u, "etag", at)
            } finally { active-- }
        }
        val start = testScheduler.currentTime
        val entry = reader.select(workspace, dashboard, null, {}, ::verifyAll)
        assertEquals(300, testScheduler.currentTime - start)
        assertEquals(4, peak)
        assertEquals("artifacts/0", completed.last())
        assertEquals(results.keys.toList(), entry.initialQueryRuns.keys.toList())
        assertEquals(results.mapValues { it.value.queryRun }, entry.initialQueryRuns)
    }

    @Test fun `deadline retains completed result and cancels unfinished sibling heads`() = runTest {
        candidates(listOf(newest, older))
        val results = results(4)
        coEvery { owner.readInitialSessionResults(any()) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerInitialSessionResults(results.keys, results, 4, false))
        var finished = 0
        coEvery { artifacts.head(any(), any()) } coAnswers {
            try {
                if (firstArg<String>() == "artifacts/0") kotlinx.coroutines.delay(10)
                else kotlinx.coroutines.awaitCancellation()
                cc.monomer.execution.api.ExecutionArtifactHead(firstArg(), 100u, "etag", at)
            } finally { finished++ }
        }
        val entry = reader.select(workspace, dashboard, null, {}, ::verifyAll)
        assertEquals(mapOf("query0" to results.getValue("query0").queryRun), entry.initialQueryRuns)
        assertEquals(4, finished)
        assertEquals(2_000, testScheduler.currentTime)
        coVerify(exactly = 0) { owner.readInitialSessionResults(match { it.sessionId == older }) }
    }

    @Test fun `caller timeout propagates and cancels every active artifact check`() = runTest {
        candidates(listOf(newest))
        val results = results(4)
        coEvery { owner.readInitialSessionResults(any()) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerInitialSessionResults(results.keys, results, 4, false))
        var cancelled = 0
        coEvery { artifacts.head(any(), any()) } coAnswers {
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled++ }
        }
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            kotlinx.coroutines.withTimeout(100) { reader.select(workspace, dashboard, null, {}, ::verifyAll) }
        }
        assertEquals(4, cancelled)
    }

    @Test fun `empty session list never plans or executes`() = runTest {
        candidates(emptyList())
        assertNull(reader.select(workspace, dashboard, null, {}, ::verifyAll).session)
        coVerify(exactly = 0) { owner.readInitialSessionResults(any()) }
        coVerify(exactly = 0) { owner.resolvePublicSession(any()) }
    }

    @Test fun `older static session is ready while latest has missing query`() = runTest {
        candidates(listOf(newest, older))
        inspected(newest, setOf("missing"), 1, false)
        inspected(older, emptySet(), 0, false)
        assertEquals(older, reader.select(workspace, dashboard, null, {}, ::verifyAll).session?.sessionId)
        coVerify { owner.readInitialSessionResults(match { it.sessionId == older && it.remainingQueryCount == 63 && it.deadline == at.plusSeconds(2) }) }
        coVerify(exactly = 0) { artifacts.head(any(), any()) }
        coVerify(exactly = 0) { owner.resolvePublicSession(any()) }
    }

    @Test fun `budget exhaustion falls back to latest without next candidate`() = runTest {
        candidates(listOf(newest, older))
        inspected(newest, setOf("missing"), 64, true)
        assertEquals(newest, reader.select(workspace, dashboard, null, {}, ::verifyAll).session?.sessionId)
        coVerify(exactly = 0) { owner.readInitialSessionResults(match { it.sessionId == older }) }
    }

    @Test fun `explicit incomplete session is never replaced or listed`() = runTest {
        coEvery { owner.readSession(workspace, dashboard, older) } returns DashboardOwnerOutcome.Success(session(older))
        inspected(older, setOf("missing"), 1, false)
        assertEquals(older, reader.select(workspace, dashboard, older, {}, ::verifyAll).session?.sessionId)
        coVerify(exactly = 0) { owner.listSessions(any()) }
    }

    @Test fun `valid initial artifact is returned and corrupt latest equivalent does not count ready`() = runTest {
        candidates(listOf(newest, older))
        val run = QueryRunId.generate()
        val manifest = cc.monomer.execution.api.ArrowResultManifest("artifacts/result", 100u, "etag", 1u, 1, "application/vnd.apache.arrow.file")
        val lookup = DashboardOwnerResultLookup(
            DashboardOwnerQueryRun(run, dashboard, newest, "query", "SEMANTIC", cc.monomer.connector.api.ConnectorId.generate(), "SUCCEEDED", true, null, null, listOf("metric.value"), at, at, null, null, null, outputColumnDisplays = null, reportingTimeZone = null),
            manifest,
        )
        coEvery { owner.readInitialSessionResults(match { it.sessionId == newest }) } returns
            DashboardOwnerOutcome.Success(DashboardOwnerInitialSessionResults(setOf("query"), mapOf("query" to lookup), 1, false))
        coEvery { artifacts.head(manifest.objectKey, at.plusSeconds(2)) } returns
            cc.monomer.execution.api.ExecutionArtifactHead(manifest.objectKey, 100u, "\"etag\"", at)
        assertEquals(mapOf("query" to lookup.queryRun), reader.select(workspace, dashboard, null, {}, ::verifyAll).initialQueryRuns)
        coEvery { artifacts.head(manifest.objectKey, at.plusSeconds(2)) } returns
            cc.monomer.execution.api.ExecutionArtifactHead(manifest.objectKey, 99u, "etag", at)
        inspected(older, emptySet(), 0, false)
        assertEquals(older, reader.select(workspace, dashboard, null, {}, ::verifyAll).session?.sessionId)
        assertEquals(emptyMap(), reader.select(workspace, dashboard, newest, {}, ::verifyAll).initialQueryRuns)
    }

    @Test fun `final revocation denies results after artifact verification`() = runTest {
        candidates(listOf(newest))
        val results = results(2)
        coEvery { owner.readInitialSessionResults(any()) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerInitialSessionResults(results.keys, results, 2, false))
        coEvery { artifacts.head(any(), any()) } coAnswers {
            cc.monomer.execution.api.ExecutionArtifactHead(firstArg(), 100u, "etag", at)
        }
        var checks = 0
        val denied = assertFailsWith<ResponseStatusException> {
            reader.select(workspace, dashboard, newest, {
                if (++checks == 3) throw ResponseStatusException(HttpStatus.FORBIDDEN)
            }, ::verifyAll)
        }
        assertEquals(HttpStatus.FORBIDDEN, denied.statusCode)
    }

    @Test fun `batch global denial propagates without artifact reads`() = runTest {
        candidates(listOf(newest))
        val results = results(2)
        coEvery { owner.readInitialSessionResults(any()) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerInitialSessionResults(results.keys, results, 2, false))
        val denied = assertFailsWith<ResponseStatusException> {
            reader.select(workspace, dashboard, newest, {}, { throw ResponseStatusException(HttpStatus.FORBIDDEN) })
        }
        assertEquals(HttpStatus.FORBIDDEN, denied.statusCode)
        coVerify(exactly = 0) { artifacts.head(any(), any()) }
    }

    @Test fun `workspace revocation is never treated as missing result`() = runTest {
        candidates(listOf(newest))
        inspected(newest, emptySet(), 0, false)
        var checks = 0
        val error = assertFailsWith<ResponseStatusException> {
            reader.select(workspace, dashboard, null, {
                if (++checks == 2) throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }, ::verifyAll)
        }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
    }
}
