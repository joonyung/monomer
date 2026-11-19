@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore

import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ConnectorType
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunKind
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.StartSemanticExploreRunCommand
import cc.monomer.explore.api.StartSqlExploreRunCommand
import cc.monomer.explore.application.replays
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.explore.domain.ExploreTerminalReceipt
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticQuerySpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import io.mockk.every
import io.mockk.mockk

class ExploreRunDomainTest {
    @Test
    fun `kind variants preserve their own request facts`() {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val workspaceId = WorkspaceId.generate()
        val binding = ExploreConnectorBinding(workspaceId, ConnectorId(Uuid.generateV7()), ConnectorType.POSTGRESQL)
        val sql = ExploreRun(
            id = ExploreRunId.generate(),
            kind = ExploreRun.Kind.Sql,
            idempotencyKey = "sql-run",
            ownerPrincipalId = PrincipalId.generate(),
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = binding,
            requestDigest = ExploreDigest("sha256:${"a".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            visibleResult = null,
            failureCode = null,
            failureMessage = null,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
        val semantic = ExploreRun(
            id = ExploreRunId.generate(),
            kind = ExploreRun.Kind.Semantic(query = SemanticQuerySpec(
                metrics = listOf(SemanticElementName("revenue")),
                groupBys = emptyList(),
                metricTimeRange = null,
                filters = emptyList(),
                orderBy = emptyList(),
                rowLimit = 100,
            ), outputColumnDisplays = listOf(cc.monomer.semantic.api.SemanticOutputColumnDisplay(
                cc.monomer.semantic.api.SemanticResultColumnKey("metric.revenue"),
                cc.monomer.semantic.api.MetricDisplay.Currency(2, cc.monomer.semantic.api.MetricDisplayCurrency.USD),
            )), reportingTimeZone = java.time.ZoneId.of("Asia/Seoul")),
            idempotencyKey = "semantic-run",
            ownerPrincipalId = sql.ownerPrincipalId,
            credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
            agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
            connectorBinding = binding,
            requestDigest = ExploreDigest("sha256:${"b".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            visibleResult = null,
            failureCode = null,
            failureMessage = null,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )

        val runs: List<ExploreRun> = listOf(sql, semantic)
        assertEquals(listOf(ExploreRunKind.SQL, ExploreRunKind.SEMANTIC), runs.map { it.kind.type })
        assertTrue(semantic.asView().kind is cc.monomer.explore.api.ExploreRunView.Kind.Semantic)
        assertEquals(cc.monomer.explore.api.ExploreRunView.Kind.Sql, sql.asView().kind)
        val semanticKind = assertIs<ExploreRun.Kind.Semantic>(semantic.kind)
        assertEquals(semanticKind.query, assertIs<cc.monomer.explore.api.ExploreRunView.Kind.Semantic>(semantic.asView().kind).query)
        assertEquals(java.time.ZoneId.of("Asia/Seoul"), assertIs<cc.monomer.explore.api.ExploreRunView.Kind.Semantic>(semantic.asView().kind).reportingTimeZone)
        val receipt = ExploreTerminalReceipt(
            workspaceId = workspaceId,
            settlementIdempotencyKey = "semantic-failed",
            runId = semantic.id,
            terminalState = ExploreRunState.FAILED,
            failureCode = cc.monomer.explore.api.ExploreRunFailureCode.EXECUTION_FAILED,
            failureMessage = "The warehouse did not complete the query.",
            result = null,
            settledAt = now.plusSeconds(1),
        )
        val settled = semantic.complete(receipt)
        assertEquals(semanticKind, settled.kind)
        assertEquals(semanticKind.outputColumnDisplays, assertIs<ExploreRun.Kind.Semantic>(settled.kind).outputColumnDisplays)
        assertEquals(java.time.ZoneId.of("Asia/Seoul"), assertIs<cc.monomer.explore.api.ExploreRunView.Kind.Semantic>(settled.asView().kind).reportingTimeZone)
        assertEquals(semanticKind.query, assertIs<cc.monomer.explore.api.ExploreRunView.Kind.Semantic>(settled.asView().kind).query)
        assertEquals(1L, settled.revision)
        assertEquals(receipt.settledAt, settled.updatedAt)
        assertFailsWith<IllegalArgumentException> { settled.complete(receipt) }
        assertFailsWith<IllegalArgumentException> { semantic.complete(receipt.copy(runId = ExploreRunId.generate())) }
        assertFailsWith<IllegalArgumentException> { semantic.complete(receipt.copy(workspaceId = WorkspaceId.generate())) }
        assertEquals(null, sql.asView().failureCode)
        assertEquals(null, sql.asView().failureDetail)
        assertEquals(null, sql.asView().credentialSurface)
        assertEquals(cc.monomer.identity.api.CredentialSurface.AGENT, semantic.asView().credentialSurface)
        assertEquals(null, sql.asView().agentClientId)
        assertEquals(cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), semantic.asView().agentClientId)
        assertEquals(sql.ownerPrincipalId, sql.asView().ownerPrincipalId)
        assertEquals(sql.ownerPrincipalId, semantic.asView().ownerPrincipalId)
        assertFailsWith<IllegalArgumentException> {
            sql.copy(credentialSurface = cc.monomer.identity.api.CredentialSurface.WEB, agentClientId = semantic.agentClientId)
        }
    }

    @Test
    fun `same owner connector and intent replay an existing run`() {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val workspaceId = WorkspaceId.generate()
        val ownerId = PrincipalId.generate()
        val binding = ExploreConnectorBinding(
            workspaceId,
            ConnectorId(Uuid.generateV7()),
            ConnectorType.POSTGRESQL,
        )
        val sqlDigest = ExploreDigest("sha256:${"a".repeat(64)}")
        val sql = ExploreRun(
            ExploreRunId.generate(),
            ExploreRun.Kind.Sql,
            "sql-replay",
            ownerId,
            null,
            null,
            binding,
            sqlDigest,
            ExploreRunState.RUNNING,
            null,
            null,
            null,
            0,
            now,
            now,
        )
        val sqlRetry = mockk<StartSqlExploreRunCommand>()
        every { sqlRetry.ownerPrincipalId } returns ownerId
        every { sqlRetry.connectorBinding } returns binding
        every { sqlRetry.requestDigest } returns sqlDigest

        val query = SemanticQuerySpec(
            listOf(SemanticElementName("revenue")),
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            100,
        )
        val semanticDigest = ExploreDigest("sha256:${"b".repeat(64)}")
        val semantic = ExploreRun(
            ExploreRunId.generate(),
            ExploreRun.Kind.Semantic(query, null, java.time.ZoneId.of("Asia/Seoul")),
            "semantic-replay",
            ownerId,
            null,
            null,
            binding,
            semanticDigest,
            ExploreRunState.RUNNING,
            null,
            null,
            null,
            0,
            now,
            now,
        )
        val semanticRetry = mockk<StartSemanticExploreRunCommand>()
        every { semanticRetry.ownerPrincipalId } returns ownerId
        every { semanticRetry.connectorBinding } returns binding
        every { semanticRetry.requestDigest } returns semanticDigest
        every { semanticRetry.query } returns query
        every { semanticRetry.reportingTimeZone } returns java.time.ZoneId.of("UTC")

        assertTrue(sql.replays(sqlRetry))
        assertTrue(semantic.replays(semanticRetry))
        assertFalse(semantic.copy(requestDigest = sqlDigest).replays(sqlRetry))
        assertFalse(sql.copy(requestDigest = semanticDigest).replays(semanticRetry))
        assertFalse(semantic.copy(kind = ExploreRun.Kind.Semantic(query.copy(rowLimit = 101), null, java.time.ZoneId.of("Asia/Seoul"))).replays(semanticRetry))
    }

    @Test
    fun `terminal failure detail is projected from the immutable receipt`() {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val workspaceId = WorkspaceId.generate()
        val binding = ExploreConnectorBinding(workspaceId, ConnectorId(Uuid.generateV7()), ConnectorType.POSTGRESQL)
        val run = ExploreRun(
            id = ExploreRunId.generate(),
            kind = ExploreRun.Kind.Sql,
            idempotencyKey = "failed-run",
            ownerPrincipalId = PrincipalId.generate(),
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = binding,
            requestDigest = ExploreDigest("sha256:${"c".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            visibleResult = null,
            failureCode = null,
            failureMessage = null,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
        val receipt = ExploreTerminalReceipt(
            workspaceId = workspaceId,
            settlementIdempotencyKey = "settlement-failed",
            runId = run.id,
            terminalState = ExploreRunState.FAILED,
            failureCode = cc.monomer.explore.api.ExploreRunFailureCode.EXECUTION_FAILED,
            failureMessage = "The warehouse did not complete the query.",
            result = null,
            settledAt = now.plusSeconds(1),
        )

        val failed = run.complete(receipt)

        assertEquals(receipt.failureMessage, failed.failureMessage)
        assertEquals(receipt.failureMessage, failed.asView().failureDetail)
        assertEquals(receipt.failureCode, failed.asView().failureCode)
        assertEquals(ExploreRunState.FAILED, failed.asView().state)

        val cancelledReceipt = ExploreTerminalReceipt(
            workspaceId = workspaceId,
            settlementIdempotencyKey = "settlement-cancelled",
            runId = run.id,
            terminalState = ExploreRunState.CANCELLED,
            failureCode = cc.monomer.explore.api.ExploreRunFailureCode.CANCELLED_BY_ACTOR,
            failureMessage = "The run was cancelled by its owner.",
            result = null,
            settledAt = now.plusSeconds(1),
        )
        val cancelled = run.complete(cancelledReceipt).asView()
        assertEquals(cancelledReceipt.failureCode, cancelled.failureCode)
        assertEquals(cancelledReceipt.failureMessage, cancelled.failureDetail)
    }

    @Test
    fun `terminal failure detail cannot exceed the public contract limit`() {
        val now = Instant.parse("2026-08-20T00:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            ExploreTerminalReceipt(
                workspaceId = WorkspaceId.generate(),
                settlementIdempotencyKey = "too-long",
                runId = ExploreRunId.generate(),
                terminalState = ExploreRunState.FAILED,
                failureCode = cc.monomer.explore.api.ExploreRunFailureCode.EXECUTION_FAILED,
                failureMessage = "x".repeat(cc.monomer.explore.api.MAX_EXPLORE_FAILURE_DETAIL_LENGTH + 1),
                result = null,
                settledAt = now,
            )
        }
    }


}
